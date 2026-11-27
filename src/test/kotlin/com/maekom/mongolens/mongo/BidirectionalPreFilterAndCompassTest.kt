package com.maekom.mongolens.mongo

import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exhaustive JUnit 5 verification suite for:
 * 1. Bidirectional Pre-Filter Join Engine:
 *    (a) Simultaneous base + target pre-filter cross-matching (multi-key AND, schema types, LATEST_ONE / UNWIND_ALL)
 *    (b) INNER JOIN (`excludeUnmatched = true`) with 0 target matches -> short-circuits WITHOUT querying base collection
 *    (c) LEFT JOIN (`excludeUnmatched = false`) with target filter -> preserves base rows and joins matching target docs
 *    (d) `Int32` <-> `Int64` cross-type key normalization + 10,000-doc safety cap automatic fallback
 * 2. Real-time MongoDB Compass `Aggregations` 100%-compatible Pipeline Serialization:
 *    - UI prefix normalization (`기준(auth_history).user_id`), blank key row stripping & 0-key detection
 *    - Compass-safe `let` variables (`joinKey0`, `joinKey1`), String `"1234"` preservation, `_id` `{ "$oid": "..." }`
 *    - `$lookup.pipeline` inner `$match` (normal target filters at top-level + separate `"$expr": { "$and": [...] }`) -> `$sort` -> `$limit: 1`
 *    - `LEFT JOIN` early `$limit` BEFORE `$lookup` vs `INNER JOIN` `$limit` AFTER `$match: { alias: { $ne: [] } }`
 *    - Empty `$sort: {}` omission and Extended JSON `{ "$numberInt": "-1" }` flattening to pure Int `-1` in `JsonMode.RELAXED`
 */
class BidirectionalPreFilterAndCompassTest {

    /**
     * Lightweight in-memory MongoDB `find` simulator that tracks every queried collection and filter,
     * evaluating `$and`, `$or`, `$in`, `$ne`, `$nin`, `$exists`, and direct equality with BSON numeric equivalence.
     */
    private class RecordingFakeSource(
        private val collections: Map<String, List<BsonDocument>>
    ) : BidirectionalPreFilterJoinEngine.CollectionQuerySource {

        data class RecordedQuery(
            val collection: String,
            val filter: BsonDocument,
            val sort: BsonDocument?,
            val limit: Int?
        )

        val queries = mutableListOf<RecordedQuery>()

        override fun find(
            collection: String,
            filter: BsonDocument,
            sort: BsonDocument?,
            limit: Int?
        ): List<BsonDocument> {
            queries.add(RecordedQuery(collection, filter.clone(), sort?.clone(), limit))
            val all = collections[collection].orEmpty()
            val matched = all.filter { matchesFilter(it, filter) }
            val limited = if (limit != null && limit > 0) matched.take(limit) else matched
            return limited.map { it.clone() }
        }

        private fun matchesFilter(doc: BsonDocument, filter: BsonDocument): Boolean {
            for ((k, v) in filter) {
                when (k) {
                    "\$and" -> {
                        val arr = v as? BsonArray ?: return false
                        if (!arr.all { elem -> elem is BsonDocument && matchesFilter(doc, elem) }) return false
                    }
                    "\$or" -> {
                        val arr = v as? BsonArray ?: return false
                        if (!arr.any { elem -> elem is BsonDocument && matchesFilter(doc, elem) }) return false
                    }
                    else -> {
                        val fieldVals = MultiKeyJoinFilterEngine.extractFlattenedValues(doc, k)
                        val rawFieldPresent = doc.containsKey(k) || fieldVals.isNotEmpty()
                        if (v is BsonDocument && v.keys.any { it.startsWith("$") }) {
                            for ((op, opVal) in v) {
                                when (op) {
                                    "\$ne" -> {
                                        if (opVal is BsonNull) {
                                            if (fieldVals.isEmpty()) return false
                                        } else {
                                            if (fieldVals.any { MultiKeyJoinFilterEngine.bsonValuesEqual(it, opVal) }) return false
                                        }
                                    }
                                    "\$in" -> {
                                        val list = (opVal as? BsonArray)?.values.orEmpty()
                                        if (!fieldVals.any { fv -> list.any { lv -> MultiKeyJoinFilterEngine.bsonValuesEqual(fv, lv) } }) {
                                            return false
                                        }
                                    }
                                    "\$nin" -> {
                                        val list = (opVal as? BsonArray)?.values.orEmpty()
                                        if (fieldVals.any { fv -> list.any { lv -> MultiKeyJoinFilterEngine.bsonValuesEqual(fv, lv) } }) {
                                            return false
                                        }
                                    }
                                    "\$exists" -> {
                                        val want = (opVal as? BsonBoolean)?.value ?: true
                                        if (rawFieldPresent != want) return false
                                    }
                                    else -> return false
                                }
                            }
                        } else {
                            if (!fieldVals.any { MultiKeyJoinFilterEngine.bsonValuesEqual(it, v) }) return false
                        }
                    }
                }
            }
            return true
        }
    }

