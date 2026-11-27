package com.maekom.mongolens.mongo

import org.bson.Document
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.math.BigDecimal

/**
 * Pure Kotlin (ZERO IntelliJ UI dependencies) AWS DocumentDB (3.6/4.0/5.0) & MongoDB Compass
 * 100%-compatible Aggregation Pipeline serializer for real-time "Copy Pipeline".
 *
 * Guarantees:
 * 1. Strips UI display prefixes (`기준(<coll>).`, `Base(<coll>).`, `조인N(<coll>).`, `JoinN(<coll>).`).
 * 2. NEVER emits `$lookup` with `let` + `pipeline` (eliminates AWS DocumentDB Error 304:
 *    `Aggregation stage not supported: '$lookup on multiple join conditions'`).
 *    Always emits standard single-key `$lookup` (`from`, `localField`, `foreignField`, `as`).
 * 3. Handles 2nd+ composite join keys (`pairIndex >= 1`) and target `WHERE AND` conditions via a follow-up
 *    `$addFields` + null-safe `$filter` (`input: { "$ifNull": ["$<alias>", []] }`, `as: "item"`) using pure
 *    Aggregation Expression operators (`$eq`, `$ne`, `$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$regexMatch`, `$ifNull`).
 * 4. Handles `LATEST_ONE` without `$sortArray` via DocumentDB-compatible `$cond` + `$gt` + `$size` + `$ifNull` + `$arrayElemAt(-1)`.
 * 5. Emits `{ "$match": { "<alias>": { "$ne": [] } } }` when `excludeUnmatched == true`, `$unwind` for `UNWIND_ALL`,
 *    early `$limit` before `$lookup` for unsorted `LEFT JOIN`, and flat `$project` across all targets.
 * 6. Eliminates Compass `Sort with object type`: omits empty `$sort: {}`, flattens Extended JSON `$numberInt`
 *    to pure `Int` (`1` / `-1`), and outputs `JsonMode.RELAXED` JSON (`{ "$oid": "..." }`).
 */
object CompassPipelineSerializer {

    private val UI_PREFIX_REGEX = Regex("^(?:기준|Base|조인\\d+|Join\\d+)\\([^)]*\\)\\.")
    private val HEX_24_REGEX = Regex("^[0-9a-fA-F]{24}$")
    private val INT_REGEX = Regex("^-?(?:0|[1-9]\\d*)$")
    private val DECIMAL_REGEX = Regex("^-?(?:0|[1-9]\\d*)\\.\\d+$")

    private val RELAXED_PRETTY: JsonWriterSettings = JsonWriterSettings.builder()
        .outputMode(JsonMode.RELAXED)
        .indent(true)
        .build()

    /**
     * Resolves a deterministic, MongoDB-safe join alias (`auth_history`, `auth_history_2`, ...).
     */
    fun resolveJoinAlias(joinSpec: JoinSpec, targetIndex: Int): String {
        val raw = joinSpec.targetKeyPrefix(targetIndex)
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .trimStart('$')
        return raw.ifEmpty { "joined_${targetIndex + 1}" }
    }

    /**
     * Strips UI display prefixes such as `기준(auth_history).user_id` or `Base(orders)._id` -> `user_id` / `_id`.
     */
    fun stripUiCollectionPrefix(rawFieldPath: String): String {
        var cur = rawFieldPath.trim()
        while (UI_PREFIX_REGEX.containsMatchIn(cur)) {
            cur = cur.replaceFirst(UI_PREFIX_REGEX, "").trim()
        }
        return cur
    }

    /**
     * Parses and normalizes a sort specification into a pure integer `{ field: 1 | -1 }` `Document`,
     * or returns `null` when empty/blank/invalid so empty `{ "$sort": {} }` is NEVER added to pipelines.
     *
     * Handles:
     * - Blank or `"{}"` -> `null`
     * - Plain field name (`"created_at"`, `"-created_at"`) -> `Document("created_at", 1)` / `Document("created_at", -1)`
     * - JSON object including Extended JSON (`{ "field": { "$numberInt": "-1" } }`) -> `Document("field", -1)`
     */
    fun parseNormalizedSortDocument(sortRaw: String?): Document? {
        val trimmed = sortRaw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "{}") return null

