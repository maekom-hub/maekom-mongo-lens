package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.TabInfo
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.config.VariableStore
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import java.util.UUID
import javax.swing.SwingUtilities

class WorkspaceTabManager(
    private val project: Project,
    private val tabs: JBTabs,
    private val parentDisposable: Disposable,
    private val connectionManager: MongoConnectionManager,
    private val savedQueryStore: SavedQueryStore,
    private val variableStore: VariableStore,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onReadOnlyStateChanged: () -> Unit,
    private val onTabCountChanged: (Int) -> Unit
) {
    data class TabEntry(
        val tabId: String,
        val tabKey: String,
        val cloneNumber: Int,
        val tabInfo: TabInfo,
        val workspaceTab: CollectionWorkspaceTab
    )

    private val entriesById = linkedMapOf<String, TabEntry>()
    private val entriesByKey = linkedMapOf<String, MutableList<TabEntry>>()
    private val entryByTabInfo = IdentityHashMap<TabInfo, TabEntry>()

    @Volatile
    private var contextMenuTargetTabId: String? = null

    init {
        installTabMouseAndContextMenuHandlers()
    }

    fun buildTabKey(profile: MongoConnectionProfile, database: String, collection: String): String {
        return "${profile.displayName}:$database:$collection"
    }

    /**
     * Left-click / Enter on Connections tree: focuses existing tab for the same tabKey if present,
     * preserving its query & results without creating duplicates.
     */
    fun openOrFocusCollectionTab(
        profile: MongoConnectionProfile,
        database: String,
        collection: String
    ): TabEntry {
        val tabKey = buildTabKey(profile, database, collection)
        val existing = entriesByKey[tabKey]?.firstOrNull()
        if (existing != null) {
            tabs.select(existing.tabInfo, true)
            return existing
        }
        return createTab(profile, database, collection, CollectionWorkspaceTab.WorkspaceMode.FIND)
    }

    /**
     * Intentional clone from right-click context menu ("Open in New Find/Aggregation/Shell Tab").
     * Creates a numbered tab such as `local: orders (2)` without overwriting existing tabKey entries.
     */
    fun openNewClonedTab(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        mode: CollectionWorkspaceTab.WorkspaceMode
    ): TabEntry {
        return createTab(profile, database, collection, mode)
    }

    private fun createTab(
        profile: MongoConnectionProfile,
        database: String,
        collection: String,
        mode: CollectionWorkspaceTab.WorkspaceMode
    ): TabEntry {
        val tabId = UUID.randomUUID().toString()
        val tabKey = buildTabKey(profile, database, collection)
        val existingForKey = entriesByKey[tabKey].orEmpty()
        val usedNumbers = existingForKey.map { it.cloneNumber }.toSet()
        var cloneNumber = 1
        while (cloneNumber in usedNumbers) {
            cloneNumber++
        }

        val workspaceTab = CollectionWorkspaceTab(
            project = project,
            connectionManager = connectionManager,
            savedQueryStore = savedQueryStore,
            variableStore = variableStore,
            profile = profile,
            database = database,
            collection = collection,
            initialMode = mode,
            onStatusUpdate = onStatusUpdate,
            onReadOnlyStateChanged = {
                updateAllReadOnlyBadges()
                onReadOnlyStateChanged()
            }
        )
        Disposer.register(parentDisposable, workspaceTab)

        val numberSuffix = if (cloneNumber >= 2) " ($cloneNumber)" else ""
        val modeSuffix = when (mode) {
            CollectionWorkspaceTab.WorkspaceMode.AGGREGATION -> " [Agg]"
            CollectionWorkspaceTab.WorkspaceMode.SHELL -> " [Shell]"
            else -> ""
        }
        val tabTitle = "${profile.displayName}: $collection$numberSuffix$modeSuffix"

        val tabInfo = TabInfo(workspaceTab).apply {
            setText(tabTitle)
            setTooltipText("${profile.displayName} / $database.$collection$numberSuffix")
            setIcon(AllIcons.FileTypes.Json)
        }

        // Close path #1: Right-side '✕' button delegates directly to unified closeTab(tabId)
        val closeAction = object : AnAction(I18n.t("tab.close"), I18n.t("tab.close"), AllIcons.Actions.Close) {
            init {
                templatePresentation.hoveredIcon = AllIcons.Actions.CloseHovered
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.text = I18n.t("tab.close")
                e.presentation.icon = AllIcons.Actions.Close
                e.presentation.hoveredIcon = AllIcons.Actions.CloseHovered
            }

            override fun actionPerformed(e: AnActionEvent) {
                closeTab(tabId)
            }
        }
        tabInfo.setTabLabelActions(DefaultActionGroup(closeAction), "MaekomMongoLensTab")

        val entry = TabEntry(
            tabId = tabId,
            tabKey = tabKey,
            cloneNumber = cloneNumber,
            tabInfo = tabInfo,
            workspaceTab = workspaceTab
        )

        entriesById[tabId] = entry
        entriesByKey.computeIfAbsent(tabKey) { mutableListOf() }.add(entry)
        entryByTabInfo[tabInfo] = entry

        tabs.addTab(tabInfo)
        tabs.select(tabInfo, true)
        onTabCountChanged(entriesById.size)
        return entry
    }

    /**
     * Single unified atomic close pipeline invoked by ALL 4 close paths:
     * 1. Right '✕' button
     * 2. Middle-click (mouse wheel) on tab header
     * 3. Context menu: Close Tab
     * 4. Context menu: Close Other Tabs / Close All Tabs
     */
    fun closeTab(tabId: String) {
        val entry = entriesById.remove(tabId) ?: return
        entriesByKey[entry.tabKey]?.let { list ->
            list.removeAll { it.tabId == tabId }
            if (list.isEmpty()) {
                entriesByKey.remove(entry.tabKey)
            }
        }
        entryByTabInfo.remove(entry.tabInfo)
        if (contextMenuTargetTabId == tabId) {
            contextMenuTargetTabId = null
        }

        tabs.removeTab(entry.tabInfo)
        Disposer.dispose(entry.workspaceTab)
        onTabCountChanged(entriesById.size)
    }

    fun closeOtherTabs(keepTabId: String) {
        val idsToClose = entriesById.keys.filter { it != keepTabId }.toList()
        idsToClose.forEach { closeTab(it) }
    }

    fun closeAllTabs() {
        val allIds = entriesById.keys.toList()
        allIds.forEach { closeTab(it) }
    }

    fun updateAllReadOnlyBadges() {
        entriesById.values.forEach { it.workspaceTab.updateReadOnlyBadge() }
    }

    fun clearRegistry() {
        entriesById.clear()
        entriesByKey.clear()
        entryByTabInfo.clear()
        contextMenuTargetTabId = null
    }

    private fun resolveTabId(info: TabInfo?): String? = info?.let { entryByTabInfo[it]?.tabId }

    private fun installTabMouseAndContextMenuHandlers() {
        // Close path #2: Middle-click (mouse wheel button) -> closeTab(tabId)
        tabs.addTabMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val info = tabs.findInfo(e) ?: return
                val tabId = resolveTabId(info) ?: return
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    e.consume()
                    closeTab(tabId)
                } else if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) {
                    contextMenuTargetTabId = tabId
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    contextMenuTargetTabId = resolveTabId(tabs.findInfo(e))
                }
            }
        })

        // Close paths #3 & #4: Tab right-click context menu -> closeTab(tabId)
        val tabPopupGroup = DefaultActionGroup().apply {
            add(object : AnAction() {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.text = I18n.t("tab.close")
                    e.presentation.isEnabled = (contextMenuTargetTabId ?: resolveTabId(tabs.selectedInfo)) != null
                }
                override fun actionPerformed(e: AnActionEvent) {
                    val targetId = contextMenuTargetTabId ?: resolveTabId(tabs.selectedInfo) ?: return
                    closeTab(targetId)
                }
            })
            add(object : AnAction() {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.text = I18n.t("tab.closeOthers")
                    e.presentation.isEnabled = entriesById.size > 1
                }
                override fun actionPerformed(e: AnActionEvent) {
                    val keepId = contextMenuTargetTabId ?: resolveTabId(tabs.selectedInfo) ?: return
                    closeOtherTabs(keepId)
                }
            })
            add(object : AnAction() {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.text = I18n.t("tab.closeAll")
                    e.presentation.isEnabled = entriesById.isNotEmpty()
                }
                override fun actionPerformed(e: AnActionEvent) {
                    closeAllTabs()
                }
            })
        }
        tabs.setPopupGroup(tabPopupGroup, "MaekomMongoLensTabPopup", true)
    }
}

typealias TabManager = WorkspaceTabManager
