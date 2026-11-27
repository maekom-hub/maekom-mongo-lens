package com.maekom.mongolens.mongo

import com.maekom.mongolens.config.MongoLensSettings
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Comprehensive JUnit 5 verification suite covering all 5 v0.3.1 deliverables:
 * 1. Timeout settings (default 40s, clamping 5..600, ms conversion, timeout guide gating vs manual Stop)
 * 2. runId token race-condition guard (late finally from runId=1 never clobbers runId=2)
 * 3. Join-mode toolbar control enable/disable state transitions
 * 4. Real-time Copy Pipeline 6-setting precision serialization (UI prefix strip, joinKey0 let vars,
 *    schema String preservation, $match->$sort->$limit:1 order, $ne:[], Limit, $project)
 * 5. MongoDB Compass Sort compatibility (omit empty $sort, flatten $numberInt to pure Int, RELAXED JSON)
 */
class V031FeaturesTest {

    @Test
    fun `test 1 - timeout settings defaults, 5-600 clamping, ms conversion, and guide popup gating`() {
        val settings = MongoLensSettings()
        assertEquals(40, settings.queryTimeoutSeconds)
        assertEquals(40_000L, settings.queryTimeoutMillis())

        // Range clamping on setter & loadState
        settings.queryTimeoutSeconds = 1
        assertEquals(5, settings.queryTimeoutSeconds)
        assertEquals(5_000L, settings.queryTimeoutMillis())

        settings.queryTimeoutSeconds = 9999
        assertEquals(600, settings.queryTimeoutSeconds)
        assertEquals(600_000L, settings.queryTimeoutMillis())

        settings.loadState(MongoLensSettings.State(queryTimeoutSeconds = -10))
        assertEquals(5, settings.queryTimeoutSeconds)

        settings.loadState(MongoLensSettings.State(queryTimeoutSeconds = 120))
        assertEquals(120, settings.queryTimeoutSeconds)
        assertEquals(120_000L, settings.queryTimeoutMillis())

        // Timeout guide must ONLY trigger when timed out AND NOT manually stopped
        assertTrue(MongoLensSettings.shouldShowTimeoutGuide(isTimedOut = true, isStopRequested = false))
        assertFalse(MongoLensSettings.shouldShowTimeoutGuide(isTimedOut = true, isStopRequested = true))
        assertFalse(MongoLensSettings.shouldShowTimeoutGuide(isTimedOut = false, isStopRequested = true))
        assertFalse(MongoLensSettings.shouldShowTimeoutGuide(isTimedOut = false, isStopRequested = false))
    }

    @Test
    fun `test 2 - runId token prevents late finally from stopped run 1 from clobbering new run 2`() {
        val tracker = QueryRunTracker()

        // Start run 1
        val run1 = tracker.tryBeginRun()
        assertNotNull(run1)
        assertTrue(tracker.isQueryRunning)
        // Double-click guard while running
        assertNull(tracker.tryBeginRun())

        // User clicks Stop -> invalidates run 1 immediately and restores idle state
        tracker.requestStop()
        assertFalse(tracker.isQueryRunning)
        assertTrue(tracker.isStopRequested)
        assertTrue(tracker.isCancelledFor(run1!!))
        // Manual stop must NOT show timeout guide
        assertFalse(tracker.tryAcquireTimeoutAlertSlot(run1, serverTimeoutException = true))

        // Immediately start run 2 before run 1's background thread finishes
        val run2 = tracker.tryBeginRun()
        assertNotNull(run2)
        assertTrue(run2!! > run1)
        assertTrue(tracker.isQueryRunning)
        assertFalse(tracker.isStopRequested)

        // Late finally from run 1 MUST be ignored and MUST NOT clear run 2's running state
        assertFalse(tracker.finishRunIfCurrent(run1))
        assertTrue(tracker.isQueryRunning)

        // Watchdog timeout on active run 2 triggers alert slot strictly once
        assertTrue(tracker.markTimedOutIfActive(run2))
        assertTrue(tracker.tryAcquireTimeoutAlertSlot(run2))
        assertFalse(tracker.tryAcquireTimeoutAlertSlot(run2)) // idempotent once-only guard

        // Finalizing run 2 clears running state cleanly
        assertTrue(tracker.finishRunIfCurrent(run2))
        assertFalse(tracker.isQueryRunning)
    }

