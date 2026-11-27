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
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.math.BigDecimal
import java.util.Date

/**
 * Pure Kotlin (ZERO IntelliJ UI dependencies) Bidirectional Pre-Filter Join Engine.
 *
 * Eliminates full-collection `$lookup` bottlenecks (especially on AWS DocumentDB & large collections) via 3 stages:
 * 1. **Base Pre-Filter**: Applies base (`LEFT`) `WHERE AND` conditions (with schema-aware BSON typing) first.
 * 2. **Target Pre-Filter**:
 *    - `INNER JOIN` (`excludeUnmatched == true`) with target conditions: runs a bounded (`<= 10,000`) `find`
 *      on the target collection with target conditions + `foreignKey != null`, extracts `distinctKeys`,
 *      short-circuits immediately with 0 docs (NEVER querying the base collection!) when 0 keys match,
 *      or injects `{ localKey: { $in: distinctKeys } }` into the base pinpoint query. Falls back to forward
 *      `$lookup` automatically if the pre-filter exceeds the 10,000-doc safety cap.
 *    - `LEFT JOIN` (`excludeUnmatched == false`): queries pre-limited base documents first, extracts their
 *      distinct join keys, and batch-fetches matching target documents via `{ foreignKey: { $in: baseKeys }, ...targetFilters }`.
 * 3. **Multi-Key (`AND`) Hash Matching & Shaping**:
 *    - Uses `normalizeJoinKey` so cross-type numeric keys (`Int32` <-> `Int64` <-> `Double`/`Decimal128`)
 *      hash and match seamlessly without type loss, then applies remaining `AND` keys, `LATEST_ONE` (by `_id` desc)
 *      or `UNWIND_ALL`, `excludeUnmatched`, `Limit`, and flat 1D column projection.
 */
object BidirectionalPreFilterJoinEngine {

    const val TARGET_PREFILTER_LIMIT: Int = 10_000

    /**
     * Minimal collection query abstraction allowing production `MongoConnectionManager` execution
     * and deterministic in-memory unit testing (including verifying zero base-collection queries on short-circuit).
     */
    fun interface CollectionQuerySource {
        fun find(
            collection: String,
            filter: BsonDocument,
            sort: BsonDocument?,
            limit: Int?
        ): List<BsonDocument>
    }

    sealed class PreFilterExecutionResult {
        data class Completed(
            val documents: List<BsonDocument>,
            val baseCollectionQueried: Boolean,
            val preFilteredTargetCount: Int
        ) : PreFilterExecutionResult()

        data class FallbackToLookup(val reason: String) : PreFilterExecutionResult()
    }

    private data class NormalizedNumericKey(val canonical: BigDecimal) {
        override fun equals(other: Any?): Boolean =
            other is NormalizedNumericKey && canonical.compareTo(other.canonical) == 0

        override fun hashCode(): Int = canonical.stripTrailingZeros().hashCode()
    }

    private data class NormalizedStringKey(val value: String)
    private data class NormalizedDateKey(val epochMillis: Long)
    private data class NormalizedBoolKey(val value: Boolean)

    /**
     * Normalizes a `BsonValue` into a type-safe hashable key:
     * - `Int32(100)` and `Int64(100L)` normalize to the EXACT SAME key so numeric cross-type joins never miss.
     * - `String("100")` remains strictly distinct from numeric `100`.
     * - `BsonNull` / `null` returns `null` so null keys never falsely match.
     */
    fun normalizeJoinKey(value: BsonValue?): Any? {
        if (value == null || value is BsonNull) return null
        return when (value) {
            is BsonInt32 -> NormalizedNumericKey(BigDecimal.valueOf(value.value.toLong()))
            is BsonInt64 -> NormalizedNumericKey(BigDecimal.valueOf(value.value))
            is BsonDouble -> if (value.value.isFinite()) NormalizedNumericKey(BigDecimal.valueOf(value.value)) else null
            is BsonDecimal128 -> runCatching { NormalizedNumericKey(value.value.bigDecimalValue()) }.getOrNull()
            is BsonString -> NormalizedStringKey(value.value)
            is BsonObjectId -> value.value
            is BsonBoolean -> NormalizedBoolKey(value.value)
            is BsonDateTime -> NormalizedDateKey(value.value)
            else -> value
        }
    }

