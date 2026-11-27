package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoLensSettings
import com.maekom.mongolens.i18n.I18n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

/**
 * Dialog opened from the top-right Settings (⚙) menu or from the Timeout Guide popup
 * to configure `queryTimeoutSeconds` (default 40s, min 5s, max 600s).
 */
class QuerySettingsDialog(
    project: Project
) : DialogWrapper(project) {

    private val settings = MongoLensSettings.getInstance()
    private val timeoutSpinner = JSpinner(
        SpinnerNumberModel(
            settings.queryTimeoutSeconds,
            MongoLensSettings.MIN_QUERY_TIMEOUT_SECONDS,
            MongoLensSettings.MAX_QUERY_TIMEOUT_SECONDS,
            5
        )
    ).apply {
        preferredSize = Dimension(JBUI.scale(110), JBUI.scale(28))
    }

    init {
        title = I18n.t("settings.timeout.dialogTitle")
        setOKButtonText(I18n.t("edit.dialog.save"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val hintLabel = JBLabel("<html>${I18n.t("settings.timeout.hint")}</html>").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = font.deriveFont(font.size2D - 0.5f)
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("settings.timeout.label")), timeoutSpinner, 4, false)
            .addComponent(hintLabel, 8)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        form.preferredSize = Dimension(JBUI.scale(440), JBUI.scale(110))
        form.border = JBUI.Borders.empty(6)
        return form
    }

    override fun doOKAction() {
        runCatching { timeoutSpinner.commitEdit() }
        val raw = (timeoutSpinner.value as? Number)?.toInt() ?: MongoLensSettings.DEFAULT_QUERY_TIMEOUT_SECONDS
        settings.queryTimeoutSeconds = MongoLensSettings.clampTimeoutSeconds(raw)
        super.doOKAction()
    }
}

/**
 * Guidance popup shown once when a query exceeds `queryTimeoutSeconds` (server error 50 or client watchdog),
 * NEVER shown when the user manually clicks Stop.
 */
class TimeoutGuideDialog(
    project: Project,
    private val timeoutSeconds: Int,
    private val onOpenSettings: () -> Unit
) : DialogWrapper(project) {

    init {
        title = I18n.t("timeout.guide.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(12, 8)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(210))
        }

        val iconLabel = JBLabel(AllIcons.General.WarningDialog).apply {
            verticalAlignment = JBLabel.TOP
        }

        val header = JBLabel("<html><b>${I18n.t("timeout.guide.summary", timeoutSeconds)}</b></html>").apply {
            font = font.deriveFont(Font.BOLD)
        }

        val bodyHtml = buildString {
            append("<html><div style='line-height:1.45;'>")
            append(I18n.t("timeout.guide.causesTitle"))
            append("<ol style='margin-top:6px; margin-left:16px;'>")
            append("<li>").append(I18n.t("timeout.guide.tip1")).append("</li>")
            append("<li>").append(I18n.t("timeout.guide.tip2")).append("</li>")
            append("<li>").append(I18n.t("timeout.guide.tip3")).append("</li>")
            append("</ol></div></html>")
        }
        val bodyLabel = JBLabel(bodyHtml)

        val textStack = JPanel(BorderLayout(0, 8)).apply {
            add(header, BorderLayout.NORTH)
            add(bodyLabel, BorderLayout.CENTER)
        }

        root.add(iconLabel, BorderLayout.WEST)
        root.add(textStack, BorderLayout.CENTER)
        return root
    }

    override fun createSouthPanel(): JComponent {
        val openSettingsBtn = JButton(I18n.t("timeout.guide.openSettings"), AllIcons.General.Settings).apply {
            font = font.deriveFont(Font.BOLD)
            margin = JBUI.insets(4, 12)
            addActionListener {
                close(OK_EXIT_CODE)
                // Open settings after closing guide modal to prevent nested modal dialog conflicts
                SwingUtilities.invokeLater {
                    onOpenSettings()
                }
            }
        }
        val closeBtn = JButton(I18n.t("index.dialog.close")).apply {
            margin = JBUI.insets(4, 12)
            addActionListener { close(CANCEL_EXIT_CODE) }
        }

        return JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            border = JBUI.Borders.emptyTop(6)
            add(openSettingsBtn)
            add(closeBtn)
        }
    }
}
