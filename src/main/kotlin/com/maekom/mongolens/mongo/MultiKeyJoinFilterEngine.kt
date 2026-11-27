package com.maekom.mongolens.mongo

import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonNumber
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonValue
import java.math.BigDecimal

/**
 * Pure Kotlin (ZERO IntelliJ UI dependencies) multi-key join filter & session-cached pagination engine.
 *
 * Designed for AWS DocumentDB (3.6/4.0/5.0) hybrid 2-phase composite-key joins where MongoDB's native
 * `$lookup` with multiple join conditions throws Error 304:
 * 1. Selects the highest-selectivity identifier key (`_id`, `id`, `*_id`, `*Id`) as the 1st-stage DB `$lookup` key
 *    and splits remaining composite keys for precise in-memory filtering.
 * 2. Evaluates remaining key pairs with nested dot-notation extraction, intermediate array flattening,
 *    cross-type numeric equality (`Int32` <-> `Int64`), and `INNER JOIN` vs `LEFT JOIN` semantics.
 * 3. Provides signature-isolated session caching so `Next` / `Prev` pagination slices cleanly with 0 missing
 *    and 0 duplicate documents.
 */
object MultiKeyJoinFilterEngine {

    const val HYBRID_SLICE_LIMIT: Int = 500
    const val MIN_HYBRID_BUFFER_LIMIT: Int = 2000

    data class PageSliceResult(
        val pageDocs: List<BsonDocument>,
        val totalCount: Int,
        val hasNextPage: Boolean,
        val offset: Int,
        val fromCache: Boolean
    )

    /**
     * Prioritizes unique identifier keys (`_id`, `id`, `*_id`, `*Id`) as the 1st-stage DB `$lookup` key pair
     * to minimize join fan-out on AWS DocumentDB, returning `(primaryPair, remainingPairs)`.
     */
    fun selectPrimaryAndRemainingPairs(keyPairs: List<JoinKeyPair>): Pair<JoinKeyPair, List<JoinKeyPair>> {
        require(keyPairs.isNotEmpty()) { "keyPairs must not be empty" }
        if (keyPairs.size == 1) return keyPairs[0] to emptyList()

        var bestIdx = 0
        var bestScore = -1
        keyPairs.forEachIndexed { idx, pair ->
            val score = maxOf(
                identifierFieldPriority(pair.localField),
                identifierFieldPriority(pair.foreignField)
            )
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }
        val primary = keyPairs[bestIdx]
        val remaining = keyPairs.filterIndexed { idx, _ -> idx != bestIdx }
        return primary to remaining
    }

    private fun identifierFieldPriority(fieldPath: String): Int {
        val leaf = fieldPath.trim().substringAfterLast('.')
        val lower = leaf.lowercase()
        return when {
            lower == "_id" -> 4
            lower == "id" -> 3
            lower.endsWith("_id") || (leaf.length > 2 && leaf.endsWith("Id")) -> 2
            lower.endsWith("id") && lower.length > 2 -> 1
            else -> 0
        }
    }

    /**
     * Extracts all non-null scalar values reachable at a dot-notation path (`a.b.c`),
     * automatically flattening any intermediate or terminal `BsonArray` elements.
     */
    fun extractFlattenedValues(doc: BsonDocument, dotPath: String): List<BsonValue> {
        val segments = dotPath.trim().split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()
        val out = mutableListOf<BsonValue>()
        collectValuesRecursive(doc, segments, 0, out)
        return out
    }

