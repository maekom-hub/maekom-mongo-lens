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

data class QueryExecutionResult(
    val documents: List<BsonDocument>,
    val totalCount: Long, // -1 means count timed out / unknown
    val isApproximateCount: Boolean,
    val elapsedMs: Long,
    val scanType: IndexScanType,
    val indexName: String?,
    val sampledFields: List<String>
)

class MongoConnectionManager(project: Project) : Disposable {
    private val clients = ConcurrentHashMap<String, MongoClient>()
    private val clientUris = ConcurrentHashMap<String, String>()
    private val unlockedReadOnlyProfiles = ConcurrentHashMap.newKeySet<String>()
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
        sampleSize: Int = 20
    ): List<String> {
        val client = getOrCreateClient(profile)
        val docs = client.getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
            .find()
            .limit(sampleSize)
            .maxTime(3, TimeUnit.SECONDS)
            .into(mutableListOf())
        return extractFieldNames(docs)
    }

    fun executeFind(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        filterRaw: String,
        projectionRaw: String,
        sortRaw: String,
        skip: Int,
        limit: Int
    ): QueryExecutionResult {
        val filter = MongoShellNormalizer.parseBsonDocument(filterRaw)
        val projection = MongoShellNormalizer.parseBsonDocument(projectionRaw)
        val sort = MongoShellNormalizer.parseBsonDocument(sortRaw)

        val client = getOrCreateClient(profile)
        val coll = client.getDatabase(database).getCollection(collection, BsonDocument::class.java)

        val startNs = System.nanoTime()
        val iterable = coll.find(filter)
            .skip(skip.coerceAtLeast(0))
            .limit(limit.coerceIn(1, 5000))
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

        val (scanType, indexName) = try {
            val explainDoc = iterable.explain(ExplainVerbosity.QUERY_PLANNER)
            parseExplainPlan(explainDoc)
        } catch (_: Throwable) {
            IndexScanType.UNKNOWN to null
        }

        val sampledFields = extractFieldNames(docs.take(20))

        return QueryExecutionResult(
            documents = docs,
            totalCount = totalCount,
            isApproximateCount = isApprox,
            elapsedMs = elapsedMs,
            scanType = scanType,
            indexName = indexName,
            sampledFields = sampledFields
        )
    }

    fun executeAggregate(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        pipeline: List<BsonDocument>
    ): QueryExecutionResult {
        val client = getOrCreateClient(profile)
        val coll = client.getDatabase(database).getCollection(collection, BsonDocument::class.java)

        val startNs = System.nanoTime()
        val iterable = coll.aggregate(pipeline).maxTime(15, TimeUnit.SECONDS)
        val docs = iterable.into(mutableListOf())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        val (scanType, indexName) = try {
            val explainDoc = iterable.explain(ExplainVerbosity.QUERY_PLANNER)
            parseExplainPlan(explainDoc)
        } catch (_: Throwable) {
            IndexScanType.UNKNOWN to null
        }

        val sampledFields = extractFieldNames(docs.take(20))

        return QueryExecutionResult(
            documents = docs,
            totalCount = docs.size.toLong(),
            isApproximateCount = false,
            elapsedMs = elapsedMs,
            scanType = scanType,
            indexName = indexName,
            sampledFields = sampledFields
        )
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
        coll.replaceOne(BsonDocument("_id", originalId), replacement)
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
        coll.deleteOne(BsonDocument("_id", id))
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

    private fun parseExplainPlan(explainDoc: Document): Pair<IndexScanType, String?> {
        var foundIxScan: String? = null
        var foundCollScan = false

        fun walk(node: Any?) {
            when (node) {
                is Document -> {
                    val stage = node.getString("stage")
                    if (stage != null) {
                        when (stage.uppercase()) {
                            "IXSCAN", "IDHACK", "COUNT_SCAN", "DISTINCT_SCAN", "EXPRESS_IXSCAN" -> {
                                val idxName = node.getString("indexName") ?: stage
                                if (foundIxScan == null) foundIxScan = idxName
                            }
                            "COLLSCAN" -> foundCollScan = true
                        }
                    }
                    for ((_, v) in node) {
                        walk(v)
                    }
                }
                is List<*> -> node.forEach { walk(it) }
            }
        }

        // Check queryPlanner.winningPlan first (or stages[0].$cursor.queryPlanner.winningPlan for aggregate)
        val qp = explainDoc["queryPlanner"]
        if (qp != null) {
            walk(qp)
        } else {
            walk(explainDoc)
        }

        return when {
            foundIxScan != null -> IndexScanType.IXSCAN to foundIxScan
            foundCollScan -> IndexScanType.COLLSCAN to null
            else -> IndexScanType.UNKNOWN to null
        }
    }

    private fun extractFieldNames(docs: List<BsonDocument>): List<String> {
        val fields = linkedSetOf<String>()
        fun collect(doc: BsonDocument, prefix: String, depth: Int) {
            if (depth > 2) return
            for ((k, v) in doc) {
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                fields.add(path)
                if (v.isDocument) {
                    collect(v.asDocument(), path, depth + 1)
                }
            }
        }
        docs.forEach { collect(it, "", 0) }
        return fields.toList()
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
        snapshot.forEach { safeClose(it) }
    }
}
