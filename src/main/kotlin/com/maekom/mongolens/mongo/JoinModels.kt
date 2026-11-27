package com.maekom.mongolens.mongo

import com.maekom.mongolens.i18n.I18n
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.types.ObjectId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class JoinMatchStrategy {
    LATEST_ONE,
    UNWIND_ALL
}

enum class JoinConditionTarget {
    LEFT,
    RIGHT
}

enum class JoinConditionOperator(val symbol: String, val requiresValue: Boolean = true) {
    EQ("=", true),
    NE("!=", true),
    GT(">", true),
    GTE(">=", true),
    LT("<", true),
    LTE("<=", true),
    IN("IN", true),
    IS_NOT_EMPTY("NOT NULL", false),
    IS_EMPTY("NULL/EMPTY", false);

    fun displayLabel(): String = when (this) {
        IS_NOT_EMPTY -> I18n.t("join.op.isNotEmpty")
        IS_EMPTY -> I18n.t("join.op.isEmpty")
        else -> symbol
    }

    override fun toString(): String = displayLabel()
}

sealed class JoinSourceRef {
    object Base : JoinSourceRef()
    data class PreviousTarget(val targetIndex: Int) : JoinSourceRef()
}

data class JoinCondition(
    val target: JoinConditionTarget,
    val field: String,
    val operator: JoinConditionOperator,
    val rawValue: String
)

data class JoinKeyPair(
    val sourceRef: JoinSourceRef = JoinSourceRef.Base,
    val localField: String,
    val foreignField: String
)

data class JoinTargetSpec(
    val targetCollection: String,
    val sourceRef: JoinSourceRef = JoinSourceRef.Base,
    val localField: String,
    val foreignField: String,
    val keyPairs: List<JoinKeyPair> = listOf(JoinKeyPair(sourceRef, localField, foreignField)),
    val matchStrategy: JoinMatchStrategy = JoinMatchStrategy.LATEST_ONE,
    val allColumns: List<String> = emptyList(),
    val selectedColumns: List<String> = emptyList(),
    val conditions: List<JoinCondition> = emptyList(),
    val excludeUnmatched: Boolean = true,
    val fieldTypes: Map<String, String> = emptyMap()
) {
    fun effectiveKeyPairs(): List<JoinKeyPair> {
        val cleaned = keyPairs.filter { it.localField.isNotBlank() && it.foreignField.isNotBlank() }
        return if (cleaned.isNotEmpty()) {
            cleaned
        } else {
            listOf(JoinKeyPair(sourceRef, localField, foreignField))
        }
    }

    fun isCompositeJoin(): Boolean = effectiveKeyPairs().size >= 2
}

data class ColumnOrigin(
    val resultFieldKey: String,
    val collectionName: String,
    val originalFieldName: String,
    val isBase: Boolean,
    val targetIndex: Int = -1
)

typealias JoinColumnOrigin = ColumnOrigin