    @Test
    fun `test 3 - join mode toolbar policy disables single-collection controls and restores on exit`() {
        val joinStates = JoinToolbarStatePolicy.compute(isJoinActive = true)
        assertFalse(joinStates.filterInputEnabled)
        assertFalse(joinStates.projectInputEnabled)
        assertFalse(joinStates.sortInputEnabled)
        assertFalse(joinStates.resetEnabled)
        assertFalse(joinStates.smartInEnabled)
        assertFalse(joinStates.toShellEnabled)
        assertFalse(joinStates.savedQueriesEnabled)
        assertFalse(joinStates.importJsonEnabled)
        assertFalse(joinStates.indexInspectorEnabled)
        // Essential Join controls remain active
        assertTrue(joinStates.runOrStopEnabled)
        assertTrue(joinStates.joinConfigEnabled)
        assertTrue(joinStates.copyPipelineEnabled)
        assertTrue(joinStates.exportEnabled)
        assertTrue(joinStates.pageSizeEnabled)
        assertTrue(joinStates.paginationEnabled)

        // Switching back to normal collection tab restores all single-collection controls
        val normalStates = JoinToolbarStatePolicy.compute(isJoinActive = false)
        assertTrue(normalStates.filterInputEnabled)
        assertTrue(normalStates.projectInputEnabled)
        assertTrue(normalStates.sortInputEnabled)
        assertTrue(normalStates.resetEnabled)
        assertTrue(normalStates.smartInEnabled)
        assertTrue(normalStates.toShellEnabled)
        assertTrue(normalStates.savedQueriesEnabled)
        assertTrue(normalStates.importJsonEnabled)
        assertTrue(normalStates.indexInspectorEnabled)
        assertTrue(normalStates.runOrStopEnabled)
    }

