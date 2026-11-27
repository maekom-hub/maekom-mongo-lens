package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.crypto.CryptoService
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.IndexScanType
import com.maekom.mongolens.mongo.JoinColumnOrigin
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import com.maekom.mongolens.mongo.QueryExecutionResult
import com.maekom.mongolens.mongo.QuickFilterHelper
import com.maekom.mongolens.mongo.ReferenceJumpHelper
import com.maekom.mongolens.mongo.TrailingQueryMetadata
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.RenderingHints
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon
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
    private val isAggregationView: Boolean = false,
    private val initialView: String = "TREE",
    initialJoinColumnOrigins: Map<String, JoinColumnOrigin> = emptyMap(),
    private val availableColumnsSupplier: (() -> List<String>)? = null,
    private val onApplyColumnSelection: ((selectedColumns: List<String>, allSelected: Boolean) -> Unit)? = null,
    private val onApplyQuickFilter: ((filterJson: String) -> Unit)? = null,
    private val onMergeQuickFilter: ((patchClause: BsonDocument) -> Unit)? = null,
    private val onApplyQuickSort: ((sortJson: String) -> Unit)? = null,
    private val onOpenIndexInspector: ((result: QueryExecutionResult?) -> Unit)? = null,
    private val indexCollectionName: String = collection,
    private val collectionsSupplier: (() -> List<String>)? = null,
    private val onJumpToReferenceCollection: ((targetCollection: String, sourceFieldPath: String, sourceValue: BsonValue) -> Unit)? = null,
    private val onRequestOpenJoinDialog: ((initialLocalField: String?) -> Unit)? = null,
    private val onRequestEditJoin: (() -> Unit)? = null,
    private val onCopyJoinPipeline: (() -> Unit)? = null,
    private val onRequestImportJson: (() -> Unit)? = null
) : JPanel(BorderLayout()), Disposable {

    /**
     * Explicit root document tree node carrying the original org.bson.Document, BsonDocument,
     * and result list index directly alongside its display label.
     */
    class DocTreeNode(
        var displayLabel: String,
        var bsonDocument: BsonDocument,
        var rawDocument: Document? = null,
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
    private var joinColumnOrigins: Map<String, JoinColumnOrigin> = initialJoinColumnOrigins

    // Lazy view build & Single-Flight rendering state
    private val isDisposedFlag = AtomicBoolean(false)
    private val dataGeneration = AtomicLong(0L)
    private val activeViewBuildFuture = AtomicReference<Future<*>?>(null)
    private var treeBuiltGen: Long = -1L
    private var tableBuiltGen: Long = -1L
    private var jsonBuiltGen: Long = -1L
    private var isLoading: Boolean = false

    private val loadingSpinner = AsyncProcessIcon("MaekomMongoLensLoading")
    private val loadingStatusLabel = JBLabel(I18n.t("result.loading")).apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val loadingOverlayPanel = JPanel(GridBagLayout()).apply {
        val inner = JPanel(FlowLayout(FlowLayout.CENTER, 8, 4)).apply {
            isOpaque = false
            add(loadingSpinner)
            add(loadingStatusLabel)
        }
        add(inner)
    }

    private val headerLeftBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
    private val summaryLabel = JBLabel(" ")
    private val indexBadge = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_GREEN_BG, UiHelpers.COLOR_GREEN_FG).apply {
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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
    private val editJoinBtn = JButton(I18n.t("join.tab.editBtn"), AllIcons.General.Settings).apply {
        margin = JBUI.insets(1, 6)
        toolTipText = I18n.t("join.tab.editBtnTooltip")
        isVisible = isJoinView && onRequestEditJoin != null
    }
    private val copyJoinPipelineBtn = JButton(I18n.t("join.tab.copyPipelineBtn"), AllIcons.Actions.Copy).apply {
        margin = JBUI.insets(1, 6)
        toolTipText = I18n.t("join.tab.copyPipelineTooltip")
        isVisible = isJoinView && onCopyJoinPipeline != null
    }

    private val importJsonBtn = JButton(I18n.t("result.import.btn"), AllIcons.Actions.Upload).apply {
        margin = JBUI.insets(1, 6)
        toolTipText = I18n.t("result.import.tooltip")
        isVisible = !isJoinView && !isAggregationView && onRequestImportJson != null
    }
    private val exportBtn = JButton(I18n.t("result.export.btn")).apply {
        margin = JBUI.insets(1, 6)
        isEnabled = false
    }

    private class ViewSegmentButton(
        text: String,
        icon: Icon,
        selected: Boolean,
        private val drawRightDivider: Boolean
    ) : JToggleButton(text, icon, selected) {
        private var hovered = false

        init {
            isContentAreaFilled = false
            isFocusPainted = false
            isBorderPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(3, 9)
            iconTextGap = JBUI.scale(5)
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
                    g2.color = JBColor(Color(0xD8E8FC), Color(0x2E436E))
                    g2.fillRect(0, 0, width, height)
                    g2.color = JBUI.CurrentTheme.Focus.focusColor()
                    g2.fillRect(0, height - JBUI.scale(2), width, JBUI.scale(2))
                } else if (hovered) {
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    g2.fillRect(0, 0, width, height)
                }
                if (drawRightDivider) {
                    g2.color = JBColor.border()
                    g2.drawLine(width - 1, JBUI.scale(2), width - 1, height - JBUI.scale(2))
                }
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private val treeViewBtn = ViewSegmentButton(I18n.t("result.view.tree"), AllIcons.Actions.ShowAsTree, true, drawRightDivider = true)
    private val tableViewBtn = ViewSegmentButton(I18n.t("result.view.table"), AllIcons.Nodes.DataTables, false, drawRightDivider = true)
    private val jsonViewBtn = ViewSegmentButton(I18n.t("result.view.json"), AllIcons.FileTypes.Json, false, drawRightDivider = false)

    private val viewSegmentedControl = JPanel(GridLayout(1, 3, 0, 0)).apply {
        isOpaque = false
        border = BorderFactory.createLineBorder(JBColor.border(), 1, true)
        add(treeViewBtn)
        add(tableViewBtn)
        add(jsonViewBtn)
    }

    private val prevBtn = JButton(I18n.t("result.prev")).apply { margin = JBUI.insets(1, 6) }
    private val nextBtn = JButton(I18n.t("result.next")).apply { margin = JBUI.insets(1, 6) }

    private val cardLayout = CardLayout()
    private val cardsPanel = JPanel(cardLayout)

    // 1. Tree View
    private var treeRoot = DefaultMutableTreeNode("Results")
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
        override fun createDefaultTableHeader(): javax.swing.table.JTableHeader {
            return object : javax.swing.table.JTableHeader(columnModel) {
                override fun getToolTipText(event: MouseEvent?): String? {
                    if (event == null) return null
                    val viewCol = columnAtPoint(event.point)
                    if (viewCol < 0) return null
                    val modelCol = convertColumnIndexToModel(viewCol)
                    val rawKey = tableModel.getColumnName(modelCol)
                    val origin = joinColumnOrigins[rawKey] ?: return rawKey
                    return I18n.t("join.origin.tooltip", origin.collectionName, origin.originalFieldName)
                }
            }
        }

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
                else -> "${UiHelpers.bsonTypeTag(bsonVal)}: ${UiHelpers.truncateForDisplay(UiHelpers.bsonPureTextValue(bsonVal), 300)}"
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
        cardsPanel.add(loadingOverlayPanel, "LOADING")
        Disposer.register(this, loadingSpinner)

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
            add(editJoinBtn)
            add(copyJoinPipelineBtn)
        }

        batchDecryptBtn.addActionListener { handleTableBatchDecryptClick() }
        editJoinBtn.addActionListener { onRequestEditJoin?.invoke() }
        copyJoinPipelineBtn.addActionListener { onCopyJoinPipeline?.invoke() }
        indexBadge.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                if (onOpenIndexInspector != null) {
                    onOpenIndexInspector.invoke(currentResult)
                } else {
                    val res = currentResult
                    IndexInspectorDialog(
                        project = project,
                        connectionManager = connectionManager,
                        profile = profile,
                        database = database,
                        collection = indexCollectionName,
                        initialIndexes = res?.detailedIndexes?.takeIf { it.isNotEmpty() },
                        currentScanType = res?.scanType,
                        currentIndexName = res?.indexName,
                        explainError = res?.explainError
                    ).show()
                }
            }
        })
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

        importJsonBtn.addActionListener { onRequestImportJson?.invoke() }
        exportBtn.addActionListener { showExportMenu() }

        val rightBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            add(importJsonBtn)
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
            tableViewBtn.addActionListener { switchView("TABLE") }
            jsonViewBtn.addActionListener { switchView("JSON") }
            add(viewSegmentedControl)
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

                        // In Join View Tree, append `[<collection>.<originalField>]` origin badge at end (renderer-only)
                        if (isJoinView) {
                            val topKey = value.fieldPath.substringBefore('.')
                            val origin = joinColumnOrigins[topKey]
                            if (origin != null) {
                                val subSuffix = if (value.fieldPath.contains('.')) {
                                    value.fieldPath.substring(topKey.length)
                                } else {
                                    ""
                                }
                                append(
                                    "  [${origin.collectionName}.${origin.originalFieldName}$subSuffix]",
                                    SimpleTextAttributes.GRAYED_ATTRIBUTES
                                )
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

                val (targetDocNode, focusFieldPath, clickedBsonVal, valueOnlyText, keyValueJsonText) = when (clickedNode) {
                    is DocTreeNode -> {
                        val idVal = clickedNode.bsonDocument["_id"]
                        val prettyJson = MongoShellNormalizer.formatPrettyExtended(clickedNode.bsonDocument)
                        Quintuple(
                            clickedNode,
                            "_id".takeIf { idVal != null },
                            idVal,
                            idVal?.let { UiHelpers.bsonPureTextValue(it) } ?: prettyJson,
                            prettyJson
                        )
                    }
                    is FieldTreeNode -> {
                        val plain = decryptedFields[decryptKeyFor(clickedNode)]?.plaintext
                        val valText = plain ?: UiHelpers.bsonPureTextValue(clickedNode.bsonValue)
                        val kvJson = QuickFilterHelper.formatKeyValueJsonSnippet(
                            clickedNode.keyLabel,
                            clickedNode.bsonValue,
                            decryptedPlaintext = plain
                        )
                        Quintuple(
                            clickedNode.rootDocNode,
                            clickedNode.fieldPath,
                            clickedNode.bsonValue,
                            valText,
                            kvJson
                        )
                    }
                    else -> return
                }

                val selectedFieldItems = collectSelectedTreeFieldItems(clickedNode)

                showUnifiedContextMenu(
                    invoker = resultTree,
                    x = e.x,
                    y = e.y,
                    docIndex = targetDocNode.docIndex,
                    selectedDocIndices = selectedTreeDocIndices(),
                    targetBsonDoc = targetDocNode.bsonDocument,
                    focusFieldPath = focusFieldPath,
                    clickedBsonValue = clickedBsonVal,
                    selectedFieldItems = selectedFieldItems,
                    copyValueOnlyText = valueOnlyText,
                    copyKeyValueJsonText = keyValueJsonText
                )
            }
        })
    }

    private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    private fun collectSelectedTreeFieldItems(fallbackNode: Any): List<QuickFilterHelper.SelectedFieldItem> {
        val items = mutableListOf<QuickFilterHelper.SelectedFieldItem>()
        for (path in resultTree.selectionPaths.orEmpty()) {
            when (val n = path.lastPathComponent) {
                is FieldTreeNode -> {
                    val normPath = QuickFilterHelper.normalizeFilterFieldPath(n.fieldPath)
                    if (normPath.isNotEmpty()) {
                        items.add(QuickFilterHelper.SelectedFieldItem(normPath, n.bsonValue))
                    }
                }
                is DocTreeNode -> {
                    n.bsonDocument["_id"]?.let { idVal ->
                        items.add(QuickFilterHelper.SelectedFieldItem("_id", idVal))
                    }
                }
            }
        }
        if (items.isEmpty()) {
            when (fallbackNode) {
                is FieldTreeNode -> {
                    val normPath = QuickFilterHelper.normalizeFilterFieldPath(fallbackNode.fieldPath)
                    if (normPath.isNotEmpty()) {
                        items.add(QuickFilterHelper.SelectedFieldItem(normPath, fallbackNode.bsonValue))
                    }
                }
                is DocTreeNode -> {
                    fallbackNode.bsonDocument["_id"]?.let { idVal ->
                        items.add(QuickFilterHelper.SelectedFieldItem("_id", idVal))
                    }
                }
            }
        }
        return items
    }

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
        ToolTipManager.sharedInstance().registerComponent(resultTable.tableHeader)

        // Table Header right-click: Fast Column Selection (SELECT col1, col2) & Hide Column
        resultTable.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowHeaderContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowHeaderContextMenu(e)

            private fun maybeShowHeaderContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val viewCol = resultTable.tableHeader.columnAtPoint(e.point)
                if (viewCol < 0) return
                // Always resolve pure internal `resultFieldKey` from tableModel (never visual headerValue)
                val modelCol = resultTable.convertColumnIndexToModel(viewCol)
                val clickedColName = tableModel.getColumnName(modelCol)
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

                // Preserve existing multi-cell / multi-row selection when clicking inside it
                val alreadySelectedCell = resultTable.isCellSelected(row, col)
                val existingRows = resultTable.selectedRows
                if (!alreadySelectedCell && !(existingRows.size >= 2 && row in existingRows)) {
                    resultTable.changeSelection(row, col, false, false)
                }

                val modelRow = resultTable.convertRowIndexToModel(row)
                val modelCol = resultTable.convertColumnIndexToModel(col)
                val colName = tableModel.getColumnName(modelCol)
                val pureVal = tableModel.getPureCopyValueAt(modelRow, modelCol)
                val clickedBsonVal = tableModel.getBsonValueAt(modelRow, modelCol)
                val decryptedPlain = if (tableModel.isCellDecrypted(modelRow, modelCol)) pureVal else null
                val kvJson = if (clickedBsonVal != null) {
                    QuickFilterHelper.formatKeyValueJsonSnippet(colName, clickedBsonVal, decryptedPlaintext = decryptedPlain)
                } else {
                    "\"${MongoShellNormalizer.escapeJsonString(colName)}\": null"
                }
                val bsonDoc = currentDocs.getOrNull(modelRow) ?: return
                val selectedDocIndices = resultTable.selectedRows
                    .map { resultTable.convertRowIndexToModel(it) }
                    .filter { it in currentDocs.indices }
                    .distinct()

                val selectedFieldItems = mutableListOf<QuickFilterHelper.SelectedFieldItem>()
                for (vRow in resultTable.selectedRows) {
                    val mRow = resultTable.convertRowIndexToModel(vRow)
                    for (vCol in resultTable.selectedColumns) {
                        if (!resultTable.isCellSelected(vRow, vCol)) continue
                        val mCol = resultTable.convertColumnIndexToModel(vCol)
                        val cName = tableModel.getColumnName(mCol)
                        val bVal = tableModel.getBsonValueAt(mRow, mCol) ?: continue
                        selectedFieldItems.add(QuickFilterHelper.SelectedFieldItem(cName, bVal))
                    }
                }
                if (selectedFieldItems.isEmpty() && clickedBsonVal != null) {
                    selectedFieldItems.add(QuickFilterHelper.SelectedFieldItem(colName, clickedBsonVal))
                }

                showUnifiedContextMenu(
                    invoker = resultTable,
                    x = e.x,
                    y = e.y,
                    docIndex = modelRow,
                    selectedDocIndices = selectedDocIndices,
                    targetBsonDoc = bsonDoc,
                    focusFieldPath = colName,
                    clickedBsonValue = clickedBsonVal,
                    selectedFieldItems = selectedFieldItems,
                    copyValueOnlyText = pureVal,
                    copyKeyValueJsonText = kvJson
                )
            }
        })
    }

    private fun popupAction(
        text: String,
        icon: Icon,
        enabled: Boolean = true,
        description: String? = null,
        onPerform: () -> Unit
    ): DumbAwareAction = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled
            if (description != null) e.presentation.description = description
        }
        override fun actionPerformed(e: AnActionEvent) {
            if (enabled) onPerform()
        }
    }

    private fun popupSubmenu(text: String, icon: Icon): DefaultActionGroup =
        DefaultActionGroup(text, true).apply {
            templatePresentation.icon = icon
        }

    private fun showTableHeaderContextMenu(x: Int, y: Int, clickedColName: String) {
        val visibleCols = tableModel.getColumnNames()
        val group = DefaultActionGroup()

        if (onApplyColumnSelection != null) {
            val canHide = visibleCols.size >= 2
            group.add(
                popupAction(
                    text = I18n.t("result.table.hideColumn", clickedColName),
                    icon = AllIcons.Actions.ToggleVisibility,
                    enabled = canHide,
                    description = if (!canHide) I18n.t("result.table.hideColumnDisabledTooltip") else null
                ) {
                    val remaining = visibleCols.filter { it != clickedColName }
                    if (remaining.isNotEmpty()) {
                        onApplyColumnSelection.invoke(remaining, false)
                    }
                }
            )
            group.add(
                popupAction(
                    text = I18n.t("result.table.selectColumns"),
                    icon = AllIcons.Nodes.DataTables
                ) {
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
            )
            group.addSeparator()
        }

        group.add(
            popupAction(
                text = I18n.t("result.table.copyColumn"),
                icon = AllIcons.Actions.Copy
            ) {
                UiHelpers.copyToClipboard(clickedColName)
                UiHelpers.notify(project, I18n.t("result.copied"))
            }
        )

        val popupMenu = ActionManager.getInstance().createActionPopupMenu("MaekomMongoLensPopup", group)
        popupMenu.setTargetComponent(resultTable.tableHeader)
        popupMenu.component.show(resultTable.tableHeader, x, y)
    }

    /**
     * IntelliJ New UI native 5-group context menu (`ActionPopupMenu` + `AllIcons`) for both Tree and Table views:
     *   Group 1: Dynamic Quick Filter (Cases A-E) & Sort (disabled/hidden in Join View)
     *   Group 2: Edit (`데이터 수정` / Edit Data)
     *   Group 3: Copy submenu (Value only / Key:Value JSON / Field Path)
     *   Group 4: Reference Jump & Collection Join View
     *   Group 5: Document Diff (2 Selected Docs + Pinned Baseline)
     */
    private fun showUnifiedContextMenu(
        invoker: JComponent,
        x: Int,
        y: Int,
        docIndex: Int,
        selectedDocIndices: List<Int>,
        targetBsonDoc: BsonDocument,
        focusFieldPath: String?,
        clickedBsonValue: BsonValue?,
        selectedFieldItems: List<QuickFilterHelper.SelectedFieldItem>,
        copyValueOnlyText: String,
        copyKeyValueJsonText: String
    ) {
        val group = DefaultActionGroup()
        val normalizedFocusPath = focusFieldPath?.let { QuickFilterHelper.normalizeFilterFieldPath(it) }?.takeIf { it.isNotEmpty() }

        // ============================================================
        // Group 1: Dynamic Quick Filter & Sort (disabled in Join tabs)
        // ============================================================
        var addedGroup1 = false
        if (!isJoinView && onMergeQuickFilter != null) {
            val selectionKind = QuickFilterHelper.classifySelection(selectedFieldItems)
            when (selectionKind) {
                is QuickFilterHelper.SmartSelectionKind.SingleScalar -> {
                    addedGroup1 = true
                    group.add(popupAction(I18n.t("result.qf.equals"), AllIcons.General.Filter) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildEqualsClause(selectionKind.fieldPath, selectionKind.value)
                        )
                    })
                    group.add(popupAction(I18n.t("result.qf.notEquals"), AllIcons.General.Remove) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildNotEqualsClause(selectionKind.fieldPath, selectionKind.value)
                        )
                    })
                }
                is QuickFilterHelper.SmartSelectionKind.SameFieldMultiValues -> {
                    addedGroup1 = true
                    val n = selectionKind.values.size
                    group.add(popupAction(I18n.t("result.qf.inList", n), AllIcons.General.Filter) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildInClause(selectionKind.fieldPath, selectionKind.values)
                        )
                    })
                    group.add(popupAction(I18n.t("result.qf.ninList", n), AllIcons.General.Remove) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildNinClause(selectionKind.fieldPath, selectionKind.values)
                        )
                    })
                }
                is QuickFilterHelper.SmartSelectionKind.SameFieldDatePair -> {
                    addedGroup1 = true
                    group.add(popupAction(I18n.t("result.qf.dateRange"), AllIcons.General.History) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildDateRangeClause(
                                selectionKind.fieldPath,
                                selectionKind.minValue,
                                selectionKind.maxValue
                            )
                        )
                    })
                    group.add(popupAction(I18n.t("result.qf.dateInTwo"), AllIcons.General.Filter) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildInClause(
                                selectionKind.fieldPath,
                                listOf(selectionKind.minValue, selectionKind.maxValue)
                            )
                        )
                    })
                }
                is QuickFilterHelper.SmartSelectionKind.MultiFieldConditions -> {
                    addedGroup1 = true
                    val n = selectionKind.items.size
                    group.add(popupAction(I18n.t("result.qf.multiAnd", n), AllIcons.General.Filter) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildMultiFieldAndClause(selectionKind.items)
                        )
                    })
                    group.add(popupAction(I18n.t("result.qf.multiOr", n), AllIcons.General.Remove) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildMultiFieldOrClause(selectionKind.items)
                        )
                    })
                }
                is QuickFilterHelper.SmartSelectionKind.SingleStruct -> {
                    addedGroup1 = true
                    group.add(popupAction(I18n.t("result.qf.existsTrue"), AllIcons.General.Filter) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildExistsTrueClause(selectionKind.fieldPath)
                        )
                    })
                    group.add(popupAction(I18n.t("result.qf.emptyOrMissing"), AllIcons.General.Remove) {
                        onMergeQuickFilter.invoke(
                            QuickFilterHelper.buildEmptyOrMissingClause(selectionKind.fieldPath)
                        )
                    })
                }
                null -> Unit
            }

            // Preserve single-click ObjectId / Date UTC range filter submenu inside Group 1
            if (onApplyQuickFilter != null && normalizedFocusPath != null && clickedBsonValue != null &&
                selectedFieldItems.size <= 1
            ) {
                when (clickedBsonValue) {
                    is BsonObjectId -> {
                        addedGroup1 = true
                        val oidSub = popupSubmenu(I18n.t("result.timeFilter.menu"), AllIcons.General.History).apply {
                            add(popupAction(I18n.t("result.timeFilter.gte"), AllIcons.General.History) {
                                onApplyQuickFilter.invoke(
                                    UiHelpers.buildObjectIdTimeRangeFilter(normalizedFocusPath, clickedBsonValue.value, isGte = true)
                                )
                            })
                            add(popupAction(I18n.t("result.timeFilter.lte"), AllIcons.General.History) {
                                onApplyQuickFilter.invoke(
                                    UiHelpers.buildObjectIdTimeRangeFilter(normalizedFocusPath, clickedBsonValue.value, isGte = false)
                                )
                            })
                        }
                        group.add(oidSub)
                    }
                    is BsonDateTime -> {
                        addedGroup1 = true
                        val dateSub = popupSubmenu(I18n.t("result.dateFilter.menu"), AllIcons.General.History).apply {
                            add(popupAction(I18n.t("result.dateFilter.gte"), AllIcons.General.History) {
                                onApplyQuickFilter.invoke(
                                    UiHelpers.buildDateTimeRangeFilter(normalizedFocusPath, clickedBsonValue.value, isGte = true)
                                )
                            })
                            add(popupAction(I18n.t("result.dateFilter.lte"), AllIcons.General.History) {
                                onApplyQuickFilter.invoke(
                                    UiHelpers.buildDateTimeRangeFilter(normalizedFocusPath, clickedBsonValue.value, isGte = false)
                                )
                            })
                        }
                        group.add(dateSub)
                    }
                    else -> Unit
                }
            }

            // Sort submenu (when single field is selected)
            val distinctFields = selectedFieldItems.map { it.fieldPath }.distinct()
            val singleSortField = when {
                distinctFields.size == 1 -> distinctFields[0]
                distinctFields.isEmpty() -> normalizedFocusPath
                else -> null
            }
            if (onApplyQuickSort != null && singleSortField != null) {
                addedGroup1 = true
                val sortSub = popupSubmenu(I18n.t("result.qs.menu"), AllIcons.ObjectBrowser.Sorted).apply {
                    add(popupAction(I18n.t("result.qs.asc"), AllIcons.Actions.MoveUp) {
                        onApplyQuickSort.invoke(QuickFilterHelper.buildSortJson(singleSortField, ascending = true))
                    })
                    add(popupAction(I18n.t("result.qs.desc"), AllIcons.Actions.MoveDown) {
                        onApplyQuickSort.invoke(QuickFilterHelper.buildSortJson(singleSortField, ascending = false))
                    })
                }
                group.add(sortSub)
            }
        }

        // ============================================================
        // Group 2: Edit (`데이터 수정` — strict BSON field edit + full doc edit)
        // ============================================================
        if (addedGroup1) {
            group.addSeparator()
        }
        val isReadOnlyProfile = connectionManager.isEffectivelyReadOnly(profile)
        val hasDocumentId = targetBsonDoc.containsKey("_id")
        val editFieldPath = focusFieldPath?.let {
            com.maekom.mongolens.mongo.BsonValueValidator.normalizeMongoUpdatePath(it)
        }?.takeIf { it.isNotEmpty() }
        val isIdFieldTarget = editFieldPath == "_id"
        val canEditDocument = !isJoinView && !isAggregationView && !isReadOnlyProfile && hasDocumentId
        val docEditDisabledReason = when {
            isJoinView -> I18n.t("join.editDisabledTooltip")
            isAggregationView -> I18n.t("agg.editDisabledTooltip")
            isReadOnlyProfile -> I18n.t("edit.dialog.readOnlyBanner")
            !hasDocumentId -> I18n.t("fieldEdit.error.missingId")
            else -> null
        }
        val fieldEditEnabled = canEditDocument && !isIdFieldTarget
        val fieldEditDisabledReason = docEditDisabledReason
            ?: if (isIdFieldTarget) I18n.t("fieldEdit.error.idReadOnly") else null

        group.add(
            popupAction(
                text = I18n.t("result.doc.editData"),
                icon = AllIcons.Actions.Edit,
                enabled = fieldEditEnabled,
                description = fieldEditDisabledReason
            ) {
                ApplicationManager.getApplication().invokeLater {
                    if (editFieldPath != null) {
                        val origFieldVal = com.maekom.mongolens.mongo.BsonValueValidator.extractValueAtPath(targetBsonDoc, editFieldPath)
                            ?: clickedBsonValue
                        openFieldEditDialog(docIndex, targetBsonDoc, editFieldPath, origFieldVal)
                    } else {
                        openDocumentEditDialog(docIndex, targetBsonDoc, toRawDocument(targetBsonDoc), focusFieldPath)
                    }
                }
            }
        )
        if (editFieldPath != null) {
            group.add(
                popupAction(
                    text = I18n.t("result.doc.editFullDocument"),
                    icon = AllIcons.Actions.EditSource,
                    enabled = canEditDocument,
                    description = docEditDisabledReason
                ) {
                    ApplicationManager.getApplication().invokeLater {
                        openDocumentEditDialog(docIndex, targetBsonDoc, toRawDocument(targetBsonDoc), focusFieldPath)
                    }
                }
            )
        }

        // ============================================================
        // Group 3: Copy Submenu (Value / Key:Value JSON / Field Path)
        // ============================================================
        group.addSeparator()
        val pathToCopy = normalizedFocusPath ?: focusFieldPath
        val copySub = popupSubmenu(I18n.t("result.copy.menu"), AllIcons.Actions.Copy).apply {
            add(popupAction(I18n.t("result.copy.valueOnly"), AllIcons.Actions.Copy) {
                UiHelpers.copyToClipboard(copyValueOnlyText)
                UiHelpers.notify(project, I18n.t("result.copied"))
            })
            add(popupAction(I18n.t("result.copy.keyValueJson"), AllIcons.FileTypes.Json) {
                UiHelpers.copyToClipboard(copyKeyValueJsonText)
                UiHelpers.notify(project, I18n.t("result.copied"))
            })
            add(popupAction(I18n.t("result.copy.fieldPath"), AllIcons.Actions.ShowAsTree, enabled = !pathToCopy.isNullOrBlank()) {
                if (!pathToCopy.isNullOrBlank()) {
                    UiHelpers.copyToClipboard(pathToCopy)
                    UiHelpers.notify(project, I18n.t("result.copied"))
                }
            })
        }
        group.add(copySub)

        // ============================================================
        // Group 4: Reference Jump & Collection Join View
        // ============================================================
        val canRefJump = onJumpToReferenceCollection != null &&
            focusFieldPath != null &&
            clickedBsonValue != null &&
            ReferenceJumpHelper.isReferenceCandidate(focusFieldPath, clickedBsonValue)
        if (canRefJump || onRequestOpenJoinDialog != null) {
            group.addSeparator()
            if (canRefJump) {
                group.add(popupAction(I18n.t("result.ref.jumpToCollection"), AllIcons.Actions.Search) {
                    showReferenceCollectionPopup(invoker, Point(x, y), focusFieldPath!!, clickedBsonValue!!)
                })
            }
            if (onRequestOpenJoinDialog != null) {
                group.add(popupAction(I18n.t("result.join.openDialog"), AllIcons.Vcs.Merge) {
                    onRequestOpenJoinDialog.invoke(normalizedFocusPath ?: focusFieldPath)
                })
            }
        }

        // ============================================================
        // Group 5: Document Diff (2 Selected Docs + Pinned Baseline)
        // ============================================================
        if (diffBaselineState != null) {
            group.addSeparator()
            val canCompareTwo = selectedDocIndices.size == 2
            group.add(
                popupAction(
                    text = I18n.t("diff.menu.compareTwo"),
                    icon = AllIcons.Actions.Diff,
                    enabled = canCompareTwo,
                    description = if (!canCompareTwo) I18n.t("diff.menu.compareTwoHint") else null
                ) {
                    if (selectedDocIndices.size != 2) return@popupAction
                    val leftIdx = selectedDocIndices[0]
                    val rightIdx = selectedDocIndices[1]
                    val leftDoc = resolveDocSnapshotForDiff(leftIdx) ?: return@popupAction
                    val rightDoc = resolveDocSnapshotForDiff(rightIdx) ?: return@popupAction
                    DocumentDiffHelper.showDiff(
                        project = project,
                        contextTitle = "$database.$collection",
                        leftTitle = buildDocDiffTitle(leftIdx, leftDoc),
                        leftDoc = leftDoc,
                        rightTitle = buildDocDiffTitle(rightIdx, rightDoc),
                        rightDoc = rightDoc
                    )
                }
            )

            group.add(popupAction(I18n.t("diff.menu.pinBaseline"), AllIcons.Actions.PinTab) {
                val snapDoc = resolveDocSnapshotForDiff(docIndex) ?: return@popupAction
                val shortId = shortDocId(snapDoc, docIndex)
                val fullLabel = "${profile.displayName}/$database.$collection [$shortId]"
                diffBaselineState.pin(fullLabel, shortId, snapDoc)
                UiHelpers.notify(project, I18n.t("diff.pinnedToast", shortId))
            })

            val pinned = diffBaselineState.current
            if (pinned != null) {
                group.add(popupAction(I18n.t("diff.menu.compareWithBaseline", pinned.shortId), AllIcons.Actions.Diff) {
                    val currentSnap = resolveDocSnapshotForDiff(docIndex) ?: return@popupAction
                    DocumentDiffHelper.showDiff(
                        project = project,
                        contextTitle = "${pinned.shortId} ↔ ${shortDocId(currentSnap, docIndex)}",
                        leftTitle = I18n.t("diff.baselineLabel", pinned.fullLabel),
                        leftDoc = pinned.canonicalDoc,
                        rightTitle = "${profile.displayName}/$database.$collection ${buildDocDiffTitle(docIndex, currentSnap)}",
                        rightDoc = currentSnap
                    )
                })
            }
        }

        val popupMenu = ActionManager.getInstance().createActionPopupMenu("MaekomMongoLensPopup", group)
        popupMenu.setTargetComponent(invoker)
        popupMenu.component.show(invoker, x, y)
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
        val group = DefaultActionGroup().apply {
            add(popupAction(I18n.t("result.export.csv"), AllIcons.Nodes.DataTables) {
                if (tableBuiltGen != dataGeneration.get()) {
                    tableModel.setDocuments(currentDocs)
                    tableBuiltGen = dataGeneration.get()
                }
                val cols = tableModel.getColumnNames()
                ResultExportHelper.exportCsv(
                    project = project,
                    suggestedBaseName = collection,
                    columns = cols,
                    rowCount = tableModel.rowCount,
                    cellTextProvider = { r, c -> tableModel.getPureCopyValueAt(r, c) }
                )
            })
            add(popupAction(I18n.t("result.export.json"), AllIcons.FileTypes.Json) {
                val docsToExport = currentDocs.indices.mapNotNull { resolveDocSnapshotForDiff(it) }
                ResultExportHelper.exportJson(
                    project = project,
                    suggestedBaseName = collection,
                    documents = docsToExport
                )
            })
        }
        val popupMenu = ActionManager.getInstance().createActionPopupMenu("MaekomMongoLensPopup", group)
        popupMenu.setTargetComponent(exportBtn)
        popupMenu.component.show(exportBtn, 0, exportBtn.height)
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

    private fun openFieldEditDialog(
        docIndex: Int,
        targetBsonDoc: BsonDocument,
        fieldPath: String,
        originalValue: BsonValue?
    ) {
        try {
            FieldEditDialog(
                project = project,
                connectionManager = connectionManager,
                profile = profile,
                database = database,
                collection = collection,
                originalDoc = targetBsonDoc,
                rawFieldPath = fieldPath,
                originalValue = originalValue,
                onUpdatedInDb = { updatedDoc -> replaceDocumentInPlace(docIndex, updatedDoc) }
            ).show()
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "${t.javaClass.simpleName}: ${t.message ?: "Unknown error while opening Edit Data dialog."}",
                I18n.t("result.doc.editTitle")
            )
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

        if (treeBuiltGen == dataGeneration.get() && docIndex < treeRoot.childCount) {
            val docNode = treeRoot.getChildAt(docIndex) as? DocTreeNode
            if (docNode != null) {
                val idPreview = updatedDoc["_id"]?.let { UiHelpers.bsonIdRawText(it) } ?: "doc"
                docNode.displayLabel = "[${currentSkip + docIndex + 1}] $idPreview"
                docNode.userObject = docNode.displayLabel
                docNode.bsonDocument = updatedDoc
                docNode.rawDocument = null
                docNode.removeAllChildren()
                addDocumentChildren(docNode, docNode, updatedDoc, "")
                treeModel.nodeStructureChanged(docNode)
                resultTree.expandPath(TreePath(docNode.path))
            }
        } else {
            treeBuiltGen = -1L
        }

        if (jsonBuiltGen == dataGeneration.get()) {
            jsonBuiltGen = -1L
            if (activeView == "JSON") buildActiveViewAsync(dataGeneration.get())
        }
        clearTableBatchDecryption(repaintTable = false)
        if (tableBuiltGen == dataGeneration.get()) {
            tableModel.updateDocumentInPlace(docIndex, updatedDoc)
        } else {
            tableBuiltGen = -1L
        }
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

        if (treeBuiltGen == dataGeneration.get() && docIndex < treeRoot.childCount) {
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
        } else {
            treeBuiltGen = -1L
        }

        updateSummaryAndBadges()
        if (jsonBuiltGen == dataGeneration.get()) {
            jsonBuiltGen = -1L
            if (activeView == "JSON") buildActiveViewAsync(dataGeneration.get())
        }
        if (tableBuiltGen == dataGeneration.get()) {
            populateTable(currentDocs)
        } else {
            tableBuiltGen = -1L
        }
        updateBatchDecryptControls()

        SwingUtilities.invokeLater {
            treeScrollPane.viewport.viewPosition = savedTreePos
            tableScrollPane.viewport.viewPosition = savedTablePos
        }
    }

    fun setLoading(loading: Boolean) {
        this.isLoading = loading
        if (loading) {
            loadingSpinner.resume()
            loadingStatusLabel.text = I18n.t("result.loading")
            cardLayout.show(cardsPanel, "LOADING")
        } else {
            loadingSpinner.suspend()
            cardLayout.show(cardsPanel, activeView)
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
        if (!isLoading) {
            cardLayout.show(cardsPanel, viewName)
            val gen = dataGeneration.get()
            if (currentResult != null && !isViewBuiltForGen(viewName, gen)) {
                buildActiveViewAsync(gen)
            }
        }
        updateBatchDecryptControls()
    }

    private fun isViewBuiltForGen(viewName: String, gen: Long): Boolean = when (viewName) {
        "TREE" -> treeBuiltGen == gen
        "TABLE" -> tableBuiltGen == gen
        "JSON" -> jsonBuiltGen == gen
        else -> true
    }

    fun updateResults(
        result: QueryExecutionResult,
        skip: Int = 0,
        limit: Int = result.documents.size.coerceAtLeast(1),
        previewBanner: String? = null
    ) {
        clearAllDecryptedPlaintext(repaintTable = false)
        val gen = dataGeneration.incrementAndGet()
        activeViewBuildFuture.getAndSet(null)?.cancel(true)

        this.currentDocs = result.documents.toMutableList()
        this.currentResult = result
        this.currentSkip = skip
        this.currentLimit = limit
        this.previewBannerText = previewBanner
        this.treeBuiltGen = -1L
        this.tableBuiltGen = -1L
        this.jsonBuiltGen = -1L

        updateSummaryAndBadges()
        updateBatchDecryptControls()
        buildActiveViewAsync(gen)
    }

    /**
     * Applies trailing async metadata (O(1) cached count, conditional QUERY_PLANNER explain, and cached indexes)
     * after primary documents are already rendered on screen — zero view rebuilds.
     */
    fun updateTrailingMetadata(meta: TrailingQueryMetadata) {
        if (isDisposedFlag.get() || project.isDisposed) return
        val existing = currentResult ?: return
        val updatedIndexes = meta.detailedIndexes ?: existing.detailedIndexes
        val updatedSummaries = if (meta.detailedIndexes != null) {
            meta.detailedIndexes.map { it.summaryLine() }
        } else {
            existing.collectionIndexes
        }
        currentResult = existing.copy(
            totalCount = meta.totalCount ?: existing.totalCount,
            isApproximateCount = if (meta.totalCount != null) meta.isApproximateCount else existing.isApproximateCount,
            scanType = meta.scanType ?: existing.scanType,
            indexName = if (meta.scanType != null) meta.indexName else existing.indexName,
            explainError = if (meta.scanType != null) meta.explainError else existing.explainError,
            detailedIndexes = updatedIndexes,
            collectionIndexes = updatedSummaries
        )
        updateSummaryAndBadges()
    }

    /**
     * Lazy View Rendering: builds ONLY the currently active view (`TREE` / `TABLE` / `JSON`) off the EDT
     * using `AppExecutorUtil.getAppExecutorService()`, leaving the other 2 views unbuilt until switched to.
     */
    private fun buildActiveViewAsync(gen: Long) {
        if (isDisposedFlag.get() || project.isDisposed) return
        val targetView = activeView
        val docsSnapshot = currentDocs.toList()
        val skipSnapshot = currentSkip

        activeViewBuildFuture.getAndSet(null)?.cancel(true)
        val future = AppExecutorUtil.getAppExecutorService().submit {
            if (isDisposedFlag.get() || project.isDisposed || gen != dataGeneration.get()) return@submit
            when (targetView) {
                "TREE" -> {
                    val builtRoot = buildTreeRootOffEdt(docsSnapshot, skipSnapshot)
                    if (isDisposedFlag.get() || project.isDisposed || gen != dataGeneration.get()) return@submit
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisposedFlag.get() || project.isDisposed || gen != dataGeneration.get()) return@invokeLater
                        treeRoot = builtRoot
                        treeModel.setRoot(builtRoot)
                        treeBuiltGen = gen
                        if (docsSnapshot.isNotEmpty()) {
                            expandDocumentRootsOnly()
                        }
                        if (!isLoading) cardLayout.show(cardsPanel, activeView)
                    }
                }
                "TABLE" -> {
                    val cols = computeTableColumnsOffEdt(docsSnapshot)
                    if (isDisposedFlag.get() || project.isDisposed || gen != dataGeneration.get()) return@submit
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisposedFlag.get() || project.isDisposed || gen != dataGeneration.get()) return@invokeLater
                        tableModel.setDocumentsPrecomputed(docsSnapshot, cols)
                        applyTableHeaderDisplayLabelsAndWidths()
                        tableBuiltGen = gen
                        updateBatchDecryptControls()
                        if (!isLoading) cardLayout.show(cardsPanel, activeView)
                    }
                }
                "JSON" -> {
                    val previewText = buildTruncatedRawJsonOffEdt(docsSnapshot)
                    if (isDisposedFlag.get() || project.isDisposed || gen != dataGeneration.get()) return@submit
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisposedFlag.get() || project.isDisposed || gen != dataGeneration.get() || rawJsonEditor.isDisposed) {
                            return@invokeLater
                        }
                        ApplicationManager.getApplication().runWriteAction {
                            if (!rawJsonEditor.isDisposed) {
                                rawJsonDocument.setText(previewText)
                            }
                        }
                        jsonBuiltGen = gen
                        if (!isLoading) cardLayout.show(cardsPanel, activeView)
                    }
                }
            }
        }
        activeViewBuildFuture.set(future)
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
                val baseTip = StringUtil.escapeXmlEntities(I18n.t("result.badge.ixscanTooltip", result.indexName ?: "index"))
                val clickHint = StringUtil.escapeXmlEntities(I18n.t("result.badge.clickInspectHint"))
                indexBadge.toolTipText = "<html><b>$baseTip</b><br/><i>$clickHint</i></html>"
                indexBadge.isVisible = true
            }
            IndexScanType.COLLSCAN -> {
                indexBadge.text = I18n.t("result.badge.collscan")
                indexBadge.background = UiHelpers.COLOR_ORANGE_BG
                indexBadge.foreground = UiHelpers.COLOR_ORANGE_FG
                indexBadge.toolTipText = buildCollscanIndexHintTooltip(result.collectionIndexes)
                indexBadge.isVisible = true
            }
            IndexScanType.NO_FILTER_PREVIEW -> {
                indexBadge.text = I18n.t("result.badge.noFilterPreview")
                indexBadge.background = UiHelpers.COLOR_BLUE_BG
                indexBadge.foreground = UiHelpers.COLOR_BLUE_FG
                val tip = StringUtil.escapeXmlEntities(I18n.t("result.badge.noFilterPreviewTooltip"))
                val clickHint = StringUtil.escapeXmlEntities(I18n.t("result.badge.clickInspectHint"))
                indexBadge.toolTipText = "<html><b>$tip</b><br/><i>$clickHint</i></html>"
                indexBadge.isVisible = true
            }
            IndexScanType.UNKNOWN -> {
                if (!result.explainError.isNullOrBlank()) {
                    indexBadge.text = I18n.t("result.badge.unknown")
                    indexBadge.background = UiHelpers.COLOR_GRAY_BG
                    indexBadge.foreground = UiHelpers.COLOR_GRAY_FG
                    val reason = StringUtil.escapeXmlEntities(result.explainError)
                    val title = StringUtil.escapeXmlEntities(I18n.t("result.badge.unknownTooltip"))
                    val clickHint = StringUtil.escapeXmlEntities(I18n.t("result.badge.clickInspectHint"))
                    indexBadge.toolTipText = "<html><b>$title</b><br/>$reason<br/><br/><i>$clickHint</i></html>"
                    indexBadge.isVisible = true
                } else {
                    indexBadge.isVisible = false
                }
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

    private fun buildCollscanIndexHintTooltip(indexes: List<String>): String {
        val warn = StringUtil.escapeXmlEntities(I18n.t("result.badge.collscanTooltip"))
        val clickHint = StringUtil.escapeXmlEntities(I18n.t("result.badge.clickInspectHint"))
        if (indexes.isEmpty()) return "<html><b>$warn</b><br/><i>$clickHint</i></html>"
        val header = StringUtil.escapeXmlEntities(I18n.t("result.badge.availableIndexes"))
        val bullets = indexes.joinToString("<br/>") { idx ->
            "• ${StringUtil.escapeXmlEntities(idx)}"
        }
        return "<html><b>$warn</b><br/><br/><b>$header</b><br/>$bullets<br/><br/><i>$clickHint</i></html>"
    }

    private fun buildTreeRootOffEdt(docs: List<BsonDocument>, skipOffset: Int): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("Results")
        if (docs.isEmpty()) {
            root.add(DefaultMutableTreeNode(I18n.t("result.empty")))
        } else {
            docs.forEachIndexed { idx, doc ->
                val idPreview = doc["_id"]?.let { UiHelpers.bsonIdRawText(it) } ?: "doc"
                val docNode = DocTreeNode(
                    displayLabel = "[${skipOffset + idx + 1}] $idPreview",
                    bsonDocument = doc,
                    rawDocument = null,
                    docIndex = idx
                )
                addDocumentChildren(docNode, docNode, doc, "")
                root.add(docNode)
            }
        }
        return root
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
        prefixPath: String,
        depth: Int = 0
    ) {
        if (depth >= 8) {
            parent.add(DefaultMutableTreeNode("... ({ ${doc.size} fields }, truncated)"))
            return
        }
        for ((k, v) in doc) {
            val fieldPath = if (prefixPath.isEmpty()) k else "$prefixPath.$k"
            val child = FieldTreeNode(rootDocNode, k, v, fieldPath)
            when (v) {
                is BsonDocument -> addDocumentChildren(rootDocNode, child, v, fieldPath, depth + 1)
                is BsonArray -> addArrayChildren(rootDocNode, child, v, fieldPath, depth + 1)
                else -> Unit
            }
            parent.add(child)
        }
    }

    private fun addArrayChildren(
        rootDocNode: DocTreeNode,
        parent: DefaultMutableTreeNode,
        arr: BsonArray,
        prefixPath: String,
        depth: Int = 0
    ) {
        if (depth >= 8) {
            parent.add(DefaultMutableTreeNode("... ([ ${arr.size} items ], truncated)"))
            return
        }
        val previewLimit = arr.size.coerceAtMost(UiHelpers.MAX_PREVIEW_ARRAY_ELEMENTS)
        for (i in 0 until previewLimit) {
            val elem = arr[i]
            val key = "[$i]"
            val fieldPath = if (prefixPath.isEmpty()) key else "$prefixPath.$key"
            val child = FieldTreeNode(rootDocNode, key, elem, fieldPath)
            when (elem) {
                is BsonDocument -> addDocumentChildren(rootDocNode, child, elem, fieldPath, depth + 1)
                is BsonArray -> addArrayChildren(rootDocNode, child, elem, fieldPath, depth + 1)
                else -> Unit
            }
            parent.add(child)
        }
        if (arr.size > previewLimit) {
            parent.add(DefaultMutableTreeNode("... (+${arr.size - previewLimit} more items, truncated)"))
        }
    }

    private fun buildTruncatedRawJsonOffEdt(docs: List<BsonDocument>): String {
        if (docs.isEmpty()) return "[]"
        val sb = StringBuilder("[\n")
        for ((idx, doc) in docs.withIndex()) {
            if (sb.length >= UiHelpers.MAX_JSON_PREVIEW_TOTAL_CHARS) {
                val remaining = docs.size - idx
                sb.append("  \"... (+$remaining more documents truncated for display preview; Export JSON contains full data)\"\n")
                break
            }
            val safeDoc = UiHelpers.buildDisplaySafePreviewDocument(doc)
            val pretty = StringUtil.convertLineSeparators(MongoShellNormalizer.formatPrettyExtended(safeDoc))
                .lines()
                .joinToString("\n") { "  $it" }
            sb.append(pretty)
            if (idx < docs.lastIndex) sb.append(",\n") else sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun computeTableColumnsOffEdt(docs: List<BsonDocument>): List<String> {
        val keys = linkedSetOf<String>()
        if (docs.any { it.containsKey("_id") }) {
            keys.add("_id")
        }
        for (d in docs) {
            keys.addAll(d.keys)
        }
        return keys.toList()
    }

    fun setJoinColumnOrigins(origins: Map<String, JoinColumnOrigin>) {
        this.joinColumnOrigins = origins
        applyTableHeaderDisplayLabelsAndWidths()
        resultTree.repaint()
    }

    private fun populateTable(docs: List<BsonDocument>) {
        tableModel.setDocuments(docs)
        applyTableHeaderDisplayLabelsAndWidths()
    }

    private fun applyTableHeaderDisplayLabelsAndWidths() {
        val cm = resultTable.columnModel
        val header = resultTable.tableHeader
        val fm = header.getFontMetrics(header.font)
        for (i in 0 until cm.columnCount) {
            val column = cm.getColumn(i)
            val modelCol = resultTable.convertColumnIndexToModel(i)
            val rawKey = tableModel.getColumnName(modelCol)
            val origin = joinColumnOrigins[rawKey]
            val displayHeader = if (origin != null) {
                "${origin.originalFieldName} (${origin.collectionName}.${origin.originalFieldName})"
            } else {
                rawKey
            }
            column.headerValue = displayHeader
            val headerWidth = fm.stringWidth(displayHeader) + JBUI.scale(32)
            val baseMin = if (i == 0) JBUI.scale(210) else JBUI.scale(150)
            column.minWidth = JBUI.scale(120)
            column.preferredWidth = maxOf(baseMin, headerWidth)
        }
        header.revalidate()
        header.repaint()
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            importJsonBtn.text = I18n.t("result.import.btn")
            importJsonBtn.toolTipText = I18n.t("result.import.tooltip")
            exportBtn.text = I18n.t("result.export.btn")
            editJoinBtn.text = I18n.t("join.tab.editBtn")
            editJoinBtn.toolTipText = I18n.t("join.tab.editBtnTooltip")
            copyJoinPipelineBtn.text = I18n.t("join.tab.copyPipelineBtn")
            copyJoinPipelineBtn.toolTipText = I18n.t("join.tab.copyPipelineTooltip")
            treeViewBtn.text = I18n.t("result.view.tree")
            jsonViewBtn.text = I18n.t("result.view.json")
            tableViewBtn.text = I18n.t("result.view.table")
            prevBtn.text = I18n.t("result.prev")
            nextBtn.text = I18n.t("result.next")
            loadingStatusLabel.text = I18n.t("result.loading")
            updateExpandCollapseToggleButton()
            updateSummaryAndBadges()
            updateBatchDecryptControls()
            updateBaselineBadge()
            if (joinColumnOrigins.isNotEmpty()) {
                applyTableHeaderDisplayLabelsAndWidths()
            }
        }
    }

    override fun dispose() {
        isDisposedFlag.set(true)
        dataGeneration.incrementAndGet()
        activeViewBuildFuture.getAndSet(null)?.cancel(true)
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

        fun setDocumentsPrecomputed(documents: List<BsonDocument>, precomputedColumns: List<String>) {
            decryptedCells.clear()
            this.docs = documents.toMutableList()
            this.columns = precomputedColumns
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
