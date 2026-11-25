package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.SavedQuery
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class FindQueryPanel(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val savedQueryStore: SavedQueryStore,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val fieldSuggestionsSupplier: () -> List<String>,
    private val onFieldsDiscovered: (List<String>) -> Unit,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onReadOnlyChanged: () -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val limitPresets = arrayOf(20, 50, 100, 500, 1000)

    private val filterLabel = JBLabel(I18n.t("find.filter"))
    private val projectToggleBtn = JButton("${I18n.t("find.project")} ▸")
    private val sortLabel = JBLabel(I18n.t("find.sort"))
    private val limitLabel = JBLabel(I18n.t("find.limit"))
    private val skipLabel = JBLabel(I18n.t("find.skip"))

    private val filterEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "{}",
        multiLine = false,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executeFind() }
    ).apply { setPreferredSizeHeight(30) }

    private val projectEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "{}",
        multiLine = false,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executeFind() }
    ).apply { setPreferredSizeHeight(28) }

    private val sortEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "{}",
        multiLine = false,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executeFind() }
    ).apply { setPreferredSizeHeight(28) }

    private val limitDecreaseBtn = JButton("-").apply {
        preferredSize = Dimension(32, 26)
        margin = JBUI.insets(0, 4)
        toolTipText = I18n.t("find.limitDecreaseTooltip")
    }
    private val limitCombo = ComboBox(limitPresets).apply {
        selectedItem = 50
        preferredSize = Dimension(78, 26)
    }
    private val limitIncreaseBtn = JButton("+").apply {
        preferredSize = Dimension(32, 26)
        margin = JBUI.insets(0, 4)
        toolTipText = I18n.t("find.limitIncreaseTooltip")
    }
    private val skipSpinner = JSpinner(SpinnerNumberModel(0, 0, Int.MAX_VALUE, 10)).apply {
        preferredSize = Dimension(82, 26)
    }

    private val runBtn = JButton(I18n.t("find.run")).apply {
        toolTipText = I18n.t("find.runTooltip")
    }
    private val resetBtn = JButton(I18n.t("find.reset"))
    private val smartInBtn = JButton(I18n.t("find.smartIn")).apply {
        toolTipText = I18n.t("find.smartInTooltip")
    }
    private val copyShellBtn = JButton(I18n.t("find.copyShell")).apply {
        toolTipText = I18n.t("find.copyShellTooltip")
    }
    private val savedQueriesBtn = JButton(I18n.t("find.savedQueries"))

    private val projectRowPanel = JPanel(BorderLayout(6, 0))
    private var projectExpanded = false

    private val resultViewer = ResultViewerPanel(
        project = project,
        connectionManager = connectionManager,
        profile = profile,
        database = database,
        collection = collection,
        onRequestRefresh = { executeFind() },
        onPrevPage = {
            val lim = currentLimit()
            val prevSkip = (currentSkip() - lim).coerceAtLeast(0)
            skipSpinner.value = prevSkip
            executeFind()
        },
        onNextPage = {
            val lim = currentLimit()
            skipSpinner.value = currentSkip() + lim
            executeFind()
        },
        onReadOnlyChanged = onReadOnlyChanged
    )

    init {
        Disposer.register(this, resultViewer)
        buildQueryBar()
        add(resultViewer, BorderLayout.CENTER)
        I18n.addListener(this) { refreshTexts() }
    }

    private fun buildQueryBar() {
        val queryPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 6, 8)
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 4)
        }

        // Row 0: Filter
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        queryPanel.add(filterLabel, gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0; gbc.gridwidth = 3
        queryPanel.add(filterEditor, gbc)

        // Row 1: Sort + Limit + Skip
        gbc.gridwidth = 1
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        queryPanel.add(sortLabel, gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.6
        queryPanel.add(sortEditor, gbc)

        val pagingPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(limitLabel)
            add(limitDecreaseBtn)
            add(limitCombo)
            add(limitIncreaseBtn)
            add(skipLabel)
            add(skipSpinner)
        }
        gbc.gridx = 2; gbc.gridy = 1; gbc.weightx = 0.0; gbc.gridwidth = 2
        queryPanel.add(pagingPanel, gbc)

        // Row 2: Collapsible Project editor
        projectRowPanel.apply {
            add(projectEditor, BorderLayout.CENTER)
            isVisible = false
        }
        gbc.gridwidth = 1
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        projectToggleBtn.margin = JBUI.insets(1, 6)
        queryPanel.add(projectToggleBtn, gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0; gbc.gridwidth = 3
        queryPanel.add(projectRowPanel, gbc)

        // Row 3: Action buttons
        val actionBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(runBtn)
            add(resetBtn)
            add(smartInBtn)
            add(copyShellBtn)
            add(savedQueriesBtn)
        }
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 1.0; gbc.gridwidth = 4
        queryPanel.add(actionBar, gbc)

        wireListeners()

        val wrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            add(queryPanel, BorderLayout.CENTER)
        }
        add(wrapper, BorderLayout.NORTH)
    }

    private fun wireListeners() {
        projectToggleBtn.addActionListener {
            projectExpanded = !projectExpanded
            projectRowPanel.isVisible = projectExpanded
            updateProjectToggleText()
            revalidate()
            repaint()
        }

        limitDecreaseBtn.addActionListener {
            val idx = limitCombo.selectedIndex
            if (idx > 0) {
                limitCombo.selectedIndex = idx - 1
                executeFind()
            }
        }

        limitIncreaseBtn.addActionListener {
            val idx = limitCombo.selectedIndex
            if (idx in 0 until limitPresets.lastIndex) {
                limitCombo.selectedIndex = idx + 1
                executeFind()
            }
        }

        runBtn.addActionListener { executeFind() }

        resetBtn.addActionListener {
            filterEditor.text = "{}"
            projectEditor.text = "{}"
            sortEditor.text = "{}"
            limitCombo.selectedItem = 50
            skipSpinner.value = 0
            executeFind()
        }

        smartInBtn.addActionListener {
            val dlg = SmartInDialog(project)
            if (dlg.showAndGet()) {
                filterEditor.text = dlg.getGeneratedQuery()
                if (dlg.runImmediately) {
                    skipSpinner.value = 0
                    executeFind()
                }
            }
        }

        copyShellBtn.addActionListener {
            val cmd = MongoShellNormalizer.toMongoshFindCommand(
                collection = collection,
                filter = filterEditor.text,
                projection = projectEditor.text,
                sort = sortEditor.text,
                skip = currentSkip(),
                limit = currentLimit()
            )
            UiHelpers.copyToClipboard(cmd)
            UiHelpers.notify(project, I18n.t("result.copied"))
        }

        savedQueriesBtn.addActionListener { showSavedQueriesMenu() }
    }

    private fun currentLimit(): Int = (limitCombo.selectedItem as? Int) ?: 50

    private fun currentSkip(): Int = (skipSpinner.value as? Int) ?: 0

    fun executeFind() {
        val filterRaw = filterEditor.text
        val projRaw = if (projectExpanded) projectEditor.text else projectEditor.text
        val sortRaw = sortEditor.text
        val limit = currentLimit()
        val skip = currentSkip()

        runBtn.isEnabled = false
        onStatusUpdate(I18n.t("header.status.querying"), false)

        object : Task.Backgroundable(project, "Finding documents in $database.$collection...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = connectionManager.executeFind(
                        profile = profile,
                        database = database,
                        collection = collection,
                        filterRaw = filterRaw,
                        projectionRaw = projRaw,
                        sortRaw = sortRaw,
                        skip = skip,
                        limit = limit
                    )

                    savedQueryStore.recordHistory(
                        SavedQuery(
                            id = UUID.randomUUID().toString(),
                            name = filterRaw.trim().take(50).ifEmpty { "{}" },
                            profileId = profile.id,
                            database = database,
                            collection = collection,
                            mode = "FIND",
                            filter = filterRaw,
                            projection = projRaw,
                            sort = sortRaw,
                            limit = limit,
                            skip = skip,
                            isHistory = true
                        )
                    )

                    ApplicationManager.getApplication().invokeLater {
                        runBtn.isEnabled = true
                        if (result.sampledFields.isNotEmpty()) {
                            onFieldsDiscovered(result.sampledFields)
                        }
                        resultViewer.updateResults(result, skip = skip, limit = limit)
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
                        Messages.showErrorDialog(project, msg, I18n.t("find.run"))
                    }
                }
            }
        }.queue()
    }

    private fun showSavedQueriesMenu() {
        val menu = JPopupMenu()
        menu.add(JMenuItem(I18n.t("find.savedQueries.save")).apply {
            addActionListener {
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
                            mode = "FIND",
                            filter = filterEditor.text,
                            projection = projectEditor.text,
                            sort = sortEditor.text,
                            limit = currentLimit(),
                            skip = currentSkip(),
                            isHistory = false
                        )
                    )
                    UiHelpers.notify(project, I18n.t("find.savedQueries.savedToast", name))
                }
            }
        })

        menu.addSeparator()
        menu.add(JMenuItem("— ${I18n.t("find.savedQueries.savedHeader")} —").apply { isEnabled = false })
        val bookmarks = savedQueryStore.getBookmarks(collection, "FIND")
        if (bookmarks.isEmpty()) {
            menu.add(JMenuItem(I18n.t("find.savedQueries.empty")).apply { isEnabled = false })
        } else {
            bookmarks.take(15).forEach { q ->
                menu.add(JMenuItem(q.name).apply {
                    addActionListener { applySavedQuery(q) }
                })
            }
        }

        menu.addSeparator()
        menu.add(JMenuItem("— ${I18n.t("find.savedQueries.historyHeader")} —").apply { isEnabled = false })
        val history = savedQueryStore.getHistory(collection, "FIND")
        if (history.isEmpty()) {
            menu.add(JMenuItem(I18n.t("find.savedQueries.empty")).apply { isEnabled = false })
        } else {
            history.take(10).forEach { q ->
                menu.add(JMenuItem(q.name).apply {
                    addActionListener { applySavedQuery(q) }
                })
            }
        }

        menu.show(savedQueriesBtn, 0, savedQueriesBtn.height)
    }

    private fun applySavedQuery(q: SavedQuery) {
        filterEditor.text = q.filter
        projectEditor.text = q.projection
        sortEditor.text = q.sort
        limitCombo.selectedItem = if (q.limit in limitPresets) q.limit else 50
        skipSpinner.value = q.skip.coerceAtLeast(0)
        if (q.projection.trim().isNotEmpty() && q.projection.trim() != "{}") {
            projectExpanded = true
            projectRowPanel.isVisible = true
            updateProjectToggleText()
        }
        executeFind()
    }

    private fun updateProjectToggleText() {
        projectToggleBtn.text = if (projectExpanded) "${I18n.t("find.project")} ▾" else "${I18n.t("find.project")} ▸"
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            filterLabel.text = I18n.t("find.filter")
            sortLabel.text = I18n.t("find.sort")
            limitLabel.text = I18n.t("find.limit")
            skipLabel.text = I18n.t("find.skip")
            updateProjectToggleText()
            runBtn.text = I18n.t("find.run")
            runBtn.toolTipText = I18n.t("find.runTooltip")
            resetBtn.text = I18n.t("find.reset")
            smartInBtn.text = I18n.t("find.smartIn")
            smartInBtn.toolTipText = I18n.t("find.smartInTooltip")
            copyShellBtn.text = I18n.t("find.copyShell")
            copyShellBtn.toolTipText = I18n.t("find.copyShellTooltip")
            savedQueriesBtn.text = I18n.t("find.savedQueries")
            limitDecreaseBtn.toolTipText = I18n.t("find.limitDecreaseTooltip")
            limitIncreaseBtn.toolTipText = I18n.t("find.limitIncreaseTooltip")
        }
    }

    override fun dispose() {
        // Child resultViewer disposed via Disposer
    }
}
