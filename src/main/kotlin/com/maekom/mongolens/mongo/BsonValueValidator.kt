package com.maekom.mongolens.mongo

import com.maekom.mongolens.i18n.I18n
import org.bson.BsonArray
import org.bson.BsonBinary
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.Document
import org.bson.json.JsonWriterSettings
import org.bson.types.Binary
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Target BSON data types selectable in the single-field "Edit Data" popup.
 */
enum class EditableBsonType(val label: String) {
    INT32("Int32"),
    INT64("Int64"),
    DOUBLE("Double"),
    DECIMAL128("Decimal128"),
    STRING("String"),
    BOOLEAN("Boolean"),
    OBJECT_ID("ObjectId"),
    DATE("Date"),
    NULL("Null"),
    JSON_OBJECT_ARRAY("JSON (Object/Array)");

    override fun toString(): String = label

    companion object {
        fun fromBsonValue(value: BsonValue?): EditableBsonType = when (value) {
            is BsonInt32 -> INT32
            is BsonInt64 -> INT64
            is BsonDouble -> DOUBLE
            is BsonDecimal128 -> DECIMAL128
            is BsonString -> STRING
            is BsonBoolean -> BOOLEAN
            is BsonObjectId -> OBJECT_ID
            is BsonDateTime -> DATE
            is BsonNull, null -> NULL
            is BsonDocument, is BsonArray -> JSON_OBJECT_ARRAY
            else -> STRING
        }
    }
}

sealed class BsonFieldValidationResult {
    data class Valid(
        val bsonValue: BsonValue,
        val nativeValue: Any?,
        val previewText: String? = null
    ) : BsonFieldValidationResult()

    data class Invalid(val errorMessage: String) : BsonFieldValidationResult()
}

/**
 * Centralized BSON type detection, strict real-time validation, flexible UTC date parsing,
 * MongoDB dot-notation path normalization, and lossless BsonValue <-> native Document codec conversion.
 */
object BsonValueValidator {
    private val OBJECT_ID_REGEX = Regex("^[0-9a-fA-F]{24}$")
    private val EPOCH_MILLIS_13_REGEX = Regex("^-?\\d{13}$")
    private val PRETTY_JSON_SETTINGS: JsonWriterSettings = JsonWriterSettings.builder().indent(true).build()
    private val SPACE_DATETIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val SPACE_DATETIME_MS_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * Converts tree-view array paths such as `items.[0].price` into standard MongoDB update dot-notation `items.0.price`.
     */
    fun normalizeMongoUpdatePath(rawPath: String): String {
        return rawPath.trim()
            .replace(Regex("\\.\\[(\\d+)]"), ".$1")
            .replace(Regex("^\\[(\\d+)]"), "$1")
    }

    /**
     * Produces the initial editor text for an existing BsonValue preserving exact type formatting.
     */
    fun initialTextFor(value: BsonValue?): String = when (value) {
        null, is BsonNull -> "null"
        is BsonInt32 -> value.value.toString()
        is BsonInt64 -> value.value.toString()
        is BsonDouble -> value.value.toString()
        is BsonDecimal128 -> value.value.toString()
        is BsonString -> value.value
        is BsonBoolean -> value.value.toString()
        is BsonObjectId -> value.value.toHexString()
        is BsonDateTime -> Instant.ofEpochMilli(value.value).toString()
        is BsonDocument -> runCatching {
            Document.parse(value.toJson()).toJson(PRETTY_JSON_SETTINGS)
        }.getOrElse { value.toJson() }
        is BsonArray -> runCatching {
            val wrapped = BsonDocument("v", value)
            val pretty = Document.parse(wrapped.toJson()).toJson(PRETTY_JSON_SETTINGS)
            val startIdx = pretty.indexOf('[')
            val endIdx = pretty.lastIndexOf(']')
            if (startIdx >= 0 && endIdx > startIdx) pretty.substring(startIdx, endIdx + 1) else value.toString()
        }.getOrElse { value.toString() }
        else -> value.toString()
    }

