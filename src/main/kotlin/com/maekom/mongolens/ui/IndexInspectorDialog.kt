package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.CollectionIndexInfo
import com.maekom.mongolens.mongo.IndexScanType
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonValue
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Strictly READ-ONLY Index Inspector & Query Filter Guide dialog.
 * NEVER executes createIndex / dropIndex / reIndex or any mutating DB command.
 */
class IndexInspectorDialog(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    initialIndexes: List<CollectionIndexInfo>? = null,
    private val currentFilterRaw: String? = null,
    private val currentSortRaw: String? = null,
    private val currentScanType: IndexScanType? = null,
    private val currentIndexName: String? = null,
    private val explainError: String? = null,
    private val onApplyFilterTemplate: ((String) -> Unit)? = null
) : DialogWrapper(project) {

    private var indexes: List<CollectionIndexInfo> = initialIndexes.orEmpty()

    private val tableModel = IndexTableModel()
    private val indexTable = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = JBUI.scale(24)
        tableHeader.reorderingAllowed = false
    }

    private val refreshBtn = JButton(I18n.t("index.dialog.refresh"), AllIcons.Actions.Refresh).apply {
        margin = JBUI.insets(2, 8)
    }
    private val applyTemplateBtn = JButton(I18n.t("index.dialog.applyTemplate"), AllIcons.Actions.Execute).apply {
        margin = JBUI.insets(2, 10)
        isEnabled = false
        toolTipText = I18n.t("index.dialog.applyTemplateTooltip")
    }
    private val statusLabel = JBLabel(" ").apply {
        foreground = UiHelpers.BSON_NULL_COLOR
    }

    private val diagnosisBadge = UiHelpers.createBadgeLabel("", UiHelpers.COLOR_BLUE_BG, UiHelpers.COLOR_BLUE_FG).apply {
        isVisible = false
    }
    private val diagnosisTextArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.label()
        background = JBUI.CurrentTheme.EditorTabs.background()
        border = JBUI.Borders.empty(6, 8)
    }

    private val esrHeaderLabel = JBLabel(I18n.t("index.dialog.esrTitle")).apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val esrSnippetArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        border = JBUI.Borders.empty(6, 8)
    }
    private val copyEsrBtn = JButton(I18n.t("index.dialog.copyEsr"), AllIcons.Actions.Copy).apply {
        margin = JBUI.insets(2, 8)
        toolTipText = I18n.t("index.dialog.copyEsrTooltip")
    }
    private val esrContainerPanel = JPanel(BorderLayout(0, 4))

    init {
        title = "${I18n.t("index.dialog.title")} — $database.$collection"
        init()

        indexTable.selectionModel.addListSelectionListener {
            updateApplyButtonState()
        }

        refreshBtn.addActionListener {
            loadIndexesAsync(forceRefresh = true)
        }

        applyTemplateBtn.addActionListener {
            val row = indexTable.selectedRow
            val idx = indexes.getOrNull(row) ?: return@addActionListener
            val template = buildFilterTemplateForIndex(idx)
            onApplyFilterTemplate?.invoke(template)
            close(OK_EXIT_CODE)
        }

        copyEsrBtn.addActionListener {
            val text = esrSnippetArea.text.trim()
            if (text.isNotEmpty()) {
                UiHelpers.copyToClipboard(text)
                UiHelpers.notify(project, I18n.t("index.dialog.esrCopied"))
            }
        }

        if (initialIndexes != null && initialIndexes.isNotEmpty()) {
            applyLoadedIndexes(initialIndexes)
        } else {
            SwingUtilities.invokeLater {
                if (!isDisposed) {
                    loadIndexesAsync(forceRefresh = false)
                }
            }
        }
    }

    override fun isResizable(): Boolean = true

    override fun getDimensionServiceKey(): String = "MaekomMongoLens.IndexInspectorDialog"

    override fun createActions(): Array<Action> = arrayOf(cancelAction.apply {
        putValue(Action.NAME, I18n.t("index.dialog.close"))
    })

    override fun createCenterPanel(): JComponent {
        // Top Section: Read-only Index Table
        val readOnlyBadge = UiHelpers.createBadgeLabel(
            I18n.t("index.dialog.readOnlyBadge"),
            UiHelpers.COLOR_BLUE_BG,
            UiHelpers.COLOR_BLUE_FG
        )

        val topHeader = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.emptyBottom(4)
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel("$database.$collection").apply { font = font.deriveFont(Font.BOLD) })
                add(readOnlyBadge)
                add(statusLabel)
            }
            add(left, BorderLayout.WEST)
            add(refreshBtn, BorderLayout.EAST)
        }

        val topFooter = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.emptyTop(4)
            add(JBLabel(I18n.t("index.dialog.readOnlyHint")).apply {
                foreground = UiHelpers.BSON_NULL_COLOR
                font = font.deriveFont(font.size2D - 1f)
            }, BorderLayout.WEST)
            if (onApplyFilterTemplate != null) {
                add(applyTemplateBtn, BorderLayout.EAST)
            }
        }

        val topPanel = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("index.dialog.indexesSection")),
                JBUI.Borders.empty(4, 6)
            )
            add(topHeader, BorderLayout.NORTH)
            add(JBScrollPane(indexTable).apply {
                preferredSize = Dimension(JBUI.scale(740), JBUI.scale(185))
            }, BorderLayout.CENTER)
            add(topFooter, BorderLayout.SOUTH)
        }

        // Bottom Section: Query Diagnosis & ESR Filter Guide
        val diagHeader = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(diagnosisBadge)
        }

        val esrHeader = JPanel(BorderLayout(6, 0)).apply {
            add(esrHeaderLabel, BorderLayout.WEST)
            add(copyEsrBtn, BorderLayout.EAST)
        }
        esrContainerPanel.apply {
            border = JBUI.Borders.emptyTop(6)
            add(esrHeader, BorderLayout.NORTH)
            add(JBScrollPane(esrSnippetArea).apply {
                preferredSize = Dimension(JBUI.scale(700), JBUI.scale(68))
                border = BorderFactory.createLineBorder(JBColor.border(), 1, true)
            }, BorderLayout.CENTER)
        }

        val bottomContent = JPanel(BorderLayout(0, 4)).apply {
            add(diagHeader, BorderLayout.NORTH)
            add(JBScrollPane(diagnosisTextArea).apply {
                preferredSize = Dimension(JBUI.scale(720), JBUI.scale(125))
                border = BorderFactory.createLineBorder(JBColor.border(), 1, true)
            }, BorderLayout.CENTER)
            add(esrContainerPanel, BorderLayout.SOUTH)
        }

        val bottomPanel = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("index.dialog.guideSection")),
                JBUI.Borders.empty(4, 6)
            )
            add(bottomContent, BorderLayout.CENTER)
        }

        val splitter = JBSplitter(true, 0.46f).apply {
            firstComponent = topPanel
            secondComponent = bottomPanel
        }

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(780), JBUI.scale(540))
            border = JBUI.Borders.empty(4)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun loadIndexesAsync(forceRefresh: Boolean) {
        refreshBtn.isEnabled = false
        statusLabel.text = I18n.t("index.dialog.loading")
        ApplicationManager.getApplication().executeOnPooledThread {
            var loaded: List<CollectionIndexInfo> = emptyList()
            var error: Throwable? = null
            try {
                loaded = connectionManager.listCollectionIndexInfos(
                    profile = profile,
                    database = database,
                    collection = collection,
                    forceRefresh = forceRefresh
                )
            } catch (t: Throwable) {
                error = t
            } finally {
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    refreshBtn.isEnabled = true
                    if (error != null) {
                        statusLabel.text = I18n.t("index.dialog.loadError", error.message ?: error.javaClass.simpleName)
                        statusLabel.foreground = UiHelpers.COLOR_RED_FG
                    } else {
                        statusLabel.foreground = UiHelpers.BSON_NULL_COLOR
                        applyLoadedIndexes(loaded)
                    }
                }, ModalityState.any())
            }
        }
    }

    private fun applyLoadedIndexes(loaded: List<CollectionIndexInfo>) {
        this.indexes = loaded
        tableModel.setIndexes(loaded)
        statusLabel.text = I18n.t("index.dialog.indexCount", loaded.size)
        configureTableWidths()
        if (loaded.isNotEmpty() && indexTable.selectedRow < 0) {
            val preferredRow = loaded.indexOfFirst { it.name != "_id_" }.takeIf { it >= 0 } ?: 0
            indexTable.setRowSelectionInterval(preferredRow, preferredRow)
        }
        updateApplyButtonState()
        updateDiagnosisAndGuide()
    }

    private fun configureTableWidths() {
        val cm = indexTable.columnModel
        if (cm.columnCount >= 4) {
            cm.getColumn(0).preferredWidth = JBUI.scale(190)
            cm.getColumn(1).preferredWidth = JBUI.scale(300)
            cm.getColumn(2).preferredWidth = JBUI.scale(140)
            cm.getColumn(3).preferredWidth = JBUI.scale(90)
        }
    }

    private fun updateApplyButtonState() {
        applyTemplateBtn.isEnabled = onApplyFilterTemplate != null && indexTable.selectedRow in indexes.indices
    }

    private fun buildFilterTemplateForIndex(index: CollectionIndexInfo): String {
        val entries = index.keys.keys.map { key ->
            val escaped = MongoShellNormalizer.escapeJsonString(key)
            "\"$escaped\": \"\""
        }
        return if (entries.isEmpty()) "{}" else "{ ${entries.joinToString(", ")} }"
    }

    private fun updateDiagnosisAndGuide() {
        val (eqFields, rangeFields) = parseFilterFields(currentFilterRaw)
        val sortFields = parseSortFields(currentSortRaw)
        val allFilterFields = (eqFields + rangeFields).distinct()
        val nonIdIndexes = indexes.filter { it.name != "_id_" }
        val onlyIdExists = indexes.isNotEmpty() && nonIdIndexes.isEmpty()

        val sb = StringBuilder()

        when (currentScanType) {
            IndexScanType.IXSCAN -> {
                diagnosisBadge.text = "IXSCAN · ${currentIndexName ?: "index"}"
                diagnosisBadge.background = UiHelpers.COLOR_GREEN_BG
                diagnosisBadge.foreground = UiHelpers.COLOR_GREEN_FG
                diagnosisBadge.isVisible = true
                sb.appendLine(I18n.t("index.guide.ixscanSummary", currentIndexName ?: "index"))
            }
            IndexScanType.COLLSCAN -> {
                diagnosisBadge.text = "COLLSCAN"
                diagnosisBadge.background = UiHelpers.COLOR_ORANGE_BG
                diagnosisBadge.foreground = UiHelpers.COLOR_ORANGE_FG
                diagnosisBadge.isVisible = true
                sb.appendLine(I18n.t("index.guide.collscanSummary"))
            }
            IndexScanType.UNKNOWN -> {
                diagnosisBadge.text = "UNKNOWN"
                diagnosisBadge.background = UiHelpers.COLOR_GRAY_BG
                diagnosisBadge.foreground = UiHelpers.COLOR_GRAY_FG
                diagnosisBadge.isVisible = true
                sb.appendLine(I18n.t("index.guide.unknownSummary", explainError ?: "-"))
            }
            null -> {
                diagnosisBadge.text = I18n.t("index.guide.collectionSummaryBadge", indexes.size)
                diagnosisBadge.background = UiHelpers.COLOR_BLUE_BG
                diagnosisBadge.foreground = UiHelpers.COLOR_BLUE_FG
                diagnosisBadge.isVisible = true
            }
        }

        if (allFilterFields.isNotEmpty() || sortFields.isNotEmpty()) {
            val indexedPrefixes = indexes.mapNotNull { it.firstKeyField() }.toSet()
            val allIndexedFields = indexes.flatMap { it.keys.keys }.toSet()
            val unindexedFilter = allFilterFields.filter { it !in allIndexedFields }
            val unindexedSort = sortFields.keys.filter { it !in allIndexedFields }

            if (currentScanType == IndexScanType.COLLSCAN || currentScanType == IndexScanType.UNKNOWN) {
                if (unindexedFilter.isNotEmpty()) {
                    sb.appendLine("• ${I18n.t("index.guide.unindexedFilterFields", unindexedFilter.joinToString(", "))}")
                }
                if (unindexedSort.isNotEmpty()) {
                    sb.appendLine("• ${I18n.t("index.guide.unindexedSortFields", unindexedSort.joinToString(", "))}")
                }
                if (unindexedFilter.isEmpty() && allFilterFields.isNotEmpty() && allFilterFields.none { it in indexedPrefixes }) {
                    sb.appendLine("• ${I18n.t("index.guide.prefixMismatch", allFilterFields.joinToString(", "))}")
                }
                if (allFilterFields.isEmpty() && sortFields.isEmpty()) {
                    sb.appendLine("• ${I18n.t("index.guide.emptyFilterCollscan")}")
                }
            }
        } else if (currentScanType == IndexScanType.COLLSCAN) {
            sb.appendLine("• ${I18n.t("index.guide.emptyFilterCollscan")}")
        }

        if (onlyIdExists) {
            sb.appendLine()
            sb.appendLine("ℹ ${I18n.t("index.guide.idOnlyNotice")}")
        } else if (nonIdIndexes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(I18n.t("index.guide.usablePrefixHeader"))
            for (idx in nonIdIndexes.take(5)) {
                val prefix = idx.firstKeyField() ?: continue
                val template = buildFilterTemplateForIndex(idx)
                sb.appendLine("  - ${idx.name} (prefix: $prefix) → $template")
            }
        }

        diagnosisTextArea.text = sb.toString().trim()
        diagnosisTextArea.caretPosition = 0

        // Build ESR (Equality -> Sort -> Range) read-only createIndex recommendation
        val esrSpec = buildEsrCreateIndexRecommendation(eqFields, sortFields, rangeFields)
        val shouldShowEsr = onlyIdExists || currentScanType == IndexScanType.COLLSCAN || currentScanType == IndexScanType.UNKNOWN || currentScanType == null
        esrContainerPanel.isVisible = shouldShowEsr
        esrSnippetArea.text = esrSpec
        esrSnippetArea.caretPosition = 0
    }

    private fun buildEsrCreateIndexRecommendation(
        eqFields: List<String>,
        sortFields: LinkedHashMap<String, Int>,
        rangeFields: List<String>
    ): String {
        val esrKeys = linkedMapOf<String, Int>()
        // 1. Equality fields (E)
        for (f in eqFields) {
            if (f != "_id") esrKeys.putIfAbsent(f, 1)
        }
        // 2. Sort fields (S)
        for ((f, dir) in sortFields) {
            if (f != "_id") esrKeys.putIfAbsent(f, dir)
        }
        // 3. Range fields (R)
        for (f in rangeFields) {
            if (f != "_id") esrKeys.putIfAbsent(f, 1)
        }

        val collRef = if (collection.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
            "db.$collection"
        } else {
            "db.getCollection(\"${MongoShellNormalizer.escapeJsonString(collection)}\")"
        }

        return if (esrKeys.isEmpty()) {
            "// ${I18n.t("index.guide.esrTemplateComment")}\n$collRef.createIndex({ \"<equalityField>\": 1, \"<sortField>\": -1, \"<rangeField>\": 1 })"
        } else {
            val body = esrKeys.entries.joinToString(", ") { (k, v) ->
                "\"${MongoShellNormalizer.escapeJsonString(k)}\": $v"
            }
            "// ${I18n.t("index.guide.esrRecommendedComment")}\n$collRef.createIndex({ $body })"
        }
    }

    private fun parseFilterFields(filterRaw: String?): Pair<List<String>, List<String>> {
        val trimmed = filterRaw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "{}") return emptyList<String>() to emptyList()
        val eqFields = linkedSetOf<String>()
        val rangeFields = linkedSetOf<String>()
        val rangeOps = setOf("\$gt", "\$gte", "\$lt", "\$lte", "\$ne", "\$nin", "\$regex", "\$exists", "\$not")

        try {
            val doc = MongoShellNormalizer.parseBsonDocument(trimmed)
            fun walk(d: BsonDocument) {
                for ((k, v) in d) {
                    if (k == "\$and" || k == "\$or" || k == "\$nor") {
                        if (v is BsonArray) {
                            v.filterIsInstance<BsonDocument>().forEach { walk(it) }
                        }
                    } else if (!k.startsWith("$")) {
                        if (v is BsonDocument) {
                            val keys = v.keys
                            if (keys.any { it in rangeOps }) {
                                rangeFields.add(k)
                            } else {
                                eqFields.add(k)
                            }
                        } else {
                            eqFields.add(k)
                        }
                    }
                }
            }
            walk(doc)
        } catch (_: Throwable) {
            // Ignore malformed filter JSON gracefully
        }
        return eqFields.toList() to rangeFields.toList()
    }

    private fun parseSortFields(sortRaw: String?): LinkedHashMap<String, Int> {
        val out = linkedMapOf<String, Int>()
        val trimmed = sortRaw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "{}") return out
        try {
            val doc = MongoShellNormalizer.parseBsonDocument(trimmed)
            for ((k, v) in doc) {
                if (k.startsWith("$")) continue
                val dir = when {
                    v.isInt32 -> if (v.asInt32().value < 0) -1 else 1
                    v.isInt64 -> if (v.asInt64().value < 0) -1 else 1
                    v.isDouble -> if (v.asDouble().value < 0) -1 else 1
                    else -> 1
                }
                out[k] = dir
            }
        } catch (_: Throwable) {
            // Ignore malformed sort JSON gracefully
        }
        return out
    }

    private fun formatSize(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "-"
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private inner class IndexTableModel : AbstractTableModel() {
        private var rows: List<CollectionIndexInfo> = emptyList()

        fun setIndexes(list: List<CollectionIndexInfo>) {
            rows = list
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = when (column) {
            0 -> I18n.t("index.dialog.colName")
            1 -> I18n.t("index.dialog.colKeys")
            2 -> I18n.t("index.dialog.colAttributes")
            3 -> I18n.t("index.dialog.colSize")
            else -> ""
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val idx = rows.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> idx.name
                1 -> idx.keyDisplay
                2 -> idx.attributesDisplay()
                3 -> formatSize(idx.sizeBytes)
                else -> ""
            }
        }
    }
}
