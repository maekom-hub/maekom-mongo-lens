package com.maekom.mongolens.mongo

import com.maekom.mongolens.ui.UiHelpers
import org.bson.BsonArray
import org.bson.BsonBinary
import org.bson.BsonBinarySubType
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonObjectId
import org.bson.BsonRegularExpression
import org.bson.BsonString
import org.bson.BsonTimestamp
import org.bson.BsonValue
import java.util.Locale

object QuickFilterHelper {
    private const val MIN_EPOCH_MILLIS_13_DIGIT = 1_000_000_000_000L // 2001-09-09
    private const val MAX_EPOCH_MILLIS_13_DIGIT = 4_102_444_800_000L // 2100-01-01
    private val arrayIndexSegmentRegex = Regex("^\\[\\d+]$")

    data class SelectedFieldItem(
        val fieldPath: String,
        val bsonValue: BsonValue
    )

    sealed class SmartSelectionKind {
        data class SingleScalar(val fieldPath: String, val value: BsonValue) : SmartSelectionKind()
        data class SameFieldMultiValues(val fieldPath: String, val values: List<BsonValue>) : SmartSelectionKind()
        data class SameFieldDatePair(val fieldPath: String, val minValue: BsonValue, val maxValue: BsonValue) : SmartSelectionKind()
        data class MultiFieldConditions(val items: List<SelectedFieldItem>) : SmartSelectionKind()
        data class SingleStruct(val fieldPath: String, val isArray: Boolean) : SmartSelectionKind()
    }

    /**
     * Converts Tree-view paths like `items.[0].product_id` or `tags.[2]` into valid MongoDB
     * dot-notation query paths (`items.product_id`, `tags`).
     */
    fun normalizeFilterFieldPath(rawPath: String): String {
        val cleaned = rawPath.trim()
        if (cleaned.isEmpty()) return ""
        val parts = cleaned.split('.').filter { it.isNotBlank() && !arrayIndexSegmentRegex.matches(it) }
        return parts.joinToString(".")
    }

    fun isDateLikeValue(value: BsonValue): Boolean {
        return extractEpochMillis(value) != null
    }

    private fun extractEpochMillis(value: BsonValue): Long? = when (value) {
        is BsonDateTime -> value.value
        is BsonInt64 -> value.value.takeIf { it in MIN_EPOCH_MILLIS_13_DIGIT..MAX_EPOCH_MILLIS_13_DIGIT }
        is BsonDouble -> {
            val l = value.value.toLong()
            if (value.value == l.toDouble() && l in MIN_EPOCH_MILLIS_13_DIGIT..MAX_EPOCH_MILLIS_13_DIGIT) l else null
        }
        else -> null
    }

    fun isStructValue(value: BsonValue): Boolean = value is BsonDocument || value is BsonArray

    fun classifySelection(rawItems: List<SelectedFieldItem>): SmartSelectionKind? {
        val normalized = rawItems
            .map { SelectedFieldItem(normalizeFilterFieldPath(it.fieldPath), it.bsonValue) }
            .filter { it.fieldPath.isNotEmpty() }
        if (normalized.isEmpty()) return null

        // Deduplicate exact (fieldPath, bsonValue) pairs while keeping order
        val distinctItems = linkedMapOf<Pair<String, BsonValue>, SelectedFieldItem>()
        for (item in normalized) {
            distinctItems.putIfAbsent(item.fieldPath to item.bsonValue, item)
        }
        val items = distinctItems.values.toList()

        if (items.size == 1) {
            val single = items[0]
            return if (isStructValue(single.bsonValue)) {
                SmartSelectionKind.SingleStruct(single.fieldPath, single.bsonValue is BsonArray)
            } else {
                SmartSelectionKind.SingleScalar(single.fieldPath, single.bsonValue)
            }
        }

        val distinctFields = items.map { it.fieldPath }.distinct()
        if (distinctFields.size == 1) {
            val field = distinctFields[0]
            val scalars = items.map { it.bsonValue }.filterNot { isStructValue(it) }.distinct()
            if (scalars.isEmpty()) {
                return SmartSelectionKind.SingleStruct(field, items[0].bsonValue is BsonArray)
            }
            if (scalars.size == 1) {
                return SmartSelectionKind.SingleScalar(field, scalars[0])
            }
            if (scalars.size == 2) {
                val t0 = extractEpochMillis(scalars[0])
                val t1 = extractEpochMillis(scalars[1])
                if (t0 != null && t1 != null && scalars[0].bsonType == scalars[1].bsonType) {
                    val (minVal, maxVal) = if (t0 <= t1) scalars[0] to scalars[1] else scalars[1] to scalars[0]
                    return SmartSelectionKind.SameFieldDatePair(field, minVal, maxVal)
                }
            }
            return SmartSelectionKind.SameFieldMultiValues(field, scalars)
        }

        val scalarItems = items.filterNot { isStructValue(it.bsonValue) }
        if (scalarItems.isEmpty()) {
            return SmartSelectionKind.SingleStruct(items[0].fieldPath, items[0].bsonValue is BsonArray)
        }
        if (scalarItems.size == 1) {
            return SmartSelectionKind.SingleScalar(scalarItems[0].fieldPath, scalarItems[0].bsonValue)
        }
        return SmartSelectionKind.MultiFieldConditions(scalarItems)
    }

