package com.maekom.mongolens.mongo

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.MongoLensSettings
import com.mongodb.ConnectionString
import com.mongodb.ExplainVerbosity
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCommandException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCursor
import com.mongodb.client.MongoIterable
import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.Filters
import org.bson.BsonArray
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

    private val projectBasePath: java.nio.file.Path? = project.basePath?.let { java.nio.file.Paths.get(it) }
    private val clients = ConcurrentHashMap<String, MongoClient>()
    private val clientFingerprints = ConcurrentHashMap<String, String>()
    private val unlockedReadOnlyProfiles = ConcurrentHashMap.newKeySet<String>()
    private val indexCache = ConcurrentHashMap<String, CachedIndexEntry>()
    private val estimatedCountCache = ConcurrentHashMap<String, CachedCountEntry>()
    private val runtimeDocumentDbProfileIds = ConcurrentHashMap.newKeySet<String>()
    private val hybridJoinCache = HybridJoinSessionCache(maxEntries = 16)
    private val disposed = AtomicBoolean(false)

    fun isDocumentDbHybridRequired(profile: MongoConnectionProfile): Boolean {
        return JsonImportEngine.isDocumentDbProfile(profile) || profile.id in runtimeDocumentDbProfileIds
    }

    fun isError304MultipleJoin(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is MongoCommandException && cur.errorCode == 304) return true
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("multiple join conditions") || msg.contains("error 304")) return true
            cur = cur.cause
        }
        return false
    }

    fun isExecutionTimeoutError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is MongoExecutionTimeoutException) return true
            if (cur is MongoCommandException && cur.errorCode == 50) return true
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("exceeded time limit") || msg.contains("maxtimems")) {
                if (!isMaxTimeUnsupportedError(cur)) return true
            }
            cur = cur.cause
        }
        return false
    }

    fun isMaxTimeUnsupportedError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("maxtimems") &&
                (msg.contains("not supported") || msg.contains("unsupported") || msg.contains("unknown") || msg.contains("invalid"))
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private inline fun <T> executeWithMaxTimeFallback(
        timeoutMs: Long,
        withMaxTime: (Long) -> T,
        withoutMaxTime: () -> T
    ): T {
        return try {
            withMaxTime(timeoutMs)
        } catch (t: Throwable) {
            if (isMaxTimeUnsupportedError(t)) {
                withoutMaxTime()
            } else {
                throw t
            }
        }
    }

    private fun drainCursorCancellably(
        iterable: MongoIterable<BsonDocument>,
        control: QueryExecutionControl?
    ): MutableList<BsonDocument> {
        val docs = mutableListOf<BsonDocument>()
        if (control?.isCancelled?.invoke() == true || Thread.currentThread().isInterrupted) {
            return docs
        }
        val cursor: MongoCursor<BsonDocument> = iterable.iterator()
        try {
            if (control?.isCancelled?.invoke() == true || Thread.currentThread().isInterrupted) {
                return docs
            }
            control?.onCursorOpened?.invoke(cursor)
            while (cursor.hasNext()) {
                if (control?.isCancelled?.invoke() == true || Thread.currentThread().isInterrupted) {
                    break
                }
                docs.add(cursor.next())
            }
        } finally {
            control?.onCursorOpened?.invoke(null)
            runCatching { cursor.close() }
        }
        return docs
    }

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
        val fingerprint = profile.connectionFingerprint()
        val existingFingerprint = clientFingerprints[profile.id]
        val existingClient = clients[profile.id]
        if (existingClient != null && existingFingerprint == fingerprint) {
            return existingClient
        }
        return reconnectInternal(profile, fingerprint)
    }

    fun reconnect(profile: MongoConnectionProfile): Long {
        reconnectInternal(profile, profile.connectionFingerprint())
        return ping(profile)
    }

    private fun reconnectInternal(profile: MongoConnectionProfile, fingerprint: String): MongoClient {
        closeClient(profile.id)
        val settings = buildClientSettings(profile)
        val newClient = MongoClients.create(settings)
        val prev = clients.put(profile.id, newClient)
        clientFingerprints[profile.id] = fingerprint
        if (prev != null && prev !== newClient) {
            safeClose(prev)
        }
        return newClient
    }

    fun closeClient(profileId: String) {
        clientFingerprints.remove(profileId)
        indexCache.keys.removeIf { it.startsWith("$profileId:") }
        estimatedCountCache.keys.removeIf { it.startsWith("$profileId:") }
        hybridJoinCache.invalidateByConnection(profileId)
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

    fun testProfileDirect(profile: MongoConnectionProfile): Long {
        val start = System.nanoTime()
        val settings = buildClientSettings(profile, testTimeoutMs = 5_000)
        val client = MongoClients.create(settings)
        try {
            val dbName = profile.extractDefaultDatabase()?.takeIf { it.isNotBlank() } ?: "admin"
            client.getDatabase(dbName).runCommand(Document("ping", 1))
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        } finally {
            safeClose(client)
        }
    }

    fun testUriDirect(uri: String, defaultDb: String?): Long {
        val tempProfile = MongoConnectionProfile(
            id = "test:direct",
            displayName = "Test",
            profileName = "test",
            source = com.maekom.mongolens.config.ProfileSource.CUSTOM,
            uri = uri,
            database = defaultDb
        )
        return testProfileDirect(tempProfile)
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
        pageSize: Int?,
        forceRefresh: Boolean = false,
        executionControl: QueryExecutionControl? = null
    ): QueryExecutionResult {
        val safePage = pageIndex.coerceAtLeast(0)
        val safePageSize = pageSize?.coerceIn(1, JoinSpec.MAX_UNLIMITED_DOCS)
        val filter = MongoShellNormalizer.parseBsonDocument(filterRaw?.trim().orEmpty().ifEmpty { "{}" })
        val sort = MongoShellNormalizer.parseBsonDocument(sortRaw?.trim().orEmpty().ifEmpty { "{}" })
        val timeoutMs = MongoLensSettings.getInstance().queryTimeoutMillis()

        val startNs = System.nanoTime()

        // Bidirectional Pre-Filter Join: reduces both base & target collections before cross-matching keys
        // (short-circuits on 0 target keys for INNER JOIN, avoids full-collection $lookup bottlenecks on DocumentDB & MongoDB).
        if (BidirectionalPreFilterJoinEngine.shouldUseBidirectionalPreFilter(joinSpec)) {
            val preFilterResult = executeJoinBidirectionalPreFilter(
                profile = profile,
                database = database,
                joinSpec = joinSpec,
                filter = filter,
                sort = sort,
                safePage = safePage,
                safePageSize = safePageSize,
                forceRefresh = forceRefresh,
                executionControl = executionControl,
                startNs = startNs
            )
            if (preFilterResult != null) {
                return preFilterResult
            }
        }

        // AWS DocumentDB (detected statically or via prior Error 304) uses 2-phase hybrid join + session-cached slicing
        if (joinSpec.hasCompositeJoin() && isDocumentDbHybridRequired(profile)) {
            return executeJoinHybridDocumentDb(
                profile = profile,
                database = database,
                joinSpec = joinSpec,
                filter = filter,
                sort = sort,
                safePage = safePage,
                safePageSize = safePageSize,
                forceRefresh = forceRefresh,
                executionControl = executionControl
            )
        }

        val client = getOrCreateClient(profile)
        val db = client.getDatabase(database)
        val coll = db.getCollection(joinSpec.leftCollection, BsonDocument::class.java)

        val pipeline = joinSpec.buildPipeline(
            filter = filter,
            sort = sort,
            pageIndex = safePage,
            pageSize = safePageSize,
            probeForNextPage = true
        )
        val rawDocs = try {
            executeWithMaxTimeFallback(
                timeoutMs = timeoutMs,
                withMaxTime = { ms ->
                    val aggIterable = coll.aggregate(pipeline)
                        .allowDiskUse(true)
                        .maxTime(ms, TimeUnit.MILLISECONDS)
                    drainCursorCancellably(aggIterable, executionControl)
                },
                withoutMaxTime = {
                    val aggIterable = coll.aggregate(pipeline).allowDiskUse(true)
                    drainCursorCancellably(aggIterable, executionControl)
                }
            )
        } catch (t: Throwable) {
            if (joinSpec.hasCompositeJoin() && isError304MultipleJoin(t)) {
                // Record connection as DocumentDB-compatible at runtime and retry seamlessly via hybrid join
                runtimeDocumentDbProfileIds.add(profile.id)
                return executeJoinHybridDocumentDb(
                    profile = profile,
                    database = database,
                    joinSpec = joinSpec,
                    filter = filter,
                    sort = sort,
                    safePage = safePage,
                    safePageSize = safePageSize,
                    forceRefresh = true,
                    executionControl = executionControl
                )
            }
            throw t
        }
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
     * Executes Bidirectional Pre-Filter Join backed by `hybridJoinCache` for zero-gap pagination.
     * Returns `null` when the pre-filter safety cap (10,000 docs) is exceeded so caller falls back
     * seamlessly to forward `$lookup` / AWS DocumentDB hybrid join.
     */
    private fun executeJoinBidirectionalPreFilter(
        profile: MongoConnectionProfile,
        database: String,
        joinSpec: JoinSpec,
        filter: BsonDocument,
        sort: BsonDocument,
        safePage: Int,
        safePageSize: Int?,
        forceRefresh: Boolean,
        executionControl: QueryExecutionControl?,
        startNs: Long
    ): QueryExecutionResult? {
        val timeoutMs = MongoLensSettings.getInstance().queryTimeoutMillis()
        val signature = hybridJoinCache.buildSignature(
            connectionId = profile.id,
            database = database,
            leftCollection = joinSpec.leftCollection,
            filterJson = filter.toJson(),
            sortJson = sort.toJson(),
            joinSpecSignature = "prefilter::${joinSpec.cacheSignatureSegment()}"
        )

        val cachedSlice = if (!forceRefresh) {
            hybridJoinCache.getPage(signature, safePage, safePageSize)
        } else {
            null
        }

        val slice = cachedSlice ?: run {
            val client = getOrCreateClient(profile)
            val db = client.getDatabase(database)
            val source = BidirectionalPreFilterJoinEngine.CollectionQuerySource { collName, qFilter, qSort, qLimit ->
                val c = db.getCollection(collName, BsonDocument::class.java)
                fun buildIt(applyMaxTime: Boolean): com.mongodb.client.FindIterable<BsonDocument> {
                    val it = c.find(qFilter)
                    if (qSort != null && !qSort.isEmpty()) it.sort(qSort)
                    if (qLimit != null && qLimit > 0) {
                        it.limit(qLimit).batchSize(minOf(qLimit, 1000))
                    }
                    if (applyMaxTime) it.maxTime(timeoutMs, TimeUnit.MILLISECONDS)
                    return it
                }
                executeWithMaxTimeFallback(
                    timeoutMs = timeoutMs,
                    withMaxTime = { drainCursorCancellably(buildIt(applyMaxTime = true), executionControl) },
                    withoutMaxTime = { drainCursorCancellably(buildIt(applyMaxTime = false), executionControl) }
                )
            }

            val allLeftJoin = joinSpec.targets.all { !it.excludeUnmatched }
            val engineLimit = if (allLeftJoin && safePageSize != null && safePage == 0) {
                // Keep enough buffer for Next page navigation while still bounding base fetch
                maxOf(safePageSize * 20, MultiKeyJoinFilterEngine.MIN_HYBRID_BUFFER_LIMIT)
                    .coerceAtMost(JoinSpec.MAX_UNLIMITED_DOCS)
            } else {
                JoinSpec.MAX_UNLIMITED_DOCS
            }

            when (val outcome = BidirectionalPreFilterJoinEngine.execute(
                source = source,
                joinSpec = joinSpec,
                topFilter = filter,
                sort = sort,
                resultLimit = engineLimit
            )) {
                is BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.FallbackToLookup -> return null
                is BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed -> {
                    hybridJoinCache.putAndSlice(profile.id, signature, outcome.documents, safePage, safePageSize)
                }
            }
        }

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        val capHit = safePageSize == null && slice.totalCount > JoinSpec.MAX_UNLIMITED_DOCS
        val effectiveLeftFilter = joinSpec.buildEffectiveLeftFilter(filter)
        val isUnfilteredPreview = effectiveLeftFilter.isEmpty() && sort.isEmpty()
        val (sampledFields, sampledFieldTypes) = extractFieldSchema(slice.pageDocs.take(20))

        return QueryExecutionResult(
            documents = slice.pageDocs,
            totalCount = slice.totalCount.toLong(),
            isApproximateCount = false,
            elapsedMs = elapsedMs,
            scanType = if (isUnfilteredPreview) IndexScanType.NO_FILTER_PREVIEW else IndexScanType.UNKNOWN,
            indexName = null,
            sampledFields = sampledFields,
            sampledFieldTypes = sampledFieldTypes,
            hasNextPage = slice.hasNextPage,
            unlimitedCapHit = capHit
        )
    }

    /**
     * AWS DocumentDB 100%-compatible 2-phase Hybrid Composite Join:
     * - Stage 1 (DB): Single-key standard `$lookup` (prioritizing unique ID keys) + `$slice(500)` 16MB guard,
     *   no `$skip`, bounded `$limit: maxOf(pageSize * 20, 2000)`.
     * - Stage 2 (Memory): `MultiKeyJoinFilterEngine.applyFullHybridJoinShaping(...)` + query-signature-isolated
     *   `HybridJoinSessionCache` slicing so `Next`/`Prev` paging has 0 missing and 0 duplicate records.
     */
    private fun executeJoinHybridDocumentDb(
        profile: MongoConnectionProfile,
        database: String,
        joinSpec: JoinSpec,
        filter: BsonDocument,
        sort: BsonDocument,
        safePage: Int,
        safePageSize: Int?,
        forceRefresh: Boolean,
        executionControl: QueryExecutionControl? = null
    ): QueryExecutionResult {
        val startNs = System.nanoTime()
        val timeoutMs = MongoLensSettings.getInstance().queryTimeoutMillis()
        val signature = hybridJoinCache.buildSignature(
            connectionId = profile.id,
            database = database,
            leftCollection = joinSpec.leftCollection,
            filterJson = filter.toJson(),
            sortJson = sort.toJson(),
            joinSpecSignature = joinSpec.cacheSignatureSegment()
        )

        val slice = if (!forceRefresh) {
            hybridJoinCache.getPage(signature, safePage, safePageSize)
        } else {
            null
        } ?: run {
            val client = getOrCreateClient(profile)
            val db = client.getDatabase(database)
            val coll = db.getCollection(joinSpec.leftCollection, BsonDocument::class.java)
            val bufferLimit = if (safePageSize != null) {
                maxOf(safePageSize * 20, MultiKeyJoinFilterEngine.MIN_HYBRID_BUFFER_LIMIT)
            } else {
                JoinSpec.MAX_UNLIMITED_DOCS
            }
            val hybridPipeline = joinSpec.buildHybridDocumentDbPipeline(filter, sort, bufferLimit)
            val rawDbDocs = executeWithMaxTimeFallback(
                timeoutMs = timeoutMs,
                withMaxTime = { ms ->
                    val iterable = coll.aggregate(hybridPipeline)
                        .allowDiskUse(true)
                        .maxTime(ms, TimeUnit.MILLISECONDS)
                    drainCursorCancellably(iterable, executionControl)
                },
                withoutMaxTime = {
                    val iterable = coll.aggregate(hybridPipeline).allowDiskUse(true)
                    drainCursorCancellably(iterable, executionControl)
                }
            )
            val shapedDocs = MultiKeyJoinFilterEngine.applyFullHybridJoinShaping(rawDbDocs, joinSpec)
            hybridJoinCache.putAndSlice(profile.id, signature, shapedDocs, safePage, safePageSize)
        }

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        val capHit = safePageSize == null && slice.totalCount > JoinSpec.MAX_UNLIMITED_DOCS
        val effectiveLeftFilter = joinSpec.buildEffectiveLeftFilter(filter)
        val isUnfilteredPreview = effectiveLeftFilter.isEmpty() && sort.isEmpty()
        val (sampledFields, sampledFieldTypes) = extractFieldSchema(slice.pageDocs.take(20))

        return QueryExecutionResult(
            documents = slice.pageDocs,
            totalCount = slice.totalCount.toLong(),
            isApproximateCount = false,
            elapsedMs = elapsedMs,
            scanType = if (isUnfilteredPreview) IndexScanType.NO_FILTER_PREVIEW else IndexScanType.UNKNOWN,
            indexName = null,
            sampledFields = sampledFields,
            sampledFieldTypes = sampledFieldTypes,
            hasNextPage = slice.hasNextPage,
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

        val pipeline = if (joinSpec.hasCompositeJoin() && isDocumentDbHybridRequired(profile)) {
            val buf = if (pageSize != null) maxOf(pageSize * 20, MultiKeyJoinFilterEngine.MIN_HYBRID_BUFFER_LIMIT) else JoinSpec.MAX_UNLIMITED_DOCS
            joinSpec.buildHybridDocumentDbPipeline(filter, sort, buf)
        } else {
            joinSpec.buildPipeline(
                filter = filter,
                sort = sort,
                pageIndex = pageIndex.coerceAtLeast(0),
                pageSize = pageSize?.coerceIn(1, JoinSpec.MAX_UNLIMITED_DOCS),
                probeForNextPage = false
            )
        }
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
        limit: Int,
        executionControl: QueryExecutionControl? = null
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
        val timeoutMs = MongoLensSettings.getInstance().queryTimeoutMillis()

        fun buildFindIterable(applyMaxTime: Boolean): com.mongodb.client.FindIterable<BsonDocument> {
            val it = coll.find(filter)
                .skip(safeSkip)
                .limit(safeLimit)
                .batchSize(safeLimit)
            if (applyMaxTime) {
                it.maxTime(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (!projection.isEmpty()) it.projection(projection)
            if (!sort.isEmpty()) it.sort(sort)
            return it
        }

        val docs = executeWithMaxTimeFallback(
            timeoutMs = timeoutMs,
            withMaxTime = { drainCursorCancellably(buildFindIterable(applyMaxTime = true), executionControl) },
            withoutMaxTime = { drainCursorCancellably(buildFindIterable(applyMaxTime = false), executionControl) }
        )
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
                val countTimeoutMs = minOf(MongoLensSettings.getInstance().queryTimeoutMillis(), 10_000L)
                executeWithMaxTimeFallback(
                    timeoutMs = countTimeoutMs,
                    withMaxTime = { ms ->
                        coll.countDocuments(filter, CountOptions().maxTime(ms, TimeUnit.MILLISECONDS))
                    },
                    withoutMaxTime = {
                        coll.countDocuments(filter)
                    }
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
        val result = coll.replaceOne(Filters.eq("_id", originalId), replacement)
        if (result.matchedCount == 0L) {
            throw IllegalStateException(
                com.maekom.mongolens.i18n.I18n.t("fieldEdit.error.matchedZero")
            )
        }
        invalidateCollectionMetaCache(profile.id, database, collection)
    }

    /**
     * Updates a single field using `Document("$set", Document(fieldPath, typedNativeValue))`
     * matching the document strictly by its original typed `_id` value (ObjectId / String / Int32 / Int64, etc.).
     * Deliberately omits `maxTimeMS` on write operations for full AWS DocumentDB compatibility.
     */
    fun updateDocumentField(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        originalId: BsonValue,
        fieldPath: String,
        typedBsonValue: BsonValue,
        typedNativeValue: Any?,
        fallbackDoc: BsonDocument
    ): BsonDocument {
        check(!isEffectivelyReadOnly(profile)) { "Write blocked: profile '${profile.displayName}' is READ-ONLY" }
        val normalizedPath = BsonValueValidator.normalizeMongoUpdatePath(fieldPath)
        require(normalizedPath.isNotEmpty() && normalizedPath != "_id") {
            com.maekom.mongolens.i18n.I18n.t("fieldEdit.error.idReadOnly")
        }

        val client = getOrCreateClient(profile)
        val db = client.getDatabase(database)
        val docColl = db.getCollection(collection)
        val originalRawId = BsonValueValidator.bsonToNativeValue(originalId)
        val updatePayload = Document("\$set", Document(normalizedPath, typedNativeValue))
        val result = docColl.updateOne(Filters.eq("_id", originalRawId), updatePayload)
        if (result.matchedCount == 0L) {
            throw IllegalStateException(
                com.maekom.mongolens.i18n.I18n.t("fieldEdit.error.matchedZero")
            )
        }

        invalidateCollectionMetaCache(profile.id, database, collection)

        val bsonColl = db.getCollection(collection, BsonDocument::class.java)
        return runCatching {
            bsonColl.find(Filters.eq("_id", originalId)).first()
        }.getOrNull() ?: applyFieldPatchLocally(fallbackDoc, normalizedPath, typedBsonValue)
    }

    private fun applyFieldPatchLocally(
        source: BsonDocument,
        dotPath: String,
        newValue: BsonValue
    ): BsonDocument {
        val cloned = BsonDocument.parse(source.toJson())
        val segments = dotPath.split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return cloned
        var cur: BsonValue = cloned
        for (i in 0 until segments.lastIndex) {
            val seg = segments[i]
            cur = when (cur) {
                is BsonDocument -> cur[seg] ?: return cloned
                is BsonArray -> {
                    val idx = seg.toIntOrNull() ?: return cloned
                    if (idx in 0 until cur.size) cur[idx] else return cloned
                }
                else -> return cloned
            }
        }
        val lastSeg = segments.last()
        when (cur) {
            is BsonDocument -> cur[lastSeg] = newValue
            is BsonArray -> {
                val idx = lastSeg.toIntOrNull()
                if (idx != null && idx in 0 until cur.size) {
                    cur[idx] = newValue
                }
            }
            else -> Unit
        }
        return cloned
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

    fun getRawDocumentCollection(
        profile: MongoConnectionProfile,
        database: String,
        collection: String
    ): com.mongodb.client.MongoCollection<Document> {
        val client = getOrCreateClient(profile)
        return client.getDatabase(database).getCollection(collection)
    }

    fun invalidateMetaCacheForCollection(profileId: String, database: String, collection: String) {
        invalidateCollectionMetaCache(profileId, database, collection)
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

    private fun buildClientSettings(
        profile: MongoConnectionProfile,
        testTimeoutMs: Int? = null
    ): MongoClientSettings {
        val uri = profile.effectiveConnectionUri()
        val connStr = ConnectionString(uri)
        val clusterTimeoutMs = testTimeoutMs?.toLong() ?: (profile.connectTimeoutMs?.toLong()?.coerceIn(1000L, 60_000L) ?: 3000L)
        val connectTimeout = testTimeoutMs ?: (profile.connectTimeoutMs?.coerceIn(1000, 60_000) ?: 3000)
        val socketTimeout = testTimeoutMs ?: (profile.socketTimeoutMs?.coerceIn(1000, 120_000) ?: 15000)

        val builder = MongoClientSettings.builder()
            .applyConnectionString(connStr)
            .applyToClusterSettings { cluster ->
                cluster.serverSelectionTimeout(clusterTimeoutMs, TimeUnit.MILLISECONDS)
            }
            .applyToSocketSettings { socket ->
                socket.connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
                socket.readTimeout(socketTimeout.toLong(), TimeUnit.MILLISECONDS)
            }

        val tlsActive = profile.effectiveTlsEnabled()
        val allowInvalidHost = profile.effectiveAllowInvalidHostnames()
        val allowInvalidCerts = profile.effectiveAllowInvalidCertificates()
        val needsCustomSsl = MongoSslHelper.needsCustomSslContext(
            caFilePath = profile.tlsCaFilePath,
            clientCertFilePath = profile.tlsClientCertFilePath,
            allowInvalidCerts = allowInvalidCerts
        )

        if (tlsActive || needsCustomSsl || allowInvalidHost) {
            val customContext = if (needsCustomSsl) {
                MongoSslHelper.buildSslContext(
                    caFilePath = profile.tlsCaFilePath,
                    clientCertFilePath = profile.tlsClientCertFilePath,
                    allowInvalidCerts = allowInvalidCerts,
                    projectBasePath = projectBasePath
                )
            } else {
                null
            }
            builder.applyToSslSettings { ssl ->
                ssl.enabled(true)
                if (allowInvalidHost) {
                    ssl.invalidHostNameAllowed(true)
                }
                if (customContext != null) {
                    ssl.context(customContext)
                }
            }
        }

        return builder.build()
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
        clientFingerprints.clear()
        unlockedReadOnlyProfiles.clear()
        indexCache.clear()
        estimatedCountCache.clear()
        snapshot.forEach { safeClose(it) }
    }
}
