package com.maekom.mongolens.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.i18n.I18n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.TimeZone
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerDateModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class DateEditorPanel(initialIsoOrMillis: String = "") : JPanel() {

    enum class TzOption(val label: String, val zoneId: ZoneId) {
        KST("KST (UTC+9)", ZoneId.of("Asia/Seoul")),
        UTC("UTC", ZoneOffset.UTC);

        override fun toString(): String = label
    }

    private var currentInstant: Instant = parseInitialInstant(initialIsoOrMillis)
    private var suppressSync = false

    private val tzCombo = ComboBox(TzOption.values()).apply {
        selectedItem = TzOption.KST
        preferredSize = Dimension(118, 26)
    }

    private val spinnerModel = SpinnerDateModel(Date.from(currentInstant), null, null, java.util.Calendar.SECOND)
    private val dateSpinner = JSpinner(spinnerModel).apply {
        preferredSize = Dimension(170, 26)
    }
    private val dateEditor = JSpinner.DateEditor(dateSpinner, "yyyy-MM-dd HH:mm:ss").apply {
        format.timeZone = TimeZone.getTimeZone(TzOption.KST.zoneId)
    }

    private val nowBtn = JButton(I18n.t("edit.dialog.dateNow")).apply {
        margin = JBUI.insets(1, 6)
    }
    private val todayBtn = JButton(I18n.t("edit.dialog.dateToday")).apply {
        margin = JBUI.insets(1, 6)
    }

    private val manualIsoField = JBTextField(formatUtcIso(currentInstant)).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    private val utcPreviewLabel = JBLabel("").apply {
        font = Font(Font.MONOSPACED, Font.BOLD, 11)
        foreground = UiHelpers.BSON_DATE_COLOR
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        dateSpinner.editor = dateEditor

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(dateSpinner)
            add(tzCombo)
            add(nowBtn)
            add(todayBtn)
        }

        val row2 = JPanel(BorderLayout(6, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            add(JBLabel(I18n.t("edit.dialog.dateManualIso")), BorderLayout.WEST)
            add(manualIsoField, BorderLayout.CENTER)
        }

        val row3 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(utcPreviewLabel)
        }

        add(row1)
        add(Box.createVerticalStrut(4))
        add(row2)
        add(Box.createVerticalStrut(3))
        add(row3)

        wireListeners()
        syncAllViewsFromInstant()
    }

    private fun selectedZone(): ZoneId = (tzCombo.selectedItem as? TzOption)?.zoneId ?: TzOption.KST.zoneId

    private fun wireListeners() {
        tzCombo.addActionListener {
            dateEditor.format.timeZone = TimeZone.getTimeZone(selectedZone())
            syncAllViewsFromInstant()
        }

        dateSpinner.addChangeListener {
            if (suppressSync) return@addChangeListener
            val d = spinnerModel.date ?: return@addChangeListener
            currentInstant = d.toInstant()
            syncManualAndPreviewFromInstant()
        }

        nowBtn.addActionListener {
            currentInstant = Instant.now()
            syncAllViewsFromInstant()
        }

        todayBtn.addActionListener {
            val z = selectedZone()
            val startOfToday = LocalDate.now(z).atStartOfDay(z).toInstant()
            currentInstant = startOfToday
            syncAllViewsFromInstant()
        }

        manualIsoField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onManualIsoEdited()
            override fun removeUpdate(e: DocumentEvent?) = onManualIsoEdited()
            override fun changedUpdate(e: DocumentEvent?) = onManualIsoEdited()
        })
    }

    private fun onManualIsoEdited() {
        if (suppressSync) return
        val parsed = tryParseInstant((manualIsoField.text as String?)?.trim().orEmpty()) ?: return
        currentInstant = parsed
        suppressSync = true
        try {
            spinnerModel.value = Date.from(currentInstant)
            utcPreviewLabel.text = I18n.t("edit.dialog.dateUtcPreview", formatUtcIso(currentInstant))
        } finally {
            suppressSync = false
        }
    }

    private fun syncAllViewsFromInstant() {
        suppressSync = true
        try {
            spinnerModel.value = Date.from(currentInstant)
            dateEditor.textField.text = dateEditor.format.format(Date.from(currentInstant))
            val iso = formatUtcIso(currentInstant)
            manualIsoField.text = iso
            utcPreviewLabel.text = I18n.t("edit.dialog.dateUtcPreview", iso)
        } finally {
            suppressSync = false
        }
    }

    private fun syncManualAndPreviewFromInstant() {
        suppressSync = true
        try {
            val iso = formatUtcIso(currentInstant)
            manualIsoField.text = iso
            utcPreviewLabel.text = I18n.t("edit.dialog.dateUtcPreview", iso)
        } finally {
            suppressSync = false
        }
    }

    fun getUtcIsoString(): String {
        val manual = tryParseInstant((manualIsoField.text as String?)?.trim().orEmpty())
        if (manual != null) {
            currentInstant = manual
        }
        return formatUtcIso(currentInstant)
    }

    fun getManualRawText(): String = (manualIsoField.text as String?)?.trim().orEmpty()

    fun isValidDateInput(): Boolean = tryParseInstant((manualIsoField.text as String?)?.trim().orEmpty()) != null

    companion object {
        private val isoFormatter = DateTimeFormatter.ISO_INSTANT

        fun formatUtcIso(instant: Instant): String = isoFormatter.format(instant)

        fun tryParseInstant(raw: String): Instant? {
            if (raw.isEmpty()) return null
            raw.toLongOrNull()?.let { return Instant.ofEpochMilli(it) }
            return try {
                Instant.parse(raw)
            } catch (_: Throwable) {
                try {
                    LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
                } catch (_: Throwable) {
                    null
                }
            }
        }

        private fun parseInitialInstant(raw: String): Instant = tryParseInstant(raw.trim()) ?: Instant.now()
    }
}

class DatePickerDialog(
    project: Project,
    initialIso: String
) : DialogWrapper(project) {

    private val pickerPanel = DateEditorPanel(initialIso)

    init {
        title = I18n.t("edit.dialog.datePickerTitle")
        init()
    }

    override fun createCenterPanel(): JComponent {
        pickerPanel.border = JBUI.Borders.empty(6)
        pickerPanel.preferredSize = Dimension(510, 120)
        return pickerPanel
    }

    fun getSelectedUtcIso(): String = pickerPanel.getUtcIsoString()
}
