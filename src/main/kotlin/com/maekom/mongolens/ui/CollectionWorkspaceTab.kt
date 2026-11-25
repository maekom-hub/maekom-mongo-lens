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
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

class CollectionWorkspaceTab(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    savedQueryStore: SavedQueryStore,
    val profile: MongoConnectionProfile,
    val database: String,
    val collection: String,
    initialMode: WorkspaceMode = WorkspaceMode.FIND,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onReadOnlyStateChanged: () -> Unit
) : JPanel(BorderLayout()), Disposable {

    enum class WorkspaceMode {
        FIND,
        AGGREGATION
    }

    private val sampledFields = CopyOnWriteArrayList<String>()

    private val findModeBtn = JToggleButton(I18n.t("workspace.mode.find"), initialMode == WorkspaceMode.FIND)
    private val aggModeBtn = JToggleButton(I18n.t("workspace.mode.agg"), initialMode == WorkspaceMode.AGGREGATION)

    private val breadcrumbLabel = JBLabel("${profile.displayName}  /  $database.$collection").apply {
        font = font.deriveFont(Font.BOLD)
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

    private val findPanel = FindQueryPanel(
        project = project,
        connectionManager = connectionManager,
        savedQueryStore = savedQueryStore,
        profile = profile,
        database = database,
        collection = collection,
        fieldSuggestionsSupplier = { sampledFields.toList() },
        onFieldsDiscovered = { fields -> mergeSampledFields(fields) },
        onStatusUpdate = onStatusUpdate,
        onReadOnlyChanged = {
            updateReadOnlyBadge()
            onReadOnlyStateChanged()
        }
    )

    private val aggregationPanel = AggregationBuilderPanel(
        project = project,
        connectionManager = connectionManager,
        savedQueryStore = savedQueryStore,
        profile = profile,
        database = database,
        collection = collection,
        fieldSuggestionsSupplier = { sampledFields.toList() },
        onFieldsDiscovered = { fields -> mergeSampledFields(fields) },
        onStatusUpdate = onStatusUpdate,
        onReadOnlyChanged = {
            updateReadOnlyBadge()
            onReadOnlyStateChanged()
        }
    )

    init {
        Disposer.register(this, findPanel)
        Disposer.register(this, aggregationPanel)

        val topBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                add(findModeBtn)
                add(aggModeBtn)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
                add(breadcrumbLabel)
                add(readOnlyBadge)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        findModeBtn.addActionListener { switchMode(WorkspaceMode.FIND) }
        aggModeBtn.addActionListener { switchMode(WorkspaceMode.AGGREGATION) }

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

        add(topBar, BorderLayout.NORTH)
        add(modeCardsPanel, BorderLayout.CENTER)

        updateReadOnlyBadge()
        switchMode(initialMode)

        // Pre-sample 20 documents asynchronously for field name autocompletion and initial view
        if (initialMode == WorkspaceMode.FIND) {
            findPanel.executeFind()
        } else {
            sampleFieldsInBackground()
        }

        I18n.addListener(this) { refreshTexts() }
    }

    fun switchMode(mode: WorkspaceMode) {
        findModeBtn.isSelected = mode == WorkspaceMode.FIND
        aggModeBtn.isSelected = mode == WorkspaceMode.AGGREGATION
        modeCardLayout.show(modeCardsPanel, if (mode == WorkspaceMode.FIND) "FIND" else "AGG")
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
        val merged = linkedSetOf<String>()
        merged.addAll(sampledFields)
        merged.addAll(fields)
        sampledFields.clear()
        sampledFields.addAll(merged)
    }

    private fun sampleFieldsInBackground() {
        object : Task.Backgroundable(project, "Sampling schema for $collection...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val fields = connectionManager.sampleFieldNames(profile, database, collection, 20)
                    ApplicationManager.getApplication().invokeLater {
                        mergeSampledFields(fields)
                    }
                } catch (_: Throwable) {
                    // Non-fatal schema sampling error
                }
            }
        }.queue()
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            findModeBtn.text = I18n.t("workspace.mode.find")
            aggModeBtn.text = I18n.t("workspace.mode.agg")
            updateReadOnlyBadge()
        }
    }

    override fun dispose() {
        // Child panels disposed via Disposer
    }
}
