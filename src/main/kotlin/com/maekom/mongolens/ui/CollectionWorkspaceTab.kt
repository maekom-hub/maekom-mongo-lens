package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
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
    onOpenJoinTab: ((joinSpec: JoinSpec, currentFilter: String, currentSort: String) -> Unit)? = null
) : JPanel(BorderLayout()), Disposable {

    enum class WorkspaceMode {
        FIND,
        AGGREGATION,
        SHELL
    }

    private val sampledFields = CopyOnWriteArrayList<String>()
    private val sampledFieldTypes = ConcurrentHashMap<String, SmartInValueType>()
    private val cachedCollections = CopyOnWriteArrayList<String>(listOf(collection))

    private val findModeBtn = JToggleButton(I18n.t("workspace.mode.find"), initialMode == WorkspaceMode.FIND).apply {
        margin = JBUI.insets(2, 8)
    }
    private val aggModeBtn = JToggleButton(I18n.t("workspace.mode.agg"), initialMode == WorkspaceMode.AGGREGATION).apply {
        margin = JBUI.insets(2, 8)
    }
    private val shellModeBtn = JToggleButton(I18n.t("workspace.mode.shell"), initialMode == WorkspaceMode.SHELL).apply {
        margin = JBUI.insets(2, 8)
    }

    private val breadcrumbLabel = JBLabel(
        if (initialJoinSpec != null) {
            "${profile.displayName}  /  $database.${initialJoinSpec.leftCollection} ⋈ ${initialJoinSpec.rightCollection}"
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
        onOpenJoinTab = onOpenJoinTab
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
        switchMode(initialMode)

        // Pre-sample 30 documents + collection names asynchronously for autocomplete
        sampleMetadataInBackground()
        if (initialMode == WorkspaceMode.FIND && autoExecuteInitialFind) {
            findPanel.executeFind()
        }

        I18n.addListener(this) { refreshTexts() }
    }

    fun applyQuickFilterAndRun(filterJson: String) {
        switchMode(WorkspaceMode.FIND)
        findPanel.applyQuickFilterAndRun(filterJson)
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

    private fun sampleMetadataInBackground() {
        object : Task.Backgroundable(project, "Sampling schema for $collection...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val (fields, types) = connectionManager.sampleSchema(profile, database, collection, 30)
                    val colls = try {
                        connectionManager.listCollections(profile, database)
                    } catch (_: Throwable) {
                        listOf(collection)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        mergeSampledSchema(fields, types)
                        if (colls.isNotEmpty()) {
                            cachedCollections.clear()
                            cachedCollections.addAll(colls)
                        }
                    }
                } catch (_: Throwable) {
                    // Non-fatal background metadata sampling error
                }
            }
        }.queue()
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            findModeBtn.text = I18n.t("workspace.mode.find")
            aggModeBtn.text = I18n.t("workspace.mode.agg")
            shellModeBtn.text = I18n.t("workspace.mode.shell")
            updateReadOnlyBadge()
        }
    }

    override fun dispose() {
        // Child panels disposed via Disposer
    }
}
