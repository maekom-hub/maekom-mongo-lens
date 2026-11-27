package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.MongoLensSettings
import com.maekom.mongolens.config.SavedQuery
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.i18n.I18n
import com.intellij.notification.NotificationType
import com.maekom.mongolens.mongo.JoinSpec
import com.maekom.mongolens.mongo.JoinToolbarStatePolicy
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import com.maekom.mongolens.mongo.QueryExecutionControl
import com.maekom.mongolens.mongo.QueryRunTracker
import com.maekom.mongolens.mongo.QuickFilterHelper
import com.maekom.mongolens.mongo.SmartInValueType
import com.mongodb.client.MongoCursor
import org.bson.BsonDocument
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
    private val onJoinSpecUpdated: ((updatedSpec: JoinSpec) -> Unit)? = null,
    private val onOpenSettings: () -> Unit = {}
) : JPanel(BorderLayout()), Disposable {

    private data class LimitChoice(val value: Int?) {
        override fun toString(): String = value?.toString() ?: I18n.t("join.limit.unlimited")
    }

    private val initialJoinSpecRef: JoinSpec? = initialJoinSpec

    @Volatile
    private var activeJoinSpec: JoinSpec? = initialJoinSpec
    val isJoinTab: Boolean get() = activeJoinSpec != null

    private var joinCurrentPage: Int = 0
    private var suppressLimitComboEvents = false

    // Per-tab isolated runId token, cursor, futures, and watchdog timer (NEVER uses admin killOp)
    private val runTracker = QueryRunTracker()
    private val activeCursor = AtomicReference<MongoCursor<*>?>(null)
    private val activePrimaryFuture = AtomicReference<Future<*>?>(null)
    private val activeMetaFuture = AtomicReference<Future<*>?>(null)
    private val activeWatchdogFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private val isDisposedFlag = AtomicBoolean(false)
    private val hasCompletedInitialLoad = AtomicBoolean(false)

    private val limitChoices: Array<LimitChoice> = if (initialJoinSpec != null) {
        JoinSpec.JOIN_LIMIT_OPTIONS.map { LimitChoice(it) }.toTypedArray()
    } else {
        arrayOf(20, 50, 100, 500, 1000).map { LimitChoice(it) }.toTypedArray()
    }
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
    private val limitCombo = ComboBox(limitChoices).apply {
        val initVal: Int? = if (initialJoinSpec != null) initialJoinSpec.limitOption else 50
        selectedItem = limitChoices.firstOrNull { it.value == initVal } ?: limitChoices.firstOrNull { it.value == 50 } ?: limitChoices.first()
        preferredSize = Dimension(if (initialJoinSpec != null) JBUI.scale(132) else JBUI.scale(68), JBUI.scale(28))
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
        onRequestRefresh = { executeFind(resetJoinPage = false, forceMetaRefresh = true) },
        onPrevPage = {
            if (isJoinTab) {
                if (currentJoinPageSize() != null && joinCurrentPage > 0) {
                    joinCurrentPage = (joinCurrentPage - 1).coerceAtLeast(0)
                    executeFind(resetJoinPage = false)
                }
            } else {
                val lim = currentLimit()
                val prevSkip = (currentSkip() - lim).coerceAtLeast(0)
                skipSpinner.value = prevSkip
                executeFind()
            }
        },
        onNextPage = {
            if (isJoinTab) {
                if (currentJoinPageSize() != null) {
                    joinCurrentPage += 1
                    executeFind(resetJoinPage = false)
                }
            } else {
                val lim = currentLimit()
                skipSpinner.value = currentSkip() + lim
                executeFind()
            }
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
        onMergeQuickFilter = { patchClause ->
            mergeQuickFilterAndRun(patchClause)
        },
        onApplyQuickSort = { sortJson ->
            applyQuickSortAndRun(sortJson)
        },
        onOpenIndexInspector = { execResult ->
            openIndexInspectorDialog(execResult)
        },
        indexCollectionName = initialJoinSpec?.leftCollection ?: collection,
        collectionsSupplier = collectionsSupplier,
        onJumpToReferenceCollection = onOpenReferenceJumpTab,
        onRequestOpenJoinDialog = if (onOpenJoinTab != null) { initialField ->
            openJoinConfigurationDialog(initialField)
        } else null,
        onRequestEditJoin = if (initialJoinSpec != null) ({ openEditCurrentJoinDialog() }) else null,
        onCopyJoinPipeline = if (initialJoinSpec != null) ({ copyCurrentJoinPipeline() }) else null,
        onRequestImportJson = if (initialJoinSpec == null) ({
            ImportJsonDialog.openIfWritable(
                project = project,
                connectionManager = connectionManager,
                profile = profile,
                database = database,
                collection = collection
            ) {
                executeFind(resetJoinPage = false, forceMetaRefresh = true)
            }
        }) else null
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
        updateToolbarStateForJoinMode(isJoinTab)
        I18n.addListener(this) { refreshTexts() }
    }

    /**
     * Synchronizes single-collection vs Join-view toolbar control states & disabled tooltips.
     * Re-invoked on tab switches and Join-mode toggles so controls always reflect the active tab.
     */
    fun updateToolbarStateForJoinMode(isJoinActive: Boolean) {
        val states = JoinToolbarStatePolicy.compute(isJoinActive)
        val disabledTip = if (isJoinActive) I18n.t("join.toolbar.disabledTooltip") else null

        filterEditor.isEnabled = states.filterInputEnabled
        filterEditor.toolTipText = disabledTip
        sortEditor.isEnabled = states.sortInputEnabled
        sortEditor.toolTipText = disabledTip
        projectEditor.isEnabled = states.projectInputEnabled
        projectEditor.toolTipText = disabledTip
        projectToggleBtn.isEnabled = states.projectInputEnabled
        projectToggleBtn.toolTipText = disabledTip

        resetBtn.isEnabled = states.resetEnabled
        resetBtn.toolTipText = disabledTip
        smartInBtn.isEnabled = states.smartInEnabled
        smartInBtn.toolTipText = if (states.smartInEnabled) I18n.t("find.smartInTooltip") else disabledTip
        toShellBtn.isEnabled = states.toShellEnabled
        toShellBtn.toolTipText = if (states.toShellEnabled) I18n.t("find.toShellTooltip") else disabledTip
        savedQueriesBtn.isEnabled = states.savedQueriesEnabled
        savedQueriesBtn.toolTipText = disabledTip

        // Always keep unified Run/Stop, Limit, Column Select, Join Config, Copy Pipeline, Export, and Paging active
        runBtn.isEnabled = states.runOrStopEnabled
        limitCombo.isEnabled = states.pageSizeEnabled
        limitDecreaseBtn.isEnabled = states.pageSizeEnabled
        limitIncreaseBtn.isEnabled = states.pageSizeEnabled

        resultViewer.setJoinModeControlStates(
            importEnabled = states.importJsonEnabled,
            indexInspectEnabled = states.indexInspectorEnabled,
            disabledTooltip = disabledTip
        )
    }

    private fun updateRunButtonVisual(running: Boolean) {
        if (running) {
            runBtn.text = I18n.t("find.stop")
            runBtn.icon = AllIcons.Actions.Suspend
            runBtn.foreground = UiHelpers.COLOR_RED_FG
            runBtn.toolTipText = I18n.t("find.stopTooltip")
        } else {
            runBtn.text = I18n.t("find.run")
            runBtn.icon = null
            runBtn.foreground = UiHelpers.COLOR_GREEN_FG
            runBtn.toolTipText = I18n.t("find.runTooltip")
        }
        runBtn.isEnabled = true
        runBtn.revalidate()
        runBtn.repaint()
    }

    /**
     * Client-side safe Stop (NEVER calls admin currentOp/killOp):
     * 1) marks manual stop & advances runId token
     * 2) cancels watchdog & worker futures
     * 3) closes active MongoCursor inside try/catch
     * 4) restores `▶ 조회` immediately on EDT
     */
    fun stopActiveQuery(updateUi: Boolean = true) {
        runTracker.requestStop()
        activeWatchdogFuture.getAndSet(null)?.cancel(false)
        activePrimaryFuture.getAndSet(null)?.cancel(true)
        activeMetaFuture.getAndSet(null)?.cancel(true)
        runCatching { activeCursor.getAndSet(null)?.close() }

        if (updateUi && !isDisposedFlag.get()) {
            val restoreUi = Runnable {
                if (isDisposedFlag.get()) return@Runnable
                updateRunButtonVisual(running = false)
                resultViewer.setLoading(false)
                onStatusUpdate(I18n.t("header.status.stopped"), false)
            }
            if (SwingUtilities.isEventDispatchThread()) restoreUi.run() else ApplicationManager.getApplication().invokeLater(restoreUi)
        }
    }

    private fun showTimeoutGuideIfNeeded(myRunId: Long, timeoutSeconds: Int, serverTimeoutException: Boolean) {
        if (!runTracker.tryAcquireTimeoutAlertSlot(myRunId, serverTimeoutException)) return
        ApplicationManager.getApplication().invokeLater {
            if (isDisposedFlag.get() || project.isDisposed) return@invokeLater
            if (runTracker.finishRunIfCurrent(myRunId)) {
                activeCursor.set(null)
                updateRunButtonVisual(running = false)
                resultViewer.setLoading(false)
            }
            onStatusUpdate(I18n.t("header.status.timedOut", timeoutSeconds), true)
            TimeoutGuideDialog(project, timeoutSeconds) {
                onOpenSettings()
            }.show()
        }
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
            if (!isJoinTab) {
                add(skipLabel)
                add(skipSpinner)
            }
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
            if (suppressLimitComboEvents) return@addActionListener
            resultViewer.resetTableBatchDecryption()
            if (isJoinTab) {
                joinCurrentPage = 0
                activeJoinSpec = activeJoinSpec?.copy(limitOption = currentJoinPageSize())
                executeFind(resetJoinPage = true)
            }
        }

        skipSpinner.addChangeListener {
            resultViewer.resetTableBatchDecryption()
        }

        limitDecreaseBtn.addActionListener {
            val idx = limitCombo.selectedIndex
            if (idx > 0) {
                resultViewer.resetTableBatchDecryption()
                suppressLimitComboEvents = true
                try {
                    limitCombo.selectedIndex = idx - 1
                } finally {
                    suppressLimitComboEvents = false
                }
                if (isJoinTab) {
                    joinCurrentPage = 0
                    activeJoinSpec = activeJoinSpec?.copy(limitOption = currentJoinPageSize())
                }
                executeFind(resetJoinPage = true)
            }
        }

        limitIncreaseBtn.addActionListener {
            val idx = limitCombo.selectedIndex
            if (idx in 0 until limitChoices.lastIndex) {
                resultViewer.resetTableBatchDecryption()
                suppressLimitComboEvents = true
                try {
                    limitCombo.selectedIndex = idx + 1
                } finally {
                    suppressLimitComboEvents = false
                }
                if (isJoinTab) {
                    joinCurrentPage = 0
                    activeJoinSpec = activeJoinSpec?.copy(limitOption = currentJoinPageSize())
                }
                executeFind(resetJoinPage = true)
            }
        }

        runBtn.addActionListener {
            if (runTracker.isQueryRunning) {
                stopActiveQuery(updateUi = true)
            } else {
                executeFind(resetJoinPage = true, forceMetaRefresh = true, ignoreIfRunning = true)
            }
        }

        resetBtn.addActionListener {
            resultViewer.resetTableBatchDecryption()
            joinCurrentPage = 0
            if (initialJoinSpecRef != null) {
                activeJoinSpec = initialJoinSpecRef
                resultViewer.setJoinColumnOrigins(initialJoinSpecRef.columnOriginsByResultKey())
                onJoinSpecUpdated?.invoke(initialJoinSpecRef)
                selectLimitChoice(initialJoinSpecRef.limitOption)
            } else {
                selectLimitChoice(50)
            }
            filterEditor.text = ""
            projectEditor.text = ""
            sortEditor.text = ""
            skipSpinner.value = 0
            executeFind(resetJoinPage = true, forceMetaRefresh = true)
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
                pageIndex = joinCurrentPage,
                pageSize = currentJoinPageSize()
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
        joinCurrentPage = 0
        revalidateHierarchy()
        executeFind(resetJoinPage = true)
    }

    fun applyFilterTemplateOnly(filterTemplateJson: String) {
        filterEditor.text = filterTemplateJson.trim()
        filterEditor.refreshAutoHeightNow()
        revalidateHierarchy()
        ApplicationManager.getApplication().invokeLater {
            filterEditor.requestFocusInWindow()
        }
    }

    private fun mergeQuickFilterAndRun(patchClause: BsonDocument) {
        val mergedJson = QuickFilterHelper.mergeIntoExistingFilter(filterEditor.safeText(), patchClause)
        filterEditor.text = mergedJson
        filterEditor.refreshAutoHeightNow()
        skipSpinner.value = 0
        joinCurrentPage = 0
        revalidateHierarchy()
        executeFind(resetJoinPage = true)
    }

    private fun applyQuickSortAndRun(sortJson: String) {
        sortEditor.text = sortJson.trim()
        sortEditor.refreshAutoHeightNow()
        skipSpinner.value = 0
        joinCurrentPage = 0
        revalidateHierarchy()
        executeFind(resetJoinPage = true)
    }

    private fun openIndexInspectorDialog(execResult: com.maekom.mongolens.mongo.QueryExecutionResult?) {
        val targetColl = activeJoinSpec?.leftCollection ?: collection
        IndexInspectorDialog(
            project = project,
            connectionManager = connectionManager,
            profile = profile,
            database = database,
            collection = targetColl,
            initialIndexes = execResult?.detailedIndexes?.takeIf { it.isNotEmpty() },
            currentFilterRaw = filterEditor.safeText(),
            currentSortRaw = sortEditor.safeText(),
            currentScanType = execResult?.scanType,
            currentIndexName = execResult?.indexName,
            explainError = execResult?.explainError,
            onApplyFilterTemplate = { templateJson ->
                applyFilterTemplateOnly(templateJson)
            }
        ).show()
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
            val sampledSchema = try {
                connectionManager.sampleCollectionTopLevelSchema(profile, database, collection, 20)
            } catch (_: Throwable) {
                MongoConnectionManager.SampledTopLevelSchema(0, linkedMapOf(), linkedMapOf())
            }
            val sourceCategories = sampledSchema.fieldTypes
            val sourceJoinKeyCategories = sampledSchema.joinFieldTypes
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
                    sourceJoinKeyCategories = sourceJoinKeyCategories,
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
        if (!com.maekom.mongolens.mongo.CompassPipelineSerializer.hasValidJoinKeys(spec)) {
            Messages.showWarningDialog(
                project,
                I18n.t("join.tab.copyPipelineNoValidKeys"),
                I18n.t("join.tab.copyPipelineBtn")
            )
            return
        }
        val jsonArray = spec.formatPipelineJsonArray(
            filterRaw = filterEditor.safeText(),
            sortRaw = sortEditor.safeText(),
            pageIndex = joinCurrentPage,
            pageSize = currentJoinPageSize(),
            useDocumentDbHybrid = connectionManager.isDocumentDbHybridRequired(profile)
        )
        UiHelpers.copyToClipboard(jsonArray)
        UiHelpers.notify(project, I18n.t("join.tab.pipelineCopied"))
    }

    private fun openEditCurrentJoinDialog() {
        val currentSpec = activeJoinSpec?.copy(limitOption = currentJoinPageSize()) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val allCollections = try {
                val cached = collectionsSupplier()
                if (cached.size > 1) cached else connectionManager.listCollections(profile, database)
            } catch (_: Throwable) {
                collectionsSupplier()
            }
            val sampledSchema = try {
                connectionManager.sampleCollectionTopLevelSchema(profile, database, currentSpec.leftCollection, 15)
            } catch (_: Throwable) {
                MongoConnectionManager.SampledTopLevelSchema(0, linkedMapOf(), linkedMapOf())
            }
            val sourceCategories = sampledSchema.fieldTypes
            val sourceJoinKeyCategories = sampledSchema.joinFieldTypes
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
                    sourceJoinKeyCategories = sourceJoinKeyCategories,
                    allCollectionsInDb = allCollections,
                    existingJoinSpec = currentSpec
                )
                if (dlg.showAndGet()) {
                    val updatedSpec = dlg.buildJoinSpec()
                    activeJoinSpec = updatedSpec
                    selectLimitChoice(updatedSpec.limitOption)
                    resultViewer.setJoinColumnOrigins(updatedSpec.columnOriginsByResultKey())
                    onJoinSpecUpdated?.invoke(updatedSpec)
                    joinCurrentPage = 0
                    skipSpinner.value = 0
                    executeFind(resetJoinPage = true)
                }
            }
        }
    }

    private fun selectLimitChoice(targetValue: Int?) {
        suppressLimitComboEvents = true
        try {
            val match = limitChoices.firstOrNull { it.value == targetValue }
                ?: limitChoices.firstOrNull { it.value == 50 }
                ?: limitChoices.first()
            limitCombo.selectedItem = match
        } finally {
            suppressLimitComboEvents = false
        }
    }

    private fun currentJoinPageSize(): Int? = (limitCombo.selectedItem as? LimitChoice)?.value

    private fun currentLimit(): Int = (limitCombo.selectedItem as? LimitChoice)?.value ?: JoinSpec.MAX_UNLIMITED_DOCS

    private fun currentSkip(): Int = (skipSpinner.value as? Int) ?: 0

    fun ensureLoadedIfNeeded() {
        if (isDisposedFlag.get() || project.isDisposed) return
        val inFlight = activePrimaryFuture.get()?.let { !it.isDone && !it.isCancelled } ?: false
        if (!hasCompletedInitialLoad.get() && !inFlight) {
            executeFind(resetJoinPage = false, forceMetaRefresh = false)
        }
    }

    fun executeFind(
        resetJoinPage: Boolean = true,
        forceMetaRefresh: Boolean = false,
        ignoreIfRunning: Boolean = false
    ) {
        if (isDisposedFlag.get() || project.isDisposed) return
        if (ignoreIfRunning && runTracker.isQueryRunning) return

        // Cancel any previous run cleanly before advancing runId token
        activeWatchdogFuture.getAndSet(null)?.cancel(false)
        activePrimaryFuture.getAndSet(null)?.cancel(true)
        activeMetaFuture.getAndSet(null)?.cancel(true)
        runCatching { activeCursor.getAndSet(null)?.close() }

        val myRunId = runTracker.beginNewRun()
        val timeoutSeconds = MongoLensSettings.getInstance().queryTimeoutSeconds

        resultViewer.resetTableBatchDecryption()
        filterEditor.dismissPopup()
        sortEditor.dismissPopup()
        projectEditor.dismissPopup()

        // Blank input safely falls back to "{}" — placeholder text is NEVER sent as a query
        val filterRaw = filterEditor.safeText().ifEmpty { "{}" }
        val projRaw = projectEditor.safeText().ifEmpty { "{}" }
        val sortRaw = sortEditor.safeText().ifEmpty { "{}" }
        val joinSpecSnapshot = activeJoinSpec
        if (joinSpecSnapshot != null && resetJoinPage) {
            joinCurrentPage = 0
        }
        val joinPageSnapshot = joinCurrentPage
        val joinPageSizeSnapshot = if (joinSpecSnapshot != null) currentJoinPageSize() else null
        val limit = currentLimit()
        val skip = currentSkip()

        updateRunButtonVisual(running = true)
        resultViewer.setLoading(true)
        onStatusUpdate(I18n.t("header.status.querying"), false)

        val executionControl = QueryExecutionControl(
            runId = myRunId,
            isCancelled = { isDisposedFlag.get() || project.isDisposed || runTracker.isCancelledFor(myRunId) },
            onCursorOpened = { cursor ->
                if (cursor == null) {
                    activeCursor.set(null)
                } else if (runTracker.isCancelledFor(myRunId)) {
                    runCatching { cursor.close() }
                } else {
                    activeCursor.set(cursor)
                }
            }
        )

        // Client-side watchdog timer paired with server maxTimeMS (Dual Timeout Guard)
        val watchdog = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (isDisposedFlag.get() || project.isDisposed) return@schedule
            if (runTracker.markTimedOutIfActive(myRunId)) {
                activePrimaryFuture.get()?.cancel(true)
                runCatching { activeCursor.getAndSet(null)?.close() }
                showTimeoutGuideIfNeeded(myRunId, timeoutSeconds, serverTimeoutException = false)
            }
        }, timeoutSeconds.toLong(), TimeUnit.SECONDS)
        activeWatchdogFuture.set(watchdog)

        val executor = AppExecutorUtil.getAppExecutorService()
        val primaryFuture = executor.submit {
            if (isDisposedFlag.get() || project.isDisposed || runTracker.isCancelledFor(myRunId)) return@submit
            try {
                // Phase 1: Zero-Overhead Primary Fetch (single find/join roundtrip; NO count/explain/listIndexes blocking UI)
                val result = if (joinSpecSnapshot != null) {
                    connectionManager.executeJoinPrimary(
                        profile = profile,
                        database = database,
                        joinSpec = joinSpecSnapshot,
                        filterRaw = filterRaw,
                        sortRaw = sortRaw,
                        pageIndex = joinPageSnapshot,
                        pageSize = joinPageSizeSnapshot,
                        forceRefresh = forceMetaRefresh || resetJoinPage,
                        executionControl = executionControl
                    )
                } else {
                    connectionManager.executeFindPrimary(
                        profile = profile,
                        database = database,
                        collection = collection,
                        filterRaw = filterRaw,
                        projectionRaw = projRaw,
                        sortRaw = sortRaw,
                        skip = skip,
                        limit = limit,
                        executionControl = executionControl
                    )
                }

                if (isDisposedFlag.get() || project.isDisposed || runTracker.isCancelledFor(myRunId)) return@submit

                if (joinSpecSnapshot == null) {
                    runCatching {
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
                }

                val displaySkip = if (joinSpecSnapshot != null) {
                    if (joinPageSizeSnapshot != null) joinPageSnapshot * joinPageSizeSnapshot else 0
                } else {
                    skip
                }
                val displayLimit = if (joinSpecSnapshot != null) {
                    joinPageSizeSnapshot ?: JoinSpec.MAX_UNLIMITED_DOCS
                } else {
                    limit
                }
                val capBanner = if (result.unlimitedCapHit) {
                    I18n.t("join.unlimitedCapBanner", JoinSpec.MAX_UNLIMITED_DOCS)
                } else {
                    null
                }

                ApplicationManager.getApplication().invokeLater {
                    if (isDisposedFlag.get() || project.isDisposed || runTracker.isCancelledFor(myRunId)) return@invokeLater
                    hasCompletedInitialLoad.set(true)
                    if (result.sampledFields.isNotEmpty()) {
                        onFieldsDiscovered(result.sampledFields, result.sampledFieldTypes)
                    }
                    resultViewer.updateResults(
                        result = result,
                        skip = displaySkip,
                        limit = displayLimit,
                        previewBanner = capBanner
                    )
                    if (result.unlimitedCapHit) {
                        UiHelpers.notify(
                            project,
                            I18n.t("join.unlimitedCapToast", JoinSpec.MAX_UNLIMITED_DOCS),
                            NotificationType.WARNING
                        )
                    }
                    val statusMsg = if (connectionManager.isEffectivelyReadOnly(profile)) {
                        "${I18n.t("header.status.connected", result.elapsedMs)} · ${I18n.t("header.status.readOnly")}"
                    } else {
                        I18n.t("header.status.connected", result.elapsedMs)
                    }
                    onStatusUpdate(statusMsg, false)
                }

                // Phase 2: Trailing Async Metadata (count + conditional QUERY_PLANNER explain when Filter/Sort is non-empty)
                val metaFuture = executor.submit {
                    if (isDisposedFlag.get() || project.isDisposed || runTracker.isCancelledFor(myRunId)) return@submit
                    val meta = runCatching {
                        if (joinSpecSnapshot != null) {
                            connectionManager.fetchJoinTrailingMetadata(
                                profile = profile,
                                database = database,
                                joinSpec = joinSpecSnapshot,
                                filterRaw = filterRaw,
                                sortRaw = sortRaw,
                                pageIndex = joinPageSnapshot,
                                pageSize = joinPageSizeSnapshot,
                                forceRefresh = forceMetaRefresh
                            )
                        } else {
                            connectionManager.fetchFindTrailingMetadata(
                                profile = profile,
                                database = database,
                                collection = collection,
                                filterRaw = filterRaw,
                                projectionRaw = projRaw,
                                sortRaw = sortRaw,
                                skip = skip,
                                limit = limit,
                                forceRefresh = forceMetaRefresh
                            )
                        }
                    }.getOrNull() ?: return@submit

                    if (isDisposedFlag.get() || project.isDisposed || runTracker.isCancelledFor(myRunId)) return@submit

                    ApplicationManager.getApplication().invokeLater {
                        if (isDisposedFlag.get() || project.isDisposed || runTracker.isCancelledFor(myRunId)) return@invokeLater
                        resultViewer.updateTrailingMetadata(meta)
                    }
                }
                activeMetaFuture.set(metaFuture)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                if (runTracker.isTimedOut) {
                    showTimeoutGuideIfNeeded(myRunId, timeoutSeconds, serverTimeoutException = false)
                }
            } catch (t: Throwable) {
                if (isDisposedFlag.get() || project.isDisposed) return@submit
                if (runTracker.isStopRequested || runTracker.currentRunId.get() != myRunId) {
                    // User manually clicked Stop or superseded run — never display error/timeout alerts
                    return@submit
                }
                if (connectionManager.isExecutionTimeoutError(t) || runTracker.isTimedOut) {
                    showTimeoutGuideIfNeeded(myRunId, timeoutSeconds, serverTimeoutException = true)
                    return@submit
                }
                val msg = t.message ?: t.javaClass.simpleName
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposedFlag.get() || project.isDisposed || runTracker.currentRunId.get() != myRunId) return@invokeLater
                    onStatusUpdate(I18n.t("header.status.error", msg.take(80)), true)
                    Messages.showErrorDialog(project, msg, I18n.t("find.run"))
                }
            } finally {
                watchdog.cancel(false)
                activeWatchdogFuture.compareAndSet(watchdog, null)
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposedFlag.get() || project.isDisposed) return@invokeLater
                    // Only clear UI running state if this runId is still active (never clobber a newer run!)
                    if (runTracker.finishRunIfCurrent(myRunId)) {
                        activeCursor.set(null)
                        updateRunButtonVisual(running = false)
                        resultViewer.setLoading(false)
                    }
                }
            }
        }
        activePrimaryFuture.set(primaryFuture)
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
        selectLimitChoice(q.limit)
        skipSpinner.value = q.skip.coerceAtLeast(0)
        if (p.isNotEmpty() && p != "{}") {
            setProjectRowVisible(true)
        }
        executeFind(resetJoinPage = true)
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
            limitCombo.repaint()
            updateProjectToggleText()
            updateRunButtonVisual(runTracker.isQueryRunning)
            resetBtn.text = I18n.t("find.reset")
            smartInBtn.text = I18n.t("find.smartIn")
            smartInBtn.toolTipText = I18n.t("find.smartInTooltip")
            toShellBtn.text = I18n.t("find.toShell")
            toShellBtn.toolTipText = I18n.t("find.toShellTooltip")
            savedQueriesBtn.text = I18n.t("find.savedQueries")
            limitDecreaseBtn.toolTipText = I18n.t("find.limitDecreaseTooltip")
            limitIncreaseBtn.toolTipText = I18n.t("find.limitIncreaseTooltip")
            updateToolbarStateForJoinMode(isJoinTab)
        }
    }

    override fun dispose() {
        isDisposedFlag.set(true)
        stopActiveQuery(updateUi = false)
        filterEditor.dismissPopup()
        sortEditor.dismissPopup()
        projectEditor.dismissPopup()
    }
}