    /**
     * Parses ISO-8601 (`Z`, `+09:00`, etc.), `yyyy-MM-dd HH:mm:ss` (interpreted as UTC),
     * or 13-digit Epoch milliseconds into a UTC `Instant`.
     */
    fun parseFlexibleInstant(raw: String): Instant? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (EPOCH_MILLIS_13_REGEX.matches(s)) {
            return s.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
        }
        runCatching { return Instant.parse(s) }
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return ZonedDateTime.parse(s).toInstant() }
        runCatching { return LocalDateTime.parse(s, SPACE_DATETIME_MS_FORMATTER).toInstant(ZoneOffset.UTC) }
        runCatching { return LocalDateTime.parse(s, SPACE_DATETIME_FORMATTER).toInstant(ZoneOffset.UTC) }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
        return null
    }

    /**
     * Validates user input strictly against the chosen BSON type and returns typed BSON + native Java values.
     */
    fun validateAndParse(type: EditableBsonType, rawInput: String): BsonFieldValidationResult {
        return when (type) {
            EditableBsonType.INT32 -> validateInt32(rawInput)
            EditableBsonType.INT64 -> validateInt64(rawInput)
            EditableBsonType.DOUBLE -> validateDouble(rawInput)
            EditableBsonType.DECIMAL128 -> validateDecimal128(rawInput)
            EditableBsonType.STRING -> BsonFieldValidationResult.Valid(
                bsonValue = BsonString(rawInput),
                nativeValue = rawInput
            )
            EditableBsonType.BOOLEAN -> validateBoolean(rawInput)
            EditableBsonType.OBJECT_ID -> validateObjectId(rawInput)
            EditableBsonType.DATE -> validateDate(rawInput)
            EditableBsonType.NULL -> BsonFieldValidationResult.Valid(
                bsonValue = BsonNull.VALUE,
                nativeValue = null
            )
            EditableBsonType.JSON_OBJECT_ARRAY -> validateJsonObjectOrArray(rawInput)
        }
    }

    private fun validateInt32(rawInput: String): BsonFieldValidationResult {
        val cleaned = rawInput.trim().replace(",", "")
        if (cleaned.isEmpty()) {
            return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.int32Invalid"))
        }
        val intVal = cleaned.toIntOrNull()
        if (intVal != null) {
            return BsonFieldValidationResult.Valid(BsonInt32(intVal), intVal)
        }
        if (cleaned.toLongOrNull() != null) {
            return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.int32Overflow"))
        }
        if (cleaned.contains('.')) {
            return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.int32Decimal"))
        }
        return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.int32Invalid"))
    }

    private fun validateInt64(rawInput: String): BsonFieldValidationResult {
        val cleaned = rawInput.trim().replace(",", "")
        if (cleaned.isEmpty()) {
            return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.int64Invalid"))
        }
        val longVal = cleaned.toLongOrNull()
        if (longVal != null) {
            return BsonFieldValidationResult.Valid(BsonInt64(longVal), longVal)
        }
        return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.int64Invalid"))
    }

    private fun validateDouble(rawInput: String): BsonFieldValidationResult {
        val cleaned = rawInput.trim().replace(",", "")
        if (cleaned.isEmpty() || isNonFiniteLiteral(cleaned)) {
            return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.doubleInvalid"))
        }
        return try {
            BigDecimal(cleaned)
            val d = cleaned.toDoubleOrNull()
            if (d == null || !d.isFinite()) {
                BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.doubleInvalid"))
            } else {
                BsonFieldValidationResult.Valid(BsonDouble(d), d)
            }
        } catch (_: Throwable) {
            BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.doubleInvalid"))
        }
    }

    private fun validateDecimal128(rawInput: String): BsonFieldValidationResult {
        val cleaned = rawInput.trim().replace(",", "")
        if (cleaned.isEmpty() || isNonFiniteLiteral(cleaned)) {
            return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.decimalInvalid"))
        }
        return try {
            val bd = BigDecimal(cleaned)
            val dec = Decimal128(bd)
            BsonFieldValidationResult.Valid(BsonDecimal128(dec), dec)
        } catch (_: Throwable) {
            BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.decimalInvalid"))
        }
    }

    private fun validateBoolean(rawInput: String): BsonFieldValidationResult {
        return when (rawInput.trim().lowercase()) {
            "true" -> BsonFieldValidationResult.Valid(BsonBoolean.TRUE, true)
            "false" -> BsonFieldValidationResult.Valid(BsonBoolean.FALSE, false)
            else -> BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.booleanInvalid"))
        }
    }

    private fun validateObjectId(rawInput: String): BsonFieldValidationResult {
        val trimmed = rawInput.trim()
        if (!OBJECT_ID_REGEX.matches(trimmed)) {
            return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.objectIdInvalid"))
        }
        val oid = ObjectId(trimmed)
        return BsonFieldValidationResult.Valid(BsonObjectId(oid), oid)
    }

    private fun validateDate(rawInput: String): BsonFieldValidationResult {
        val instant = parseFlexibleInstant(rawInput)
            ?: return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.dateInvalid"))
        val millis = instant.toEpochMilli()
        return BsonFieldValidationResult.Valid(
            bsonValue = BsonDateTime(millis),
            nativeValue = Date.from(instant),
            previewText = instant.toString()
        )
    }

    private fun validateJsonObjectOrArray(rawInput: String): BsonFieldValidationResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return BsonFieldValidationResult.Invalid(I18n.t("fieldEdit.error.jsonInvalid", "Empty JSON"))
        }
        return try {
            when {
                trimmed.startsWith("{") -> {
                    val nativeDoc = Document.parse(trimmed)
                    val bsonDoc = BsonDocument.parse(trimmed)
                    BsonFieldValidationResult.Valid(bsonDoc, nativeDoc)
                }
                trimmed.startsWith("[") -> {
                    val bsonArr = BsonArray.parse(trimmed)
                    val wrapper = Document.parse("{\"items\": $trimmed}")
                    val nativeList = wrapper["items"]
                    BsonFieldValidationResult.Valid(bsonArr, nativeList)
                }
                else -> BsonFieldValidationResult.Invalid(
                    I18n.t("fieldEdit.error.jsonInvalid", "Must start with '{' or '['")
                )
            }
        } catch (t: Throwable) {
            BsonFieldValidationResult.Invalid(
                I18n.t("fieldEdit.error.jsonInvalid", t.message ?: t.javaClass.simpleName)
            )
        }
    }

    private fun isNonFiniteLiteral(s: String): Boolean {
        val lower = s.lowercase()
        return lower == "nan" || lower == "+nan" || lower == "-nan" ||
            lower == "infinity" || lower == "+infinity" || lower == "-infinity" ||
            lower == "inf" || lower == "+inf" || lower == "-inf"
    }

    /**
     * Converts any `BsonValue` (including original `_id` of any BSON type) into its exact native Java/BSON
     * representation suitable for `MongoCollection<Document>` filters and `$set` updates.
     */
    fun bsonToNativeValue(value: BsonValue): Any? = when (value) {
        is BsonNull -> null
        is BsonString -> value.value
        is BsonInt32 -> value.value
        is BsonInt64 -> value.value
        is BsonDouble -> value.value
        is BsonDecimal128 -> value.value
        is BsonBoolean -> value.value
        is BsonObjectId -> value.value
        is BsonDateTime -> Date(value.value)
        is BsonBinary -> Binary(value.type, value.data)
        is BsonDocument -> {
            val doc = Document()
            for ((k, v) in value) {
                doc[k] = bsonToNativeValue(v)
            }
            doc
        }
        is BsonArray -> value.map { bsonToNativeValue(it) }
        else -> value
    }

    /**
     * Resolves a dot-notation path (supporting array index segments like `items.0.price` or `items.[0].price`)
     * within a `BsonDocument`.
     */
    fun extractValueAtPath(doc: BsonDocument, rawPath: String): BsonValue? {
        val normalized = normalizeMongoUpdatePath(rawPath)
        if (normalized.isEmpty()) return null
        val segments = normalized.split('.').filter { it.isNotEmpty() }
        var cur: BsonValue = doc
        for (seg in segments) {
            cur = when (cur) {
                is BsonDocument -> cur[seg] ?: return null
                is BsonArray -> {
                    val idx = seg.toIntOrNull() ?: return null
                    if (idx in 0 until cur.size) cur[idx] else return null
                }
                else -> return null
            }
        }
        return cur
    }
}
