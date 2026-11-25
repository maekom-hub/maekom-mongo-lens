package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import org.bson.BsonArray
import org.bson.BsonBinary
import org.bson.BsonBinarySubType
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.Document
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID
import javax.swing.AbstractCellEditor
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor

class DocumentEditDialog(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val originalDoc: BsonDocument,
    @Suppress("unused")
    private val rawDocument: Document? = null,
    private val isReadOnly: Boolean,
    private val focusFieldPath: String? = null,
    private val onSavedInDb: (BsonDocument) -> Unit,
    private val onDeletedInDb: () -> Unit
) : DialogWrapper(project) {

    enum class BsonFieldType(val label: String) {
        STRING("String"),
        INT32("Int32"),
        INT64("Int64"),
        DOUBLE("Double"),
        DECIMAL128("Decimal128"),
        BOOLEAN("Boolean"),
        DATE("Date"),
        OBJECT_ID("ObjectId"),
        UUID_TYPE("UUID"),
        ARRAY("Array"),
        OBJECT("Object"),
        NULL("Null");

        override fun toString(): String = label

        fun isContainer(): Boolean = this == OBJECT || this == ARRAY

        companion object {
            fun fromBsonValue(v: BsonValue): BsonFieldType = when (v) {
                is BsonString -> STRING
                is BsonInt32 -> INT32
                is BsonInt64 -> INT64
                is BsonDouble -> DOUBLE
                is BsonDecimal128 -> DECIMAL128
                is BsonBoolean -> BOOLEAN
                is BsonDateTime -> DATE
                is BsonObjectId -> OBJECT_ID
                is BsonBinary -> {
                    val sub = v.type
                    if ((sub == BsonBinarySubType.UUID_STANDARD.value || sub == BsonBinarySubType.UUID_LEGACY.value) &&
                        v.data.size == 16
                    ) {
                        UUID_TYPE
                    } else {
                        STRING
                    }
                }
                is BsonArray -> ARRAY
                is BsonDocument -> OBJECT
                is BsonNull -> NULL
                else -> STRING
            }
        }
    }

    private class EditableFieldNode(
        var key: String,
        var type: BsonFieldType,
        var valueText: String,
        var parent: EditableFieldNode? = null,
        var expanded: Boolean = true,
        val children: MutableList<EditableFieldNode> = mutableListOf(),
        var originalBsonValue: BsonValue? = null,
        var modified: Boolean = false
    ) {
        fun depth(): Int {
            var d = 0
            var p = parent
            while (p != null) {
                d++
                p = p.parent
            }
            return (d - 1).coerceAtLeast(0)
        }

        fun pathFromRoot(root: EditableFieldNode): String {
            if (this === root) return ""
            val segs = mutableListOf<String>()
            var cur: EditableFieldNode? = this
            while (cur != null && cur !== root) {
                segs.add(0, cur.key)
                cur = cur.parent
            }
            return segs.joinToString(".")
        }

        fun isRootId(root: EditableFieldNode): Boolean = parent === root && key == "_id"

        fun isArrayElement(): Boolean = parent?.type == BsonFieldType.ARRAY

        fun summaryValue(): String = when (type) {
            BsonFieldType.OBJECT -> "{ ${children.size} fields }"
            BsonFieldType.ARRAY -> "[ ${children.size} items ]"
            BsonFieldType.NULL -> "null"
            else -> valueText
        }
    }

    private val originalId: BsonValue? = originalDoc["_id"]
    private val originalPrettyJson: String = safePrettyJson(originalDoc)
    private val rootNode = EditableFieldNode("root", BsonFieldType.OBJECT, "", parent = null, expanded = true)
    private val treeTableModel = DocumentTreeTableModel()

    // Drag-and-drop state on dedicated Drag Handle column (col 0)
    private var draggingNode: EditableFieldNode? = null
    private var dropHighlightRow: Int = -1
    private var allowCloseWithoutDirtyPrompt = false

    private val structuredModeBtn = JToggleButton(I18n.t("edit.dialog.mode.structured"), true).apply {
        margin = JBUI.insets(2, 8)
    }
    private val rawJsonModeBtn = JToggleButton(I18n.t("edit.dialog.mode.rawJson"), false).apply {
        margin = JBUI.insets(2, 8)
    }
    private var activeMode = "STRUCTURED"

    private val readOnlyBanner = JBLabel("🔒 ${I18n.t("edit.dialog.readOnlyBanner")}").apply {
        isOpaque = true
        background = UiHelpers.COLOR_ORANGE_BG
        foreground = UiHelpers.COLOR_ORANGE_FG
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(6, 10)
        isVisible = isReadOnly
    }

    private val errorBanner = JBLabel("").apply {
        isOpaque = true
        background = UiHelpers.COLOR_RED_BG
        foreground = UiHelpers.COLOR_RED_FG
        border = JBUI.Borders.empty(6, 10)
        isVisible = false
    }

    private val cardLayout = CardLayout()
    private val modeCardsPanel = JPanel(cardLayout)

    private val treeTable: JBTable = object : JBTable(treeTableModel) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val targetRow = dropHighlightRow
            if (draggingNode != null && targetRow >= 0 && targetRow < rowCount) {
                val g2 = g.create() as Graphics2D
                try {
                    val rect = getCellRect(targetRow, 0, true)
                    g2.color = UiHelpers.COLOR_BLUE_FG
                    g2.stroke = BasicStroke(2f)
                    g2.drawLine(0, rect.y, width, rect.y)
                } finally {
                    g2.dispose()
                }
            }
        }
    }.apply {
        rowHeight = 28
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private val addFieldBtn = JButton(I18n.t("edit.dialog.addField")).apply {
        margin = JBUI.insets(2, 8)
        isEnabled = !isReadOnly
    }
    private val moveUpBtn = JButton(I18n.t("edit.dialog.moveUp")).apply {
        margin = JBUI.insets(2, 8)
        isEnabled = !isReadOnly
    }
    private val moveDownBtn = JButton(I18n.t("edit.dialog.moveDown")).apply {
        margin = JBUI.insets(2, 8)
        isEnabled = !isReadOnly
    }

    private val rawJsonEditor = MongoJsonEditor(
        projectRef = project,
        initialText = originalPrettyJson,
        multiLine = true
    )

    private val deleteDocBtn = JButton(I18n.t("edit.dialog.deleteDocBtn")).apply {
        foreground = UiHelpers.COLOR_RED_FG
        font = font.deriveFont(Font.BOLD)
        margin = JBUI.insets(4, 10)
        isEnabled = !isReadOnly && originalId != null
    }
    private val saveBtn = JButton(I18n.t("edit.dialog.save")).apply {
        font = font.deriveFont(Font.BOLD)
        margin = JBUI.insets(4, 14)
        isEnabled = !isReadOnly
    }
    private val cancelBtn = JButton(I18n.t("edit.dialog.cancel")).apply {
        margin = JBUI.insets(4, 12)
    }

    init {
        title = "${I18n.t("result.doc.editTitle")} — $database.$collection"
        loadBsonIntoTree(originalDoc)
        configureTreeTable()
        init()
        installWindowCloseGuard()
        focusInitialPath()
    }

    private fun installWindowCloseGuard() {
        val jDialog = peer.window as? JDialog ?: return
        jDialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        // Remove default windowClosing listeners that bypass doCancelAction if present
        jDialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                doCancelAction()
            }
        })
    }

    override fun doCancelAction() {
        if (allowCloseWithoutDirtyPrompt || isReadOnly) {
            super.doCancelAction()
            return
        }

        // Always commit any active cell edit before computing field diff
        stopCellEditing()

        val diffs = computeUnsavedDiffs()
        if (diffs.isEmpty()) {
            super.doCancelAction()
            return
        }

        val confirmDlg = UnsavedChangesDialog(project, diffs)
        confirmDlg.show()
        when (confirmDlg.selectedChoice) {
            UnsavedChangesChoice.SAVE_AND_CLOSE -> performSaveDocument()
            UnsavedChangesChoice.DONT_SAVE -> {
                allowCloseWithoutDirtyPrompt = true
                super.doCancelAction()
            }
            UnsavedChangesChoice.KEEP_EDITING -> Unit
        }
    }

    private fun computeUnsavedDiffs(): List<String> {
        val currentDoc = try {
            if (activeMode == "STRUCTURED") {
                rebuildBsonDocumentFromTree()
            } else {
                MongoShellNormalizer.parseBsonDocument(rawJsonEditor.text)
            }
        } catch (_: Throwable) {
            // If user edited Raw JSON into an invalid state or typed invalid value in table
            return if (activeMode == "RAW" && rawJsonEditor.text.trim() == originalPrettyJson.trim()) {
                emptyList()
            } else {
                listOf(I18n.t("edit.dialog.diff.rawModified"))
            }
        }

        val diffs = mutableListOf<String>()
        diffDocuments(originalDoc, currentDoc, "", diffs)
        return diffs
    }

    private fun diffDocuments(orig: BsonDocument, curr: BsonDocument, prefix: String, out: MutableList<String>) {
        val origKeys = orig.keys.toList()
        val currKeys = curr.keys.toList()

        for (k in origKeys) {
            val fullPath = if (prefix.isEmpty()) k else "$prefix.$k"
            if (!curr.containsKey(k)) {
                out.add(I18n.t("edit.dialog.diff.deleted", fullPath))
            } else {
                val ov = orig[k]!!
                val cv = curr[k]!!
                if (ov.isDocument && cv.isDocument) {
                    diffDocuments(ov.asDocument(), cv.asDocument(), fullPath, out)
                } else if (ov != cv) {
                    out.add(
                        I18n.t(
                            "edit.dialog.diff.modified",
                            fullPath,
                            UiHelpers.bsonDisplayValue(ov),
                            UiHelpers.bsonDisplayValue(cv)
                        )
                    )
                }
            }
        }

        for (k in currKeys) {
            if (!orig.containsKey(k)) {
                val fullPath = if (prefix.isEmpty()) k else "$prefix.$k"
                out.add(I18n.t("edit.dialog.diff.added", fullPath, UiHelpers.bsonDisplayValue(curr[k]!!)))
            }
        }

        // Check key ordering among common keys
        val origCommon = origKeys.filter { curr.containsKey(it) }
        val currCommon = currKeys.filter { orig.containsKey(it) }
        if (origCommon != currCommon) {
            val reorderMsg = I18n.t("edit.dialog.diff.reordered")
            if (reorderMsg !in out) {
                out.add(reorderMsg)
            }
        }
    }

    private fun safePrettyJson(doc: BsonDocument): String {
        val raw = try {
            MongoShellNormalizer.formatPrettyExtended(doc)
        } catch (_: Throwable) {
            doc.toJson()
        }
        return StringUtil.convertLineSeparators(raw)
    }

    private fun loadBsonIntoTree(doc: BsonDocument) {
        rootNode.children.clear()
        // Ensure _id is always pinned at index 0 if present
        val idVal = doc["_id"]
        if (idVal != null) {
            rootNode.children.add(buildNodeFromBson("_id", idVal, rootNode))
        }
        for ((k, v) in doc) {
            if (k == "_id") continue
            rootNode.children.add(buildNodeFromBson(k, v, rootNode))
        }
        treeTableModel.rebuildVisibleRows()
    }

    private fun buildNodeFromBson(key: String, value: BsonValue, parent: EditableFieldNode): EditableFieldNode {
        val type = BsonFieldType.fromBsonValue(value)
        val text = when (value) {
            is BsonString -> value.value
            is BsonInt32 -> value.value.toString()
            is BsonInt64 -> value.value.toString()
            is BsonDouble -> value.value.toString()
            is BsonDecimal128 -> value.value.toString()
            is BsonBoolean -> value.value.toString()
            is BsonDateTime -> UiHelpers.formatIsoUtc(value.value)
            is BsonObjectId -> value.value.toHexString()
            is BsonBinary -> UiHelpers.bsonIdRawText(value)
            is BsonNull -> "null"
            is BsonDocument, is BsonArray -> ""
            else -> UiHelpers.bsonPureTextValue(value)
        }
        val node = EditableFieldNode(
            key = key,
            type = type,
            valueText = text,
            parent = parent,
            expanded = true,
            originalBsonValue = value,
            modified = false
        )
        when (value) {
            is BsonDocument -> {
                for ((ck, cv) in value) {
                    node.children.add(buildNodeFromBson(ck, cv, node))
                }
            }
            is BsonArray -> {
                value.forEachIndexed { idx, elem ->
                    node.children.add(buildNodeFromBson("[$idx]", elem, node))
                }
            }
            else -> Unit
        }
        return node
    }

    private fun configureTreeTable() {
        val cm = treeTable.columnModel
        // Col 0: Drag Handle (≡)
        cm.getColumn(0).preferredWidth = 28
        cm.getColumn(0).maxWidth = 30
        cm.getColumn(0).minWidth = 26
        // Col 1: Key
        cm.getColumn(1).preferredWidth = 210
        // Col 2: BSON Type
        cm.getColumn(2).preferredWidth = 105
        cm.getColumn(2).maxWidth = 135
        // Col 3: Value (Dynamic BSON Type Editor)
        cm.getColumn(3).preferredWidth = 340
        // Col 4: Delete [✕]
        cm.getColumn(4).preferredWidth = 34
        cm.getColumn(4).maxWidth = 38
        cm.getColumn(4).minWidth = 32

        // Col 0: Drag handle renderer
        cm.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val lbl = super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column) as JLabel
                lbl.horizontalAlignment = SwingConstants.CENTER
                val node = treeTableModel.getNodeAt(row)
                val draggable = !isReadOnly && node != null && !node.isRootId(rootNode)
                lbl.text = if (draggable) "≡" else if (node?.isRootId(rootNode) == true) "🔒" else ""
                lbl.foreground = UiHelpers.BSON_NULL_COLOR
                lbl.toolTipText = if (draggable) I18n.t("edit.dialog.dragHandleTooltip") else null
                return lbl
            }
        }

        // Col 1: Indented Key renderer with expand/collapse chevron
        cm.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val lbl = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val node = treeTableModel.getNodeAt(row)
                if (node != null) {
                    val indentPx = node.depth() * 16 + 6
                    lbl.border = JBUI.Borders.emptyLeft(indentPx)
                    val prefix = when {
                        node.type.isContainer() -> if (node.expanded) "▾ " else "▸ "
                        else -> "   "
                    }
                    lbl.text = prefix + node.key
                    lbl.font = if (node.isRootId(rootNode) || node.type.isContainer()) {
                        lbl.font.deriveFont(Font.BOLD)
                    } else {
                        lbl.font.deriveFont(Font.PLAIN)
                    }
                    if (!isSelected) {
                        lbl.foreground = if (node.isRootId(rootNode)) UiHelpers.BSON_OID_COLOR else table.foreground
                    }
                }
                return lbl
            }
        }

        // Col 2: BSON Type dropdown editor & colored badge renderer
        val typeCombo = JComboBox(BsonFieldType.values())
        cm.getColumn(2).cellEditor = DefaultCellEditor(typeCombo)
        cm.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val lbl = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val node = treeTableModel.getNodeAt(row)
                if (node != null && !isSelected) {
                    lbl.foreground = when (node.type) {
                        BsonFieldType.OBJECT_ID, BsonFieldType.UUID_TYPE -> UiHelpers.BSON_OID_COLOR
                        BsonFieldType.DATE -> UiHelpers.BSON_DATE_COLOR
                        BsonFieldType.INT32, BsonFieldType.INT64, BsonFieldType.DOUBLE, BsonFieldType.DECIMAL128 -> UiHelpers.BSON_NUM_COLOR
                        BsonFieldType.STRING -> UiHelpers.BSON_STR_COLOR
                        BsonFieldType.BOOLEAN -> UiHelpers.BSON_BOOL_COLOR
                        BsonFieldType.NULL -> UiHelpers.BSON_NULL_COLOR
                        else -> UiHelpers.BSON_STRUCT_COLOR
                    }
                }
                return lbl
            }
        }

        // Col 3: Dynamic BSON-type-specific Value Cell Editor & Renderer
        cm.getColumn(3).cellEditor = DynamicBsonValueCellEditor()
        cm.getColumn(3).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val lbl = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val node = treeTableModel.getNodeAt(row)
                if (node != null && !isSelected) {
                    lbl.foreground = when {
                        node.type.isContainer() || node.type == BsonFieldType.NULL -> UiHelpers.BSON_NULL_COLOR
                        node.type == BsonFieldType.STRING -> UiHelpers.BSON_STR_COLOR
                        node.type == BsonFieldType.OBJECT_ID || node.type == BsonFieldType.UUID_TYPE -> UiHelpers.BSON_OID_COLOR
                        node.type == BsonFieldType.DATE -> UiHelpers.BSON_DATE_COLOR
                        node.type == BsonFieldType.BOOLEAN -> UiHelpers.BSON_BOOL_COLOR
                        else -> UiHelpers.BSON_NUM_COLOR
                    }
                }
                return lbl
            }
        }

        // Col 4: [✕] Delete row button renderer
        cm.getColumn(4).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val lbl = super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column) as JLabel
                lbl.horizontalAlignment = SwingConstants.CENTER
                val node = treeTableModel.getNodeAt(row)
                val canDelete = !isReadOnly && node != null && !node.isRootId(rootNode)
                lbl.icon = if (canDelete) AllIcons.Actions.Close else null
                lbl.toolTipText = if (canDelete) I18n.t("edit.dialog.deleteKeyTooltip") else null
                return lbl
            }
        }

        // Dedicated Drag Handle DnD (Col 0) + Chevron Expand/Collapse (Col 1) + Delete (Col 4)
        treeTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val row = treeTable.rowAtPoint(e.point)
                val col = treeTable.columnAtPoint(e.point)
                if (row < 0 || col < 0) return
                val node = treeTableModel.getNodeAt(row) ?: return

                // Col 0: Start Drag-and-Drop reorder on handle
                if (col == 0 && !isReadOnly && !node.isRootId(rootNode)) {
                    stopCellEditing()
                    draggingNode = node
                    dropHighlightRow = row
                    treeTable.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    treeTable.setRowSelectionInterval(row, row)
                    return
                }

                // Col 4: Delete individual key line immediately
                if (col == 4 && !isReadOnly && !node.isRootId(rootNode)) {
                    stopCellEditing()
                    deleteNode(node)
                    return
                }

                // Col 1: Toggle expand/collapse on chevron click for Object/Array
                if (col == 1 && node.type.isContainer()) {
                    val cellRect = treeTable.getCellRect(row, col, false)
                    val chevronEnd = cellRect.x + node.depth() * 16 + 24
                    if (e.x <= chevronEnd || e.clickCount == 2) {
                        stopCellEditing()
                        node.expanded = !node.expanded
                        treeTableModel.rebuildVisibleRows()
                        if (row < treeTable.rowCount) {
                            treeTable.setRowSelectionInterval(row, row)
                        }
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                val dragged = draggingNode ?: return
                val targetRow = dropHighlightRow
                draggingNode = null
                dropHighlightRow = -1
                treeTable.cursor = Cursor.getDefaultCursor()
                treeTable.repaint()

                if (targetRow >= 0 && targetRow < treeTable.rowCount) {
                    val targetNode = treeTableModel.getNodeAt(targetRow)
                    if (targetNode != null && targetNode !== dragged && targetNode.parent === dragged.parent) {
                        reorderSibling(dragged, targetNode)
                    }
                }
            }
        })

        treeTable.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val dragged = draggingNode ?: return
                val row = treeTable.rowAtPoint(e.point).coerceIn(0, (treeTable.rowCount - 1).coerceAtLeast(0))
                val candidate = treeTableModel.getNodeAt(row)
                if (candidate != null && candidate.parent === dragged.parent && !candidate.isRootId(rootNode)) {
                    dropHighlightRow = row
                    treeTable.repaint()
                }
            }
        })
    }

    private fun reorderSibling(dragged: EditableFieldNode, targetSibling: EditableFieldNode) {
        val parent = dragged.parent ?: return
        if (targetSibling.isRootId(rootNode)) return
        val fromIdx = parent.children.indexOf(dragged)
        val toIdx = parent.children.indexOf(targetSibling)
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
        val minIdx = if (parent === rootNode && parent.children.firstOrNull()?.isRootId(rootNode) == true) 1 else 0
        val clampedTo = toIdx.coerceAtLeast(minIdx)

        parent.children.removeAt(fromIdx)
        parent.children.add(clampedTo, dragged)
        if (parent.type == BsonFieldType.ARRAY) {
            parent.children.forEachIndexed { idx, child -> child.key = "[$idx]" }
        }
        treeTableModel.rebuildVisibleRows()
        val newRow = treeTableModel.indexOfNode(dragged)
        if (newRow >= 0) {
            treeTable.setRowSelectionInterval(newRow, newRow)
            treeTable.scrollRectToVisible(treeTable.getCellRect(newRow, 1, true))
        }
    }

    private fun moveSelectedRow(delta: Int) {
        if (isReadOnly) return
        stopCellEditing()
        val selectedRow = treeTable.selectedRow
        if (selectedRow < 0) return
        val node = treeTableModel.getNodeAt(selectedRow) ?: return
        if (node.isRootId(rootNode)) return
        val parent = node.parent ?: return

        val idx = parent.children.indexOf(node)
        val minIdx = if (parent === rootNode && parent.children.firstOrNull()?.isRootId(rootNode) == true) 1 else 0
        val targetIdx = idx + delta
        if (idx < minIdx || targetIdx < minIdx || targetIdx !in parent.children.indices) return

        parent.children.removeAt(idx)
        parent.children.add(targetIdx, node)
        if (parent.type == BsonFieldType.ARRAY) {
            parent.children.forEachIndexed { i, child -> child.key = "[$i]" }
        }
        treeTableModel.rebuildVisibleRows()
        val newRow = treeTableModel.indexOfNode(node)
        if (newRow >= 0) {
            treeTable.setRowSelectionInterval(newRow, newRow)
            treeTable.scrollRectToVisible(treeTable.getCellRect(newRow, 1, true))
        }
    }

    private fun deleteNode(node: EditableFieldNode) {
        val parent = node.parent ?: return
        parent.children.remove(node)
        if (parent.type == BsonFieldType.ARRAY) {
            parent.children.forEachIndexed { idx, child -> child.key = "[$idx]" }
        }
        treeTableModel.rebuildVisibleRows()
    }

    private fun stopCellEditing() {
        if (treeTable.isEditing) {
            treeTable.cellEditor?.stopCellEditing()
        }
    }

    private fun collectContainerTargets(): List<Pair<String, EditableFieldNode>> {
        val targets = mutableListOf<Pair<String, EditableFieldNode>>()
        targets.add(I18n.t("edit.dialog.addFieldRootPath") to rootNode)
        fun walk(parent: EditableFieldNode) {
            for (child in parent.children) {
                if (child.type.isContainer()) {
                    val path = child.pathFromRoot(rootNode)
                    val tag = if (child.type == BsonFieldType.ARRAY) "Array" else "Object"
                    targets.add("$path ($tag)" to child)
                    walk(child)
                }
            }
        }
        walk(rootNode)
        return targets
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 6)).apply {
            preferredSize = Dimension(820, 560)
            border = JBUI.Borders.empty(4)
        }

        val idDisplay = originalId?.let { UiHelpers.bsonIdRawText(it) } ?: "(no _id)"
        val copyIdMiniBtn = JButton(I18n.t("edit.dialog.copyId"), AllIcons.Actions.Copy).apply {
            margin = JBUI.insets(1, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isEnabled = originalId != null
            addActionListener {
                if (originalId != null) {
                    UiHelpers.copyToClipboard(UiHelpers.bsonIdRawText(originalId))
                    UiHelpers.notify(project, I18n.t("result.copied"))
                }
            }
        }

        val metaLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(UiHelpers.createBadgeLabel("$database.$collection", UiHelpers.COLOR_BLUE_BG, UiHelpers.COLOR_BLUE_FG))
            add(JBLabel("_id: $idDisplay").apply {
                font = Font(Font.MONOSPACED, Font.BOLD, 12)
            })
            add(copyIdMiniBtn)
        }

        val metaRight = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            add(structuredModeBtn)
            add(rawJsonModeBtn)
        }

        val headerRow = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            add(metaLeft, BorderLayout.WEST)
            add(metaRight, BorderLayout.EAST)
        }

        val bannerStack = JPanel(BorderLayout(0, 2)).apply {
            add(readOnlyBanner, BorderLayout.NORTH)
            add(errorBanner, BorderLayout.SOUTH)
        }

        val topStack = JPanel(BorderLayout(0, 4)).apply {
            add(headerRow, BorderLayout.NORTH)
            add(bannerStack, BorderLayout.SOUTH)
        }

        // Structured mode toolbar: [+ Add Key/Value] [▲ Move Up] [▼ Move Down]
        val structuredToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(addFieldBtn)
            add(moveUpBtn)
            add(moveDownBtn)
        }
        val structuredPanel = JPanel(BorderLayout(0, 4)).apply {
            add(structuredToolbar, BorderLayout.NORTH)
            add(JBScrollPane(treeTable), BorderLayout.CENTER)
        }

        val copyFullJsonBtn = JButton(I18n.t("edit.dialog.copyFullJson"), AllIcons.Actions.Copy).apply {
            margin = JBUI.insets(2, 8)
            addActionListener {
                UiHelpers.copyToClipboard(rawJsonEditor.text)
                UiHelpers.notify(project, I18n.t("result.copied"))
            }
        }
        if (isReadOnly) {
            rawJsonEditor.isEnabled = false
        }
        val rawJsonPanel = JPanel(BorderLayout(0, 4)).apply {
            val rawTop = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
                add(copyFullJsonBtn)
            }
            add(rawTop, BorderLayout.NORTH)
            add(rawJsonEditor, BorderLayout.CENTER)
        }

        modeCardsPanel.add(structuredPanel, "STRUCTURED")
        modeCardsPanel.add(rawJsonPanel, "RAW")

        root.add(topStack, BorderLayout.NORTH)
        root.add(modeCardsPanel, BorderLayout.CENTER)

        wireModeAndActionListeners()
        return root
    }

    override fun createSouthPanel(): JComponent {
        val south = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBUI.CurrentTheme.ToolWindow.borderColor()),
                JBUI.Borders.empty(8, 4, 4, 4)
            )
        }

        val leftDanger = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(deleteDocBtn)
        }
        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(saveBtn)
            add(cancelBtn)
        }

        south.add(leftDanger, BorderLayout.WEST)
        south.add(rightActions, BorderLayout.EAST)
        return south
    }

    private fun wireModeAndActionListeners() {
        structuredModeBtn.addActionListener { switchToStructuredMode() }
        rawJsonModeBtn.addActionListener { switchToRawJsonMode() }

        addFieldBtn.addActionListener {
            if (isReadOnly) return@addActionListener
            stopCellEditing()
            val selectedRow = treeTable.selectedRow
            val selectedNode = if (selectedRow >= 0) treeTableModel.getNodeAt(selectedRow) else null
            val defaultTarget = when {
                selectedNode == null -> rootNode
                selectedNode.type.isContainer() -> selectedNode
                else -> selectedNode.parent ?: rootNode
            }
            val containerTargets = collectContainerTargets()
            val dlg = AddFieldDialog(project, containerTargets, defaultTarget)
            if (dlg.showAndGet()) {
                val targetParent = dlg.selectedParentNode()
                val addingToArray = targetParent.type == BsonFieldType.ARRAY
                val newKey = if (addingToArray) "[${targetParent.children.size}]" else dlg.fieldKey()
                val newType = dlg.fieldType()
                val newNode = EditableFieldNode(
                    key = newKey,
                    type = newType,
                    valueText = dlg.fieldValue(),
                    parent = targetParent,
                    expanded = true,
                    modified = true
                )
                targetParent.expanded = true
                targetParent.children.add(newNode)
                treeTableModel.rebuildVisibleRows()
                val idx = treeTableModel.indexOfNode(newNode)
                if (idx >= 0) {
                    treeTable.setRowSelectionInterval(idx, idx)
                    treeTable.scrollRectToVisible(treeTable.getCellRect(idx, 1, true))
                }
            }
        }

        moveUpBtn.addActionListener { moveSelectedRow(-1) }
        moveDownBtn.addActionListener { moveSelectedRow(1) }

        deleteDocBtn.addActionListener { performDeleteEntireDocument() }
        saveBtn.addActionListener { performSaveDocument() }
        cancelBtn.addActionListener { doCancelAction() }
    }

    private fun switchToRawJsonMode() {
        hideErrorBanner()
        if (activeMode == "RAW") {
            rawJsonModeBtn.isSelected = true
            structuredModeBtn.isSelected = false
            return
        }
        stopCellEditing()
        try {
            val rebuilt = rebuildBsonDocumentFromTree()
            rawJsonEditor.text = safePrettyJson(rebuilt)
            activeMode = "RAW"
            structuredModeBtn.isSelected = false
            rawJsonModeBtn.isSelected = true
            cardLayout.show(modeCardsPanel, "RAW")
        } catch (t: Throwable) {
            structuredModeBtn.isSelected = true
            rawJsonModeBtn.isSelected = false
            showErrorBanner(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun switchToStructuredMode() {
        hideErrorBanner()
        if (activeMode == "STRUCTURED") {
            structuredModeBtn.isSelected = true
            rawJsonModeBtn.isSelected = false
            return
        }
        try {
            val parsed = MongoShellNormalizer.parseBsonDocument(rawJsonEditor.text)
            loadBsonIntoTree(parsed)
            activeMode = "STRUCTURED"
            structuredModeBtn.isSelected = true
            rawJsonModeBtn.isSelected = false
            cardLayout.show(modeCardsPanel, "STRUCTURED")
        } catch (t: Throwable) {
            structuredModeBtn.isSelected = false
            rawJsonModeBtn.isSelected = true
            showErrorBanner(I18n.t("edit.dialog.syncError", t.message ?: t.javaClass.simpleName))
        }
    }

    private fun focusInitialPath() {
        val rawPath = focusFieldPath?.trim().orEmpty()
        if (rawPath.isEmpty()) return
        val segments = rawPath.split(".").filter { it.isNotEmpty() }
        var current = rootNode
        var matched: EditableFieldNode? = null
        for (segment in segments) {
            val next = current.children.firstOrNull { it.key == segment } ?: break
            current.expanded = true
            current = next
            matched = next
        }
        if (matched != null) {
            treeTableModel.rebuildVisibleRows()
            val rowIdx = treeTableModel.indexOfNode(matched)
            if (rowIdx >= 0) {
                ApplicationManager.getApplication().invokeLater {
                    if (rowIdx < treeTable.rowCount) {
                        treeTable.setRowSelectionInterval(rowIdx, rowIdx)
                        treeTable.scrollRectToVisible(treeTable.getCellRect(rowIdx, 1, true))
                    }
                }
            }
        }
    }

    private fun rebuildBsonDocumentFromTree(): BsonDocument {
        val doc = BsonDocument()
        for (child in rootNode.children) {
            val k = child.key.trim()
            if (k.isEmpty()) {
                throw IllegalArgumentException(I18n.t("edit.dialog.addFieldKeyRequired"))
            }
            doc[k] = toBsonValue(child)
        }
        return doc
    }

    private fun toBsonValue(node: EditableFieldNode): BsonValue {
        if (node.isRootId(rootNode) && originalId != null) {
            return originalId
        }
        if (!node.modified && !node.type.isContainer() && node.originalBsonValue != null) {
            return node.originalBsonValue!!
        }

        val raw = node.valueText
        return try {
            when (node.type) {
                BsonFieldType.STRING -> BsonString(raw)
                BsonFieldType.INT32 -> BsonInt32(raw.trim().toInt())
                BsonFieldType.INT64 -> BsonInt64(raw.trim().toLong())
                BsonFieldType.DOUBLE -> BsonDouble(raw.trim().toDouble())
                BsonFieldType.DECIMAL128 -> BsonDecimal128(Decimal128.parse(raw.trim()))
                BsonFieldType.BOOLEAN -> {
                    when (raw.trim().lowercase()) {
                        "true" -> BsonBoolean.TRUE
                        "false" -> BsonBoolean.FALSE
                        else -> throw IllegalArgumentException("Expected 'true' or 'false'")
                    }
                }
                BsonFieldType.DATE -> {
                    val instant = DateEditorPanel.tryParseInstant(raw.trim())
                        ?: throw IllegalArgumentException("Invalid ISO-8601 date: '$raw'")
                    BsonDateTime(instant.toEpochMilli())
                }
                BsonFieldType.OBJECT_ID -> {
                    val trimmed = raw.trim()
                    if (!ObjectId.isValid(trimmed)) {
                        throw IllegalArgumentException("Must be a 24-character hex ObjectId")
                    }
                    BsonObjectId(ObjectId(trimmed))
                }
                BsonFieldType.UUID_TYPE -> BsonBinary(UUID.fromString(raw.trim()))
                BsonFieldType.NULL -> BsonNull.VALUE
                BsonFieldType.OBJECT -> {
                    val obj = BsonDocument()
                    for (c in node.children) {
                        val ck = c.key.trim()
                        if (ck.isEmpty()) throw IllegalArgumentException(I18n.t("edit.dialog.addFieldKeyRequired"))
                        obj[ck] = toBsonValue(c)
                    }
                    obj
                }
                BsonFieldType.ARRAY -> {
                    val arr = BsonArray()
                    for (c in node.children) {
                        arr.add(toBsonValue(c))
                    }
                    arr
                }
            }
        } catch (t: Throwable) {
            throw IllegalArgumentException(
                I18n.t("edit.dialog.validationError", node.key, node.type.label, t.message ?: t.javaClass.simpleName)
            )
        }
    }

    private fun performSaveDocument() {
        if (isReadOnly) return
        hideErrorBanner()
        stopCellEditing()

        val idVal = originalId ?: run {
            showErrorBanner("Document has no _id field; replaceOne requires _id.")
            return
        }

        val rebuiltDoc = try {
            if (activeMode == "STRUCTURED") {
                rebuildBsonDocumentFromTree()
            } else {
                MongoShellNormalizer.parseBsonDocument(rawJsonEditor.text)
            }
        } catch (t: Throwable) {
            showErrorBanner(t.message ?: t.javaClass.simpleName)
            return
        }

        saveBtn.isEnabled = false
        deleteDocBtn.isEnabled = false

        object : Task.Backgroundable(project, I18n.t("result.doc.editTitle"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    connectionManager.replaceDocument(profile, database, collection, idVal, rebuiltDoc)
                    ApplicationManager.getApplication().invokeLater {
                        UiHelpers.notify(project, I18n.t("result.doc.editSuccess"))
                        onSavedInDb(rebuiltDoc)
                        allowCloseWithoutDirtyPrompt = true
                        close(OK_EXIT_CODE)
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        saveBtn.isEnabled = true
                        deleteDocBtn.isEnabled = true
                        showErrorBanner(t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        }.queue()
    }

    private fun performDeleteEntireDocument() {
        if (isReadOnly) return
        val idVal = originalId ?: return

        val confirm = Messages.showYesNoDialog(
            project,
            I18n.t("result.doc.deleteConfirm", UiHelpers.bsonIdRawText(idVal)),
            I18n.t("result.doc.deleteTitle"),
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        saveBtn.isEnabled = false
        deleteDocBtn.isEnabled = false

        object : Task.Backgroundable(project, I18n.t("result.doc.deleteTitle"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    connectionManager.deleteDocument(profile, database, collection, idVal)
                    ApplicationManager.getApplication().invokeLater {
                        UiHelpers.notify(project, I18n.t("result.doc.deleteSuccess"))
                        onDeletedInDb()
                        allowCloseWithoutDirtyPrompt = true
                        close(OK_EXIT_CODE)
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        saveBtn.isEnabled = true
                        deleteDocBtn.isEnabled = true
                        showErrorBanner(t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        }.queue()
    }

    private fun showErrorBanner(msg: String) {
        errorBanner.text = msg
        errorBanner.isVisible = true
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun hideErrorBanner() {
        errorBanner.isVisible = false
        errorBanner.text = ""
    }

    /**
     * Dynamic TableCellEditor for the Value column switching UI by BSON type:
     * - Boolean -> ComboBox(true / false) only
     * - Date -> Inline ISO text + [📅] DatePickerDialog (JSpinner + KST/UTC + Now/Today 00:00 + UTC preview)
     * - ObjectId -> Inline hex text + [New ID] generator button
     * - String / Number / UUID -> JBTextField
     */
    private inner class DynamicBsonValueCellEditor : AbstractCellEditor(), TableCellEditor {
        private var currentType: BsonFieldType = BsonFieldType.STRING
        private val boolCombo = ComboBox(arrayOf("true", "false"))
        private val plainField = JBTextField()

        private val dateField = JBTextField()
        private val datePickerBtn = JButton("📅").apply {
            margin = JBUI.insets(0, 4)
            preferredSize = Dimension(30, 24)
            addActionListener {
                val dlg = DatePickerDialog(project, dateField.text)
                if (dlg.showAndGet()) {
                    dateField.text = dlg.getSelectedUtcIso()
                    stopCellEditing()
                }
            }
        }
        private val dateComposite = JPanel(BorderLayout(2, 0)).apply {
            add(dateField, BorderLayout.CENTER)
            add(datePickerBtn, BorderLayout.EAST)
        }

        private val oidField = JBTextField()
        private val oidGenBtn = JButton(I18n.t("edit.dialog.generateObjectId")).apply {
            margin = JBUI.insets(0, 4)
            addActionListener {
                oidField.text = ObjectId().toHexString()
                stopCellEditing()
            }
        }
        private val oidComposite = JPanel(BorderLayout(2, 0)).apply {
            add(oidField, BorderLayout.CENTER)
            add(oidGenBtn, BorderLayout.EAST)
        }

        override fun getTableCellEditorComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int
        ): Component {
            val node = treeTableModel.getNodeAt(row)
            currentType = node?.type ?: BsonFieldType.STRING
            val strVal = value?.toString() ?: ""

            return when (currentType) {
                BsonFieldType.BOOLEAN -> {
                    boolCombo.selectedItem = if (strVal.equals("true", ignoreCase = true)) "true" else "false"
                    boolCombo
                }
                BsonFieldType.DATE -> {
                    dateField.text = strVal
                    dateComposite
                }
                BsonFieldType.OBJECT_ID -> {
                    oidField.text = strVal
                    oidComposite
                }
                else -> {
                    plainField.text = strVal
                    plainField
                }
            }
        }

        override fun getCellEditorValue(): Any {
            return when (currentType) {
                BsonFieldType.BOOLEAN -> boolCombo.selectedItem?.toString() ?: "false"
                BsonFieldType.DATE -> dateField.text
                BsonFieldType.OBJECT_ID -> oidField.text
                else -> plainField.text
            }
        }
    }

    private inner class DocumentTreeTableModel : AbstractTableModel() {
        private val visibleRows = mutableListOf<EditableFieldNode>()

        fun rebuildVisibleRows() {
            visibleRows.clear()
            fun visit(parent: EditableFieldNode) {
                for (child in parent.children) {
                    visibleRows.add(child)
                    if (child.type.isContainer() && child.expanded) {
                        visit(child)
                    }
                }
            }
            visit(rootNode)
            fireTableDataChanged()
        }

        fun getNodeAt(row: Int): EditableFieldNode? = visibleRows.getOrNull(row)

        fun indexOfNode(node: EditableFieldNode): Int = visibleRows.indexOfFirst { it === node }

        override fun getRowCount(): Int = visibleRows.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = when (column) {
            0 -> ""
            1 -> I18n.t("edit.dialog.col.key")
            2 -> I18n.t("edit.dialog.col.type")
            3 -> I18n.t("edit.dialog.col.value")
            else -> I18n.t("edit.dialog.col.action")
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val node = visibleRows.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> ""
                1 -> node.key
                2 -> node.type
                3 -> node.summaryValue()
                else -> ""
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            if (isReadOnly) return false
            val node = visibleRows.getOrNull(rowIndex) ?: return false
            if (node.isRootId(rootNode)) return false
            return when (columnIndex) {
                1 -> !node.isArrayElement()
                2 -> true
                3 -> !node.type.isContainer() && node.type != BsonFieldType.NULL
                else -> false
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val node = visibleRows.getOrNull(rowIndex) ?: return
            if (isReadOnly || node.isRootId(rootNode)) return

            when (columnIndex) {
                1 -> {
                    val newKey = aValue?.toString()?.trim().orEmpty()
                    if (newKey.isNotEmpty()) {
                        node.key = newKey
                        fireTableCellUpdated(rowIndex, 1)
                    }
                }
                2 -> {
                    val newType = aValue as? BsonFieldType ?: return
                    if (newType != node.type) {
                        node.type = newType
                        node.modified = true
                        when (newType) {
                            BsonFieldType.NULL -> {
                                node.children.clear()
                                node.valueText = "null"
                            }
                            BsonFieldType.BOOLEAN -> {
                                node.children.clear()
                                if (node.valueText != "true" && node.valueText != "false") node.valueText = "false"
                            }
                            BsonFieldType.INT32, BsonFieldType.INT64 -> {
                                node.children.clear()
                                if (node.valueText.toLongOrNull() == null) node.valueText = "0"
                            }
                            BsonFieldType.DOUBLE, BsonFieldType.DECIMAL128 -> {
                                node.children.clear()
                                if (node.valueText.toDoubleOrNull() == null) node.valueText = "0.0"
                            }
                            BsonFieldType.DATE -> {
                                node.children.clear()
                                if (DateEditorPanel.tryParseInstant(node.valueText) == null) {
                                    node.valueText = UiHelpers.formatIsoUtc(System.currentTimeMillis())
                                }
                            }
                            BsonFieldType.OBJECT_ID -> {
                                node.children.clear()
                                if (!ObjectId.isValid(node.valueText)) node.valueText = ObjectId().toHexString()
                            }
                            BsonFieldType.UUID_TYPE -> {
                                node.children.clear()
                                try {
                                    UUID.fromString(node.valueText.trim())
                                } catch (_: Throwable) {
                                    node.valueText = UUID.randomUUID().toString()
                                }
                            }
                            BsonFieldType.OBJECT, BsonFieldType.ARRAY -> {
                                node.valueText = ""
                                node.expanded = true
                            }
                            BsonFieldType.STRING -> {
                                node.children.clear()
                            }
                        }
                        rebuildVisibleRows()
                    }
                }
                3 -> {
                    node.valueText = aValue?.toString() ?: ""
                    node.modified = true
                    fireTableCellUpdated(rowIndex, 3)
                }
            }
        }
    }

    private enum class UnsavedChangesChoice {
        SAVE_AND_CLOSE,
        DONT_SAVE,
        KEEP_EDITING
    }

    private class UnsavedChangesDialog(
        project: Project,
        private val diffs: List<String>
    ) : DialogWrapper(project) {

        var selectedChoice: UnsavedChangesChoice = UnsavedChangesChoice.KEEP_EDITING
            private set

        init {
            title = I18n.t("edit.dialog.unsaved.title")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val summaryArea = JBTextArea(diffs.joinToString("\n")).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                border = JBUI.Borders.empty(6)
            }
            return JPanel(BorderLayout(0, 6)).apply {
                preferredSize = Dimension(520, 220)
                border = JBUI.Borders.empty(6)
                add(JBLabel(I18n.t("edit.dialog.unsaved.header")).apply {
                    font = font.deriveFont(Font.BOLD)
                }, BorderLayout.NORTH)
                add(JBScrollPane(summaryArea), BorderLayout.CENTER)
            }
        }

        override fun createSouthPanel(): JComponent {
            val saveAndCloseBtn = JButton(I18n.t("edit.dialog.unsaved.saveAndClose")).apply {
                font = font.deriveFont(Font.BOLD)
                addActionListener {
                    selectedChoice = UnsavedChangesChoice.SAVE_AND_CLOSE
                    close(OK_EXIT_CODE)
                }
            }
            val dontSaveBtn = JButton(I18n.t("edit.dialog.unsaved.dontSave")).apply {
                foreground = UiHelpers.COLOR_RED_FG
                addActionListener {
                    selectedChoice = UnsavedChangesChoice.DONT_SAVE
                    close(OK_EXIT_CODE)
                }
            }
            val keepEditingBtn = JButton(I18n.t("edit.dialog.unsaved.keepEditing")).apply {
                addActionListener {
                    selectedChoice = UnsavedChangesChoice.KEEP_EDITING
                    close(CANCEL_EXIT_CODE)
                }
            }

            return JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
                border = JBUI.Borders.emptyTop(6)
                add(keepEditingBtn)
                add(dontSaveBtn)
                add(saveAndCloseBtn)
            }
        }
    }

    /**
     * Enhanced Add Field Dialog with:
     * - Parent target path dropdown (Root + all nested Object/Array paths)
     * - Dynamic BSON-type-specific Value input (Boolean combo, DateEditorPanel, ObjectId + generator, Number validation, Null disabled)
     */
    private class AddFieldDialog(
        project: Project,
        private val containerTargets: List<Pair<String, EditableFieldNode>>,
        defaultTarget: EditableFieldNode
    ) : DialogWrapper(project) {

        private data class TargetItem(val label: String, val node: EditableFieldNode) {
            override fun toString(): String = label
        }

        private val targetCombo = ComboBox(containerTargets.map { TargetItem(it.first, it.second) }.toTypedArray()).apply {
            val defaultIdx = containerTargets.indexOfFirst { it.second === defaultTarget }.coerceAtLeast(0)
            selectedIndex = defaultIdx
        }

        private val keyField = JBTextField("")
        private val typeCombo = ComboBox(BsonFieldType.values()).apply {
            selectedItem = BsonFieldType.STRING
        }

        // Dynamic value cards
        private val valueCardLayout = CardLayout()
        private val valueCards = JPanel(valueCardLayout)

        private val plainValueField = JBTextField("")
        private val boolValueCombo = ComboBox(arrayOf("true", "false"))
        private val dateEditorPanel = DateEditorPanel()
        private val oidValueField = JBTextField(ObjectId().toHexString())
        private val oidGenBtn = JButton(I18n.t("edit.dialog.generateObjectId")).apply {
            margin = JBUI.insets(1, 6)
            addActionListener { oidValueField.text = ObjectId().toHexString() }
        }
        private val nullDisabledField = JBTextField("null").apply { isEnabled = false }
        private val containerHintField = JBTextField("{ } / [ ]").apply { isEnabled = false }

        init {
            title = I18n.t("edit.dialog.addFieldTitle")

            val oidPanel = JPanel(BorderLayout(4, 0)).apply {
                add(oidValueField, BorderLayout.CENTER)
                add(oidGenBtn, BorderLayout.EAST)
            }

            valueCards.add(plainValueField, "PLAIN")
            valueCards.add(boolValueCombo, "BOOLEAN")
            valueCards.add(dateEditorPanel, "DATE")
            valueCards.add(oidPanel, "OBJECT_ID")
            valueCards.add(nullDisabledField, "NULL")
            valueCards.add(containerHintField, "CONTAINER")

            targetCombo.addActionListener { updateKeyFieldForTarget() }
            typeCombo.addActionListener { updateValueControlForType() }

            updateKeyFieldForTarget()
            updateValueControlForType()
            init()
        }

        private fun updateKeyFieldForTarget() {
            val target = selectedParentNode()
            val isArray = target.type == BsonFieldType.ARRAY
            keyField.isEnabled = !isArray
            if (isArray) {
                keyField.text = "[${target.children.size}]"
            } else if (keyField.text.startsWith("[")) {
                keyField.text = ""
            }
        }

        private fun updateValueControlForType() {
            when (fieldType()) {
                BsonFieldType.BOOLEAN -> valueCardLayout.show(valueCards, "BOOLEAN")
                BsonFieldType.DATE -> valueCardLayout.show(valueCards, "DATE")
                BsonFieldType.OBJECT_ID -> {
                    if (!ObjectId.isValid(oidValueField.text.trim())) {
                        oidValueField.text = ObjectId().toHexString()
                    }
                    valueCardLayout.show(valueCards, "OBJECT_ID")
                }
                BsonFieldType.NULL -> valueCardLayout.show(valueCards, "NULL")
                BsonFieldType.OBJECT -> {
                    containerHintField.text = "{ } (Empty Object)"
                    valueCardLayout.show(valueCards, "CONTAINER")
                }
                BsonFieldType.ARRAY -> {
                    containerHintField.text = "[ ] (Empty Array)"
                    valueCardLayout.show(valueCards, "CONTAINER")
                }
                BsonFieldType.INT32, BsonFieldType.INT64 -> {
                    if (plainValueField.text.toLongOrNull() == null) plainValueField.text = "0"
                    valueCardLayout.show(valueCards, "PLAIN")
                }
                BsonFieldType.DOUBLE, BsonFieldType.DECIMAL128 -> {
                    if (plainValueField.text.toDoubleOrNull() == null) plainValueField.text = "0.0"
                    valueCardLayout.show(valueCards, "PLAIN")
                }
                BsonFieldType.UUID_TYPE -> {
                    try {
                        UUID.fromString(plainValueField.text.trim())
                    } catch (_: Throwable) {
                        plainValueField.text = UUID.randomUUID().toString()
                    }
                    valueCardLayout.show(valueCards, "PLAIN")
                }
                BsonFieldType.STRING -> valueCardLayout.show(valueCards, "PLAIN")
            }
            pack()
        }

        override fun createCenterPanel(): JComponent {
            return FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel(I18n.t("edit.dialog.addFieldParentPath")), targetCombo, 1, false)
                .addLabeledComponent(JBLabel(I18n.t("edit.dialog.addFieldKey")), keyField, 1, false)
                .addLabeledComponent(JBLabel(I18n.t("edit.dialog.addFieldType")), typeCombo, 1, false)
                .addLabeledComponent(JBLabel(I18n.t("edit.dialog.addFieldValue")), valueCards, 1, false)
                .panel.apply {
                    preferredSize = Dimension(560, 235)
                    border = JBUI.Borders.empty(6)
                }
        }

        override fun doValidate(): ValidationInfo? {
            if (selectedParentNode().type != BsonFieldType.ARRAY && keyField.text.trim().isEmpty()) {
                return ValidationInfo(I18n.t("edit.dialog.addFieldKeyRequired"), keyField)
            }
            return when (val t = fieldType()) {
                BsonFieldType.INT32 -> if (plainValueField.text.trim().toIntOrNull() == null) {
                    ValidationInfo("Int32 must be a valid 32-bit integer.", plainValueField)
                } else null
                BsonFieldType.INT64 -> if (plainValueField.text.trim().toLongOrNull() == null) {
                    ValidationInfo("Int64 must be a valid 64-bit integer.", plainValueField)
                } else null
                BsonFieldType.DOUBLE -> if (plainValueField.text.trim().toDoubleOrNull() == null) {
                    ValidationInfo("Double must be a valid floating-point number.", plainValueField)
                } else null
                BsonFieldType.DECIMAL128 -> try {
                    Decimal128.parse(plainValueField.text.trim()); null
                } catch (_: Throwable) {
                    ValidationInfo("Decimal128 must be a valid decimal number.", plainValueField)
                }
                BsonFieldType.OBJECT_ID -> if (!ObjectId.isValid(oidValueField.text.trim())) {
                    ValidationInfo("ObjectId must be a valid 24-character hex string.", oidValueField)
                } else null
                BsonFieldType.UUID_TYPE -> try {
                    UUID.fromString(plainValueField.text.trim()); null
                } catch (_: Throwable) {
                    ValidationInfo("UUID must be a valid UUID string.", plainValueField)
                }
                BsonFieldType.DATE -> if (!dateEditorPanel.isValidDateInput()) {
                    ValidationInfo("Invalid ISO-8601 date format.", dateEditorPanel)
                } else null
                else -> null
            }
        }

        fun selectedParentNode(): EditableFieldNode = (targetCombo.selectedItem as TargetItem).node
        fun fieldKey(): String = keyField.text.trim()
        fun fieldType(): BsonFieldType = (typeCombo.selectedItem as? BsonFieldType) ?: BsonFieldType.STRING
        fun fieldValue(): String = when (fieldType()) {
            BsonFieldType.BOOLEAN -> boolValueCombo.selectedItem?.toString() ?: "false"
            BsonFieldType.DATE -> dateEditorPanel.getUtcIsoString()
            BsonFieldType.OBJECT_ID -> oidValueField.text.trim()
            BsonFieldType.NULL -> "null"
            BsonFieldType.OBJECT, BsonFieldType.ARRAY -> ""
            else -> plainValueField.text
        }
    }
}