    fun buildEqualsClause(fieldPath: String, value: BsonValue): BsonDocument =
        BsonDocument(fieldPath, value)

    fun buildNotEqualsClause(fieldPath: String, value: BsonValue): BsonDocument =
        BsonDocument(fieldPath, BsonDocument("\$ne", value))

    fun buildInClause(fieldPath: String, values: List<BsonValue>): BsonDocument =
        BsonDocument(fieldPath, BsonDocument("\$in", BsonArray(values)))

    fun buildNinClause(fieldPath: String, values: List<BsonValue>): BsonDocument =
        BsonDocument(fieldPath, BsonDocument("\$nin", BsonArray(values)))

    fun buildDateRangeClause(fieldPath: String, minVal: BsonValue, maxVal: BsonValue): BsonDocument =
        BsonDocument(fieldPath, BsonDocument("\$gte", minVal).append("\$lte", maxVal))

    fun buildExistsTrueClause(fieldPath: String): BsonDocument =
        BsonDocument(fieldPath, BsonDocument("\$exists", BsonBoolean.TRUE))

    fun buildEmptyOrMissingClause(fieldPath: String): BsonDocument =
        BsonDocument(
            "\$or",
            BsonArray(
                listOf(
                    BsonDocument(fieldPath, BsonDocument("\$exists", BsonBoolean.FALSE)),
                    BsonDocument(
                        fieldPath,
                        BsonDocument(
                            "\$in",
                            BsonArray(listOf(BsonNull.VALUE, BsonString(""), BsonArray(), BsonDocument()))
                        )
                    )
                )
            )
        )

    fun buildMultiFieldAndClause(items: List<SelectedFieldItem>): BsonDocument {
        val grouped = linkedMapOf<String, MutableList<BsonValue>>()
        for (item in items) {
            grouped.computeIfAbsent(item.fieldPath) { mutableListOf() }.add(item.bsonValue)
        }
        val doc = BsonDocument()
        for ((field, vals) in grouped) {
            val distinct = vals.distinct()
            doc[field] = if (distinct.size == 1) {
                distinct[0]
            } else {
                BsonDocument("\$in", BsonArray(distinct))
            }
        }
        return doc
    }

    fun buildMultiFieldOrClause(items: List<SelectedFieldItem>): BsonDocument {
        val branches = items.map { BsonDocument(it.fieldPath, it.bsonValue) }
        return BsonDocument("\$or", BsonArray(branches))
    }

    fun buildSortJson(fieldPath: String, ascending: Boolean): String {
        val clean = normalizeFilterFieldPath(fieldPath)
        val escaped = MongoShellNormalizer.escapeJsonString(clean)
        val dir = if (ascending) 1 else -1
        return """{ "$escaped": $dir }"""
    }

    /**
     * Merges `patchClause` into `existingFilterRaw` safely:
     * - Preserves unrelated existing filter keys.
     * - Upserts (replaces) matching field keys.
     * - If both `existing` and `patchClause` contain `$or`, wraps both under `$and` so existing `$or` is NEVER lost!
     */
    fun mergeIntoExistingFilter(existingFilterRaw: String?, patchClause: BsonDocument): String {
        val baseDoc = try {
            val trimmed = existingFilterRaw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed == "{}") {
                BsonDocument()
            } else {
                MongoShellNormalizer.parseBsonDocument(trimmed)
            }
        } catch (_: Throwable) {
            BsonDocument()
        }

        val merged = BsonDocument()
        for ((k, v) in baseDoc) {
            merged[k] = v
        }

        for ((k, v) in patchClause) {
            if (k == "\$or" && merged.containsKey("\$or")) {
                val existingOr = merged.remove("\$or")!!
                val existingOrDoc = BsonDocument("\$or", existingOr)
                val newOrDoc = BsonDocument("\$or", v)
                val currentAnd = merged["\$and"]
                if (currentAnd is BsonArray) {
                    val combined = BsonArray(currentAnd.values.toList())
                    combined.add(existingOrDoc)
                    combined.add(newOrDoc)
                    merged["\$and"] = combined
                } else {
                    merged["\$and"] = BsonArray(listOf(existingOrDoc, newOrDoc))
                }
            } else {
                merged[k] = v
            }
        }