data class JoinSpec(
    val leftCollection: String,
    val allLeftFields: List<String>,
    val selectedLeftFields: List<String>,
    val baseConditions: List<JoinCondition> = emptyList(),
    val targets: List<JoinTargetSpec>,
    val limitOption: Int? = 50, // null = Unlimited (safely capped at MAX_UNLIMITED_DOCS)
    val leftFieldTypes: Map<String, String> = emptyMap()
) {
    val primaryRightCollection: String
        get() = targets.firstOrNull()?.targetCollection.orEmpty()

    fun displayTitle(): String {
        val rightChain = targets.joinToString(" ⋈ ") { it.targetCollection }
        return if (rightChain.isNotEmpty()) "$leftCollection ⋈ $rightChain" else leftCollection
    }

    fun compactTabKeySegment(): String {
        return "$leftCollection⋈${targets.joinToString("⋈") { it.targetCollection }}"
    }

    fun hasCompositeJoin(): Boolean = targets.any { it.isCompositeJoin() }

    /**
     * Stable deterministic signature covering all join parameters for session-cache isolation.
     */
    fun cacheSignatureSegment(): String {
        val targetsSig = targets.joinToString("|") { t ->
            val keysSig = t.effectiveKeyPairs().joinToString("&") { k ->
                val refSig = when (val r = k.sourceRef) {
                    is JoinSourceRef.Base -> "B"
                    is JoinSourceRef.PreviousTarget -> "P${r.targetIndex}"
                }
                "$refSig:${k.localField.trim()}=${k.foreignField.trim()}"
            }
            val condsSig = t.conditions.joinToString(";") { "${it.field.trim()}${it.operator.name}${it.rawValue}" }
            "${t.targetCollection}[$keysSig][${t.matchStrategy.name}][ex=${t.excludeUnmatched}][c=$condsSig][cols=${t.selectedColumns.joinToString(",")}]"
        }
        val baseCondSig = baseConditions.joinToString(";") { "${it.field.trim()}${it.operator.name}${it.rawValue}" }
        return "$leftCollection|left=${selectedLeftFields.joinToString(",")}|bc=$baseCondSig|targets=$targetsSig"
    }

    /**
     * Human-readable join condition summary for the Join result tab header badge, e.g.:
     * `a_hts.userid=b_hts.userid AND a_hts.stcd=b_hts.stcd`
     */
    fun joinKeysSummary(): String {
        return targets.mapIndexed { idx, target ->
            target.effectiveKeyPairs().joinToString(" AND ") { pair ->
                val srcColl = when (val ref = pair.sourceRef) {
                    is JoinSourceRef.Base -> leftCollection
                    is JoinSourceRef.PreviousTarget -> {
                        targets.getOrNull(ref.targetIndex.coerceIn(0, (idx - 1).coerceAtLeast(0)))?.targetCollection
                            ?: leftCollection
                    }
                }
                "$srcColl.${pair.localField}=${target.targetCollection}.${pair.foreignField}"
            }
        }.filter { it.isNotBlank() }.joinToString("  |  ")
    }

    /**
     * Computes deterministic, collision-safe prefixes for each join target:
     * - First occurrence of `temp_hst` -> `temp_hst`
     * - Second occurrence of `temp_hst` -> `temp_hst_2`
     * - Third occurrence of `temp_hst` -> `temp_hst_3`
     */
    fun targetKeyPrefix(targetIndex: Int): String {
        val target = targets.getOrNull(targetIndex) ?: return "join_${targetIndex + 1}"
        val safeName = target.targetCollection.replace('.', '_')
        var occurrence = 0
        for (idx in 0..targetIndex) {
            if (targets[idx].targetCollection == target.targetCollection) {
                occurrence++
            }
        }
        return if (occurrence <= 1) safeName else "${safeName}_$occurrence"
    }

    /**
     * Builds the authoritative `resultFieldKey -> ColumnOrigin` map for all currently selected columns.
     * - Base collection columns keep `<originalFieldName>`.
     * - Join target columns use `<targetCollection>_<originalFieldName>` (or `<targetCollection>_2_<originalFieldName>` on duplicate target joins).
     */
    fun columnOriginsByResultKey(): LinkedHashMap<String, ColumnOrigin> {
        val map = linkedMapOf<String, ColumnOrigin>()
        for (lf in selectedLeftFields) {
            map[lf] = ColumnOrigin(
                resultFieldKey = lf,
                collectionName = leftCollection,
                originalFieldName = lf,
                isBase = true,
                targetIndex = -1
            )
        }
        targets.forEachIndexed { targetIdx, target ->
            val prefix = targetKeyPrefix(targetIdx)
            for (rf in target.selectedColumns) {
                val cleanRf = rf.replace('.', '_')
                var candidateKey = "${prefix}_$cleanRf"
                if (candidateKey in map) {
                    candidateKey = "${prefix}_j${targetIdx + 1}_$cleanRf"
                }
                map[candidateKey] = ColumnOrigin(
                    resultFieldKey = candidateKey,
                    collectionName = target.targetCollection,
                    originalFieldName = rf,
                    isBase = false,
                    targetIndex = targetIdx
                )
            }
        }
        return map
    }

    /**
     * Builds the full candidate `resultFieldKey -> ColumnOrigin` map across ALL base & target fields
     * for Table Header `Select Visible Columns (Project)...` without losing collection mapping.
     */
    fun allCandidateOriginsByResultKey(): LinkedHashMap<String, ColumnOrigin> {
        val map = linkedMapOf<String, ColumnOrigin>()
        for (lf in allLeftFields) {
            map[lf] = ColumnOrigin(
                resultFieldKey = lf,
                collectionName = leftCollection,
                originalFieldName = lf,
                isBase = true,
                targetIndex = -1
            )
        }
        targets.forEachIndexed { targetIdx, target ->
            val prefix = targetKeyPrefix(targetIdx)
            val candidateFields = (target.allColumns + target.selectedColumns).distinct()
            for (rf in candidateFields) {
                val cleanRf = rf.replace('.', '_')
                var candidateKey = "${prefix}_$cleanRf"
                if (candidateKey in map) {
                    candidateKey = "${prefix}_j${targetIdx + 1}_$cleanRf"
                }
                map[candidateKey] = ColumnOrigin(
                    resultFieldKey = candidateKey,
                    collectionName = target.targetCollection,
                    originalFieldName = rf,
                    isBase = false,
                    targetIndex = targetIdx
                )
            }
        }
        return map
    }

    fun visibleOutputColumns(): List<String> = columnOriginsByResultKey().keys.toList()

    fun allCandidateOutputColumns(): List<String> = allCandidateOriginsByResultKey().keys.toList()

    fun updateFromVisibleColumnSelection(chosenResultKeys: List<String>): JoinSpec {
        val chosenSet = chosenResultKeys.toSet()
        val allOrigins = allCandidateOriginsByResultKey()

        val newLeft = mutableListOf<String>()
        val newTargetCols = Array(targets.size) { mutableListOf<String>() }

        for ((resultKey, origin) in allOrigins) {
            if (resultKey !in chosenSet) continue
            if (origin.isBase) {
                newLeft.add(origin.originalFieldName)
            } else if (origin.targetIndex in targets.indices) {
                newTargetCols[origin.targetIndex].add(origin.originalFieldName)
            }
        }

        if (newLeft.isEmpty() && newTargetCols.all { it.isEmpty() }) {
            val fallbackLeft = allLeftFields.firstOrNull()?.let { listOf(it) } ?: listOf("_id")
            return copy(
                selectedLeftFields = fallbackLeft,
                targets = targets.map { it.copy(selectedColumns = emptyList()) }
            )
        }

        val updatedTargets = targets.mapIndexed { idx, t ->
            t.copy(selectedColumns = newTargetCols[idx].distinct())
        }
        return copy(
            selectedLeftFields = newLeft.distinct(),
            targets = updatedTargets
        )
    }

    /**
     * Merges top Filter bar query with all base-collection `WHERE AND` conditions into one `$match` document.
     */
    fun buildEffectiveLeftFilter(topFilter: BsonDocument): BsonDocument {
        val clauses = mutableListOf<BsonDocument>()
        if (!topFilter.isEmpty()) {
            clauses.add(topFilter)
        }
        for (cond in baseConditions) {
            if (cond.field.isNotBlank()) {
                clauses.add(buildStandardMatchClause(cond.field.trim(), cond.operator, cond.rawValue))
            }
        }
        return when (clauses.size) {
            0 -> BsonDocument()
            1 -> clauses[0]
            else -> BsonDocument("\$and", BsonArray(clauses))
        }
    }

    /**
     * Builds the AWS DocumentDB & MongoDB 100% compatible sequential Multi-Join Aggregation Pipeline:
     * 1. Base `$match` (top Filter + baseConditions)
     * 2. Deterministic `$sort` (user sort + `_id: 1` tie-breaker, or default `{ _id: -1 }` so pages never drift/duplicate)
     * 3. For each target[i] sequentially:
     *    - Standard localField/foreignField `$lookup` -> as: `_joined_$i` (ZERO `let`/`pipeline`, natively handles array-of-objects paths!)
     *    - `LATEST_ONE`: `$addFields` + `$cond($isArray)` + (`$filter` if conditions exist) + `$arrayElemAt(-1)`
     *    - `UNWIND_ALL`: `$unwind(preserveNullAndEmptyArrays: true)` + `$match` (or `$filter` before `$unwind` for LEFT JOIN)
     *    - If `target.excludeUnmatched`: `$match { "_joined_$i": { "$ne": null } }` immediately
     * 4. Post-Join single `$skip(pageIndex * pageSize)` & `$limit(pageSize + 1)` (NO `$facet`!; Unlimited omits `$skip` and caps at 5,000)
     * 5. Final flat 1D `$project` mapping every selected base & target column to its unique `resultFieldKey`
     */
    fun buildPipeline(
        filter: BsonDocument,
        sort: BsonDocument,
        pageIndex: Int = 0,
        pageSize: Int? = limitOption,
        probeForNextPage: Boolean = false
    ): List<BsonDocument> {
        val stages = mutableListOf<BsonDocument>()

        // Stage 1: Base collection pre-filter
        val effectiveLeftFilter = buildEffectiveLeftFilter(filter)
        if (!effectiveLeftFilter.isEmpty()) {
            stages.add(BsonDocument("\$match", effectiveLeftFilter))
        }

        // Stage 2: Deterministic $sort (always ensure _id tie-breaker so pagination is 100% stable)
        val effectiveSort = BsonDocument()
        if (sort.isEmpty()) {
            effectiveSort["_id"] = BsonInt32(-1)
        } else {
            for ((k, v) in sort) {
                effectiveSort[k] = v
            }
            if (!effectiveSort.containsKey("_id")) {
                effectiveSort["_id"] = BsonInt32(1)
            }
        }
        stages.add(BsonDocument("\$sort", effectiveSort))

        // Stage 3: Sequential $lookup (hybrid single-key vs composite let+pipeline) + per-target conditions + exclusion
        targets.forEachIndexed { idx, target ->
            val joinAlias = "_joined_$idx"
            val pairs = target.effectiveKeyPairs()
            val isComposite = pairs.size >= 2

            fun resolveSourceFieldPath(pair: JoinKeyPair): String = when (val src = pair.sourceRef) {
                is JoinSourceRef.Base -> pair.localField.trim()
                is JoinSourceRef.PreviousTarget -> {
                    val safeRef = src.targetIndex.coerceIn(0, (idx - 1).coerceAtLeast(0))
                    "_joined_$safeRef.${pair.localField.trim()}"
                }
            }

            val targetConds = target.conditions.filter {
                it.field.isNotBlank() && (!it.operator.requiresValue || it.rawValue.isNotBlank())
            }

            if (!isComposite) {
                // Single key pair: standard index-friendly localField/foreignField $lookup
                val single = pairs[0]
                val lookupDoc = BsonDocument()
                    .append("from", BsonString(target.targetCollection))
                    .append("localField", BsonString(resolveSourceFieldPath(single)))
                    .append("foreignField", BsonString(single.foreignField.trim()))
                    .append("as", BsonString(joinAlias))
                stages.add(BsonDocument("\$lookup", lookupDoc))
            } else {
                // Composite join keys (>= 2 AND keys): let + pipeline with safe local_k0..kN vars,
                // null/missing guard (prevents null==null explosion), and $cond($isArray) dynamic array/scalar matching.
                val letDoc = BsonDocument()
                val exprAndList = BsonArray()

                pairs.forEachIndexed { kIdx, pair ->
                    val varName = "local_k$kIdx"
                    val varRef = "\$\$$varName"
                    val srcPath = resolveSourceFieldPath(pair)
                    val foreignRef = "\$${pair.foreignField.trim()}"

                    letDoc[varName] = BsonString("\$$srcPath")

                    // Guard: { "$ne": [ { "$ifNull": [ "$$local_kN", null ] }, null ] }
                    exprAndList.add(
                        BsonDocument(
                            "\$ne",
                            BsonArray(
                                listOf(
                                    BsonDocument("\$ifNull", BsonArray(listOf(BsonString(varRef), BsonNull.VALUE))),
                                    BsonNull.VALUE
                                )
                            )
                        )
                    )

                    // Dynamic array/scalar match:
                    // { "$cond": [ { "$isArray": "$$local_kN" }, { "$in": [ "$foreign", { "$ifNull": [ "$$local_kN", [] ] } ] }, { "$eq": [ "$foreign", "$$local_kN" ] } ] }
                    exprAndList.add(
                        BsonDocument(
                            "\$cond",
                            BsonArray(
                                listOf(
                                    BsonDocument("\$isArray", BsonString(varRef)),
                                    BsonDocument(
                                        "\$in",
                                        BsonArray(
                                            listOf(
                                                BsonString(foreignRef),
                                                BsonDocument("\$ifNull", BsonArray(listOf(BsonString(varRef), BsonArray())))
                                            )
                                        )
                                    ),
                                    BsonDocument("\$eq", BsonArray(listOf(BsonString(foreignRef), BsonString(varRef))))
                                )
                            )
                        )
                    )
                }

                val innerPipeline = BsonArray()
                innerPipeline.add(
                    BsonDocument(
                        "\$match",
                        BsonDocument("\$expr", BsonDocument("\$and", exprAndList))
                    )
                )

                // Push down target conditions + { $sort: { _id: -1 } } + { $limit: 1 } for LATEST_ONE mode
                if (target.matchStrategy == JoinMatchStrategy.LATEST_ONE) {
                    if (targetConds.isNotEmpty()) {
                        val condClauses = targetConds.map {
                            buildStandardMatchClause(it.field.trim(), it.operator, it.rawValue)
                        }
                        val condMatch = if (condClauses.size == 1) {
                            condClauses[0]
                        } else {
                            BsonDocument("\$and", BsonArray(condClauses))
                        }
                        innerPipeline.add(BsonDocument("\$match", condMatch))
                    }
                    innerPipeline.add(BsonDocument("\$sort", BsonDocument("_id", BsonInt32(-1))))
                    innerPipeline.add(BsonDocument("\$limit", BsonInt32(1)))
                }

                val lookupDoc = BsonDocument()
                    .append("from", BsonString(target.targetCollection))
                    .append("let", letDoc)
                    .append("pipeline", innerPipeline)
                    .append("as", BsonString(joinAlias))
                stages.add(BsonDocument("\$lookup", lookupDoc))
            }

            when (target.matchStrategy) {
                JoinMatchStrategy.LATEST_ONE -> {
                    val thenExpr: BsonDocument = if (isComposite) {
                        // Already filtered + sorted ({ _id: -1 }) + limited (1) inside $lookup.pipeline
                        BsonDocument(
                            "\$arrayElemAt",
                            BsonArray(listOf(BsonString("\$$joinAlias"), BsonInt32(0)))
                        )
                    } else if (targetConds.isEmpty()) {
                        BsonDocument(
                            "\$arrayElemAt",
                            BsonArray(listOf(BsonString("\$$joinAlias"), BsonInt32(-1)))
                        )
                    } else {
                        val filterExprs = targetConds.map { buildFilterItemExpr(it) }
                        val condBody = if (filterExprs.size == 1) {
                            filterExprs[0]
                        } else {
                            BsonDocument("\$and", BsonArray(filterExprs))
                        }
                        val filterObj = BsonDocument()
                            .append("input", BsonString("\$$joinAlias"))
                            .append("as", BsonString("item"))
                            .append("cond", condBody)
                        BsonDocument(
                            "\$arrayElemAt",
                            BsonArray(listOf(BsonDocument("\$filter", filterObj), BsonInt32(-1)))
                        )
                    }

                    val condDoc = BsonDocument()
                        .append("if", BsonDocument("\$isArray", BsonString("\$$joinAlias")))
                        .append("then", thenExpr)
                        .append("else", BsonNull.VALUE)
                    stages.add(
                        BsonDocument(
                            "\$addFields",
                            BsonDocument(joinAlias, BsonDocument("\$cond", condDoc))
                        )
                    )
                }

                JoinMatchStrategy.UNWIND_ALL -> {
                    if (targetConds.isNotEmpty() && !target.excludeUnmatched) {
                        // LEFT JOIN: filter array before $unwind so unmatched base rows survive as null
                        val filterExprs = targetConds.map { buildFilterItemExpr(it) }
                        val condBody = if (filterExprs.size == 1) {
                            filterExprs[0]
                        } else {
                            BsonDocument("\$and", BsonArray(filterExprs))
                        }
                        val filterObj = BsonDocument()
                            .append("input", BsonString("\$$joinAlias"))
                            .append("as", BsonString("item"))
                            .append("cond", condBody)
                        val condDoc = BsonDocument()
                            .append("if", BsonDocument("\$isArray", BsonString("\$$joinAlias")))
                            .append("then", BsonDocument("\$filter", filterObj))
                            .append("else", BsonArray())
                        stages.add(
                            BsonDocument(
                                "\$addFields",
                                BsonDocument(joinAlias, BsonDocument("\$cond", condDoc))
                            )
                        )
                        stages.add(
                            BsonDocument(
                                "\$unwind",
                                BsonDocument()
                                    .append("path", BsonString("\$$joinAlias"))
                                    .append("preserveNullAndEmptyArrays", BsonBoolean.TRUE)
                            )
                        )
                    } else {
                        stages.add(
                            BsonDocument(
                                "\$unwind",
                                BsonDocument()
                                    .append("path", BsonString("\$$joinAlias"))
                                    .append("preserveNullAndEmptyArrays", BsonBoolean.TRUE)
                            )
                        )
                        if (targetConds.isNotEmpty()) {
                            val matchClauses = targetConds.map {
                                buildStandardMatchClause("$joinAlias.${it.field.trim()}", it.operator, it.rawValue)
                            }
                            val matchDoc = if (matchClauses.size == 1) {
                                matchClauses[0]
                            } else {
                                BsonDocument("\$and", BsonArray(matchClauses))
                            }
                            stages.add(BsonDocument("\$match", matchDoc))
                        }
                    }
                }
            }

            // Exclude unmatched / condition-failed base rows immediately when excludeUnmatched == true
            if (target.excludeUnmatched) {
                stages.add(
                    BsonDocument(
                        "\$match",
                        BsonDocument(joinAlias, BsonDocument("\$ne", BsonNull.VALUE))
                    )
                )
            }
        }

        // Stage 4: Single Post-Join $skip & $limit (strictly AFTER $lookup & exclusion $match!)
        if (pageSize != null) {
            val safeSize = pageSize.coerceIn(1, MAX_UNLIMITED_DOCS)
            val offset = pageIndex.coerceAtLeast(0) * safeSize
            if (offset > 0) {
                stages.add(BsonDocument("\$skip", BsonInt32(offset)))
            }
            val limitValue = if (probeForNextPage) safeSize + 1 else safeSize
            stages.add(BsonDocument("\$limit", BsonInt32(limitValue)))
        } else {
            // Unlimited mode: omit $skip, enforce 5,000-doc safety cap
            val capLimit = if (probeForNextPage) MAX_UNLIMITED_DOCS + 1 else MAX_UNLIMITED_DOCS
            stages.add(BsonDocument("\$limit", BsonInt32(capLimit)))
        }

        // Stage 5: Final flat 1D $project using authoritative ColumnOrigin keys
        val origins = columnOriginsByResultKey()
        val proj = BsonDocument()
        if ("_id" !in origins || origins["_id"]?.isBase != true) {
            proj["_id"] = BsonInt32(0)
        }
        for ((resultKey, origin) in origins) {
            if (origin.isBase) {
                proj[resultKey] = BsonInt32(1)
            } else {
                proj[resultKey] = BsonString("\$_joined_${origin.targetIndex}.${origin.originalFieldName}")
            }
        }
        if (proj.isEmpty() || (proj.size == 1 && proj["_id"] == BsonInt32(0))) {
            proj["_id"] = BsonInt32(1)
        }
        stages.add(BsonDocument("\$project", proj))

        return stages
    }

    /**
     * Builds the AWS DocumentDB (3.6/4.0/5.0) 100%-compatible 1st-stage Hybrid Join Pipeline:
     * - NEVER emits `$lookup` with `let`/`pipeline` (completely eliminates Error 304).
     * - Prioritizes unique identifier keys (`_id`, `id`, `*_id`, `*Id`) as the single `$lookup` key pair.
     * - Caps each joined array at `500` elements via `$addFields: { "<alias>": { "$slice": ["$<alias>", 500] } }`
     *   to guard against the 16MB BSON single-document limit.
     * - Omits `$skip` and fetches a bounded buffer (`$limit: bufferLimit`) for exact in-memory multi-key filtering & slicing.
     */
    fun buildHybridDocumentDbPipeline(
        filter: BsonDocument,
        sort: BsonDocument,
        bufferLimit: Int = MultiKeyJoinFilterEngine.MIN_HYBRID_BUFFER_LIMIT
    ): List<BsonDocument> {
        val stages = mutableListOf<BsonDocument>()

        // Stage 1: Base collection pre-filter
        val effectiveLeftFilter = buildEffectiveLeftFilter(filter)
        if (!effectiveLeftFilter.isEmpty()) {
            stages.add(BsonDocument("\$match", effectiveLeftFilter))
        }

        // Stage 2: Deterministic $sort (always ensure _id tie-breaker)
        val effectiveSort = BsonDocument()
        if (sort.isEmpty()) {
            effectiveSort["_id"] = BsonInt32(-1)
        } else {
            for ((k, v) in sort) {
                effectiveSort[k] = v
            }
            if (!effectiveSort.containsKey("_id")) {
                effectiveSort["_id"] = BsonInt32(1)
            }
        }
        stages.add(BsonDocument("\$sort", effectiveSort))

        // Stage 3: Safe upper-bound buffer (NO $skip!)
        stages.add(BsonDocument("\$limit", BsonInt32(bufferLimit.coerceIn(1, MAX_UNLIMITED_DOCS))))

        // Stage 4: Single-key standard $lookup (prioritizing unique ID key) + 500-element $slice guard
        targets.forEachIndexed { idx, target ->
            val joinAlias = "_joined_$idx"
            val (primaryPair, _) = MultiKeyJoinFilterEngine.selectPrimaryAndRemainingPairs(target.effectiveKeyPairs())
            val localPath = when (val src = primaryPair.sourceRef) {
                is JoinSourceRef.Base -> primaryPair.localField.trim()
                is JoinSourceRef.PreviousTarget -> {
                    val safeRef = src.targetIndex.coerceIn(0, (idx - 1).coerceAtLeast(0))
                    "_joined_$safeRef.${primaryPair.localField.trim()}"
                }
            }
            val lookupDoc = BsonDocument()
                .append("from", BsonString(target.targetCollection))
                .append("localField", BsonString(localPath))
                .append("foreignField", BsonString(primaryPair.foreignField.trim()))
                .append("as", BsonString(joinAlias))
            stages.add(BsonDocument("\$lookup", lookupDoc))

            // 16MB BSON guard: cap each joined array at 500 elements immediately after $lookup
            stages.add(
                BsonDocument(
                    "\$addFields",
                    BsonDocument(
                        joinAlias,
                        BsonDocument(
                            "\$slice",
                            BsonArray(listOf(BsonString("\$$joinAlias"), BsonInt32(MultiKeyJoinFilterEngine.HYBRID_SLICE_LIMIT)))
                        )
                    )
                )
            )
        }

        return stages
    }

    fun formatPipelineJsonArray(
        filterRaw: String?,
        sortRaw: String?,
        pageIndex: Int,
        pageSize: Int?,
        useDocumentDbHybrid: Boolean = false
    ): String {
        val effectiveSpec = if (pageSize != null && pageSize != limitOption) {
            copy(limitOption = pageSize)
        } else {
            this
        }
        return CompassPipelineSerializer.formatCompassPipelineJson(
            joinSpec = effectiveSpec,
            filterRaw = filterRaw,
            sortRaw = sortRaw
        )
    }

    fun toMongoshScript(
        filterRaw: String?,
        sortRaw: String?,
        pageIndex: Int,
        pageSize: Int?
    ): String {
        val prettyArray = formatPipelineJsonArray(filterRaw, sortRaw, pageIndex, pageSize)
        return MongoShellNormalizer.toMongoshAggregateCommand(leftCollection, prettyArray)
    }

    private fun buildStandardMatchClause(
        fieldPath: String,
        operator: JoinConditionOperator,
        rawValue: String
    ): BsonDocument {
        val baseFieldName = fieldPath.substringAfterLast('.')
        return when (operator) {
            JoinConditionOperator.EQ -> BsonDocument(fieldPath, parseTypedValue(baseFieldName, rawValue))
            JoinConditionOperator.NE -> BsonDocument(fieldPath, BsonDocument("\$ne", parseTypedValue(baseFieldName, rawValue)))
            JoinConditionOperator.GT -> BsonDocument(fieldPath, BsonDocument("\$gt", parseTypedValue(baseFieldName, rawValue)))
            JoinConditionOperator.GTE -> BsonDocument(fieldPath, BsonDocument("\$gte", parseTypedValue(baseFieldName, rawValue)))
            JoinConditionOperator.LT -> BsonDocument(fieldPath, BsonDocument("\$lt", parseTypedValue(baseFieldName, rawValue)))
            JoinConditionOperator.LTE -> BsonDocument(fieldPath, BsonDocument("\$lte", parseTypedValue(baseFieldName, rawValue)))
            JoinConditionOperator.IN -> BsonDocument(fieldPath, BsonDocument("\$in", parseInList(baseFieldName, rawValue)))
            JoinConditionOperator.IS_NOT_EMPTY -> BsonDocument(
                fieldPath,
                BsonDocument("\$exists", BsonBoolean.TRUE)
                    .append("\$nin", BsonArray(listOf(BsonNull.VALUE, BsonString(""))))
            )
            JoinConditionOperator.IS_EMPTY -> BsonDocument(
                "\$or",
                BsonArray(
                    listOf(
                        BsonDocument(fieldPath, BsonDocument("\$exists", BsonBoolean.FALSE)),
                        BsonDocument(fieldPath, BsonDocument("\$in", BsonArray(listOf(BsonNull.VALUE, BsonString("")))))
                    )
                )
            )
        }
    }

    private fun buildFilterItemExpr(cond: JoinCondition): BsonDocument {
        val field = cond.field.trim()
        val itemRef = BsonString("\$\$item.$field")
        val baseFieldName = field.substringAfterLast('.')
        val mayBeArrayOfObjects = field.contains('.')
        return when (cond.operator) {
            JoinConditionOperator.EQ -> {
                val lit = wrapExprLiteral(parseTypedValue(baseFieldName, cond.rawValue))
                if (mayBeArrayOfObjects) {
                    BsonDocument(
                        "\$cond",
                        BsonDocument()
                            .append("if", BsonDocument("\$isArray", itemRef))
                            .append("then", BsonDocument("\$in", BsonArray(listOf(lit, itemRef))))
                            .append("else", BsonDocument("\$eq", BsonArray(listOf(itemRef, lit))))
                    )
                } else {
                    BsonDocument("\$eq", BsonArray(listOf(itemRef, lit)))
                }
            }
            JoinConditionOperator.NE -> {
                val lit = wrapExprLiteral(parseTypedValue(baseFieldName, cond.rawValue))
                if (mayBeArrayOfObjects) {
                    BsonDocument(
                        "\$cond",
                        BsonDocument()
                            .append("if", BsonDocument("\$isArray", itemRef))
                            .append("then", BsonDocument("\$not", BsonArray(listOf(BsonDocument("\$in", BsonArray(listOf(lit, itemRef)))))))
                            .append("else", BsonDocument("\$ne", BsonArray(listOf(itemRef, lit))))
                    )
                } else {
                    BsonDocument("\$ne", BsonArray(listOf(itemRef, lit)))
                }
            }
            JoinConditionOperator.GT -> BsonDocument("\$gt", BsonArray(listOf(itemRef, wrapExprLiteral(parseTypedValue(baseFieldName, cond.rawValue)))))
            JoinConditionOperator.GTE -> BsonDocument("\$gte", BsonArray(listOf(itemRef, wrapExprLiteral(parseTypedValue(baseFieldName, cond.rawValue)))))
            JoinConditionOperator.LT -> BsonDocument("\$lt", BsonArray(listOf(itemRef, wrapExprLiteral(parseTypedValue(baseFieldName, cond.rawValue)))))
            JoinConditionOperator.LTE -> BsonDocument("\$lte", BsonArray(listOf(itemRef, wrapExprLiteral(parseTypedValue(baseFieldName, cond.rawValue)))))
            JoinConditionOperator.IN -> {
                val arr = parseInList(baseFieldName, cond.rawValue)
                val safeArr = BsonArray(arr.map { wrapExprLiteral(it) })
                if (mayBeArrayOfObjects) {
                    BsonDocument(
                        "\$cond",
                        BsonDocument()
                            .append("if", BsonDocument("\$isArray", itemRef))
                            .append(
                                "then",
                                BsonDocument(
                                    "\$gt",
                                    BsonArray(
                                        listOf(
                                            BsonDocument("\$size", BsonDocument("\$setIntersection", BsonArray(listOf(itemRef, safeArr)))),
                                            BsonInt32(0)
                                        )
                                    )
                                )
                            )
                            .append("else", BsonDocument("\$in", BsonArray(listOf(itemRef, safeArr))))
                    )
                } else {
                    BsonDocument("\$in", BsonArray(listOf(itemRef, safeArr)))
                }
            }
            JoinConditionOperator.IS_NOT_EMPTY -> {
                // Normalize missing field -> null via $ifNull so missing fields are treated identically to null
                val normalized = BsonDocument("\$ifNull", BsonArray(listOf(itemRef, BsonNull.VALUE)))
                BsonDocument(
                    "\$and",
                    BsonArray(
                        listOf(
                            BsonDocument("\$ne", BsonArray(listOf(normalized, BsonNull.VALUE))),
                            BsonDocument("\$ne", BsonArray(listOf(normalized, BsonString("")))),
                            BsonDocument("\$ne", BsonArray(listOf(normalized, BsonArray())))
                        )
                    )
                )
            }
            JoinConditionOperator.IS_EMPTY -> {
                val normalized = BsonDocument("\$ifNull", BsonArray(listOf(itemRef, BsonNull.VALUE)))
                BsonDocument(
                    "\$or",
                    BsonArray(
                        listOf(
                            BsonDocument("\$eq", BsonArray(listOf(normalized, BsonNull.VALUE))),
                            BsonDocument("\$eq", BsonArray(listOf(normalized, BsonString("")))),
                            BsonDocument("\$eq", BsonArray(listOf(normalized, BsonArray())))
                        )
                    )
                )
            }
        }
    }

    private fun wrapExprLiteral(v: BsonValue): BsonValue {
        return if (v is BsonString && v.value.startsWith("$")) {
            BsonDocument("\$literal", v)
        } else {
            v
        }
    }

    companion object {
        const val MAX_UNLIMITED_DOCS = 5000
        val JOIN_LIMIT_OPTIONS: List<Int?> = listOf(20, 50, 100, 500, null)

        private val hex24Regex = Regex("^[0-9a-fA-F]{24}$")
        private val intRegex = Regex("^-?(?:0|[1-9]\\d*)$")
        private val decimalRegex = Regex("^-?(?:0|[1-9]\\d*)\\.\\d+$")

        fun parseTypedValue(fieldName: String, raw: String): BsonValue {
            val trimmed = raw.trim()
            if (trimmed.length >= 2 &&
                ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                    (trimmed.startsWith("'") && trimmed.endsWith("'")))
            ) {
                return BsonString(trimmed.substring(1, trimmed.length - 1))
            }
            if (trimmed.equals("true", ignoreCase = true)) return BsonBoolean.TRUE
            if (trimmed.equals("false", ignoreCase = true)) return BsonBoolean.FALSE
            if (trimmed.equals("null", ignoreCase = true)) return BsonNull.VALUE

            if (fieldName == "_id" && hex24Regex.matches(trimmed)) {
                return BsonObjectId(ObjectId(trimmed))
            }
            if (intRegex.matches(trimmed)) {
                trimmed.toIntOrNull()?.let { return BsonInt32(it) }
                trimmed.toLongOrNull()?.let { return BsonInt64(it) }
            }
            if (decimalRegex.matches(trimmed)) {
                trimmed.toDoubleOrNull()?.let { return BsonDouble(it) }
            }
            return BsonString(trimmed)
        }

        fun parseInList(fieldName: String, raw: String): BsonArray {
            val parts = splitCommaRespectingQuotes(raw)
            val arr = BsonArray()
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.isNotEmpty()) {
                    arr.add(parseTypedValue(fieldName, trimmed))
                }
            }
            return arr
        }

        private fun splitCommaRespectingQuotes(src: String): List<String> {
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
                            out.add(cur.toString())
                            cur.setLength(0)
                        }
                        else -> cur.append(ch)
                    }
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
    }
}