    /**
     * Returns true when Bidirectional Pre-Filter Join should be used:
     * any target has `WHERE AND` conditions or base `WHERE AND` conditions are configured alongside valid join keys.
     */
    fun shouldUseBidirectionalPreFilter(joinSpec: JoinSpec): Boolean {
        if (!CompassPipelineSerializer.hasValidJoinKeys(joinSpec)) return false
        return joinSpec.targets.any { it.conditions.isNotEmpty() } || joinSpec.baseConditions.isNotEmpty()
    }

    /**
     * Converts a `CompassPipelineSerializer` typed Kotlin/BSON value into an `org.bson.BsonValue`.
     */
    fun toBsonValue(v: Any?): BsonValue = when (v) {
        null -> BsonNull.VALUE
        is BsonValue -> v
        is Int -> BsonInt32(v)
        is Long -> BsonInt64(v)
        is Double -> BsonDouble(v)
        is Float -> BsonDouble(v.toDouble())
        is Decimal128 -> BsonDecimal128(v)
        is BigDecimal -> BsonDecimal128(Decimal128(v))
        is Boolean -> BsonBoolean(v)
        is ObjectId -> BsonObjectId(v)
        is Date -> BsonDateTime(v.time)
        is String -> BsonString(v)
        else -> BsonString(v.toString())
    }

    /**
     * Builds a single schema-aware BSON condition clause using `CompassPipelineSerializer.convertValueForSchemaType`.
     */
    fun buildSchemaAwareConditionClause(
        cond: JoinCondition,
        fieldTypes: Map<String, String>
    ): BsonDocument? {
        val cleanField = CompassPipelineSerializer.stripUiCollectionPrefix(cond.field)
        if (cleanField.isEmpty()) return null
        if (cond.operator.requiresValue && cond.rawValue.isBlank()) return null

        val schemaType = fieldTypes[cleanField] ?: fieldTypes[cleanField.substringAfterLast('.')]
        fun typed(raw: String): BsonValue =
            toBsonValue(CompassPipelineSerializer.convertValueForSchemaType(cleanField, raw, schemaType))

        return when (cond.operator) {
            JoinConditionOperator.EQ -> BsonDocument(cleanField, typed(cond.rawValue))
            JoinConditionOperator.NE -> BsonDocument(cleanField, BsonDocument("\$ne", typed(cond.rawValue)))
            JoinConditionOperator.GT -> BsonDocument(cleanField, BsonDocument("\$gt", typed(cond.rawValue)))
            JoinConditionOperator.GTE -> BsonDocument(cleanField, BsonDocument("\$gte", typed(cond.rawValue)))
            JoinConditionOperator.LT -> BsonDocument(cleanField, BsonDocument("\$lt", typed(cond.rawValue)))
            JoinConditionOperator.LTE -> BsonDocument(cleanField, BsonDocument("\$lte", typed(cond.rawValue)))
            JoinConditionOperator.IN -> {
                val list = splitCommaValues(cond.rawValue).map { typed(it) }
                BsonDocument(cleanField, BsonDocument("\$in", BsonArray(list)))
            }
            JoinConditionOperator.IS_NOT_EMPTY -> BsonDocument(
                cleanField,
                BsonDocument("\$exists", BsonBoolean.TRUE)
                    .append("\$nin", BsonArray(listOf(BsonNull.VALUE, BsonString(""))))
            )
            JoinConditionOperator.IS_EMPTY -> BsonDocument(
                "\$or",
                BsonArray(
                    listOf(
                        BsonDocument(cleanField, BsonDocument("\$exists", BsonBoolean.FALSE)),
                        BsonDocument(cleanField, BsonDocument("\$in", BsonArray(listOf(BsonNull.VALUE, BsonString("")))))
                    )
                )
            )
        }
    }

