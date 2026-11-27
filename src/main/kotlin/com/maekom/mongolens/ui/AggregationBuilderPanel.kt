package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.config.SavedQuery
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.crypto.CryptoConfigStore
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.MongoShellNormalizer
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonValue
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

class AggregationBuilderPanel(
    private val project: Project,
    private val projectBasePath: java.nio.file.Path?,
    private val connectionManager: MongoConnectionManager,
    private val savedQueryStore: SavedQueryStore,
    private val cryptoStore: CryptoConfigStore,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val collection: String,
    private val fieldSuggestionsSupplier: () -> List<String>,
    private val onFieldsDiscovered: (List<String>) -> Unit,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onReadOnlyChanged: () -> Unit,
    private val onConvertToShell: (String) -> Unit,
    private val diffBaselineState: DiffBaselineState? = null
) : JPanel(BorderLayout()), Disposable {

    companion object {
        val OPERATORS: Array<String> = arrayOf(
            "\$match", "\$group", "\$project", "\$sort", "\$limit",
            "\$skip", "\$unwind", "\$lookup", "\$addFields", "\$count", "\$facet"
        )

        fun defaultSkeletonFor(operator: String): String = when (operator) {
            "\$match" -> "{\n  \n}"
            "\$group" -> "{\n  \"_id\": \"\$fieldName\",\n  \"count\": { \"\$sum\": 1 }\n}"
            "\$project" -> "{\n  \"_id\": 1\n}"
            "\$sort" -> "{\n  \"_id\": -1\n}"
            "\$limit" -> "20"
            "\$skip" -> "0"
            "\$unwind" -> "\"\$arrayField\""
            "\$lookup" -> "{\n  \"from\": \"otherCollection\",\n  \"localField\": \"localId\",\n  \"foreignField\": \"_id\",\n  \"as\": \"joined\"\n}"
            "\$addFields" -> "{\n  \"newField\": \"value\"\n}"
            "\$count" -> "\"totalCount\""
            "\$facet" -> "{\n  \"branch1\": [\n    { \"\$limit\": 5 }\n  ]\n}"
            else -> "{}"
        }
    }

    private inner class StageCard(
        initialOperator: String,
        initialBody: String,
        initialEnabled: Boolean = true
    ) : JPanel(BorderLayout(4, 4)) {

        val operatorCombo = ComboBox(OPERATORS).apply {
            isEditable = true
            selectedItem = initialOperator
            preferredSize = Dimension(130, 26)
        }
        val activeToggle = JToggleButton(
            if (initialEnabled) I18n.t("agg.stage.on") else I18n.t("agg.stage.off"),
            initialEnabled
        ).apply {
            margin = JBUI.insets(1, 8)
        }
        val moveUpBtn = JButton("↑").apply {
            margin = JBUI.insets(1, 6)
            toolTipText = I18n.t("agg.stage.moveUp")
        }
        val moveDownBtn = JButton("↓").apply {
            margin = JBUI.insets(1, 6)
            toolTipText = I18n.t("agg.stage.moveDown")
        }
        val previewBtn = JButton(I18n.t("agg.stage.preview")).apply {
            margin = JBUI.insets(1, 8)
            toolTipText = I18n.t("agg.stage.previewTooltip")
        }
        val deleteBtn = JButton(I18n.t("agg.stage.delete")).apply {
            margin = JBUI.insets(1, 8)
        }

        val indexLabel = JBLabel("#1").apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyRight(4)
        }

        val bodyEditor = MongoJsonEditor(
            projectRef = project,
            initialText = initialBody,
            multiLine = true,
            fieldSuggestionsSupplier = fieldSuggestionsSupplier,
            onExecuteShortcut = { executePipeline() }
        ).apply {
            preferredSize = Dimension(400, 88)
            minimumSize = Dimension(200, 66)
        }

        private var lastOperator: String = initialOperator

        init {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(4, 6),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, true),
                    JBUI.Borders.empty(6)
                )
            )
            maximumSize = Dimension(Int.MAX_VALUE, 145)
            alignmentX = JComponent.LEFT_ALIGNMENT

            val header = JPanel(BorderLayout(4, 0))
            val leftControls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(indexLabel)
                add(operatorCombo)
                add(activeToggle)
            }
            val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(moveUpBtn)
                add(moveDownBtn)
                add(previewBtn)
                add(deleteBtn)
            }
            header.add(leftControls, BorderLayout.WEST)
            header.add(rightControls, BorderLayout.EAST)

            add(header, BorderLayout.NORTH)
            add(bodyEditor, BorderLayout.CENTER)

            updateToggleStyle()

            activeToggle.addActionListener {
                updateToggleStyle()
            }

            operatorCombo.addActionListener {
                val selectedOp = operatorCombo.selectedItem?.toString()?.trim() ?: return@addActionListener
                if (selectedOp != lastOperator) {
                    val currentText = (bodyEditor.text as String?)?.trim().orEmpty()
                    if (currentText.isEmpty() || currentText == defaultSkeletonFor(lastOperator).trim()) {
                        bodyEditor.text = defaultSkeletonFor(selectedOp)
                    }
                    lastOperator = selectedOp
                }
            }

            moveUpBtn.addActionListener { moveStage(this, -1) }
            moveDownBtn.addActionListener { moveStage(this, 1) }
            deleteBtn.addActionListener { removeStage(this) }
            previewBtn.addActionListener { previewUpToStage(this) }
        }

        fun currentOperator(): String = operatorCombo.selectedItem?.toString()?.trim().orEmpty().ifEmpty { "\$match" }

        fun isStageEnabled(): Boolean = activeToggle.isSelected

        fun updateToggleStyle() {
            if (activeToggle.isSelected) {
                activeToggle.text = I18n.t("agg.stage.on")
                activeToggle.foreground = UiHelpers.COLOR_GREEN_FG
                bodyEditor.isEnabled = true
            } else {
                activeToggle.text = I18n.t("agg.stage.off")
                activeToggle.foreground = UiHelpers.BSON_NULL_COLOR
                bodyEditor.isEnabled = false
            }
        }

        fun refreshLabels() {
            updateToggleStyle()
            moveUpBtn.toolTipText = I18n.t("agg.stage.moveUp")
            moveDownBtn.toolTipText = I18n.t("agg.stage.moveDown")
            previewBtn.text = I18n.t("agg.stage.preview")
            previewBtn.toolTipText = I18n.t("agg.stage.previewTooltip")
            deleteBtn.text = I18n.t("agg.stage.delete")
        }
    }

    private val uiModeBtn = JToggleButton(I18n.t("agg.mode.ui"), true).apply { margin = JBUI.insets(2, 6) }
    private val rawModeBtn = JToggleButton(I18n.t("agg.mode.raw"), false).apply { margin = JBUI.insets(2, 6) }
    private val addStageBtn = JButton(I18n.t("agg.addStage")).apply { margin = JBUI.insets(2, 6) }
    private val runBtn = JButton(I18n.t("agg.run")).apply {
        margin = JBUI.insets(2, 8)
        font = font.deriveFont(Font.BOLD)
        foreground = UiHelpers.COLOR_GREEN_FG
    }
    private val toShellBtn = JButton(I18n.t("agg.toShell")).apply {
        margin = JBUI.insets(2, 6)
        toolTipText = I18n.t("agg.toShellTooltip")
    }
    private val copyBtn = JButton(I18n.t("agg.copy")).apply { margin = JBUI.insets(2, 6) }
    private val savedQueriesBtn = JButton(I18n.t("find.savedQueries")).apply { margin = JBUI.insets(2, 6) }

    private val errorBanner = JBLabel("").apply {
        isOpaque = true
        background = UiHelpers.COLOR_RED_BG
        foreground = UiHelpers.COLOR_RED_FG
        border = JBUI.Borders.empty(6, 10)
        isVisible = false
    }

    private val editorCardLayout = CardLayout()
    private val editorCardsPanel = JPanel(editorCardLayout)

    private val stagesContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val emptyStagesLabel = JBLabel(I18n.t("agg.emptyStages")).apply {
        border = JBUI.Borders.empty(18)
        foreground = UiHelpers.BSON_NULL_COLOR
        alignmentX = JComponent.LEFT_ALIGNMENT
    }
    private val stageCards = mutableListOf<StageCard>()

    private val rawScriptEditor = MongoJsonEditor(
        projectRef = project,
        initialText = "[\n  {\n    \"\$match\": {}\n  }\n]",
        multiLine = true,
        fieldSuggestionsSupplier = fieldSuggestionsSupplier,
        onExecuteShortcut = { executePipeline() }
    )

    private val resultViewer = ResultViewerPanel(
        project = project,
        projectBasePath = projectBasePath,
        connectionManager = connectionManager,
        cryptoStore = cryptoStore,
        profile = profile,
        database = database,
        collection = collection,
        onRequestRefresh = { executePipeline() },
        onReadOnlyChanged = onReadOnlyChanged,
        diffBaselineState = diffBaselineState,
        isAggregationView = true
    )

    private var activeSubMode = "UI" // "UI" or "RAW"

    init {
        Disposer.register(this, resultViewer)

        // Start with one default $match stage card
        addStage("\$match", "{\n  \n}", enabled = true)

        val topPanel = JPanel(BorderLayout())
        val toolbar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor())
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                add(uiModeBtn)
                add(rawModeBtn)
                add(addStageBtn)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 3)).apply {
                add(runBtn)
                add(toShellBtn)
                add(copyBtn)
                add(savedQueriesBtn)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        val headerStack = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(errorBanner, BorderLayout.SOUTH)
        }
        topPanel.add(headerStack, BorderLayout.NORTH)

        editorCardsPanel.add(JBScrollPane(stagesContainer), "UI")
        editorCardsPanel.add(rawScriptEditor, "RAW")
        topPanel.add(editorCardsPanel, BorderLayout.CENTER)

        val splitter = JBSplitter(true, 0.48f).apply {
            firstComponent = topPanel
            secondComponent = resultViewer
        }
        add(splitter, BorderLayout.CENTER)

        wireListeners()
        I18n.addListener(this) { refreshTexts() }
    }

    private fun wireListeners() {
        uiModeBtn.addActionListener { switchToUiMode() }
        rawModeBtn.addActionListener { switchToRawMode() }
        addStageBtn.addActionListener {
            addStage("\$match", defaultSkeletonFor("\$match"), enabled = true)
            if (activeSubMode != "UI") {
                switchToUiMode()
            }
        }
        runBtn.addActionListener { executePipeline() }
        toShellBtn.addActionListener {
            try {
                val json = if (activeSubMode == "UI") serializeActiveStagesToJson() else (rawScriptEditor.text as String?)?.trim().orEmpty().ifEmpty { "[]" }
                onConvertToShell("db.$collection.aggregate($json)")
            } catch (t: Throwable) {
                showErrorBanner(t.message ?: "Error serializing pipeline")
            }
        }
        copyBtn.addActionListener {
            try {
                val json = if (activeSubMode == "UI") serializeActiveStagesToJson() else rawScriptEditor.text
                UiHelpers.copyToClipboard(MongoShellNormalizer.toMongoshAggregateCommand(collection, json))
                UiHelpers.notify(project, I18n.t("result.copied"))
            } catch (t: Throwable) {
                showErrorBanner(t.message ?: "Error serializing pipeline")
            }
        }
        savedQueriesBtn.addActionListener { showSavedQueriesMenu() }
    }

    private fun addStage(operator: String, body: String, enabled: Boolean) {
        val card = StageCard(operator, body, enabled)
        stageCards.add(card)
        rebuildStagesContainer()
    }

    private fun removeStage(card: StageCard) {
        stageCards.remove(card)
        rebuildStagesContainer()
    }

    private fun moveStage(card: StageCard, delta: Int) {
        val idx = stageCards.indexOf(card)
        val target = idx + delta
        if (idx < 0 || target !in stageCards.indices) return
        stageCards.removeAt(idx)
        stageCards.add(target, card)
        rebuildStagesContainer()
    }

    private fun rebuildStagesContainer() {
        stagesContainer.removeAll()
        if (stageCards.isEmpty()) {
            stagesContainer.add(emptyStagesLabel)
        } else {
            stageCards.forEachIndexed { idx, card ->
                card.indexLabel.text = "#${idx + 1}"
                card.moveUpBtn.isEnabled = idx > 0
                card.moveDownBtn.isEnabled = idx < stageCards.lastIndex
                stagesContainer.add(card)
            }
            stagesContainer.add(Box.createVerticalGlue())
        }
        stagesContainer.revalidate()
        stagesContainer.repaint()
    }

    private fun switchToRawMode() {
        hideErrorBanner()
        if (activeSubMode == "RAW") {
            rawModeBtn.isSelected = true
            uiModeBtn.isSelected = false
            return
        }
        try {
            val serialized = serializeActiveStagesToJson()
            rawScriptEditor.text = serialized
            activeSubMode = "RAW"
            uiModeBtn.isSelected = false
            rawModeBtn.isSelected = true
            addStageBtn.isEnabled = false
            editorCardLayout.show(editorCardsPanel, "RAW")
        } catch (t: Throwable) {
            // Keep user in UI mode if stage JSON has an error, showing clear banner
            uiModeBtn.isSelected = true
            rawModeBtn.isSelected = false
            showErrorBanner(I18n.t("agg.error.parseRaw", t.message ?: t.javaClass.simpleName))
        }
    }

    private fun switchToUiMode() {
        hideErrorBanner()
        if (activeSubMode == "UI") {
            uiModeBtn.isSelected = true
            rawModeBtn.isSelected = false
            return
        }
        val rawText = (rawScriptEditor.text as String?)?.trim().orEmpty()
        try {
            val parsedStages = if (rawText.isEmpty()) {
                emptyList()
            } else {
                val normalized = MongoShellNormalizer.normalizeToExtendedJson(rawText)
                if (!normalized.startsWith("[")) {
                    throw IllegalArgumentException(I18n.t("agg.error.notArray"))
                }
                MongoShellNormalizer.parsePipeline(rawText)
            }

            val newCards = mutableListOf<StageCard>()
            val prettySettings = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).indent(true).build()
            parsedStages.forEachIndexed { idx, stageDoc ->
                if (stageDoc.size != 1) {
                    throw IllegalArgumentException(I18n.t("agg.error.invalidStage", idx + 1))
                }
                val entry = stageDoc.entries.first()
                val op = entry.key
                val bodyStr = formatBsonValue(entry.value, prettySettings)
                newCards.add(StageCard(op, bodyStr, initialEnabled = true))
            }

            stageCards.clear()
            stageCards.addAll(newCards)
            rebuildStagesContainer()

            activeSubMode = "UI"
            uiModeBtn.isSelected = true
            rawModeBtn.isSelected = false
            addStageBtn.isEnabled = true
            editorCardLayout.show(editorCardsPanel, "UI")
        } catch (t: Throwable) {
            // Preserve raw text completely & display clear error banner
            uiModeBtn.isSelected = false
            rawModeBtn.isSelected = true
            showErrorBanner(I18n.t("agg.error.parseRaw", t.message ?: t.javaClass.simpleName))
        }
    }

    private fun formatBsonValue(value: BsonValue, settings: JsonWriterSettings): String {
        return when {
            value.isDocument -> value.asDocument().toJson(settings)
            else -> {
                val wrapper = BsonDocument("v", value).toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build())
                val colon = wrapper.indexOf(':')
                wrapper.substring(colon + 1, wrapper.lastIndexOf('}')).trim()
            }
        }
    }

    private fun buildStageBsonDocument(card: StageCard): BsonDocument {
        val op = card.currentOperator()
        val body = (card.bodyEditor.text as String?)?.trim().orEmpty().ifEmpty { "{}" }
        val combined = """{ "${MongoShellNormalizer.escapeJsonString(op)}": $body }"""
        return MongoShellNormalizer.parseBsonDocument(combined)
    }

    private fun serializeActiveStagesToJson(): String {
        val docs = stageCards
            .filter { it.isStageEnabled() }
            .map { buildStageBsonDocument(it) }

        if (docs.isEmpty()) return "[]"
        return docs.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { doc ->
            MongoShellNormalizer.formatPrettyExtended(doc)
                .lines()
                .joinToString("\n") { "  $it" }
        }
    }

    private fun previewUpToStage(targetCard: StageCard) {
        hideErrorBanner()
        val idx = stageCards.indexOf(targetCard)
        if (idx < 0) return

        val stages = try {
            val list = mutableListOf<BsonDocument>()
            for (i in 0..idx) {
                val c = stageCards[i]
                if (i == idx || c.isStageEnabled()) {
                    list.add(buildStageBsonDocument(c))
                }
            }
            list.add(BsonDocument("\$limit", BsonInt32(5)))
            list
        } catch (t: Throwable) {
            showErrorBanner(t.message ?: t.javaClass.simpleName)
            return
        }

        val banner = I18n.t("agg.previewBanner", idx + 1, targetCard.currentOperator())
        runPipelineAsync(stages, previewBanner = banner, recordHistory = false)
    }

    fun executePipeline() {
        hideErrorBanner()
        val (pipelineDocs, serializedJson) = try {
            if (activeSubMode == "UI") {
                val docs = stageCards.filter { it.isStageEnabled() }.map { buildStageBsonDocument(it) }
                val json = serializeActiveStagesToJson()
                docs to json
            } else {
                val raw = rawScriptEditor.text
                val docs = MongoShellNormalizer.parsePipeline(raw)
                docs to raw
            }
        } catch (t: Throwable) {
            showErrorBanner(t.message ?: t.javaClass.simpleName)
            return
        }

        runPipelineAsync(pipelineDocs, previewBanner = null, recordHistory = true, rawForHistory = serializedJson)
    }

    private fun runPipelineAsync(
        pipelineDocs: List<BsonDocument>,
        previewBanner: String?,
        recordHistory: Boolean,
        rawForHistory: String = "[]"
    ) {
        resultViewer.resetTableBatchDecryption()
        runBtn.isEnabled = false
        onStatusUpdate(I18n.t("header.status.querying"), false)

        object : Task.Backgroundable(project, "Running aggregation on $database.$collection...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = connectionManager.executeAggregate(profile, database, collection, pipelineDocs)
                    if (recordHistory) {
                        savedQueryStore.recordHistory(
                            SavedQuery(
                                id = UUID.randomUUID().toString(),
                                name = "Pipeline (${pipelineDocs.size} stages)",
                                profileId = profile.id,
                                database = database,
                                collection = collection,
                                mode = "AGGREGATION",
                                pipelineJson = rawForHistory,
                                isHistory = true
                            )
                        )
                    }

                    ApplicationManager.getApplication().invokeLater {
                        runBtn.isEnabled = true
                        if (result.sampledFields.isNotEmpty()) {
                            onFieldsDiscovered(result.sampledFields)
                        }
                        resultViewer.updateResults(
                            result = result,
                            skip = 0,
                            limit = result.documents.size.coerceAtLeast(1),
                            previewBanner = previewBanner
                        )
                        val statusMsg = if (connectionManager.isEffectivelyReadOnly(profile)) {
                            "${I18n.t("header.status.connected", result.elapsedMs)} · ${I18n.t("header.status.readOnly")}"
                        } else {
                            I18n.t("header.status.connected", result.elapsedMs)
                        }
                        onStatusUpdate(statusMsg, false)
                    }
                } catch (t: Throwable) {
                    val msg = t.message ?: t.javaClass.simpleName
                    ApplicationManager.getApplication().invokeLater {
                        runBtn.isEnabled = true
                        onStatusUpdate(I18n.t("header.status.error", msg.take(80)), true)
                        showErrorBanner(msg)
                    }
                }
            }
        }.queue()
    }

    private fun showSavedQueriesMenu() {
        QueryHistoryPopup.show(
            project = project,
            invoker = savedQueriesBtn,
            savedQueryStore = savedQueryStore,
            collection = collection,
            mode = "AGGREGATION",
            onSaveCurrent = {
                val json = try {
                    if (activeSubMode == "UI") serializeActiveStagesToJson() else rawScriptEditor.text
                } catch (t: Throwable) {
                    showErrorBanner(t.message ?: "Invalid pipeline")
                    return@show
                }
                val name = Messages.showInputDialog(
                    project,
                    I18n.t("find.savedQueries.promptName"),
                    I18n.t("find.savedQueries"),
                    Messages.getQuestionIcon()
                )?.trim()
                if (!name.isNullOrEmpty()) {
                    savedQueryStore.saveBookmark(
                        SavedQuery(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            profileId = profile.id,
                            database = database,
                            collection = collection,
                            mode = "AGGREGATION",
                            pipelineJson = json,
                            isHistory = false
                        )
                    )
                    UiHelpers.notify(project, I18n.t("find.savedQueries.savedToast", name))
                }
            },
            onSelectQuery = { q -> loadSavedPipeline(q) }
        )
    }

    private fun loadSavedPipeline(q: SavedQuery) {
        rawScriptEditor.text = q.pipelineJson
        if (activeSubMode == "UI") {
            activeSubMode = "RAW"
            switchToUiMode()
        }
    }

    private fun showErrorBanner(msg: String) {
        errorBanner.text = msg
        errorBanner.isVisible = true
        revalidate()
        repaint()
    }

    private fun hideErrorBanner() {
        errorBanner.isVisible = false
        errorBanner.text = ""
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            uiModeBtn.text = I18n.t("agg.mode.ui")
            rawModeBtn.text = I18n.t("agg.mode.raw")
            addStageBtn.text = I18n.t("agg.addStage")
            runBtn.text = I18n.t("agg.run")
            toShellBtn.text = I18n.t("agg.toShell")
            toShellBtn.toolTipText = I18n.t("agg.toShellTooltip")
            copyBtn.text = I18n.t("agg.copy")
            savedQueriesBtn.text = I18n.t("find.savedQueries")
            emptyStagesLabel.text = I18n.t("agg.emptyStages")
            stageCards.forEach { it.refreshLabels() }
        }
    }

    override fun dispose() {
        // Child resultViewer disposed via Disposer
    }
}
