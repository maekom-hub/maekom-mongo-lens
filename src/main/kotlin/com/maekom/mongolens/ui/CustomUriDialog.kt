package com.maekom.mongolens.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.ProfileSource
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.UUID
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class CustomUriDialog(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val existing: MongoConnectionProfile? = null
) : DialogWrapper(project) {

    private val nameField = JBTextField(existing?.displayName ?: "")
    private val uriField = JBTextField(existing?.uri ?: "mongodb://localhost:27017")
    private val dbField = JBTextField(existing?.database ?: "")
    private val readOnlyCheck = JBCheckBox(I18n.t("custom.dialog.readOnly"), existing?.readOnly ?: false)
    private val testStatusLabel = JBLabel(" ")
    private val testButton = JButton(I18n.t("custom.dialog.test"))

    init {
        title = if (existing == null) I18n.t("custom.dialog.title") else I18n.t("custom.dialog.editTitle")
        testButton.addActionListener { runConnectionTest() }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val testRow = JPanel(BorderLayout(8, 0)).apply {
            add(testButton, BorderLayout.WEST)
            add(testStatusLabel, BorderLayout.CENTER)
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("custom.dialog.name")), nameField, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("custom.dialog.uri")), uriField, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("custom.dialog.database")), dbField, 1, false)
            .addComponent(readOnlyCheck, 6)
            .addComponent(testRow, 10)
            .panel

        form.preferredSize = Dimension(520, 210)
        form.border = JBUI.Borders.empty(6)
        return form
    }

    override fun doValidate(): ValidationInfo? {
        if ((nameField.text as String?)?.trim().orEmpty().isEmpty()) {
            return ValidationInfo(I18n.t("custom.dialog.validationName"), nameField)
        }
        val uri = (uriField.text as String?)?.trim().orEmpty()
        if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
            return ValidationInfo(I18n.t("custom.dialog.validationUri"), uriField)
        }
        return null
    }

    private fun runConnectionTest() {
        val uri = (uriField.text as String?)?.trim().orEmpty()
        if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
            testStatusLabel.text = I18n.t("custom.dialog.validationUri")
            testStatusLabel.foreground = UiHelpers.COLOR_RED_FG
            return
        }
        testButton.isEnabled = false
        testStatusLabel.text = I18n.t("custom.dialog.testing")
        testStatusLabel.foreground = UiHelpers.COLOR_BLUE_FG

        val db = (dbField.text as String?)?.trim()?.takeIf { it.isNotEmpty() }
        object : Task.Backgroundable(project, I18n.t("custom.dialog.testing"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val ms = connectionManager.testUriDirect(uri, db)
                    ApplicationManager.getApplication().invokeLater {
                        testButton.isEnabled = true
                        testStatusLabel.text = I18n.t("custom.dialog.testSuccess", ms)
                        testStatusLabel.foreground = UiHelpers.COLOR_GREEN_FG
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        testButton.isEnabled = true
                        testStatusLabel.text = I18n.t("custom.dialog.testFail", t.message ?: t.javaClass.simpleName)
                        testStatusLabel.foreground = UiHelpers.COLOR_RED_FG
                    }
                }
            }
        }.queue()
    }

    fun buildProfile(): MongoConnectionProfile {
        val name = (nameField.text as String?)?.trim().orEmpty()
        val uri = (uriField.text as String?)?.trim().orEmpty()
        val db = (dbField.text as String?)?.trim()?.takeIf { it.isNotEmpty() }
        val isPrd = readOnlyCheck.isSelected || MongoConnectionProfile.isProductionProfile(name, null)
        return MongoConnectionProfile(
            id = existing?.id ?: "custom:${UUID.randomUUID()}",
            displayName = name,
            profileName = name,
            source = ProfileSource.CUSTOM,
            uri = uri,
            database = db,
            readOnly = isPrd
        )
    }
}