    fun combineFilterClauses(clauses: List<BsonDocument>): BsonDocument {
        val nonEmpty = clauses.filter { !it.isEmpty() }
        if (nonEmpty.isEmpty()) return BsonDocument()
        if (nonEmpty.size == 1) return nonEmpty[0].clone()

        val merged = BsonDocument()
        val overflow = mutableListOf<BsonDocument>()
        for (doc in nonEmpty) {
            if (doc.size == 1) {
                val (k, v) = doc.entries.first()
                if (k != "\$and" && k != "\$or") {
                    if (!merged.containsKey(k)) {
                        merged[k] = v
                    } else {
                        val prev = merged.remove(k)
                        if (prev != null) overflow.add(BsonDocument(k, prev))
                        overflow.add(doc)
                    }
                    continue
                }
            }
            overflow.add(doc)
        }
        if (overflow.isEmpty()) return merged
        if (merged.isEmpty() && overflow.size == 1) return overflow[0].clone()
        val all = mutableListOf<BsonDocument>()
        if (!merged.isEmpty()) {
            for ((k, v) in merged) all.add(BsonDocument(k, v))
        }
        all.addAll(overflow)
        return BsonDocument("\$and", BsonArray(all))
    }

    /**
     * Executes the 3-stage Bidirectional Pre-Filter Join against `source`.
     */
    fun execute(
        source: CollectionQuerySource,
        joinSpec: JoinSpec,
        topFilter: BsonDocument = BsonDocument(),
        sort: BsonDocument = BsonDocument(),
        resultLimit: Int? = joinSpec.limitOption,
        targetPreFilterCap: Int = TARGET_PREFILTER_LIMIT
    ): PreFilterExecutionResult {
        if (!CompassPipelineSerializer.hasValidJoinKeys(joinSpec)) {
            return PreFilterExecutionResult.FallbackToLookup("JoinSpec has no valid join key pairs")
        }

        val cleanedTargetsPairs: List<Pair<JoinKeyPair, List<JoinKeyPair>>> = joinSpec.targets.map { target ->
            val cleanPairs = target.effectiveKeyPairs().map { p ->
                p.copy(
                    localField = CompassPipelineSerializer.stripUiCollectionPrefix(p.localField),
                    foreignField = CompassPipelineSerializer.stripUiCollectionPrefix(p.foreignField)
                )
            }.filter { it.localField.isNotEmpty() && it.foreignField.isNotEmpty() }
            MultiKeyJoinFilterEngine.selectPrimaryAndRemainingPairs(cleanPairs)
        }

        // =========================================================================
        // Stage 2A: Target Pre-Filter for INNER JOIN targets with target conditions
        // =========================================================================
        val preFetchedTargets = mutableMapOf<Int, List<BsonDocument>>()
        val basePinpointClauses = mutableListOf<BsonDocument>()
        var preFilteredTargetCount = 0

        joinSpec.targets.forEachIndexed { idx, target ->
            val (primaryPair, _) = cleanedTargetsPairs[idx]
            val targetCondClauses = target.conditions.mapNotNull { buildSchemaAwareConditionClause(it, target.fieldTypes) }

            if (target.excludeUnmatched &&
                targetCondClauses.isNotEmpty() &&
                primaryPair.sourceRef is JoinSourceRef.Base
            ) {
                val nonNullKeyClause = BsonDocument(primaryPair.foreignField, BsonDocument("\$ne", BsonNull.VALUE))
                val targetFilter = combineFilterClauses(targetCondClauses + nonNullKeyClause)
                val targetDocs = source.find(
                    collection = target.targetCollection,
                    filter = targetFilter,
                    sort = null,
                    limit = targetPreFilterCap + 1
                )
                preFilteredTargetCount++

                if (targetDocs.size > targetPreFilterCap) {
                    return PreFilterExecutionResult.FallbackToLookup(
                        "Target '${target.targetCollection}' pre-filter exceeded $targetPreFilterCap documents"
                    )
                }

                val distinctKeys = extractDistinctBsonKeys(targetDocs, primaryPair.foreignField)
                if (distinctKeys.isEmpty()) {
                    // Zero matching keys on INNER target -> short-circuit WITHOUT querying base collection!
                    return PreFilterExecutionResult.Completed(
                        documents = emptyList(),
                        baseCollectionQueried = false,
                        preFilteredTargetCount = preFilteredTargetCount
                    )
                }

                preFetchedTargets[idx] = targetDocs
                basePinpointClauses.add(
                    BsonDocument(primaryPair.localField, BsonDocument("\$in", BsonArray(distinctKeys)))
                )
            }
        }

        // =========================================================================
        // Stage 1: Base Collection Pre-Filter + Pinpoint `$in` Query
        // =========================================================================
        val baseClauses = mutableListOf<BsonDocument>()
        if (!topFilter.isEmpty()) baseClauses.add(topFilter)
        joinSpec.baseConditions.mapNotNullTo(baseClauses) {
            buildSchemaAwareConditionClause(it, joinSpec.leftFieldTypes)
        }
        baseClauses.addAll(basePinpointClauses)

        val effectiveBaseFilter = combineFilterClauses(baseClauses)
        val effectiveBaseSort = if (!sort.isEmpty()) {
            sort.clone().apply { if (!containsKey("_id")) append("_id", BsonInt32(1)) }
        } else {
            BsonDocument("_id", BsonInt32(-1))
        }

        val allLeftJoin = joinSpec.targets.all { !it.excludeUnmatched }
        val baseFetchLimit: Int = when {
            allLeftJoin && resultLimit != null -> resultLimit.coerceIn(1, JoinSpec.MAX_UNLIMITED_DOCS)
            resultLimit != null -> maxOf(resultLimit * 20, MultiKeyJoinFilterEngine.MIN_HYBRID_BUFFER_LIMIT)
                .coerceAtMost(JoinSpec.MAX_UNLIMITED_DOCS)
            else -> JoinSpec.MAX_UNLIMITED_DOCS
        }

        val baseDocs = source.find(
            collection = joinSpec.leftCollection,
            filter = effectiveBaseFilter,
            sort = effectiveBaseSort,
            limit = baseFetchLimit
        )
        if (baseDocs.isEmpty()) {
            return PreFilterExecutionResult.Completed(
                documents = emptyList(),
                baseCollectionQueried = true,
                preFilteredTargetCount = preFilteredTargetCount
            )
        }

        // =========================================================================
        // Stage 2B & Stage 3: Batch `$in` Fetch for Remaining Targets + Multi-Key Hash Join
        // =========================================================================
        var working: List<BsonDocument> = baseDocs

        joinSpec.targets.forEachIndexed { idx, target ->
            val alias = "_joined_$idx"
            val (primaryPair, remainingPairs) = cleanedTargetsPairs[idx]
            val sourcePathResolver: (JoinKeyPair) -> String = { pair ->
                when (val src = pair.sourceRef) {
                    is JoinSourceRef.Base -> pair.localField
                    is JoinSourceRef.PreviousTarget -> {
                        val safeRef = src.targetIndex.coerceIn(0, (idx - 1).coerceAtLeast(0))
                        "_joined_$safeRef.${pair.localField}"
                    }
                }
            }
            val primaryLocalPath = sourcePathResolver(primaryPair)

            val targetDocs: List<BsonDocument> = preFetchedTargets[idx] ?: run {
                val baseKeys = extractDistinctBsonKeys(working, primaryLocalPath)
                if (baseKeys.isEmpty()) {
                    emptyList()
                } else {
                    val targetCondClauses = target.conditions.mapNotNull {
                        buildSchemaAwareConditionClause(it, target.fieldTypes)
                    }
                    val inClause = BsonDocument(primaryPair.foreignField, BsonDocument("\$in", BsonArray(baseKeys)))
                    val batchFilter = combineFilterClauses(listOf(inClause) + targetCondClauses)
                    val fetched = source.find(
                        collection = target.targetCollection,
                        filter = batchFilter,
                        sort = null,
                        limit = targetPreFilterCap + 1
                    )
                    if (fetched.size > targetPreFilterCap) {
                        return PreFilterExecutionResult.FallbackToLookup(
                            "Target '${target.targetCollection}' batch fetch exceeded $targetPreFilterCap documents"
                        )
                    }
                    fetched
                }
            }

            // Build normalized hash index on primary foreign key (`Int32` <-> `Int64` cross-type safe!)
            val hashIndex = HashMap<Any, MutableList<Int>>(targetDocs.size * 2 + 1)
            targetDocs.forEachIndexed { docIdx, tDoc ->
                val fVals = MultiKeyJoinFilterEngine.extractFlattenedValues(tDoc, primaryPair.foreignField)
                for (fv in fVals) {
                    val norm = normalizeJoinKey(fv) ?: continue
                    val bucket = hashIndex.getOrPut(norm) { mutableListOf() }
                    if (bucket.isEmpty() || bucket.last() != docIdx) {
                        bucket.add(docIdx)
                    }
                }
            }

            val nextWorking = mutableListOf<BsonDocument>()
            for (parent in working) {
                val localPrimaryVals = MultiKeyJoinFilterEngine.extractFlattenedValues(parent, primaryLocalPath)
                val seenCandidateIndices = LinkedHashSet<Int>()
                for (lv in localPrimaryVals) {
                    val norm = normalizeJoinKey(lv) ?: continue
                    hashIndex[norm]?.let { seenCandidateIndices.addAll(it) }
                }

                val matchedChildren = mutableListOf<BsonDocument>()
                for (candIdx in seenCandidateIndices) {
                    val childDoc = targetDocs[candIdx]
                    if (MultiKeyJoinFilterEngine.childMatchesRemainingPairs(parent, childDoc, remainingPairs, sourcePathResolver) &&
                        matchesConditionsSchemaAware(childDoc, target.conditions, target.fieldTypes)
                    ) {
                        matchedChildren.add(childDoc)
                    }
                }

                when (target.matchStrategy) {
                    JoinMatchStrategy.LATEST_ONE -> {
                        val chosen = selectLatestById(matchedChildren)
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

        val limited = if (resultLimit != null) {
            working.take(resultLimit.coerceIn(1, JoinSpec.MAX_UNLIMITED_DOCS))
        } else {
            working.take(JoinSpec.MAX_UNLIMITED_DOCS)
        }

        val origins = joinSpec.columnOriginsByResultKey()
        val projected = limited.map { doc -> MultiKeyJoinFilterEngine.projectShapedDocument(doc, origins) }
        return PreFilterExecutionResult.Completed(
            documents = projected,
            baseCollectionQueried = true,
            preFilteredTargetCount = preFilteredTargetCount
        )
    }

    private fun extractDistinctBsonKeys(docs: List<BsonDocument>, dotPath: String): List<BsonValue> {
        val byNormalized = LinkedHashMap<Any, BsonValue>()
        for (doc in docs) {
            val vals = MultiKeyJoinFilterEngine.extractFlattenedValues(doc, dotPath)
            for (v in vals) {
                val norm = normalizeJoinKey(v) ?: continue
                if (!byNormalized.containsKey(norm)) {
                    byNormalized[norm] = v
                }
            }
        }
        return byNormalized.values.toList()
    }

    private fun selectLatestById(candidates: List<BsonDocument>): BsonDocument? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        var best = candidates[0]
        for (i in 1 until candidates.size) {
            val cur = candidates[i]
            val cmp = compareIds(cur["_id"], best["_id"])
            if (cmp >= 0) {
                best = cur
            }
        }
        return best
    }

    private fun compareIds(a: BsonValue?, b: BsonValue?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        if (a is BsonNumber && b is BsonNumber) {
            val bdA = toBigDecimalOrNull(a)
            val bdB = toBigDecimalOrNull(b)
            if (bdA != null && bdB != null) return bdA.compareTo(bdB)
        }
        return when {
            a is BsonObjectId && b is BsonObjectId -> a.value.compareTo(b.value)
            a is BsonString && b is BsonString -> a.value.compareTo(b.value)
            a is BsonDateTime && b is BsonDateTime -> a.value.compareTo(b.value)
            else -> 0
        }
    }

    private fun matchesConditionsSchemaAware(
        doc: BsonDocument,
        conditions: List<JoinCondition>,
        fieldTypes: Map<String, String>
    ): Boolean {
        for (cond in conditions) {
            val field = CompassPipelineSerializer.stripUiCollectionPrefix(cond.field)
            if (field.isEmpty()) continue
            if (cond.operator.requiresValue && cond.rawValue.isBlank()) continue
            val vals = MultiKeyJoinFilterEngine.extractFlattenedValues(doc, field)
            val schemaType = fieldTypes[field] ?: fieldTypes[field.substringAfterLast('.')]
            fun typed(raw: String): BsonValue =
                toBsonValue(CompassPipelineSerializer.convertValueForSchemaType(field, raw, schemaType))

            val ok = when (cond.operator) {
                JoinConditionOperator.IS_NOT_EMPTY -> vals.any { v ->
                    v !is BsonNull && !(v is BsonString && v.value.isEmpty())
                }
                JoinConditionOperator.IS_EMPTY -> vals.isEmpty() || vals.all { v ->
                    v is BsonNull || (v is BsonString && v.value.isEmpty())
                }
                JoinConditionOperator.EQ -> {
                    val target = typed(cond.rawValue)
                    vals.any { MultiKeyJoinFilterEngine.bsonValuesEqual(it, target) }
                }
                JoinConditionOperator.NE -> {
                    val target = typed(cond.rawValue)
                    vals.none { MultiKeyJoinFilterEngine.bsonValuesEqual(it, target) }
                }
                JoinConditionOperator.GT -> {
                    val target = typed(cond.rawValue)
                    vals.any { (compareValues(it, target) ?: -1) > 0 }
                }
                JoinConditionOperator.GTE -> {
                    val target = typed(cond.rawValue)
                    vals.any { (compareValues(it, target) ?: -1) >= 0 }
                }
                JoinConditionOperator.LT -> {
                    val target = typed(cond.rawValue)
                    vals.any { (compareValues(it, target) ?: 1) < 0 }
                }
                JoinConditionOperator.LTE -> {
                    val target = typed(cond.rawValue)
                    vals.any { (compareValues(it, target) ?: 1) <= 0 }
                }
                JoinConditionOperator.IN -> {
                    val list = splitCommaValues(cond.rawValue).map { typed(it) }
                    vals.any { lv -> list.any { rv -> MultiKeyJoinFilterEngine.bsonValuesEqual(lv, rv) } }
                }
            }
            if (!ok) return false
        }
        return true
    }

    private fun compareValues(a: BsonValue, b: BsonValue): Int? {
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

    private fun toBigDecimalOrNull(num: BsonNumber): BigDecimal? = when (num) {
        is BsonInt32 -> BigDecimal.valueOf(num.value.toLong())
        is BsonInt64 -> BigDecimal.valueOf(num.value)
        is BsonDouble -> if (num.value.isFinite()) BigDecimal.valueOf(num.value) else null
        is BsonDecimal128 -> runCatching { num.value.bigDecimalValue() }.getOrNull()
        else -> null
    }

    private fun splitCommaValues(src: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in src) {
            if (quote != null) {
                cur.append(ch)
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '"', '\'' -> {
                        quote = ch
                        cur.append(ch)
                    }
                    ',' -> {
                        val s = cur.toString().trim()
                        if (s.isNotEmpty()) out.add(s)
                        cur.setLength(0)
                    }
                    else -> cur.append(ch)
                }
            }
        }
        val last = cur.toString().trim()
        if (last.isNotEmpty()) out.add(last)
        return out
    }
}
