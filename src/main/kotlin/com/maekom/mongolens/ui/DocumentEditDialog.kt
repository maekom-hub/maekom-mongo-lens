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
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

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
    private val rootNode = EditableFieldNode("root", BsonFieldType.OBJECT, "", parent = null, expanded = true)
    private val treeTableModel = DocumentTreeTableModel()

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

    private val treeTable = JBTable(treeTableModel).apply {
        rowHeight = 26
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private val addFieldBtn = JButton(I18n.t("edit.dialog.addField")).apply {
        margin = JBUI.insets(2, 8)
        isEnabled = !isReadOnly
    }

    // Normalize line separators strictly to '\n' so IntelliJ DocumentImpl never throws AssertionError
    private val rawJsonEditor = MongoJsonEditor(
        projectRef = project,
        initialText = safePrettyJson(originalDoc),
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
        focusInitialPath()
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
        for ((k, v) in doc) {
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
        cm.getColumn(0).preferredWidth = 220
        cm.getColumn(1).preferredWidth = 105
        cm.getColumn(1).maxWidth = 135
        cm.getColumn(2).preferredWidth = 340
        cm.getColumn(3).preferredWidth = 34
        cm.getColumn(3).maxWidth = 38
        cm.getColumn(3).minWidth = 32

        cm.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
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

        val typeCombo = JComboBox(BsonFieldType.values())
        cm.getColumn(1).cellEditor = DefaultCellEditor(typeCombo)
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

        cm.getColumn(3).cellRenderer = object : DefaultTableCellRenderer() {
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

        treeTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val row = treeTable.rowAtPoint(e.point)
                val col = treeTable.columnAtPoint(e.point)
                if (row < 0 || col < 0) return
                val node = treeTableModel.getNodeAt(row) ?: return

                if (col == 3 && !isReadOnly && !node.isRootId(rootNode)) {
                    stopCellEditing()
                    deleteNode(node)
                    return
                }

                if (col == 0 && node.type.isContainer()) {
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
        })
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

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 6)).apply {
            preferredSize = Dimension(780, 540)
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

        val structuredPanel = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(treeTable), BorderLayout.CENTER)
            val footerBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(addFieldBtn)
            }
            add(footerBar, BorderLayout.SOUTH)
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
            val targetParent = when {
                selectedNode == null -> rootNode
                selectedNode.type.isContainer() -> selectedNode
                else -> selectedNode.parent ?: rootNode
            }
            val addingToArray = targetParent.type == BsonFieldType.ARRAY
            val dlg = AddFieldDialog(project, addingToArray)
            if (dlg.showAndGet()) {
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
                    treeTable.scrollRectToVisible(treeTable.getCellRect(idx, 0, true))
                }
            }
        }

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
                        treeTable.scrollRectToVisible(treeTable.getCellRect(rowIdx, 0, true))
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
        // Always preserve original _id with its exact BSON type (ObjectId, String, Long, Int, UUID, etc.)
        if (node.isRootId(rootNode) && originalId != null) {
            return originalId
        }
        // Preserve exotic unmodified primitive types (e.g. Binary, Regex, Timestamp) losslessly
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
                BsonFieldType.DATE -> BsonDateTime(parseEpochMillis(raw.trim()))
                BsonFieldType.OBJECT_ID -> BsonObjectId(ObjectId(raw.trim()))
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

    private fun parseEpochMillis(raw: String): Long {
        raw.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: Throwable) {
            LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
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

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = when (column) {
            0 -> I18n.t("edit.dialog.col.key")
            1 -> I18n.t("edit.dialog.col.type")
            2 -> I18n.t("edit.dialog.col.value")
            else -> I18n.t("edit.dialog.col.action")
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val node = visibleRows.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> node.key
                1 -> node.type
                2 -> node.summaryValue()
                else -> ""
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            if (isReadOnly) return false
            val node = visibleRows.getOrNull(rowIndex) ?: return false
            if (node.isRootId(rootNode)) return false
            return when (columnIndex) {
                0 -> !node.isArrayElement()
                1 -> true
                2 -> !node.type.isContainer() && node.type != BsonFieldType.NULL
                else -> false
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val node = visibleRows.getOrNull(rowIndex) ?: return
            if (isReadOnly || node.isRootId(rootNode)) return

            when (columnIndex) {
                0 -> {
                    val newKey = aValue?.toString()?.trim().orEmpty()
                    if (newKey.isNotEmpty()) {
                        node.key = newKey
                        fireTableCellUpdated(rowIndex, 0)
                    }
                }
                1 -> {
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
                                if (node.valueText.isBlank()) node.valueText = UiHelpers.formatIsoUtc(System.currentTimeMillis())
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
                2 -> {
                    node.valueText = aValue?.toString() ?: ""
                    node.modified = true
                    fireTableCellUpdated(rowIndex, 2)
                }
            }
        }
    }

    private class AddFieldDialog(
        project: Project,
        private val addingToArray: Boolean
    ) : DialogWrapper(project) {
        private val keyField = JBTextField(if (addingToArray) "(auto index)" else "").apply {
            isEnabled = !addingToArray
        }
        private val typeCombo = ComboBox(BsonFieldType.values()).apply {
            selectedItem = BsonFieldType.STRING
        }
        private val valueField = JBTextField("")

        init {
            title = I18n.t("edit.dialog.addFieldTitle")
            typeCombo.addActionListener {
                val t = typeCombo.selectedItem as? BsonFieldType ?: return@addActionListener
                valueField.isEnabled = !t.isContainer() && t != BsonFieldType.NULL
                when (t) {
                    BsonFieldType.NULL -> valueField.text = "null"
                    BsonFieldType.BOOLEAN -> if (valueField.text.isBlank()) valueField.text = "true"
                    BsonFieldType.INT32, BsonFieldType.INT64 -> if (valueField.text.isBlank()) valueField.text = "0"
                    BsonFieldType.DOUBLE, BsonFieldType.DECIMAL128 -> if (valueField.text.isBlank()) valueField.text = "0.0"
                    BsonFieldType.DATE -> if (valueField.text.isBlank()) valueField.text = UiHelpers.formatIsoUtc(System.currentTimeMillis())
                    BsonFieldType.OBJECT_ID -> if (valueField.text.isBlank()) valueField.text = ObjectId().toHexString()
                    BsonFieldType.UUID_TYPE -> if (valueField.text.isBlank()) valueField.text = UUID.randomUUID().toString()
                    BsonFieldType.OBJECT, BsonFieldType.ARRAY -> valueField.text = ""
                    BsonFieldType.STRING -> Unit
                }
            }
            init()
        }

        override fun createCenterPanel(): JComponent {
            return FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel(I18n.t("edit.dialog.addFieldKey")), keyField, 1, false)
                .addLabeledComponent(JBLabel(I18n.t("edit.dialog.addFieldType")), typeCombo, 1, false)
                .addLabeledComponent(JBLabel(I18n.t("edit.dialog.addFieldValue")), valueField, 1, false)
                .panel.apply {
                    preferredSize = Dimension(380, 125)
                    border = JBUI.Borders.empty(4)
                }
        }

        override fun doValidate(): ValidationInfo? {
            if (!addingToArray && keyField.text.trim().isEmpty()) {
                return ValidationInfo(I18n.t("edit.dialog.addFieldKeyRequired"), keyField)
            }
            return null
        }

        fun fieldKey(): String = keyField.text.trim()
        fun fieldType(): BsonFieldType = (typeCombo.selectedItem as? BsonFieldType) ?: BsonFieldType.STRING
        fun fieldValue(): String = valueField.text
    }
}
