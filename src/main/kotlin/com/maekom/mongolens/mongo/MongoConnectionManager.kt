package com.maekom.mongolens.mongo

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.maekom.mongolens.config.MongoConnectionProfile
import com.mongodb.ConnectionString
import com.mongodb.ExplainVerbosity
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCommandException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.Filters
import org.bson.BsonDocument
import org.bson.BsonObjectId
import org.bson.BsonValue
import org.bson.Document
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class IndexScanType {
    IXSCAN,
    COLLSCAN,
    NO_FILTER_PREVIEW,
    UNKNOWN
}

data class TrailingQueryMetadata(
    val totalCount: Long?,
    val isApproximateCount: Boolean,
    val scanType: IndexScanType?,
    val indexName: String?,
    val explainError: String?,
    val detailedIndexes: List<CollectionIndexInfo>?
)

data class CollectionIndexInfo(
    val name: String,
    val keys: LinkedHashMap<String, Any>,
    val keyDisplay: String,
    val unique: Boolean = false,
    val sparse: Boolean = false,
    val ttlSeconds: Long? = null,
    val sizeBytes: Long? = null
) {
    fun summaryLine(): String = "$name: { $keyDisplay }"

    fun attributesDisplay(): String {
        val attrs = mutableListOf<String>()
        if (unique) attrs.add("UNIQUE")
        if (sparse) attrs.add("SPARSE")
        if (ttlSeconds != null) attrs.add("TTL (${ttlSeconds}s)")
        return if (attrs.isEmpty()) "-" else attrs.joinToString(", ")
    }

    fun firstKeyField(): String? = keys.keys.firstOrNull()
}

data class QueryExecutionResult(
    val documents: List<BsonDocument>,
    val totalCount: Long, // -1 means count timed out / unknown
    val isApproximateCount: Boolean,
    val elapsedMs: Long,
    val scanType: IndexScanType,
    val indexName: String?,
    val sampledFields: List<String>,
    val sampledFieldTypes: Map<String, SmartInValueType> = emptyMap(),
    val hasNextPage: Boolean? = null,
    val collectionIndexes: List<String> = emptyList(),
    val detailedIndexes: List<CollectionIndexInfo> = emptyList(),
    val explainError: String? = null,
    val unlimitedCapHit: Boolean = false
)

class MongoConnectionManager(project: Project) : Disposable {
    companion object {
        private const val META_CACHE_TTL_MS = 60_000L
        private const val META_QUERY_MAX_TIME_MS = 1_500L
    }

    private data class CachedIndexEntry(val fetchedAtMs: Long, val indexes: List<CollectionIndexInfo>)
    private data class CachedCountEntry(val fetchedAtMs: Long, val count: Long)
    private data class ExplainOutcome(val scanType: IndexScanType, val indexName: String?, val errorMessage: String?)

    private val clients = ConcurrentHashMap<String, MongoClient>()
    private val clientUris = ConcurrentHashMap<String, String>()
    private val unlockedReadOnlyProfiles = ConcurrentHashMap.newKeySet<String>()
    private val indexCache = ConcurrentHashMap<String, CachedIndexEntry>()
    private val estimatedCountCache = ConcurrentHashMap<String, CachedCountEntry>()
    private val disposed = AtomicBoolean(false)

    init {
        Disposer.register(project, this)
    }

    fun isEffectivelyReadOnly(profile: MongoConnectionProfile): Boolean {
        return profile.readOnly && !unlockedReadOnlyProfiles.contains(profile.id)
    }

    fun isSessionUnlocked(profileId: String): Boolean {
        return unlockedReadOnlyProfiles.contains(profileId)
    }

    fun unlockForSession(profileId: String) {
        unlockedReadOnlyProfiles.add(profileId)
    }

    fun relockForSession(profileId: String) {
        unlockedReadOnlyProfiles.remove(profileId)
    }

    fun getOrCreateClient(profile: MongoConnectionProfile): MongoClient {
        check(!disposed.get()) { "MongoConnectionManager is already disposed" }
        val uri = profile.effectiveConnectionUri()
        val existingUri = clientUris[profile.id]
        val existingClient = clients[profile.id]
        if (existingClient != null && existingUri == uri) {
            return existingClient
        }
        return reconnectInternal(profile, uri)
    }

    fun reconnect(profile: MongoConnectionProfile): Long {
        val uri = profile.effectiveConnectionUri()
        reconnectInternal(profile, uri)
        return ping(profile)
    }

    private fun reconnectInternal(profile: MongoConnectionProfile, uri: String): MongoClient {
        closeClient(profile.id)
        val settings = buildClientSettings(uri)
        val newClient = MongoClients.create(settings)
        val prev = clients.put(profile.id, newClient)
        clientUris[profile.id] = uri
        if (prev != null && prev !== newClient) {
            safeClose(prev)
        }
        return newClient
    }

    fun closeClient(profileId: String) {
        clientUris.remove(profileId)
        indexCache.keys.removeIf { it.startsWith("$profileId:") }
        estimatedCountCache.keys.removeIf { it.startsWith("$profileId:") }
        val old = clients.remove(profileId)
        if (old != null) {
            safeClose(old)
        }
    }

    fun invalidateCollectionMetaCache(profileId: String, database: String, collection: String) {
        val cacheKey = "$profileId:$database:$collection"
        indexCache.remove(cacheKey)
        estimatedCountCache.remove(cacheKey)
    }

