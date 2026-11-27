package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import com.maekom.mongolens.config.ConnectionColorTag
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.config.VariableStore
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.JoinSpec
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.SmartInValueType
import org.bson.BsonValue
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

class CollectionWorkspaceTab(
    private val project: Project,
    private val projectBasePath: java.nio.file.Path?,
    private val connectionManager: MongoConnectionManager,
    savedQueryStore: SavedQueryStore,
    variableStore: VariableStore,
    cryptoStore: CryptoConfigStore,
    val profile: MongoConnectionProfile,
    val database: String,
    val collection: String,
    initialMode: WorkspaceMode = WorkspaceMode.FIND,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onReadOnlyStateChanged: () -> Unit,
    initialJoinSpec: JoinSpec? = null,
    initialFilterText: String = "",
    initialSortText: String = "",
    autoExecuteInitialFind: Boolean = true,
    diffBaselineState: DiffBaselineState? = null,
    onOpenReferenceJumpTab: ((targetCollection: String, sourceFieldPath: String, sourceValue: BsonValue) -> Unit)? = null,
    onOpenJoinTab: ((joinSpec: JoinSpec, currentFilter: String, currentSort: String) -> Unit)? = null,
    private val onJoinTitleUpdated: ((displayJoinTitle: String) -> Unit)? = null
) : JPanel(BorderLayout()), Disposable {

    enum class WorkspaceMode {
        FIND,
        AGGREGATION,
        SHELL
    }

    private val sampledFields = CopyOnWriteArrayList<String>()
    private val sampledFieldTypes = ConcurrentHashMap<String, SmartInValueType>()
    private val cachedCollections = CopyOnWriteArrayList<String>(listOf(collection))
    private val isDisposedFlag = AtomicBoolean(false)
    private val metadataFuture = AtomicReference<Future<*>?>(null)

    private class ModeTabButton(text: String, selected: Boolean) : JToggleButton(text, selected) {
        private var hovered = false

        init {
            isContentAreaFilled = false
            isFocusPainted = false
            isBorderPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(6, 12, 7, 12)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    hovered = true
                    repaint()
                }
                override fun mouseExited(e: MouseEvent?) {
                    hovered = false
                    repaint()
                }
            })
            refreshStyle()
        }

        fun refreshStyle() {
            font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN)
            foreground = if (isSelected) {
                JBUI.CurrentTheme.Label.foreground()
            } else {
                JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }
            repaint()
        }

        override fun setSelected(b: Boolean) {
            super.setSelected(b)
            refreshStyle()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (isSelected) {
                    g2.color = JBColor(Color(0xE8F1FD), Color(0x2B3646))
                    g2.fillRoundRect(0, JBUI.scale(1), width, height - JBUI.scale(2), JBUI.scale(6), JBUI.scale(6))
                    val barH = JBUI.scale(3)
                    g2.color = JBUI.CurrentTheme.TabbedPane.FOCUS_COLOR
                    g2.fillRect(JBUI.scale(2), height - barH, width - JBUI.scale(4), barH)
                } else if (hovered) {
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    g2.fillRoundRect(0, JBUI.scale(1), width, height - JBUI.scale(2), JBUI.scale(6), JBUI.scale(6))
                }
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private val findModeBtn = ModeTabButton(I18n.t("workspace.mode.find"), initialMode == WorkspaceMode.FIND)
    private val aggModeBtn = ModeTabButton(I18n.t("workspace.mode.agg"), initialMode == WorkspaceMode.AGGREGATION)
    private val shellModeBtn = ModeTabButton(I18n.t("workspace.mode.shell"), initialMode == WorkspaceMode.SHELL)

    private val breadcrumbLabel = JBLabel(
        if (initialJoinSpec != null) {
            "${profile.displayName}  /  $database.${initialJoinSpec.displayTitle()}"
        } else {
            "${profile.displayName}  /  $database.$collection"
        }
    ).apply {
        font = font.deriveFont(Font.BOLD)
    }

    private val joinModeBadge = UiHelpers.createBadgeLabel(
        I18n.t("join.badge"),
        UiHelpers.COLOR_BLUE_BG,
        UiHelpers.COLOR_BLUE_FG
    ).apply {
        isVisible = initialJoinSpec != null
    }

    private val joinKeysSummaryBadge = UiHelpers.createBadgeLabel(
        "",
        UiHelpers.COLOR_GRAY_BG,
        UiHelpers.COLOR_GRAY_FG
    ).apply {
        isVisible = false
    }

    private fun updateJoinKeysSummaryBadge(spec: JoinSpec?) {
        val summary = spec?.joinKeysSummary()?.trim().orEmpty()
        if (summary.isEmpty()) {
            joinKeysSummaryBadge.isVisible = false
            return
        }
        joinKeysSummaryBadge.text = if (summary.length > 56) "${summary.take(54)}…" else summary
        joinKeysSummaryBadge.toolTipText = summary
        joinKeysSummaryBadge.isVisible = true
    }

    private val colorTagBadge = UiHelpers.createBadgeLabel(
        "",
        profile.colorTag.bgColor() ?: UiHelpers.COLOR_GRAY_BG,
        profile.colorTag.fgColor() ?: UiHelpers.COLOR_GRAY_FG
    ).apply {
        val tag = profile.colorTag
        if (tag != ConnectionColorTag.NONE) {
            text = I18n.t(tag.i18nKey)
            isVisible = true
        } else {
            isVisible = false
        }
    }

    private val readOnlyBadge = UiHelpers.createBadgeLabel(
        I18n.t("tree.badge.readOnly"),
        UiHelpers.COLOR_RED_BG,
        UiHelpers.COLOR_RED_FG
    ).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val modeCardLayout = CardLayout()
    private val modeCardsPanel = JPanel(modeCardLayout)

    private val shellPanel = MongoShellPanel(
        project = project,
        projectBasePath = projectBasePath,
        connectionManager = connectionManager,
        savedQueryStore = savedQueryStore,
        variableStore = variableStore,
        cryptoStore = cryptoStore,
        profile = profile,
        database = database,
        collection = collection,
        collectionsSupplier = { cachedCollections.toList() },
        fieldSuggestionsSupplier = { sampledFields.toList() },
        onFieldsDiscovered = { fields -> mergeSampledFields(fields) },
        onStatusUpdate = onStatusUpdate,
        onReadOnlyChanged = {
            updateReadOnlyBadge()
            onReadOnlyStateChanged()
        },
        diffBaselineState = diffBaselineState
    )

    private val findPanel = FindQueryPanel(
        project = project,
        projectBasePath = projectBasePath,
        connectionManager = connectionManager,
        savedQueryStore = savedQueryStore,
        cryptoStore = cryptoStore,
        profile = profile,
        database = database,
        collection = collection,
        fieldSuggestionsSupplier = { sampledFields.toList() },
        fieldTypesSupplier = { sampledFieldTypes.toMap() },
        onFieldsDiscovered = { fields, types -> mergeSampledSchema(fields, types) },
        onStatusUpdate = onStatusUpdate,
        onReadOnlyChanged = {
            updateReadOnlyBadge()
            onReadOnlyStateChanged()
        },
        onConvertToShell = { script ->
            shellPanel.setScriptText(script)
            switchMode(WorkspaceMode.SHELL)
        },
        initialJoinSpec = initialJoinSpec,
        initialFilterText = initialFilterText,
        initialSortText = initialSortText,
        diffBaselineState = diffBaselineState,
        collectionsSupplier = { cachedCollections.toList() },
        onOpenReferenceJumpTab = onOpenReferenceJumpTab,
        onOpenJoinTab = onOpenJoinTab,
        onJoinSpecUpdated = { updatedSpec ->
            val joinDisplay = updatedSpec.displayTitle()
            breadcrumbLabel.text = "${profile.displayName}  /  $database.$joinDisplay"
            updateJoinKeysSummaryBadge(updatedSpec)
            onJoinTitleUpdated?.invoke(joinDisplay)
        }
    )

    private val aggregationPanel = AggregationBuilderPanel(
        project = project,
        projectBasePath = projectBasePath,
        connectionManager = connectionManager,
        savedQueryStore = savedQueryStore,
        cryptoStore = cryptoStore,
        profile = profile,
        database = database,
        collection = collection,
        fieldSuggestionsSupplier = { sampledFields.toList() },
        onFieldsDiscovered = { fields -> mergeSampledFields(fields) },
        onStatusUpdate = onStatusUpdate,
        onReadOnlyChanged = {
            updateReadOnlyBadge()
            onReadOnlyStateChanged()
        },
        onConvertToShell = { script ->
            shellPanel.setScriptText(script)
            switchMode(WorkspaceMode.SHELL)
        },
        diffBaselineState = diffBaselineState
    )

    init {
        Disposer.register(this, findPanel)
        Disposer.register(this, aggregationPanel)
        Disposer.register(this, shellPanel)

        val topBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(findModeBtn)
                add(aggModeBtn)
                add(shellModeBtn)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
                add(joinModeBadge)
                add(joinKeysSummaryBadge)
                add(colorTagBadge)
                add(breadcrumbLabel)
                add(readOnlyBadge)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        findModeBtn.addActionListener { switchMode(WorkspaceMode.FIND) }
        aggModeBtn.addActionListener { switchMode(WorkspaceMode.AGGREGATION) }
        shellModeBtn.addActionListener { switchMode(WorkspaceMode.SHELL) }

        readOnlyBadge.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!profile.readOnly) return
                if (connectionManager.isEffectivelyReadOnly(profile)) {
                    UiHelpers.ensureWritableOrPromptUnlock(project, connectionManager, profile) {
                        updateReadOnlyBadge()
                        onReadOnlyStateChanged()
                    }
                } else {
                    connectionManager.relockForSession(profile.id)
                    updateReadOnlyBadge()
                    onReadOnlyStateChanged()
                }
            }
        })

        modeCardsPanel.add(findPanel, "FIND")
        modeCardsPanel.add(aggregationPanel, "AGG")
        modeCardsPanel.add(shellPanel, "SHELL")

        add(topBar, BorderLayout.NORTH)
        add(modeCardsPanel, BorderLayout.CENTER)

        updateReadOnlyBadge()
        updateJoinKeysSummaryBadge(initialJoinSpec)
        switchMode(initialMode)

        // UI-First Zero-Overhead open:
        // When Find executes immediately, it already discovers field schema from its returned docs —
        // do NOT fire a duplicate `sampleSchema(30)` query in parallel!
        if (initialMode == WorkspaceMode.FIND && autoExecuteInitialFind) {
            findPanel.executeFind(resetJoinPage = false, forceMetaRefresh = false)
        } else {
            sampleMetadataInBackground(includeSchemaSample = true)
        }

        I18n.addListener(this) { refreshTexts() }
    }

    fun ensureLoadedIfNeeded() {
        if (!isDisposedFlag.get()) {
            findPanel.ensureLoadedIfNeeded()
        }
    }

    fun applyQuickFilterAndRun(filterJson: String) {
        switchMode(WorkspaceMode.FIND)
        findPanel.applyQuickFilterAndRun(filterJson)
    }

    fun applyFilterTemplateOnly(filterTemplateJson: String) {
        switchMode(WorkspaceMode.FIND)
        findPanel.applyFilterTemplateOnly(filterTemplateJson)
    }

    fun switchMode(mode: WorkspaceMode) {
        findModeBtn.isSelected = mode == WorkspaceMode.FIND
        aggModeBtn.isSelected = mode == WorkspaceMode.AGGREGATION
        shellModeBtn.isSelected = mode == WorkspaceMode.SHELL
        val card = when (mode) {
            WorkspaceMode.FIND -> "FIND"
            WorkspaceMode.AGGREGATION -> "AGG"
            WorkspaceMode.SHELL -> "SHELL"
        }
        modeCardLayout.show(modeCardsPanel, card)
    }

    fun updateReadOnlyBadge() {
        if (!profile.readOnly) {
            readOnlyBadge.isVisible = false
            return
        }
        readOnlyBadge.isVisible = true
        if (connectionManager.isEffectivelyReadOnly(profile)) {
            readOnlyBadge.text = I18n.t("tree.badge.readOnly")
            readOnlyBadge.background = UiHelpers.COLOR_RED_BG
            readOnlyBadge.foreground = UiHelpers.COLOR_RED_FG
        } else {
            readOnlyBadge.text = I18n.t("tree.badge.unlocked")
            readOnlyBadge.background = UiHelpers.COLOR_ORANGE_BG
            readOnlyBadge.foreground = UiHelpers.COLOR_ORANGE_FG
        }
    }

    private fun mergeSampledFields(fields: List<String>) {
        mergeSampledSchema(fields, emptyMap())
    }

    private fun mergeSampledSchema(fields: List<String>, types: Map<String, SmartInValueType>) {
        val merged = linkedSetOf<String>()
        merged.addAll(sampledFields)
        merged.addAll(fields)
        sampledFields.clear()
        sampledFields.addAll(merged)
        types.forEach { (k, v) -> sampledFieldTypes.putIfAbsent(k, v) }
    }

    private fun sampleMetadataInBackground(includeSchemaSample: Boolean) {
        metadataFuture.getAndSet(null)?.cancel(true)
        val future = AppExecutorUtil.getAppExecutorService().submit {
            if (isDisposedFlag.get() || project.isDisposed) return@submit
            try {
                val (fields, types) = if (includeSchemaSample) {
                    connectionManager.sampleSchema(profile, database, collection, 20)
                } else {
                    emptyList<String>() to emptyMap()
                }
                val colls = runCatching {
                    connectionManager.listCollections(profile, database)
                }.getOrDefault(listOf(collection))
                if (isDisposedFlag.get() || project.isDisposed) return@submit
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposedFlag.get() || project.isDisposed) return@invokeLater
                    if (fields.isNotEmpty()) {
                        mergeSampledSchema(fields, types)
                    }
                    if (colls.isNotEmpty()) {
                        cachedCollections.clear()
                        cachedCollections.addAll(colls)
                    }
                }
            } catch (_: Throwable) {
                // Non-fatal background metadata sampling error
            }
        }
        metadataFuture.set(future)
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            findModeBtn.text = I18n.t("workspace.mode.find")
            aggModeBtn.text = I18n.t("workspace.mode.agg")
            shellModeBtn.text = I18n.t("workspace.mode.shell")
            if (profile.colorTag != ConnectionColorTag.NONE) {
                colorTagBadge.text = I18n.t(profile.colorTag.i18nKey)
            }
            updateReadOnlyBadge()
        }
    }

    override fun dispose() {
        isDisposedFlag.set(true)
        metadataFuture.getAndSet(null)?.cancel(true)
    }
}
