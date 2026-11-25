package com.maekom.mongolens.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoShellNormalizer
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SmartInDialog(
    project: Project,
    initialText: String = ""
) : DialogWrapper(project) {

    private val fieldNameInput = JBTextField("_id")
    private val rawIdsArea = JBTextArea(6, 50)
    private val previewArea = JBTextArea(6, 50)
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

        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updatePreview()
            override fun removeUpdate(e: DocumentEvent?) = updatePreview()
            override fun changedUpdate(e: DocumentEvent?) = updatePreview()
        }
        fieldNameInput.document.addDocumentListener(listener)
        rawIdsArea.document.addDocumentListener(listener)
        updatePreview()

        init()
    }

    private fun detectClipboardIds(): String {
        val clip = UiHelpers.readClipboardText()?.trim() ?: return ""
        if (clip.length > 10_000) return ""
        if (clip.startsWith("{") || clip.startsWith("[")) return ""
        return clip
    }

    private fun updatePreview() {
        previewArea.text = MongoShellNormalizer.buildSmartInQuery(rawIdsArea.text, fieldNameInput.text)
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(6)
        root.preferredSize = Dimension(560, 360)

        val fieldRow = JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel(I18n.t("smartIn.dialog.fieldLabel")), BorderLayout.WEST)
            add(fieldNameInput, BorderLayout.CENTER)
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
