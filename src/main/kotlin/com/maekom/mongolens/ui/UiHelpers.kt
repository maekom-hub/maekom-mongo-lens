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

    fun formatKst(epochMillis: Long): String = kstFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(kstZone))

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

    fun bsonDisplayValue(value: BsonValue): String {
        return when (value) {
            is BsonObjectId -> value.value.toHexString()
            is BsonDateTime -> formatIsoUtc(value.value)
            is BsonInt32 -> value.value.toString()
            is BsonInt64 -> value.value.toString()
            is BsonDecimal128 -> value.value.toString()
            is BsonDouble -> value.value.toString()
            is BsonBoolean -> value.value.toString()
            is BsonString -> "\"${value.value}\""
            is BsonNull -> "null"
            is BsonBinary -> "Binary(${value.data.size} bytes)"
            is BsonRegularExpression -> "/${value.pattern}/${value.options}"
            is BsonTimestamp -> "Timestamp(${value.time}, ${value.inc})"
            is BsonArray -> "[ ${value.size} items ]"
            is BsonDocument -> "{ ${value.size} fields }"
            else -> value.toString()
        }
    }

    /** Pure text without outer quotes — used for Tree double-click copy & Table cell copy */
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

    /** Clean quote-free display for Table View cells */
    fun bsonTableDisplayValue(value: BsonValue): String {
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
            is BsonBinary -> "Binary(${value.data.size}B)"
            is BsonRegularExpression -> "/${value.pattern}/${value.options}"
            is BsonTimestamp -> "${value.time}:${value.inc}"
            is BsonDocument -> "{...}"
            is BsonArray -> "[${value.size} items]"
            else -> value.toString()
        }
    }

    fun bsonIdRawText(idValue: BsonValue): String {
        return when (idValue) {
            is BsonObjectId -> idValue.value.toHexString()
            is BsonString -> idValue.value
            is BsonInt32 -> idValue.value.toString()
            is BsonInt64 -> idValue.value.toString()
            else -> idValue.toString()
        }
    }
}
