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
    fun `prioritizes unique identifier keys as primary 1st-stage lookup key`() {
        val pairs = listOf(
            JoinKeyPair(JoinSourceRef.Base, "stcd", "stcd"),
            JoinKeyPair(JoinSourceRef.Base, "region", "region"),
            JoinKeyPair(JoinSourceRef.Base, "user_id", "user_id")
        )
        val (primary, remaining) = MultiKeyJoinFilterEngine.selectPrimaryAndRemainingPairs(pairs)
        assertEquals("user_id", primary.localField)
        assertEquals(2, remaining.size)
        assertEquals(listOf("stcd", "region"), remaining.map { it.localField })

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
    fun `matches 35 composite-key documents while eliminating trap records and verifying Next-Prev paging`() {
        val joinSpec = JoinSpec(
            leftCollection = "orders",
            allLeftFields = listOf("_id", "user_id", "stcd", "seq"),
            selectedLeftFields = listOf("_id", "user_id", "stcd", "seq"),
            targets = listOf(
                JoinTargetSpec(
                    targetCollection = "auth_history",
                    localField = "user_id",
                    foreignField = "user_id",
                    keyPairs = listOf(
                        JoinKeyPair(JoinSourceRef.Base, "user_id", "user_id"),
                        JoinKeyPair(JoinSourceRef.Base, "stcd", "stcd")
                    ),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    allColumns = listOf("user_id", "stcd", "result"),
                    selectedColumns = listOf("result"),
                    excludeUnmatched = true // INNER JOIN
                )
            ),
            limitOption = 10
        )

        // Build 35 genuinely matching parent docs + 10 trap docs (primary user_id matches, secondary stcd differs)
        val rawDbDocs = mutableListOf<BsonDocument>()
        for (i in 1..35) {
            val validChild = BsonDocument()
                .append("user_id", BsonInt64(1000L + i)) // Int64 on child vs Int32 on parent!
                .append("stcd", BsonString("ACTIVE"))
                .append("result", BsonString("OK_$i"))
            val trapChild = BsonDocument()
                .append("user_id", BsonInt64(1000L + i))
                .append("stcd", BsonString("REVOKED")) // Should be filtered out!
                .append("result", BsonString("TRAP_$i"))

            val parent = BsonDocument()
                .append("_id", BsonString("doc_${i.toString().padStart(2, '0')}"))
                .append("user_id", BsonInt32(1000 + i)) // Int32 vs Int64 cross-type test
                .append("stcd", BsonString("ACTIVE"))
                .append("seq", BsonInt32(i))
                .append("_joined_0", BsonArray(listOf(trapChild, validChild)))
            rawDbDocs.add(parent)
        }

        // Add 10 trap parent docs whose joined children ONLY have mismatched secondary keys
        for (t in 1..10) {
            val wrongChild = BsonDocument()
                .append("user_id", BsonInt32(9000 + t))
                .append("stcd", BsonString("EXPIRED"))
                .append("result", BsonString("BAD_$t"))
            val trapParent = BsonDocument()
                .append("_id", BsonString("trap_$t"))
                .append("user_id", BsonInt32(9000 + t))
                .append("stcd", BsonString("ACTIVE"))
                .append("seq", BsonInt32(9000 + t))
                .append("_joined_0", BsonArray(listOf(wrongChild)))
            rawDbDocs.add(trapParent)
        }

        val shapedDocs = MultiKeyJoinFilterEngine.applyFullHybridJoinShaping(rawDbDocs, joinSpec)

        // Exactly 35 matched documents must survive; all 10 trap parents and trap children must be eliminated
        assertEquals(35, shapedDocs.size)
        for (i in 1..35) {
            val doc = shapedDocs[i - 1]
            assertEquals("doc_${i.toString().padStart(2, '0')}", doc.getString("_id").value)
            assertEquals("OK_$i", doc.getString("auth_history_result").value)
        }

        // Verify signature-isolated session cache Next/Prev slicing with pageSize=10:
        // Pages: [0]=10, [1]=10, [2]=10, [3]=5 -> Prev [2]=10, [1]=10, [0]=10
        val cache = HybridJoinSessionCache(maxEntries = 4)
        val sig = cache.buildSignature("conn-1", "testdb", "orders", "{}", "{}", joinSpec.cacheSignatureSegment())

        val page0 = cache.putAndSlice("conn-1", sig, shapedDocs, pageIndex = 0, pageSize = 10)
        assertFalse(page0.fromCache)
        assertEquals(10, page0.pageDocs.size)
        assertEquals(35, page0.totalCount)
        assertTrue(page0.hasNextPage)

        val page1 = cache.getPage(sig, pageIndex = 1, pageSize = 10)!!
        assertTrue(page1.fromCache)
        assertEquals(10, page1.pageDocs.size)
        assertTrue(page1.hasNextPage)

        val page2 = cache.getPage(sig, pageIndex = 2, pageSize = 10)!!
        assertEquals(10, page2.pageDocs.size)
        assertTrue(page2.hasNextPage)

        val page3 = cache.getPage(sig, pageIndex = 3, pageSize = 10)!!
        assertEquals(5, page3.pageDocs.size)
        assertFalse(page3.hasNextPage)

        // Combine all forward pages -> must equal exact 35 unique IDs with 0 omissions and 0 duplicates
        val allForwardIds = (page0.pageDocs + page1.pageDocs + page2.pageDocs + page3.pageDocs)
            .map { it.getString("_id").value }
        assertEquals(35, allForwardIds.size)
        assertEquals(35, allForwardIds.toSet().size)
        assertEquals((1..35).map { "doc_${it.toString().padStart(2, '0')}" }, allForwardIds)

        // Navigate backward (Prev): 3 -> 2 -> 1 -> 0 must match exact same slices
        assertEquals(page2.pageDocs, cache.getPage(sig, pageIndex = 2, pageSize = 10)!!.pageDocs)
        assertEquals(page1.pageDocs, cache.getPage(sig, pageIndex = 1, pageSize = 10)!!.pageDocs)
        assertEquals(page0.pageDocs, cache.getPage(sig, pageIndex = 0, pageSize = 10)!!.pageDocs)

        // Signature isolation: different filter must NOT hit this cache entry
        val otherSig = cache.buildSignature("conn-1", "testdb", "orders", "{\"stcd\":\"OTHER\"}", "{}", joinSpec.cacheSignatureSegment())
        assertNull(cache.getPage(otherSig, 0, 10))
    }

    @Test
    fun `supports Int32 vs Int64 cross-type equality, nested dot-paths, and array element flattening`() {
        val parent = BsonDocument()
            .append("_id", BsonString("p1"))
            .append("meta", BsonDocument("tenant", BsonDocument("id", BsonInt32(42))))
            .append(
                "items",
                BsonArray(
                    listOf(
                        BsonDocument("product_id", BsonInt64(700L)),
                        BsonDocument("product_id", BsonInt64(800L))
                    )
                )
            )
            .append(
                "_joined_0",
                BsonArray(
                    listOf(
                        // Matches both nested tenant.id (Int64 42 == Int32 42) AND flattened items.product_id (Int32 800 == Int64 800)
                        BsonDocument()
                            .append("tenant", BsonDocument("code", BsonInt64(42L)))
                            .append("prod_id", BsonInt32(800))
                            .append("label", BsonString("MATCH")),
                        // Wrong product id -> trap
                        BsonDocument()
                            .append("tenant", BsonDocument("code", BsonInt64(42L)))
                            .append("prod_id", BsonInt32(999))
                            .append("label", BsonString("TRAP"))
                    )
                )
            )

        val remaining = listOf(
            JoinKeyPair(JoinSourceRef.Base, "meta.tenant.id", "tenant.code"),
            JoinKeyPair(JoinSourceRef.Base, "items.product_id", "prod_id")
        )

        val filtered = MultiKeyJoinFilterEngine.filterSingleJoinInMemory(
            parentDocs = listOf(parent),
            alias = "_joined_0",
            remainingPairs = remaining,
            excludeUnmatched = true
        )

        assertEquals(1, filtered.size)
        val joinedArr = filtered[0].getArray("_joined_0")
        assertEquals(1, joinedArr.size)
        assertEquals("MATCH", joinedArr[0].asDocument().getString("label").value)
    }

    @Test
    fun `distinguishes INNER JOIN and LEFT JOIN semantics when secondary keys fail`() {
        val parentMatched = BsonDocument()
            .append("_id", BsonString("m1"))
            .append("uid", BsonInt32(1))
            .append("code", BsonString("A"))
            .append("_joined_0", BsonArray(listOf(BsonDocument("uid", BsonInt32(1)).append("code", BsonString("A")))))

        val parentUnmatched = BsonDocument()
            .append("_id", BsonString("u1"))
            .append("uid", BsonInt32(2))
            .append("code", BsonString("A"))
            .append("_joined_0", BsonArray(listOf(BsonDocument("uid", BsonInt32(2)).append("code", BsonString("B")))))

        val remaining = listOf(JoinKeyPair(JoinSourceRef.Base, "code", "code"))

        // INNER JOIN drops unmatched parent
        val innerResult = MultiKeyJoinFilterEngine.filterSingleJoinInMemory(
            parentDocs = listOf(parentMatched, parentUnmatched),
            alias = "_joined_0",
            remainingPairs = remaining,
            excludeUnmatched = true
        )
        assertEquals(1, innerResult.size)
        assertEquals("m1", innerResult[0].getString("_id").value)

        // LEFT JOIN preserves unmatched parent with empty array
        val leftResult = MultiKeyJoinFilterEngine.filterSingleJoinInMemory(
            parentDocs = listOf(parentMatched, parentUnmatched),
            alias = "_joined_0",
            remainingPairs = remaining,
            excludeUnmatched = false
        )
        assertEquals(2, leftResult.size)
        assertEquals(1, leftResult[0].getArray("_joined_0").size)
        assertEquals(0, leftResult[1].getArray("_joined_0").size)
    }

    @Test
    fun `hybrid DocumentDB pipeline avoids Error 304 let-pipeline and applies 500-item slice guard`() {
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

        // Must NOT contain $skip
        assertTrue(stages.none { it.containsKey("\$skip") })
        // Must contain standard single-key $lookup using prioritized 'userid' (and ZERO 'let' or 'pipeline')
        val lookupStage = stages.firstOrNull { it.containsKey("\$lookup") }?.getDocument("\$lookup")
        assertNotNull(lookupStage)
        assertEquals("b_hts", lookupStage!!.getString("from").value)
        assertEquals("userid", lookupStage.getString("localField").value)
        assertEquals("userid", lookupStage.getString("foreignField").value)
        assertFalse(lookupStage.containsKey("let"))
        assertFalse(lookupStage.containsKey("pipeline"))

        // Must immediately guard with $slice 500
        val addFieldsStage = stages.firstOrNull { it.containsKey("\$addFields") }?.getDocument("\$addFields")
        assertNotNull(addFieldsStage)
        val sliceExpr = addFieldsStage!!.getDocument("_joined_0").getArray("\$slice")
        assertEquals("\$_joined_0", sliceExpr[0].asString().value)
        assertEquals(500, sliceExpr[1].asInt32().value)
    }
}
