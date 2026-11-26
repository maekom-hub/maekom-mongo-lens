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
    UNKNOWN
}

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
    private data class CachedIndexEntry(val fetchedAtMs: Long, val indexes: List<CollectionIndexInfo>)
    private data class ExplainOutcome(val scanType: IndexScanType, val indexName: String?, val errorMessage: String?)

    private val clients = ConcurrentHashMap<String, MongoClient>()
    private val clientUris = ConcurrentHashMap<String, String>()
    private val unlockedReadOnlyProfiles = ConcurrentHashMap.newKeySet<String>()
    private val indexCache = ConcurrentHashMap<String, CachedIndexEntry>()
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
        val old = clients.remove(profileId)
        if (old != null) {
            safeClose(old)
        }
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

    fun getEstimatedCount(profile: MongoConnectionProfile, database: String, collection: String): Long {
        val client = getOrCreateClient(profile)
        return client.getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
            .estimatedDocumentCount()
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
        val client = getOrCreateClient(profile)
        val docs = client.getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
            .find()
            .limit(sampleSize)
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
        val client = getOrCreateClient(profile)
        val docs = client.getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
            .find()
            .limit(sampleSize.coerceIn(1, 50))
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
     * Executes a safe, AWS DocumentDB-compatible RDB-style Join View (`JoinSpec`):
     * - Pipeline order: Base `$match` -> Deterministic `$sort` -> `$lookup` + Right Filters + Exclusion `$match`
     *   -> Post-Join `$skip` -> Post-Join `$limit(pageSize + 1)` (or `5001` in Unlimited mode) -> `$project`.
     * - Guarantees exact page sizes even when `excludeUnmatched` or `UNWIND_ALL` filters rows, with ZERO `$facet`.
     */
    fun executeJoin(
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

        val explainOutcome = explainAggregateSafe(
            db = db,
            collection = joinSpec.leftCollection,
            pipeline = pipeline,
            aggIterable = aggIterable
        )
        val detailedIndexes = listCollectionIndexInfos(profile, database, joinSpec.leftCollection)
        val collectionIndexes = detailedIndexes.map { it.summaryLine() }

        val (sampledFields, sampledFieldTypes) = extractFieldSchema(docs.take(30))

        return QueryExecutionResult(
            documents = docs,
            totalCount = totalCount,
            isApproximateCount = isApprox,
            elapsedMs = elapsedMs,
            scanType = explainOutcome.scanType,
            indexName = explainOutcome.indexName,
            sampledFields = sampledFields,
            sampledFieldTypes = sampledFieldTypes,
            hasNextPage = hasNext,
            collectionIndexes = collectionIndexes,
            detailedIndexes = detailedIndexes,
            explainError = explainOutcome.errorMessage,
            unlimitedCapHit = capHit
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
            .maxTime(15, TimeUnit.SECONDS)

        if (!projection.isEmpty()) {
            iterable.projection(projection)
        }
        if (!sort.isEmpty()) {
            iterable.sort(sort)
        }

        val docs = iterable.into(mutableListOf())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        var isApprox = false
        val totalCount: Long = try {
            if (filter.isEmpty()) {
                coll.estimatedDocumentCount()
            } else {
                coll.countDocuments(filter, CountOptions().maxTime(2, TimeUnit.SECONDS))
            }
        } catch (_: Throwable) {
            isApprox = true
            -1L
        }

        val explainOutcome = explainFindSafe(
            db = db,
            collection = collection,
            filter = filter,
            projection = projection,
            sort = sort,
            skip = safeSkip,
            limit = safeLimit,
            findIterable = iterable
        )
        val detailedIndexes = listCollectionIndexInfos(profile, database, collection)
        val collectionIndexes = detailedIndexes.map { it.summaryLine() }

        val (sampledFields, sampledFieldTypes) = extractFieldSchema(docs.take(30))

        return QueryExecutionResult(
            documents = docs,
            totalCount = totalCount,
            isApproximateCount = isApprox,
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

        val explainOutcome = explainAggregateSafe(
            db = db,
            collection = collection,
            pipeline = pipeline,
            aggIterable = iterable
        )
        val detailedIndexes = listCollectionIndexInfos(profile, database, collection)
        val collectionIndexes = detailedIndexes.map { it.summaryLine() }

        val (sampledFields, sampledFieldTypes) = extractFieldSchema(docs.take(30))

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
        if (!forceRefresh && cached != null && now - cached.fetchedAtMs < 60_000L) {
            return cached.indexes
        }
        return try {
            val client = getOrCreateClient(profile)
            val db = client.getDatabase(database)
            val coll = db.getCollection(collection, BsonDocument::class.java)
            val rawIndexes = coll.listIndexes(BsonDocument::class.java)
                .maxTime(3, TimeUnit.SECONDS)
                .into(mutableListOf())

            // Best-effort read-only collStats for per-index size bytes (ignored gracefully if restricted)
            val sizeByName: Map<String, Long> = try {
                val stats = db.runCommand(Document("collStats", collection))
                val sizesDoc = stats["indexSizes"] as? Document
                sizesDoc?.entries?.mapNotNull { (k, v) ->
                    val bytes = (v as? Number)?.toLong() ?: return@mapNotNull null
                    k to bytes
                }?.toMap().orEmpty()
            } catch (_: Throwable) {
                emptyMap()
            }

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
        } catch (_: Throwable) {
            cached?.indexes ?: emptyList()
        }
    }

    fun listCollectionIndexSummaries(
        profile: MongoConnectionProfile,
        database: String,
        collection: String
    ): List<String> = listCollectionIndexInfos(profile, database, collection).map { it.summaryLine() }

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

        // 1. Standard driver explain(QUERY_PLANNER)
        try {
            val res = parseExplainPlan(findIterable.explain(ExplainVerbosity.QUERY_PLANNER))
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        // 2. Driver explain(EXECUTION_STATS) (AWS DocumentDB often populates richer plan info here)
        try {
            val res = parseExplainPlan(findIterable.explain(ExplainVerbosity.EXECUTION_STATS))
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        // 3. Explicit db.runCommand({ explain: { find: ... }, verbosity: "executionStats" }) fallback
        val findCmd = Document("find", collection).append("filter", filter)
        if (!projection.isEmpty()) findCmd.append("projection", projection)
        if (!sort.isEmpty()) findCmd.append("sort", sort)
        if (skip > 0) findCmd.append("skip", skip)
        if (limit > 0) findCmd.append("limit", limit)

        try {
            val explainDoc = db.runCommand(Document("explain", findCmd).append("verbosity", "executionStats"))
            val res = parseExplainPlan(explainDoc)
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        // 4. Explicit db.runCommand({ explain: { find: ... }, verbosity: "queryPlanner" }) fallback
        try {
            val explainDoc = db.runCommand(Document("explain", findCmd).append("verbosity", "queryPlanner"))
            val res = parseExplainPlan(explainDoc)
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        // 5. DocumentDB 3.6/4.0 fallback without explicit verbosity field
        try {
            val explainDoc = db.runCommand(Document("explain", findCmd))
            val res = parseExplainPlan(explainDoc)
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        return ExplainOutcome(
            scanType = IndexScanType.UNKNOWN,
            indexName = null,
            errorMessage = lastError ?: "Explain output did not include a recognizable scan stage"
        )
    }

    private fun explainAggregateSafe(
        db: com.mongodb.client.MongoDatabase,
        collection: String,
        pipeline: List<BsonDocument>,
        aggIterable: com.mongodb.client.AggregateIterable<BsonDocument>
    ): ExplainOutcome {
        var lastError: String? = null

        // 1. Driver explain(QUERY_PLANNER)
        try {
            val res = parseExplainPlan(aggIterable.explain(ExplainVerbosity.QUERY_PLANNER))
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        // 2. Driver explain(EXECUTION_STATS)
        try {
            val res = parseExplainPlan(aggIterable.explain(ExplainVerbosity.EXECUTION_STATS))
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        val aggCmd = Document("aggregate", collection)
            .append("pipeline", pipeline)
            .append("cursor", Document())

        // 3. runCommand({ explain: { aggregate: ... }, verbosity: "executionStats" })
        try {
            val explainDoc = db.runCommand(Document("explain", aggCmd).append("verbosity", "executionStats"))
            val res = parseExplainPlan(explainDoc)
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        // 4. runCommand({ explain: { aggregate: ... } })
        try {
            val explainDoc = db.runCommand(Document("explain", aggCmd))
            val res = parseExplainPlan(explainDoc)
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
            lastError = summarizeExplainError(t)
        }

        // 5. Legacy MongoDB & AWS DocumentDB direct { aggregate: ..., pipeline: [...], explain: true }
        try {
            val explainDoc = db.runCommand(
                Document("aggregate", collection)
                    .append("pipeline", pipeline)
                    .append("explain", true)
            )
            val res = parseExplainPlan(explainDoc)
            if (res.first != IndexScanType.UNKNOWN) return ExplainOutcome(res.first, res.second, null)
        } catch (t: Throwable) {
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
        snapshot.forEach { safeClose(it) }
    }
}
