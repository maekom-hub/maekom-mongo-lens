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
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
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
    private val onReadOnlyChanged: () -> Unit,
    private val onConvertToShell: (String) -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val limitPresets = arrayOf(20, 50, 100, 500, 1000)

    private val filterLabel = JBLabel(I18n.t("find.filter")).apply {
        preferredSize = Dimension(38, 24)
    }
    private val projectLabel = JBLabel(I18n.t("find.project")).apply {
        preferredSize = Dimension(38, 24)
    }
    private val projectToggleBtn = JButton(I18n.t("find.projectAdd")).apply {
        margin = JBUI.insets(2, 6)
    }
    private val sortLabel = JBLabel(I18n.t("find.sort")).apply {
        preferredSize = Dimension(38, 24)
    }
    private val limitLabel = JBLabel(I18n.t("find.limit"))
    private val skipLabel = JBLabel(I18n.t("find.skip"))

    private val filterEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "{}",
        multiLine = false,
        completionMode = MongoJsonEditor.CompletionMode.FILTER,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executeFind() }
    ).apply { setPreferredSizeHeight(27) }

    private val projectEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "{}",
        multiLine = false,
        completionMode = MongoJsonEditor.CompletionMode.FILTER,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executeFind() }
    ).apply { setPreferredSizeHeight(26) }

    private val sortEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "{}",
        multiLine = false,
        completionMode = MongoJsonEditor.CompletionMode.SORT,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executeFind() }
    ).apply { setPreferredSizeHeight(27) }

    private val limitDecreaseBtn = JButton("-").apply {
        preferredSize = Dimension(26, 25)
        margin = JBUI.insets(1, 3)
        toolTipText = I18n.t("find.limitDecreaseTooltip")
    }
    private val limitCombo = ComboBox(limitPresets).apply {
        selectedItem = 50
        preferredSize = Dimension(68, 25)
    }
    private val limitIncreaseBtn = JButton("+").apply {
        preferredSize = Dimension(26, 25)
        margin = JBUI.insets(1, 3)
        toolTipText = I18n.t("find.limitIncreaseTooltip")
    }
    private val skipSpinner = JSpinner(SpinnerNumberModel(0, 0, Int.MAX_VALUE, 10)).apply {
        preferredSize = Dimension(64, 25)
    }

    // Row 1 Right Block (Compact Accent)
    private val runBtn = JButton(I18n.t("find.run")).apply {
        margin = JBUI.insets(2, 8)
        font = font.deriveFont(Font.BOLD)
        foreground = UiHelpers.COLOR_GREEN_FG
        toolTipText = I18n.t("find.runTooltip")
    }
    private val resetBtn = JButton(I18n.t("find.reset")).apply {
        margin = JBUI.insets(2, 6)
    }

    // Row 2 Right Block (Compact Tools)
    private val smartInBtn = JButton(I18n.t("find.smartIn")).apply {
        margin = JBUI.insets(2, 6)
        toolTipText = I18n.t("find.smartInTooltip")
    }
    private val toShellBtn = JButton(I18n.t("find.toShell")).apply {
        margin = JBUI.insets(2, 6)
        toolTipText = I18n.t("find.toShellTooltip")
    }
    private val savedQueriesBtn = JButton(I18n.t("find.savedQueries")).apply {
        margin = JBUI.insets(2, 6)
    }

    private val projectRowContainer = JPanel(BorderLayout(4, 0)).apply {
        border = JBUI.Borders.empty(2, 2, 0, 2)
        add(projectLabel, BorderLayout.WEST)
        add(projectEditor, BorderLayout.CENTER)
        isVisible = false
    }
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
        buildCompactQueryBar()
        add(resultViewer, BorderLayout.CENTER)
        I18n.addListener(this) { refreshTexts() }
    }

    /**
     * Compact 2-row GridBagLayout maximizing vertical space for the result viewer:
     * Row 1: [Filter label + Filter editor + [+ Project]] (weightx=1.0) | [▶ 조회] [초기화] (fixed)
     * Optional Project Row (only visible when [+ Project] toggled)
     * Row 2: [Sort label + Sort editor + Limit -/combo/+ + Skip] (weightx=1.0) | [📋 Smart $in] [Shell] [⭐ 저장/기록] (fixed)
     */
    private fun buildCompactQueryBar() {
        val grid = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(3, 6, 3, 6)
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(1, 2)
        }

        // Row 0 Left: Filter + [+ Project]
        val row1Left = JPanel(BorderLayout(4, 0)).apply {
            add(filterLabel, BorderLayout.WEST)
            add(filterEditor, BorderLayout.CENTER)
            add(projectToggleBtn, BorderLayout.EAST)
        }
        // Row 0 Right: [▶ 조회] [초기화]
        val row1Right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(runBtn)
            add(resetBtn)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0
        grid.add(row1Left, gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.0
        grid.add(row1Right, gbc)

        // Row 1 (Collapsible): Project input row
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 1.0; gbc.gridwidth = 2
        grid.add(projectRowContainer, gbc)
        gbc.gridwidth = 1

        // Row 2 Left: Sort + inline Limit/Skip
        val inlinePaging = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            add(limitLabel)
            add(limitDecreaseBtn)
            add(limitCombo)
            add(limitIncreaseBtn)
            add(skipLabel)
            add(skipSpinner)
        }
        val row2Left = JPanel(BorderLayout(4, 0)).apply {
            add(sortLabel, BorderLayout.WEST)
            add(sortEditor, BorderLayout.CENTER)
            add(inlinePaging, BorderLayout.EAST)
        }
        // Row 2 Right: [📋 Smart $in] [Shell] [⭐ 저장/기록]
        val row2Right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(smartInBtn)
            add(toShellBtn)
            add(savedQueriesBtn)
        }

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 1.0
        grid.add(row2Left, gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.0
        grid.add(row2Right, gbc)

        wireListeners()

        val wrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            add(grid, BorderLayout.CENTER)
        }
        add(wrapper, BorderLayout.NORTH)
    }

    private fun wireListeners() {
        projectToggleBtn.addActionListener {
            projectExpanded = !projectExpanded
            projectRowContainer.isVisible = projectExpanded
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

        toShellBtn.addActionListener {
            val cmd = buildDirectShellFindCommand()
            onConvertToShell(cmd)
        }

        savedQueriesBtn.addActionListener { showSavedQueriesPopup() }
    }

    private fun buildDirectShellFindCommand(): String {
        val f = filterEditor.text.trim().ifEmpty { "{}" }
        val p = projectEditor.text.trim()
        val s = sortEditor.text.trim()
        val skip = currentSkip()
        val limit = currentLimit()

        val sb = StringBuilder()
        sb.append("db.").append(collection).append(".find(").append(f)
        if (p.isNotEmpty() && p != "{}") {
            sb.append(", ").append(p)
        }
        sb.append(")")
        if (s.isNotEmpty() && s != "{}") {
            sb.append(".sort(").append(s).append(")")
        }
        if (skip > 0) {
            sb.append(".skip(").append(skip).append(")")
        }
        if (limit > 0) {
            sb.append(".limit(").append(limit).append(")")
        }
        return sb.toString()
    }

    private fun currentLimit(): Int = (limitCombo.selectedItem as? Int) ?: 50

    private fun currentSkip(): Int = (skipSpinner.value as? Int) ?: 0

    fun executeFind() {
        filterEditor.dismissPopup()
        sortEditor.dismissPopup()
        projectEditor.dismissPopup()

        val filterRaw = filterEditor.text
        val projRaw = projectEditor.text
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

    private fun showSavedQueriesPopup() {
        QueryHistoryPopup.show(
            project = project,
            invoker = savedQueriesBtn,
            savedQueryStore = savedQueryStore,
            collection = collection,
            mode = "FIND",
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
            },
            onSelectQuery = { q -> applySavedQuery(q) }
        )
    }

    private fun applySavedQuery(q: SavedQuery) {
        filterEditor.text = q.filter
        projectEditor.text = q.projection
        sortEditor.text = q.sort
        limitCombo.selectedItem = if (q.limit in limitPresets) q.limit else 50
        skipSpinner.value = q.skip.coerceAtLeast(0)
        if (q.projection.trim().isNotEmpty() && q.projection.trim() != "{}") {
            projectExpanded = true
            projectRowContainer.isVisible = true
            updateProjectToggleText()
        }
        executeFind()
    }

    private fun updateProjectToggleText() {
        projectToggleBtn.text = if (projectExpanded) I18n.t("find.projectHide") else I18n.t("find.projectAdd")
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            filterLabel.text = I18n.t("find.filter")
            projectLabel.text = I18n.t("find.project")
            sortLabel.text = I18n.t("find.sort")
            limitLabel.text = I18n.t("find.limit")
            skipLabel.text = I18n.t("find.skip")
            updateProjectToggleText()
            runBtn.text = I18n.t("find.run")
            runBtn.toolTipText = I18n.t("find.runTooltip")
            resetBtn.text = I18n.t("find.reset")
            smartInBtn.text = I18n.t("find.smartIn")
            smartInBtn.toolTipText = I18n.t("find.smartInTooltip")
            toShellBtn.text = I18n.t("find.toShell")
            toShellBtn.toolTipText = I18n.t("find.toShellTooltip")
            savedQueriesBtn.text = I18n.t("find.savedQueries")
            limitDecreaseBtn.toolTipText = I18n.t("find.limitDecreaseTooltip")
            limitIncreaseBtn.toolTipText = I18n.t("find.limitIncreaseTooltip")
        }
    }

    override fun dispose() {
        filterEditor.dismissPopup()
        sortEditor.dismissPopup()
        projectEditor.dismissPopup()
    }
}
