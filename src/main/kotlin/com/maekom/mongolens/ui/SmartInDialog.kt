package com.maekom.mongolens.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoShellNormalizer
import com.maekom.mongolens.mongo.SmartInValueType
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SmartInDialog(
    project: Project,
    initialText: String = "",
    filterFieldKeys: List<String> = emptyList(),
    schemaFieldKeys: List<String> = emptyList(),
    private val schemaFieldTypes: Map<String, SmartInValueType> = emptyMap()
) : DialogWrapper(project) {

    // Priority order: 1. _id -> 2. Current Filter keys -> 3. Collection schema keys (incl. dot-notation)
    private val allFieldCandidates: List<String> = buildList {
        add("_id")
        filterFieldKeys.forEach { if (it.isNotBlank() && it !in this) add(it) }
        schemaFieldKeys.forEach { if (it.isNotBlank() && it !in this) add(it) }
    }

    private val comboModel = DefaultComboBoxModel(allFieldCandidates.toTypedArray())
    private val fieldCombo = ComboBox(comboModel).apply {
        isEditable = true
        selectedItem = "_id"
        toolTipText = I18n.t("smartIn.dialog.fieldHint")
    }

    private val inferredTypeBadge = UiHelpers.createBadgeLabel(
        I18n.t("smartIn.dialog.typeInferred", SmartInValueType.AUTO.label),
        UiHelpers.COLOR_BLUE_BG,
        UiHelpers.COLOR_BLUE_FG
    )

    private val rawIdsArea = JBTextArea(6, 50)
    private val previewArea = JBTextArea(6, 50)

    private var suppressComboFilter = false

    var runImmediately: Boolean = false
        private set

    init {
        title = I18n.t("smartIn.dialog.title")
        setOKButtonText(I18n.t("smartIn.dialog.apply"))

        rawIdsArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        previewArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        previewArea.isEditable = false

        val prefill = initialText.ifBlank { detectClipboardIds() }
        rawIdsArea.text = prefill

        setupFieldAutocompleteCombo()

        rawIdsArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updatePreview()
            override fun removeUpdate(e: DocumentEvent?) = updatePreview()
            override fun changedUpdate(e: DocumentEvent?) = updatePreview()
        })

        updatePreview()
        init()
    }

    /**
     * Place initial keyboard focus directly in the ID paste area so user can hit Ctrl+V immediately
     * while Target Field stays cleanly defaulted to `_id`.
     */
    override fun getPreferredFocusedComponent(): JComponent = rawIdsArea

    private fun setupFieldAutocompleteCombo() {
        val editorField = fieldCombo.editor.editorComponent as? JTextField

        fieldCombo.addActionListener {
            if (!suppressComboFilter) {
                updatePreview()
            }
        }

        if (editorField != null) {
            editorField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onFieldTyped(editorField)
                override fun removeUpdate(e: DocumentEvent?) = onFieldTyped(editorField)
                override fun changedUpdate(e: DocumentEvent?) = onFieldTyped(editorField)
            })

            editorField.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && fieldCombo.isPopupVisible) {
                        val sel = fieldCombo.selectedItem?.toString()
                        if (!sel.isNullOrBlank()) {
                            suppressComboFilter = true
                            try {
                                editorField.text = sel
                                fieldCombo.hidePopup()
                            } finally {
                                suppressComboFilter = false
                            }
                            updatePreview()
                            e.consume()
                        }
                    }
                }
            })
        }
    }

    private fun onFieldTyped(editorField: JTextField) {
        if (suppressComboFilter) return
        val typed = editorField.text
        updatePreview()

        if (!editorField.isFocusOwner) return

        SwingUtilities.invokeLater {
            if (!editorField.isFocusOwner) return@invokeLater
            val query = editorField.text.trim()
            val filtered = if (query.isEmpty()) {
                allFieldCandidates
            } else {
                val prefixMatches = allFieldCandidates.filter { it.startsWith(query, ignoreCase = true) }
                val containsMatches = allFieldCandidates.filter {
                    !it.startsWith(query, ignoreCase = true) && it.contains(query, ignoreCase = true)
                }
                prefixMatches + containsMatches
            }

            suppressComboFilter = true
            try {
                val caret = editorField.caretPosition
                comboModel.removeAllElements()
                filtered.forEach { comboModel.addElement(it) }
                editorField.text = typed
                editorField.caretPosition = caret.coerceIn(0, typed.length)
                if (filtered.isNotEmpty() && query.isNotEmpty()) {
                    fieldCombo.isPopupVisible = true
                } else if (filtered.isEmpty()) {
                    fieldCombo.isPopupVisible = false
                }
            } finally {
                suppressComboFilter = false
            }
        }
    }

    private fun currentTargetField(): String {
        val editorField = fieldCombo.editor.editorComponent as? JTextField
        val raw = editorField?.text ?: fieldCombo.selectedItem?.toString() ?: "_id"
        return raw.trim().ifEmpty { "_id" }
    }

    private fun inferTypeForField(fieldName: String): SmartInValueType {
        val sampleType = schemaFieldTypes[fieldName]
        return sampleType ?: SmartInValueType.AUTO
    }

    private fun detectClipboardIds(): String {
        val clip = UiHelpers.readClipboardText()?.trim() ?: return ""
        if (clip.length > 10_000) return ""
        if (clip.startsWith("{") || clip.startsWith("[")) return ""
        return clip
    }

    private fun updatePreview() {
        val targetField = currentTargetField()
        val preferredType = inferTypeForField(targetField)
        inferredTypeBadge.text = I18n.t("smartIn.dialog.typeInferred", preferredType.label)
        previewArea.text = MongoShellNormalizer.buildSmartInQuery(rawIdsArea.text, targetField, preferredType)
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(6)
        root.preferredSize = Dimension(590, 380)

        val fieldRow = JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel(I18n.t("smartIn.dialog.fieldLabel")), BorderLayout.WEST)
            add(fieldCombo, BorderLayout.CENTER)
            add(inferredTypeBadge, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }

        val inputLabel = JBLabel(I18n.t("smartIn.dialog.inputLabel")).apply {
            border = JBUI.Borders.empty(8, 0, 4, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val previewLabel = JBLabel(I18n.t("smartIn.dialog.previewLabel")).apply {
            border = JBUI.Borders.empty(8, 0, 4, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        fieldRow.alignmentX = JComponent.LEFT_ALIGNMENT
        val rawScroll = JBScrollPane(rawIdsArea).apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val prevScroll = JBScrollPane(previewArea).apply { alignmentX = JComponent.LEFT_ALIGNMENT }

        root.add(fieldRow)
        root.add(inputLabel)
        root.add(rawScroll)
        root.add(previewLabel)
        root.add(prevScroll)
        return root
    }

    override fun createActions(): Array<Action> {
        val applyAndRunAction = object : DialogWrapperAction(I18n.t("smartIn.dialog.applyAndRun")) {
            override fun doAction(e: ActionEvent?) {
                runImmediately = true
                doOKAction()
            }
        }
        return arrayOf(okAction, applyAndRunAction, cancelAction)
    }

    fun getGeneratedQuery(): String = previewArea.text
}