        return formatShellFriendlyDocument(merged)
    }

    fun formatKeyValueJsonSnippet(key: String, value: BsonValue, decryptedPlaintext: String? = null): String {
        val escapedKey = MongoShellNormalizer.escapeJsonString(key)
        val formattedVal = if (decryptedPlaintext != null) {
            "\"${MongoShellNormalizer.escapeJsonString(decryptedPlaintext)}\""
        } else {
            formatShellFriendlyValue(value)
        }
        return "\"$escapedKey\": $formattedVal"
    }

    fun formatShellFriendlyDocument(doc: BsonDocument): String {
        val compact = formatShellDocInline(doc)
        return if (compact.length <= 110) compact else formatShellDocPretty(doc, 0)
    }

    fun formatShellFriendlyValue(value: BsonValue): String = when (value) {
        is BsonObjectId -> "ObjectId(\"${value.value.toHexString()}\")"
        is BsonDateTime -> "ISODate(\"${UiHelpers.formatStrictIsoUtcMillis(value.value)}\")"
        is BsonString -> "\"${MongoShellNormalizer.escapeJsonString(value.value)}\""
        is BsonInt32 -> value.value.toString()
        is BsonInt64 -> value.value.toString()
        is BsonDouble -> value.value.toString()
        is BsonDecimal128 -> "NumberDecimal(\"${value.value}\")"
        is BsonBoolean -> value.value.toString()
        is BsonNull -> "null"
        is BsonRegularExpression -> """{ "${'$'}regex": "${MongoShellNormalizer.escapeJsonString(value.pattern)}", "${'$'}options": "${MongoShellNormalizer.escapeJsonString(value.options)}" }"""
        is BsonTimestamp -> """{ "${'$'}timestamp": { "t": ${value.time}, "i": ${value.inc} } }"""
        is BsonBinary -> {
            val sub = value.type
            if ((sub == BsonBinarySubType.UUID_STANDARD.value || sub == BsonBinarySubType.UUID_LEGACY.value) &&
                value.data.size == 16
            ) {
                "UUID(\"${value.asUuid()}\")"
            } else {
                BsonDocument("v", value).toJson().let { j ->
                    val idx = j.indexOf(':')
                    if (idx > 0) j.substring(idx + 1, j.lastIndexOf('}')).trim() else "\"${value}\""
                }
            }
        }
        is BsonDocument -> formatShellDocInline(value)
        is BsonArray -> "[${value.joinToString(", ") { formatShellFriendlyValue(it) }}]"
        else -> "\"${MongoShellNormalizer.escapeJsonString(value.toString())}\""
    }

    private fun formatShellDocInline(doc: BsonDocument): String {
        if (doc.isEmpty()) return "{}"
        val body = doc.entries.joinToString(", ") { (k, v) ->
            "\"${MongoShellNormalizer.escapeJsonString(k)}\": ${formatShellFriendlyValue(v)}"
        }
        return "{ $body }"
    }

    private fun formatShellDocPretty(doc: BsonDocument, indentLevel: Int): String {
        if (doc.isEmpty()) return "{}"
        val pad = "  ".repeat(indentLevel)
        val innerPad = "  ".repeat(indentLevel + 1)
        val lines = doc.entries.map { (k, v) ->
            val escapedK = MongoShellNormalizer.escapeJsonString(k)
            val valStr = when (v) {
                is BsonDocument -> {
                    val inline = formatShellDocInline(v)
                    if (inline.length <= 72) inline else formatShellDocPretty(v, indentLevel + 1)
                }
                is BsonArray -> {
                    val inline = "[${v.joinToString(", ") { formatShellFriendlyValue(it) }}]"
                    if (inline.length <= 72) {
                        inline
                    } else {
                        val arrInnerPad = "  ".repeat(indentLevel + 2)
                        val elemLines = v.map { elem ->
                            val rendered = if (elem is BsonDocument) {
                                val eInline = formatShellDocInline(elem)
                                if (eInline.length <= 68) eInline else formatShellDocPretty(elem, indentLevel + 2)
                            } else {
                                formatShellFriendlyValue(elem)
                            }
                            "$arrInnerPad$rendered"
                        }
                        "[\n${elemLines.joinToString(",\n")}\n$innerPad]"
                    }
                }
                else -> formatShellFriendlyValue(v)
            }
            "$innerPad\"$escapedK\": $valStr"
        }
        return "{\n${lines.joinToString(",\n")}\n$pad}"
    }
}
