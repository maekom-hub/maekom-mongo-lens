package com.maekom.mongolens.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
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
import org.bson.BsonRegularExpression
import org.bson.BsonString
import org.bson.BsonTimestamp
import org.bson.BsonValue
import java.awt.Color
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JLabel

object UiHelpers {
    private val kstZone: ZoneId = ZoneId.of("Asia/Seoul")
    private val utcZone: ZoneId = ZoneId.of("UTC")
    private val kstFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'KST'")
    private val kstDateTimeSecFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val utcTimeSecFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val kstMillisNoSuffixFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val utcMillisTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val utcIsoMillisFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    private val numberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US)

    val COLOR_GREEN_BG = JBColor(Color(0xE6F7ED), Color(0x1B3B2B))
    val COLOR_GREEN_FG = JBColor(Color(0x0D7A3E), Color(0x52D98B))
    val COLOR_ORANGE_BG = JBColor(Color(0xFFF3E0), Color(0x4A2E14))
    val COLOR_ORANGE_FG = JBColor(Color(0xB85C00), Color(0xFFB74D))
    val COLOR_RED_BG = JBColor(Color(0xFDECEC), Color(0x4A1D21))
    val COLOR_RED_FG = JBColor(Color(0xC62828), Color(0xFF7B86))
    val COLOR_BLUE_BG = JBColor(Color(0xE8F1FD), Color(0x1D334D))
    val COLOR_BLUE_FG = JBColor(Color(0x1565C0), Color(0x7AB8FF))
    val COLOR_GRAY_BG = JBColor(Color(0xECEFF1), Color(0x32363A))
    val COLOR_GRAY_FG = JBColor(Color(0x546E7A), Color(0xB0BEC5))

    // BSON Type Colors
    val BSON_OID_COLOR = JBColor(Color(0x8E44AD), Color(0xC39BD3))
    val BSON_DATE_COLOR = JBColor(Color(0x00796B), Color(0x4DB6AC))
    val BSON_NUM_COLOR = JBColor(Color(0x1565C0), Color(0x64B5F6))
    val BSON_STR_COLOR = JBColor(Color(0x2E7D32), Color(0x81C784))
    val BSON_BOOL_COLOR = JBColor(Color(0xD84315), Color(0xFF8A65))
    val BSON_NULL_COLOR = JBColor(Color(0x757575), Color(0x9E9E9E))
    val BSON_STRUCT_COLOR = JBColor(Color(0x546E7A), Color(0xB0BEC5))

    fun formatNumber(value: Long): String = numberFormat.format(value)

    fun formatIsoUtc(epochMillis: Long): String = isoFormatter.format(Instant.ofEpochMilli(epochMillis))

    fun formatStrictIsoUtcMillis(epochMillis: Long): String =
        utcIsoMillisFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(utcZone))

    fun formatKst(epochMillis: Long): String = kstFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(kstZone))

    /** Tree inline auxiliary text for ObjectId: `2026-11-26 10:30:15 KST (UTC 01:30:15)` */
    fun formatObjectIdInlineTime(oid: org.bson.types.ObjectId): String {
        val epochSec = oid.timestamp.toLong() and 0xFFFFFFFFL
        val inst = Instant.ofEpochSecond(epochSec)
        val kstStr = kstDateTimeSecFormatter.format(inst.atZone(kstZone))
        val utcTimeStr = utcTimeSecFormatter.format(inst.atZone(utcZone))
        return "$kstStr KST (UTC $utcTimeStr)"
    }

    /** Table/Tree tooltip for ObjectId: `생성 시각: 2026-11-26 10:30:15 KST (UTC: 2026-11-26 01:30:15)` */
    fun formatObjectIdTooltip(oid: org.bson.types.ObjectId): String {
        val epochSec = oid.timestamp.toLong() and 0xFFFFFFFFL
        val inst = Instant.ofEpochSecond(epochSec)
        val kstStr = kstDateTimeSecFormatter.format(inst.atZone(kstZone))
        val utcStr = kstDateTimeSecFormatter.format(inst.atZone(utcZone))
        return I18n.t("result.oid.tooltip", kstStr, utcStr)
    }

    /** Tree inline auxiliary text for BsonDateTime: `2026-11-26 10:30:15.123 KST (UTC 01:30:15.123)` */
    fun formatDateInlineTime(epochMillis: Long): String {
        val inst = Instant.ofEpochMilli(epochMillis)
        val kstStr = kstMillisNoSuffixFormatter.format(inst.atZone(kstZone))
        val utcTimeStr = utcMillisTimeFormatter.format(inst.atZone(utcZone))
        return "$kstStr KST (UTC $utcTimeStr)"
    }

    /** Table/Tree tooltip for BsonDateTime showing both KST and UTC */
    fun formatDateDualTooltip(epochMillis: Long): String {
        val inst = Instant.ofEpochMilli(epochMillis)
        val kstStr = kstMillisNoSuffixFormatter.format(inst.atZone(kstZone))
        val utcIso = utcIsoMillisFormatter.format(inst.atZone(utcZone))
        return I18n.t("result.date.dualTooltip", kstStr, utcIso)
    }

    /**
     * Builds a pure UTC-epoch-based ObjectId range filter without any KST offset arithmetic:
     * - `$gte`: `<8-hex-sec>0000000000000000`
     * - `$lte`: `<8-hex-sec>ffffffffffffffff`
     */
    fun buildObjectIdTimeRangeFilter(fieldPath: String, oid: org.bson.types.ObjectId, isGte: Boolean): String {
        val epochSec = oid.timestamp.toLong() and 0xFFFFFFFFL
        val hex8 = java.lang.Long.toHexString(epochSec).padStart(8, '0').lowercase(Locale.ROOT)
        val suffix = if (isGte) "0000000000000000" else "ffffffffffffffff"
        val op = if (isGte) "\$gte" else "\$lte"
        val escapedField = com.maekom.mongolens.mongo.MongoShellNormalizer.escapeJsonString(fieldPath)
        return """{ "$escapedField": { "$op": ObjectId("$hex8$suffix") } }"""
    }

    /** Builds a pure UTC ISO-8601 ISODate range filter (`$gte` / `$lte`) */
    fun buildDateTimeRangeFilter(fieldPath: String, epochMillis: Long, isGte: Boolean): String {
        val utcIso = formatStrictIsoUtcMillis(epochMillis)
        val op = if (isGte) "\$gte" else "\$lte"
        val escapedField = com.maekom.mongolens.mongo.MongoShellNormalizer.escapeJsonString(fieldPath)
        return """{ "$escapedField": { "$op": ISODate("$utcIso") } }"""
    }

    fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    fun readClipboardText(): String? {
        return try {
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        } catch (_: Throwable) {
            null
        }
    }

    fun notify(project: Project, message: String, type: NotificationType = NotificationType.INFORMATION) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Maekom Mongo Lens")
                .createNotification(message, type)
                .notify(project)
        } catch (_: Throwable) {
            // Fallback silently if notification group unavailable
        }
    }

    fun createBadgeLabel(text: String, bg: Color, fg: Color): JLabel {
        return JLabel(text).apply {
            isOpaque = true
            background = bg
            foreground = fg
            font = font.deriveFont(Font.BOLD, (font.size - 1).coerceAtLeast(10).toFloat())
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.let { Color(it.red, it.green, it.blue, 90) }, 1, true),
                JBUI.Borders.empty(1, 6)
            )
        }
    }

    fun ensureWritableOrPromptUnlock(
        project: Project,
        connectionManager: MongoConnectionManager,
        profile: MongoConnectionProfile,
        onUnlocked: () -> Unit = {}
    ): Boolean {
        if (!connectionManager.isEffectivelyReadOnly(profile)) {
            return true
        }
        val choice = Messages.showYesNoDialog(
            project,
            I18n.t("readonly.blocked.msg", profile.displayName),
            I18n.t("readonly.blocked.title"),
            I18n.t("readonly.unlock.action"),
            Messages.getCancelButton(),
            Messages.getWarningIcon()
        )
        if (choice == Messages.YES) {
            connectionManager.unlockForSession(profile.id)
            onUnlocked()
            return true
        }
        return false
    }

    fun bsonTypeTag(value: BsonValue): String {
        return when (value) {
            is BsonObjectId -> "ObjectId"
            is BsonDateTime -> "Date"
            is BsonInt32 -> "Int32"
            is BsonInt64 -> "Int64"
            is BsonDecimal128 -> "Decimal128"
            is BsonDouble -> "Double"
            is BsonBoolean -> "Boolean"
            is BsonString -> "String"
            is BsonNull -> "Null"
            is BsonBinary -> "Binary"
            is BsonRegularExpression -> "Regex"
            is BsonTimestamp -> "Timestamp"
            is BsonArray -> "Array [${value.size}]"
            is BsonDocument -> "Object {${value.size}}"
            else -> value.bsonType.name
        }
    }

    fun bsonTypeColor(value: BsonValue): Color {
        return when (value) {
            is BsonObjectId -> BSON_OID_COLOR
            is BsonDateTime, is BsonTimestamp -> BSON_DATE_COLOR
            is BsonInt32, is BsonInt64, is BsonDecimal128, is BsonDouble -> BSON_NUM_COLOR
            is BsonString, is BsonRegularExpression -> BSON_STR_COLOR
            is BsonBoolean -> BSON_BOOL_COLOR
            is BsonNull -> BSON_NULL_COLOR
            else -> BSON_STRUCT_COLOR
        }
    }

    const val MAX_DISPLAY_STRING_CHARS = 400
    const val MAX_JSON_PREVIEW_STRING_CHARS = 2_000
    const val MAX_PREVIEW_ARRAY_ELEMENTS = 100
    const val MAX_JSON_PREVIEW_TOTAL_CHARS = 400_000

    fun truncateForDisplay(text: String, maxChars: Int = MAX_DISPLAY_STRING_CHARS): String {
        if (text.length <= maxChars) return text
        val hidden = text.length - maxChars
        return "${text.substring(0, maxChars)}... (truncated +$hidden chars)"
    }

    /**
     * Produces a display-safe truncated copy of a BsonDocument for Raw JSON preview rendering,
     * capping huge strings, giant arrays, and Binary blobs without EVER mutating the original BsonDocument.
     */
    fun buildDisplaySafePreviewDocument(doc: BsonDocument, depth: Int = 0): BsonDocument {
        val out = BsonDocument()
        for ((k, v) in doc) {
            out[k] = buildDisplaySafePreviewValue(v, depth)
        }
        return out
    }

    private fun buildDisplaySafePreviewValue(value: BsonValue, depth: Int): BsonValue = when (value) {
        is BsonString -> {
            if (value.value.length > MAX_JSON_PREVIEW_STRING_CHARS) {
                BsonString(truncateForDisplay(value.value, MAX_JSON_PREVIEW_STRING_CHARS))
            } else {
                value
            }
        }
        is BsonBinary -> {
            if (value.data.size > 128) {
                BsonString("Binary(${value.data.size} bytes, truncated)")
            } else {
                value
            }
        }
        is BsonDocument -> {
            if (depth >= 8) {
                BsonString("{ ${value.size} fields ... (truncated) }")
            } else {
                buildDisplaySafePreviewDocument(value, depth + 1)
            }
        }
        is BsonArray -> {
            if (depth >= 8) {
                BsonString("[ ${value.size} items ... (truncated) ]")
            } else {
                val arr = BsonArray()
                val limit = value.size.coerceAtMost(MAX_PREVIEW_ARRAY_ELEMENTS)
                for (i in 0 until limit) {
                    arr.add(buildDisplaySafePreviewValue(value[i], depth + 1))
                }
                if (value.size > limit) {
                    arr.add(BsonString("... (+${value.size - limit} more items, truncated)"))
                }
                arr
            }
        }
        else -> value
    }

    fun bsonDisplayValue(value: BsonValue): String {
        return when (value) {
            is BsonObjectId -> "ObjectId(\"${value.value.toHexString()}\")"
            is BsonDateTime -> formatIsoUtc(value.value)
            is BsonInt32 -> value.value.toString()
            is BsonInt64 -> value.value.toString()
            is BsonDecimal128 -> value.value.toString()
            is BsonDouble -> value.value.toString()
            is BsonBoolean -> value.value.toString()
            is BsonString -> "\"${truncateForDisplay(value.value)}\""
            is BsonNull -> "null"
            is BsonBinary -> "Binary(${value.data.size} bytes)"
            is BsonRegularExpression -> "/${truncateForDisplay(value.pattern, 200)}/${value.options}"
            is BsonTimestamp -> "Timestamp(${value.time}, ${value.inc})"
            is BsonArray -> "[ ${value.size} items ]"
            is BsonDocument -> "{ ${value.size} fields }"
            else -> truncateForDisplay(value.toString())
        }
    }

    /** Pure text without outer quotes — used for Tree double-click copy & Table cell copy (ALWAYS full original data!) */
    fun bsonPureTextValue(value: BsonValue): String {
        return when (value) {
            is BsonString -> value.value
            is BsonObjectId -> value.value.toHexString()
            is BsonDateTime -> formatIsoUtc(value.value)
            is BsonInt32 -> value.value.toString()
            is BsonInt64 -> value.value.toString()
            is BsonDecimal128 -> value.value.toString()
            is BsonDouble -> value.value.toString()
            is BsonBoolean -> value.value.toString()
            is BsonNull -> "null"
            is BsonBinary -> "Binary(${value.data.size} bytes)"
            is BsonRegularExpression -> "/${value.pattern}/${value.options}"
            is BsonTimestamp -> "Timestamp(${value.time}, ${value.inc})"
            is BsonDocument -> com.maekom.mongolens.mongo.MongoShellNormalizer.formatCompact(value)
            is BsonArray -> BsonDocument("v", value).toJson().let { j ->
                val idx = j.indexOf(':')
                if (idx > 0) j.substring(idx + 1, j.lastIndexOf('}')).trim() else value.toString()
            }
            else -> value.toString()
        }
    }

    /** Clean quote-free display for Table View cells (safely truncated for huge strings; copy still uses full original!) */
    fun bsonTableDisplayValue(value: BsonValue): String {
        return when (value) {
            is BsonString -> truncateForDisplay(value.value)
            is BsonObjectId -> value.value.toHexString()
            is BsonDateTime -> formatIsoUtc(value.value)
            is BsonInt32 -> value.value.toString()
            is BsonInt64 -> value.value.toString()
            is BsonDecimal128 -> value.value.toString()
            is BsonDouble -> value.value.toString()
            is BsonBoolean -> value.value.toString()
            is BsonNull -> "null"
            is BsonBinary -> "Binary(${value.data.size}B)"
            is BsonRegularExpression -> "/${truncateForDisplay(value.pattern, 200)}/${value.options}"
            is BsonTimestamp -> "${value.time}:${value.inc}"
            is BsonDocument -> "{...}"
            is BsonArray -> "[${value.size} items]"
            else -> truncateForDisplay(value.toString())
        }
    }

    fun bsonIdRawText(idValue: BsonValue): String {
        return try {
            when (idValue) {
                is BsonObjectId -> idValue.value.toHexString()
                is BsonString -> idValue.value
                is BsonInt32 -> idValue.value.toString()
                is BsonInt64 -> idValue.value.toString()
                is BsonDouble -> idValue.value.toString()
                is BsonDecimal128 -> idValue.value.toString()
                is BsonDateTime -> formatIsoUtc(idValue.value)
                is BsonBinary -> {
                    val subType = idValue.type
                    if ((subType == org.bson.BsonBinarySubType.UUID_STANDARD.value ||
                            subType == org.bson.BsonBinarySubType.UUID_LEGACY.value) &&
                        idValue.data.size == 16
                    ) {
                        idValue.asUuid().toString()
                    } else {
                        "Binary(${idValue.data.size}B)"
                    }
                }
                is BsonDocument -> com.maekom.mongolens.mongo.MongoShellNormalizer.formatCompact(idValue)
                else -> idValue.toString()
            }
        } catch (_: Throwable) {
            idValue.toString()
        }
    }
}