        val normalized = Document()
        if (trimmed.startsWith("{")) {
            val parsed = runCatching { Document.parse(trimmed) }.getOrNull() ?: return null
            for ((k, v) in parsed) {
                val cleanKey = stripUiCollectionPrefix(k)
                if (cleanKey.isEmpty()) continue
                val dir = extractSortDirection(v) ?: continue
                normalized[cleanKey] = dir
            }
        } else {
            val parts = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            for (part in parts) {
                val descending = part.startsWith("-")
                val key = stripUiCollectionPrefix(part.removePrefix("-").removePrefix("+"))
                if (key.isNotEmpty()) {
                    normalized[key] = if (descending) -1 else 1
                }
            }
        }
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun extractSortDirection(rawVal: Any?): Int? {
        return when (rawVal) {
            is Number -> if (rawVal.toDouble() < 0.0) -1 else 1
            is String -> rawVal.trim().toDoubleOrNull()?.let { if (it < 0.0) -1 else 1 }
            is Map<*, *> -> {
                val extNum = rawVal["\$numberInt"]
                    ?: rawVal["\$numberLong"]
                    ?: rawVal["\$numberDouble"]
                    ?: rawVal["\$numberDecimal"]
                extractSortDirection(extNum)
            }
            else -> null
        }
    }

    /**
     * Converts a condition input value using sampled schema BSON type first:
     * - Schema `String`: ALWAYS preserves `"1234"` as String!
     * - Schema `Int32` / `Int64` / `Double` / `Decimal128` / `Boolean` / `ObjectId`: casts strictly when valid.
     * - `_id` 24-char hex string: converts to `org.bson.types.ObjectId`.
     */
    fun convertValueForSchemaType(fieldName: String, rawValue: String, schemaType: String?): Any? {
        val trimmed = rawValue.trim()
        val unquoted = if (trimmed.length >= 2 &&
            ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'")))
        ) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }

        if (unquoted.equals("null", ignoreCase = true) && trimmed == unquoted) {
            return null
        }

        val leaf = stripUiCollectionPrefix(fieldName).substringAfterLast('.')
        val normType = schemaType?.trim()?.lowercase()

        // Explicit schema type takes top priority (e.g. String field holding "1234" MUST stay String)
        when (normType) {
            "string" -> return unquoted
            "int32", "int" -> unquoted.toIntOrNull()?.let { return it }
            "int64", "long" -> unquoted.toLongOrNull()?.let { return it }
            "double" -> unquoted.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
            "decimal128" -> runCatching { Decimal128(BigDecimal(unquoted)) }.getOrNull()?.let { return it }
            "boolean", "bool" -> {
                if (unquoted.equals("true", ignoreCase = true)) return true
                if (unquoted.equals("false", ignoreCase = true)) return false
            }
            "objectid" -> if (HEX_24_REGEX.matches(unquoted)) return ObjectId(unquoted)
        }

        // Quoted literals always stay String
        if (trimmed != unquoted) return unquoted

