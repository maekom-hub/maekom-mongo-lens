package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.ConnectionColorTag
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.ImportErrorCategory
import com.maekom.mongolens.mongo.ImportValidationIssue
import com.maekom.mongolens.mongo.JsonImportEngine
import com.maekom.mongolens.mongo.MongoConnectionManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Collection JSON Data Import Dialog supporting single object `{...}`, array `[...]`, and NDJSON formats,
 * with [.json file load] (10MB cap), [Format JSON], [Strip existing _id] toggle,
 * [Validate Only (Dry-Run)], and 4-stage atomic Pre-Flight Validation blocking DB writes on any error.
 */
class ImportJsonDialog(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val onImportSucceeded: () -> Unit
) : DialogWrapper(project) {

    private val workerFutureRef = AtomicReference<Future<*>?>(null)

    private val jsonEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "{\n  \n}",
        multiLine = true
    )

    private val loadFileBtn = JButton(I18n.t("import.dialog.loadFile"), AllIcons.Actions.MenuOpen).apply {
        margin = JBUI.insets(2, 8)
    }
    private val formatJsonBtn = JButton(I18n.t("import.dialog.formatJson"), AllIcons.Actions.ListFiles).apply {
        margin = JBUI.insets(2, 8)
    }
    private val stripIdCheck = JBCheckBox(I18n.t("import.dialog.stripId"), false)

    private val statusLabel = JBLabel(" ").apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    private val dryRunBtn = JButton(I18n.t("import.dialog.dryRun"), AllIcons.Actions.Checked).apply {
        margin = JBUI.insets(4, 12)
    }
    private val importBtn = JButton(I18n.t("import.dialog.execute"), AllIcons.Actions.Upload).apply {
        font = font.deriveFont(Font.BOLD)
        margin = JBUI.insets(4, 14)
    }
    private val cancelBtn = JButton(I18n.t("edit.dialog.cancel")).apply {
        margin = JBUI.insets(4, 12)
    }

    init {
        title = "${I18n.t("import.dialog.title")} — $database.$collection"
        wireListeners()
        init()
    }

    override fun isResizable(): Boolean = true

    override fun getDimensionServiceKey(): String = "MaekomMongoLens.ImportJsonDialog"

    private fun wireListeners() {
        loadFileBtn.addActionListener { handleLoadJsonFile() }
        formatJsonBtn.addActionListener { handleFormatJson() }
        dryRunBtn.addActionListener { runValidationAndOptionalImport(dryRunOnly = true) }
        importBtn.addActionListener { runValidationAndOptionalImport(dryRunOnly = false) }
        cancelBtn.addActionListener { doCancelAction() }
    }

    private fun handleLoadJsonFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle(I18n.t("import.dialog.loadFileTitle"))
            .withDescription(I18n.t("import.dialog.loadFileDesc"))
        val vf = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = Paths.get(vf.path)
        try {
            val sizeBytes = Files.size(path)
            if (sizeBytes > JsonImportEngine.MAX_IMPORT_FILE_BYTES) {
                val sizeMb = String.format("%.2f", sizeBytes / (1024.0 * 1024.0))
                Messages.showErrorDialog(
                    project,
                    I18n.t("import.dialog.fileTooLarge", sizeMb),
                    I18n.t("import.dialog.title")
                )
                return
            }
            val content = Files.readString(path, StandardCharsets.UTF_8).trimStart('\uFEFF')
            jsonEditor.text = content
            statusLabel.text = I18n.t("import.dialog.fileLoaded", vf.name, UiHelpers.formatNumber(sizeBytes))
            statusLabel.foreground = UiHelpers.COLOR_BLUE_FG
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                t.message ?: t.javaClass.simpleName,
                I18n.t("import.dialog.title")
            )
        }
    }

    private fun handleFormatJson() {
        val res = JsonImportEngine.formatPrettyJson(jsonEditor.text)
        res.onSuccess { pretty ->
            jsonEditor.text = pretty
            statusLabel.text = I18n.t("import.dialog.formatSuccess")
            statusLabel.foreground = UiHelpers.COLOR_GREEN_FG
        }.onFailure { err ->
            statusLabel.text = err.message ?: err.javaClass.simpleName
            statusLabel.foreground = UiHelpers.COLOR_RED_FG
        }
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        loadFileBtn.isEnabled = !busy
        formatJsonBtn.isEnabled = !busy
        stripIdCheck.isEnabled = !busy
        dryRunBtn.isEnabled = !busy
        importBtn.isEnabled = !busy
        if (message != null) {
            statusLabel.text = message
            statusLabel.foreground = UiHelpers.COLOR_BLUE_FG
        }
    }

    private fun runValidationAndOptionalImport(dryRunOnly: Boolean) {
        val rawText = jsonEditor.text
        val stripIds = stripIdCheck.isSelected

        setBusy(true, I18n.t("import.dialog.validating"))

        workerFutureRef.getAndSet(null)?.cancel(true)
        val future = AppExecutorUtil.getAppExecutorService().submit {
            try {
                val preflight = JsonImportEngine.runPreflightValidation(
                    connectionManager = connectionManager,
                    profile = profile,
                    database = database,
                    collection = collection,
                    rawText = rawText,
                    stripIds = stripIds
                )

                if (!preflight.isValid) {
                    ApplicationManager.getApplication().invokeLater({
                        if (isDisposed) return@invokeLater
                        setBusy(false)
                        statusLabel.text = I18n.t("import.dialog.abortedSummary", preflight.issues.size)
                        statusLabel.foreground = UiHelpers.COLOR_RED_FG
                        ImportValidationErrorDialog(project, database, collection, preflight.issues).show()
                    }, ModalityState.any())
                    return@submit
                }

                if (dryRunOnly) {
                    ApplicationManager.getApplication().invokeLater({
                        if (isDisposed) return@invokeLater
                        setBusy(false)
                        val passMsg = I18n.t("import.dialog.dryRunPassed", preflight.validDocuments.size)
                        statusLabel.text = passMsg
                        statusLabel.foreground = UiHelpers.COLOR_GREEN_FG
                        Messages.showInfoMessage(project, passMsg, I18n.t("import.dialog.dryRunTitle"))
                    }, ModalityState.any())
                    return@submit
                }

                // Passed all 4 Pre-Flight stages -> execute chunked insertMany
                ApplicationManager.getApplication().invokeLater({
                    if (!isDisposed) {
                        statusLabel.text = I18n.t("import.dialog.inserting", preflight.validDocuments.size)
                        statusLabel.foreground = UiHelpers.COLOR_BLUE_FG
                    }
                }, ModalityState.any())

                val insertedCount = JsonImportEngine.executeChunkedImport(
                    connectionManager = connectionManager,
                    profile = profile,
                    database = database,
                    collection = collection,
                    documents = preflight.validDocuments
                )

                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    setBusy(false)
                    UiHelpers.notify(project, I18n.t("import.dialog.successToast", insertedCount))
                    onImportSucceeded()
                    close(OK_EXIT_CODE)
                }, ModalityState.any())
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    setBusy(false)
                    statusLabel.text = msg
                    statusLabel.foreground = UiHelpers.COLOR_RED_FG
                    Messages.showErrorDialog(
                        project,
                        I18n.t("import.dialog.execError", msg),
                        I18n.t("import.dialog.title")
                    )
                }, ModalityState.any())
            }
        }
        workerFutureRef.set(future)
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(6)
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
        }

        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Target Connection / DB / Collection badge row
        val targetRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel(I18n.t("import.dialog.targetLabel")).apply { font = font.deriveFont(Font.BOLD) })
            add(
                UiHelpers.createBadgeLabel(
                    "${profile.displayName}  /  $database.$collection",
                    profile.colorTag.bgColor() ?: UiHelpers.COLOR_BLUE_BG,
                    profile.colorTag.fgColor() ?: UiHelpers.COLOR_BLUE_FG
                )
            )
            if (JsonImportEngine.isDocumentDbProfile(profile)) {
                add(
                    UiHelpers.createBadgeLabel(
                        "AWS DocumentDB Mode",
                        UiHelpers.COLOR_ORANGE_BG,
                        UiHelpers.COLOR_ORANGE_FG
                    )
                )
            }
        }
        topStack.add(targetRow)

        if (profile.colorTag == ConnectionColorTag.RED) {
            topStack.add(Box.createVerticalStrut(JBUI.scale(6)))
            val prodBanner = JBLabel(
                I18n.t("import.dialog.prodWarningBanner"),
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
            topStack.add(prodBanner)
        }

        topStack.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Toolbar row
        val toolbar = JPanel(BorderLayout(6, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            val leftBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(loadFileBtn)
                add(formatJsonBtn)
            }
            add(leftBtns, BorderLayout.WEST)
            add(stripIdCheck, BorderLayout.EAST)
        }
        topStack.add(toolbar)
        topStack.add(Box.createVerticalStrut(JBUI.scale(4)))

        val hintLabel = JBLabel(I18n.t("import.dialog.formatHint")).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = font.deriveFont(font.size2D - 1f)
        }
        topStack.add(hintLabel)

        root.add(topStack, BorderLayout.NORTH)
        root.add(jsonEditor, BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        return root
    }

    override fun createSouthPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBUI.CurrentTheme.ToolWindow.borderColor()),
                JBUI.Borders.empty(8, 4, 4, 4)
            )
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(dryRunBtn)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(importBtn)
                add(cancelBtn)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    override fun dispose() {
        workerFutureRef.getAndSet(null)?.cancel(true)
        super.dispose()
    }

    /**
     * Detailed Pre-Flight Validation Failure Alert listing every detected issue in a table with clipboard export.
     */
    private class ImportValidationErrorDialog(
        private val project: Project,
        database: String,
        collection: String,
        private val issues: List<ImportValidationIssue>
    ) : DialogWrapper(project) {

        init {
            title = I18n.t("import.errDialog.title", "$database.$collection")
            setOKButtonText(I18n.t("index.dialog.close"))
            init()
        }

        override fun isResizable(): Boolean = true

        override fun createCenterPanel(): JComponent {
            val root = JPanel(BorderLayout(0, 8)).apply {
                border = JBUI.Borders.empty(6)
                preferredSize = Dimension(JBUI.scale(820), JBUI.scale(420))
            }

            val banner = JBLabel(
                I18n.t("import.errDialog.header", issues.size),
                AllIcons.General.Error,
                JBLabel.LEFT
            ).apply {
                isOpaque = true
                background = UiHelpers.COLOR_RED_BG
                foreground = UiHelpers.COLOR_RED_FG
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(8, 10)
            }

            val model = object : DefaultTableModel(
                arrayOf(
                    I18n.t("import.errDialog.colCategory"),
                    I18n.t("import.errDialog.colDoc"),
                    I18n.t("import.errDialog.colField"),
                    I18n.t("import.errDialog.colExpected"),
                    I18n.t("import.errDialog.colActualDetail")
                ),
                0
            ) {
                override fun isCellEditable(row: Int, column: Int): Boolean = false
            }

            for (issue in issues) {
                model.addRow(
                    arrayOf(
                        "[${I18n.t(issue.category.i18nKey)}]",
                        issue.docLabel(),
                        issue.fieldPath,
                        issue.expected,
                        "${issue.actual} — ${issue.detail}"
                    )
                )
            }

            val table = JBTable(model).apply {
                setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                rowHeight = JBUI.scale(26)
                autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
                columnModel.getColumn(0).preferredWidth = JBUI.scale(130)
                columnModel.getColumn(1).preferredWidth = JBUI.scale(82)
                columnModel.getColumn(2).preferredWidth = JBUI.scale(130)
                columnModel.getColumn(3).preferredWidth = JBUI.scale(155)
                columnModel.getColumn(4).preferredWidth = JBUI.scale(310)
                columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
                    override fun getTableCellRendererComponent(
                        table: JTable?,
                        value: Any?,
                        isSelected: Boolean,
                        hasFocus: Boolean,
                        row: Int,
                        column: Int
                    ): Component {
                        val lbl = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                        if (!isSelected) {
                            val cat = issues.getOrNull(row)?.category
                            lbl.foreground = when (cat) {
                                ImportErrorCategory.DUPLICATE_ID -> UiHelpers.COLOR_ORANGE_FG
                                else -> UiHelpers.COLOR_RED_FG
                            }
                            lbl.font = lbl.font.deriveFont(Font.BOLD)
                        }
                        return lbl
                    }
                }
            }

            root.add(banner, BorderLayout.NORTH)
            root.add(JBScrollPane(table), BorderLayout.CENTER)
            return root
        }

        override fun createSouthPanel(): JComponent {
            val copyBtn = JButton(I18n.t("import.errDialog.copyBtn"), AllIcons.Actions.Copy).apply {
                margin = JBUI.insets(4, 10)
                addActionListener {
                    val report = buildString {
                        appendLine(I18n.t("import.errDialog.header", issues.size))
                        appendLine()
                        issues.forEachIndexed { idx, issue ->
                            appendLine(
                                "${idx + 1}. [${I18n.t(issue.category.i18nKey)}] ${issue.docLabel()} | field=${issue.fieldPath} | expected=${issue.expected} | actual=${issue.actual} | ${issue.detail}"
                            )
                        }
                    }
                    UiHelpers.copyToClipboard(report)
                    UiHelpers.notify(project, I18n.t("import.errDialog.copied"), NotificationType.INFORMATION)
                }
            }
            val closeBtn = JButton(I18n.t("index.dialog.close")).apply {
                margin = JBUI.insets(4, 12)
                addActionListener { close(OK_EXIT_CODE) }
            }
            return JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(6)
                add(copyBtn, BorderLayout.WEST)
                add(closeBtn, BorderLayout.EAST)
            }
        }
    }

    companion object {
        /**
         * Entry guard: blocks Read-Only profiles immediately with a warning alert without opening the dialog.
         */
        fun openIfWritable(
            project: Project,
            connectionManager: MongoConnectionManager,
            profile: MongoConnectionProfile,
            database: String,
            collection: String,
            onImportSucceeded: () -> Unit
        ) {
            if (connectionManager.isEffectivelyReadOnly(profile)) {
                Messages.showWarningDialog(
                    project,
                    I18n.t("import.dialog.readOnlyBlocked", profile.displayName),
                    I18n.t("import.dialog.title")
                )
                return
            }
            ImportJsonDialog(
                project = project,
                connectionManager = connectionManager,
                profile = profile,
                database = database,
                collection = collection,
                onImportSucceeded = onImportSucceeded
            ).show()
        }
    }
}