object ReferenceJumpHelper {
    data class RankedCollectionItem(
        val name: String,
        val badgeKey: String?
    )

    private val hex24Regex = Regex("^[0-9a-fA-F]{24}$")
    private val recentMappings = ConcurrentHashMap<String, String>()

    fun recordSelection(database: String, fieldPath: String, targetCollection: String) {
        val leaf = fieldPath.substringAfterLast('.').trim()
        if (leaf.isNotEmpty()) {
            recentMappings["$database:$leaf"] = targetCollection
        }
    }

    fun isReferenceCandidate(fieldPath: String?, value: BsonValue?): Boolean {
        if (fieldPath.isNullOrBlank() || value == null) return false
        return when (value) {
            is BsonObjectId, is BsonString, is BsonInt32, is BsonInt64, is BsonDecimal128, is BsonDouble -> true
            is org.bson.BsonBinary -> true
            else -> false
        }
    }

    fun camelToSnake(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length + 4)
        input.forEachIndexed { idx, c ->
            if (c.isUpperCase()) {
                if (idx > 0 && input[idx - 1] != '_' && !input[idx - 1].isUpperCase()) {
                    sb.append('_')
                }
                sb.append(c.lowercaseChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun snakeToCamel(input: String): String {
        if (!input.contains('_') || input.startsWith("_")) return input
        val parts = input.split('_').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return input
        return buildString {
            append(parts.first().lowercase(Locale.ROOT))
            for (i in 1 until parts.size) {
                val p = parts[i].lowercase(Locale.ROOT)
                append(p.replaceFirstChar { it.uppercaseChar() })
            }
        }
    }

    private fun extractStem(fieldName: String): String {
        val leaf = fieldName.substringAfterLast('.').trim().trimStart('_')
        val snake = camelToSnake(leaf)
        val stripped = snake
            .removeSuffix("_id")
            .removeSuffix("_ids")
            .removeSuffix("_no")
            .removeSuffix("_key")
            .removeSuffix("_code")
            .removeSuffix("_seq")
            .trim('_')
        return stripped.lowercase(Locale.ROOT)
    }

    fun rankCollections(
        database: String,
        currentCollection: String,
        fieldPath: String?,
        allCollections: List<String>
    ): List<RankedCollectionItem> {
        val leaf = fieldPath?.substringAfterLast('.')?.trim().orEmpty()
        val recent = if (leaf.isNotEmpty()) recentMappings["$database:$leaf"] else null
        val stem = if (leaf.isNotEmpty()) extractStem(leaf) else ""
        val currentStem = extractStem(currentCollection)

        val candidateNames = if (stem.isNotEmpty()) {
            setOf(
                stem,
                "${stem}s",
                "${stem}es",
                "${stem}_hst",
                "${stem}_history",
                "${stem}_tb",
                "tb_$stem",
                snakeToCamel(stem),
                "${snakeToCamel(stem)}s"
            )
        } else {
            emptySet()
        }

        val recentList = mutableListOf<RankedCollectionItem>()
        val suggestedList = mutableListOf<RankedCollectionItem>()
        val restList = mutableListOf<RankedCollectionItem>()

        for (coll in allCollections.distinct()) {
            val lower = coll.lowercase(Locale.ROOT)
            when {
                recent != null && coll == recent -> {
                    recentList.add(RankedCollectionItem(coll, "result.ref.badgeRecent"))
                }
                coll != currentCollection && (
                    lower in candidateNames ||
                        (stem.length >= 3 && lower.contains(stem)) ||
                        (currentStem.length >= 3 && (lower.contains(currentStem) || (lower.endsWith("_hst") && currentCollection.endsWith("_hst"))))
                    ) -> {
                    suggestedList.add(RankedCollectionItem(coll, "result.ref.badgeSuggested"))
                }
                else -> {
                    restList.add(RankedCollectionItem(coll, null))
                }
            }
        }

        return recentList + suggestedList.sortedBy { it.name } + restList.sortedBy { it.name }
    }

    fun buildReferenceFilterQuery(
        sourceFieldPath: String,
        sourceValue: BsonValue,
        targetTopFields: List<String>,
        targetFieldTypes: Map<String, SmartInValueType>
    ): String {
        val leaf = sourceFieldPath.substringAfterLast('.').trim()
        val snake = camelToSnake(leaf)
        val camel = snakeToCamel(leaf)
        val fieldSet = targetTopFields.toSet()

        val chosenField = when {
            leaf.isNotEmpty() && leaf != "_id" && leaf in fieldSet -> leaf
            snake.isNotEmpty() && snake != "_id" && snake in fieldSet -> snake
            camel.isNotEmpty() && camel != "_id" && camel in fieldSet -> camel
            else -> "_id"
        }

        val targetType = targetFieldTypes[chosenField]
        val preferObjectId = when {
            targetType == SmartInValueType.OBJECT_ID -> true
            targetType == SmartInValueType.STRING -> false
            chosenField == "_id" -> true
            else -> sourceValue is BsonObjectId
        }
        val preferString = targetType == SmartInValueType.STRING

        val formattedValue = formatCoercedQueryLiteral(sourceValue, preferObjectId, preferString)
        val escapedField = MongoShellNormalizer.escapeJsonString(chosenField)
        return """{ "$escapedField": $formattedValue }"""
    }

    private fun formatCoercedQueryLiteral(
        value: BsonValue,
        preferObjectId: Boolean,
        preferString: Boolean
    ): String {
        return when (value) {
            is BsonObjectId -> {
                val hex = value.value.toHexString()
                if (preferString) "\"$hex\"" else "ObjectId(\"$hex\")"
            }
            is BsonString -> {
                val raw = value.value.trim()
                if (preferObjectId && hex24Regex.matches(raw)) {
                    "ObjectId(\"$raw\")"
                } else {
                    "\"${MongoShellNormalizer.escapeJsonString(value.value)}\""
                }
            }
            is BsonInt32 -> value.value.toString()
            is BsonInt64 -> value.value.toString()
            is BsonDouble -> value.value.toString()
            is BsonDecimal128 -> "NumberDecimal(\"${value.value}\")"
            is org.bson.BsonBinary -> {
                val sub = value.type
                if ((sub == org.bson.BsonBinarySubType.UUID_STANDARD.value ||
                        sub == org.bson.BsonBinarySubType.UUID_LEGACY.value) &&
                    value.data.size == 16
                ) {
                    "UUID(\"${value.asUuid()}\")"
                } else {
                    "\"${MongoShellNormalizer.escapeJsonString(value.toString())}\""
                }
            }
            else -> "\"${MongoShellNormalizer.escapeJsonString(value.toString())}\""
        }
    }
}
