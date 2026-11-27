package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.ConnectionColorTag
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.MongoUriHelper
import com.maekom.mongolens.config.ProfileSource
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ConnectionsTreePanel(
    private val project: Project,
    private val projectBasePath: Path?,
    private val connectionManager: MongoConnectionManager,
    parentDisposable: Disposable,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onSyncYaml: () -> Unit,
    private val onProfileFilterChanged: (String?, MongoConnectionProfile?) -> Unit,
    private val onAddCustomUri: () -> Unit,
    private val onOpenFindTab: (MongoConnectionProfile, String, String, Boolean) -> Unit,
    private val onOpenAggTab: (MongoConnectionProfile, String, String) -> Unit,
    private val onOpenShellTab: (MongoConnectionProfile, String, String) -> Unit,
    private val onInspectIndexes: (MongoConnectionProfile, String, String) -> Unit,
    private val onOpenConnectionSettings: (MongoConnectionProfile) -> Unit,
    private val onDeleteCustomProfile: (MongoConnectionProfile) -> Unit
) : JPanel(BorderLayout()) {

    private sealed class ProfileComboItem {
        object AllProfiles : ProfileComboItem() {
            override fun toString(): String = I18n.t("header.allProfiles")
        }
        data class SingleProfile(val profile: MongoConnectionProfile) : ProfileComboItem() {
            override fun toString(): String =
                if (profile.readOnly) "${profile.displayName} 🔒" else profile.displayName
        }
        object AddCustomUri : ProfileComboItem() {
            override fun toString(): String = I18n.t("header.addCustomUri")
        }
    }

    sealed class TreeNodeData {
        data class ProfileNode(val profile: MongoConnectionProfile, var loaded: Boolean = false) : TreeNodeData()
        data class DatabaseNode(val profile: MongoConnectionProfile, val database: String, var loaded: Boolean = false) : TreeNodeData()
        data class CollectionNode(
            val profile: MongoConnectionProfile,
            val database: String,
            val collection: String,
            var estimatedCount: Long? = null
        ) : TreeNodeData()
        data class PlaceholderNode(val text: String, val isError: Boolean = false) : TreeNodeData()
    }

    private val headerLabel = JBLabel(I18n.t("tree.header")).apply {
        font = font.deriveFont(Font.BOLD)
    }

    // Compact Refresh button with icon (no truncation even in narrow sidebar)
    private val syncYamlBtn = JButton(
        I18n.t("header.syncYaml").removePrefix("↻ ").trim(),
        AllIcons.Actions.Refresh
    ).apply {
        margin = JBUI.insets(2, 6)
        toolTipText = I18n.t("header.syncYamlTooltip")
    }

    private val profileComboModel = DefaultComboBoxModel<ProfileComboItem>()
    private val profileCombo = ComboBox(profileComboModel).apply {
        // Prevent long profile names from widening the Connections sidebar or triggering horizontal scrollbars
        minimumSize = Dimension(0, JBUI.scale(30))
        preferredSize = Dimension(0, JBUI.scale(30))
    }

    private val rootNode = DefaultMutableTreeNode("Connections")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    private var allProfiles: List<MongoConnectionProfile> = emptyList()
    private var filterProfileId: String? = null
    private var suppressComboEvent = false

    init {
        buildTwoRowHeader()

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = I18n.t("tree.empty")

        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode ?: return
                when (val data = node.userObject) {
                    is TreeNodeData.ProfileNode -> {
                        val p = data.profile
                        icon = if (p.source == ProfileSource.CUSTOM) AllIcons.Nodes.WebFolder else AllIcons.Nodes.DataTables
                        val tagColor = p.colorTag.fgColor()
                        if (p.colorTag != ConnectionColorTag.NONE && tagColor != null) {
                            append("● ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, tagColor))
                        }
                        append(p.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        val srcTag = if (p.source == ProfileSource.YAML) " [YAML]" else " [Custom]"
                        append(srcTag, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        if (p.colorTag != ConnectionColorTag.NONE && tagColor != null) {
                            val shortEnv = when (p.colorTag) {
                                ConnectionColorTag.GREEN -> "Dev"
                                ConnectionColorTag.ORANGE -> "Stg"
                                ConnectionColorTag.RED -> "Prod"
                                ConnectionColorTag.NONE -> ""
                            }
                            if (shortEnv.isNotEmpty()) {
                                append(" [$shortEnv]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, tagColor))
                            }
                        }
                        if (p.readOnly) {
                            if (connectionManager.isEffectivelyReadOnly(p)) {
                                append(
                                    "  ${I18n.t("tree.badge.readOnly")}",
                                    SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UiHelpers.COLOR_RED_FG)
                                )
                            } else {
                                append(
                                    "  ${I18n.t("tree.badge.unlocked")}",
                                    SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UiHelpers.COLOR_ORANGE_FG)
                                )
                            }
                        }
                    }
                    is TreeNodeData.DatabaseNode -> {
                        icon = AllIcons.Nodes.Folder
                        append(data.database, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    is TreeNodeData.CollectionNode -> {
                        icon = AllIcons.FileTypes.Json
                        append(data.collection, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val cnt = data.estimatedCount
                        if (cnt != null) {
                            append(" (${UiHelpers.formatNumber(cnt)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                    is TreeNodeData.PlaceholderNode -> {
                        val attr = if (data.isError) {
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UiHelpers.COLOR_RED_FG)
                        } else {
                            SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                        }
                        append(data.text, attr)
                    }
                    else -> append(data?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        // Lazy Connection on expand (NO sockets opened at IDE startup)
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val data = node.userObject) {
                    is TreeNodeData.ProfileNode -> {
                        if (!data.loaded) {
                            loadDatabasesForProfile(node, data)
                        }
                    }
                    is TreeNodeData.DatabaseNode -> {
                        if (!data.loaded) {
                            loadCollectionsForDatabase(node, data)
                        }
                    }
                    else -> Unit
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val path = TreePath(node.path)
                when (val data = node.userObject) {
                    is TreeNodeData.CollectionNode -> {
                        e.consume()
                        onOpenFindTab(data.profile, data.database, data.collection, false)
                    }
                    is TreeNodeData.ProfileNode, is TreeNodeData.DatabaseNode -> {
                        e.consume()
                        if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                    }
                    else -> Unit
                }
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenuAt(e)
                    return
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val path = resolveTreePathAtPoint(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val data = node.userObject) {
                    is TreeNodeData.CollectionNode -> {
                        // Open/focus immediately on first left press so slight mouse movement never drops clicks
                        if (e.clickCount == 1) {
                            tree.selectionPath = path
                            onOpenFindTab(data.profile, data.database, data.collection, false)
                        }
                    }
                    is TreeNodeData.ProfileNode, is TreeNodeData.DatabaseNode -> {
                        if (e.clickCount == 2) {
                            if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                        }
                    }
                    else -> Unit
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenuAt(e)
                }
            }

            private fun resolveTreePathAtPoint(x: Int, y: Int): TreePath? {
                val exact = tree.getPathForLocation(x, y)
                if (exact != null) return exact
                val closest = tree.getClosestPathForLocation(x, y) ?: return null
                val bounds = tree.getPathBounds(closest) ?: return null
                return if (y in bounds.y..(bounds.y + bounds.height)) closest else null
            }

            private fun showContextMenuAt(e: MouseEvent) {
                val path = resolveTreePathAtPoint(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                showContextMenu(node, e.x, e.y)
            }
        })

        add(JBScrollPane(tree), BorderLayout.CENTER)
        I18n.addListener(parentDisposable) { refreshTexts() }
    }

    /**
     * Two-row sidebar header:
     * Row 1: Left "Connections" (Bold) | Right compact [↻ YAML 동기화]
     * Row 2: Full-width (100%) Profile selector ComboBox with minimumSize = (0, 30)
     */
    private fun buildTwoRowHeader() {
        val row1 = JPanel(BorderLayout(4, 0)).apply {
            add(headerLabel, BorderLayout.WEST)
            add(syncYamlBtn, BorderLayout.EAST)
        }

        val headerPanel = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor()),
                JBUI.Borders.empty(5, 8, 6, 8)
            )
            add(row1, BorderLayout.NORTH)
            add(profileCombo, BorderLayout.SOUTH)
        }

        syncYamlBtn.addActionListener { onSyncYaml() }

        profileCombo.addActionListener {
            if (suppressComboEvent) return@addActionListener
            when (val selected = profileCombo.selectedItem as? ProfileComboItem) {
                is ProfileComboItem.AllProfiles -> {
                    filterProfileId = null
                    rebuildTree()
                    onProfileFilterChanged(null, null)
                }
                is ProfileComboItem.SingleProfile -> {
                    filterProfileId = selected.profile.id
                    rebuildTree()
                    onProfileFilterChanged(selected.profile.id, selected.profile)
                }
                is ProfileComboItem.AddCustomUri -> {
                    restoreComboSelection()
                    onAddCustomUri()
                }
                null -> Unit
            }
        }

        add(headerPanel, BorderLayout.NORTH)
    }

    fun setProfiles(profiles: List<MongoConnectionProfile>, selectedFilterId: String?) {
        this.allProfiles = profiles
        this.filterProfileId = selectedFilterId
        rebuildProfileCombo()
        rebuildTree()
    }

    fun setProfileFilter(profileId: String?) {
        this.filterProfileId = profileId
        restoreComboSelection()
        rebuildTree()
    }

    fun repaintTree() {
        tree.repaint()
    }

    private fun rebuildProfileCombo() {
        suppressComboEvent = true
        try {
            profileComboModel.removeAllElements()
            profileComboModel.addElement(ProfileComboItem.AllProfiles)
            allProfiles.forEach { p -> profileComboModel.addElement(ProfileComboItem.SingleProfile(p)) }
            profileComboModel.addElement(ProfileComboItem.AddCustomUri)
            restoreComboSelection()
        } finally {
            suppressComboEvent = false
        }
    }

    private fun restoreComboSelection() {
        suppressComboEvent = true
        try {
            if (filterProfileId == null) {
                if (profileComboModel.size > 0) profileCombo.selectedIndex = 0
                return
            }
            for (i in 0 until profileComboModel.size) {
                val item = profileComboModel.getElementAt(i)
                if (item is ProfileComboItem.SingleProfile && item.profile.id == filterProfileId) {
                    profileCombo.selectedIndex = i
                    return
                }
            }
            if (profileComboModel.size > 0) profileCombo.selectedIndex = 0
        } finally {
            suppressComboEvent = false
        }
    }

    private fun rebuildTree() {
        rootNode.removeAllChildren()
        val visible = if (filterProfileId == null) {
            allProfiles
        } else {
            allProfiles.filter { it.id == filterProfileId }
        }

        for (profile in visible) {
            val profileNode = DefaultMutableTreeNode(TreeNodeData.ProfileNode(profile, loaded = false))
            profileNode.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.loading"))))
            rootNode.add(profileNode)
        }
        treeModel.reload()
    }

    private fun loadDatabasesForProfile(profileNode: DefaultMutableTreeNode, data: TreeNodeData.ProfileNode) {
        val profile = data.profile
        onStatusUpdate(I18n.t("header.status.connecting"), false)

        object : Task.Backgroundable(project, "Connecting to ${profile.displayName}...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val latencyMs = connectionManager.ping(profile)
                    val dbs = connectionManager.listDatabases(profile)
                    ApplicationManager.getApplication().invokeLater {
                        data.loaded = true
                        profileNode.removeAllChildren()
                        if (dbs.isEmpty()) {
                            profileNode.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.emptyDatabases"))))
                        } else {
                            for (db in dbs) {
                                val dbNode = DefaultMutableTreeNode(TreeNodeData.DatabaseNode(profile, db, loaded = false))
                                dbNode.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.loading"))))
                                profileNode.add(dbNode)
                            }
                        }
                        treeModel.nodeStructureChanged(profileNode)
                        val statusText = if (connectionManager.isEffectivelyReadOnly(profile)) {
                            "${I18n.t("header.status.connected", latencyMs)} · ${I18n.t("header.status.readOnly")}"
                        } else {
                            I18n.t("header.status.connected", latencyMs)
                        }
                        onStatusUpdate(statusText, false)
                    }
                } catch (t: Throwable) {
                    val msg = t.message ?: t.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        data.loaded = false
                        profileNode.removeAllChildren()
                        profileNode.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.errorNode", msg), isError = true)))
                        treeModel.nodeStructureChanged(profileNode)
                        onStatusUpdate(I18n.t("header.status.error", msg.take(60)), true)
                    }
                }
            }
        }.queue()
    }

    private fun loadCollectionsForDatabase(dbNode: DefaultMutableTreeNode, data: TreeNodeData.DatabaseNode) {
        val profile = data.profile
        val db = data.database

        object : Task.Backgroundable(project, "Loading collections in $db...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val collections = connectionManager.listCollections(profile, db)
                    ApplicationManager.getApplication().invokeLater {
                        data.loaded = true
                        dbNode.removeAllChildren()
                        if (collections.isEmpty()) {
                            dbNode.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.emptyCollections"))))
                        } else {
                            for (coll in collections) {
                                dbNode.add(DefaultMutableTreeNode(TreeNodeData.CollectionNode(profile, db, coll)))
                            }
                        }
                        treeModel.nodeStructureChanged(dbNode)
                    }
                } catch (t: Throwable) {
                    val msg = t.message ?: t.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        data.loaded = false
                        dbNode.removeAllChildren()
                        dbNode.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.errorNode", msg), isError = true)))
                        treeModel.nodeStructureChanged(dbNode)
                    }
                }
            }
        }.queue()
    }

    private fun refreshEstimatedCount(node: DefaultMutableTreeNode, data: TreeNodeData.CollectionNode) {
        object : Task.Backgroundable(project, "Counting ${data.collection}...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val count = connectionManager.getEstimatedCount(
                        data.profile,
                        data.database,
                        data.collection,
                        forceRefresh = true
                    )
                    ApplicationManager.getApplication().invokeLater {
                        data.estimatedCount = count
                        treeModel.nodeChanged(node)
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        onStatusUpdate(I18n.t("header.status.error", (t.message ?: "error").take(60)), true)
                    }
                }
            }
        }.queue()
    }

    private fun popupAction(
        text: String,
        icon: Icon,
        onPerform: () -> Unit
    ): DumbAwareAction = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = onPerform()
    }

    private fun showContextMenu(node: DefaultMutableTreeNode, x: Int, y: Int) {
        val group = DefaultActionGroup()
        when (val data = node.userObject) {
            is TreeNodeData.CollectionNode -> {
                group.add(popupAction(I18n.t("tree.ctx.openFind"), AllIcons.Actions.Find) {
                    onOpenFindTab(data.profile, data.database, data.collection, true)
                })
                group.add(popupAction(I18n.t("tree.ctx.openAgg"), AllIcons.Actions.ListFiles) {
                    onOpenAggTab(data.profile, data.database, data.collection)
                })
                group.add(popupAction(I18n.t("tree.ctx.openShell"), AllIcons.Nodes.Console) {
                    onOpenShellTab(data.profile, data.database, data.collection)
                })
                group.addSeparator()
                group.add(popupAction(I18n.t("tree.ctx.inspectIndexes"), AllIcons.Actions.Preview) {
                    onInspectIndexes(data.profile, data.database, data.collection)
                })
                group.addSeparator()
                group.add(popupAction(I18n.t("tree.ctx.copyName"), AllIcons.Actions.Copy) {
                    UiHelpers.copyToClipboard(data.collection)
                    UiHelpers.notify(project, I18n.t("result.copied"))
                })
                group.add(popupAction(I18n.t("tree.ctx.refreshCount"), AllIcons.Actions.Refresh) {
                    refreshEstimatedCount(node, data)
                })
            }
            is TreeNodeData.ProfileNode -> {
                val profile = data.profile
                // 1. Connection Settings / View URI (both YAML & Custom)
                group.add(popupAction(I18n.t("tree.ctx.connectionSettings"), AllIcons.General.Settings) {
                    onOpenConnectionSettings(profile)
                })

                // 2. Copy Connection String / CLI Submenu
                val copyGroup = DefaultActionGroup(I18n.t("tree.ctx.copyUriMenu"), true).apply {
                    templatePresentation.icon = AllIcons.Actions.Copy
                    add(popupAction(I18n.t("tree.ctx.copyFullUri"), AllIcons.Actions.Copy) {
                        UiHelpers.copyToClipboard(profile.effectiveConnectionUri())
                        UiHelpers.notify(project, I18n.t("tree.ctx.copiedFullUri"))
                    })
                    add(popupAction(I18n.t("tree.ctx.copyMaskedUri"), AllIcons.Actions.ToggleVisibility) {
                        UiHelpers.copyToClipboard(MongoUriHelper.maskPasswordInUri(profile.effectiveConnectionUri()))
                        UiHelpers.notify(project, I18n.t("tree.ctx.copiedMaskedUri"))
                    })
                    add(popupAction(I18n.t("tree.ctx.copyMongoshCli"), AllIcons.Nodes.Console) {
                        UiHelpers.copyToClipboard(profile.buildMongoshCliCommand(projectBasePath))
                        UiHelpers.notify(project, I18n.t("tree.ctx.copiedMongoshCli"))
                    })
                }
                group.add(copyGroup)

                group.addSeparator()

                // 3. Reconnect & Refresh (closes cached MongoClient immediately)
                group.add(popupAction(I18n.t("tree.ctx.reconnect"), AllIcons.Actions.Refresh) {
                    connectionManager.closeClient(profile.id)
                    data.loaded = false
                    node.removeAllChildren()
                    node.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.loading"))))
                    treeModel.nodeStructureChanged(node)
                    loadDatabasesForProfile(node, data)
                    tree.expandPath(TreePath(node.path))
                })

                if (profile.readOnly) {
                    group.addSeparator()
                    if (connectionManager.isEffectivelyReadOnly(profile)) {
                        group.add(popupAction(I18n.t("tree.ctx.unlockSession"), AllIcons.Nodes.Padlock) {
                            val confirm = Messages.showYesNoDialog(
                                project,
                                I18n.t("readonly.unlock.msg", profile.displayName),
                                I18n.t("readonly.unlock.title"),
                                I18n.t("readonly.unlock.action"),
                                Messages.getCancelButton(),
                                Messages.getWarningIcon()
                            )
                            if (confirm == Messages.YES) {
                                connectionManager.unlockForSession(profile.id)
                                tree.repaint()
                            }
                        })
                    } else {
                        group.add(popupAction(I18n.t("tree.ctx.relockSession"), AllIcons.Nodes.Padlock) {
                            connectionManager.relockForSession(profile.id)
                            tree.repaint()
                        })
                    }
                }
                if (profile.source == ProfileSource.CUSTOM) {
                    group.addSeparator()
                    group.add(popupAction(I18n.t("tree.ctx.deleteCustom"), AllIcons.General.Remove) {
                        val confirm = Messages.showYesNoDialog(
                            project,
                            I18n.t("tree.deleteCustom.confirm", profile.displayName),
                            I18n.t("tree.ctx.deleteCustom"),
                            Messages.getWarningIcon()
                        )
                        if (confirm == Messages.YES) {
                            onDeleteCustomProfile(profile)
                        }
                    })
                }
            }
            is TreeNodeData.DatabaseNode -> {
                group.add(popupAction(I18n.t("tree.ctx.reconnect"), AllIcons.Actions.Refresh) {
                    data.loaded = false
                    node.removeAllChildren()
                    node.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.loading"))))
                    treeModel.nodeStructureChanged(node)
                    loadCollectionsForDatabase(node, data)
                    tree.expandPath(TreePath(node.path))
                })
            }
            else -> return
        }

        val popupMenu = ActionManager.getInstance().createActionPopupMenu("MaekomMongoLensPopup", group)
        popupMenu.setTargetComponent(tree)
        popupMenu.component.show(tree, x, y)
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            headerLabel.text = I18n.t("tree.header")
            syncYamlBtn.text = I18n.t("header.syncYaml").removePrefix("↻ ").trim()
            syncYamlBtn.toolTipText = I18n.t("header.syncYamlTooltip")
            tree.emptyText.text = I18n.t("tree.empty")
            profileCombo.repaint()
            tree.repaint()
        }
    }
}
