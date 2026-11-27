package com.maekom.mongolens.mongo

import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiKeyJoinFilterEngineTest {

    @Test
    fun `1 - prioritizes unique identifier keys as primary 1st-stage lookup key`() {
        val pairs = listOf(
            JoinKeyPair(JoinSourceRef.Base, "tenant_id", "tenant_id"),
            JoinKeyPair(JoinSourceRef.Base, "region", "region"),
            JoinKeyPair(JoinSourceRef.Base, "user_id", "user_id")
        )
        val (primary, remaining) = MultiKeyJoinFilterEngine.selectPrimaryAndRemainingPairs(pairs)
        // Both tenant_id and user_id have *_id priority; ties preserve first, while _id beats *_id
        assertEquals("tenant_id", primary.localField)
        assertEquals(2, remaining.size)

        val nonIdFirst = listOf(
            JoinKeyPair(JoinSourceRef.Base, "stcd", "stcd"),
            JoinKeyPair(JoinSourceRef.Base, "region", "region"),
            JoinKeyPair(JoinSourceRef.Base, "user_id", "user_id")
        )
        val (primaryUser, remUser) = MultiKeyJoinFilterEngine.selectPrimaryAndRemainingPairs(nonIdFirst)
        assertEquals("user_id", primaryUser.localField)
        assertEquals(listOf("stcd", "region"), remUser.map { it.localField })

        val withObjectId = listOf(
            JoinKeyPair(JoinSourceRef.Base, "memberId", "memberId"),
            JoinKeyPair(JoinSourceRef.Base, "_id", "parent_id")
        )
        val (primaryOid, remOid) = MultiKeyJoinFilterEngine.selectPrimaryAndRemainingPairs(withObjectId)
        assertEquals("_id", primaryOid.localField)
        assertEquals(1, remOid.size)
        assertEquals("memberId", remOid[0].localField)
    }

    @Test
    fun `2 - filters 35 composite-key docs with trap elimination, Int32-Int64 cross-match, and nested array path`() {
        val joinSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id", "tenant_id", "items", "seq"),
            selectedLeftFields = listOf("_id", "user_id", "tenant_id", "seq"),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "auth_history",
                    localField = "user_id",
                    foreignField = "user_id",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "user_id", "user_id"),
                        JoinKeyPair(JoinSourceRef.Base, "tenant_id", "tenant_id"),
                        JoinKeyPair(JoinSourceRef.Base, "items.code", "item_code")
                    ),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("user_id", "tenant_id", "item_code", "result"),
                    selectedColumns = listOf("result"),
                    excludeUnmatched = true // INNER JOIN
                )
            ),
            limitOption = 10
        )

        // Build 35 genuinely matching parent docs (Int32(100+i) vs Int64(100L+i) + nested array items.code)
        val rawDbDocs = mutableListOf<BsonDocument>()
        for (i in 1..35) {
            val validChild = BsonDocument()
                .append("user_id", BsonInt64(100L + i)) // Int64 vs Int32 cross-type
                .append("tenant_id", BsonInt64(100L))    // Int64(100L) vs Int32(100)
                .append("item_code", BsonString("SKU_B"))
                .append("result", BsonString("OK_$i"))
            val trapChildWrongTenant = BsonDocument()
                .append("user_id", BsonInt64(100L + i))
                .append("tenant_id", BsonInt64(999L))    // Primary user_id matches, secondary tenant_id mismatches!
                .append("item_code", BsonString("SKU_B"))
                .append("result", BsonString("TRAP_$i"))

            val parent = BsonDocument()
                .append("_id", BsonString("doc_${i.toString().padStart(2, '0')}"))
                .append("user_id", BsonInt32(100 + i))
                .append("tenant_id", BsonInt32(100))
                .append(
                    "items",
                    BsonArray(
                        listOf(
                            BsonDocument("code", BsonString("SKU_A")),
                            BsonDocument("code", BsonString("SKU_B"))
                        )
                    )
                )
                .append("seq", BsonInt32(i))
                .append("_joined_0", BsonArray(listOf(trapChildWrongTenant, validChild)))
            rawDbDocs.add(parent)
        }

        // Add 10 trap parent docs whose joined children ONLY have mismatched tenant_id
        for (t in 1..10) {
            val trapChild = BsonDocument()
                .append("user_id", BsonInt64(9000L + t))
                .append("tenant_id", BsonInt64(999L))
                .append("item_code", BsonString("SKU_B"))
                .append("result", BsonString("BAD_$t"))
            val trapParent = BsonDocument()
                .append("_id", BsonString("trap_$t"))
                .append("user_id", BsonInt32(9000 + t))
                .append("tenant_id", BsonInt32(100))
                .append("items", BsonArray(listOf(BsonDocument("code", BsonString("SKU_B")))))
                .append("seq", BsonInt32(9000 + t))
                .append("_joined_0", BsonArray(listOf(trapChild)))
            rawDbDocs.add(trapParent)
        }

        val shapedDocs = MultiKeyJoinFilterEngine.applyFullHybridJoinShaping(rawDbDocs, joinSpec)

        assertEquals(35, shapedDocs.size)
        for (i in 1..35) {
            val doc = shapedDocs[i - 1]
            assertEquals("doc_${i.toString().padStart(2, '0')}", doc.getString("_id").value)
            assertEquals("OK_$i", doc.getString("auth_history_result").value)
        }
    }

    @Test
    fun `3 - mixes INNER JOIN target 1 and LEFT JOIN target 2 accurately`() {
        val mixedSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id", "tenant_id"),
            selectedLeftFields = listOf("_id", "user_id"),
            targets = listOf(
                // Target ①: INNER JOIN (must match both user_id and tenant_id)
                JoinTargetSpec(
                    targetCollection = "users",
                    localField = "user_id",
                    foreignField = "user_id",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "user_id", "user_id"),
                        JoinKeyPair(JoinSourceRef.Base, "tenant_id", "tenant_id")
                    ),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("user_id", "tenant_id", "name"),
                    selectedColumns = listOf("name"),
                    excludeUnmatched = true
                ),
                // Target ②: LEFT JOIN (unmatched secondary key keeps parent row with null target field)
                JoinTargetSpec(
                    targetCollection = "coupons",
                    localField = "user_id",
                    foreignField = "user_id",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "user_id", "user_id"),
                        JoinKeyPair(JoinSourceRef.Base, "tenant_id", "tenant_id")
                    ),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("user_id", "tenant_id", "coupon_code"),
                    selectedColumns = listOf("coupon_code"),
                    excludeUnmatched = false
                )
            )
        )

        // Doc 1: Target ① matched, Target ② matched
        val docBothMatch = BsonDocument()
            .append("_id", BsonString("o1"))
            .append("user_id", BsonInt32(1))
            .append("tenant_id", BsonString("T1"))
            .append("_joined_0", BsonArray(listOf(BsonDocument("user_id", BsonInt32(1)).append("tenant_id", BsonString("T1")).append("name", BsonString("Alice")))))
            .append("_joined_1", BsonArray(listOf(BsonDocument("user_id", BsonInt32(1)).append("tenant_id", BsonString("T1")).append("coupon_code", BsonString("WELCOME10")))))

        // Doc 2: Target ① matched, Target ② secondary key fails -> MUST survive because Target ② is LEFT JOIN!
        val docLeftNull = BsonDocument()
            .append("_id", BsonString("o2"))
            .append("user_id", BsonInt32(2))
            .append("tenant_id", BsonString("T1"))
            .append("_joined_0", BsonArray(listOf(BsonDocument("user_id", BsonInt32(2)).append("tenant_id", BsonString("T1")).append("name", BsonString("Bob")))))
            .append("_joined_1", BsonArray(listOf(BsonDocument("user_id", BsonInt32(2)).append("tenant_id", BsonString("WRONG_TENANT")).append("coupon_code", BsonString("NOPE")))))

        // Doc 3: Target ① secondary key fails -> MUST be dropped because Target ① is INNER JOIN!
        val docInnerDrop = BsonDocument()
            .append("_id", BsonString("o3"))
            .append("user_id", BsonInt32(3))
            .append("tenant_id", BsonString("T1"))
            .append("_joined_0", BsonArray(listOf(BsonDocument("user_id", BsonInt32(3)).append("tenant_id", BsonString("WRONG_TENANT")).append("name", BsonString("Eve")))))
            .append("_joined_1", BsonArray(listOf(BsonDocument("user_id", BsonInt32(3)).append("tenant_id", BsonString("T1")).append("coupon_code", BsonString("C3")))))

        val result = MultiKeyJoinFilterEngine.applyFullHybridJoinShaping(
            listOf(docBothMatch, docLeftNull, docInnerDrop),
            mixedSpec
        )

        assertEquals(2, result.size)
        assertEquals("o1", result[0].getString("_id").value)
        assertEquals("Alice", result[0].getString("users_name").value)
        assertEquals("WELCOME10", result[0].getString("coupons_coupon_code").value)

        assertEquals("o2", result[1].getString("_id").value)
        assertEquals("Bob", result[1].getString("users_name").value)
        assertFalse(result[1].containsKey("coupons_coupon_code"))

        // Also verify single-join filterSingleJoinInMemory preserves empty array on LEFT JOIN vs dropping on INNER JOIN
        val remaining = listOf(JoinKeyPair(JoinSourceRef.Base, "tenant_id", "tenant_id"))
        val leftArr = MultiKeyJoinFilterEngine.filterSingleJoinInMemory(listOf(docLeftNull), "_joined_1", remaining, excludeUnmatched = false)
        assertEquals(1, leftArr.size)
        assertEquals(0, leftArr[0].getArray("_joined_1").size)
        val innerArr = MultiKeyJoinFilterEngine.filterSingleJoinInMemory(listOf(docLeftNull), "_joined_1", remaining, excludeUnmatched = true)
        assertEquals(0, innerArr.size)
    }

    @Test
    fun `4 - Next and Prev multi-page slicing across 35 docs yields 0 missing, 0 duplicates, and signature isolation`() {
        val docs = (1..35).map { i ->
            BsonDocument("_id", BsonString("doc_${i.toString().padStart(2, '0')}"))
                .append("seq", BsonInt32(i))
        }
        val cache = HybridJoinSessionCache(maxEntries = 4)
        val sigA = cache.buildSignature("conn-docdb", "appdb", "orders", "{}", "{\"seq\":1}", "joinSigV1")

        // Page 1 (index 0): 10 docs, hasNextPage = true
        val p1 = cache.putAndSlice("conn-docdb", sigA, docs, pageIndex = 0, pageSize = 10)
        assertFalse(p1.fromCache)
        assertEquals(10, p1.pageDocs.size)
        assertEquals(35, p1.totalCount)
        assertTrue(p1.hasNextPage)

        // Page 2 (index 1): 10 docs, hasNextPage = true
        val p2 = cache.getPage(sigA, pageIndex = 1, pageSize = 10)!!
        assertTrue(p2.fromCache)
        assertEquals(10, p2.pageDocs.size)
        assertTrue(p2.hasNextPage)

        // Page 3 (index 2): 10 docs, hasNextPage = true
        val p3 = cache.getPage(sigA, pageIndex = 2, pageSize = 10)!!
        assertEquals(10, p3.pageDocs.size)
        assertTrue(p3.hasNextPage)

        // Page 4 (index 3): 5 docs, hasNextPage = false
        val p4 = cache.getPage(sigA, pageIndex = 3, pageSize = 10)!!
        assertEquals(5, p4.pageDocs.size)
        assertFalse(p4.hasNextPage)

        // Verify 0 missing, 0 duplicates, exact sort order across all 35 docs
        val combinedIds = (p1.pageDocs + p2.pageDocs + p3.pageDocs + p4.pageDocs)
            .map { it.getString("_id").value }
        assertEquals(35, combinedIds.size)
        assertEquals(35, combinedIds.toSet().size)
        assertEquals((1..35).map { "doc_${it.toString().padStart(2, '0')}" }, combinedIds)

        // Navigate Prev: Page 4 -> Page 3 -> Page 2 -> Page 1
        val prevP3 = cache.getPage(sigA, pageIndex = 2, pageSize = 10)!!
        assertEquals(10, prevP3.pageDocs.size)
        assertEquals(p3.pageDocs, prevP3.pageDocs)
        assertEquals(p2.pageDocs, cache.getPage(sigA, pageIndex = 1, pageSize = 10)!!.pageDocs)
        assertEquals(p1.pageDocs, cache.getPage(sigA, pageIndex = 0, pageSize = 10)!!.pageDocs)

        // Signature change (different filter or joinConfig) must miss cache
        val sigChangedFilter = cache.buildSignature("conn-docdb", "appdb", "orders", "{\"status\":\"PAID\"}", "{\"seq\":1}", "joinSigV1")
        val sigChangedJoin = cache.buildSignature("conn-docdb", "appdb", "orders", "{}", "{\"seq\":1}", "joinSigV2")
        assertNull(cache.getPage(sigChangedFilter, 0, 10))
        assertNull(cache.getPage(sigChangedJoin, 0, 10))
    }

    @Test
    fun `5 - FieldSearchFilter ranks prefix matches ahead of substring matches case-insensitively`() {
        val masterFields = listOf("created_user_id", "user_id", "tenant_id", "USER_NAME")
        val filtered = FieldSearchFilter.filter(masterFields, "user")

        assertEquals(listOf("user_id", "USER_NAME", "created_user_id"), filtered)
        assertFalse("tenant_id" in filtered)

        // Blank query returns full master list unchanged
        assertEquals(masterFields, FieldSearchFilter.filter(masterFields, "   "))
    }

    @Test
    fun `6 - hybrid DocumentDB pipeline omits Error 304 let-pipeline and includes 500-item slice guard`() {
        val spec = JoinSpec(
            leftCollection = "a_hts",
            allLeftFields = listOf("_id", "userid", "stcd"),
            selectedLeftFields = listOf("_id", "userid", "stcd"),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "b_hts",
                    localField = "stcd",
                    foreignField = "stcd",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "stcd", "stcd"),
                        JoinKeyPair(JoinSourceRef.Base, "userid", "userid")
                    ),
                    selectedColumns = listOf("status"),
                    excludeUnmatched = true
                )
            )
        )

        val stages = spec.buildHybridDocumentDbPipeline(BsonDocument(), BsonDocument(), bufferLimit = 2000)

        assertTrue(stages.none { it.containsKey("\$skip") })
        val lookupStage = stages.firstOrNull { it.containsKey("\$lookup") }?.getDocument("\$lookup")
        assertNotNull(lookupStage)
        assertEquals("b_hts", lookupStage!!.getString("from").value)
        assertEquals("userid", lookupStage.getString("localField").value)
        assertEquals("userid", lookupStage.getString("foreignField").value)
        assertFalse(lookupStage.containsKey("let"))
        assertFalse(lookupStage.containsKey("pipeline"))

        val addFieldsStage = stages.firstOrNull { it.containsKey("\$addFields") }?.getDocument("\$addFields")
        assertNotNull(addFieldsStage)
        val sliceExpr = addFieldsStage!!.getDocument("_joined_0").getArray("\$slice")
        assertEquals("\$_joined_0", sliceExpr[0].asString().value)
        assertEquals(500, sliceExpr[1].asInt32().value)
    }
}