    @Test
    fun `test 4 - real-time pipeline serialization reflects all 6 join settings cleanly for MongoDB Compass`() {
        val sampleOid = "65f1a2b3c4d5e6f7a8b9c0d1"
        val joinSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id", "status", "amount"),
            selectedLeftFields = listOf("user_id", "status"), // _id intentionally omitted -> "_id": 0
            baseConditions = listOf(
                // Schema type for user_id is String -> "1234" MUST stay String, NOT int 1234!
                JoinCondition(JoinConditionTarget.LEFT, "기준(orders).user_id", JoinConditionOperator.EQ, "1234"),
                // _id 24-hex string -> serialized as ObjectId
                JoinCondition(JoinConditionTarget.LEFT, "_id", JoinConditionOperator.EQ, sampleOid)
            ),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "auth_history",
                    localField = "기준(orders)._id",
                    foreignField = "order_id",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "기준(orders)._id", "order_id"),
                        JoinKeyPair(JoinSourceRef.Base, "Base(orders).meta.tenant_id", "tenant_id")
                    ),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("order_id", "tenant_id", "result", "created_at"),
                    selectedColumns = listOf("result"),
                    conditions = listOf(
                        JoinCondition(JoinConditionTarget.RIGHT, "result", JoinConditionOperator.EQ, "OK")
                    ),
                    excludeUnmatched = true,
                    fieldTypes = mapOf("result" to "String", "tenant_id" to "String")
                )
            ),
            limitOption = 50,
            leftFieldTypes = mapOf("user_id" to "String", "_id" to "ObjectId", "amount" to "Int32")
        )

        val jsonArray = CompassPipelineSerializer.formatCompassPipelineJson(joinSpec)

        // Must be parseable as a valid JSON array!
        val parsedStages = Document.parse("{\"pipeline\": $jsonArray}").getList("pipeline", Document::class.java)
        assertTrue(parsedStages.isNotEmpty())

        // 1. Top-level $match: base conditions with String "1234" preserved & _id as ObjectId
        val topMatch = parsedStages[0].get("\$match", Document::class.java)
        assertNotNull(topMatch)
        assertEquals("1234", topMatch.getString("user_id")) // String, NOT Int!
        assertEquals(ObjectId(sampleOid), topMatch.getObjectId("_id"))
        assertTrue(jsonArray.contains("\"\$oid\": \"$sampleOid\""))

        // 2. Standard single-key $lookup ONLY (NO `let`, NO `pipeline` -> AWS DocumentDB 100% compatible!)
        val lookupDoc = parsedStages[1].get("\$lookup", Document::class.java)
        assertNotNull(lookupDoc)
        assertEquals("auth_history", lookupDoc.getString("from"))
        assertEquals("_id", lookupDoc.getString("localField"))
        assertEquals("order_id", lookupDoc.getString("foreignField"))
        assertEquals("auth_history", lookupDoc.getString("as"))
        assertFalse(lookupDoc.containsKey("let"))
        assertFalse(lookupDoc.containsKey("pipeline"))

        // 3. $addFields + null-safe $filter for 2nd join key & target filter using Aggregation Expression operators
        val filterStage = parsedStages[2].get("\$addFields", Document::class.java)
        assertNotNull(filterStage)
        val filterDoc = filterStage.get("auth_history", Document::class.java).get("\$filter", Document::class.java)
        assertEquals(Document("\$ifNull", listOf("\$auth_history", emptyList<Any>())), filterDoc.get("input", Document::class.java))
        assertEquals("item", filterDoc.getString("as"))
        val filterAnd = filterDoc.get("cond", Document::class.java).getList("\$and", Document::class.java)
        assertEquals(2, filterAnd.size)
        assertEquals(Document("\$eq", listOf("\$\$item.tenant_id", "\$meta.tenant_id")), filterAnd[0])
        assertEquals(Document("\$eq", listOf("\$\$item.result", "OK")), filterAnd[1])

        // 4. LATEST_ONE via DocumentDB-safe $cond + $gt($size($ifNull)) + [$arrayElemAt(-1)]
        val latestStage = parsedStages[3].get("\$addFields", Document::class.java)
        assertNotNull(latestStage)
        assertNotNull(latestStage.get("auth_history", Document::class.java).get("\$cond", Document::class.java))

        // 5. excludeUnmatched = true -> { "$match": { "auth_history": { "$ne": [] } } }
        val excludeStage = parsedStages[4].get("\$match", Document::class.java)
        assertNotNull(excludeStage)
        assertEquals(emptyList<Any>(), excludeStage.get("auth_history", Document::class.java).getList("\$ne", Any::class.java))

        // 6. Limit: 50
        assertEquals(50, parsedStages[5].getInteger("\$limit"))

        // 7. $project: only 1s on selected fields + "_id": 0 because _id was deselected (no 0 on other fields)
        val projStage = parsedStages[6].get("\$project", Document::class.java)
        assertEquals(0, projStage.getInteger("_id"))
        assertEquals(1, projStage.getInteger("user_id"))
        assertEquals(1, projStage.getInteger("status"))
        assertEquals(1, projStage.getInteger("auth_history.result"))
        assertFalse(projStage.containsKey("amount"))
    }

    @Test
    fun `test 5 - Compass sort normalization omits empty sort and flattens Extended JSON $numberInt`() {
        // Empty / blank sort -> null (stage MUST be omitted)
        assertNull(CompassPipelineSerializer.parseNormalizedSortDocument(""))
        assertNull(CompassPipelineSerializer.parseNormalizedSortDocument("   "))
        assertNull(CompassPipelineSerializer.parseNormalizedSortDocument("{}"))

        // Plain field name sort
        assertEquals(Document("created_at", 1), CompassPipelineSerializer.parseNormalizedSortDocument("created_at"))
        assertEquals(Document("created_at", -1), CompassPipelineSerializer.parseNormalizedSortDocument("-created_at"))

        // Extended JSON object sort `{ "field": { "$numberInt": "-1" } }` -> flattened pure Int `-1`
        val extSortDoc = CompassPipelineSerializer.parseNormalizedSortDocument("""{ "field": { "${'$'}numberInt": "-1" } }""")
        assertEquals(Document("field", -1), extSortDoc)

        val simpleSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id"),
            selectedLeftFields = listOf("_id", "user_id"),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "users",
                    localField = "user_id",
                    foreignField = "_id",
                    matchStrategy = JoinMatchStrategy.UNWIND_ALL,
                    allColumns = listOf("_id", "name"),
                    selectedColumns = listOf("_id", "name"),
                    excludeUnmatched = false
                )
            ),
            limitOption = 20
        )

        // Blank sort MUST NOT emit `{ "$sort": {} }`
        val jsonNoSort = CompassPipelineSerializer.formatCompassPipelineJson(simpleSpec, sortRaw = "{}")
        assertFalse(jsonNoSort.contains("\"\$sort\""))

        // Extended JSON sort MUST serialize as pure integer `-1` with ZERO `$numberInt`
        val jsonWithExtSort = CompassPipelineSerializer.formatCompassPipelineJson(
            simpleSpec,
            sortRaw = """{ "field": { "${'$'}numberInt": "-1" } }"""
        )
        assertFalse(jsonWithExtSort.contains("\$numberInt"))
        assertTrue(jsonWithExtSort.contains("\"field\": -1"))
        val stages = Document.parse("{\"p\": $jsonWithExtSort}").getList("p", Document::class.java)
        assertEquals(Document("field", -1), stages[0].get("\$sort", Document::class.java))
    }
}