    fun ping(profile: MongoConnectionProfile): Long {
        val start = System.nanoTime()
        val client = getOrCreateClient(profile)
        val targetDb = profile.extractDefaultDatabase() ?: "admin"
        client.getDatabase(targetDb).runCommand(Document("ping", 1))
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    fun testUriDirect(uri: String, defaultDb: String?): Long {
        val start = System.nanoTime()
        val client = MongoClients.create(buildClientSettings(uri))
        try {
            val dbName = defaultDb?.takeIf { it.isNotBlank() } ?: "admin"
            client.getDatabase(dbName).runCommand(Document("ping", 1))
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        } finally {
            safeClose(client)
        }
    }

    fun listDatabases(profile: MongoConnectionProfile): List<String> {
        val client = getOrCreateClient(profile)
        return try {
            val names = client.listDatabaseNames().into(mutableListOf())
            if (names.isEmpty()) {
                listOfNotNull(profile.extractDefaultDatabase())
            } else {
                names.sorted()
            }
        } catch (e: MongoCommandException) {
            // Restricted database user without listDatabases privilege: fall back to configured database
            val fallbackDb = profile.extractDefaultDatabase()
            if (fallbackDb != null) listOf(fallbackDb) else throw e
        }
    }

    fun listCollections(profile: MongoConnectionProfile, database: String): List<String> {
        val client = getOrCreateClient(profile)
        return client.getDatabase(database)
            .listCollectionNames()
            .into(mutableListOf())
            .sorted()
    }

    fun getEstimatedCount(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        forceRefresh: Boolean = false
    ): Long {
        val cacheKey = "${profile.id}:$database:$collection"
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = estimatedCountCache[cacheKey]
            if (cached != null && now - cached.fetchedAtMs < META_CACHE_TTL_MS) {
                return cached.count
            }
        }
        val client = getOrCreateClient(profile)
        val count = client.getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
            .estimatedDocumentCount()
        estimatedCountCache[cacheKey] = CachedCountEntry(now, count)
        return count
    }

    fun getEstimatedCountSafe(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        forceRefresh: Boolean = false
    ): Long? = runCatching {
        getEstimatedCount(profile, database, collection, forceRefresh)
    }.getOrNull()

    fun getCachedEstimatedCountIfWarm(
        profileId: String,
        database: String,
        collection: String
    ): Long? {
        val cached = estimatedCountCache["$profileId:$database:$collection"] ?: return null
        return if (System.currentTimeMillis() - cached.fetchedAtMs < META_CACHE_TTL_MS) cached.count else null
    }

    fun sampleFieldNames(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        sampleSize: Int = 30
    ): List<String> = sampleSchema(profile, database, collection, sampleSize).first

    fun sampleSchema(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        sampleSize: Int = 30
    ): Pair<List<String>, Map<String, SmartInValueType>> {
        val safeSample = sampleSize.coerceIn(1, 50)
        val client = getOrCreateClient(profile)
        val docs = client.getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
            .find()
            .limit(safeSample)
            .batchSize(safeSample)
            .maxTime(3, TimeUnit.SECONDS)
            .into(mutableListOf())
        return extractFieldSchema(docs)
    }

    data class SampledTopLevelSchema(
        val documentCount: Int,
        val fieldTypes: LinkedHashMap<String, String>,
        val joinFieldTypes: LinkedHashMap<String, String> = fieldTypes
    )

    /**
     * Samples up to `sampleSize` documents (default 10) and extracts:
     * - `fieldTypes`: top-level keys with their most frequent non-null BSON type category
     * - `joinFieldTypes`: top-level keys PLUS nested Object & Array-of-Objects dot-notation paths
     *   (e.g. `items.product_id`) for Join View key matching and WHERE AND conditions.
     */
    fun sampleCollectionTopLevelSchema(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        sampleSize: Int = 10
    ): SampledTopLevelSchema {
        val safeSample = sampleSize.coerceIn(1, 50)
        val client = getOrCreateClient(profile)
        val docs = client.getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
            .find()
            .limit(safeSample)
            .batchSize(safeSample)
            .maxTime(5, TimeUnit.SECONDS)
            .into(mutableListOf())
        return SampledTopLevelSchema(
            documentCount = docs.size,
            fieldTypes = extractTopLevelTypeCategories(docs),
            joinFieldTypes = extractJoinKeyTypeCategories(docs)
        )
    }

    /**
     * Samples top-level fields and their normalized BSON type categories (ObjectId, String, Number, Date, Boolean, Object, Array)
     * for Join View field matching & type-mismatch warning detection.
     */
    fun sampleCollectionTopLevelTypes(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        sampleSize: Int = 10
    ): LinkedHashMap<String, String> = sampleCollectionTopLevelSchema(profile, database, collection, sampleSize).fieldTypes

    fun extractTopLevelTypeCategories(docs: List<BsonDocument>): LinkedHashMap<String, String> {
        val typeCountsByField = linkedMapOf<String, LinkedHashMap<String, Int>>()
        for (doc in docs) {
            for ((k, v) in doc) {
                val counts = typeCountsByField.computeIfAbsent(k) { linkedMapOf() }
                val cat = if (v.isNull) "Null" else categorizeBsonType(v)
                counts[cat] = (counts[cat] ?: 0) + 1
            }
        }
        return resolveWinningTypeCategories(typeCountsByField)
    }

    /**
     * Recursively collects top-level keys plus nested Object and Array-of-Objects dot-notation paths
     * (up to depth 2, e.g. `user.id`, `items.product_id`) along with their dominant BSON type category.
     */
    fun extractJoinKeyTypeCategories(docs: List<BsonDocument>): LinkedHashMap<String, String> {
        val typeCountsByField = linkedMapOf<String, LinkedHashMap<String, Int>>()

        fun record(path: String, v: BsonValue) {
            val counts = typeCountsByField.computeIfAbsent(path) { linkedMapOf() }
            val cat = if (v.isNull) "Null" else categorizeBsonType(v)
            counts[cat] = (counts[cat] ?: 0) + 1
        }

        fun walkDoc(doc: BsonDocument, prefix: String, depth: Int) {
            for ((k, v) in doc) {
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                record(path, v)
                if (depth >= 2) continue
                when {
                    v.isDocument -> walkDoc(v.asDocument(), path, depth + 1)
                    v.isArray -> {
                        for (elem in v.asArray().take(5)) {
                            if (elem.isDocument) {
                                walkDoc(elem.asDocument(), path, depth + 1)
                            }
                        }
                    }
                }
            }
        }

        for (doc in docs) {
            walkDoc(doc, "", 0)
        }
        return resolveWinningTypeCategories(typeCountsByField)
    }

