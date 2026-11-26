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
import com.maekom.mongolens.mongo.JoinSpec
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import com.maekom.mongolens.mongo.SmartInValueType
import org.bson.BsonValue
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
    private val onConvertToShell: (String) -> Unit,
    initialJoinSpec: JoinSpec? = null,
    initialFilterText: String = "",
    initialSortText: String = "",
    private val diffBaselineState: DiffBaselineState? = null,
    private val collectionsSupplier: () -> List<String> = { emptyList() },
    private val onOpenReferenceJumpTab: ((targetCollection: String, sourceFieldPath: String, sourceValue: BsonValue) -> Unit)? = null,
    private val onOpenJoinTab: ((joinSpec: JoinSpec, currentFilter: String, currentSort: String) -> Unit)? = null,
    private val onJoinSpecUpdated: ((updatedSpec: JoinSpec) -> Unit)? = null
) : JPanel(BorderLayout()), Disposable {

    private val initialJoinSpecRef: JoinSpec? = initialJoinSpec

    @Volatile
    private var activeJoinSpec: JoinSpec? = initialJoinSpec
    val isJoinTab: Boolean get() = activeJoinSpec != null

    private val limitPresets = arrayOf(20, 50, 100, 500, 1000)
    private val unifiedInputHeight = JBUI.scale(32)
    private val unifiedLabelSize = Dimension(JBUI.scale(48), unifiedInputHeight)

    private fun createUnifiedFieldLabel(text: String): JBLabel = JBLabel(text).apply {
        preferredSize = unifiedLabelSize
        minimumSize = unifiedLabelSize
        maximumSize = unifiedLabelSize
    }

    private val filterLabel = createUnifiedFieldLabel(I18n.t("find.filter"))
    private val sortLabel = createUnifiedFieldLabel(I18n.t("find.sort"))
    private val projectLabel = createUnifiedFieldLabel(I18n.t("find.project")).apply {
        isVisible = false
    }

    private val projectToggleBtn = JButton(I18n.t("find.projectAdd")).apply {
        margin = JBUI.insets(2, 6)
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(JBUI.scale(76)), unifiedInputHeight)
    }
    private val limitLabel = JBLabel(I18n.t("find.limit"))
    private val skipLabel = JBLabel(I18n.t("find.skip"))

    /**
     * Common Query Input Factory:
     * Filter (max 6 lines), Sort (max 4 lines), and Project (max 4 lines) share identical
     * 32px 1-line height, JBUI.insets(5, 8) padding, auto-grow/shrink, vertical scrollbar on overflow,
     * zero width inflation (minimumSize/preferredSize width = 0), {} auto-wrapping completion, and gray placeholders.
     */
    private fun createUnifiedQueryEditor(
        maxVisibleLines: Int,
        placeholder: String,
        completionMode: MongoJsonEditor.CompletionMode
    ): MongoJsonEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "",
        multiLine = false,
        autoGrow = true,
        maxVisibleLines = maxVisibleLines,
        unifiedInputStyle = true,
        placeholderText = placeholder,
        completionMode = completionMode,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executeFind() },
        onAutoHeightChanged = { revalidateHierarchy() }
    )

    private val filterEditor = createUnifiedQueryEditor(
        maxVisibleLines = 6,
        placeholder = "Type a Query: { field: 'value' }",
        completionMode = MongoJsonEditor.CompletionMode.FILTER
    )

    private val sortEditor = createUnifiedQueryEditor(
        maxVisibleLines = 4,
        placeholder = "{ field: -1 }",
        completionMode = MongoJsonEditor.CompletionMode.SORT
    )

    private val projectEditor = createUnifiedQueryEditor(
        maxVisibleLines = 4,
        placeholder = "{ field: 1, _id: 0 }",
        completionMode = MongoJsonEditor.CompletionMode.PROJECT
    ).apply {
        isVisible = false
    }

    private val projectRowSpacer = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        isVisible = false
    }

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

    private val queryGridLayout = GridBagLayout()
    private val queryGridPanel = JPanel(queryGridLayout).apply {
        border = JBUI.Borders.empty(4, 6, 4, 6)
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
        collection = initialJoinSpec?.compactTabKeySegment() ?: collection,
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
        onReadOnlyChanged = onReadOnlyChanged,
        diffBaselineState = diffBaselineState,
        isJoinView = initialJoinSpec != null,
        initialView = if (initialJoinSpec != null) "TABLE" else "TREE",
        initialJoinColumnOrigins = initialJoinSpec?.columnOriginsByResultKey().orEmpty(),
        availableColumnsSupplier = { resolveAvailableProjectionColumns() },
        onApplyColumnSelection = { selectedCols, allSelected ->
            handleColumnSelectionApplied(selectedCols, allSelected)
        },
        onApplyQuickFilter = { filterJson ->
            applyQuickFilterAndRun(filterJson)
        },
        collectionsSupplier = collectionsSupplier,
        onJumpToReferenceCollection = onOpenReferenceJumpTab,
        onRequestOpenJoinDialog = if (onOpenJoinTab != null) { initialField ->
            openJoinConfigurationDialog(initialField)
        } else null,
        onRequestEditJoin = if (initialJoinSpec != null) ({ openEditCurrentJoinDialog() }) else null,
        onCopyJoinPipeline = if (initialJoinSpec != null) ({ copyCurrentJoinPipeline() }) else null
    )

    init {
        Disposer.register(this, resultViewer)
        if (initialFilterText.isNotBlank() && initialFilterText.trim() != "{}") {
            filterEditor.text = initialFilterText.trim()
        }
        if (initialSortText.isNotBlank() && initialSortText.trim() != "{}") {
            sortEditor.text = initialSortText.trim()
        }
        buildCompactQueryBar()
        add(resultViewer, BorderLayout.CENTER)
        I18n.addListener(this) { refreshTexts() }
    }

    private fun buildCompactQueryBar() {
        val rowInsets = JBUI.insets(2, 2, 2, 2)
        val hiddenInsets = JBUI.emptyInsets()

        // Column 2 Row 0 Right: [+ Project / - Project] [▶ 조회] [초기화]
        val row0Right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(projectToggleBtn)
            add(runBtn)
            add(resetBtn)
        }

        // Column 2 Row 1 Right: Limit [-] [50] [+] Skip [0] [📋 Smart $in] [Shell] [⭐ 저장/기록]
        val row1Right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(limitLabel)
            add(limitDecreaseBtn)
            add(limitCombo)
            add(limitIncreaseBtn)
            add(skipLabel)
            add(skipSpinner)
            add(smartInBtn)
            add(toShellBtn)
            add(savedQueriesBtn)
        }

        // Unified 3-column GridBagLayout:
        // Col 0 (fixed label width) | Col 1 (weightx=1.0, HORIZONTAL: Filter / Sort / Project) | Col 2 (right actions)

        // Row 0: Filter
        queryGridPanel.add(filterLabel, GridBagConstraints().apply {
            gridx = 0; gridy = 0; weightx = 0.0
            fill = GridBagConstraints.NONE; anchor = GridBagConstraints.NORTHWEST; insets = rowInsets
        })
        queryGridPanel.add(filterEditor, GridBagConstraints().apply {
            gridx = 1; gridy = 0; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTH; insets = rowInsets
        })
        queryGridPanel.add(row0Right, GridBagConstraints().apply {
            gridx = 2; gridy = 0; weightx = 0.0
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTH; insets = rowInsets
        })

        // Row 1: Sort
        queryGridPanel.add(sortLabel, GridBagConstraints().apply {
            gridx = 0; gridy = 1; weightx = 0.0
            fill = GridBagConstraints.NONE; anchor = GridBagConstraints.NORTHWEST; insets = rowInsets
        })
        queryGridPanel.add(sortEditor, GridBagConstraints().apply {
            gridx = 1; gridy = 1; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTH; insets = rowInsets
        })
        queryGridPanel.add(row1Right, GridBagConstraints().apply {
            gridx = 2; gridy = 1; weightx = 0.0
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTH; insets = rowInsets
        })

        // Row 2: Project (collapsible with zero leftover insets when hidden)
        queryGridPanel.add(projectLabel, GridBagConstraints().apply {
            gridx = 0; gridy = 2; weightx = 0.0
            fill = GridBagConstraints.NONE; anchor = GridBagConstraints.NORTHWEST; insets = hiddenInsets
        })
        queryGridPanel.add(projectEditor, GridBagConstraints().apply {
            gridx = 1; gridy = 2; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTH; insets = hiddenInsets
        })
        queryGridPanel.add(projectRowSpacer, GridBagConstraints().apply {
            gridx = 2; gridy = 2; weightx = 0.0
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTH; insets = hiddenInsets
        })

        wireListeners()

        queryBarWrapper.apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            add(queryGridPanel, BorderLayout.CENTER)
        }
        add(queryBarWrapper, BorderLayout.NORTH)
    }

    private fun setProjectRowVisible(visible: Boolean) {
        projectExpanded = visible
        val row2Insets = if (visible) JBUI.insets(2, 2, 2, 2) else JBUI.emptyInsets()
        for (comp in listOf(projectLabel, projectEditor, projectRowSpacer)) {
            val c = queryGridLayout.getConstraints(comp)
            c.insets = row2Insets
            queryGridLayout.setConstraints(comp, c)
            comp.isVisible = visible
        }
        updateProjectToggleText()
        if (visible) {
            projectEditor.refreshAutoHeightNow()
        }
        revalidateHierarchy()
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
            val joinSpec = activeJoinSpec
            if (joinSpec != null) {
                val allCols = joinSpec.allCandidateOutputColumns()
                val visibleCols = joinSpec.visibleOutputColumns()
                val dlg = ColumnSelectDialog(project, allCols, visibleCols)
                if (dlg.showAndGet()) {
                    handleColumnSelectionApplied(dlg.getSelectedColumns(), dlg.isAllColumnsSelected())
                }
            } else {
                setProjectRowVisible(!projectExpanded)
            }
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
            if (initialJoinSpecRef != null) {
                activeJoinSpec = initialJoinSpecRef
                resultViewer.setJoinColumnOrigins(initialJoinSpecRef.columnOriginsByResultKey())
                onJoinSpecUpdated?.invoke(initialJoinSpecRef)
            }
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
        val joinSpec = activeJoinSpec
        if (joinSpec != null) {
            return joinSpec.toMongoshScript(
                filterRaw = filterEditor.safeText(),
                sortRaw = sortEditor.safeText(),
                skip = currentSkip(),
                limit = currentLimit()
            )
        }

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

    private fun resolveAvailableProjectionColumns(): List<String> {
        val joinSpec = activeJoinSpec
        if (joinSpec != null) {
            return joinSpec.allCandidateOutputColumns()
        }
        return fieldSuggestionsSupplier()
            .map { it.substringBefore('.') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun handleColumnSelectionApplied(selectedColumns: List<String>, allSelected: Boolean) {
        if (selectedColumns.isEmpty()) return
        val joinSpec = activeJoinSpec
        if (joinSpec != null) {
            // Join Tab: update JoinSpec selected columns without overwriting Project with { field: 1 }
            val updated = joinSpec.updateFromVisibleColumnSelection(selectedColumns)
            activeJoinSpec = updated
            resultViewer.setJoinColumnOrigins(updated.columnOriginsByResultKey())
            executeFind()
            return
        }

        val allKnown = resolveAvailableProjectionColumns()
        val effectivelyAllSelected = allSelected && allKnown.isNotEmpty() && selectedColumns.size >= allKnown.size
        if (effectivelyAllSelected) {
            projectEditor.text = ""
        } else {
            val entries = mutableListOf<String>()
            if ("_id" !in selectedColumns) {
                entries.add("\"_id\": 0")
            }
            for (col in selectedColumns) {
                val escaped = MongoShellNormalizer.escapeJsonString(col)
                entries.add("\"$escaped\": 1")
            }
            projectEditor.text = "{ ${entries.joinToString(", ")} }"
            if (!projectExpanded) {
                setProjectRowVisible(true)
            } else {
                projectEditor.refreshAutoHeightNow()
                revalidateHierarchy()
            }
        }
        executeFind()
    }

    fun applyQuickFilterAndRun(filterJson: String) {
        filterEditor.text = filterJson.trim()
        filterEditor.refreshAutoHeightNow()
        skipSpinner.value = 0
        revalidateHierarchy()
        executeFind()
    }

    private fun openJoinConfigurationDialog(initialLocalField: String?) {
        val joinLauncher = onOpenJoinTab ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val allCollections = try {
                val cached = collectionsSupplier()
                if (cached.size > 1) cached else connectionManager.listCollections(profile, database)
            } catch (_: Throwable) {
                collectionsSupplier()
            }
            val sourceCategories: Map<String, String> = try {
                connectionManager.sampleCollectionTopLevelTypes(profile, database, collection, 20)
            } catch (_: Throwable) {
                emptyMap()
            }
            val topFields = buildList {
                addAll(sourceCategories.keys)
                fieldSuggestionsSupplier().forEach { add(it.substringBefore('.')) }
            }.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("_id") }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val dlg = JoinViewDialog(
                    project = project,
                    connectionManager = connectionManager,
                    profile = profile,
                    database = database,
                    sourceCollection = collection,
                    initialLocalField = initialLocalField,
                    sourceTopFields = topFields,
                    sourceFieldCategories = sourceCategories,
                    allCollectionsInDb = allCollections
                )
                if (dlg.showAndGet()) {
                    joinLauncher.invoke(dlg.buildJoinSpec(), filterEditor.safeText(), sortEditor.safeText())
                }
            }
        }
    }

    private fun copyCurrentJoinPipeline() {
        val spec = activeJoinSpec ?: return
        val jsonArray = spec.formatPipelineJsonArray(
            filterRaw = filterEditor.safeText(),
            sortRaw = sortEditor.safeText(),
            skip = currentSkip(),
            limit = currentLimit()
        )
        UiHelpers.copyToClipboard(jsonArray)
        UiHelpers.notify(project, I18n.t("join.tab.pipelineCopied"))
    }

    private fun openEditCurrentJoinDialog() {
        val currentSpec = activeJoinSpec ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val allCollections = try {
                val cached = collectionsSupplier()
                if (cached.size > 1) cached else connectionManager.listCollections(profile, database)
            } catch (_: Throwable) {
                collectionsSupplier()
            }
            val sourceCategories: Map<String, String> = try {
                connectionManager.sampleCollectionTopLevelTypes(profile, database, currentSpec.leftCollection, 10)
            } catch (_: Throwable) {
                emptyMap()
            }
            val topFields = buildList {
                addAll(sourceCategories.keys)
                addAll(currentSpec.allLeftFields)
                addAll(currentSpec.selectedLeftFields)
            }.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("_id") }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val dlg = JoinViewDialog(
                    project = project,
                    connectionManager = connectionManager,
                    profile = profile,
                    database = database,
                    sourceCollection = currentSpec.leftCollection,
                    initialLocalField = currentSpec.targets.firstOrNull()?.localField,
                    sourceTopFields = topFields,
                    sourceFieldCategories = sourceCategories,
                    allCollectionsInDb = allCollections,
                    existingJoinSpec = currentSpec
                )
                if (dlg.showAndGet()) {
                    val updatedSpec = dlg.buildJoinSpec()
                    activeJoinSpec = updatedSpec
                    resultViewer.setJoinColumnOrigins(updatedSpec.columnOriginsByResultKey())
                    onJoinSpecUpdated?.invoke(updatedSpec)
                    skipSpinner.value = 0
                    executeFind()
                }
            }
        }
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
        val joinSpecSnapshot = activeJoinSpec

        runBtn.isEnabled = false
        onStatusUpdate(I18n.t("header.status.querying"), false)

        val taskTitle = if (joinSpecSnapshot != null) {
            "Joining ${joinSpecSnapshot.displayTitle()}..."
        } else {
            "Finding documents in $database.$collection..."
        }

        object : Task.Backgroundable(project, taskTitle, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = if (joinSpecSnapshot != null) {
                        connectionManager.executeJoin(
                            profile = profile,
                            database = database,
                            joinSpec = joinSpecSnapshot,
                            filterRaw = filterRaw,
                            sortRaw = sortRaw,
                            skip = skip,
                            limit = limit
                        )
                    } else {
                        connectionManager.executeFind(
                            profile = profile,
                            database = database,
                            collection = collection,
                            filterRaw = filterRaw,
                            projectionRaw = projRaw,
                            sortRaw = sortRaw,
                            skip = skip,
                            limit = limit
                        )
                    }

                    if (joinSpecSnapshot == null) {
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
                    }

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
            setProjectRowVisible(true)
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
