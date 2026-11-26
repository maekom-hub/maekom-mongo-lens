package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBSplitter
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.SavedQuery
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.config.VariableStore
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellExecutor
import com.maekom.mongolens.mongo.VariableResolver
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

class MongoShellPanel(
    private val project: Project,
    private val projectBasePath: java.nio.file.Path?,
    private val connectionManager: MongoConnectionManager,
    private val savedQueryStore: SavedQueryStore,
    private val variableStore: VariableStore,
    private val cryptoStore: CryptoConfigStore,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val collectionsSupplier: () -> List<String>,
    private val fieldSuggestionsSupplier: () -> List<String>,
    private val onFieldsDiscovered: (List<String>) -> Unit,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onReadOnlyChanged: () -> Unit,
    private val diffBaselineState: DiffBaselineState? = null
) : JPanel(BorderLayout()), Disposable {

    private var autocomplete: MongoShellAutocomplete? = null

    private val shellEditor = object : EditorTextField(
        EditorFactory.getInstance().createDocument(MongoShellTemplates.defaultStarter(collection)),
        project,
        resolveShellFileType(),
        false,
        false
    ) {
        override fun createEditor(): EditorEx {
            val ed = super.createEditor()
            ed.settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = true
                isAutoCodeFoldingEnabled = true
                isUseSoftWraps = true
                isRightMarginShown = false
                additionalLinesCount = 2
            }
            autocomplete = MongoShellAutocomplete(
                project = project,
                editor = ed,
                collectionsSupplier = collectionsSupplier,
                fieldsSupplier = fieldSuggestionsSupplier,
                userVariablesSupplier = { variableStore.loadAll() },
                onExecuteShortcut = { executeShell() }
            )
            return ed
        }
    }

    private val runBtn = JButton(I18n.t("shell.run")).apply {
        margin = JBUI.insets(2, 8)
        font = font.deriveFont(Font.BOLD)
        foreground = UiHelpers.COLOR_GREEN_FG
        toolTipText = I18n.t("shell.runTooltip")
    }
    private val templateBtn = JButton(I18n.t("shell.templates")).apply { margin = JBUI.insets(2, 6) }
    private val cheatsheetToggle = JToggleButton(I18n.t("shell.cheatsheet"), false).apply { margin = JBUI.insets(2, 6) }
    private val previewVarsBtn = JButton(I18n.t("shell.previewVars")).apply { margin = JBUI.insets(2, 6) }
    private val variablesBtn = JButton(I18n.t("shell.variables")).apply { margin = JBUI.insets(2, 6) }
    private val savedQueriesBtn = JButton(I18n.t("find.savedQueries")).apply { margin = JBUI.insets(2, 6) }

    private val cheatsheetPanel = MongoShellCheatsheetPanel(
        project = project,
        collectionName = collection,
        parentDisposable = this,
        onInsertSnippet = { snippet -> insertSnippetSmartly(snippet) }
    )

    private val editorSplitter = OnePixelSplitter(false, 0.68f).apply {
        firstComponent = shellEditor
        secondComponent = null
    }

    private val resultViewer = ResultViewerPanel(
        project = project,
        projectBasePath = projectBasePath,
        connectionManager = connectionManager,
        cryptoStore = cryptoStore,
        profile = profile,
        database = database,
        collection = collection,
        onRequestRefresh = { executeShell() },
        onReadOnlyChanged = onReadOnlyChanged,
        diffBaselineState = diffBaselineState
    )

    init {
        Disposer.register(this, resultViewer)

        val toolbar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(runBtn)
                add(templateBtn)
                add(cheatsheetToggle)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
                add(previewVarsBtn)
                add(variablesBtn)
                add(savedQueriesBtn)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        val topShellPane = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(editorSplitter, BorderLayout.CENTER)
        }

        val mainSplitter = JBSplitter(true, 0.42f).apply {
            firstComponent = topShellPane
            secondComponent = resultViewer
        }
        add(mainSplitter, BorderLayout.CENTER)

        wireListeners()
        I18n.addListener(this) { refreshTexts() }
    }

    private fun wireListeners() {
        runBtn.addActionListener { executeShell() }
        templateBtn.addActionListener { showTemplateMenu() }

        cheatsheetToggle.addActionListener {
            editorSplitter.secondComponent = if (cheatsheetToggle.isSelected) cheatsheetPanel else null
            editorSplitter.proportion = 0.68f
            editorSplitter.revalidate()
            editorSplitter.repaint()
        }

        previewVarsBtn.addActionListener {
            val vars = variableStore.loadAll()
            val resolved = VariableResolver.resolve(shellEditor.text, vars)
            VariablePreviewDialog(project, resolved).show()
        }

        variablesBtn.addActionListener {
            val dlg = ShellVariablesDialog(project, variableStore.loadAll())
            if (dlg.showAndGet()) {
                variableStore.saveAll(dlg.getVariables())
            }
        }

        savedQueriesBtn.addActionListener {
            QueryHistoryPopup.show(
                project = project,
                invoker = savedQueriesBtn,
                savedQueryStore = savedQueryStore,
                collection = collection,
                mode = "SHELL",
                onSaveCurrent = {
                    val name = Messages.showInputDialog(
                        project,
                        I18n.t("find.savedQueries.promptName"),
                        I18n.t("find.savedQueries"),
                        Messages.getQuestionIcon()
                    )?.trim()
                    if (!name.isNullOrEmpty()) {
                        savedQueryStore.saveBookmark(
                            SavedQuery(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                profileId = profile.id,
                                database = database,
                                collection = collection,
                                mode = "SHELL",
                                shellScript = shellEditor.text,
                                isHistory = false
                            )
                        )
                        UiHelpers.notify(project, I18n.t("find.savedQueries.savedToast", name))
                    }
                },
                onSelectQuery = { q ->
                    if (q.shellScript.isNotBlank()) {
                        setScriptText(q.shellScript)
                    }
                }
            )
        }
    }

    fun setScriptText(script: String) {
        shellEditor.text = script
    }

    private fun showTemplateMenu() {
        val menu = JPopupMenu()
        val isReadOnly = connectionManager.isEffectivelyReadOnly(profile)
        for (cat in MongoShellTemplates.categories()) {
            val subMenu = JMenu(I18n.t(cat.titleKey))
            for (tpl in cat.templates) {
                val label = if (tpl.isWriteOp && isReadOnly) {
                    "${MongoShellTemplates.titleOf(tpl)} [READ-ONLY 🔒]"
                } else {
                    MongoShellTemplates.titleOf(tpl)
                }
                subMenu.add(JMenuItem(label).apply {
                    if (tpl.isWriteOp && isReadOnly) {
                        isEnabled = false
                    }
                    addActionListener {
                        insertSnippetSmartly(tpl.render(collection))
                    }
                })
            }
            menu.add(subMenu)
        }
        menu.show(templateBtn, 0, templateBtn.height)
    }

    private fun insertSnippetSmartly(snippet: String) {
        val current = shellEditor.text
        if (MongoShellTemplates.isDefaultOrBlank(current, collection)) {
            shellEditor.text = snippet
            return
        }

        val editor = shellEditor.editor
        if (editor == null || editor.isDisposed) {
            shellEditor.text = "$current\n\n$snippet"
            return
        }

        val doc = editor.document
        val caretLine = editor.caretModel.logicalPosition.line
        val lineEndOffset = doc.getLineEndOffset(caretLine.coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0)))
        WriteCommandAction.runWriteCommandAction(project) {
            val toInsert = "\n\n$snippet"
            doc.insertString(lineEndOffset, toInsert)
            editor.caretModel.moveToOffset(lineEndOffset + toInsert.length)
        }
    }

    fun executeShell() {
        val script = (shellEditor.text as String?)?.trim().orEmpty()
        if (script.isEmpty()) return

        if (MongoShellExecutor.isWriteOperation(script)) {
            if (!UiHelpers.ensureWritableOrPromptUnlock(project, connectionManager, profile, onReadOnlyChanged)) {
                return
            }
        }

        val userVars = variableStore.loadAll()
        resultViewer.resetTableBatchDecryption()
        runBtn.isEnabled = false
        onStatusUpdate(I18n.t("header.status.querying"), false)

        object : Task.Backgroundable(project, "Running Mongo Shell script on $database.$collection...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MongoShellExecutor.execute(
                        connectionManager = connectionManager,
                        profile = profile,
                        database = database,
                        defaultCollection = collection,
                        rawScript = script,
                        userVariables = userVars
                    )

                    savedQueryStore.recordHistory(
                        SavedQuery(
                            id = UUID.randomUUID().toString(),
                            name = script.replace("\n", " ").trim().take(55),
                            profileId = profile.id,
                            database = database,
                            collection = collection,
                            mode = "SHELL",
                            shellScript = script,
                            isHistory = true
                        )
                    )

                    ApplicationManager.getApplication().invokeLater {
                        runBtn.isEnabled = true
                        if (result.sampledFields.isNotEmpty()) {
                            onFieldsDiscovered(result.sampledFields)
                        }
                        resultViewer.updateResults(result, skip = 0, limit = result.documents.size.coerceAtLeast(1))
                        val statusMsg = if (connectionManager.isEffectivelyReadOnly(profile)) {
                            "${I18n.t("header.status.connected", result.elapsedMs)} · ${I18n.t("header.status.readOnly")}"
                        } else {
                            I18n.t("header.status.connected", result.elapsedMs)
                        }
                        onStatusUpdate(statusMsg, false)
                    }
                } catch (t: Throwable) {
                    val msg = t.message ?: t.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        runBtn.isEnabled = true
                        onStatusUpdate(I18n.t("header.status.error", msg.take(80)), true)
                        Messages.showErrorDialog(project, msg, I18n.t("workspace.mode.shell"))
                    }
                }
            }
        }.queue()
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            runBtn.text = I18n.t("shell.run")
            runBtn.toolTipText = I18n.t("shell.runTooltip")
            templateBtn.text = I18n.t("shell.templates")
            cheatsheetToggle.text = I18n.t("shell.cheatsheet")
            previewVarsBtn.text = I18n.t("shell.previewVars")
            variablesBtn.text = I18n.t("shell.variables")
            savedQueriesBtn.text = I18n.t("find.savedQueries")
        }
    }

    override fun dispose() {
        autocomplete?.dismiss()
    }

    companion object {
        private fun resolveShellFileType(): FileType {
            val ftm = FileTypeManager.getInstance()
            val js = ftm.getFileTypeByExtension("js")
            if (js.name != "UNKNOWN") return js
            val json = ftm.getFileTypeByExtension("json")
            return if (json.name != "UNKNOWN") json else PlainTextFileType.INSTANCE
        }
    }
}