    private fun resolveWinningTypeCategories(
        typeCountsByField: LinkedHashMap<String, LinkedHashMap<String, Int>>
    ): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        for ((field, counts) in typeCountsByField) {
            val nonNullWinner = counts.entries
                .filter { it.key != "Null" }
                .maxByOrNull { it.value }
                ?.key
            out[field] = nonNullWinner ?: "Null"
        }
        return out
    }

    private fun categorizeBsonType(v: BsonValue): String = when {
        v.isObjectId -> "ObjectId"
        v.isString -> "String"
        v.isInt32 || v.isInt64 || v.isDouble || v.isDecimal128 -> "Number"
        v.isDateTime || v.isTimestamp -> "Date"
        v.isBoolean -> "Boolean"
        v.isDocument -> "Object"
        v.isArray -> "Array"
        v.isBinary -> "Binary"
        else -> v.bsonType.name
    }

    /**
     * Phase 1 (Zero-Overhead Primary Fetch) for Join View:
     * Runs ONLY the DocumentDB-compatible Join aggregation pipeline (`$limit(pageSize + 1)`),
     * deferring optional `explain()` to Phase 2 (`fetchJoinTrailingMetadata`) so rows appear immediately.
     */
    fun executeJoinPrimary(
        profile: MongoConnectionProfile,
        database: String,
        joinSpec: JoinSpec,
        filterRaw: String?,
        sortRaw: String?,
        pageIndex: Int,
        pageSize: Int?
    ): QueryExecutionResult {
        val safePage = pageIndex.coerceAtLeast(0)
        val safePageSize = pageSize?.coerceIn(1, JoinSpec.MAX_UNLIMITED_DOCS)
        val filter = MongoShellNormalizer.parseBsonDocument(filterRaw?.trim().orEmpty().ifEmpty { "{}" })
        val sort = MongoShellNormalizer.parseBsonDocument(sortRaw?.trim().orEmpty().ifEmpty { "{}" })

        val client = getOrCreateClient(profile)
        val db = client.getDatabase(database)
        val coll = db.getCollection(joinSpec.leftCollection, BsonDocument::class.java)

        val startNs = System.nanoTime()

        val pipeline = joinSpec.buildPipeline(
            filter = filter,
            sort = sort,
            pageIndex = safePage,
            pageSize = safePageSize,
            probeForNextPage = true
        )
        val aggIterable = coll.aggregate(pipeline).allowDiskUse(true).maxTime(20, TimeUnit.SECONDS)
        val rawDocs = aggIterable.into(mutableListOf())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        val hasNext: Boolean
        val capHit: Boolean
        val docs: List<BsonDocument>
        if (safePageSize != null) {
            hasNext = rawDocs.size > safePageSize
            capHit = false
            docs = if (hasNext) rawDocs.take(safePageSize) else rawDocs
        } else {
            hasNext = false
            capHit = rawDocs.size > JoinSpec.MAX_UNLIMITED_DOCS
            docs = if (capHit) rawDocs.take(JoinSpec.MAX_UNLIMITED_DOCS) else rawDocs
        }

        val currentOffset = if (safePageSize != null) safePage * safePageSize else 0
        val isApprox = hasNext || capHit
        val totalCount: Long = if (!isApprox) {
            (currentOffset + docs.size).toLong()
        } else {
            -1L
        }

        val effectiveLeftFilter = joinSpec.buildEffectiveLeftFilter(filter)
        val isUnfilteredPreview = effectiveLeftFilter.isEmpty() && sort.isEmpty()
        val (sampledFields, sampledFieldTypes) = extractFieldSchema(docs.take(20))

        return QueryExecutionResult(
            documents = docs,
            totalCount = totalCount,
            isApproximateCount = isApprox,
            elapsedMs = elapsedMs,
            scanType = if (isUnfilteredPreview) IndexScanType.NO_FILTER_PREVIEW else IndexScanType.UNKNOWN,
            indexName = null,
            sampledFields = sampledFields,
            sampledFieldTypes = sampledFieldTypes,
            hasNextPage = hasNext,
            unlimitedCapHit = capHit
        )
    }

    /**
     * Phase 2 (Trailing Async Metadata) for Join View:
     * Skips `explain()` and `listIndexes()` completely when there is no base Filter and no Sort.
     */
    fun fetchJoinTrailingMetadata(
        profile: MongoConnectionProfile,
        database: String,
        joinSpec: JoinSpec,
        filterRaw: String?,
        sortRaw: String?,
        pageIndex: Int,
        pageSize: Int?,
        forceRefresh: Boolean = false
    ): TrailingQueryMetadata {
        val filter = runCatching {
            MongoShellNormalizer.parseBsonDocument(filterRaw?.trim().orEmpty().ifEmpty { "{}" })
        }.getOrDefault(BsonDocument())
        val sort = runCatching {
            MongoShellNormalizer.parseBsonDocument(sortRaw?.trim().orEmpty().ifEmpty { "{}" })
        }.getOrDefault(BsonDocument())
        val effectiveLeftFilter = joinSpec.buildEffectiveLeftFilter(filter)

        if (effectiveLeftFilter.isEmpty() && sort.isEmpty()) {
            return TrailingQueryMetadata(
                totalCount = null,
                isApproximateCount = false,
                scanType = IndexScanType.NO_FILTER_PREVIEW,
                indexName = null,
                explainError = null,
                detailedIndexes = null
            )
        }

        val pipeline = joinSpec.buildPipeline(
            filter = filter,
            sort = sort,
            pageIndex = pageIndex.coerceAtLeast(0),
            pageSize = pageSize?.coerceIn(1, JoinSpec.MAX_UNLIMITED_DOCS),
            probeForNextPage = false
        )
        val explainOutcome = runCatching {
            val client = getOrCreateClient(profile)
            val db = client.getDatabase(database)
            val coll = db.getCollection(joinSpec.leftCollection, BsonDocument::class.java)
            val explainIterable = coll.aggregate(pipeline)
                .allowDiskUse(true)
                .maxTime(META_QUERY_MAX_TIME_MS, TimeUnit.MILLISECONDS)
            explainAggregateSafe(db, joinSpec.leftCollection, pipeline, explainIterable)
        }.getOrElse { t ->
            ExplainOutcome(IndexScanType.UNKNOWN, null, summarizeExplainError(t))
        }

        val detailedIndexes = listCollectionIndexInfos(
            profile = profile,
            database = database,
            collection = joinSpec.leftCollection,
            forceRefresh = forceRefresh
        )

        return TrailingQueryMetadata(
            totalCount = null,
            isApproximateCount = false,
            scanType = explainOutcome.scanType,
            indexName = explainOutcome.indexName,
            explainError = explainOutcome.errorMessage,
            detailedIndexes = detailedIndexes
        )
    }

    fun executeJoin(
        profile: MongoConnectionProfile,
        database: String,
        joinSpec: JoinSpec,
        filterRaw: String?,
        sortRaw: String?,
        pageIndex: Int,
        pageSize: Int?
    ): QueryExecutionResult {
        val primary = executeJoinPrimary(profile, database, joinSpec, filterRaw, sortRaw, pageIndex, pageSize)
        val trailing = fetchJoinTrailingMetadata(profile, database, joinSpec, filterRaw, sortRaw, pageIndex, pageSize)
        val indexes = trailing.detailedIndexes ?: emptyList()
        return primary.copy(
            scanType = trailing.scanType ?: primary.scanType,
            indexName = trailing.indexName,
            explainError = trailing.explainError,
            detailedIndexes = indexes,
            collectionIndexes = indexes.map { it.summaryLine() }
        )
    }

    /**
     * Phase 1 (Zero-Overhead Primary Fetch) for Find:
     * - Single network roundtrip with `.limit(safeLimit).batchSize(safeLimit)` so server cursor closes immediately.
     * - Runs ZERO `countDocuments`, ZERO `explain`, and ZERO `listIndexes` on the critical render path.
     * - When both Filter and Sort are empty, marks scanType as `NO_FILTER_PREVIEW`.
     */
    fun executeFindPrimary(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        filterRaw: String?,
        projectionRaw: String?,
        sortRaw: String?,
        skip: Int,
        limit: Int
    ): QueryExecutionResult {
        val filter = MongoShellNormalizer.parseBsonDocument(filterRaw?.trim().orEmpty().ifEmpty { "{}" })
        val projection = MongoShellNormalizer.parseBsonDocument(projectionRaw?.trim().orEmpty().ifEmpty { "{}" })
        val sort = MongoShellNormalizer.parseBsonDocument(sortRaw?.trim().orEmpty().ifEmpty { "{}" })

        val client = getOrCreateClient(profile)
        val db = client.getDatabase(database)
        val coll = db.getCollection(collection, BsonDocument::class.java)

        val startNs = System.nanoTime()
        val safeSkip = skip.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 5000)
        val iterable = coll.find(filter)
            .skip(safeSkip)
            .limit(safeLimit)
            .batchSize(safeLimit)
            .maxTime(15, TimeUnit.SECONDS)

        if (!projection.isEmpty()) {
            iterable.projection(projection)
        }
        if (!sort.isEmpty()) {
            iterable.sort(sort)
        }

        val docs = iterable.into(mutableListOf())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        val isUnfilteredPreview = filter.isEmpty() && sort.isEmpty()
        val warmEstCount = if (filter.isEmpty()) {
            getCachedEstimatedCountIfWarm(profile.id, database, collection)
        } else {
            null
        }
        val (sampledFields, sampledFieldTypes) = extractFieldSchema(docs.take(20))

        return QueryExecutionResult(
            documents = docs,
            totalCount = warmEstCount ?: -1L,
            isApproximateCount = warmEstCount == null,
            elapsedMs = elapsedMs,
            scanType = if (isUnfilteredPreview) IndexScanType.NO_FILTER_PREVIEW else IndexScanType.UNKNOWN,
            indexName = null,
            sampledFields = sampledFields,
            sampledFieldTypes = sampledFieldTypes
        )
    }

    /**
     * Phase 2 (Trailing Async Metadata) for Find:
     * - Runs asynchronously AFTER primary documents are already rendered.
     * - Empty Filter: uses O(1) `estimatedDocumentCount()` with 60s TTL cache (NEVER `countDocuments({})`).
     * - Non-empty Filter: uses `countDocuments(filter)` capped at 1,500ms `maxTimeMS`.
     * - Empty Filter + Empty Sort: NEVER calls `explain()` or `listIndexes()` -> returns `NO_FILTER_PREVIEW`.
     * - Non-empty Filter or Sort: runs `QUERY_PLANNER` explain (1,500ms `maxTimeMS`, ZERO `EXECUTION_STATS`) + cached `listIndexes()`.
     * - Every metadata call is isolated in `runCatching` so View / restricted-permission collections never fail.
     */
    fun fetchFindTrailingMetadata(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        filterRaw: String?,
        projectionRaw: String?,
        sortRaw: String?,
        skip: Int,
        limit: Int,
        forceRefresh: Boolean = false
    ): TrailingQueryMetadata {
        val filter = runCatching {
            MongoShellNormalizer.parseBsonDocument(filterRaw?.trim().orEmpty().ifEmpty { "{}" })
        }.getOrDefault(BsonDocument())
        val projection = runCatching {
            MongoShellNormalizer.parseBsonDocument(projectionRaw?.trim().orEmpty().ifEmpty { "{}" })
        }.getOrDefault(BsonDocument())
        val sort = runCatching {
            MongoShellNormalizer.parseBsonDocument(sortRaw?.trim().orEmpty().ifEmpty { "{}" })
        }.getOrDefault(BsonDocument())

        if (forceRefresh) {
            invalidateCollectionMetaCache(profile.id, database, collection)
        }

        // 1. Safe count (O(1) cached estimatedDocumentCount for empty filter; 1500ms-bounded countDocuments for filtered)
        var isApprox = false
        val totalCount: Long = if (filter.isEmpty()) {
            getEstimatedCountSafe(profile, database, collection, forceRefresh) ?: run {
                isApprox = true
                -1L
            }
        } else {
            runCatching {
                val client = getOrCreateClient(profile)
                val coll = client.getDatabase(database).getCollection(collection, BsonDocument::class.java)
                coll.countDocuments(
                    filter,
                    CountOptions().maxTime(META_QUERY_MAX_TIME_MS, TimeUnit.MILLISECONDS)
                )
            }.getOrElse {
                isApprox = true
                -1L
            }
        }

        // 2. Zero-overhead initial open: when Filter and Sort are both empty, skip explain() & listIndexes() completely!
        if (filter.isEmpty() && sort.isEmpty()) {
            return TrailingQueryMetadata(
                totalCount = totalCount,
                isApproximateCount = isApprox,
                scanType = IndexScanType.NO_FILTER_PREVIEW,
                indexName = null,
                explainError = null,
                detailedIndexes = null
            )
        }

        // 3. Conditional QUERY_PLANNER explain (1,500ms maxTimeMS, zero EXECUTION_STATS) + cached index metadata
        val safeSkip = skip.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 5000)
        val explainOutcome = runCatching {
            val client = getOrCreateClient(profile)
            val db = client.getDatabase(database)
            val coll = db.getCollection(collection, BsonDocument::class.java)
            val explainIterable = coll.find(filter)
                .skip(safeSkip)
                .limit(safeLimit)
                .batchSize(safeLimit)
                .maxTime(META_QUERY_MAX_TIME_MS, TimeUnit.MILLISECONDS)
            if (!projection.isEmpty()) explainIterable.projection(projection)
            if (!sort.isEmpty()) explainIterable.sort(sort)

            explainFindSafe(
                db = db,
                collection = collection,
                filter = filter,
                projection = projection,
                sort = sort,
                skip = safeSkip,
                limit = safeLimit,
                findIterable = explainIterable
            )
        }.getOrElse { t ->
            ExplainOutcome(IndexScanType.UNKNOWN, null, summarizeExplainError(t))
        }

        val detailedIndexes = listCollectionIndexInfos(
            profile = profile,
            database = database,
            collection = collection,
            forceRefresh = forceRefresh
        )

        return TrailingQueryMetadata(
            totalCount = totalCount,
            isApproximateCount = isApprox,
            scanType = explainOutcome.scanType,
            indexName = explainOutcome.indexName,
            explainError = explainOutcome.errorMessage,
            detailedIndexes = detailedIndexes
        )
    }

    fun executeFind(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        filterRaw: String?,
        projectionRaw: String?,
        sortRaw: String?,
        skip: Int,
        limit: Int
    ): QueryExecutionResult {
        val primary = executeFindPrimary(profile, database, collection, filterRaw, projectionRaw, sortRaw, skip, limit)
        val trailing = fetchFindTrailingMetadata(profile, database, collection, filterRaw, projectionRaw, sortRaw, skip, limit)
        val indexes = trailing.detailedIndexes ?: emptyList()
        return primary.copy(
            totalCount = trailing.totalCount ?: primary.totalCount,
            isApproximateCount = trailing.isApproximateCount,
            scanType = trailing.scanType ?: primary.scanType,
            indexName = trailing.indexName,
            explainError = trailing.explainError,
            detailedIndexes = indexes,
            collectionIndexes = indexes.map { it.summaryLine() }
        )
    }

    fun executeAggregate(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        pipeline: List<BsonDocument>
    ): QueryExecutionResult {
        val client = getOrCreateClient(profile)
        val db = client.getDatabase(database)
        val coll = db.getCollection(collection, BsonDocument::class.java)

        val startNs = System.nanoTime()
        val iterable = coll.aggregate(pipeline).allowDiskUse(true).maxTime(15, TimeUnit.SECONDS)
        val docs = iterable.into(mutableListOf())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        val explainOutcome = runCatching {
            val explainIterable = coll.aggregate(pipeline)
                .allowDiskUse(true)
                .maxTime(META_QUERY_MAX_TIME_MS, TimeUnit.MILLISECONDS)
            explainAggregateSafe(db, collection, pipeline, explainIterable)
        }.getOrElse { t ->
            ExplainOutcome(IndexScanType.UNKNOWN, null, summarizeExplainError(t))
        }
        val detailedIndexes = listCollectionIndexInfos(profile, database, collection)
        val collectionIndexes = detailedIndexes.map { it.summaryLine() }

        val (sampledFields, sampledFieldTypes) = extractFieldSchema(docs.take(20))

        return QueryExecutionResult(
            documents = docs,
            totalCount = docs.size.toLong(),
            isApproximateCount = false,
            elapsedMs = elapsedMs,
            scanType = explainOutcome.scanType,
            indexName = explainOutcome.indexName,
            sampledFields = sampledFields,
            sampledFieldTypes = sampledFieldTypes,
            collectionIndexes = collectionIndexes,
            detailedIndexes = detailedIndexes,
            explainError = explainOutcome.errorMessage
        )
    }

    /**
     * Unified read-only index metadata loader with 60-second per-collection cache.
     * Shared by both the COLLSCAN/IXSCAN badge tooltip AND the Read-Only Index Inspector dialog
     * so `listIndexes()` is never duplicated redundantly.
     * Isolated with `runCatching` so View or restricted collections never propagate errors.
     */
    fun listCollectionIndexInfos(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        forceRefresh: Boolean = false
    ): List<CollectionIndexInfo> {
        val cacheKey = "${profile.id}:$database:$collection"
        val now = System.currentTimeMillis()
        val cached = indexCache[cacheKey]
        if (!forceRefresh && cached != null && now - cached.fetchedAtMs < META_CACHE_TTL_MS) {
            return cached.indexes
        }
        return runCatching {
            val client = getOrCreateClient(profile)
            val db = client.getDatabase(database)
            val coll = db.getCollection(collection, BsonDocument::class.java)
            val rawIndexes = coll.listIndexes(BsonDocument::class.java)
                .maxTime(META_QUERY_MAX_TIME_MS, TimeUnit.MILLISECONDS)
                .into(mutableListOf())

            // Best-effort read-only collStats for per-index size bytes (ignored gracefully if restricted/View)
            val sizeByName: Map<String, Long> = runCatching {
                val stats = db.runCommand(Document("collStats", collection).append("maxTimeMS", META_QUERY_MAX_TIME_MS.toInt()))
                val sizesDoc = stats["indexSizes"] as? Document
                sizesDoc?.entries?.mapNotNull { (k, v) ->
                    val bytes = (v as? Number)?.toLong() ?: return@mapNotNull null
                    k to bytes
                }?.toMap().orEmpty()
            }.getOrDefault(emptyMap())

            val parsed = rawIndexes.mapNotNull { idxDoc ->
                val name = idxDoc.getString("name")?.value ?: return@mapNotNull null
                val keyDoc = idxDoc.getDocument("key", null) ?: return@mapNotNull null
                val keysMap = linkedMapOf<String, Any>()
                val keyParts = mutableListOf<String>()
                for ((k, v) in keyDoc) {
                    val prettyVal: Any = when {
                        v.isInt32 -> v.asInt32().value
                        v.isInt64 -> v.asInt64().value
                        v.isDouble -> {
                            val d = v.asDouble().value
                            if (d == d.toLong().toDouble()) d.toLong() else d
                        }
                        v.isString -> v.asString().value
                        else -> v.toString()
                    }
                    keysMap[k] = prettyVal
                    val displayVal = if (prettyVal is String) "\"$prettyVal\"" else prettyVal.toString()
                    keyParts.add("$k: $displayVal")
                }

                val isUnique = idxDoc["unique"]?.let { it.isBoolean && it.asBoolean().value } ?: false
                val isSparse = idxDoc["sparse"]?.let { it.isBoolean && it.asBoolean().value } ?: false
                val ttlSec = idxDoc["expireAfterSeconds"]?.let { v ->
                    when {
                        v.isInt32 -> v.asInt32().value.toLong()
                        v.isInt64 -> v.asInt64().value
                        v.isDouble -> v.asDouble().value.toLong()
                        else -> null
                    }
                }

                CollectionIndexInfo(
                    name = name,
                    keys = keysMap,
                    keyDisplay = keyParts.joinToString(", "),
                    unique = isUnique,
                    sparse = isSparse,
                    ttlSeconds = ttlSec,
                    sizeBytes = sizeByName[name]
                )
            }
            indexCache[cacheKey] = CachedIndexEntry(now, parsed)
            parsed
        }.getOrElse {
            cached?.indexes ?: emptyList()
        }
    }

    fun listCollectionIndexSummaries(
        profile: MongoConnectionProfile,
        database: String,
        collection: String
    ): List<String> = listCollectionIndexInfos(profile, database, collection).map { it.summaryLine() }

    /**
     * Lightweight QUERY_PLANNER-only explain (ZERO EXECUTION_STATS!) bounded by 1,500ms `maxTimeMS`.
     * Never executes a full collection scan just to inspect the query plan.
     */
    private fun explainFindSafe(
        db: com.mongodb.client.MongoDatabase,
        collection: String,
        filter: BsonDocument,
        projection: BsonDocument,
        sort: BsonDocument,
        skip: Int,
        limit: Int,
        findIterable: com.mongodb.client.FindIterable<BsonDocument>
    ): ExplainOutcome {
        var lastError: String? = null

        // 1. Driver explain(QUERY_PLANNER) with 1,500ms maxTime on iterable
        runCatching {
            parseExplainPlan(findIterable.explain(ExplainVerbosity.QUERY_PLANNER))
        }.onSuccess { res ->
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        }.onFailure { t ->
            lastError = summarizeExplainError(t)
        }

        val findCmd = Document("find", collection).append("filter", filter)
        if (!projection.isEmpty()) findCmd.append("projection", projection)
        if (!sort.isEmpty()) findCmd.append("sort", sort)
        if (skip > 0) findCmd.append("skip", skip)
        if (limit > 0) findCmd.append("limit", limit)

        // 2. Explicit db.runCommand({ explain: { find: ... }, verbosity: "queryPlanner", maxTimeMS: 1500 })
        runCatching {
            val explainDoc = db.runCommand(
                Document("explain", findCmd)
                    .append("verbosity", "queryPlanner")
                    .append("maxTimeMS", META_QUERY_MAX_TIME_MS.toInt())
            )
            parseExplainPlan(explainDoc)
        }.onSuccess { res ->
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        }.onFailure { t ->
            lastError = summarizeExplainError(t)
        }

        // 3. DocumentDB 3.6/4.0 fallback without verbosity field (bounded by maxTimeMS: 1500)
        runCatching {
            val explainDoc = db.runCommand(
                Document("explain", findCmd)
                    .append("maxTimeMS", META_QUERY_MAX_TIME_MS.toInt())
            )
            parseExplainPlan(explainDoc)
        }.onSuccess { res ->
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        }.onFailure { t ->
            lastError = summarizeExplainError(t)
        }

        return ExplainOutcome(
            scanType = IndexScanType.UNKNOWN,
            indexName = null,
            errorMessage = lastError ?: "Explain output did not include a recognizable scan stage"
        )
    }

    /**
     * Lightweight QUERY_PLANNER-only aggregate explain (ZERO EXECUTION_STATS!) bounded by 1,500ms `maxTimeMS`.
     */
    private fun explainAggregateSafe(
        db: com.mongodb.client.MongoDatabase,
        collection: String,
        pipeline: List<BsonDocument>,
        aggIterable: com.mongodb.client.AggregateIterable<BsonDocument>
    ): ExplainOutcome {
        var lastError: String? = null

        // 1. Driver explain(QUERY_PLANNER)
        runCatching {
            parseExplainPlan(aggIterable.explain(ExplainVerbosity.QUERY_PLANNER))
        }.onSuccess { res ->
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        }.onFailure { t ->
            lastError = summarizeExplainError(t)
        }

        val aggCmd = Document("aggregate", collection)
            .append("pipeline", pipeline)
            .append("cursor", Document())

        // 2. runCommand({ explain: { aggregate: ... }, verbosity: "queryPlanner", maxTimeMS: 1500 })
        runCatching {
            val explainDoc = db.runCommand(
                Document("explain", aggCmd)
                    .append("verbosity", "queryPlanner")
                    .append("maxTimeMS", META_QUERY_MAX_TIME_MS.toInt())
            )
            parseExplainPlan(explainDoc)
        }.onSuccess { res ->
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        }.onFailure { t ->
            lastError = summarizeExplainError(t)
        }

        // 3. runCommand({ explain: { aggregate: ... }, maxTimeMS: 1500 })
        runCatching {
            val explainDoc = db.runCommand(
                Document("explain", aggCmd)
                    .append("maxTimeMS", META_QUERY_MAX_TIME_MS.toInt())
            )
            parseExplainPlan(explainDoc)
        }.onSuccess { res ->
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        }.onFailure { t ->
            lastError = summarizeExplainError(t)
        }

        // 4. Legacy MongoDB & AWS DocumentDB direct { aggregate: ..., pipeline: [...], explain: true, maxTimeMS: 1500 }
        runCatching {
            val explainDoc = db.runCommand(
                Document("aggregate", collection)
                    .append("pipeline", pipeline)
                    .append("explain", true)
                    .append("maxTimeMS", META_QUERY_MAX_TIME_MS.toInt())
            )
            parseExplainPlan(explainDoc)
        }.onSuccess { res ->
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        }.onFailure { t ->
            lastError = summarizeExplainError(t)
        }

        return ExplainOutcome(
            scanType = IndexScanType.UNKNOWN,
            indexName = null,
            errorMessage = lastError ?: "Explain output did not include a recognizable scan stage"
        )
    }

    private fun summarizeExplainError(t: Throwable): String {
        val raw = t.message?.trim().orEmpty().ifEmpty { t.javaClass.simpleName }
        return raw.replace(Regex("\\s+"), " ").take(220)
    }

    fun replaceDocument(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        originalId: BsonValue,
        replacement: BsonDocument
    ) {
        check(!isEffectivelyReadOnly(profile)) { "Write blocked: profile '${profile.displayName}' is READ-ONLY" }
        val client = getOrCreateClient(profile)
        val coll = client.getDatabase(database).getCollection(collection, BsonDocument::class.java)
        coll.replaceOne(Filters.eq("_id", originalId), replacement)
        invalidateCollectionMetaCache(profile.id, database, collection)
    }

    fun cloneDocument(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        source: BsonDocument
    ): BsonObjectId {
        check(!isEffectivelyReadOnly(profile)) { "Write blocked: profile '${profile.displayName}' is READ-ONLY" }
        val client = getOrCreateClient(profile)
        val coll = client.getDatabase(database).getCollection(collection, BsonDocument::class.java)
        val clone = BsonDocument.parse(MongoShellNormalizer.formatPrettyExtended(source))
        val newId = BsonObjectId(ObjectId())
        clone["_id"] = newId
        coll.insertOne(clone)
        invalidateCollectionMetaCache(profile.id, database, collection)
        return newId
    }

    fun deleteDocument(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        id: BsonValue
    ) {
        check(!isEffectivelyReadOnly(profile)) { "Write blocked: profile '${profile.displayName}' is READ-ONLY" }
        val client = getOrCreateClient(profile)
        val coll = client.getDatabase(database).getCollection(collection, BsonDocument::class.java)
        coll.deleteOne(Filters.eq("_id", id))
        invalidateCollectionMetaCache(profile.id, database, collection)
    }

    private fun buildClientSettings(uri: String): MongoClientSettings {
        val connStr = ConnectionString(uri)
        return MongoClientSettings.builder()
            .applyConnectionString(connStr)
            .applyToClusterSettings { builder ->
                builder.serverSelectionTimeout(3000, TimeUnit.MILLISECONDS)
            }
            .applyToSocketSettings { builder ->
                builder.connectTimeout(3000, TimeUnit.MILLISECONDS)
                builder.readTimeout(15000, TimeUnit.MILLISECONDS)
            }
            .build()
    }

    /**
     * Recursively parses explain() output for both pure MongoDB (`FETCH -> IXSCAN`, `SORT -> IXSCAN`, `IDHACK`, `shards`)
     * and AWS DocumentDB (`SUBSCAN -> IXSCAN`, `IXONLY`, `executionStats.executionStages`, `stages[0].$cursor`).
     * Always ignores `rejectedPlans` and `allPlansExecution` so losing plans never cause false classification.
     */
    private fun parseExplainPlan(explainDoc: Document): Pair<IndexScanType, String?> {
        var foundIxScan: String? = null
        var foundCollScan = false

        fun isIgnoredBranchKey(key: String?): Boolean =
            key == "rejectedPlans" || key == "allPlansExecution" || key == "parsedQuery"

        fun findStageRecursive(node: Any?) {
            when (node) {
                is Document -> {
                    val stage = node["stage"]?.toString()?.trim()
                    if (!stage.isNullOrEmpty()) {
                        val upper = stage.uppercase()
                        when {
                            upper == "IXSCAN" ||
                                upper == "IXONLY" ||
                                upper == "IDHACK" ||
                                upper == "COUNT_SCAN" ||
                                upper == "DISTINCT_SCAN" ||
                                upper == "EXPRESS_IXSCAN" ||
                                upper == "EXPRESS_CLUSTERED_IXSCAN" ||
                                upper.contains("IXSCAN") ||
                                upper.contains("IXONLY") -> {
                                val idxName = node["indexName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: node["index"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: if (upper == "IDHACK") "_id_" else upper
                                if (foundIxScan == null || (foundIxScan == upper && idxName != upper)) {
                                    foundIxScan = idxName
                                }
                            }
                            upper == "COLLSCAN" -> foundCollScan = true
                        }
                    }
                    for ((k, v) in node) {
                        if (isIgnoredBranchKey(k)) continue
                        findStageRecursive(v)
                    }
                }
                is Map<*, *> -> {
                    for ((k, v) in node) {
                        if (isIgnoredBranchKey(k?.toString())) continue
                        findStageRecursive(v)
                    }
                }
                is List<*> -> node.forEach { findStageRecursive(it) }
            }
        }

        // Priority 1: Inspect winningPlan / executionStages / stages[0].$cursor / shards (excluding rejectedPlans)
        val qp = explainDoc["queryPlanner"] as? Document
        val winningPlan = qp?.get("winningPlan")
        if (winningPlan != null) {
            findStageRecursive(winningPlan)
        }
        if (foundIxScan == null) {
            val execStats = explainDoc["executionStats"] as? Document
            val execStages = execStats?.get("executionStages")
            if (execStages != null) {
                findStageRecursive(execStages)
            }
        }
        if (foundIxScan == null && !foundCollScan) {
            val stages = explainDoc["stages"] as? List<*>
            val firstCursor = (stages?.firstOrNull() as? Document)?.get("\$cursor") as? Document
            if (firstCursor != null) {
                (firstCursor["queryPlanner"] as? Document)?.get("winningPlan")?.let { findStageRecursive(it) }
                if (foundIxScan == null) {
                    (firstCursor["executionStats"] as? Document)?.get("executionStages")?.let { findStageRecursive(it) }
                }
            }
            if (foundIxScan == null && !foundCollScan && stages != null) {
                findStageRecursive(stages)
            }
        }
        if (foundIxScan == null && !foundCollScan) {
            explainDoc["shards"]?.let { findStageRecursive(it) }
        }
        if (foundIxScan == null && !foundCollScan) {
            findStageRecursive(explainDoc)
        }

        return when {
            foundIxScan != null -> IndexScanType.IXSCAN to foundIxScan
            foundCollScan -> IndexScanType.COLLSCAN to null
            else -> IndexScanType.UNKNOWN to null
        }
    }

    private fun extractFieldSchema(docs: List<BsonDocument>): Pair<List<String>, Map<String, SmartInValueType>> {
        val fields = linkedSetOf<String>()
        val types = linkedMapOf<String, SmartInValueType>()

        fun inferType(v: BsonValue): SmartInValueType? = when {
            v.isObjectId -> SmartInValueType.OBJECT_ID
            v.isInt32 || v.isInt64 || v.isDouble || v.isDecimal128 -> SmartInValueType.NUMBER
            v.isString -> SmartInValueType.STRING
            else -> null
        }

        fun collect(doc: BsonDocument, prefix: String, depth: Int) {
            if (depth > 3) return
            for ((k, v) in doc) {
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                fields.add(path)
                if (!types.containsKey(path)) {
                    inferType(v)?.let { types[path] = it }
                }
                when {
                    v.isDocument -> collect(v.asDocument(), path, depth + 1)
                    v.isArray -> {
                        for (elem in v.asArray().take(3)) {
                            if (elem.isDocument) {
                                collect(elem.asDocument(), path, depth + 1)
                            }
                        }
                    }
                }
            }
        }
        docs.forEach { collect(it, "", 0) }
        return fields.toList() to types
    }

    private fun safeClose(client: MongoClient) {
        try {
            client.close()
        } catch (_: Throwable) {
            // Ignore shutdown exceptions
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val snapshot = clients.values.toList()
        clients.clear()
        clientUris.clear()
        unlockedReadOnlyProfiles.clear()
        indexCache.clear()
        estimatedCountCache.clear()
        snapshot.forEach { safeClose(it) }
    }
}
