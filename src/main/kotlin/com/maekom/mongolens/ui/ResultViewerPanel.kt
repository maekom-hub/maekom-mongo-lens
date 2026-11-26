package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.crypto.CryptoService
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.IndexScanType
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import com.maekom.mongolens.mongo.QueryExecutionResult
import com.maekom.mongolens.mongo.ReferenceJumpHelper
import org.bson.BsonArray
import org.bson.BsonDateTime
import org.bson.BsonDocument
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.Document
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.TransferHandler
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class ResultViewerPanel(
    private val project: Project,
    private val projectBasePath: Path?,
    private val connectionManager: MongoConnectionManager,
    private val cryptoStore: CryptoConfigStore,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val onRequestRefresh: () -> Unit,
    private val onPrevPage: (() -> Unit)? = null,
    private val onNextPage: (() -> Unit)? = null,
    private val onReadOnlyChanged: () -> Unit = {},
    private val diffBaselineState: DiffBaselineState? = null,
    private val isJoinView: Boolean = false,
    private val initialView: String = "TREE",
    private val availableColumnsSupplier: (() -> List<String>)? = null,
    private val onApplyColumnSelection: ((selectedColumns: List<String>, allSelected: Boolean) -> Unit)? = null,
    private val onApplyQuickFilter: ((filterJson: String) -> Unit)? = null,
    private val collectionsSupplier: (() -> List<String>)? = null,
    private val onJumpToReferenceCollection: ((targetCollection: String, sourceFieldPath: String, sourceValue: BsonValue) -> Unit)? = null,
    private val onRequestOpenJoinDialog: ((initialLocalField: String?) -> Unit)? = null
) : JPanel(BorderLayout()), Disposable {

    /**
     * Explicit root document tree node carrying the original org.bson.Document, BsonDocument,
     * and result list index directly alongside its display label.
     */
    class DocTreeNode(
        var displayLabel: String,
        var bsonDocument: BsonDocument,
        var rawDocument: Document,
        var docIndex: Int
    ) : DefaultMutableTreeNode(displayLabel)

    /**
     * Explicit nested key/value tree node carrying a direct reference to its owning DocTreeNode
     * and its dot-notation fieldPath (e.g. "status", "address.city").
     */
    class FieldTreeNode(
        val rootDocNode: DocTreeNode,
        val keyLabel: String,
        val bsonValue: BsonValue,
        val fieldPath: String
    ) : DefaultMutableTreeNode(keyLabel)

    private data class DecryptedEntry(
        val plaintext: String,
        var remainingSeconds: Int,
        val treeNode: FieldTreeNode
    )

    private val decryptedFields = linkedMapOf<String, DecryptedEntry>()
    private val decryptCountdownTimer: Timer = Timer(1000) { onDecryptTimerTick() }

    private var isTableBatchDecrypted: Boolean = false
    private var tableBatchRemainingSeconds: Int = 0
    private val tableBatchDecryptTimer: Timer = Timer(1000) { onTableBatchTimerTick() }

    private var currentDocs: MutableList<BsonDocument> = mutableListOf()
    private var currentResult: QueryExecutionResult? = null
    private var currentSkip: Int = 0
    private var currentLimit: Int = 50
    private var previewBannerText: String? = null
    private var activeCopyOverlayPopup: JBPopup? = null
    private var isTreeFullyExpanded: Boolean = false

    private val headerLeftBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
    private val summaryLabel = JBLabel(" ")
    private val indexBadge = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_GREEN_BG, UiHelpers.COLOR_GREEN_FG).apply {
        isVisible = false
    }
    private val batchDecryptBtn = JButton(I18n.t("crypto.table.decryptPage"), AllIcons.Nodes.Padlock).apply {
        margin = JBUI.insets(1, 6)
        toolTipText = I18n.t("crypto.table.decryptPageTooltip")
    }
    private val batchCountdownBadge = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_ORANGE_BG, UiHelpers.COLOR_ORANGE_FG).apply {
        isVisible = false
    }
    private val tableBatchDecryptPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = false
        isVisible = false
        add(batchDecryptBtn)
        add(batchCountdownBadge)
    }
    private val baselineBadgeLabel = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_BLUE_BG, UiHelpers.COLOR_BLUE_FG).apply {
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val previewBannerLabel = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_BLUE_BG, UiHelpers.COLOR_BLUE_FG).apply {
        isVisible = false
    }

    private val expandCollapseToggleBtn = JButton(I18n.t("result.tree.expandAll")).apply {
        margin = JBUI.insets(1, 6)
    }

    private val exportBtn = JButton(I18n.t("result.export.btn")).apply {
        margin = JBUI.insets(1, 6)
        isEnabled = false
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
            return when (val comp = path.lastPathComponent) {
                is FieldTreeNode -> when (val bv = comp.bsonValue) {
                    is BsonObjectId -> UiHelpers.formatObjectIdTooltip(bv.value)
                    is BsonDateTime -> UiHelpers.formatDateDualTooltip(bv.value)
                    else -> null
                }
                is DocTreeNode -> (comp.bsonDocument["_id"] as? BsonObjectId)?.let {
                    UiHelpers.formatObjectIdTooltip(it.value)
                }
                else -> null
            }
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

    // 3. Table View (quote-free cells + multi-row selection + pure text/plain TransferHandler)
    private val tableModel = MongoResultTableModel()
    private val resultTable = object : JBTable(tableModel) {
        override fun getToolTipText(event: MouseEvent?): String? {
            if (event == null) return null
            val row = rowAtPoint(event.point)
            val col = columnAtPoint(event.point)
            if (row < 0 || col < 0) return null
            val modelRow = convertRowIndexToModel(row)
            val modelCol = convertColumnIndexToModel(col)
            val bsonVal = tableModel.getEffectiveBsonValueAt(modelRow, modelCol) ?: return null
            return when (bsonVal) {
                is BsonObjectId -> UiHelpers.formatObjectIdTooltip(bsonVal.value)
                is BsonDateTime -> UiHelpers.formatDateDualTooltip(bsonVal.value)
                else -> "${UiHelpers.bsonTypeTag(bsonVal)}: ${UiHelpers.bsonPureTextValue(bsonVal)}"
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
        if (initialView != "TREE") {
            switchView(initialView)
        }
        com.intellij.openapi.util.Disposer.register(this) { clearAllDecryptedPlaintext(repaintTable = false) }
        cryptoStore.addChangeListener(this) {
            val refresh = Runnable {
                clearAllDecryptedPlaintext(repaintTable = true)
                resultTree.repaint()
                updateBatchDecryptControls()
            }
            if (SwingUtilities.isEventDispatchThread()) {
                refresh.run()
            } else {
                SwingUtilities.invokeLater(refresh)
            }
        }
        diffBaselineState?.addListener(this) {
            updateBaselineBadge()
        }
        updateBaselineBadge()
        I18n.addListener(this) { refreshTexts() }
    }

    private fun buildHeaderBar() {
        val headerBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
        }

        headerLeftBar.apply {
            summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD)
            add(summaryLabel)
            add(indexBadge)
            add(tableBatchDecryptPanel)
            add(baselineBadgeLabel)
            add(previewBannerLabel)
            add(expandCollapseToggleBtn)
        }

        batchDecryptBtn.addActionListener { handleTableBatchDecryptClick() }
        baselineBadgeLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    diffBaselineState?.clear()
                    UiHelpers.notify(project, I18n.t("diff.unpinnedToast"))
                }
            }
        })

        expandCollapseToggleBtn.addActionListener {
            if (isTreeFullyExpanded) {
                collapseAllDocumentRoots()
            } else {
                expandAllRecursive()
            }
        }

        exportBtn.addActionListener { showExportMenu() }

        val rightBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            add(exportBtn)
            if (onPrevPage != null && onNextPage != null) {
                prevBtn.isEnabled = false
                nextBtn.isEnabled = false
                prevBtn.addActionListener {
                    resetTableBatchDecryption()
                    onPrevPage.invoke()
                }
                nextBtn.addActionListener {
                    resetTableBatchDecryption()
                    onNextPage.invoke()
                }
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

        headerBar.add(headerLeftBar, BorderLayout.WEST)
        headerBar.add(rightBar, BorderLayout.EAST)
        add(headerBar, BorderLayout.NORTH)
    }

    private fun buildTreeView() {
        resultTree.isRootVisible = false
        resultTree.showsRootHandles = true
        resultTree.toggleClickCount = 0
        resultTree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
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
                when (value) {
                    is DocTreeNode -> {
                        append(value.displayLabel, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append(": ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append(
                            UiHelpers.bsonDisplayValue(value.bsonDocument),
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UiHelpers.BSON_STRUCT_COLOR)
                        )
                        append(
                            "  [${UiHelpers.bsonTypeTag(value.bsonDocument)}]",
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, UiHelpers.BSON_NULL_COLOR)
                        )
                    }
                    is FieldTreeNode -> {
                        append(value.keyLabel, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append(": ", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                        val decrypted = decryptedFields[decryptKeyFor(value)]
                        if (decrypted != null) {
                            append(
                                "\"${decrypted.plaintext}\"",
                                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UiHelpers.COLOR_GREEN_FG)
                            )
                            append(
                                "  [String]",
                                SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, UiHelpers.BSON_NULL_COLOR)
                            )
                            append(
                                "  ${decrypted.remainingSeconds}s  ",
                                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UiHelpers.COLOR_ORANGE_FG)
                            )
                            append(
                                "[${I18n.t("crypto.btn.hide")}]",
                                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UiHelpers.COLOR_BLUE_FG)
                            )
                        } else {
                            val valColor = UiHelpers.bsonTypeColor(value.bsonValue)
                            append(
                                UiHelpers.bsonDisplayValue(value.bsonValue),
                                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, valColor)
                            )
                            append(
                                "  [${UiHelpers.bsonTypeTag(value.bsonValue)}]",
                                SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, UiHelpers.BSON_NULL_COLOR)
                            )
                            when (val bv = value.bsonValue) {
                                is BsonObjectId -> {
                                    append(
                                        "  ${UiHelpers.formatObjectIdInlineTime(bv.value)}",
                                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                                    )
                                }
                                is BsonDateTime -> {
                                    append(
                                        "  ${UiHelpers.formatDateInlineTime(bv.value)}",
                                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                                    )
                                }
                                is BsonString -> {
                                    if (CryptoService.isEncryptedCandidate(bv.value, cryptoStore.getConfig(profile.profileName))) {
                                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                                        append(
                                            "[${I18n.t("crypto.btn.decrypt")}]",
                                            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UiHelpers.COLOR_BLUE_FG)
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                    is DefaultMutableTreeNode -> {
                        append(value.userObject?.toString() ?: "", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }

        resultTree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val path = resolveTreePathAtPoint(e.x, e.y)
                val node = path?.lastPathComponent as? FieldTreeNode
                val rect = if (path != null && node != null) computeCryptoBadgeRect(path, node) else null
                resultTree.cursor = if (rect != null && rect.contains(e.point)) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        })

        resultTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowTreeContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowTreeContextMenu(e)

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                if (e.clickCount == 1) {
                    val path = resolveTreePathAtPoint(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? FieldTreeNode ?: return
                    val badgeRect = computeCryptoBadgeRect(path, node)
                    if (badgeRect != null && badgeRect.contains(e.point)) {
                        e.consume()
                        handleCryptoBadgeClick(node)
                        return
                    }
                } else if (e.clickCount == 2) {
                    handleTreeDoubleClickCopyOverlay(e)
                }
            }

            private fun maybeShowTreeContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                // Resolve row even when right-clicking blank space to the right of a tree label
                val exactPath = resultTree.getPathForLocation(e.x, e.y)
                val resolvedPath = exactPath ?: run {
                    val closest = resultTree.getClosestPathForLocation(e.x, e.y) ?: return
                    val bounds = resultTree.getPathBounds(closest) ?: return
                    if (e.y in bounds.y..(bounds.y + bounds.height)) closest else return
                }

                val clickedNode = resolvedPath.lastPathComponent
                val clickedDocIdx = when (clickedNode) {
                    is DocTreeNode -> clickedNode.docIndex
                    is FieldTreeNode -> clickedNode.rootDocNode.docIndex
                    else -> return
                }

                // Preserve existing multi-selection when right-clicking a node inside already-selected documents
                val existingPaths = resultTree.selectionPaths.orEmpty()
                val existingDocIndices = selectedTreeDocIndices()
                if (resolvedPath !in existingPaths && !(existingDocIndices.size >= 2 && clickedDocIdx in existingDocIndices)) {
                    resultTree.selectionPath = resolvedPath
                }

                val (targetDocNode, focusFieldPath, clickedBsonVal, copyText) = when (clickedNode) {
                    is DocTreeNode -> Quadruple(
                        clickedNode,
                        "_id".takeIf { clickedNode.bsonDocument.containsKey("_id") },
                        clickedNode.bsonDocument["_id"],
                        MongoShellNormalizer.formatPrettyExtended(clickedNode.bsonDocument)
                    )
                    is FieldTreeNode -> {
                        val plain = decryptedFields[decryptKeyFor(clickedNode)]?.plaintext
                        val valText = plain ?: UiHelpers.bsonPureTextValue(clickedNode.bsonValue)
                        Quadruple(
                            clickedNode.rootDocNode,
                            clickedNode.fieldPath,
                            clickedNode.bsonValue,
                            "${clickedNode.keyLabel}: $valText"
                        )
                    }
                    else -> return
                }

                showUnifiedContextMenu(
                    invoker = resultTree,
                    x = e.x,
                    y = e.y,
                    docIndex = targetDocNode.docIndex,
                    selectedDocIndices = selectedTreeDocIndices(),
                    targetBsonDoc = targetDocNode.bsonDocument,
                    targetRawDoc = targetDocNode.rawDocument,
                    focusFieldPath = focusFieldPath,
                    clickedBsonValue = clickedBsonVal,
                    copyKeyValueText = copyText
                )
            }
        })
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun selectedTreeDocIndices(): List<Int> {
        return resultTree.selectionPaths.orEmpty().mapNotNull { path ->
            when (val n = path.lastPathComponent) {
                is DocTreeNode -> n.docIndex
                is FieldTreeNode -> n.rootDocNode.docIndex
                else -> null
            }
        }.filter { it in currentDocs.indices }.distinct()
    }

    private fun handleTreeDoubleClickCopyOverlay(e: MouseEvent) {
        val path = resultTree.getPathForLocation(e.x, e.y) ?: return
        val bounds = resultTree.getPathBounds(path) ?: return
        val (keyLabel, bsonValue) = when (val node = path.lastPathComponent) {
            is DocTreeNode -> node.displayLabel to node.bsonDocument
            is FieldTreeNode -> node.keyLabel to node.bsonValue
            else -> return
        }

        val boldFont = resultTree.font.deriveFont(Font.BOLD)
        val fm = resultTree.getFontMetrics(boldFont)
        val keyWidth = fm.stringWidth(keyLabel)
        val colonWidth = fm.stringWidth(": ")
        val relX = e.x - bounds.x

        val clickedKey = relX <= (keyWidth + 6)
        val decryptedPlain = (path.lastPathComponent as? FieldTreeNode)?.let { decryptedFields[decryptKeyFor(it)]?.plaintext }
        val targetText = when {
            clickedKey -> keyLabel
            decryptedPlain != null -> decryptedPlain
            else -> UiHelpers.bsonPureTextValue(bsonValue)
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
        resultTable.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        resultTable.columnModel.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        resultTable.rowSorter = TableRowSorter(tableModel)
        resultTable.rowHeight = 24
        ToolTipManager.sharedInstance().registerComponent(resultTable)

        // Table Header right-click: Fast Column Selection (SELECT col1, col2) & Hide Column
        resultTable.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowHeaderContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowHeaderContextMenu(e)

            private fun maybeShowHeaderContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val viewCol = resultTable.tableHeader.columnAtPoint(e.point)
                if (viewCol < 0) return
                val clickedColName = resultTable.getColumnName(viewCol)
                showTableHeaderContextMenu(e.x, e.y, clickedColName)
            }
        })

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
                    if (tableModel.isCellDecrypted(modelRow, modelCol)) {
                        comp.foreground = UiHelpers.COLOR_GREEN_FG
                    } else {
                        val bsonVal = tableModel.getBsonValueAt(modelRow, modelCol)
                        comp.foreground = if (bsonVal != null) UiHelpers.bsonTypeColor(bsonVal) else UiHelpers.BSON_NULL_COLOR
                    }
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
                // Preserve existing multi-row selection if right-clicked row is already selected
                if (row !in resultTable.selectedRows) {
                    resultTable.changeSelection(row, col, false, false)
                }

                val modelRow = resultTable.convertRowIndexToModel(row)
                val modelCol = resultTable.convertColumnIndexToModel(col)
                val colName = tableModel.getColumnName(modelCol)
                val pureVal = tableModel.getPureCopyValueAt(modelRow, modelCol)
                val clickedBsonVal = tableModel.getBsonValueAt(modelRow, modelCol)
                val bsonDoc = currentDocs.getOrNull(modelRow) ?: return
                val rawDoc = toRawDocument(bsonDoc)
                val selectedDocIndices = resultTable.selectedRows
                    .map { resultTable.convertRowIndexToModel(it) }
                    .filter { it in currentDocs.indices }
                    .distinct()

                showUnifiedContextMenu(
                    invoker = resultTable,
                    x = e.x,
                    y = e.y,
                    docIndex = modelRow,
                    selectedDocIndices = selectedDocIndices,
                    targetBsonDoc = bsonDoc,
                    targetRawDoc = rawDoc,
                    focusFieldPath = colName,
                    clickedBsonValue = clickedBsonVal,
                    copyKeyValueText = pureVal,
                    copyLabelKey = "result.table.copyCell"
                )
            }
        })
    }

    private fun showTableHeaderContextMenu(x: Int, y: Int, clickedColName: String) {
        val visibleCols = tableModel.getColumnNames()
        val menu = JPopupMenu()

        if (onApplyColumnSelection != null) {
            val canHide = visibleCols.size >= 2
            menu.add(JMenuItem(I18n.t("result.table.hideColumn", clickedColName)).apply {
                isEnabled = canHide
                if (!canHide) {
                    toolTipText = I18n.t("result.table.hideColumnDisabledTooltip")
                }
                addActionListener {
                    val remaining = visibleCols.filter { it != clickedColName }
                    if (remaining.isNotEmpty()) {
                        onApplyColumnSelection.invoke(remaining, false)
                    }
                }
            })

            menu.add(JMenuItem(I18n.t("result.table.selectColumns")).apply {
                font = font.deriveFont(Font.BOLD)
                addActionListener {
                    val schemaCols = availableColumnsSupplier?.invoke().orEmpty()
                    val allCols = buildList {
                        addAll(visibleCols)
                        schemaCols.forEach { add(it) }
                    }.distinct()
                    val dlg = ColumnSelectDialog(project, allCols, visibleCols)
                    if (dlg.showAndGet()) {
                        onApplyColumnSelection.invoke(dlg.getSelectedColumns(), dlg.isAllColumnsSelected())
                    }
                }
            })
            menu.addSeparator()
        }

        menu.add(JMenuItem(I18n.t("result.table.copyColumn")).apply {
            addActionListener {
                UiHelpers.copyToClipboard(clickedColName)
                UiHelpers.notify(project, I18n.t("result.copied"))
            }
        })

        menu.show(resultTable.tableHeader, x, y)
    }

    private fun showUnifiedContextMenu(
        invoker: Component,
        x: Int,
        y: Int,
        docIndex: Int,
        selectedDocIndices: List<Int>,
        targetBsonDoc: BsonDocument,
        targetRawDoc: Document,
        focusFieldPath: String?,
        clickedBsonValue: BsonValue?,
        copyKeyValueText: String,
        copyLabelKey: String = "result.doc.copyKeyValue"
    ) {
        val menu = JPopupMenu()

        // 1. Edit Data (disabled with tooltip in Join View)
        menu.add(JMenuItem(I18n.t("result.doc.editData")).apply {
            font = font.deriveFont(Font.BOLD)
            isEnabled = !isJoinView
            if (isJoinView) {
                toolTipText = I18n.t("join.editDisabledTooltip")
            }
            addActionListener {
                if (isJoinView) return@addActionListener
                ApplicationManager.getApplication().invokeLater {
                    openDocumentEditDialog(docIndex, targetBsonDoc, targetRawDoc, focusFieldPath)
                }
            }
        })
        menu.add(JMenuItem(I18n.t(copyLabelKey)).apply {
            addActionListener {
                UiHelpers.copyToClipboard(copyKeyValueText)
                UiHelpers.notify(project, I18n.t("result.copied"))
            }
        })

        // 2. Reference Jump / Join View / Time Range Filters
        var addedRelationalSection = false
        if (onJumpToReferenceCollection != null &&
            focusFieldPath != null &&
            clickedBsonValue != null &&
            ReferenceJumpHelper.isReferenceCandidate(focusFieldPath, clickedBsonValue)
        ) {
            menu.addSeparator()
            addedRelationalSection = true
            menu.add(JMenuItem(I18n.t("result.ref.jumpToCollection")).apply {
                addActionListener {
                    showReferenceCollectionPopup(invoker, Point(x, y), focusFieldPath, clickedBsonValue)
                }
            })
        }

        if (onRequestOpenJoinDialog != null) {
            if (!addedRelationalSection) {
                menu.addSeparator()
                addedRelationalSection = true
            }
            menu.add(JMenuItem(I18n.t("result.join.openDialog")).apply {
                addActionListener {
                    onRequestOpenJoinDialog.invoke(focusFieldPath)
                }
            })
        }

        if (onApplyQuickFilter != null && focusFieldPath != null && clickedBsonValue != null) {
            when (clickedBsonValue) {
                is BsonObjectId -> {
                    if (!addedRelationalSection) {
                        menu.addSeparator()
                        addedRelationalSection = true
                    }
                    val oidMenu = JMenu(I18n.t("result.timeFilter.menu")).apply {
                        add(JMenuItem(I18n.t("result.timeFilter.gte")).apply {
                            addActionListener {
                                onApplyQuickFilter.invoke(
                                    UiHelpers.buildObjectIdTimeRangeFilter(focusFieldPath, clickedBsonValue.value, isGte = true)
                                )
                            }
                        })
                        add(JMenuItem(I18n.t("result.timeFilter.lte")).apply {
                            addActionListener {
                                onApplyQuickFilter.invoke(
                                    UiHelpers.buildObjectIdTimeRangeFilter(focusFieldPath, clickedBsonValue.value, isGte = false)
                                )
                            }
                        })
                    }
                    menu.add(oidMenu)
                }
                is BsonDateTime -> {
                    if (!addedRelationalSection) {
                        menu.addSeparator()
                        addedRelationalSection = true
                    }
                    val dateMenu = JMenu(I18n.t("result.dateFilter.menu")).apply {
                        add(JMenuItem(I18n.t("result.dateFilter.gte")).apply {
                            addActionListener {
                                onApplyQuickFilter.invoke(
                                    UiHelpers.buildDateTimeRangeFilter(focusFieldPath, clickedBsonValue.value, isGte = true)
                                )
                            }
                        })
                        add(JMenuItem(I18n.t("result.dateFilter.lte")).apply {
                            addActionListener {
                                onApplyQuickFilter.invoke(
                                    UiHelpers.buildDateTimeRangeFilter(focusFieldPath, clickedBsonValue.value, isGte = false)
                                )
                            }
                        })
                    }
                    menu.add(dateMenu)
                }
                else -> Unit
            }
        }

        // 3. Diff Comparison (2 Selected Documents + Baseline Snapshot)
        if (diffBaselineState != null) {
            menu.addSeparator()
            val canCompareTwo = selectedDocIndices.size == 2
            menu.add(JMenuItem(I18n.t("diff.menu.compareTwo")).apply {
                isEnabled = canCompareTwo
                if (!canCompareTwo) {
                    toolTipText = I18n.t("diff.menu.compareTwoHint")
                }
                addActionListener {
                    if (selectedDocIndices.size != 2) return@addActionListener
                    val leftIdx = selectedDocIndices[0]
                    val rightIdx = selectedDocIndices[1]
                    val leftDoc = resolveDocSnapshotForDiff(leftIdx) ?: return@addActionListener
                    val rightDoc = resolveDocSnapshotForDiff(rightIdx) ?: return@addActionListener
                    DocumentDiffHelper.showDiff(
                        project = project,
                        contextTitle = "$database.$collection",
                        leftTitle = buildDocDiffTitle(leftIdx, leftDoc),
                        leftDoc = leftDoc,
                        rightTitle = buildDocDiffTitle(rightIdx, rightDoc),
                        rightDoc = rightDoc
                    )
                }
            })

            menu.add(JMenuItem(I18n.t("diff.menu.pinBaseline")).apply {
                addActionListener {
                    val snapDoc = resolveDocSnapshotForDiff(docIndex) ?: return@addActionListener
                    val shortId = shortDocId(snapDoc, docIndex)
                    val fullLabel = "${profile.displayName}/$database.$collection [$shortId]"
                    diffBaselineState.pin(fullLabel, shortId, snapDoc)
                    UiHelpers.notify(project, I18n.t("diff.pinnedToast", shortId))
                }
            })

            val pinned = diffBaselineState.current
            if (pinned != null) {
                menu.add(JMenuItem(I18n.t("diff.menu.compareWithBaseline", pinned.shortId)).apply {
                    font = font.deriveFont(Font.BOLD)
                    addActionListener {
                        val currentSnap = resolveDocSnapshotForDiff(docIndex) ?: return@addActionListener
                        DocumentDiffHelper.showDiff(
                            project = project,
                            contextTitle = "${pinned.shortId} ↔ ${shortDocId(currentSnap, docIndex)}",
                            leftTitle = I18n.t("diff.baselineLabel", pinned.fullLabel),
                            leftDoc = pinned.canonicalDoc,
                            rightTitle = "${profile.displayName}/$database.$collection ${buildDocDiffTitle(docIndex, currentSnap)}",
                            rightDoc = currentSnap
                        )
                    }
                })
            }
        }

        menu.show(invoker, x, y)
    }

    private fun showReferenceCollectionPopup(
        invoker: Component,
        point: Point,
        sourceFieldPath: String,
        sourceValue: BsonValue
    ) {
        val jumpHandler = onJumpToReferenceCollection ?: return
        val cached = collectionsSupplier?.invoke().orEmpty().filter { it.isNotBlank() }

        fun displayPopup(allColls: List<String>) {
            if (allColls.isEmpty()) {
                UiHelpers.notify(project, I18n.t("result.ref.noCollections"), NotificationType.WARNING)
                return
            }
            val ranked = ReferenceJumpHelper.rankCollections(database, collection, sourceFieldPath, allColls)
            val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<ReferenceJumpHelper.RankedCollectionItem>(
                I18n.t("result.ref.popupTitle", sourceFieldPath),
                ranked
            ) {
                override fun getTextFor(value: ReferenceJumpHelper.RankedCollectionItem): String {
                    val badge = value.badgeKey?.let { "  [${I18n.t(it)}]" }.orEmpty()
                    return "${value.name}$badge"
                }

                override fun isSpeedSearchEnabled(): Boolean = true

                override fun onChosen(
                    selectedValue: ReferenceJumpHelper.RankedCollectionItem?,
                    finalChoice: Boolean
                ): com.intellij.openapi.ui.popup.PopupStep<*>? {
                    if (selectedValue != null && finalChoice) {
                        ApplicationManager.getApplication().invokeLater {
                            ReferenceJumpHelper.recordSelection(database, sourceFieldPath, selectedValue.name)
                            jumpHandler.invoke(selectedValue.name, sourceFieldPath, sourceValue)
                        }
                    }
                    return FINAL_CHOICE
                }
            }
            JBPopupFactory.getInstance().createListPopup(step).show(RelativePoint(invoker, point))
        }

        if (cached.size > 1) {
            displayPopup(cached)
        } else {
            ApplicationManager.getApplication().executeOnPooledThread {
                val fetched = try {
                    connectionManager.listCollections(profile, database)
                } catch (_: Throwable) {
                    cached
                }
                ApplicationManager.getApplication().invokeLater {
                    displayPopup(fetched)
                }
            }
        }
    }

    private fun resolveDocSnapshotForDiff(docIndex: Int): BsonDocument? {
        val raw = currentDocs.getOrNull(docIndex) ?: return null
        val prefix = "$docIndex:"
        val perFieldPlain = linkedMapOf<String, String>()
        for ((k, entry) in decryptedFields) {
            if (k.startsWith(prefix)) {
                perFieldPlain[k.removePrefix(prefix)] = entry.plaintext
            }
        }
        val cfg = cryptoStore.getConfig(profile.profileName)
        val secretKey = if (isTableBatchDecrypted) cryptoStore.getSecretKey(profile.profileName) else ""
        return DocumentDiffHelper.resolveDocumentForDiff(
            rawDoc = raw,
            isBatchDecrypted = isTableBatchDecrypted,
            cryptoConfig = cfg,
            secretKey = secretKey,
            perFieldPlaintexts = perFieldPlain
        )
    }

    private fun shortDocId(doc: BsonDocument, docIndex: Int): String {
        val idVal = doc["_id"] ?: return "#${currentSkip + docIndex + 1}"
        val raw = UiHelpers.bsonIdRawText(idVal)
        return if (raw.length > 14) "${raw.take(6)}…${raw.takeLast(4)}" else raw
    }

    private fun buildDocDiffTitle(docIndex: Int, doc: BsonDocument): String {
        val idText = doc["_id"]?.let { UiHelpers.bsonIdRawText(it) } ?: "doc"
        return "[${currentSkip + docIndex + 1}] $idText"
    }

    private fun updateBaselineBadge() {
        val pinned = diffBaselineState?.current
        if (pinned == null) {
            baselineBadgeLabel.isVisible = false
        } else {
            baselineBadgeLabel.text = I18n.t("diff.badge.pinned", pinned.shortId)
            baselineBadgeLabel.toolTipText = I18n.t("diff.badge.pinnedTooltip", pinned.fullLabel)
            baselineBadgeLabel.isVisible = true
        }
        headerLeftBar.revalidate()
        headerLeftBar.repaint()
    }

    private fun showExportMenu() {
        if (currentDocs.isEmpty()) return
        val menu = JPopupMenu()
        menu.add(JMenuItem(I18n.t("result.export.csv")).apply {
            addActionListener {
                val cols = tableModel.getColumnNames()
                ResultExportHelper.exportCsv(
                    project = project,
                    suggestedBaseName = collection,
                    columns = cols,
                    rowCount = tableModel.rowCount,
                    cellTextProvider = { r, c -> tableModel.getPureCopyValueAt(r, c) }
                )
            }
        })
        menu.add(JMenuItem(I18n.t("result.export.json")).apply {
            addActionListener {
                val docsToExport = currentDocs.indices.mapNotNull { resolveDocSnapshotForDiff(it) }
                ResultExportHelper.exportJson(
                    project = project,
                    suggestedBaseName = collection,
                    documents = docsToExport
                )
            }
        })
        menu.show(exportBtn, 0, exportBtn.height)
    }

    private fun resolveTreePathAtPoint(x: Int, y: Int): TreePath? {
        val exact = resultTree.getPathForLocation(x, y)
        if (exact != null) return exact
        val closest = resultTree.getClosestPathForLocation(x, y) ?: return null
        val bounds = resultTree.getPathBounds(closest) ?: return null
        return if (y in bounds.y..(bounds.y + bounds.height)) closest else null
    }

    private fun decryptKeyFor(node: FieldTreeNode): String = "${node.rootDocNode.docIndex}:${node.fieldPath}"

    private fun computeCryptoBadgeRect(path: TreePath, node: FieldTreeNode): Rectangle? {
        val strVal = (node.bsonValue as? BsonString)?.value ?: return null
        val decrypted = decryptedFields[decryptKeyFor(node)]
        val config = cryptoStore.getConfig(profile.profileName)
        if (decrypted == null && !CryptoService.isEncryptedCandidate(strVal, config)) {
            return null
        }

        val bounds = resultTree.getPathBounds(path) ?: return null
        val leftPad = (resultTree.cellRenderer as? ColoredTreeCellRenderer)?.ipad?.left ?: 2
        val plainFm = resultTree.getFontMetrics(resultTree.font)
        val boldFm = resultTree.getFontMetrics(resultTree.font.deriveFont(Font.BOLD))
        val italicFm = resultTree.getFontMetrics(resultTree.font.deriveFont(Font.ITALIC))

        val prefixWidth = leftPad + if (decrypted != null) {
            boldFm.stringWidth(node.keyLabel) +
                plainFm.stringWidth(": ") +
                boldFm.stringWidth("\"${decrypted.plaintext}\"") +
                italicFm.stringWidth("  [String]") +
                boldFm.stringWidth("  ${decrypted.remainingSeconds}s  ")
        } else {
            boldFm.stringWidth(node.keyLabel) +
                plainFm.stringWidth(": ") +
                plainFm.stringWidth(UiHelpers.bsonDisplayValue(node.bsonValue)) +
                italicFm.stringWidth("  [${UiHelpers.bsonTypeTag(node.bsonValue)}]") +
                plainFm.stringWidth("  ")
        }

        val badgeText = if (decrypted != null) "[${I18n.t("crypto.btn.hide")}]" else "[${I18n.t("crypto.btn.decrypt")}]"
        val badgeWidth = boldFm.stringWidth(badgeText) + 14
        return Rectangle(bounds.x + prefixWidth - 6, bounds.y, badgeWidth, bounds.height)
    }

    private fun handleCryptoBadgeClick(node: FieldTreeNode) {
        val key = decryptKeyFor(node)
        // If currently revealed, immediately hide and wipe plaintext
        if (decryptedFields.remove(key) != null) {
            treeModel.nodeChanged(node)
            if (decryptedFields.isEmpty()) decryptCountdownTimer.stop()
            return
        }

        val cipherText = (node.bsonValue as? BsonString)?.value ?: return
        var secretKey = cryptoStore.getSecretKey(profile.profileName)
        if (secretKey.isBlank()) {
            val dlg = DecryptConfigDialog(project, projectBasePath, cryptoStore, profile.profileName)
            if (!dlg.showAndGet()) return
            secretKey = cryptoStore.getSecretKey(profile.profileName)
            if (secretKey.isBlank()) return
        }

        val config = cryptoStore.getConfig(profile.profileName)
        try {
            val plaintext = CryptoService.decrypt(cipherText, config, secretKey)
            decryptedFields[key] = DecryptedEntry(
                plaintext = plaintext,
                remainingSeconds = config.clampedRevealSeconds(),
                treeNode = node
            )
            treeModel.nodeChanged(node)
            if (!decryptCountdownTimer.isRunning) {
                decryptCountdownTimer.start()
            }
        } catch (t: Throwable) {
            UiHelpers.notify(
                project,
                I18n.t("crypto.decryptFailed", t.message ?: t.javaClass.simpleName),
                com.intellij.notification.NotificationType.WARNING
            )
        }
    }

    private fun onDecryptTimerTick() {
        if (decryptedFields.isEmpty()) {
            decryptCountdownTimer.stop()
            return
        }
        val toRemove = mutableListOf<String>()
        for ((k, entry) in decryptedFields) {
            entry.remainingSeconds -= 1
            if (entry.remainingSeconds <= 0) {
                toRemove.add(k)
            } else {
                treeModel.nodeChanged(entry.treeNode)
            }
        }
        for (k in toRemove) {
            val removed = decryptedFields.remove(k)
            if (removed != null) {
                treeModel.nodeChanged(removed.treeNode)
            }
        }
        if (decryptedFields.isEmpty()) {
            decryptCountdownTimer.stop()
        }
    }

    private fun clearAllDecryptedPlaintext(repaintTable: Boolean = false) {
        decryptCountdownTimer.stop()
        decryptedFields.clear()
        clearTableBatchDecryption(repaintTable = repaintTable)
    }

    fun resetTableBatchDecryption() {
        val action = Runnable { clearTableBatchDecryption(repaintTable = true) }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }

    private fun clearTableBatchDecryption(repaintTable: Boolean) {
        tableBatchDecryptTimer.stop()
        tableBatchRemainingSeconds = 0
        val hadDecrypted = isTableBatchDecrypted || tableModel.hasDecryptedCells()
        isTableBatchDecrypted = false
        if (hadDecrypted) {
            activeCopyOverlayPopup?.let { if (!it.isDisposed) it.cancel() }
        }
        tableModel.clearDecryptedCells(fireRowsUpdated = repaintTable && hadDecrypted)
        updateBatchDecryptControls()
    }

    private fun updateBatchDecryptControls() {
        val config = cryptoStore.getConfig(profile.profileName)
        val hasEncryptedOnPage = currentDocs.any { CryptoService.containsEncryptedCandidate(it, config) }
        val shouldShow = activeView == "TABLE" && hasEncryptedOnPage

        if (!shouldShow) {
            tableBatchDecryptPanel.isVisible = false
            batchCountdownBadge.isVisible = false
        } else {
            tableBatchDecryptPanel.isVisible = true
            if (isTableBatchDecrypted) {
                batchDecryptBtn.icon = AllIcons.Actions.ToggleVisibility
                batchDecryptBtn.text = I18n.t("crypto.table.maskAll")
                batchDecryptBtn.toolTipText = I18n.t("crypto.table.maskAllTooltip")
                batchCountdownBadge.text = "${tableBatchRemainingSeconds}s"
                batchCountdownBadge.isVisible = true
            } else {
                batchDecryptBtn.icon = AllIcons.Nodes.Padlock
                batchDecryptBtn.text = I18n.t("crypto.table.decryptPage")
                batchDecryptBtn.toolTipText = I18n.t("crypto.table.decryptPageTooltip")
                batchCountdownBadge.isVisible = false
            }
        }
        headerLeftBar.revalidate()
        headerLeftBar.repaint()
    }

    private fun handleTableBatchDecryptClick() {
        if (isTableBatchDecrypted) {
            clearTableBatchDecryption(repaintTable = true)
            return
        }

        var secretKey = cryptoStore.getSecretKey(profile.profileName)
        if (secretKey.isBlank()) {
            val dlg = DecryptConfigDialog(project, projectBasePath, cryptoStore, profile.profileName)
            if (!dlg.showAndGet()) return
            secretKey = cryptoStore.getSecretKey(profile.profileName)
            if (secretKey.isBlank()) return
        }

        val config = cryptoStore.getConfig(profile.profileName)
        val decryptedMap = linkedMapOf<Pair<Int, Int>, MongoResultTableModel.DecryptedCellValue>()
        var totalSuccess = 0
        var totalFail = 0
        var firstError: Throwable? = null

        val rowCount = tableModel.rowCount
        val colCount = tableModel.columnCount
        for (rowIdx in 0 until rowCount) {
            for (colIdx in 0 until colCount) {
                val rawValue = tableModel.getBsonValueAt(rowIdx, colIdx) ?: continue
                if (!CryptoService.containsEncryptedCandidate(rawValue, config)) continue
                val res = CryptoService.decryptBsonValueRecursively(rawValue, config, secretKey)
                totalSuccess += res.decryptedCount
                totalFail += res.failedCount
                if (firstError == null && res.firstError != null) {
                    firstError = res.firstError
                }
                if (res.decryptedCount > 0) {
                    decryptedMap[rowIdx to colIdx] = MongoResultTableModel.DecryptedCellValue(
                        decryptedValue = res.value,
                        displayText = formatDecryptedTableCell(res.value),
                        copyText = UiHelpers.bsonPureTextValue(res.value)
                    )
                }
            }
        }

        if (totalSuccess > 0) {
            tableModel.setDecryptedCells(decryptedMap)
            isTableBatchDecrypted = true
            tableBatchRemainingSeconds = config.clampedRevealSeconds()
            updateBatchDecryptControls()
            tableBatchDecryptTimer.restart()
        } else if (totalFail > 0) {
            UiHelpers.notify(
                project,
                I18n.t("crypto.decryptFailed", firstError?.message ?: firstError?.javaClass?.simpleName ?: "Unknown error"),
                NotificationType.WARNING
            )
            updateBatchDecryptControls()
        } else {
            updateBatchDecryptControls()
        }
    }

    private fun onTableBatchTimerTick() {
        if (!isTableBatchDecrypted) {
            tableBatchDecryptTimer.stop()
            return
        }
        tableBatchRemainingSeconds -= 1
        if (tableBatchRemainingSeconds <= 0) {
            clearTableBatchDecryption(repaintTable = true)
        } else {
            batchCountdownBadge.text = "${tableBatchRemainingSeconds}s"
        }
    }

    private fun formatDecryptedTableCell(value: BsonValue): String {
        return when (value) {
            is BsonString -> value.value
            is BsonDocument, is BsonArray -> UiHelpers.bsonPureTextValue(value)
            else -> UiHelpers.bsonTableDisplayValue(value)
        }
    }

    private fun openDocumentEditDialog(
        docIndex: Int,
        targetBsonDoc: BsonDocument,
        targetRawDoc: Document,
        focusFieldPath: String?
    ) {
        try {
            val isReadOnly = connectionManager.isEffectivelyReadOnly(profile)
            val dialog = DocumentEditDialog(
                project = project,
                connectionManager = connectionManager,
                profile = profile,
                database = database,
                collection = collection,
                originalDoc = targetBsonDoc,
                rawDocument = targetRawDoc,
                isReadOnly = isReadOnly,
                focusFieldPath = focusFieldPath,
                projectBasePath = projectBasePath,
                cryptoStore = cryptoStore,
                onSavedInDb = { updatedDoc -> replaceDocumentInPlace(docIndex, updatedDoc) },
                onDeletedInDb = { removeDocumentInPlace(docIndex) }
            )
            dialog.show()
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "${t.javaClass.simpleName}: ${t.message ?: "Unknown error while opening Edit Data dialog."}",
                I18n.t("result.doc.editTitle")
            )
        }
    }

    private fun toRawDocument(bsonDoc: BsonDocument): Document {
        return try {
            Document.parse(bsonDoc.toJson())
        } catch (_: Throwable) {
            Document()
        }
    }

    private fun replaceDocumentInPlace(docIndex: Int, updatedDoc: BsonDocument) {
        if (docIndex !in currentDocs.indices) return
        // Purge any active decrypted entries for this edited document
        val prefix = "$docIndex:"
        decryptedFields.keys.removeIf { it.startsWith(prefix) }
        if (decryptedFields.isEmpty()) decryptCountdownTimer.stop()

        val savedTreePos = treeScrollPane.viewport.viewPosition
        val savedTablePos = tableScrollPane.viewport.viewPosition

        currentDocs[docIndex] = updatedDoc
        currentResult = currentResult?.copy(documents = currentDocs.toList())

        if (docIndex < treeRoot.childCount) {
            val docNode = treeRoot.getChildAt(docIndex) as? DocTreeNode
            if (docNode != null) {
                val idPreview = updatedDoc["_id"]?.let { UiHelpers.bsonIdRawText(it) } ?: "doc"
                docNode.displayLabel = "[${currentSkip + docIndex + 1}] $idPreview"
                docNode.userObject = docNode.displayLabel
                docNode.bsonDocument = updatedDoc
                docNode.rawDocument = toRawDocument(updatedDoc)
                docNode.removeAllChildren()
                addDocumentChildren(docNode, docNode, updatedDoc, "")
                treeModel.nodeStructureChanged(docNode)
                // Keep 1st-level expanded and nested Object/Array collapsed
                resultTree.expandPath(TreePath(docNode.path))
            }
        }

        populateRawJson(currentDocs)
        clearTableBatchDecryption(repaintTable = false)
        tableModel.updateDocumentInPlace(docIndex, updatedDoc)
        updateBatchDecryptControls()

        SwingUtilities.invokeLater {
            treeScrollPane.viewport.viewPosition = savedTreePos
            tableScrollPane.viewport.viewPosition = savedTablePos
        }
    }

    private fun removeDocumentInPlace(docIndex: Int) {
        if (docIndex !in currentDocs.indices) return
        clearAllDecryptedPlaintext(repaintTable = false)
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
            for (i in 0 until treeRoot.childCount) {
                val docNode = treeRoot.getChildAt(i) as? DocTreeNode ?: continue
                docNode.docIndex = i
                val idPreview = docNode.bsonDocument["_id"]?.let { UiHelpers.bsonIdRawText(it) } ?: "doc"
                docNode.displayLabel = "[${currentSkip + i + 1}] $idPreview"
                docNode.userObject = docNode.displayLabel
                treeModel.nodeChanged(docNode)
            }
            if (currentDocs.isEmpty()) {
                treeRoot.add(DefaultMutableTreeNode(I18n.t("result.empty")))
                treeModel.reload()
            }
        }

        updateSummaryAndBadges()
        populateRawJson(currentDocs)
        populateTable(currentDocs)
        updateBatchDecryptControls()

        SwingUtilities.invokeLater {
            treeScrollPane.viewport.viewPosition = savedTreePos
            tableScrollPane.viewport.viewPosition = savedTablePos
        }
    }

    private fun switchView(viewName: String) {
        if (viewName != "TABLE") {
            clearTableBatchDecryption(repaintTable = true)
        }
        activeView = viewName
        treeViewBtn.isSelected = viewName == "TREE"
        jsonViewBtn.isSelected = viewName == "JSON"
        tableViewBtn.isSelected = viewName == "TABLE"
        val isTree = viewName == "TREE"
        expandCollapseToggleBtn.isVisible = isTree
        cardLayout.show(cardsPanel, viewName)
        updateBatchDecryptControls()
    }

    fun updateResults(
        result: QueryExecutionResult,
        skip: Int = 0,
        limit: Int = result.documents.size.coerceAtLeast(1),
        previewBanner: String? = null
    ) {
        clearAllDecryptedPlaintext(repaintTable = false)
        this.currentDocs = result.documents.toMutableList()
        this.currentResult = result
        this.currentSkip = skip
        this.currentLimit = limit
        this.previewBannerText = previewBanner

        updateSummaryAndBadges()
        populateTree(currentDocs)
        populateRawJson(currentDocs)
        populateTable(currentDocs)
        updateBatchDecryptControls()
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

        exportBtn.isEnabled = docs.isNotEmpty()
        prevBtn.isEnabled = currentSkip > 0
        nextBtn.isEnabled = result.hasNextPage
            ?: (docs.size >= currentLimit && (result.totalCount < 0 || currentSkip + docs.size < result.totalCount))
    }

    private fun populateTree(docs: List<BsonDocument>) {
        treeRoot.removeAllChildren()
        if (docs.isEmpty()) {
            treeRoot.add(DefaultMutableTreeNode(I18n.t("result.empty")))
        } else {
            docs.forEachIndexed { idx, doc ->
                val idPreview = doc["_id"]?.let { UiHelpers.bsonIdRawText(it) } ?: "doc"
                val docNode = DocTreeNode(
                    displayLabel = "[${currentSkip + idx + 1}] $idPreview",
                    bsonDocument = doc,
                    rawDocument = toRawDocument(doc),
                    docIndex = idx
                )
                addDocumentChildren(docNode, docNode, doc, "")
                treeRoot.add(docNode)
            }
        }
        treeModel.reload()

        // Expand ONLY top-level document roots via TreePath (keeps nested Object/Array collapsed)
        if (docs.isNotEmpty()) {
            expandDocumentRootsOnly()
        }
    }

    /**
     * Expands ONLY each document's top-level root node using TreePath (never expandRow),
     * revealing 1st-level fields while leaving nested Objects and Arrays collapsed.
     */
    private fun expandDocumentRootsOnly() {
        for (i in 0 until treeRoot.childCount) {
            val docNode = treeRoot.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            resultTree.expandPath(TreePath(docNode.path))
        }
        isTreeFullyExpanded = false
        updateExpandCollapseToggleButton()
    }

    /**
     * Triggered by the unified [⊞ Expand All] toggle: recursively expands nested Objects & Arrays too.
     */
    private fun expandAllRecursive() {
        fun visit(node: DefaultMutableTreeNode) {
            if (node.childCount == 0) return
            if (node !== treeRoot) {
                resultTree.expandPath(TreePath(node.path))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                visit(child)
            }
        }
        visit(treeRoot)
        isTreeFullyExpanded = true
        updateExpandCollapseToggleButton()
    }

    /**
     * Triggered by the unified [⊟ Collapse All] toggle: collapses all top-level document root nodes.
     */
    private fun collapseAllDocumentRoots() {
        for (i in treeRoot.childCount - 1 downTo 0) {
            val docNode = treeRoot.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            resultTree.collapsePath(TreePath(docNode.path))
        }
        isTreeFullyExpanded = false
        updateExpandCollapseToggleButton()
    }

    private fun updateExpandCollapseToggleButton() {
        expandCollapseToggleBtn.text = if (isTreeFullyExpanded) {
            I18n.t("result.tree.collapseAll")
        } else {
            I18n.t("result.tree.expandAll")
        }
    }

    private fun addDocumentChildren(
        rootDocNode: DocTreeNode,
        parent: DefaultMutableTreeNode,
        doc: BsonDocument,
        prefixPath: String
    ) {
        for ((k, v) in doc) {
            val fieldPath = if (prefixPath.isEmpty()) k else "$prefixPath.$k"
            val child = FieldTreeNode(rootDocNode, k, v, fieldPath)
            when (v) {
                is BsonDocument -> addDocumentChildren(rootDocNode, child, v, fieldPath)
                is BsonArray -> addArrayChildren(rootDocNode, child, v, fieldPath)
                else -> Unit
            }
            parent.add(child)
        }
    }

    private fun addArrayChildren(
        rootDocNode: DocTreeNode,
        parent: DefaultMutableTreeNode,
        arr: BsonArray,
        prefixPath: String
    ) {
        arr.forEachIndexed { i, elem ->
            val key = "[$i]"
            val fieldPath = if (prefixPath.isEmpty()) key else "$prefixPath.$key"
            val child = FieldTreeNode(rootDocNode, key, elem, fieldPath)
            when (elem) {
                is BsonDocument -> addDocumentChildren(rootDocNode, child, elem, fieldPath)
                is BsonArray -> addArrayChildren(rootDocNode, child, elem, fieldPath)
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
                StringUtil.convertLineSeparators(MongoShellNormalizer.formatPrettyExtended(doc))
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
            exportBtn.text = I18n.t("result.export.btn")
            treeViewBtn.text = I18n.t("result.view.tree")
            jsonViewBtn.text = I18n.t("result.view.json")
            tableViewBtn.text = I18n.t("result.view.table")
            prevBtn.text = I18n.t("result.prev")
            nextBtn.text = I18n.t("result.next")
            updateExpandCollapseToggleButton()
            updateSummaryAndBadges()
            updateBatchDecryptControls()
            updateBaselineBadge()
        }
    }

    override fun dispose() {
        clearAllDecryptedPlaintext(repaintTable = false)
        activeCopyOverlayPopup?.let { if (!it.isDisposed) it.cancel() }
        if (!rawJsonEditor.isDisposed) {
            EditorFactory.getInstance().releaseEditor(rawJsonEditor)
        }
    }

    private class MongoResultTableModel : AbstractTableModel() {
        data class DecryptedCellValue(
            val decryptedValue: BsonValue,
            val displayText: String,
            val copyText: String
        )

        private var columns: List<String> = emptyList()
        private var docs: MutableList<BsonDocument> = mutableListOf()
        private val decryptedCells: MutableMap<Pair<Int, Int>, DecryptedCellValue> = linkedMapOf()

        fun setDocuments(documents: List<BsonDocument>) {
            decryptedCells.clear()
            this.docs = documents.toMutableList()
            recomputeColumns()
            fireTableStructureChanged()
        }

        fun updateDocumentInPlace(rowIndex: Int, updatedDoc: BsonDocument) {
            if (rowIndex !in docs.indices) return
            decryptedCells.clear()
            docs[rowIndex] = updatedDoc
            val oldColumns = columns
            recomputeColumns()
            if (columns == oldColumns) {
                fireTableRowsUpdated(rowIndex, rowIndex)
            } else {
                fireTableStructureChanged()
            }
        }

        fun hasDecryptedCells(): Boolean = decryptedCells.isNotEmpty()

        fun isCellDecrypted(rowIndex: Int, columnIndex: Int): Boolean =
            decryptedCells.containsKey(rowIndex to columnIndex)

        fun setDecryptedCells(cells: Map<Pair<Int, Int>, DecryptedCellValue>) {
            decryptedCells.clear()
            decryptedCells.putAll(cells)
            if (docs.isNotEmpty()) {
                fireTableRowsUpdated(0, docs.size - 1)
            }
        }

        fun clearDecryptedCells(fireRowsUpdated: Boolean) {
            decryptedCells.clear()
            if (fireRowsUpdated && docs.isNotEmpty()) {
                fireTableRowsUpdated(0, docs.size - 1)
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

        fun getColumnNames(): List<String> = columns.toList()

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            decryptedCells[rowIndex to columnIndex]?.let { return it.displayText }
            val v = getBsonValueAt(rowIndex, columnIndex) ?: return ""
            return UiHelpers.bsonTableDisplayValue(v)
        }

        fun getPureCopyValueAt(rowIndex: Int, columnIndex: Int): String {
            decryptedCells[rowIndex to columnIndex]?.let { return it.copyText }
            val v = getBsonValueAt(rowIndex, columnIndex) ?: return ""
            return UiHelpers.bsonPureTextValue(v)
        }

        fun getEffectiveBsonValueAt(rowIndex: Int, columnIndex: Int): BsonValue? {
            decryptedCells[rowIndex to columnIndex]?.let { return it.decryptedValue }
            return getBsonValueAt(rowIndex, columnIndex)
        }

        fun getBsonValueAt(rowIndex: Int, columnIndex: Int): BsonValue? {
            val doc = docs.getOrNull(rowIndex) ?: return null
            val colName = columns.getOrNull(columnIndex) ?: return null
            return doc[colName]
        }
    }
}