        // Fallback inference when schema type is unknown
        if (leaf == "_id" && HEX_24_REGEX.matches(unquoted)) {
            return ObjectId(unquoted)
        }
        if (unquoted.equals("true", ignoreCase = true)) return true
        if (unquoted.equals("false", ignoreCase = true)) return false
        if (INT_REGEX.matches(unquoted)) {
            unquoted.toIntOrNull()?.let { return it }
            unquoted.toLongOrNull()?.let { return it }
        }
        if (DECIMAL_REGEX.matches(unquoted)) {
            unquoted.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
        }
        return unquoted
    }

    /**
     * Returns true iff every join target has at least one non-blank `(localField, foreignField)` pair
     * after stripping UI display prefixes.
     */
    fun hasValidJoinKeys(joinSpec: JoinSpec): Boolean {
        if (joinSpec.targets.isEmpty()) return false
        return joinSpec.targets.all { target ->
            target.effectiveKeyPairs().any { pair ->
                stripUiCollectionPrefix(pair.localField).isNotEmpty() &&
                    stripUiCollectionPrefix(pair.foreignField).isNotEmpty()
            }
        }
    }

    /**
     * Builds a complete AWS DocumentDB & MongoDB Compass-ready Aggregation pipeline (`List<Document>`):
     * 1. Stage 1 — Base (`LEFT`) pre-filter `$match` at pipeline top (schema-type-aware values).
     * 2. Stage 2 — Normalized `$sort` (omitted when blank/`{}`, pure `1`/`-1`) + early `$limit` BEFORE `$lookup`
     *    when ALL targets are `LEFT JOIN` (`excludeUnmatched == false`) and no top-level sort is present.
     * 3. Stage 3 — Standard single-key `$lookup` (ZERO `let`/`pipeline`!), followed by:
     *    - `$addFields` + null-safe `$filter` ONLY when 2nd+ join keys (`pairIndex >= 1`) or target conditions exist
     *    - `$addFields` + `$cond`/`$size`/`$ifNull`/`$arrayElemAt(-1)` when `matchStrategy == LATEST_ONE`
     *    - `$match: { "<alias>": { "$ne": [] } }` when `excludeUnmatched == true`
     *    - `$unwind` when `matchStrategy == UNWIND_ALL`
     * 4. Stage 4 — Deferred `$limit` (for `INNER JOIN` or sorted queries) and flat `$project` across all targets.
     */
    fun buildCompassPipelineDocuments(
        joinSpec: JoinSpec,
        filterRaw: String? = null,
        sortRaw: String? = null,
        baseFieldTypes: Map<String, String> = joinSpec.leftFieldTypes,
        targetFieldTypesList: List<Map<String, String>> = joinSpec.targets.map { it.fieldTypes }
    ): List<Document> {
        val stages = mutableListOf<Document>()

        // Stage 1: Base collection pre-filter ($match combining top Filter bar + Base WHERE AND conditions)
        val baseClauses = mutableListOf<Document>()
        val cleanFilter = filterRaw?.trim().orEmpty()
        if (cleanFilter.isNotEmpty() && cleanFilter != "{}") {
            runCatching { Document.parse(cleanFilter) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { baseClauses.add(it) }
        }
        for (cond in joinSpec.baseConditions) {
            val cleanField = stripUiCollectionPrefix(cond.field)
            if (cleanField.isEmpty()) continue
            if (cond.operator.requiresValue && cond.rawValue.isBlank()) continue
            val schemaType = baseFieldTypes[cleanField] ?: baseFieldTypes[cleanField.substringAfterLast('.')]
            baseClauses.add(buildConditionMatchDoc(cleanField, cond.operator, cond.rawValue, schemaType))
        }
        mergeMatchClauses(baseClauses)?.let { mergedBaseMatch ->
            stages.add(Document("\$match", mergedBaseMatch))
        }

        // Stage 2: Normalized top-level $sort (strictly OMITTED when blank/empty; pure Int 1/-1 only!)
        val normalizedSort = parseNormalizedSortDocument(sortRaw)
        if (normalizedSort != null) {
            stages.add(Document("\$sort", normalizedSort))
        }

        // Stage 2 (Optimization): Early $limit BEFORE $lookup when LEFT JOIN (excludeUnmatched == false) & no sort
        val allLeftJoin = joinSpec.targets.isNotEmpty() && joinSpec.targets.all { !it.excludeUnmatched }
        val limitValue = joinSpec.limitOption?.coerceIn(1, JoinSpec.MAX_UNLIMITED_DOCS)
        val emitEarlyLimit = allLeftJoin && normalizedSort == null
        if (emitEarlyLimit && limitValue != null) {
            stages.add(Document("\$limit", limitValue))
        }

        // Stage 3: Per-target AWS DocumentDB & Compass 100%-compatible standard single-key $lookup + $filter + strategy
        joinSpec.targets.forEachIndexed { idx, target ->
            val alias = resolveJoinAlias(joinSpec, idx)
            val targetTypes = targetFieldTypesList.getOrNull(idx) ?: target.fieldTypes
            val cleanPairs = target.effectiveKeyPairs().map { pair ->
                pair.copy(
                    localField = stripUiCollectionPrefix(pair.localField),
                    foreignField = stripUiCollectionPrefix(pair.foreignField)
                )
            }.filter { it.localField.isNotEmpty() && it.foreignField.isNotEmpty() }

            if (cleanPairs.isEmpty()) return@forEachIndexed

            val targetConds = target.conditions.filter {
                stripUiCollectionPrefix(it.field).isNotEmpty() && (!it.operator.requiresValue || it.rawValue.isNotBlank())
            }

            fun resolveLocalPath(pair: JoinKeyPair): String = when (val src = pair.sourceRef) {
                is JoinSourceRef.Base -> pair.localField
                is JoinSourceRef.PreviousTarget -> {
                    val safePrev = src.targetIndex.coerceIn(0, (idx - 1).coerceAtLeast(0))
                    "${resolveJoinAlias(joinSpec, safePrev)}.${pair.localField}"
                }
            }

            // 1) ALWAYS use standard single-key $lookup (NO `let`, NO `pipeline` -> 0 Error 304 on AWS DocumentDB!)
            val firstPair = cleanPairs[0]
            stages.add(
                Document(
                    "\$lookup",
                    Document("from", target.targetCollection)
                        .append("localField", resolveLocalPath(firstPair))
                        .append("foreignField", firstPair.foreignField)
                        .append("as", alias)
                )
            )

            // 2) Secondary join keys (pairIndex >= 1) + target WHERE AND conditions via null-safe `$addFields` + `$filter`
            val secondaryPairs = cleanPairs.drop(1)
            if (secondaryPairs.isNotEmpty() || targetConds.isNotEmpty()) {
                val filterConds = mutableListOf<Document>()

                for (pair in secondaryPairs) {
                    filterConds.add(
                        Document(
                            "\$eq",
                            listOf("\$\$item.${pair.foreignField}", "\$${resolveLocalPath(pair)}")
                        )
                    )
                }

                for (cond in targetConds) {
                    val cf = stripUiCollectionPrefix(cond.field)
                    val st = targetTypes[cf] ?: targetTypes[cf.substringAfterLast('.')]
                    filterConds.add(buildFilterItemExpression(cf, cond.operator, cond.rawValue, st))
                }

                val filterExpr = Document(
                    "\$filter",
                    Document("input", Document("\$ifNull", listOf("\$$alias", emptyList<Any>())))
                        .append("as", "item")
                        .append("cond", Document("\$and", filterConds))
                )
                stages.add(Document("\$addFields", Document(alias, filterExpr)))
            }

            // 3) LATEST_ONE using AWS DocumentDB-compatible operators ($cond + $gt + $size + $ifNull + $arrayElemAt)
            if (target.matchStrategy == JoinMatchStrategy.LATEST_ONE) {
                val latestOneExpr = Document(
                    "\$cond",
                    Document(
                        "if",
                        Document(
                            "\$gt",
                            listOf(
                                Document("\$size", Document("\$ifNull", listOf("\$$alias", emptyList<Any>()))),
                                0
                            )
                        )
                    )
                        .append("then", listOf(Document("\$arrayElemAt", listOf("\$$alias", -1))))
                        .append("else", emptyList<Any>())
                )
                stages.add(Document("\$addFields", Document(alias, latestOneExpr)))
            }

            // 4) INNER JOIN exclusion: { "$match": { "<alias>": { "$ne": [] } } }
            if (target.excludeUnmatched) {
                stages.add(Document("\$match", Document(alias, Document("\$ne", emptyList<Any>()))))
            }

            // 5) UNWIND_ALL: preserveNullAndEmptyArrays = !excludeUnmatched
            if (target.matchStrategy == JoinMatchStrategy.UNWIND_ALL) {
                stages.add(
                    Document(
                        "\$unwind",
                        Document("path", "\$$alias")
                            .append("preserveNullAndEmptyArrays", !target.excludeUnmatched)
                    )
                )
            }
        }

        // Stage 4: Deferred Result Limit (for INNER JOIN or sorted pipelines where early $limit wasn't emitted)
        if (!emitEarlyLimit && limitValue != null) {
            stages.add(Document("\$limit", limitValue))
        }

        // 5. $project stage when subset of columns is selected (ONLY `1` values; `_id: 0` ONLY if `_id` omitted)
        val cleanAllLeft = (joinSpec.allLeftFields + joinSpec.selectedLeftFields)
            .map { stripUiCollectionPrefix(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val cleanSelectedLeftSet = joinSpec.selectedLeftFields
            .map { stripUiCollectionPrefix(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val hasPartialSelection = cleanSelectedLeftSet.size < cleanAllLeft.size ||
            joinSpec.targets.any { t ->
                val allSet = (t.allColumns + t.selectedColumns)
                    .map { stripUiCollectionPrefix(it) }
                    .filter { it.isNotEmpty() }
                    .toSet()
                val selSet = t.selectedColumns
                    .map { stripUiCollectionPrefix(it) }
                    .filter { it.isNotEmpty() }
                    .toSet()
                selSet.size < allSet.size
            }
        val anySelected = cleanSelectedLeftSet.isNotEmpty() || joinSpec.targets.any { it.selectedColumns.isNotEmpty() }
        if (hasPartialSelection && anySelected) {
            val proj = Document()
            val cleanSelectedLeft = joinSpec.selectedLeftFields.map { stripUiCollectionPrefix(it) }.filter { it.isNotEmpty() }.distinct()
            if ("_id" !in cleanSelectedLeft) {
                proj["_id"] = 0
            }
            for (lf in cleanSelectedLeft) {
                proj[lf] = 1
            }
            joinSpec.targets.forEachIndexed { idx, target ->
                val alias = resolveJoinAlias(joinSpec, idx)
                for (rf in target.selectedColumns.map { stripUiCollectionPrefix(it) }.filter { it.isNotEmpty() }.distinct()) {
                    proj["$alias.$rf"] = 1
                }
            }
            if (proj.isNotEmpty()) {
                stages.add(Document("\$project", proj))
            }
        }

        return stages
    }

    /**
     * Serializes the Compass-compatible pipeline into a cleanly indented `JsonMode.RELAXED` JSON array (`[ ... ]`).
     */
    fun formatCompassPipelineJson(
        joinSpec: JoinSpec,
        filterRaw: String? = null,
        sortRaw: String? = null,
        baseFieldTypes: Map<String, String> = joinSpec.leftFieldTypes,
        targetFieldTypesList: List<Map<String, String>> = joinSpec.targets.map { it.fieldTypes }
    ): String {
        val stages = buildCompassPipelineDocuments(
            joinSpec = joinSpec,
            filterRaw = filterRaw,
            sortRaw = sortRaw,
            baseFieldTypes = baseFieldTypes,
            targetFieldTypesList = targetFieldTypesList
        )
        return stages.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { stage ->
            stage.toJson(RELAXED_PRETTY)
                .lines()
                .joinToString("\n") { "  $it" }
        }
    }

    private fun mergeMatchClauses(clauses: List<Document>): Document? {
        val nonEmpty = clauses.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null
        if (nonEmpty.size == 1) return nonEmpty[0]

        val merged = Document()
        val overflow = mutableListOf<Document>()
        for (doc in nonEmpty) {
            if (doc.size == 1) {
                val (k, v) = doc.entries.first()
                if (k != "\$and" && k != "\$or") {
                    if (!merged.containsKey(k)) {
                        merged[k] = v
                    } else {
                        val prev = merged.remove(k)
                        if (prev != null) overflow.add(Document(k, prev))
                        overflow.add(doc)
                    }
                    continue
                }
            }
            overflow.add(doc)
        }
        if (overflow.isEmpty()) return merged
        if (merged.isEmpty() && overflow.size == 1) return overflow[0]
        val all = mutableListOf<Document>()
        for ((k, v) in merged) all.add(Document(k, v))
        all.addAll(overflow)
        return Document("\$and", all)
    }

    /**
     * Converts a target condition into an Aggregation Expression document for `$filter.cond` (`$$item.<field>`).
     * Supports all `JoinConditionOperator` values (`=`, `!=`, `>`, `>=`, `<`, `<=`, `IN`, `IS_NOT_EMPTY`, `IS_EMPTY`).
     */
    fun buildFilterItemExpression(
        fieldPath: String,
        operator: JoinConditionOperator,
        rawValue: String,
        schemaType: String?
    ): Document {
        val itemRef = "\$\$item.$fieldPath"
        return when (operator) {
            JoinConditionOperator.EQ ->
                Document("\$eq", listOf(itemRef, wrapAggLiteral(convertValueForSchemaType(fieldPath, rawValue, schemaType))))
            JoinConditionOperator.NE ->
                Document("\$ne", listOf(itemRef, wrapAggLiteral(convertValueForSchemaType(fieldPath, rawValue, schemaType))))
            JoinConditionOperator.GT ->
                Document("\$gt", listOf(itemRef, wrapAggLiteral(convertValueForSchemaType(fieldPath, rawValue, schemaType))))
            JoinConditionOperator.GTE ->
                Document("\$gte", listOf(itemRef, wrapAggLiteral(convertValueForSchemaType(fieldPath, rawValue, schemaType))))
            JoinConditionOperator.LT ->
                Document("\$lt", listOf(itemRef, wrapAggLiteral(convertValueForSchemaType(fieldPath, rawValue, schemaType))))
            JoinConditionOperator.LTE ->
                Document("\$lte", listOf(itemRef, wrapAggLiteral(convertValueForSchemaType(fieldPath, rawValue, schemaType))))
            JoinConditionOperator.IN -> {
                val values = splitCommaValues(rawValue).map { wrapAggLiteral(convertValueForSchemaType(fieldPath, it, schemaType)) }
                Document("\$in", listOf(itemRef, values))
            }
            JoinConditionOperator.IS_NOT_EMPTY -> Document(
                "\$and",
                listOf(
                    Document("\$ne", listOf(Document("\$ifNull", listOf(itemRef, null)), null)),
                    Document("\$ne", listOf(itemRef, ""))
                )
            )
            JoinConditionOperator.IS_EMPTY -> Document(
                "\$or",
                listOf(
                    Document("\$eq", listOf(Document("\$ifNull", listOf(itemRef, null)), null)),
                    Document("\$eq", listOf(itemRef, ""))
                )
            )
        }
    }

    /**
     * Converts symbolic filter operators (`=`, `!=`, `>`, `>=`, `<`, `<=`, `in`, `contains`, `exists`)
     * into pure Aggregation Expression documents for `$filter.cond` (`$$item.<field>`).
     */
    fun buildFilterExpressionBySymbol(
        fieldPath: String,
        operatorSymbol: String,
        rawValue: String,
        schemaType: String? = null
    ): Document {
        val cleanField = stripUiCollectionPrefix(fieldPath)
        val itemRef = "\$\$item.$cleanField"
        return when (operatorSymbol.trim().lowercase()) {
            "=", "eq" -> buildFilterItemExpression(cleanField, JoinConditionOperator.EQ, rawValue, schemaType)
            "!=", "ne" -> buildFilterItemExpression(cleanField, JoinConditionOperator.NE, rawValue, schemaType)
            ">", "gt" -> buildFilterItemExpression(cleanField, JoinConditionOperator.GT, rawValue, schemaType)
            ">=", "gte" -> buildFilterItemExpression(cleanField, JoinConditionOperator.GTE, rawValue, schemaType)
            "<", "lt" -> buildFilterItemExpression(cleanField, JoinConditionOperator.LT, rawValue, schemaType)
            "<=", "lte" -> buildFilterItemExpression(cleanField, JoinConditionOperator.LTE, rawValue, schemaType)
            "in" -> buildFilterItemExpression(cleanField, JoinConditionOperator.IN, rawValue, schemaType)
            "contains" -> Document(
                "\$regexMatch",
                Document("input", Document("\$toString", itemRef))
                    .append("regex", Regex.escape(rawValue))
                    .append("options", "i")
            )
            "exists" -> Document("\$ne", listOf(Document("\$ifNull", listOf(itemRef, null)), null))
            else -> buildFilterItemExpression(cleanField, JoinConditionOperator.EQ, rawValue, schemaType)
        }
    }

    private fun wrapAggLiteral(value: Any?): Any? {
        return if (value is String && value.startsWith("$")) {
            Document("\$literal", value)
        } else {
            value
        }
    }

    private fun buildConditionMatchDoc(
        fieldPath: String,
        operator: JoinConditionOperator,
        rawValue: String,
        schemaType: String?
    ): Document {
        return when (operator) {
            JoinConditionOperator.EQ -> Document(fieldPath, convertValueForSchemaType(fieldPath, rawValue, schemaType))
            JoinConditionOperator.NE -> Document(fieldPath, Document("\$ne", convertValueForSchemaType(fieldPath, rawValue, schemaType)))
            JoinConditionOperator.GT -> Document(fieldPath, Document("\$gt", convertValueForSchemaType(fieldPath, rawValue, schemaType)))
            JoinConditionOperator.GTE -> Document(fieldPath, Document("\$gte", convertValueForSchemaType(fieldPath, rawValue, schemaType)))
            JoinConditionOperator.LT -> Document(fieldPath, Document("\$lt", convertValueForSchemaType(fieldPath, rawValue, schemaType)))
            JoinConditionOperator.LTE -> Document(fieldPath, Document("\$lte", convertValueForSchemaType(fieldPath, rawValue, schemaType)))
            JoinConditionOperator.IN -> {
                val values = splitCommaValues(rawValue).map { convertValueForSchemaType(fieldPath, it, schemaType) }
                Document(fieldPath, Document("\$in", values))
            }
            JoinConditionOperator.IS_NOT_EMPTY -> Document(
                fieldPath,
                Document("\$exists", true).append("\$nin", listOf(null, ""))
            )
            JoinConditionOperator.IS_EMPTY -> Document(
                "\$or",
                listOf(
                    Document(fieldPath, Document("\$exists", false)),
                    Document(fieldPath, Document("\$in", listOf(null, "")))
                )
            )
        }
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
