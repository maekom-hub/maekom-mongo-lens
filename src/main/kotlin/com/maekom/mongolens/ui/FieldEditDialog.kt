package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.ConnectionColorTag
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.BsonFieldValidationResult
import com.maekom.mongolens.mongo.BsonValueValidator
import com.maekom.mongolens.mongo.EditableBsonType
import com.maekom.mongolens.mongo.MongoConnectionManager
import org.bson.BsonDocument
import org.bson.BsonValue
import org.bson.types.ObjectId
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.time.Instant
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Single-field "Edit Data" dialog with strict BSON type preservation, real-time `DocumentListener` validation,
 * `Red (Prod)` environment warning banner, `Date` [Now] button + UTC ISO preview, `_id` protection,
 * and typed `$set` `updateOne` execution compatible with both MongoDB and AWS DocumentDB.
 */
class FieldEditDialog(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val originalDoc: BsonDocument,
    rawFieldPath: String,
    private val originalValue: BsonValue?,
    private val onUpdatedInDb: (BsonDocument) -> Unit
) : DialogWrapper(project) {

    private val fieldPath: String = BsonValueValidator.normalizeMongoUpdatePath(rawFieldPath)
    private val originalId: BsonValue? = originalDoc["_id"]
    private val originalType: EditableBsonType = EditableBsonType.fromBsonValue(originalValue)
    private val isProtectedField: Boolean = fieldPath == "_id" || originalId == null || connectionManager.isEffectivelyReadOnly(profile)
    private val updateFutureRef = AtomicReference<Future<*>?>(null)

    private val typeCombo = ComboBox(EditableBsonType.values()).apply {
        selectedItem = originalType
        isEnabled = !isProtectedField
    }

    private val valueCardLayout = CardLayout()
    private val valueCards = JPanel(valueCardLayout)

    private val singleLineField = JBTextField().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        isEditable = !isProtectedField
    }
    private val oidField = JBTextField().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        isEditable = !isProtectedField
    }
    private val oidGenBtn = JButton(I18n.t("edit.dialog.generateObjectId")).apply {
        margin = JBUI.insets(2, 8)
        isEnabled = !isProtectedField
        addActionListener { oidField.text = ObjectId().toHexString() }
    }
    private val boolCombo = ComboBox(arrayOf("true", "false")).apply {
        isEnabled = !isProtectedField
    }
    private val dateField = JBTextField().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        isEditable = !isProtectedField
    }
    private val dateNowBtn = JButton(I18n.t("fieldEdit.date.nowBtn")).apply {
        margin = JBUI.insets(2, 8)
        isEnabled = !isProtectedField
        addActionListener { dateField.text = Instant.now().toString() }
    }
    private val dateUtcPreviewLabel = JBLabel(" ").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, (font.size - 1).coerceAtLeast(11))
        foreground = UiHelpers.BSON_DATE_COLOR
    }
    private val nullField = JBTextField("null").apply {
        isEnabled = false
    }
    private val jsonTextArea = JBTextArea(8, 48).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        isEditable = !isProtectedField
        lineWrap = false
    }
    private val jsonScrollPane = JBScrollPane(jsonTextArea).apply {
        preferredSize = Dimension(JBUI.scale(480), JBUI.scale(150))
    }

    private val validationLabel = JBLabel(" ").apply {
        foreground = UiHelpers.COLOR_RED_FG
        isVisible = false
    }

    private var latestValidResult: BsonFieldValidationResult.Valid? = null

    init {
        title = "${I18n.t("result.doc.editTitle")} — $database.$collection"
        setOKButtonText(I18n.t("edit.dialog.save"))

        setupValueCards()
        populateInitialValue()
        wireListeners()
        init()
        revalidateCurrentInput()
    }

    override fun isResizable(): Boolean = true

    private fun setupValueCards() {
        val oidRow = JPanel(BorderLayout(6, 0)).apply {
            add(oidField, BorderLayout.CENTER)
            add(oidGenBtn, BorderLayout.EAST)
        }

        val dateTopRow = JPanel(BorderLayout(6, 0)).apply {
            add(dateField, BorderLayout.CENTER)
            add(dateNowBtn, BorderLayout.EAST)
        }
        val datePanel = JPanel(BorderLayout(0, 4)).apply {
            add(dateTopRow, BorderLayout.NORTH)
            add(dateUtcPreviewLabel, BorderLayout.SOUTH)
        }

        valueCards.add(singleLineField, "PLAIN")
        valueCards.add(oidRow, "OBJECT_ID")
        valueCards.add(boolCombo, "BOOLEAN")
        valueCards.add(datePanel, "DATE")
        valueCards.add(nullField, "NULL")
        valueCards.add(jsonScrollPane, "JSON")
    }

    private fun populateInitialValue() {
        val initialText = BsonValueValidator.initialTextFor(originalValue)
        when (originalType) {
            EditableBsonType.BOOLEAN -> {
                boolCombo.selectedItem = if (initialText.equals("true", ignoreCase = true)) "true" else "false"
            }
            EditableBsonType.OBJECT_ID -> oidField.text = initialText
            EditableBsonType.DATE -> dateField.text = initialText.ifBlank { Instant.now().toString() }
            EditableBsonType.JSON_OBJECT_ARRAY -> jsonTextArea.text = initialText
            EditableBsonType.NULL -> nullField.text = "null"
            else -> singleLineField.text = initialText
        }
        switchCardForType(originalType, initializeDefaults = false)
    }

    private fun switchCardForType(type: EditableBsonType, initializeDefaults: Boolean) {
        when (type) {
            EditableBsonType.BOOLEAN -> {
                if (initializeDefaults) {
                    val cur = singleLineField.text.trim().lowercase()
                    boolCombo.selectedItem = if (cur == "true") "true" else "false"
                }
                valueCardLayout.show(valueCards, "BOOLEAN")
            }
            EditableBsonType.OBJECT_ID -> {
                if (initializeDefaults && oidField.text.trim().isEmpty()) {
                    val candidate = singleLineField.text.trim()
                    oidField.text = if (ObjectId.isValid(candidate)) candidate else ObjectId().toHexString()
                }
                valueCardLayout.show(valueCards, "OBJECT_ID")
            }
            EditableBsonType.DATE -> {
                if (initializeDefaults && dateField.text.trim().isEmpty()) {
                    val candidate = singleLineField.text.trim()
                    dateField.text = BsonValueValidator.parseFlexibleInstant(candidate)?.toString()
                        ?: Instant.now().toString()
                }
                valueCardLayout.show(valueCards, "DATE")
            }
            EditableBsonType.NULL -> {
                valueCardLayout.show(valueCards, "NULL")
            }
            EditableBsonType.JSON_OBJECT_ARRAY -> {
                if (initializeDefaults && jsonTextArea.text.trim().isEmpty()) {
                    val candidate = singleLineField.text.trim()
                    jsonTextArea.text = if (candidate.startsWith("{") || candidate.startsWith("[")) candidate else "{}"
                }
                valueCardLayout.show(valueCards, "JSON")
            }
            else -> {
                if (initializeDefaults && singleLineField.text.isEmpty()) {
                    singleLineField.text = when {
                        oidField.text.isNotEmpty() -> oidField.text
                        dateField.text.isNotEmpty() -> dateField.text
                        else -> ""
                    }
                }
                valueCardLayout.show(valueCards, "PLAIN")
            }
        }
    }

    private fun wireListeners() {
        typeCombo.addActionListener {
            val selected = selectedType()
            switchCardForType(selected, initializeDefaults = true)
            revalidateCurrentInput()
        }
        boolCombo.addActionListener { revalidateCurrentInput() }

        val liveDocListener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                revalidateCurrentInput()
            }
        }
        singleLineField.document.addDocumentListener(liveDocListener)
        oidField.document.addDocumentListener(liveDocListener)
        dateField.document.addDocumentListener(liveDocListener)
        jsonTextArea.document.addDocumentListener(liveDocListener)
    }

    private fun selectedType(): EditableBsonType =
        (typeCombo.selectedItem as? EditableBsonType) ?: EditableBsonType.STRING

    private fun currentRawInput(type: EditableBsonType): String = when (type) {
        EditableBsonType.BOOLEAN -> boolCombo.selectedItem?.toString() ?: "false"
        EditableBsonType.OBJECT_ID -> oidField.text
        EditableBsonType.DATE -> dateField.text
        EditableBsonType.NULL -> "null"
        EditableBsonType.JSON_OBJECT_ARRAY -> jsonTextArea.text
        else -> singleLineField.text
    }

    private fun activeInputComponent(type: EditableBsonType): JComponent = when (type) {
        EditableBsonType.BOOLEAN -> boolCombo
        EditableBsonType.OBJECT_ID -> oidField
        EditableBsonType.DATE -> dateField
        EditableBsonType.NULL -> nullField
        EditableBsonType.JSON_OBJECT_ARRAY -> jsonScrollPane
        else -> singleLineField
    }

    private fun clearAllErrorOutlines() {
        singleLineField.putClientProperty("JComponent.outline", null)
        oidField.putClientProperty("JComponent.outline", null)
        dateField.putClientProperty("JComponent.outline", null)
        boolCombo.putClientProperty("JComponent.outline", null)
        jsonScrollPane.putClientProperty("JComponent.outline", null)
    }

    private fun revalidateCurrentInput() {
        clearAllErrorOutlines()

        if (fieldPath == "_id") {
            latestValidResult = null
            validationLabel.icon = AllIcons.General.Warning
            validationLabel.text = I18n.t("fieldEdit.error.idReadOnly")
            validationLabel.isVisible = true
            isOKActionEnabled = false
            return
        }
        if (originalId == null) {
            latestValidResult = null
            validationLabel.icon = AllIcons.General.Error
            validationLabel.text = I18n.t("fieldEdit.error.missingId")
            validationLabel.isVisible = true
            isOKActionEnabled = false
            return
        }
        if (connectionManager.isEffectivelyReadOnly(profile)) {
            latestValidResult = null
            validationLabel.icon = AllIcons.General.Warning
            validationLabel.text = I18n.t("edit.dialog.readOnlyBanner")
            validationLabel.isVisible = true
            isOKActionEnabled = false
            return
        }

        val type = selectedType()
        val raw = currentRawInput(type)
        when (val result = BsonValueValidator.validateAndParse(type, raw)) {
            is BsonFieldValidationResult.Valid -> {
                latestValidResult = result
                validationLabel.isVisible = false
                validationLabel.text = " "
                if (type == EditableBsonType.DATE) {
                    dateUtcPreviewLabel.text = I18n.t("fieldEdit.date.utcPreview", result.previewText ?: "")
                    dateUtcPreviewLabel.foreground = UiHelpers.BSON_DATE_COLOR
                }
                isOKActionEnabled = true
            }
            is BsonFieldValidationResult.Invalid -> {
                latestValidResult = null
                activeInputComponent(type).putClientProperty("JComponent.outline", "error")
                validationLabel.icon = AllIcons.General.Error
                validationLabel.text = result.errorMessage
                validationLabel.isVisible = true
                if (type == EditableBsonType.DATE) {
                    dateUtcPreviewLabel.text = I18n.t("fieldEdit.date.utcPreviewInvalid")
                    dateUtcPreviewLabel.foreground = UiHelpers.COLOR_RED_FG
                }
                isOKActionEnabled = false
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(6)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(310))
        }

        val topBanners = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        if (profile.colorTag == ConnectionColorTag.RED) {
            val prodBanner = JBLabel(
                I18n.t("fieldEdit.prodWarningBanner"),
                AllIcons.General.Warning,
                JBLabel.LEFT
            ).apply {
                isOpaque = true
                alignmentX = JComponent.LEFT_ALIGNMENT
                background = JBColor(Color(0xFDECEC), Color(0x4A1D21))
                foreground = JBColor(Color(0xB71C1C), Color(0xFF8A93))
                font = font.deriveFont(Font.BOLD)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xF5B5B5), Color(0x7A2E35))),
                    JBUI.Borders.empty(6, 10)
                )
            }
            topBanners.add(prodBanner)
            topBanners.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        val origSummaryValue = UiHelpers.truncateForDisplay(
            BsonValueValidator.initialTextFor(originalValue).replace("\n", " "),
            80
        )
        val origSummaryLabel = JBLabel(
            I18n.t("fieldEdit.originalSummary", originalType.label, origSummaryValue)
        ).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        val keyLabel = JBLabel(fieldPath).apply {
            font = Font(Font.MONOSPACED, Font.BOLD, font.size)
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("fieldEdit.fieldPath")), keyLabel, 4, false)
            .addLabeledComponent(JBLabel(I18n.t("fieldEdit.originalLabel")), origSummaryLabel, 4, false)
            .addLabeledComponent(JBLabel(I18n.t("fieldEdit.targetType")), typeCombo, 6, false)
            .addLabeledComponent(JBLabel(I18n.t("fieldEdit.valueLabel")), valueCards, 6, false)
            .addComponent(validationLabel, 6)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        if (topBanners.componentCount > 0) {
            root.add(topBanners, BorderLayout.NORTH)
        }
        root.add(form, BorderLayout.CENTER)
        return root
    }

    override fun doOKAction() {
        revalidateCurrentInput()
        val valid = latestValidResult ?: return
        val idVal = originalId ?: return

        isOKActionEnabled = false
        validationLabel.icon = null
        validationLabel.foreground = UiHelpers.COLOR_BLUE_FG
        validationLabel.text = I18n.t("fieldEdit.saving")
        validationLabel.isVisible = true

        updateFutureRef.getAndSet(null)?.cancel(true)
        val future = AppExecutorUtil.getAppExecutorService().submit {
            try {
                val updatedDoc = connectionManager.updateDocumentField(
                    profile = profile,
                    database = database,
                    collection = collection,
                    originalId = idVal,
                    fieldPath = fieldPath,
                    typedBsonValue = valid.bsonValue,
                    typedNativeValue = valid.nativeValue,
                    fallbackDoc = originalDoc
                )
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    UiHelpers.notify(project, I18n.t("result.doc.editSuccess"))
                    onUpdatedInDb(updatedDoc)
                    super.doOKAction()
                }, ModalityState.any())
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    validationLabel.icon = AllIcons.General.Error
                    validationLabel.foreground = UiHelpers.COLOR_RED_FG
                    validationLabel.text = msg
                    validationLabel.isVisible = true
                    isOKActionEnabled = true
                }, ModalityState.any())
            }
        }
        updateFutureRef.set(future)
    }

    override fun dispose() {
        updateFutureRef.getAndSet(null)?.cancel(true)
        super.dispose()
    }
}