    @Test
    fun `a - simultaneous base and target pre-filters narrow both sides and cross-match accurately`() {
        val baseDocs = listOf(
            BsonDocument("_id", BsonInt32(1))
                .append("user_id", BsonString("1234"))
                .append("tenant_id", BsonString("T1"))
                .append("status", BsonString("PAID")),
            BsonDocument("_id", BsonInt32(2))
                .append("user_id", BsonString("1234"))
                .append("tenant_id", BsonString("T2")) // fails 2nd composite key (tenant_id)
                .append("status", BsonString("PAID")),
            BsonDocument("_id", BsonInt32(3))
                .append("user_id", BsonString("9999")) // fails base user_id filter
                .append("tenant_id", BsonString("T1"))
                .append("status", BsonString("PAID"))
        )
        val targetDocs = listOf(
            // Older approved record for (1234, T1)
            BsonDocument("_id", BsonInt32(10))
                .append("uid", BsonString("1234"))
                .append("tenant", BsonString("T1"))
                .append("result", BsonString("OK"))
                .append("note", BsonString("older")),
            // Latest approved record for (1234, T1) -> should be picked by LATEST_ONE
            BsonDocument("_id", BsonInt32(20))
                .append("uid", BsonString("1234"))
                .append("tenant", BsonString("T1"))
                .append("result", BsonString("OK"))
                .append("note", BsonString("latest")),
            // Rejected record for (1234, T2) -> filtered out by target pre-filter result == "OK"
            BsonDocument("_id", BsonInt32(30))
                .append("uid", BsonString("1234"))
                .append("tenant", BsonString("T2"))
                .append("result", BsonString("FAIL"))
                .append("note", BsonString("failed"))
        )

        val source = RecordingFakeSource(mapOf("orders" to baseDocs, "auth_history" to targetDocs))
        val joinSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id", "tenant_id", "status"),
            selectedLeftFields = listOf("_id", "user_id", "tenant_id"),
            baseConditions = listOf(
                // Schema type is String -> "1234" MUST stay BsonString("1234")!
                JoinCondition(JoinConditionTarget.LEFT, "기준(orders).user_id", JoinConditionOperator.EQ, "1234"),
                JoinCondition(JoinConditionTarget.LEFT, "status", JoinConditionOperator.EQ, "PAID")
            ),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "auth_history",
                    localField = "user_id",
                    foreignField = "uid",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "user_id", "uid"),
                        JoinKeyPair(JoinSourceRef.Base, "tenant_id", "tenant")
                    ),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("uid", "tenant", "result", "note"),
                    selectedColumns = listOf("result", "note"),
                    conditions = listOf(
                        JoinCondition(JoinConditionTarget.RIGHT, "result", JoinConditionOperator.EQ, "OK")
                    ),
                    excludeUnmatched = true,
                    fieldTypes = mapOf("uid" to "String", "result" to "String")
                )
            ),
            limitOption = 50,
            leftFieldTypes = mapOf("user_id" to "String", "tenant_id" to "String", "status" to "String")
        )

        val outcome = BidirectionalPreFilterJoinEngine.execute(source, joinSpec)
        assertTrue(outcome is BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed)
        val completed = outcome as BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed

        assertTrue(completed.baseCollectionQueried)
        assertEquals(1, completed.preFilteredTargetCount)
        // Target was queried FIRST (pre-filter), then Base with pinpoint $in!
        assertEquals(listOf("auth_history", "orders"), source.queries.map { it.collection })

        // Only order _id=1 matches both (user_id="1234", tenant_id="T1") & target result="OK", picking latest note="latest"
        assertEquals(1, completed.documents.size)
        val row = completed.documents[0]
        assertEquals(BsonInt32(1), row["_id"])
        assertEquals(BsonString("1234"), row["user_id"])
        assertEquals(BsonString("OK"), row["auth_history_result"])
        assertEquals(BsonString("latest"), row["auth_history_note"])
    }

    @Test
    fun `b - INNER JOIN target pre-filter with 0 matches short-circuits WITHOUT querying base collection`() {
        val baseDocs = listOf(
            BsonDocument("_id", BsonInt32(1)).append("user_id", BsonString("u1")),
            BsonDocument("_id", BsonInt32(2)).append("user_id", BsonString("u2"))
        )
        val targetDocs = listOf(
            BsonDocument("_id", BsonInt32(10)).append("uid", BsonString("u1")).append("result", BsonString("FAIL"))
        )

        val source = RecordingFakeSource(mapOf("orders" to baseDocs, "auth_history" to targetDocs))
        val joinSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id"),
            selectedLeftFields = listOf("_id", "user_id"),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "auth_history",
                    localField = "user_id",
                    foreignField = "uid",
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("uid", "result"),
                    selectedColumns = listOf("result"),
                    conditions = listOf(
                        JoinCondition(JoinConditionTarget.RIGHT, "result", JoinConditionOperator.EQ, "NON_EXISTENT")
                    ),
                    excludeUnmatched = true
                )
            ),
            limitOption = 50
        )

        val outcome = BidirectionalPreFilterJoinEngine.execute(source, joinSpec)
        assertTrue(outcome is BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed)
        val completed = outcome as BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed

        assertTrue(completed.documents.isEmpty())
        assertFalse(completed.baseCollectionQueried)
        // Base collection ("orders") MUST NOT have been queried at all!
        assertEquals(listOf("auth_history"), source.queries.map { it.collection })
        assertEquals(0, source.queries.count { it.collection == "orders" })
    }

    @Test
    fun `c - LEFT JOIN with target filter preserves unmatched base rows and joins filtered target docs`() {
        val baseDocs = listOf(
            BsonDocument("_id", BsonInt32(1)).append("user_id", BsonString("u1")).append("region", BsonString("KR")),
            BsonDocument("_id", BsonInt32(2)).append("user_id", BsonString("u2")).append("region", BsonString("KR")),
            BsonDocument("_id", BsonInt32(3)).append("user_id", BsonString("u3")).append("region", BsonString("US"))
        )
        val targetDocs = listOf(
            BsonDocument("_id", BsonInt32(10)).append("uid", BsonString("u1")).append("result", BsonString("OK")),
            // u2 has a target doc, but result="FAIL" fails target filter -> u2 base row MUST still be preserved!
            BsonDocument("_id", BsonInt32(11)).append("uid", BsonString("u2")).append("result", BsonString("FAIL"))
        )

        val source = RecordingFakeSource(mapOf("orders" to baseDocs, "auth_history" to targetDocs))
        val joinSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id", "region"),
            selectedLeftFields = listOf("_id", "user_id"),
            baseConditions = listOf(
                JoinCondition(JoinConditionTarget.LEFT, "region", JoinConditionOperator.EQ, "KR")
            ),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "auth_history",
                    localField = "user_id",
                    foreignField = "uid",
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("uid", "result"),
                    selectedColumns = listOf("result"),
                    conditions = listOf(
                        JoinCondition(JoinConditionTarget.RIGHT, "result", JoinConditionOperator.EQ, "OK")
                    ),
                    excludeUnmatched = false // LEFT JOIN
                )
            ),
            limitOption = 50
        )

        val outcome = BidirectionalPreFilterJoinEngine.execute(source, joinSpec)
        assertTrue(outcome is BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed)
        val completed = outcome as BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed

        // Base queried FIRST (pre-limited by Limit=50), then Target batch `$in`!
        assertEquals(listOf("orders", "auth_history"), source.queries.map { it.collection })
        assertEquals(50, source.queries[0].limit)

        // Both KR rows (u1, u2) preserved; u1 has auth_history_result="OK", u2 has null/absent joined column
        assertEquals(2, completed.documents.size)
        val byUser = completed.documents.associateBy { it.getString("user_id").value }
        assertEquals(BsonString("OK"), byUser["u1"]?.get("auth_history_result"))
        assertNull(byUser["u2"]?.get("auth_history_result"))
    }

    @Test
    fun `d - Int32 and Int64 join keys cross-match via normalizeJoinKey and safety cap triggers fallback`() {
        // Key normalization checks
        assertEquals(
            BidirectionalPreFilterJoinEngine.normalizeJoinKey(BsonInt32(42)),
            BidirectionalPreFilterJoinEngine.normalizeJoinKey(BsonInt64(42L))
        )
        assertNotEquals(
            BidirectionalPreFilterJoinEngine.normalizeJoinKey(BsonInt32(42)),
            BidirectionalPreFilterJoinEngine.normalizeJoinKey(BsonString("42"))
        )
        assertNull(BidirectionalPreFilterJoinEngine.normalizeJoinKey(BsonNull.VALUE))

        val baseDocs = listOf(
            BsonDocument("_id", BsonInt32(1)).append("account_id", BsonInt32(42)).append("seq", BsonInt64(7L)),
            BsonDocument("_id", BsonInt32(2)).append("account_id", BsonInt32(99)).append("seq", BsonInt64(8L))
        )
        val targetDocs = listOf(
            // account_id is Int64(42L) vs base Int32(42), and seq is Int32(7) vs base Int64(7L)!
            BsonDocument("_id", BsonInt32(100))
                .append("acc_id", BsonInt64(42L))
                .append("seq_no", BsonInt32(7))
                .append("grade", BsonString("VIP"))
        )

        val source = RecordingFakeSource(mapOf("users" to baseDocs, "profiles" to targetDocs))
        val joinSpec = JoinSpec(
            leftCollection = "users",
            allLeftFields = listOf("_id", "account_id", "seq"),
            selectedLeftFields = listOf("_id", "account_id"),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "profiles",
                    localField = "account_id",
                    foreignField = "acc_id",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "account_id", "acc_id"),
                        JoinKeyPair(JoinSourceRef.Base, "seq", "seq_no")
                    ),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("acc_id", "seq_no", "grade"),
                    selectedColumns = listOf("grade"),
                    conditions = listOf(
                        JoinCondition(JoinConditionTarget.RIGHT, "grade", JoinConditionOperator.EQ, "VIP")
                    ),
                    excludeUnmatched = true
                )
            ),
            limitOption = 20
        )

        val outcome = BidirectionalPreFilterJoinEngine.execute(source, joinSpec)
        assertTrue(outcome is BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed)
        val completed = outcome as BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.Completed
        assertEquals(1, completed.documents.size)
        assertEquals(BsonInt32(1), completed.documents[0]["_id"])
        assertEquals(BsonString("VIP"), completed.documents[0]["profiles_grade"])

        // Exceeding safety cap (cap = 0 with 1 target doc) MUST return FallbackToLookup cleanly
        val fallbackOutcome = BidirectionalPreFilterJoinEngine.execute(
            source = source,
            joinSpec = joinSpec,
            targetPreFilterCap = 0
        )
        assertTrue(fallbackOutcome is BidirectionalPreFilterJoinEngine.PreFilterExecutionResult.FallbackToLookup)
    }

    @Test
    fun `e - Compass pipeline serializer enforces pre-filter order, UI prefix strip, joinKey0 let, ObjectId, and blank-key guard`() {
        val sampleOid = "65f1a2b3c4d5e6f7a8b9c0d1"

        // Guard: spec with only blank join keys MUST fail hasValidJoinKeys
        val invalidSpec = JoinSpec(
            leftCollection = "auth_history",
            allLeftFields = listOf("_id", "user_id"),
            selectedLeftFields = listOf("_id"),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "users",
                    localField = "",
                    foreignField = "",
                    keyPairs = listOf(JoinKeyPair(JoinSourceRef.Base, "  ", "user_id")),
                    allColumns = listOf("_id"),
                    selectedColumns = listOf("_id")
                )
            )
        )
        assertFalse(CompassPipelineSerializer.hasValidJoinKeys(invalidSpec))

        val spec = JoinSpec(
            leftCollection = "auth_history",
            allLeftFields = listOf("_id", "user_id", "tenant_id", "created_at"),
            selectedLeftFields = listOf("user_id", "tenant_id"), // _id omitted -> "_id": 0
            baseConditions = listOf(
                JoinCondition(JoinConditionTarget.LEFT, "기준(auth_history).user_id", JoinConditionOperator.EQ, "1234"),
                JoinCondition(JoinConditionTarget.LEFT, "_id", JoinConditionOperator.EQ, sampleOid)
            ),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "users",
                    localField = "기준(auth_history)._id",
                    foreignField = "auth_id",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "기준(auth_history)._id", "auth_id"),
                        JoinKeyPair(JoinSourceRef.Base, "기준(auth_history).user_id", "user_id"),
                        // Blank incomplete row MUST be stripped automatically!
                        JoinKeyPair(JoinSourceRef.Base, "tenant_id", "   ")
                    ),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("_id", "user_id", "status", "email"),
                    selectedColumns = listOf("email"),
                    conditions = listOf(
                        JoinCondition(JoinConditionTarget.RIGHT, "status", JoinConditionOperator.EQ, "ACTIVE")
                    ),
                    excludeUnmatched = true,
                    fieldTypes = mapOf("status" to "String", "user_id" to "String")
                )
            ),
            limitOption = 50,
            leftFieldTypes = mapOf("user_id" to "String", "_id" to "ObjectId")
        )

        assertTrue(CompassPipelineSerializer.hasValidJoinKeys(spec))

        val json = CompassPipelineSerializer.formatCompassPipelineJson(spec, sortRaw = "{}")
        // Must contain standard Relaxed Extended JSON ObjectId and ZERO empty $sort / ZERO UI prefix / ZERO $numberInt
        assertTrue(json.contains("\"\$oid\": \"$sampleOid\""))
        assertFalse(json.contains("기준("))
        assertFalse(json.contains("\"\$sort\": {}"))
        assertFalse(json.contains("\$numberInt"))

        val stages = Document.parse("{\"p\": $json}").getList("p", Document::class.java)
        // Stage 0: Base pre-filter $match with String "1234" preserved + ObjectId _id
        val topMatch = stages[0].get("\$match", Document::class.java)
        assertNotNull(topMatch)
        assertEquals("1234", topMatch.getString("user_id"))
        assertEquals(ObjectId(sampleOid), topMatch.getObjectId("_id"))

        // Stage 1: $lookup with joinKey0 & joinKey1 (incomplete 3rd row excluded!)
        val lookup = stages[1].get("\$lookup", Document::class.java)
        assertNotNull(lookup)
        val letDoc = lookup.get("let", Document::class.java)
        assertEquals(2, letDoc.size)
        assertEquals("\$_id", letDoc.getString("joinKey0"))
        assertEquals("\$user_id", letDoc.getString("joinKey1"))

        // $lookup.pipeline: 1) $match (normal filter `status: "ACTIVE"` at top-level + separate `$expr.$and`), 2) $sort, 3) $limit: 1
        val inner = lookup.getList("pipeline", Document::class.java)
        assertEquals(3, inner.size)
        val innerMatch = inner[0].get("\$match", Document::class.java)
        assertEquals("ACTIVE", innerMatch.getString("status"))
        assertEquals(listOf("status", "\$expr"), innerMatch.keys.toList())
        val innerExprAnd = innerMatch.get("\$expr", Document::class.java).getList("\$and", Document::class.java)
        assertEquals(2, innerExprAnd.size)
        assertEquals(Document("\$eq", listOf("\$auth_id", "\$\$joinKey0")), innerExprAnd[0])
        assertEquals(Document("\$eq", listOf("\$user_id", "\$\$joinKey1")), innerExprAnd[1])
        assertEquals(Document("_id", -1), inner[1].get("\$sort", Document::class.java))
        assertEquals(1, inner[2].getInteger("\$limit"))

        // Stage 2: INNER JOIN excludeUnmatched `$ne: []`
        val excludeStage = stages[2].get("\$match", Document::class.java)
        assertEquals(emptyList<Any>(), excludeStage.get("users", Document::class.java).getList("\$ne", Any::class.java))

        // Stage 3: Deferred `$limit: 50` AFTER INNER exclude
        assertEquals(50, stages[3].getInteger("\$limit"))

        // Stage 4: `$project` with `_id: 0` (since omitted) and `1` only for selected fields
        val proj = stages[4].get("\$project", Document::class.java)
        assertEquals(0, proj.getInteger("_id"))
        assertEquals(1, proj.getInteger("user_id"))
        assertEquals(1, proj.getInteger("tenant_id"))
        assertEquals(1, proj.getInteger("users.email"))
        assertFalse(proj.containsKey("created_at"))
    }

    @Test
    fun `f - LEFT JOIN emits early $limit before $lookup when unsorted, and Compass sort flattens $numberInt`() {
        val leftJoinSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id"),
            selectedLeftFields = listOf("_id", "user_id"),
            baseConditions = listOf(
                JoinCondition(JoinConditionTarget.LEFT, "user_id", JoinConditionOperator.EQ, "1234")
            ),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "users",
                    localField = "user_id",
                    foreignField = "user_id",
                    matchStrategy = JoinMatchStrategy.UNWIND_ALL,
                    allColumns = listOf("_id", "name"),
                    selectedColumns = listOf("_id", "name"),
                    excludeUnmatched = false // LEFT JOIN
                )
            ),
            limitOption = 30,
            leftFieldTypes = mapOf("user_id" to "String")
        )

        // 1. Unsorted LEFT JOIN: Stage 0 = $match, Stage 1 = early $limit: 30 BEFORE Stage 2 = $lookup!
        val jsonUnsorted = CompassPipelineSerializer.formatCompassPipelineJson(leftJoinSpec, sortRaw = "  ")
        assertFalse(jsonUnsorted.contains("\"\$sort\""))
        val stagesUnsorted = Document.parse("{\"p\": $jsonUnsorted}").getList("p", Document::class.java)
        assertTrue(stagesUnsorted[0].containsKey("\$match"))
        assertEquals(30, stagesUnsorted[1].getInteger("\$limit"))
        assertTrue(stagesUnsorted[2].containsKey("\$lookup"))
        val unwindDoc = stagesUnsorted[3].get("\$unwind", Document::class.java)
        assertEquals(true, unwindDoc.getBoolean("preserveNullAndEmptyArrays"))

        // 2. Extended JSON sort `{ "field": { "$numberInt": "-1" } }` -> pure Int `-1`, ZERO `$numberInt`
        val jsonSorted = CompassPipelineSerializer.formatCompassPipelineJson(
            leftJoinSpec,
            sortRaw = """{ "field": { "${'$'}numberInt": "-1" } }"""
        )
        assertFalse(jsonSorted.contains("\$numberInt"))
        assertTrue(jsonSorted.contains("\"field\": -1"))
        val stagesSorted = Document.parse("{\"p\": $jsonSorted}").getList("p", Document::class.java)
        assertTrue(stagesSorted[0].containsKey("\$match"))
        assertEquals(Document("field", -1), stagesSorted[1].get("\$sort", Document::class.java))
        assertTrue(stagesSorted[2].containsKey("\$lookup"))
        assertEquals(30, stagesSorted[4].getInteger("\$limit"))
    }
}
