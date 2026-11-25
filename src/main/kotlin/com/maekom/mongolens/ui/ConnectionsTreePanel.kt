package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.ProfileSource
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ConnectionsTreePanel(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    parentDisposable: Disposable,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onOpenFindTab: (MongoConnectionProfile, String, String, Boolean) -> Unit,
    private val onOpenAggTab: (MongoConnectionProfile, String, String) -> Unit,
    private val onOpenShellTab: (MongoConnectionProfile, String, String) -> Unit,
    private val onEditCustomProfile: (MongoConnectionProfile) -> Unit,
    private val onDeleteCustomProfile: (MongoConnectionProfile) -> Unit
) : JPanel(BorderLayout()) {

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
        border = JBUI.Borders.empty(6, 10)
    }

    private val rootNode = DefaultMutableTreeNode("Connections")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    private var allProfiles: List<MongoConnectionProfile> = emptyList()
    private var filterProfileId: String? = null // null means All

    init {
        val headerPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            add(headerLabel, BorderLayout.WEST)
        }
        add(headerPanel, BorderLayout.NORTH)

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
                        append(p.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        val srcTag = if (p.source == ProfileSource.YAML) " [YAML]" else " [Custom]"
                        append(srcTag, SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val data = node.userObject) {
                    is TreeNodeData.CollectionNode -> {
                        // Normal click (single or double) opens or focuses existing tab — never duplicates
                        onOpenFindTab(data.profile, data.database, data.collection, false)
                    }
                    is TreeNodeData.ProfileNode -> {
                        if (e.clickCount == 2) {
                            tree.expandPath(path)
                        }
                    }
                    is TreeNodeData.DatabaseNode -> {
                        if (e.clickCount == 2) {
                            tree.expandPath(path)
                        }
                    }
                    else -> Unit
                }
            }

            private fun maybeShowContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                showContextMenu(node, e.x, e.y)
            }
        })

        add(JBScrollPane(tree), BorderLayout.CENTER)
        I18n.addListener(parentDisposable) { refreshTexts() }
    }

    fun setProfiles(profiles: List<MongoConnectionProfile>, selectedFilterId: String?) {
        this.allProfiles = profiles
        this.filterProfileId = selectedFilterId
        rebuildTree()
    }

    fun setProfileFilter(profileId: String?) {
        this.filterProfileId = profileId
        rebuildTree()
    }

    fun repaintTree() {
        tree.repaint()
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
            // Add dummy child so expand handle appears without connecting yet (Lazy Connection)
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
                    val count = connectionManager.getEstimatedCount(data.profile, data.database, data.collection)
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

    private fun showContextMenu(node: DefaultMutableTreeNode, x: Int, y: Int) {
        val menu = JPopupMenu()
        when (val data = node.userObject) {
            is TreeNodeData.CollectionNode -> {
                menu.add(JMenuItem(I18n.t("tree.ctx.openFind")).apply {
                    addActionListener { onOpenFindTab(data.profile, data.database, data.collection, true) }
                })
                menu.add(JMenuItem(I18n.t("tree.ctx.openAgg")).apply {
                    addActionListener { onOpenAggTab(data.profile, data.database, data.collection) }
                })
                menu.add(JMenuItem(I18n.t("tree.ctx.openShell")).apply {
                    addActionListener { onOpenShellTab(data.profile, data.database, data.collection) }
                })
                menu.addSeparator()
                menu.add(JMenuItem(I18n.t("tree.ctx.copyName")).apply {
                    addActionListener {
                        UiHelpers.copyToClipboard(data.collection)
                        UiHelpers.notify(project, I18n.t("result.copied"))
                    }
                })
                menu.add(JMenuItem(I18n.t("tree.ctx.refreshCount")).apply {
                    addActionListener { refreshEstimatedCount(node, data) }
                })
            }
            is TreeNodeData.ProfileNode -> {
                val profile = data.profile
                menu.add(JMenuItem(I18n.t("tree.ctx.reconnect")).apply {
                    addActionListener {
                        data.loaded = false
                        node.removeAllChildren()
                        node.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.loading"))))
                        treeModel.nodeStructureChanged(node)
                        loadDatabasesForProfile(node, data)
                        tree.expandPath(TreePath(node.path))
                    }
                })
                if (profile.readOnly) {
                    menu.addSeparator()
                    if (connectionManager.isEffectivelyReadOnly(profile)) {
                        menu.add(JMenuItem(I18n.t("tree.ctx.unlockSession")).apply {
                            addActionListener {
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
                            }
                        })
                    } else {
                        menu.add(JMenuItem(I18n.t("tree.ctx.relockSession")).apply {
                            addActionListener {
                                connectionManager.relockForSession(profile.id)
                                tree.repaint()
                            }
                        })
                    }
                }
                if (profile.source == ProfileSource.CUSTOM) {
                    menu.addSeparator()
                    menu.add(JMenuItem(I18n.t("tree.ctx.editCustom")).apply {
                        addActionListener { onEditCustomProfile(profile) }
                    })
                    menu.add(JMenuItem(I18n.t("tree.ctx.deleteCustom")).apply {
                        addActionListener {
                            val confirm = Messages.showYesNoDialog(
                                project,
                                I18n.t("tree.deleteCustom.confirm", profile.displayName),
                                I18n.t("tree.ctx.deleteCustom"),
                                Messages.getWarningIcon()
                            )
                            if (confirm == Messages.YES) {
                                onDeleteCustomProfile(profile)
                            }
                        }
                    })
                }
            }
            is TreeNodeData.DatabaseNode -> {
                menu.add(JMenuItem(I18n.t("tree.ctx.reconnect")).apply {
                    addActionListener {
                        data.loaded = false
                        node.removeAllChildren()
                        node.add(DefaultMutableTreeNode(TreeNodeData.PlaceholderNode(I18n.t("tree.loading"))))
                        treeModel.nodeStructureChanged(node)
                        loadCollectionsForDatabase(node, data)
                        tree.expandPath(TreePath(node.path))
                    }
                })
            }
            else -> return
        }
        menu.show(tree, x, y)
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            headerLabel.text = I18n.t("tree.header")
            tree.emptyText.text = I18n.t("tree.empty")
            tree.repaint()
        }
    }
}