    private fun collectValuesRecursive(
        current: BsonValue,
        segments: List<String>,
        segIdx: Int,
        out: MutableList<BsonValue>
    ) {
        if (current is BsonNull) return
        if (segIdx >= segments.size) {
            if (current is BsonArray) {
                for (elem in current) {
                    if (elem !is BsonNull) {
                        if (elem is BsonArray) {
                            collectValuesRecursive(elem, segments, segIdx, out)
                        } else {
                            out.add(elem)
                        }
                    }
                }
            } else {
                out.add(current)
            }
            return
        }

        val seg = segments[segIdx]
        when (current) {
            is BsonDocument -> {
                val next = current[seg] ?: return
                collectValuesRecursive(next, segments, segIdx + 1, out)
            }
            is BsonArray -> {
                val numericIdx = seg.toIntOrNull()
                if (numericIdx != null && numericIdx in 0 until current.size) {
                    collectValuesRecursive(current[numericIdx], segments, segIdx + 1, out)
                } else {
                    for (elem in current) {
                        if (elem is BsonDocument || elem is BsonArray) {
                            collectValuesRecursive(elem, segments, segIdx, out)
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * Strict BSON value equality with safe `Int32` <-> `Int64` (and numeric) cross-type equivalence.
     * `BsonNull` never matches another `BsonNull` to prevent null-key join explosion.
     */
    fun bsonValuesEqual(a: BsonValue?, b: BsonValue?): Boolean {
        if (a == null || b == null || a is BsonNull || b is BsonNull) return false
        if ((a is BsonInt32 || a is BsonInt64) && (b is BsonInt32 || b is BsonInt64)) {
            return (a as BsonNumber).longValue() == (b as BsonNumber).longValue()
        }
        if (a is BsonNumber && b is BsonNumber) {
            val bdA = toBigDecimalOrNull(a) ?: return false
            val bdB = toBigDecimalOrNull(b) ?: return false
            return bdA.compareTo(bdB) == 0
        }
        return when {
            a is BsonObjectId && b is BsonObjectId -> a.value == b.value
            a is BsonString && b is BsonString -> a.value == b.value
            a is BsonBoolean && b is BsonBoolean -> a.value == b.value
            a is BsonDateTime && b is BsonDateTime -> a.value == b.value
            else -> a == b
        }
    }

    private fun toBigDecimalOrNull(num: BsonNumber): BigDecimal? = when (num) {
        is BsonInt32 -> BigDecimal.valueOf(num.value.toLong())
        is BsonInt64 -> BigDecimal.valueOf(num.value)
        is BsonDouble -> if (num.value.isFinite()) BigDecimal.valueOf(num.value) else null
        is BsonDecimal128 -> runCatching { num.value.bigDecimalValue() }.getOrNull()
        else -> null
    }

    private fun compareBsonValues(a: BsonValue, b: BsonValue): Int? {
        if (a is BsonNumber && b is BsonNumber) {
            val bdA = toBigDecimalOrNull(a) ?: return null
            val bdB = toBigDecimalOrNull(b) ?: return null
            return bdA.compareTo(bdB)
        }
        return when {
            a is BsonString && b is BsonString -> a.value.compareTo(b.value)
            a is BsonDateTime && b is BsonDateTime -> a.value.compareTo(b.value)
            a is BsonObjectId && b is BsonObjectId -> a.value.compareTo(b.value)
            a is BsonBoolean && b is BsonBoolean -> a.value.compareTo(b.value)
            else -> null
        }
    }

    /**
     * Checks whether a single joined child document satisfies ALL remaining composite key pairs
     * against the parent document (intersection check for array paths, `Int32` <-> `Int64` safe).
     */
    fun childMatchesRemainingPairs(
        parentDoc: BsonDocument,
        childDoc: BsonDocument,
        remainingPairs: List<JoinKeyPair>,
        sourcePathResolver: (JoinKeyPair) -> String = { it.localField.trim() }
    ): Boolean {
        for (pair in remainingPairs) {
            val localPath = sourcePathResolver(pair)
            val foreignPath = pair.foreignField.trim()
            val leftVals = extractFlattenedValues(parentDoc, localPath)
            val rightVals = extractFlattenedValues(childDoc, foreignPath)
            if (leftVals.isEmpty() || rightVals.isEmpty()) return false
            val hasMatch = leftVals.any { lv -> rightVals.any { rv -> bsonValuesEqual(lv, rv) } }
            if (!hasMatch) return false
        }
        return true
    }

    /**
     * Evaluates target `WHERE AND` conditions against a candidate joined child document in memory.
     */
    fun childMatchesConditions(childDoc: BsonDocument, conditions: List<JoinCondition>): Boolean {
        for (cond in conditions) {
            val field = cond.field.trim()
            if (field.isEmpty()) continue
            if (cond.operator.requiresValue && cond.rawValue.isBlank()) continue
            val vals = extractFlattenedValues(childDoc, field)
            val leaf = field.substringAfterLast('.')

            val ok = when (cond.operator) {
                JoinConditionOperator.IS_NOT_EMPTY -> vals.any { v ->
                    v !is BsonNull && !(v is BsonString && v.value.isEmpty())
                }
                JoinConditionOperator.IS_EMPTY -> vals.isEmpty() || vals.all { v ->
                    v is BsonNull || (v is BsonString && v.value.isEmpty())
                }
                JoinConditionOperator.EQ -> {
                    val target = JoinSpec.parseTypedValue(leaf, cond.rawValue)
                    vals.any { bsonValuesEqual(it, target) }
                }
                JoinConditionOperator.NE -> {
                    val target = JoinSpec.parseTypedValue(leaf, cond.rawValue)
                    vals.none { bsonValuesEqual(it, target) }
                }
                JoinConditionOperator.GT -> {
                    val target = JoinSpec.parseTypedValue(leaf, cond.rawValue)
                    vals.any { (compareBsonValues(it, target) ?: -1) > 0 }
                }
                JoinConditionOperator.GTE -> {
                    val target = JoinSpec.parseTypedValue(leaf, cond.rawValue)
                    vals.any { (compareBsonValues(it, target) ?: -1) >= 0 }
                }
                JoinConditionOperator.LT -> {
                    val target = JoinSpec.parseTypedValue(leaf, cond.rawValue)
                    vals.any { (compareBsonValues(it, target) ?: 1) < 0 }
                }
                JoinConditionOperator.LTE -> {
                    val target = JoinSpec.parseTypedValue(leaf, cond.rawValue)
                    vals.any { (compareBsonValues(it, target) ?: 1) <= 0 }
                }
                JoinConditionOperator.IN -> {
                    val list = JoinSpec.parseInList(leaf, cond.rawValue)
                    vals.any { lv -> list.any { rv -> bsonValuesEqual(lv, rv) } }
                }
            }
            if (!ok) return false
        }
        return true
    }

    /**
     * Filters a batch of 1st-stage `$lookup` parent documents by `remainingPairs` (and optional conditions):
     * - Leaves only matching child documents inside `parent[alias]`.
     * - When `excludeUnmatched == true` (INNER JOIN), drops parent documents whose filtered `alias` array is empty.
     * - When `excludeUnmatched == false` (LEFT JOIN), preserves parent documents with an empty `alias` array.
     */
    fun filterSingleJoinInMemory(
        parentDocs: List<BsonDocument>,
        alias: String,
        remainingPairs: List<JoinKeyPair>,
        excludeUnmatched: Boolean,
        targetConditions: List<JoinCondition> = emptyList(),
        sourcePathResolver: (JoinKeyPair) -> String = { it.localField.trim() }
    ): List<BsonDocument> {
        val result = mutableListOf<BsonDocument>()
        for (doc in parentDocs) {
            val rawJoined = doc[alias]
            val childArray = rawJoined as? BsonArray
            val matchedChildren = BsonArray()
            if (childArray != null) {
                for (elem in childArray) {
                    val childDoc = elem as? BsonDocument ?: continue
                    if (childMatchesRemainingPairs(doc, childDoc, remainingPairs, sourcePathResolver) &&
                        childMatchesConditions(childDoc, targetConditions)
                    ) {
                        matchedChildren.add(childDoc)
                    }
                }
            }
            if (excludeUnmatched && matchedChildren.isEmpty()) {
                continue
            }
            val copy = doc.clone()
            copy[alias] = matchedChildren
            result.add(copy)
        }
        return result
    }

    /**
     * Full sequential multi-target hybrid shaping & flat 1D column projection matching `JoinSpec`:
     * Applies remaining-key filtering, per-target conditions, `LATEST_ONE` / `UNWIND_ALL`,
     * `INNER JOIN` / `LEFT JOIN` exclusion, and `ColumnOrigin` flat projection in memory.
     */
    fun applyFullHybridJoinShaping(
        rawDbDocs: List<BsonDocument>,
        joinSpec: JoinSpec
    ): List<BsonDocument> {
        var working: List<BsonDocument> = rawDbDocs

        joinSpec.targets.forEachIndexed { idx, target ->
            val alias = "_joined_$idx"
            val pairs = target.effectiveKeyPairs()
            val (_, remainingPairs) = selectPrimaryAndRemainingPairs(pairs)

            val resolver: (JoinKeyPair) -> String = { pair ->
                when (val src = pair.sourceRef) {
                    is JoinSourceRef.Base -> pair.localField.trim()
                    is JoinSourceRef.PreviousTarget -> {
                        val safeRef = src.targetIndex.coerceIn(0, (idx - 1).coerceAtLeast(0))
                        "_joined_$safeRef.${pair.localField.trim()}"
                    }
                }
            }

            val nextWorking = mutableListOf<BsonDocument>()
            for (parent in working) {
                val rawArr = parent[alias] as? BsonArray
                val matchedChildren = mutableListOf<BsonDocument>()
                if (rawArr != null) {
                    for (elem in rawArr) {
                        val childDoc = elem as? BsonDocument ?: continue
                        if (childMatchesRemainingPairs(parent, childDoc, remainingPairs, resolver) &&
                            childMatchesConditions(childDoc, target.conditions)
                        ) {
                            matchedChildren.add(childDoc)
                        }
                    }
                }

                when (target.matchStrategy) {
                    JoinMatchStrategy.LATEST_ONE -> {
                        val chosen = matchedChildren.lastOrNull()
                        if (chosen == null && target.excludeUnmatched) {
                            continue
                        }
                        val copy = parent.clone()
                        copy[alias] = chosen ?: BsonNull.VALUE
                        nextWorking.add(copy)
                    }
                    JoinMatchStrategy.UNWIND_ALL -> {
                        if (matchedChildren.isEmpty()) {
                            if (!target.excludeUnmatched) {
                                val copy = parent.clone()
                                copy[alias] = BsonNull.VALUE
                                nextWorking.add(copy)
                            }
                        } else {
                            for (child in matchedChildren) {
                                val copy = parent.clone()
                                copy[alias] = child
                                nextWorking.add(copy)
                            }
                        }
                    }
                }
            }
            working = nextWorking
        }

        // Project final flat 1D documents matching JoinSpec.columnOriginsByResultKey()
        val origins = joinSpec.columnOriginsByResultKey()
        return working.map { doc -> projectShapedDocument(doc, origins) }
    }

    internal fun projectShapedDocument(
        doc: BsonDocument,
        origins: Map<String, ColumnOrigin>
    ): BsonDocument {
        val out = BsonDocument()
        for ((resultKey, origin) in origins) {
            val value: BsonValue? = if (origin.isBase) {
                extractSingleOrArrayValue(doc, origin.originalFieldName)
            } else {
                val joinedObj = doc["_joined_${origin.targetIndex}"] as? BsonDocument
                if (joinedObj != null) extractSingleOrArrayValue(joinedObj, origin.originalFieldName) else null
            }
            if (value != null) {
                out[resultKey] = value
            }
        }
        if (out.isEmpty() && doc.containsKey("_id")) {
            out["_id"] = doc["_id"]!!
        }
        return out
    }

    private fun extractSingleOrArrayValue(doc: BsonDocument, fieldPath: String): BsonValue? {
        val trimmed = fieldPath.trim()
        if (!trimmed.contains('.')) return doc[trimmed]
        val segments = trimmed.split('.').filter { it.isNotEmpty() }
        var cur: BsonValue = doc
        for (seg in segments) {
            cur = when (cur) {
                is BsonDocument -> cur[seg] ?: return null
                is BsonArray -> {
                    val idx = seg.toIntOrNull()
                    if (idx != null && idx in 0 until cur.size) {
                        cur[idx]
                    } else {
                        val flattened = extractFlattenedValues(doc, trimmed)
                        return when (flattened.size) {
                            0 -> null
                            1 -> flattened[0]
                            else -> BsonArray(flattened)
                        }
                    }
                }
                else -> return null
            }
        }
        return cur
    }

    /**
     * Computes an exact page slice from an already-filtered full document list without overlap or omission.
     */
    fun slicePage(
        filteredDocs: List<BsonDocument>,
        pageIndex: Int,
        pageSize: Int?,
        fromCache: Boolean
    ): PageSliceResult {
        val total = filteredDocs.size
        if (pageSize == null) {
            val capped = filteredDocs.take(JoinSpec.MAX_UNLIMITED_DOCS)
            return PageSliceResult(
                pageDocs = capped,
                totalCount = total,
                hasNextPage = false,
                offset = 0,
                fromCache = fromCache
            )
        }
        val safeSize = pageSize.coerceIn(1, JoinSpec.MAX_UNLIMITED_DOCS)
        val safePage = pageIndex.coerceAtLeast(0)
        val start = safePage * safeSize
        if (start >= total) {
            return PageSliceResult(
                pageDocs = emptyList(),
                totalCount = total,
                hasNextPage = false,
                offset = start,
                fromCache = fromCache
            )
        }
        val end = minOf(start + safeSize, total)
        return PageSliceResult(
            pageDocs = filteredDocs.subList(start, end).toList(),
            totalCount = total,
            hasNextPage = end < total,
            offset = start,
            fromCache = fromCache
        )
    }
}

/**
 * Thread-safe, query-signature-isolated LRU session cache for hybrid multi-key join pagination.
 * Ensures `Next` / `Prev` navigation returns exact slices with 0 missing and 0 duplicate documents
 * without leaking cross-tab or cross-filter data.
 */
class HybridJoinSessionCache(private val maxEntries: Int = 16) {

    private data class CacheEntry(
        val connectionId: String,
        val filteredDocs: List<BsonDocument>
    )

    private val lock = Any()
    private val lru = object : LinkedHashMap<String, CacheEntry>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    fun buildSignature(
        connectionId: String,
        database: String,
        leftCollection: String,
        filterJson: String,
        sortJson: String,
        joinSpecSignature: String
    ): String {
        return listOf(connectionId, database, leftCollection, filterJson, sortJson, joinSpecSignature)
            .joinToString("::")
    }

    fun getPage(signature: String, pageIndex: Int, pageSize: Int?): MultiKeyJoinFilterEngine.PageSliceResult? {
        val docs = synchronized(lock) { lru[signature]?.filteredDocs } ?: return null
        return MultiKeyJoinFilterEngine.slicePage(docs, pageIndex, pageSize, fromCache = true)
    }

    fun putAndSlice(
        connectionId: String,
        signature: String,
        filteredDocs: List<BsonDocument>,
        pageIndex: Int,
        pageSize: Int?
    ): MultiKeyJoinFilterEngine.PageSliceResult {
        val snapshot = filteredDocs.toList()
        synchronized(lock) {
            lru[signature] = CacheEntry(connectionId, snapshot)
        }
        return MultiKeyJoinFilterEngine.slicePage(snapshot, pageIndex, pageSize, fromCache = false)
    }

    fun invalidateByConnection(connectionId: String) {
        synchronized(lock) {
            lru.entries.removeIf { it.value.connectionId == connectionId }
        }
    }

    fun invalidateSignature(signature: String) {
        synchronized(lock) {
            lru.remove(signature)
        }
    }

    fun clear() {
        synchronized(lock) {
            lru.clear()
        }
    }
}
