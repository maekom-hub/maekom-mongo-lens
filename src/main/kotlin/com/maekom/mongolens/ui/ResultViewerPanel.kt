package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
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
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.TransferHandler
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

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
        var rootDocIndex: Int,
        val isRootDoc: Boolean = false
    )

    private var currentDocs: MutableList<BsonDocument> = mutableListOf()
    private var currentResult: QueryExecutionResult? = null
    private var currentSkip: Int = 0
    private var currentLimit: Int = 50
    private var previewBannerText: String? = null
    private var activeCopyOverlayPopup: JBPopup? = null
    private var treeAllExpanded: Boolean = true

    private val summaryLabel = JBLabel(" ")
    private val indexBadge = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_GREEN_BG, UiHelpers.COLOR_GREEN_FG).apply {
        isVisible = false
    }
    private val previewBannerLabel = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_BLUE_BG, UiHelpers.COLOR_BLUE_FG).apply {
        isVisible = false
    }

    private val expandCollapseBtn = JButton(I18n.t("result.tree.collapseAll")).apply {
        margin = JBUI.insets(1, 6)
    }

    private val treeViewBtn = JToggleButton(I18n.t("result.view.tree"), true).apply { margin = JBUI.insets(1, 6) }
    private val jsonViewBtn = JToggleButton(I18n.t("result.view.json"), false).apply { margin = JBUI.insets(1, 6) }
    private val tableViewBtn = JToggleButton(I18n.t("result.view.table"), false).apply { margin = JBUI.insets(1, 6) }

    private val prevBtn = JButton(I18n.t("result.prev")).apply { margin = JBUI.insets(1, 6) }
    private val nextBtn = JButton(I18n.t("result.next")).apply { margin = JBUI.insets(1, 6) }

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
    private val treeScrollPane = JBScrollPane(resultTree)

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

    // 3. Table View (quote-free cells + single-cell selection + pure text/plain TransferHandler)
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
                bsonVal?.let { "${UiHelpers.bsonTypeTag(it)}: ${UiHelpers.bsonPureTextValue(it)}" }
            }
        }
    }
    private val tableScrollPane = JBScrollPane(resultTable)

    private var activeView = "TREE"

    init {
        buildHeaderBar()
        buildTreeView()
        buildTableView()

        cardsPanel.add(treeScrollPane, "TREE")
        cardsPanel.add(rawJsonEditor.component, "JSON")
        cardsPanel.add(tableScrollPane, "TABLE")

        add(cardsPanel, BorderLayout.CENTER)
        I18n.addListener(this) { refreshTexts() }
    }

    /**
     * Single compact header row only — all external document action buttons removed to maximize result height.
     */
    private fun buildHeaderBar() {
        val headerBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
        }

        val leftBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD)
            add(summaryLabel)
            add(indexBadge)
            add(previewBannerLabel)
            add(expandCollapseBtn)
        }

        expandCollapseBtn.addActionListener {
            if (treeAllExpanded) {
                collapseAllNodes()
            } else {
                expandAllNodes(maxDepth = Int.MAX_VALUE)
            }
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

        headerBar.add(leftBar, BorderLayout.WEST)
        headerBar.add(rightBar, BorderLayout.EAST)
        add(headerBar, BorderLayout.NORTH)
    }

    private fun buildTreeView() {
        resultTree.isRootVisible = false
        resultTree.showsRootHandles = true
        // Prevent double-click from collapsing/expanding nodes; handle-only expand/collapse
        resultTree.toggleClickCount = 0
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
            override fun mousePressed(e: MouseEvent) = maybeShowTreeContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowTreeContextMenu(e)

            override fun mouseClicked(e: MouseEvent) {
                // Left double-click stays dedicated to inline key/value drag-select copy overlay
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                    handleTreeDoubleClickCopyOverlay(e)
                }
            }

            private fun maybeShowTreeContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = resultTree.getPathForLocation(e.x, e.y) ?: return
                resultTree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? BsonTreeNodeData ?: return

                val focusPath = extractFieldPath(path)
                val copyText = if (data.isRootDoc) {
                    MongoShellNormalizer.formatPrettyExtended(data.value.asDocument())
                } else {
                    "${data.keyLabel}: ${UiHelpers.bsonPureTextValue(data.value)}"
                }
                showUnifiedContextMenu(
                    invoker = resultTree,
                    x = e.x,
                    y = e.y,
                    docIndex = data.rootDocIndex,
                    focusPath = focusPath,
                    copyKeyValueText = copyText
                )
            }
        })
    }

    private fun extractFieldPath(path: TreePath): List<String> {
        val segments = mutableListOf<String>()
        for (comp in path.path) {
            val node = comp as? DefaultMutableTreeNode ?: continue
            val data = node.userObject as? BsonTreeNodeData ?: continue
            if (!data.isRootDoc) {
                segments.add(data.keyLabel)
            }
        }
        return segments
    }

    private fun handleTreeDoubleClickCopyOverlay(e: MouseEvent) {
        val path = resultTree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? BsonTreeNodeData ?: return
        val bounds = resultTree.getPathBounds(path) ?: return

        val boldFont = resultTree.font.deriveFont(Font.BOLD)
        val fm = resultTree.getFontMetrics(boldFont)
        val keyWidth = fm.stringWidth(data.keyLabel)
        val colonWidth = fm.stringWidth(": ")
        val relX = e.x - bounds.x

        val clickedKey = relX <= (keyWidth + 6)
        val targetText = if (clickedKey) {
            data.keyLabel
        } else {
            UiHelpers.bsonPureTextValue(data.value)
        }

        val overlayX = if (clickedKey) bounds.x else bounds.x + keyWidth + colonWidth
        showCopyableTextOverlay(
            parent = resultTree,
            point = Point(overlayX, bounds.y),
            minWidth = if (clickedKey) keyWidth + 24 else (bounds.width - keyWidth).coerceAtLeast(140),
            rowHeight = bounds.height.coerceAtLeast(22),
            text = targetText
        )
    }

    private fun showCopyableTextOverlay(
        parent: JComponent,
        point: Point,
        minWidth: Int,
        rowHeight: Int,
        text: String
    ) {
        activeCopyOverlayPopup?.let {
            if (!it.isDisposed) it.cancel()
        }

        var popupRef: JBPopup? = null
        val field = JTextField(text).apply {
            isEditable = false
            font = parent.font
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiHelpers.COLOR_BLUE_FG, 1),
                JBUI.Borders.empty(0, 4)
            )
            val textWidth = getFontMetrics(font).stringWidth(text) + 28
            preferredSize = Dimension(textWidth.coerceIn(minWidth.coerceAtLeast(80), 560), rowHeight.coerceAtLeast(24))
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        e.consume()
                        popupRef?.cancel()
                    }
                }
            })
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent?) {
                    popupRef?.cancel()
                }
            })
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(field, field)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()

        popupRef = popup
        activeCopyOverlayPopup = popup
        popup.show(RelativePoint(parent, point))

        ApplicationManager.getApplication().invokeLater {
            field.requestFocusInWindow()
            field.selectAll()
        }
    }

    private fun buildTableView() {
        resultTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        resultTable.cellSelectionEnabled = true
        resultTable.rowSelectionAllowed = true
        resultTable.columnSelectionAllowed = true
        resultTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_INTERVAL_SELECTION
        resultTable.columnModel.selectionModel.selectionMode = ListSelectionModel.SINGLE_INTERVAL_SELECTION
        resultTable.rowSorter = TableRowSorter(tableModel)
        resultTable.rowHeight = 24
        ToolTipManager.sharedInstance().registerComponent(resultTable)

        // Pure text/plain TransferHandler (no HTML pollution, no outer quotes)
        resultTable.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent?): Int = COPY

            override fun createTransferable(c: JComponent?): Transferable? {
                val rows = resultTable.selectedRows
                val cols = resultTable.selectedColumns
                if (rows.isEmpty() || cols.isEmpty()) return null

                val sb = StringBuilder()
                rows.forEachIndexed { rIdx, viewRow ->
                    val modelRow = resultTable.convertRowIndexToModel(viewRow)
                    cols.forEachIndexed { cIdx, viewCol ->
                        val modelCol = resultTable.convertColumnIndexToModel(viewCol)
                        sb.append(tableModel.getPureCopyValueAt(modelRow, modelCol))
                        if (cIdx < cols.lastIndex) sb.append('\t')
                    }
                    if (rIdx < rows.lastIndex) sb.append('\n')
                }
                return StringSelection(sb.toString())
            }
        }

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
                    comp.foreground = if (bsonVal != null) UiHelpers.bsonTypeColor(bsonVal) else UiHelpers.BSON_NULL_COLOR
                }
                return comp
            }
        })

        resultTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowTableContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowTableContextMenu(e)

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                    val row = resultTable.rowAtPoint(e.point)
                    val col = resultTable.columnAtPoint(e.point)
                    if (row >= 0 && col >= 0) {
                        val modelRow = resultTable.convertRowIndexToModel(row)
                        val modelCol = resultTable.convertColumnIndexToModel(col)
                        val pureVal = tableModel.getPureCopyValueAt(modelRow, modelCol)
                        val cellRect = resultTable.getCellRect(row, col, false)
                        showCopyableTextOverlay(
                            parent = resultTable,
                            point = Point(cellRect.x, cellRect.y),
                            minWidth = cellRect.width,
                            rowHeight = cellRect.height,
                            text = pureVal
                        )
                    }
                }
            }

            private fun maybeShowTableContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = resultTable.rowAtPoint(e.point)
                val col = resultTable.columnAtPoint(e.point)
                if (row < 0 || col < 0) return
                resultTable.changeSelection(row, col, false, false)

                val modelRow = resultTable.convertRowIndexToModel(row)
                val modelCol = resultTable.convertColumnIndexToModel(col)
                val colName = tableModel.getColumnName(modelCol)
                val pureVal = tableModel.getPureCopyValueAt(modelRow, modelCol)

                showUnifiedContextMenu(
                    invoker = resultTable,
                    x = e.x,
                    y = e.y,
                    docIndex = modelRow,
                    focusPath = listOf(colName),
                    copyKeyValueText = pureVal
                )
            }
        })
    }

    /**
     * Unified right-click context menu for Tree View & Table View:
     * 1. Edit Data... (opens unified DocumentEditDialog)
     * 2. Separator
     * 3. Copy Key / Value
     */
    private fun showUnifiedContextMenu(
        invoker: Component,
        x: Int,
        y: Int,
        docIndex: Int,
        focusPath: List<String>,
        copyKeyValueText: String
    ) {
        val doc = currentDocs.getOrNull(docIndex) ?: return
        val menu = JPopupMenu()

        menu.add(JMenuItem(I18n.t("result.doc.editData")).apply {
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                openDocumentEditDialog(docIndex, doc, focusPath)
            }
        })
        menu.addSeparator()
        menu.add(JMenuItem(I18n.t("result.doc.copyKeyValue")).apply {
            addActionListener {
                UiHelpers.copyToClipboard(copyKeyValueText)
                UiHelpers.notify(project, I18n.t("result.copied"))
            }
        })

        menu.show(invoker, x, y)
    }

    private fun openDocumentEditDialog(docIndex: Int, doc: BsonDocument, focusPath: List<String>) {
        val isReadOnly = connectionManager.isEffectivelyReadOnly(profile)
        val dialog = DocumentEditDialog(
            project = project,
            connectionManager = connectionManager,
            profile = profile,
            database = database,
            collection = collection,
            originalDoc = doc,
            isReadOnly = isReadOnly,
            initialFocusPath = focusPath,
            onSavedInDb = { updatedDoc -> replaceDocumentInPlace(docIndex, updatedDoc) },
            onDeletedInDb = { removeDocumentInPlace(docIndex) }
        )
        dialog.show()
    }

    /**
     * Updates ONLY the edited document in Tree / Table / Raw JSON while preserving scroll position.
     */
    private fun replaceDocumentInPlace(docIndex: Int, updatedDoc: BsonDocument) {
        if (docIndex !in currentDocs.indices) return
        val savedTreePos = treeScrollPane.viewport.viewPosition
        val savedTablePos = tableScrollPane.viewport.viewPosition

        currentDocs[docIndex] = updatedDoc
        currentResult = currentResult?.copy(documents = currentDocs.toList())

        // Update single document subtree in Tree View without collapsing other documents
        if (docIndex < treeRoot.childCount) {
            val docNode = treeRoot.getChildAt(docIndex) as? DefaultMutableTreeNode
            if (docNode != null) {
                val idPreview = updatedDoc["_id"]?.let { UiHelpers.bsonPureTextValue(it) } ?: "doc"
                docNode.userObject = BsonTreeNodeData("[${currentSkip + docIndex + 1}] $idPreview", updatedDoc, docIndex, isRootDoc = true)
                docNode.removeAllChildren()
                addDocumentChildren(docNode, updatedDoc, docIndex)
                treeModel.nodeStructureChanged(docNode)
                expandSubtree(docNode)
            }
        }

        populateRawJson(currentDocs)
        tableModel.updateDocumentInPlace(docIndex, updatedDoc)

        SwingUtilities.invokeLater {
            treeScrollPane.viewport.viewPosition = savedTreePos
            tableScrollPane.viewport.viewPosition = savedTablePos
        }
    }

    /**
     * Removes ONLY the deleted document from Tree / Table / Raw JSON while preserving scroll position.
     */
    private fun removeDocumentInPlace(docIndex: Int) {
        if (docIndex !in currentDocs.indices) return
        val savedTreePos = treeScrollPane.viewport.viewPosition
        val savedTablePos = tableScrollPane.viewport.viewPosition

        currentDocs.removeAt(docIndex)
        val prev = currentResult
        if (prev != null) {
            val newTotal = if (prev.totalCount > 0) (prev.totalCount - 1).coerceAtLeast(0) else prev.totalCount
            currentResult = prev.copy(documents = currentDocs.toList(), totalCount = newTotal)
        }

        if (docIndex < treeRoot.childCount) {
            val nodeToRemove = treeRoot.getChildAt(docIndex) as? DefaultMutableTreeNode
            if (nodeToRemove != null) {
                treeModel.removeNodeFromParent(nodeToRemove)
            }
            // Re-index remaining rootDocIndex values cleanly
            for (i in 0 until treeRoot.childCount) {
                val rootChild = treeRoot.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                reindexSubtreeDocIndex(rootChild, i)
                val data = rootChild.userObject as? BsonTreeNodeData
                if (data != null && data.isRootDoc) {
                    val doc = currentDocs.getOrNull(i)
                    val idPreview = doc?.get("_id")?.let { UiHelpers.bsonPureTextValue(it) } ?: "doc"
                    rootChild.userObject = data.copy(keyLabel = "[${currentSkip + i + 1}] $idPreview", rootDocIndex = i)
                    treeModel.nodeChanged(rootChild)
                }
            }
            if (currentDocs.isEmpty()) {
                treeRoot.add(DefaultMutableTreeNode(I18n.t("result.empty")))
                treeModel.reload()
            }
        }

        updateSummaryAndBadges()
        populateRawJson(currentDocs)
        populateTable(currentDocs)

        SwingUtilities.invokeLater {
            treeScrollPane.viewport.viewPosition = savedTreePos
            tableScrollPane.viewport.viewPosition = savedTablePos
        }
    }

    private fun reindexSubtreeDocIndex(node: DefaultMutableTreeNode, newDocIdx: Int) {
        val data = node.userObject as? BsonTreeNodeData
        if (data != null) {
            data.rootDocIndex = newDocIdx
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            reindexSubtreeDocIndex(child, newDocIdx)
        }
    }

    private fun expandSubtree(node: DefaultMutableTreeNode) {
        resultTree.expandPath(TreePath(node.path))
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            if (child.childCount > 0) {
                expandSubtree(child)
            }
        }
    }

    private fun switchView(viewName: String) {
        activeView = viewName
        treeViewBtn.isSelected = viewName == "TREE"
        jsonViewBtn.isSelected = viewName == "JSON"
        tableViewBtn.isSelected = viewName == "TABLE"
        expandCollapseBtn.isVisible = viewName == "TREE"
        cardLayout.show(cardsPanel, viewName)
    }

    fun updateResults(
        result: QueryExecutionResult,
        skip: Int = 0,
        limit: Int = result.documents.size.coerceAtLeast(1),
        previewBanner: String? = null
    ) {
        this.currentDocs = result.documents.toMutableList()
        this.currentResult = result
        this.currentSkip = skip
        this.currentLimit = limit
        this.previewBannerText = previewBanner

        updateSummaryAndBadges()
        populateTree(currentDocs)
        populateRawJson(currentDocs)
        populateTable(currentDocs)
    }

    private fun updateSummaryAndBadges() {
        val result = currentResult ?: return
        val docs = currentDocs
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
        var totalNodes = 0
        if (docs.isEmpty()) {
            treeRoot.add(DefaultMutableTreeNode(I18n.t("result.empty")))
        } else {
            docs.forEachIndexed { idx, doc ->
                val idPreview = doc["_id"]?.let { UiHelpers.bsonPureTextValue(it) } ?: "doc"
                val docNode = DefaultMutableTreeNode(
                    BsonTreeNodeData("[${currentSkip + idx + 1}] $idPreview", doc, idx, isRootDoc = true)
                )
                totalNodes++
                totalNodes += addDocumentChildren(docNode, doc, idx)
                treeRoot.add(docNode)
            }
        }
        treeModel.reload()

        if (docs.isNotEmpty()) {
            val safeMaxDepth = if (docs.size >= 500 && totalNodes > 3000) 3 else Int.MAX_VALUE
            expandAllNodes(safeMaxDepth)
        }
    }

    private fun expandAllNodes(maxDepth: Int) {
        fun expandRecursive(node: DefaultMutableTreeNode, depth: Int) {
            if (depth > maxDepth || node.childCount == 0) return
            if (node != treeRoot) {
                resultTree.expandPath(TreePath(node.path))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                expandRecursive(child, depth + 1)
            }
        }
        expandRecursive(treeRoot, 0)
        treeAllExpanded = true
        updateExpandCollapseButton()
    }

    private fun collapseAllNodes() {
        for (i in treeRoot.childCount - 1 downTo 0) {
            val child = treeRoot.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            resultTree.collapsePath(TreePath(child.path))
        }
        treeAllExpanded = false
        updateExpandCollapseButton()
    }

    private fun updateExpandCollapseButton() {
        expandCollapseBtn.text = if (treeAllExpanded) {
            I18n.t("result.tree.collapseAll")
        } else {
            I18n.t("result.tree.expandAll")
        }
    }

    private fun addDocumentChildren(parent: DefaultMutableTreeNode, doc: BsonDocument, docIdx: Int): Int {
        var count = 0
        for ((k, v) in doc) {
            val child = DefaultMutableTreeNode(BsonTreeNodeData(k, v, docIdx))
            count++
            when (v) {
                is BsonDocument -> count += addDocumentChildren(child, v, docIdx)
                is BsonArray -> count += addArrayChildren(child, v, docIdx)
                else -> Unit
            }
            parent.add(child)
        }
        return count
    }

    private fun addArrayChildren(parent: DefaultMutableTreeNode, arr: BsonArray, docIdx: Int): Int {
        var count = 0
        arr.forEachIndexed { i, elem ->
            val child = DefaultMutableTreeNode(BsonTreeNodeData("[$i]", elem, docIdx))
            count++
            when (elem) {
                is BsonDocument -> count += addDocumentChildren(child, elem, docIdx)
                is BsonArray -> count += addArrayChildren(child, elem, docIdx)
                else -> Unit
            }
            parent.add(child)
        }
        return count
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

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            treeViewBtn.text = I18n.t("result.view.tree")
            jsonViewBtn.text = I18n.t("result.view.json")
            tableViewBtn.text = I18n.t("result.view.table")
            prevBtn.text = I18n.t("result.prev")
            nextBtn.text = I18n.t("result.next")
            updateExpandCollapseButton()
            updateSummaryAndBadges()
        }
    }

    override fun dispose() {
        activeCopyOverlayPopup?.let { if (!it.isDisposed) it.cancel() }
        if (!rawJsonEditor.isDisposed) {
            EditorFactory.getInstance().releaseEditor(rawJsonEditor)
        }
    }

    private class MongoResultTableModel : AbstractTableModel() {
        private var columns: List<String> = emptyList()
        private var docs: MutableList<BsonDocument> = mutableListOf()

        fun setDocuments(documents: List<BsonDocument>) {
            this.docs = documents.toMutableList()
            recomputeColumns()
            fireTableStructureChanged()
        }

        fun updateDocumentInPlace(rowIndex: Int, updatedDoc: BsonDocument) {
            if (rowIndex !in docs.indices) return
            docs[rowIndex] = updatedDoc
            val oldColumns = columns
            recomputeColumns()
            if (columns == oldColumns) {
                fireTableRowsUpdated(rowIndex, rowIndex)
            } else {
                fireTableStructureChanged()
            }
        }

        private fun recomputeColumns() {
            val keys = linkedSetOf<String>()
            if (docs.any { it.containsKey("_id") }) {
                keys.add("_id")
            }
            for (d in docs) {
                keys.addAll(d.keys)
            }
            this.columns = keys.toList()
        }

        override fun getRowCount(): Int = docs.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val v = getBsonValueAt(rowIndex, columnIndex) ?: return ""
            return UiHelpers.bsonTableDisplayValue(v)
        }

        fun getPureCopyValueAt(rowIndex: Int, columnIndex: Int): String {
            val v = getBsonValueAt(rowIndex, columnIndex) ?: return ""
            return UiHelpers.bsonPureTextValue(v)
        }

        fun getBsonValueAt(rowIndex: Int, columnIndex: Int): BsonValue? {
            val doc = docs.getOrNull(rowIndex) ?: return null
            val colName = columns.getOrNull(columnIndex) ?: return null
            return doc[colName]
        }
    }
}
