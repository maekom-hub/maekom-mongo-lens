package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.IndexScanType
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import com.maekom.mongolens.mongo.QueryExecutionResult
import org.bson.BsonArray
import org.bson.BsonDateTime
import org.bson.BsonDocument
import org.bson.BsonValue
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ResultViewerPanel(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val onRequestRefresh: () -> Unit,
    private val onPrevPage: (() -> Unit)? = null,
    private val onNextPage: (() -> Unit)? = null,
    private val onReadOnlyChanged: () -> Unit = {}
) : JPanel(BorderLayout()), Disposable {

    private data class BsonTreeNodeData(
        val keyLabel: String,
        val value: BsonValue,
        val rootDocIndex: Int
    )

    private var currentResult: QueryExecutionResult? = null
    private var currentSkip: Int = 0
    private var currentLimit: Int = 50
    private var previewBannerText: String? = null

    private val summaryLabel = JBLabel(" ")
    private val indexBadge = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_GREEN_BG, UiHelpers.COLOR_GREEN_FG).apply {
        isVisible = false
    }
    private val previewBannerLabel = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_BLUE_BG, UiHelpers.COLOR_BLUE_FG).apply {
        isVisible = false
    }

    private val treeViewBtn = JToggleButton(I18n.t("result.view.tree"), true)
    private val jsonViewBtn = JToggleButton(I18n.t("result.view.json"), false)
    private val tableViewBtn = JToggleButton(I18n.t("result.view.table"), false)

    private val prevBtn = JButton(I18n.t("result.prev"))
    private val nextBtn = JButton(I18n.t("result.next"))

    private val copyJsonBtn = JButton(I18n.t("result.doc.copyJson"))
    private val copyIdBtn = JButton(I18n.t("result.doc.copyId"))
    private val editDocBtn = JButton(I18n.t("result.doc.edit"))
    private val cloneDocBtn = JButton(I18n.t("result.doc.clone"))
    private val deleteDocBtn = JButton(I18n.t("result.doc.delete"))

    private val cardLayout = CardLayout()
    private val cardsPanel = JPanel(cardLayout)

    // 1. Tree View
    private val treeRoot = DefaultMutableTreeNode("Results")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val resultTree = object : Tree(treeModel) {
        override fun getToolTipText(event: MouseEvent?): String? {
            if (event == null) return null
            val path = getPathForLocation(event.x, event.y) ?: return null
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
            val data = node.userObject as? BsonTreeNodeData ?: return null
            val dt = data.value as? BsonDateTime ?: return null
            return I18n.t("result.date.kstTooltip", UiHelpers.formatKst(dt.value))
        }
    }

    // 2. Raw JSON View (Read-only IntelliJ Editor with native Ctrl+F search)
    private val rawJsonDocument = EditorFactory.getInstance().createDocument("[]")
    private val rawJsonEditor: EditorEx = (EditorFactory.getInstance().createViewer(
        rawJsonDocument,
        project,
        com.intellij.openapi.editor.EditorKind.MAIN_EDITOR
    ) as EditorEx).apply {
        highlighter = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, MongoJsonEditor.resolveJsonFileType())
        settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = true
            isAutoCodeFoldingEnabled = true
            isUseSoftWraps = false
            isRightMarginShown = false
        }
    }

    // 3. Table View
    private val tableModel = MongoResultTableModel()
    private val resultTable = object : JBTable(tableModel) {
        override fun getToolTipText(event: MouseEvent?): String? {
            if (event == null) return null
            val row = rowAtPoint(event.point)
            val col = columnAtPoint(event.point)
            if (row < 0 || col < 0) return null
            val modelRow = convertRowIndexToModel(row)
            val modelCol = convertColumnIndexToModel(col)
            val bsonVal = tableModel.getBsonValueAt(modelRow, modelCol)
            return if (bsonVal is BsonDateTime) {
                I18n.t("result.date.kstTooltip", UiHelpers.formatKst(bsonVal.value))
            } else {
                bsonVal?.let { "${UiHelpers.bsonTypeTag(it)}: ${UiHelpers.bsonDisplayValue(it)}" }
            }
        }
    }

    private var activeView = "TREE"

    init {
        buildHeaderBar()
        buildTreeView()
        buildTableView()

        cardsPanel.add(JBScrollPane(resultTree), "TREE")
        cardsPanel.add(rawJsonEditor.component, "JSON")
        cardsPanel.add(JBScrollPane(resultTable), "TABLE")

        add(cardsPanel, BorderLayout.CENTER)
        I18n.addListener(this) { refreshTexts() }
    }

    private fun buildHeaderBar() {
        val topWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
        }

        val leftBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
            summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD)
            add(summaryLabel)
            add(indexBadge)
            add(previewBannerLabel)
        }

        val rightBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            if (onPrevPage != null && onNextPage != null) {
                prevBtn.isEnabled = false
                nextBtn.isEnabled = false
                prevBtn.addActionListener { onPrevPage.invoke() }
                nextBtn.addActionListener { onNextPage.invoke() }
                add(prevBtn)
                add(nextBtn)
            }

            treeViewBtn.addActionListener { switchView("TREE") }
            jsonViewBtn.addActionListener { switchView("JSON") }
            tableViewBtn.addActionListener { switchView("TABLE") }
            add(treeViewBtn)
            add(jsonViewBtn)
            add(tableViewBtn)
        }

        val docActionBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            copyJsonBtn.addActionListener { actionCopyJson() }
            copyIdBtn.addActionListener { actionCopyId() }
            editDocBtn.addActionListener { actionEditDocument() }
            cloneDocBtn.addActionListener { actionCloneDocument() }
            deleteDocBtn.addActionListener { actionDeleteDocument() }

            listOf(copyJsonBtn, copyIdBtn, editDocBtn, cloneDocBtn, deleteDocBtn).forEach { btn ->
                btn.margin = JBUI.insets(1, 6)
                add(btn)
            }
        }

        val row1 = JPanel(BorderLayout()).apply {
            add(leftBar, BorderLayout.WEST)
            add(rightBar, BorderLayout.EAST)
        }

        topWrapper.add(row1, BorderLayout.NORTH)
        topWrapper.add(docActionBar, BorderLayout.SOUTH)
        add(topWrapper, BorderLayout.NORTH)
    }

    private fun buildTreeView() {
        resultTree.isRootVisible = false
        resultTree.showsRootHandles = true
        ToolTipManager.sharedInstance().registerComponent(resultTree)

        resultTree.cellRenderer = object : ColoredTreeCellRenderer() {
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
                val data = node.userObject as? BsonTreeNodeData
                if (data == null) {
                    append(node.userObject?.toString() ?: "", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    return
                }

                append(data.keyLabel, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(": ", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                val valColor = UiHelpers.bsonTypeColor(data.value)
                append(
                    UiHelpers.bsonDisplayValue(data.value),
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, valColor)
                )
                append(
                    "  [${UiHelpers.bsonTypeTag(data.value)}]",
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, UiHelpers.BSON_NULL_COLOR)
                )
            }
        }

        resultTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
            private fun maybeShowContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = resultTree.getPathForLocation(e.x, e.y)
                if (path != null) {
                    resultTree.selectionPath = path
                }
                showDocumentContextMenu(resultTree, e.x, e.y)
            }
        })
    }

    private fun buildTableView() {
        resultTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        resultTable.rowSorter = TableRowSorter(tableModel)
        resultTable.rowHeight = 24
        ToolTipManager.sharedInstance().registerComponent(resultTable)

        resultTable.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (!isSelected) {
                    val modelRow = resultTable.convertRowIndexToModel(row)
                    val modelCol = resultTable.convertColumnIndexToModel(column)
                    val bsonVal = tableModel.getBsonValueAt(modelRow, modelCol)
                    if (bsonVal != null) {
                        comp.foreground = UiHelpers.bsonTypeColor(bsonVal)
                    }
                }
                return comp
            }
        })

        resultTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
            private fun maybeShowContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = resultTable.rowAtPoint(e.point)
                if (row >= 0) {
                    resultTable.setRowSelectionInterval(row, row)
                }
                showDocumentContextMenu(resultTable, e.x, e.y)
            }
        })
    }

    private fun switchView(viewName: String) {
        activeView = viewName
        treeViewBtn.isSelected = viewName == "TREE"
        jsonViewBtn.isSelected = viewName == "JSON"
        tableViewBtn.isSelected = viewName == "TABLE"
        cardLayout.show(cardsPanel, viewName)
    }

    fun updateResults(
        result: QueryExecutionResult,
        skip: Int = 0,
        limit: Int = result.documents.size.coerceAtLeast(1),
        previewBanner: String? = null
    ) {
        this.currentResult = result
        this.currentSkip = skip
        this.currentLimit = limit
        this.previewBannerText = previewBanner

        updateSummaryAndBadges()
        populateTree(result.documents)
        populateRawJson(result.documents)
        populateTable(result.documents)
    }

    private fun updateSummaryAndBadges() {
        val result = currentResult ?: return
        val docs = result.documents
        if (docs.isEmpty()) {
            summaryLabel.text = I18n.t("result.summaryEmpty", result.elapsedMs)
        } else {
            val from = currentSkip + 1
            val to = currentSkip + docs.size
            summaryLabel.text = if (result.totalCount < 0 || result.isApproximateCount) {
                I18n.t("result.summaryApprox", UiHelpers.formatNumber(from.toLong()), UiHelpers.formatNumber(to.toLong()), UiHelpers.formatNumber(to.toLong()), result.elapsedMs)
            } else {
                I18n.t("result.summary", UiHelpers.formatNumber(from.toLong()), UiHelpers.formatNumber(to.toLong()), UiHelpers.formatNumber(result.totalCount), result.elapsedMs)
            }
        }

        when (result.scanType) {
            IndexScanType.IXSCAN -> {
                indexBadge.text = I18n.t("result.badge.ixscan")
                indexBadge.background = UiHelpers.COLOR_GREEN_BG
                indexBadge.foreground = UiHelpers.COLOR_GREEN_FG
                indexBadge.toolTipText = I18n.t("result.badge.ixscanTooltip", result.indexName ?: "index")
                indexBadge.isVisible = true
            }
            IndexScanType.COLLSCAN -> {
                indexBadge.text = I18n.t("result.badge.collscan")
                indexBadge.background = UiHelpers.COLOR_ORANGE_BG
                indexBadge.foreground = UiHelpers.COLOR_ORANGE_FG
                indexBadge.toolTipText = I18n.t("result.badge.collscanTooltip")
                indexBadge.isVisible = true
            }
            IndexScanType.UNKNOWN -> {
                indexBadge.isVisible = false
            }
        }

        if (!previewBannerText.isNullOrBlank()) {
            previewBannerLabel.text = previewBannerText
            previewBannerLabel.isVisible = true
        } else {
            previewBannerLabel.isVisible = false
        }

        prevBtn.isEnabled = currentSkip > 0
        nextBtn.isEnabled = docs.size >= currentLimit && (result.totalCount < 0 || currentSkip + docs.size < result.totalCount)
    }

    private fun populateTree(docs: List<BsonDocument>) {
        treeRoot.removeAllChildren()
        if (docs.isEmpty()) {
            treeRoot.add(DefaultMutableTreeNode(I18n.t("result.empty")))
        } else {
            docs.forEachIndexed { idx, doc ->
                val idPreview = doc["_id"]?.let { UiHelpers.bsonDisplayValue(it) } ?: "doc"
                val docNode = DefaultMutableTreeNode(
                    BsonTreeNodeData("[${currentSkip + idx + 1}] $idPreview", doc, idx)
                )
                addDocumentChildren(docNode, doc, idx)
                treeRoot.add(docNode)
            }
        }
        treeModel.reload()
        // Expand first document automatically for instant visibility
        if (docs.isNotEmpty()) {
            resultTree.expandRow(0)
        }
    }

    private fun addDocumentChildren(parent: DefaultMutableTreeNode, doc: BsonDocument, docIdx: Int) {
        for ((k, v) in doc) {
            val child = DefaultMutableTreeNode(BsonTreeNodeData(k, v, docIdx))
            when (v) {
                is BsonDocument -> addDocumentChildren(child, v, docIdx)
                is BsonArray -> addArrayChildren(child, v, docIdx)
                else -> Unit
            }
            parent.add(child)
        }
    }

    private fun addArrayChildren(parent: DefaultMutableTreeNode, arr: BsonArray, docIdx: Int) {
        arr.forEachIndexed { i, elem ->
            val child = DefaultMutableTreeNode(BsonTreeNodeData("[$i]", elem, docIdx))
            when (elem) {
                is BsonDocument -> addDocumentChildren(child, elem, docIdx)
                is BsonArray -> addArrayChildren(child, elem, docIdx)
                else -> Unit
            }
            parent.add(child)
        }
    }

    private fun populateRawJson(docs: List<BsonDocument>) {
        val text = if (docs.isEmpty()) {
            "[]"
        } else {
            docs.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { doc ->
                MongoShellNormalizer.formatPrettyExtended(doc)
                    .lines()
                    .joinToString("\n") { "  $it" }
            }
        }
        ApplicationManager.getApplication().runWriteAction {
            rawJsonDocument.setText(text)
        }
    }

    private fun populateTable(docs: List<BsonDocument>) {
        tableModel.setDocuments(docs)
        val cm = resultTable.columnModel
        for (i in 0 until cm.columnCount) {
            cm.getColumn(i).preferredWidth = if (i == 0) 210 else 150
        }
    }

    private fun getSelectedDocument(): BsonDocument? {
        val docs = currentResult?.documents ?: return null
        if (docs.isEmpty()) return null

        return when (activeView) {
            "TREE" -> {
                val node = resultTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
                val data = node.userObject as? BsonTreeNodeData ?: return null
                docs.getOrNull(data.rootDocIndex)
            }
            "TABLE" -> {
                val viewRow = resultTable.selectedRow
                if (viewRow < 0) return null
                val modelRow = resultTable.convertRowIndexToModel(viewRow)
                docs.getOrNull(modelRow)
            }
            else -> docs.firstOrNull()
        }
    }

    private fun showDocumentContextMenu(invoker: Component, x: Int, y: Int) {
        val menu = JPopupMenu()
        menu.add(JMenuItem(I18n.t("result.doc.copyJson")).apply { addActionListener { actionCopyJson() } })
        menu.add(JMenuItem(I18n.t("result.doc.copyId")).apply { addActionListener { actionCopyId() } })
        menu.addSeparator()
        menu.add(JMenuItem(I18n.t("result.doc.edit")).apply { addActionListener { actionEditDocument() } })
        menu.add(JMenuItem(I18n.t("result.doc.clone")).apply { addActionListener { actionCloneDocument() } })
        menu.add(JMenuItem(I18n.t("result.doc.delete")).apply { addActionListener { actionDeleteDocument() } })
        menu.show(invoker, x, y)
    }

    private fun actionCopyJson() {
        val doc = getSelectedDocument()
        if (doc == null) {
            UiHelpers.notify(project, I18n.t("result.doc.noSelection"))
            return
        }
        UiHelpers.copyToClipboard(MongoShellNormalizer.formatPrettyExtended(doc))
        UiHelpers.notify(project, I18n.t("result.copied"))
    }

    private fun actionCopyId() {
        val doc = getSelectedDocument()
        val idVal = doc?.get("_id")
        if (idVal == null) {
            UiHelpers.notify(project, I18n.t("result.doc.noSelection"))
            return
        }
        UiHelpers.copyToClipboard(UiHelpers.bsonIdRawText(idVal))
        UiHelpers.notify(project, I18n.t("result.copied"))
    }

    private fun actionEditDocument() {
        val doc = getSelectedDocument()
        val originalId = doc?.get("_id")
        if (doc == null || originalId == null) {
            UiHelpers.notify(project, I18n.t("result.doc.noSelection"))
            return
        }
        if (!UiHelpers.ensureWritableOrPromptUnlock(project, connectionManager, profile, onReadOnlyChanged)) {
            return
        }

        val dialog = DocumentEditDialog(project, doc)
        if (!dialog.showAndGet()) return
        val updated = dialog.parsedDocument ?: return

        object : Task.Backgroundable(project, I18n.t("result.doc.editTitle"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    connectionManager.replaceDocument(profile, database, collection, originalId, updated)
                    ApplicationManager.getApplication().invokeLater {
                        UiHelpers.notify(project, I18n.t("result.doc.editSuccess"))
                        onRequestRefresh()
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, t.message ?: t.javaClass.simpleName, I18n.t("result.doc.editTitle"))
                    }
                }
            }
        }.queue()
    }

    private fun actionCloneDocument() {
        val doc = getSelectedDocument()
        if (doc == null) {
            UiHelpers.notify(project, I18n.t("result.doc.noSelection"))
            return
        }
        if (!UiHelpers.ensureWritableOrPromptUnlock(project, connectionManager, profile, onReadOnlyChanged)) {
            return
        }

        object : Task.Backgroundable(project, I18n.t("result.doc.clone"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    connectionManager.cloneDocument(profile, database, collection, doc)
                    ApplicationManager.getApplication().invokeLater {
                        UiHelpers.notify(project, I18n.t("result.doc.cloneSuccess"))
                        onRequestRefresh()
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, t.message ?: t.javaClass.simpleName, I18n.t("result.doc.clone"))
                    }
                }
            }
        }.queue()
    }

    private fun actionDeleteDocument() {
        val doc = getSelectedDocument()
        val idVal = doc?.get("_id")
        if (doc == null || idVal == null) {
            UiHelpers.notify(project, I18n.t("result.doc.noSelection"))
            return
        }
        if (!UiHelpers.ensureWritableOrPromptUnlock(project, connectionManager, profile, onReadOnlyChanged)) {
            return
        }

        val confirm = Messages.showYesNoDialog(
            project,
            I18n.t("result.doc.deleteConfirm", UiHelpers.bsonIdRawText(idVal)),
            I18n.t("result.doc.deleteTitle"),
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        object : Task.Backgroundable(project, I18n.t("result.doc.delete"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    connectionManager.deleteDocument(profile, database, collection, idVal)
                    ApplicationManager.getApplication().invokeLater {
                        UiHelpers.notify(project, I18n.t("result.doc.deleteSuccess"))
                        onRequestRefresh()
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, t.message ?: t.javaClass.simpleName, I18n.t("result.doc.delete"))
                    }
                }
            }
        }.queue()
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            treeViewBtn.text = I18n.t("result.view.tree")
            jsonViewBtn.text = I18n.t("result.view.json")
            tableViewBtn.text = I18n.t("result.view.table")
            prevBtn.text = I18n.t("result.prev")
            nextBtn.text = I18n.t("result.next")
            copyJsonBtn.text = I18n.t("result.doc.copyJson")
            copyIdBtn.text = I18n.t("result.doc.copyId")
            editDocBtn.text = I18n.t("result.doc.edit")
            cloneDocBtn.text = I18n.t("result.doc.clone")
            deleteDocBtn.text = I18n.t("result.doc.delete")
            updateSummaryAndBadges()
        }
    }

    override fun dispose() {
        if (!rawJsonEditor.isDisposed) {
            EditorFactory.getInstance().releaseEditor(rawJsonEditor)
        }
    }

    private class MongoResultTableModel : AbstractTableModel() {
        private var columns: List<String> = emptyList()
        private var docs: List<BsonDocument> = emptyList()

        fun setDocuments(documents: List<BsonDocument>) {
            this.docs = documents
            val keys = linkedSetOf<String>()
            if (documents.any { it.containsKey("_id") }) {
                keys.add("_id")
            }
            for (d in documents) {
                keys.addAll(d.keys)
            }
            this.columns = keys.toList()
            fireTableStructureChanged()
        }

        override fun getRowCount(): Int = docs.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val v = getBsonValueAt(rowIndex, columnIndex) ?: return ""
            return UiHelpers.bsonDisplayValue(v)
        }

        fun getBsonValueAt(rowIndex: Int, columnIndex: Int): BsonValue? {
            val doc = docs.getOrNull(rowIndex) ?: return null
            val colName = columns.getOrNull(columnIndex) ?: return null
            return doc[colName]
        }
    }
}
