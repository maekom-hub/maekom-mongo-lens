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
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import com.maekom.mongolens.mongo.SmartInValueType
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Path
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class FindQueryPanel(
    private val project: Project,
    private val projectBasePath: Path?,
    private val connectionManager: MongoConnectionManager,
    private val savedQueryStore: SavedQueryStore,
    private val cryptoStore: CryptoConfigStore,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val fieldSuggestionsSupplier: () -> List<String>,
    private val fieldTypesSupplier: () -> Map<String, SmartInValueType>,
    private val onFieldsDiscovered: (List<String>, Map<String, SmartInValueType>) -> Unit,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onReadOnlyChanged: () -> Unit,
    private val onConvertToShell: (String) -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val limitPresets = arrayOf(20, 50, 100, 500, 1000)
    private val unifiedInputHeight = JBUI.scale(32)

    private val filterLabel = JBLabel(I18n.t("find.filter")).apply {
        preferredSize = Dimension(JBUI.scale(42), unifiedInputHeight)
    }
    private val projectLabel = JBLabel(I18n.t("find.project")).apply {
        preferredSize = Dimension(JBUI.scale(42), unifiedInputHeight)
    }
    private val projectToggleBtn = JButton(I18n.t("find.projectAdd")).apply {
        margin = JBUI.insets(2, 6)
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(JBUI.scale(72)), unifiedInputHeight)
    }
    private val sortLabel = JBLabel(I18n.t("find.sort")).apply {
        preferredSize = Dimension(JBUI.scale(42), unifiedInputHeight)
    }
    private val limitLabel = JBLabel(I18n.t("find.limit"))
    private val skipLabel = JBLabel(I18n.t("find.skip"))

    /**
     * Common Query Input Factory:
     * Filter (1-line default), Sort, and Project share identical 32px height, JBUI.insets(5, 8) padding,
     * vertical alignment, and gray placeholder hints (empty text safely resolves to "{}" on execute).
     */
    private fun createUnifiedQueryEditor(
        autoGrow: Boolean,
        placeholder: String,
        completionMode: MongoJsonEditor.CompletionMode
    ): MongoJsonEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "",
        multiLine = false,
        autoGrow = autoGrow,
        unifiedInputStyle = true,
        placeholderText = placeholder,
        completionMode = completionMode,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executeFind() },
        onAutoHeightChanged = if (autoGrow) ({ revalidateHierarchy() }) else null
    )

    private val filterEditor = createUnifiedQueryEditor(
        autoGrow = true,
        placeholder = "Type a Query: { field: 'value' }",
        completionMode = MongoJsonEditor.CompletionMode.FILTER
    )

    private val projectEditor = createUnifiedQueryEditor(
        autoGrow = false,
        placeholder = "{ field: 1 }",
        completionMode = MongoJsonEditor.CompletionMode.FILTER
    )

    private val sortEditor = createUnifiedQueryEditor(
        autoGrow = false,
        placeholder = "{ field: -1 }",
        completionMode = MongoJsonEditor.CompletionMode.SORT
    )

    private val limitDecreaseBtn = JButton("-").apply {
        preferredSize = Dimension(26, JBUI.scale(28))
        margin = JBUI.insets(1, 3)
        toolTipText = I18n.t("find.limitDecreaseTooltip")
    }
    private val limitCombo = ComboBox(limitPresets).apply {
        selectedItem = 50
        preferredSize = Dimension(68, JBUI.scale(28))
    }
    private val limitIncreaseBtn = JButton("+").apply {
        preferredSize = Dimension(26, JBUI.scale(28))
        margin = JBUI.insets(1, 3)
        toolTipText = I18n.t("find.limitIncreaseTooltip")
    }
    private val skipSpinner = JSpinner(SpinnerNumberModel(0, 0, Int.MAX_VALUE, 10)).apply {
        preferredSize = Dimension(64, JBUI.scale(28))
    }

    // Row 1 Right Block (Top-aligned when Filter auto-grows)
    private val runBtn = JButton(I18n.t("find.run")).apply {
        margin = JBUI.insets(2, 8)
        font = font.deriveFont(Font.BOLD)
        foreground = UiHelpers.COLOR_GREEN_FG
        toolTipText = I18n.t("find.runTooltip")
    }
    private val resetBtn = JButton(I18n.t("find.reset")).apply {
        margin = JBUI.insets(2, 6)
    }

    // Row 2 Right Block
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
        border = JBUI.Borders.empty(2, 0, 2, 0)
        add(projectLabel, BorderLayout.WEST)
        add(projectEditor, BorderLayout.CENTER)
        isVisible = false
    }
    private var projectExpanded = false

    private val queryBarWrapper = JPanel(BorderLayout())

    private val resultViewer = ResultViewerPanel(
        project = project,
        projectBasePath = projectBasePath,
        connectionManager = connectionManager,
        cryptoStore = cryptoStore,
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

    private fun buildCompactQueryBar() {
        val grid = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(4, 6, 4, 6)
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 2)
        }

        // Row 0 Left: [Filter label] + [Unified 32px Auto-Grow Filter Editor] + [+ Project]
        val filterLabelWrap = JPanel(BorderLayout()).apply {
            add(filterLabel, BorderLayout.NORTH)
        }
        val projectToggleWrap = JPanel(BorderLayout()).apply {
            add(projectToggleBtn, BorderLayout.NORTH)
        }
        val row1Left = JPanel(BorderLayout(4, 0)).apply {
            add(filterLabelWrap, BorderLayout.WEST)
            add(filterEditor, BorderLayout.CENTER)
            add(projectToggleWrap, BorderLayout.EAST)
        }

        // Row 0 Right: [▶ 조회] [초기화] top-aligned
        val row1Right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            add(runBtn)
            add(resetBtn)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.NORTH
        grid.add(row1Left, gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.NORTH
        grid.add(row1Right, gbc)

        // Row 1 (Collapsible): Unified 32px Project input row
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 1.0; gbc.gridwidth = 2; gbc.anchor = GridBagConstraints.CENTER
        grid.add(projectRowContainer, gbc)
        gbc.gridwidth = 1

        // Row 2 Left: Unified 32px Sort + inline Limit/Skip
        val inlinePaging = JPanel(FlowLayout(FlowLayout.LEFT, 3, 2)).apply {
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
        val row2Right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            add(smartInBtn)
            add(toShellBtn)
            add(savedQueriesBtn)
        }

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 1.0
        grid.add(row2Left, gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.0
        grid.add(row2Right, gbc)

        wireListeners()

        queryBarWrapper.apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            add(grid, BorderLayout.CENTER)
        }
        add(queryBarWrapper, BorderLayout.NORTH)
    }

    private fun revalidateHierarchy() {
        queryBarWrapper.revalidate()
        queryBarWrapper.repaint()
        revalidate()
        repaint()
        parent?.let {
            it.revalidate()
            it.repaint()
        }
        SwingUtilities.getAncestorOfClass(CollectionWorkspaceTab::class.java, this)?.let { ancestor ->
            ancestor.revalidate()
            ancestor.repaint()
        }
    }

    private fun wireListeners() {
        projectToggleBtn.addActionListener {
            projectExpanded = !projectExpanded
            projectRowContainer.isVisible = projectExpanded
            updateProjectToggleText()
            revalidateHierarchy()
        }

        limitCombo.addActionListener {
            resultViewer.resetTableBatchDecryption()
        }

        skipSpinner.addChangeListener {
            resultViewer.resetTableBatchDecryption()
        }

        limitDecreaseBtn.addActionListener {
            val idx = limitCombo.selectedIndex
            if (idx > 0) {
                resultViewer.resetTableBatchDecryption()
                limitCombo.selectedIndex = idx - 1
                executeFind()
            }
        }

        limitIncreaseBtn.addActionListener {
            val idx = limitCombo.selectedIndex
            if (idx in 0 until limitPresets.lastIndex) {
                resultViewer.resetTableBatchDecryption()
                limitCombo.selectedIndex = idx + 1
                executeFind()
            }
        }

        runBtn.addActionListener { executeFind() }

        resetBtn.addActionListener {
            resultViewer.resetTableBatchDecryption()
            filterEditor.text = ""
            projectEditor.text = ""
            sortEditor.text = ""
            limitCombo.selectedItem = 50
            skipSpinner.value = 0
            executeFind()
        }

        smartInBtn.addActionListener {
            val filterKeys = MongoShellNormalizer.extractFilterFieldKeys(filterEditor.safeText())
            val dlg = SmartInDialog(
                project = project,
                filterFieldKeys = filterKeys,
                schemaFieldKeys = fieldSuggestionsSupplier(),
                schemaFieldTypes = fieldTypesSupplier()
            )
            if (dlg.showAndGet()) {
                filterEditor.text = dlg.getGeneratedQuery()
                if (dlg.runImmediately) {
                    skipSpinner.value = 0
                    executeFind()
                }
            }
        }

        toShellBtn.addActionListener {
            onConvertToShell(buildDirectShellFindCommand())
        }

        savedQueriesBtn.addActionListener { showSavedQueriesPopup() }
    }

    private fun buildDirectShellFindCommand(): String {
        val f = filterEditor.safeText().ifEmpty { "{}" }
        val p = projectEditor.safeText()
        val s = sortEditor.safeText()
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
        resultViewer.resetTableBatchDecryption()
        filterEditor.dismissPopup()
        sortEditor.dismissPopup()
        projectEditor.dismissPopup()

        // Blank input safely falls back to "{}" — placeholder text is NEVER sent as a query
        val filterRaw = filterEditor.safeText().ifEmpty { "{}" }
        val projRaw = projectEditor.safeText().ifEmpty { "{}" }
        val sortRaw = sortEditor.safeText().ifEmpty { "{}" }
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
                            name = filterRaw.take(50).ifEmpty { "{}" },
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
                            onFieldsDiscovered(result.sampledFields, result.sampledFieldTypes)
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
                            filter = filterEditor.safeText().ifEmpty { "{}" },
                            projection = projectEditor.safeText().ifEmpty { "{}" },
                            sort = sortEditor.safeText().ifEmpty { "{}" },
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
        val f = (q.filter as String?)?.trim().orEmpty()
        val p = (q.projection as String?)?.trim().orEmpty()
        val s = (q.sort as String?)?.trim().orEmpty()
        filterEditor.text = if (f == "{}") "" else f
        projectEditor.text = if (p == "{}") "" else p
        sortEditor.text = if (s == "{}") "" else s
        limitCombo.selectedItem = if (q.limit in limitPresets) q.limit else 50
        skipSpinner.value = q.skip.coerceAtLeast(0)
        if (p.isNotEmpty() && p != "{}") {
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
