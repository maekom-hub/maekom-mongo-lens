package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScreenUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.JoinCondition
import com.maekom.mongolens.mongo.JoinConditionOperator
import com.maekom.mongolens.mongo.JoinConditionTarget
import com.maekom.mongolens.mongo.JoinKeyPair
import com.maekom.mongolens.mongo.JoinMatchStrategy
import com.maekom.mongolens.mongo.JoinSourceRef
import com.maekom.mongolens.mongo.JoinSpec
import com.maekom.mongolens.mongo.JoinTargetSpec
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.ReferenceJumpHelper
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

class JoinViewDialog(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val sourceCollection: String,
    initialLocalField: String?,
    sourceTopFields: List<String>,
    private val sourceFieldCategories: Map<String, String>,
    private val sourceJoinKeyCategories: Map<String, String> = sourceFieldCategories,
    allCollectionsInDb: List<String>,
    private val existingJoinSpec: JoinSpec? = null
) : DialogWrapper(project) {

    private data class JoinLimitOption(val value: Int?) {
        override fun toString(): String = value?.toString() ?: I18n.t("join.limit.unlimited")
    }

    private data class SourceKeyOption(
        val sourceRef: JoinSourceRef,
        val collectionName: String,
        val fieldName: String
    ) {
        override fun toString(): String {
            return when (sourceRef) {
                is JoinSourceRef.Base -> "${I18n.t("join.dialog.sourceBasePrefix", collectionName)}.$fieldName"
                is JoinSourceRef.PreviousTarget -> "${I18n.t("join.dialog.sourcePrevPrefix", sourceRef.targetIndex + 1, collectionName)}.$fieldName"
            }
        }
    }

    private class MutableTargetDraft(
        var targetCollection: String,
        var keyPairs: MutableList<JoinKeyPair>,
        var matchStrategy: JoinMatchStrategy = JoinMatchStrategy.LATEST_ONE,
        var allColumns: MutableList<String> = mutableListOf(),
        var selectedColumns: LinkedHashSet<String> = linkedSetOf(),
        var fieldCategories: MutableMap<String, String> = linkedMapOf(),
        var joinKeyFields: MutableList<String> = mutableListOf(),
        var joinFieldCategories: MutableMap<String, String> = linkedMapOf(),
        var conditions: MutableList<JoinCondition> = mutableListOf(),
        var excludeUnmatched: Boolean = true,
        var schemaLoaded: Boolean = false,
        var isEmptyCollection: Boolean = false,
        var lastError: String? = null,
        var preserveExistingSelectionOnFirstLoad: Boolean = false
    )

    private val isEditMode: Boolean = existingJoinSpec != null

    private val leftFields: List<String> = buildList {
        sourceTopFields.forEach { add(it.substringBefore('.')) }
        existingJoinSpec?.let { spec ->
            addAll(spec.allLeftFields)
            addAll(spec.selectedLeftFields)
        }
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("_id") }

    // Includes top-level fields PLUS nested Object & Array-of-Objects dot paths (e.g. `items.product_id`)
    private val leftJoinKeyFields: List<String> = buildList {
        addAll(sourceJoinKeyCategories.keys)
        addAll(leftFields)
        initialLocalField?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
        existingJoinSpec?.let { spec ->
            spec.baseConditions.forEach { add(it.field) }
            spec.targets.forEach { t ->
                t.effectiveKeyPairs().filter { it.sourceRef is JoinSourceRef.Base }.forEach { add(it.localField) }
            }
        }
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("_id") }

    private val selectedLeftSet: LinkedHashSet<String> = LinkedHashSet(
        existingJoinSpec?.selectedLeftFields ?: leftFields
    )

    private val candidateCollections: List<String> = run {
        val basePool = buildList {
            addAll(allCollectionsInDb)
            add(sourceCollection)
            existingJoinSpec?.targets?.forEach { add(it.targetCollection) }
        }.filter { it.isNotBlank() }.distinct()
        val ranked = ReferenceJumpHelper.rankCollections(
            database = database,
            currentCollection = sourceCollection,
            fieldPath = existingJoinSpec?.targets?.firstOrNull()?.effectiveKeyPairs()?.firstOrNull()?.localField ?: initialLocalField,
            allCollections = basePool
        ).map { it.name }
        ranked.filter { it != sourceCollection } + ranked.filter { it == sourceCollection }
    }

    private val baseConditionDrafts: MutableList<JoinCondition> =
        existingJoinSpec?.baseConditions?.toMutableList() ?: mutableListOf()

    private val targetDrafts: MutableList<MutableTargetDraft> = mutableListOf()
    private var activeTargetIdx: Int = 0

    // Per-collection schema cache so switching join target tabs is instant
    private val schemaCache = linkedMapOf<String, MongoConnectionManager.SampledTopLevelSchema>()
    private val schemaRequestSeq = AtomicLong(0L)
    private var suppressEditorEvents = false

    // Top bar: [ ① <target1> (×) ] [ ② <target2> (×) ] [+]  (inline FlowLayout inside horizontal scroll pane)
    private val targetTabsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
        isOpaque = false
    }
    private val targetTabsScrollPane = JBScrollPane(
        targetTabsPanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    ).apply {
        border = JBUI.Borders.empty()
        viewportBorder = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(38))
        minimumSize = Dimension(JBUI.scale(260), JBUI.scale(38))
    }

    private val addJoinBtn = object : JButton() {
        private var hovered = false
        private var pressed = false

        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = I18n.t("join.dialog.addJoinTargetTooltip")
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(26))
            minimumSize = Dimension(JBUI.scale(28), JBUI.scale(26))
            maximumSize = Dimension(JBUI.scale(28), JBUI.scale(26))
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    hovered = true
                    repaint()
                }
                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    hovered = false
                    pressed = false
                    repaint()
                }
                override fun mousePressed(e: java.awt.event.MouseEvent?) {
                    pressed = true
                    repaint()
                }
                override fun mouseReleased(e: java.awt.event.MouseEvent?) {
                    pressed = false
                    repaint()
                }
            })
        }

        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = when {
                    pressed -> JBColor(java.awt.Color(0x1B5E20), java.awt.Color(0x215526))
                    hovered -> JBColor(java.awt.Color(0x388E3C), java.awt.Color(0x357A3B))
                    else -> JBColor(java.awt.Color(0x2E7D32), java.awt.Color(0x2B6A30))
                }
                val arc = JBUI.scale(6)
                g2.fillRoundRect(0, 0, width, height, arc, arc)

                // Draw crisp white '+' vector
                g2.color = java.awt.Color.WHITE
                g2.stroke = java.awt.BasicStroke(
                    JBUI.scale(2).toFloat().coerceAtLeast(1.8f),
                    java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND
                )
                val cx = width / 2
                val cy = height / 2
                val arm = JBUI.scale(5)
                g2.drawLine(cx - arm, cy, cx + arm, cy)
                g2.drawLine(cx, cy - arm, cx, cy + arm)
            } finally {
                g2.dispose()
            }
        }
    }
    private val multiUnwindWarningLabel = UiHelpers.createBadgeLabel(
        "⚠ ${I18n.t("join.dialog.multiUnwindWarning")}",
        UiHelpers.COLOR_ORANGE_BG,
        UiHelpers.COLOR_ORANGE_FG
    ).apply {
        isVisible = false
    }

    // Active target controls
    private val targetCollectionCombo = ComboBox(candidateCollections.toTypedArray())

    // Dynamic Composite Join Key Mapping Rows (1..N AND key pairs)
    private val addJoinKeyBtn = JButton(I18n.t("join.dialog.addKeyPair"), AllIcons.General.Add).apply {
        margin = JBUI.insets(1, 8)
    }
    private val autoMatchCommonKeysBtn = JButton(I18n.t("join.dialog.autoMatchCommonKeys"), AllIcons.Actions.Refresh).apply {
        margin = JBUI.insets(1, 8)
        toolTipText = I18n.t("join.dialog.autoMatchCommonKeysTooltip")
    }
    private val joinKeysListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(2, 4)
    }
    private var currentKeysScrollHeightPx: Int = JBUI.scale(48)

    private val joinKeysScrollPane = JBScrollPane(joinKeysListPanel).apply {
        preferredSize = Dimension(JBUI.scale(720), currentKeysScrollHeightPx)
        minimumSize = Dimension(JBUI.scale(480), currentKeysScrollHeightPx)
    }
    private val joinKeyRows = mutableListOf<JoinKeyMappingRow>()

    private val statusOrMismatchLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD)
    }

    // Composite Join ($expr) BSON type & performance guidance banner (visible when >= 2 join keys)
    private val compositeKeyInfoLabel = JBLabel(
        "<html>${I18n.t("join.dialog.compositeInfoBanner")}</html>",
        AllIcons.General.BalloonInformation,
        JBLabel.LEFT
    ).apply {
        isOpaque = true
        background = UiHelpers.COLOR_BLUE_BG
        foreground = UiHelpers.COLOR_BLUE_FG
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1, true),
            JBUI.Borders.empty(4, 8)
        )
        isVisible = false
    }

    private val latestOneRadio = JBRadioButton(I18n.t("join.dialog.strategyLatestOne"), true)
    private val unwindAllRadio = JBRadioButton(I18n.t("join.dialog.strategyUnwindAll"), false)
    private val excludeUnmatchedCheckBox = JBCheckBox(I18n.t("join.dialog.innerJoinCheck"), true)

    private val joinLimitChoices: Array<JoinLimitOption> =
        JoinSpec.JOIN_LIMIT_OPTIONS.map { JoinLimitOption(it) }.toTypedArray()
    private val joinLimitCombo = ComboBox(joinLimitChoices).apply {
        val initLimit = if (existingJoinSpec != null) existingJoinSpec.limitOption else 50
        selectedItem = joinLimitChoices.firstOrNull { it.value == initLimit }
            ?: joinLimitChoices.firstOrNull { it.value == 50 }
            ?: joinLimitChoices.first()
        preferredSize = Dimension(JBUI.scale(148), JBUI.scale(26))
    }

    // WHERE AND conditions builder
    private val addConditionBtn = JButton(I18n.t("join.dialog.addCondition"), AllIcons.General.Add).apply {
        margin = JBUI.insets(1, 6)
    }
    private val conditionsListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(2, 4)
    }
    private val conditionsScrollPane = JBScrollPane(conditionsListPanel).apply {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(62))
        minimumSize = Dimension(JBUI.scale(400), JBUI.scale(48))
    }
    private val conditionRows = mutableListOf<ConditionRow>()

    // Bottom multi-column checkbox container (horizontal scroll when 4+ panels)
    private val columnsRowPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(4)
    }
    private val columnsScrollPane = JBScrollPane(
        columnsRowPanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    ).apply {
        border = JBUI.Borders.empty()
        preferredSize = Dimension(JBUI.scale(760), JBUI.scale(200))
        minimumSize = Dimension(JBUI.scale(400), JBUI.scale(140))
    }

    private val rootContainerPanel = JPanel(BorderLayout(0, 6))
    private var mainSplitter: JBSplitter? = null

    /**
     * Dynamic single join-key mapping row:
     *   [Source Key (Editable ComboBox)]  =  [Target Field (Editable ComboBox)]  [Remove]
     */
    private inner class JoinKeyMappingRow(initialPair: JoinKeyPair) : JPanel(GridBagLayout()) {
        private val sourceModel = DefaultComboBoxModel<Any>()
        val sourceKeyCombo = ComboBox<Any>(sourceModel).apply {
            isEditable = true
            minimumSize = Dimension(JBUI.scale(210), JBUI.scale(28))
            preferredSize = Dimension(JBUI.scale(290), JBUI.scale(28))
        }

        private val equalsLabel = JBLabel("=").apply {
            font = font.deriveFont(Font.BOLD)
            horizontalAlignment = JBLabel.CENTER
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(28))
        }

        private val foreignModel = DefaultComboBoxModel<String>()
        val foreignFieldCombo = ComboBox<String>(foreignModel).apply {
            isEditable = true
            minimumSize = Dimension(JBUI.scale(210), JBUI.scale(28))
            preferredSize = Dimension(JBUI.scale(290), JBUI.scale(28))
        }

        val removeBtn = JButton(AllIcons.General.Remove).apply {
            margin = JBUI.insets(1, 5)
            toolTipText = I18n.t("join.dialog.removeKeyPairTooltip")
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
        }

        private var rowSuppressEvents = false
        private var currentSourceOptions: List<SourceKeyOption> = emptyList()

        private val sourceSearchHelper = SearchableFieldComboBox(
            comboBox = sourceKeyCombo,
            model = sourceModel,
            primaryKey = { item -> (item as? SourceKeyOption)?.fieldName ?: item.toString() },
            displayLabel = { item -> item.toString() },
            onTextOrSelectionChanged = {
                if (!suppressEditorEvents && !rowSuppressEvents) {
                    val draft = activeDraft()
                    val (_, localField) = currentSourceRefAndField()
                    if (draft.joinKeyFields.isNotEmpty() && !draft.preserveExistingSelectionOnFirstLoad) {
                        val autoForeign = suggestForeignFieldForLocal(localField, draft)
                        if (autoForeign != null && currentForeignField().isEmpty()) {
                            setForeignText(autoForeign)
                        }
                    }
                    syncActiveRowsToDraftAndWarnings()
                }
            }
        )

        private val foreignSearchHelper = SearchableFieldComboBox(
            comboBox = foreignFieldCombo,
            model = foreignModel,
            primaryKey = { it },
            displayLabel = { it },
            onTextOrSelectionChanged = {
                if (!suppressEditorEvents && !rowSuppressEvents) {
                    syncActiveRowsToDraftAndWarnings()
                }
            }
        )

        init {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)

            val gbc = GridBagConstraints().apply {
                gridy = 0
                insets = JBUI.insets(0, 2)
                fill = GridBagConstraints.HORIZONTAL
            }
            gbc.gridx = 0; gbc.weightx = 0.5; add(sourceKeyCombo, gbc)
            gbc.gridx = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; add(equalsLabel, gbc)
            gbc.gridx = 2; gbc.weightx = 0.5; gbc.fill = GridBagConstraints.HORIZONTAL; add(foreignFieldCombo, gbc)
            gbc.gridx = 3; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; add(removeBtn, gbc)

            refreshSourceOptions(activeTargetIdx, initialPair.sourceRef, initialPair.localField)
            refreshForeignOptions(activeDraft(), initialPair.foreignField)

            removeBtn.addActionListener {
                removeJoinKeyRow(this)
            }
        }

        fun setControlsEnabled(enabled: Boolean) {
            foreignFieldCombo.isEnabled = enabled
        }

        fun setRemovable(removable: Boolean) {
            removeBtn.isEnabled = removable
        }

        fun refreshSourceOptions(targetIdx: Int, preserveRef: JoinSourceRef? = null, preserveField: String? = null) {
            val (curRef, curField) = if (preserveRef != null && preserveField != null) {
                preserveRef to preserveField
            } else {
                currentSourceRefAndField()
            }
            val options = buildSourceKeyOptions(targetIdx)
            currentSourceOptions = options

            rowSuppressEvents = true
            try {
                val matched = options.firstOrNull {
                    it.sourceRef == curRef && it.fieldName == curField
                } ?: options.firstOrNull {
                    it.sourceRef is JoinSourceRef.Base && it.fieldName == curField
                }
                sourceSearchHelper.updateMasterItems(
                    newItems = options,
                    selectedItemOverride = matched ?: curField,
                    preserveCurrentText = false
                )
            } finally {
                rowSuppressEvents = false
            }
        }

        fun refreshForeignOptions(draft: MutableTargetDraft, explicitForeign: String? = null) {
            val savedText = explicitForeign ?: currentForeignField()
            val pool = buildList {
                addAll(draft.joinKeyFields)
                addAll(draft.allColumns)
                if (savedText.isNotBlank()) add(savedText)
            }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

            rowSuppressEvents = true
            try {
                foreignSearchHelper.updateMasterItems(
                    newItems = pool,
                    selectedItemOverride = savedText,
                    preserveCurrentText = false
                )
            } finally {
                rowSuppressEvents = false
            }
        }

        fun setForeignText(text: String) {
            rowSuppressEvents = true
            try {
                foreignSearchHelper.setTextProgrammatically(text)
            } finally {
                rowSuppressEvents = false
            }
        }

        fun currentSourceRefAndField(): Pair<JoinSourceRef, String> {
            val editorText = (sourceKeyCombo.editor?.editorComponent as? JTextComponent)?.text?.trim()
            val selected = sourceKeyCombo.selectedItem
            if (selected is SourceKeyOption && (editorText == null || editorText == selected.toString())) {
                return selected.sourceRef to selected.fieldName
            }
            val raw = editorText ?: selected?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) return JoinSourceRef.Base to ""

            // Exact match against any dropdown option display label
            currentSourceOptions.firstOrNull { it.toString() == raw }?.let {
                return it.sourceRef to it.fieldName
            }
            // Strip optional `Base(coll).` prefix if user manually edited the field suffix
            val basePrefix = "${I18n.t("join.dialog.sourceBasePrefix", sourceCollection)}."
            if (raw.startsWith(basePrefix)) {
                return JoinSourceRef.Base to raw.removePrefix(basePrefix).trim()
            }
            // Strip optional `JoinN(coll).` prefix if user edited a previous target's field suffix
            for (prevIdx in 0 until activeTargetIdx) {
                val prevColl = targetDrafts.getOrNull(prevIdx)?.targetCollection ?: continue
                val prevPrefix = "${I18n.t("join.dialog.sourcePrevPrefix", prevIdx + 1, prevColl)}."
                if (raw.startsWith(prevPrefix)) {
                    return JoinSourceRef.PreviousTarget(prevIdx) to raw.removePrefix(prevPrefix).trim()
                }
            }
            return JoinSourceRef.Base to com.maekom.mongolens.mongo.CompassPipelineSerializer.stripUiCollectionPrefix(raw)
        }

        fun currentForeignField(): String {
            val editorComp = foreignFieldCombo.editor?.editorComponent as? JTextComponent
            if (editorComp != null) {
                return (editorComp.text as String?)?.trim().orEmpty()
            }
            return (foreignFieldCombo.editor?.item?.toString() ?: foreignFieldCombo.selectedItem?.toString())?.trim().orEmpty()
        }

        fun toKeyPair(): JoinKeyPair {
            val (ref, local) = currentSourceRefAndField()
            return JoinKeyPair(
                sourceRef = ref,
                localField = local,
                foreignField = currentForeignField()
            )
        }
    }

    private inner class ConditionRow(initial: JoinCondition? = null) : JPanel(GridBagLayout()) {
        val targetCombo = ComboBox(arrayOf(JoinConditionTarget.LEFT, JoinConditionTarget.RIGHT)).apply {
            selectedItem = initial?.target ?: JoinConditionTarget.RIGHT
            preferredSize = Dimension(JBUI.scale(150), JBUI.scale(26))
            renderer = object : SimpleListCellRenderer<JoinConditionTarget>() {
                override fun customize(
                    list: JList<out JoinConditionTarget>,
                    value: JoinConditionTarget?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    val targetColl = currentTargetCollectionName().ifEmpty { "-" }
                    text = when (value) {
                        JoinConditionTarget.LEFT -> I18n.t("join.dialog.condTargetLeft", sourceCollection)
                        JoinConditionTarget.RIGHT -> I18n.t("join.dialog.condTargetRight", targetColl)
                        null -> ""
                    }
                }
            }
        }

        private val fieldModel = DefaultComboBoxModel<String>()
        val fieldCombo = ComboBox(fieldModel).apply {
            isEditable = true
            preferredSize = Dimension(JBUI.scale(155), JBUI.scale(26))
        }
        private val fieldSearchHelper = SearchableFieldComboBox(
            comboBox = fieldCombo,
            model = fieldModel,
            primaryKey = { it },
            displayLabel = { it }
        )

        val operatorCombo = ComboBox(JoinConditionOperator.values()).apply {
            selectedItem = initial?.operator ?: JoinConditionOperator.EQ
            preferredSize = Dimension(JBUI.scale(148), JBUI.scale(26))
        }

        val valueField = JBTextField(initial?.rawValue.orEmpty()).apply {
            preferredSize = Dimension(JBUI.scale(150), JBUI.scale(26))
        }

        private val removeBtn = JButton("×").apply {
            margin = JBUI.insets(1, 6)
            toolTipText = I18n.t("join.dialog.condDeleteTooltip")
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(26))
        }

        init {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)

            val gbc = GridBagConstraints().apply {
                gridy = 0
                insets = JBUI.insets(0, 2)
                fill = GridBagConstraints.HORIZONTAL
            }
            gbc.gridx = 0; gbc.weightx = 0.0; add(targetCombo, gbc)
            gbc.gridx = 1; gbc.weightx = 0.35; add(fieldCombo, gbc)
            gbc.gridx = 2; gbc.weightx = 0.0; add(operatorCombo, gbc)
            gbc.gridx = 3; gbc.weightx = 0.65; add(valueField, gbc)
            gbc.gridx = 4; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; add(removeBtn, gbc)

            refreshFieldChoices(preserveCurrentText = false, explicitText = initial?.field)
            updateValueFieldState()

            targetCombo.addActionListener {
                refreshFieldChoices(preserveCurrentText = false, explicitText = null)
            }
            operatorCombo.addActionListener {
                updateValueFieldState()
            }
            removeBtn.addActionListener {
                removeConditionRow(this)
            }
        }

        private fun updateValueFieldState() {
            val requiresVal = currentOperator().requiresValue
            valueField.isEnabled = requiresVal
            if (!requiresVal) {
                valueField.text = ""
                valueField.emptyText.text = I18n.t("join.dialog.condValueDisabled")
            } else {
                valueField.emptyText.text = I18n.t("join.dialog.condValueHint")
            }
        }

        fun currentTarget(): JoinConditionTarget =
            (targetCombo.selectedItem as? JoinConditionTarget) ?: JoinConditionTarget.RIGHT

        fun currentField(): String {
            val editorComp = fieldCombo.editor?.editorComponent as? JTextComponent
            if (editorComp != null) {
                return (editorComp.text as String?)?.trim().orEmpty()
            }
            return (fieldCombo.editor?.item?.toString() ?: fieldCombo.selectedItem?.toString())?.trim().orEmpty()
        }

        fun currentOperator(): JoinConditionOperator =
            (operatorCombo.selectedItem as? JoinConditionOperator) ?: JoinConditionOperator.EQ

        fun currentRawValue(): String = if (currentOperator().requiresValue) {
            (valueField.text as String?)?.trim().orEmpty()
        } else {
            ""
        }

        fun refreshTargetDisplay() {
            targetCombo.repaint()
        }

        fun refreshFieldChoices(preserveCurrentText: Boolean, explicitText: String? = null) {
            val savedText = explicitText ?: if (preserveCurrentText) currentField() else null
            val activeRightFields = activeDraft().let { d ->
                (d.joinKeyFields + d.allColumns).distinct()
            }
            val choices = when (currentTarget()) {
                JoinConditionTarget.LEFT -> leftJoinKeyFields
                JoinConditionTarget.RIGHT -> activeRightFields
            }
            val mergedChoices = buildList {
                addAll(choices)
                if (!savedText.isNullOrBlank() && savedText !in choices) {
                    add(savedText)
                }
            }.distinct()

            val targetText = when {
                !savedText.isNullOrBlank() -> savedText
                mergedChoices.isNotEmpty() -> mergedChoices.first()
                else -> ""
            }
            fieldSearchHelper.updateMasterItems(
                newItems = mergedChoices,
                selectedItemOverride = targetText,
                preserveCurrentText = false
            )
        }

        fun toCondition(): JoinCondition = JoinCondition(
            target = currentTarget(),
            field = currentField(),
            operator = currentOperator(),
            rawValue = currentRawValue()
        )
    }

    init {
        title = "$sourceCollection — ${I18n.t("join.dialog.title")}"
        setOKButtonText(
            if (isEditMode) I18n.t("join.dialog.applyChanges") else I18n.t("join.dialog.runInNewTab")
        )

        @Suppress("DEPRECATION")
        ComboboxSpeedSearch(targetCollectionCombo)

        ButtonGroup().apply {
            add(latestOneRadio)
            add(unwindAllRadio)
        }

        // Initialize drafts from existingJoinSpec (edit mode) or 1 fresh default target
        if (existingJoinSpec != null && existingJoinSpec.targets.isNotEmpty()) {
            existingJoinSpec.targets.forEach { t ->
                val pairs = t.effectiveKeyPairs().toMutableList()
                val foreignKeys = pairs.map { it.foreignField }
                val mergedCols = (t.allColumns + t.selectedColumns)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .toMutableList()
                targetDrafts.add(
                    MutableTargetDraft(
                        targetCollection = t.targetCollection,
                        keyPairs = pairs,
                        matchStrategy = t.matchStrategy,
                        allColumns = mergedCols,
                        selectedColumns = LinkedHashSet(t.selectedColumns),
                        joinKeyFields = (mergedCols + foreignKeys + t.conditions.map { it.field })
                            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList(),
                        conditions = t.conditions.toMutableList(),
                        excludeUnmatched = t.excludeUnmatched,
                        schemaLoaded = false,
                        preserveExistingSelectionOnFirstLoad = true
                    )
                )
            }
        } else {
            val defaultTarget = candidateCollections.firstOrNull { it != sourceCollection }
                ?: candidateCollections.firstOrNull()
                ?: sourceCollection
            val defaultLocal = initialLocalField?.trim()?.takeIf { it in leftJoinKeyFields }
                ?: initialLocalField?.substringBefore('.')?.takeIf { it in leftJoinKeyFields }
                ?: leftJoinKeyFields.firstOrNull { it != "_id" && (it.endsWith("Id") || it.endsWith("_id")) }
                ?: leftJoinKeyFields.first()
            targetDrafts.add(
                MutableTargetDraft(
                    targetCollection = defaultTarget,
                    keyPairs = mutableListOf(JoinKeyPair(JoinSourceRef.Base, defaultLocal, defaultLocal)),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    excludeUnmatched = true
                )
            )
        }

        wireListeners()
        rebuildTargetTabsBar()
        loadActiveDraftIntoEditor(triggerSchemaFetch = false)
        rebuildAllColumnCheckboxPanels()
        updateMultiUnwindWarning()
        updateCompositeInfoBanner()

        init()

        // Force initial schema sampling for active target immediately after DialogWrapper initialization
        SwingUtilities.invokeLater {
            if (!isDisposed) {
                loadSchemaForDraftAsync(activeTargetIdx, forceFetch = true)
                for (i in targetDrafts.indices) {
                    if (i != activeTargetIdx) {
                        loadSchemaForDraftAsync(i, forceFetch = false)
                    }
                }
                adjustDialogSizeDynamically(initialOpen = true)
            }
        }
    }

    override fun isResizable(): Boolean = true

    override fun getDimensionServiceKey(): String = "MaekomMongoLens.JoinViewDialog"

    private fun activeDraft(): MutableTargetDraft = targetDrafts[activeTargetIdx.coerceIn(0, targetDrafts.lastIndex)]

    private fun currentTargetCollectionName(): String =
        targetCollectionCombo.selectedItem?.toString()?.trim().orEmpty()

    private fun buildSourceKeyOptions(targetIdx: Int): List<SourceKeyOption> {
        val options = mutableListOf<SourceKeyOption>()
        for (lf in leftJoinKeyFields) {
            options.add(SourceKeyOption(JoinSourceRef.Base, sourceCollection, lf))
        }
        for (prevIdx in 0 until targetIdx) {
            val prev = targetDrafts[prevIdx]
            val prevFields = (prev.joinKeyFields + prev.allColumns + prev.selectedColumns + prev.keyPairs.map { it.foreignField })
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .ifEmpty { listOf("_id") }
            for (pf in prevFields) {
                options.add(SourceKeyOption(JoinSourceRef.PreviousTarget(prevIdx), prev.targetCollection, pf))
            }
        }
        return options
    }

    private fun wireListeners() {
        addJoinBtn.addActionListener {
            flushActiveTargetToDraft()
            val usedColls = targetDrafts.map { it.targetCollection }.toSet()
            val nextColl = candidateCollections.firstOrNull { it != sourceCollection && it !in usedColls }
                ?: candidateCollections.firstOrNull { it != sourceCollection }
                ?: sourceCollection
            val defaultLocal = leftJoinKeyFields.firstOrNull { it != "_id" && (it.endsWith("Id") || it.endsWith("_id")) }
                ?: leftJoinKeyFields.first()
            targetDrafts.add(
                MutableTargetDraft(
                    targetCollection = nextColl,
                    keyPairs = mutableListOf(JoinKeyPair(JoinSourceRef.Base, defaultLocal, defaultLocal)),
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    excludeUnmatched = true
                )
            )
            activeTargetIdx = targetDrafts.lastIndex
            rebuildTargetTabsBar()
            loadActiveDraftIntoEditor(triggerSchemaFetch = true)
            rebuildAllColumnCheckboxPanels()
            updateMultiUnwindWarning()
            updateCompositeInfoBanner()
            adjustDialogSizeDynamically(initialOpen = false)
        }

        targetCollectionCombo.addActionListener {
            if (suppressEditorEvents) return@addActionListener
            val target = currentTargetCollectionName()
            if (target.isEmpty()) return@addActionListener
            val draft = activeDraft()
            if (draft.targetCollection != target) {
                draft.targetCollection = target
                draft.schemaLoaded = false
                draft.preserveExistingSelectionOnFirstLoad = false
                draft.allColumns.clear()
                draft.selectedColumns.clear()
                draft.fieldCategories.clear()
                draft.joinKeyFields.clear()
                draft.joinFieldCategories.clear()
                rebuildTargetTabsBar()
                conditionRows.forEach { it.refreshTargetDisplay() }
                loadSchemaForDraftAsync(activeTargetIdx, forceFetch = true)
            }
        }

        addJoinKeyBtn.addActionListener {
            val draft = activeDraft()
            val usedLocals = joinKeyRows.map { it.toKeyPair().localField }.toSet()
            val nextLocal = leftJoinKeyFields.firstOrNull { it != "_id" && it !in usedLocals }
                ?: leftJoinKeyFields.firstOrNull { it !in usedLocals }
                ?: ""
            val suggestedForeign = if (nextLocal.isNotEmpty()) {
                suggestForeignFieldForLocal(nextLocal, draft) ?: ""
            } else {
                ""
            }
            addJoinKeyRowInternal(JoinKeyPair(JoinSourceRef.Base, nextLocal, suggestedForeign))
            refreshJoinKeyRowsState()
            syncActiveRowsToDraftAndWarnings()
            adjustDialogSizeDynamically(initialOpen = false)
        }

        autoMatchCommonKeysBtn.addActionListener {
            handleAutoMatchCommonKeys()
        }

        latestOneRadio.addActionListener {
            if (!suppressEditorEvents) {
                activeDraft().matchStrategy = JoinMatchStrategy.LATEST_ONE
                updateMultiUnwindWarning()
            }
        }
        unwindAllRadio.addActionListener {
            if (!suppressEditorEvents) {
                activeDraft().matchStrategy = JoinMatchStrategy.UNWIND_ALL
                updateMultiUnwindWarning()
            }
        }
        excludeUnmatchedCheckBox.addActionListener {
            if (!suppressEditorEvents) {
                activeDraft().excludeUnmatched = excludeUnmatchedCheckBox.isSelected
            }
        }

        addConditionBtn.addActionListener {
            addConditionRow(null)
            adjustDialogSizeDynamically(initialOpen = false)
        }
    }

    /**
     * Finds fields with identical names (excluding `_id`) between source and target schemas,
     * preserving ALL existing user rows and ONLY appending pairs not already mapped.
     */
    private fun handleAutoMatchCommonKeys() {
        val draft = activeDraft()
        val targetPool = (draft.joinKeyFields + draft.allColumns).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (targetPool.isEmpty()) {
            statusOrMismatchLabel.text = I18n.t("join.dialog.autoMatchNone")
            statusOrMismatchLabel.foreground = UiHelpers.COLOR_BLUE_FG
            return
        }
        val targetSet = targetPool.toSet()

        // Existing mapped pairs (preserved untouched!)
        val existingPairs = joinKeyRows.map { row ->
            val p = row.toKeyPair()
            p.localField to p.foreignField
        }.toSet()

        val commonFields = leftJoinKeyFields
            .filter { it != "_id" && it in targetSet && (it to it) !in existingPairs }
            .distinct()

        if (commonFields.isEmpty()) {
            statusOrMismatchLabel.text = I18n.t("join.dialog.autoMatchNone")
            statusOrMismatchLabel.foreground = UiHelpers.COLOR_BLUE_FG
            return
        }

        for (field in commonFields) {
            addJoinKeyRowInternal(JoinKeyPair(JoinSourceRef.Base, field, field))
        }
        refreshJoinKeyRowsState()
        syncActiveRowsToDraftAndWarnings()
        statusOrMismatchLabel.text = I18n.t("join.dialog.autoMatchAdded", commonFields.size)
        statusOrMismatchLabel.foreground = UiHelpers.COLOR_GREEN_FG
        adjustDialogSizeDynamically(initialOpen = false)
    }

    private fun addJoinKeyRowInternal(pair: JoinKeyPair) {
        val row = JoinKeyMappingRow(pair)
        joinKeyRows.add(row)
        joinKeysListPanel.add(row)
    }

    private fun removeJoinKeyRow(row: JoinKeyMappingRow) {
        if (joinKeyRows.size <= 1 || joinKeyRows.indexOf(row) == 0) return
        joinKeyRows.remove(row)
        joinKeysListPanel.remove(row)
        refreshJoinKeyRowsState()
        syncActiveRowsToDraftAndWarnings()
        adjustDialogSizeDynamically(initialOpen = false)
    }

    private fun refreshJoinKeyRowsState() {
        joinKeyRows.forEachIndexed { idx, row ->
            row.setRemovable(idx > 0 && joinKeyRows.size > 1)
        }
        joinKeysListPanel.revalidate()
        joinKeysListPanel.repaint()

        // Expand up to 3 rows visibly; scroll internally for 4+ rows
        val visibleRows = joinKeyRows.size.coerceIn(1, 3)
        val rowHeightPx = JBUI.scale(34)
        val paddingPx = JBUI.scale(14)
        val newScrollH = visibleRows * rowHeightPx + paddingPx
        val prevScrollH = currentKeysScrollHeightPx
        currentKeysScrollHeightPx = newScrollH

        val w = joinKeysScrollPane.preferredSize.width.coerceAtLeast(JBUI.scale(680))
        joinKeysScrollPane.preferredSize = Dimension(w, newScrollH)
        joinKeysScrollPane.minimumSize = Dimension(JBUI.scale(480), newScrollH)
        joinKeysScrollPane.maximumSize = Dimension(Int.MAX_VALUE, newScrollH)
        joinKeysScrollPane.revalidate()
        joinKeysScrollPane.repaint()

        val deltaH = newScrollH - prevScrollH
        updateCompositeInfoBanner()

        // Keep dialog width fixed while expanding/shrinking vertical height by deltaH so up to 3 rows are immediately visible
        val win = window
        if (win != null && deltaH != 0) {
            val screen = ScreenUtil.getScreenRectangle(win)
            val maxH = (screen.height * 0.88).toInt().coerceAtLeast(JBUI.scale(560))
            val minH = JBUI.scale(580)
            val targetH = (win.height + deltaH).coerceIn(minH, maxH)
            if (targetH != win.height) {
                win.setSize(win.width, targetH)
            }
            mainSplitter?.let { sp ->
                val minProp = when (visibleRows) {
                    1 -> 0.58f
                    2 -> 0.64f
                    else -> 0.68f
                }
                if (deltaH > 0 && sp.proportion < minProp) {
                    sp.proportion = minProp
                }
            }
            rootContainerPanel.revalidate()
            rootContainerPanel.repaint()
            win.revalidate()
            win.repaint()
        } else {
            rootContainerPanel.revalidate()
            rootContainerPanel.repaint()
        }
    }

    private fun syncActiveRowsToDraftAndWarnings() {
        val draft = activeDraft()
        draft.keyPairs = joinKeyRows.map { it.toKeyPair() }.toMutableList()
        updateCompositeInfoBanner()
        updateTypeMismatchWarning()
    }

    private fun updateCompositeInfoBanner() {
        val hasComposite = joinKeyRows.size >= 2 || targetDrafts.any { it.keyPairs.size >= 2 }
        compositeKeyInfoLabel.isVisible = hasComposite
    }

    private fun flushActiveTargetToDraft() {
        if (activeTargetIdx !in targetDrafts.indices) return
        val draft = activeDraft()
        val chosenTarget = currentTargetCollectionName()
        if (chosenTarget.isNotEmpty()) {
            draft.targetCollection = chosenTarget
        }
        draft.keyPairs = joinKeyRows.map { it.toKeyPair() }.toMutableList()
        draft.matchStrategy = if (latestOneRadio.isSelected) JoinMatchStrategy.LATEST_ONE else JoinMatchStrategy.UNWIND_ALL
        draft.excludeUnmatched = excludeUnmatchedCheckBox.isSelected

        baseConditionDrafts.clear()
        draft.conditions.clear()
        for (row in conditionRows) {
            val cond = row.toCondition()
            if (cond.target == JoinConditionTarget.LEFT) {
                baseConditionDrafts.add(cond)
            } else {
                draft.conditions.add(cond)
            }
        }
    }

    private fun removeJoinTargetAt(removedIdx: Int) {
        if (targetDrafts.size <= 1 || removedIdx !in targetDrafts.indices) return
        flushActiveTargetToDraft()
        targetDrafts.removeAt(removedIdx)

        // Re-index any subsequent target referencing an earlier join target
        for (i in removedIdx until targetDrafts.size) {
            val d = targetDrafts[i]
            d.keyPairs = d.keyPairs.map { pair ->
                val ref = pair.sourceRef
                if (ref is JoinSourceRef.PreviousTarget) {
                    when {
                        ref.targetIndex == removedIdx -> pair.copy(
                            sourceRef = JoinSourceRef.Base,
                            localField = leftFields.firstOrNull() ?: "_id"
                        )
                        ref.targetIndex > removedIdx -> pair.copy(
                            sourceRef = JoinSourceRef.PreviousTarget(ref.targetIndex - 1)
                        )
                        else -> pair
                    }
                } else {
                    pair
                }
            }.toMutableList()
        }

        activeTargetIdx = when {
            activeTargetIdx > removedIdx -> (activeTargetIdx - 1).coerceAtLeast(0)
            activeTargetIdx >= targetDrafts.size -> (targetDrafts.size - 1).coerceAtLeast(0)
            else -> activeTargetIdx
        }

        rebuildTargetTabsBar()
        loadActiveDraftIntoEditor(triggerSchemaFetch = false)
        rebuildAllColumnCheckboxPanels()
        updateMultiUnwindWarning()
        updateCompositeInfoBanner()
        adjustDialogSizeDynamically(initialOpen = false)
    }

    private fun createTargetTabChip(idx: Int, labelText: String, isActive: Boolean, showClose: Boolean): JPanel {
        val chip = object : JPanel(BorderLayout(4, 0)) {
            private var hovered = false

            init {
                isOpaque = false
                border = JBUI.Borders.empty(3, 9, 3, if (showClose) 5 else 9)
            }

            fun setHovered(h: Boolean) {
                if (hovered != h) {
                    hovered = h
                    repaint()
                }
            }

            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = JBUI.scale(7)
                    g2.color = when {
                        isActive -> JBColor(java.awt.Color(0xD8E8FC), java.awt.Color(0x2E436E))
                        hovered -> JBColor(java.awt.Color(0xE8ECF2), java.awt.Color(0x41444A))
                        else -> JBColor(java.awt.Color(0xF2F3F5), java.awt.Color(0x35373B))
                    }
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)

                    g2.color = if (isActive) {
                        JBUI.CurrentTheme.Focus.focusColor()
                    } else {
                        JBColor.border()
                    }
                    g2.stroke = java.awt.BasicStroke(if (isActive) 1.5f else 1f)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }

        val selectLabel = JBLabel(labelText).apply {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            font = font.deriveFont(if (isActive) Font.BOLD else Font.PLAIN)
            foreground = if (isActive) JBUI.CurrentTheme.Label.foreground() else JBUI.CurrentTheme.ContextHelp.FOREGROUND
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) = chip.setHovered(true)
                override fun mouseExited(e: java.awt.event.MouseEvent?) = chip.setHovered(false)
                override fun mousePressed(e: java.awt.event.MouseEvent?) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (idx == activeTargetIdx) return
                    flushActiveTargetToDraft()
                    activeTargetIdx = idx
                    rebuildTargetTabsBar()
                    loadActiveDraftIntoEditor(triggerSchemaFetch = !targetDrafts[idx].schemaLoaded)
                    updateMultiUnwindWarning()
                }
            })
        }
        chip.add(selectLabel, BorderLayout.CENTER)

        val closeBtn = JButton(AllIcons.Actions.Close).apply {
            rolloverIcon = AllIcons.Actions.CloseHovered
            pressedIcon = AllIcons.Actions.CloseHovered
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            isVisible = showClose
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = I18n.t("join.dialog.removeJoinTarget")
            preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
            minimumSize = Dimension(JBUI.scale(16), JBUI.scale(16))
            margin = JBUI.emptyInsets()
            addActionListener {
                removeJoinTargetAt(idx)
            }
        }
        if (showClose) {
            chip.add(closeBtn, BorderLayout.EAST)
        }
        return chip
    }

    private fun rebuildTargetTabsBar() {
        targetTabsPanel.removeAll()
        val circledNums = arrayOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
        val showClose = targetDrafts.size > 1
        targetDrafts.forEachIndexed { idx, draft ->
            val num = circledNums.getOrElse(idx) { "#${idx + 1}" }
            targetTabsPanel.add(
                createTargetTabChip(
                    idx = idx,
                    labelText = "$num ${draft.targetCollection}",
                    isActive = idx == activeTargetIdx,
                    showClose = showClose
                )
            )
        }
        // Place the green '+' icon button inline directly beside the last join target chip (6px gap)
        targetTabsPanel.add(addJoinBtn)
        targetTabsPanel.revalidate()
        targetTabsPanel.repaint()
        targetTabsScrollPane.revalidate()
        targetTabsScrollPane.repaint()
    }

    private fun loadActiveDraftIntoEditor(triggerSchemaFetch: Boolean) {
        val draft = activeDraft()
        suppressEditorEvents = true
        try {
            targetCollectionCombo.selectedItem = draft.targetCollection

            // Rebuild dynamic join key rows from draft.keyPairs
            joinKeyRows.clear()
            joinKeysListPanel.removeAll()
            val pairsToShow = draft.keyPairs.ifEmpty {
                mutableListOf(JoinKeyPair(JoinSourceRef.Base, leftJoinKeyFields.first(), leftJoinKeyFields.first()))
            }
            pairsToShow.forEach { addJoinKeyRowInternal(it) }
            refreshJoinKeyRowsState()

            latestOneRadio.isSelected = draft.matchStrategy == JoinMatchStrategy.LATEST_ONE
            unwindAllRadio.isSelected = draft.matchStrategy == JoinMatchStrategy.UNWIND_ALL
            excludeUnmatchedCheckBox.isSelected = draft.excludeUnmatched

            // Rebuild condition rows: shared base conditions + this target's right conditions
            conditionRows.clear()
            conditionsListPanel.removeAll()
            baseConditionDrafts.forEach { addConditionRowInternal(it) }
            draft.conditions.forEach { addConditionRowInternal(it) }
            refreshConditionsViewportHeight()
        } finally {
            suppressEditorEvents = false
        }

        if (triggerSchemaFetch) {
            loadSchemaForDraftAsync(activeTargetIdx, forceFetch = true)
        } else {
            updateTypeMismatchWarning()
        }
    }

    private fun addConditionRow(initial: JoinCondition?) {
        addConditionRowInternal(initial)
        refreshConditionsViewportHeight()
    }

    private fun addConditionRowInternal(initial: JoinCondition?) {
        val row = ConditionRow(initial)
        conditionRows.add(row)
        conditionsListPanel.add(row)
        conditionsListPanel.revalidate()
        conditionsListPanel.repaint()
    }

    private fun removeConditionRow(row: ConditionRow) {
        conditionRows.remove(row)
        conditionsListPanel.remove(row)
        conditionsListPanel.revalidate()
        conditionsListPanel.repaint()
        refreshConditionsViewportHeight()
        adjustDialogSizeDynamically(initialOpen = false)
    }

    private fun refreshConditionsViewportHeight() {
        val rowHeightPx = JBUI.scale(34)
        val minH = JBUI.scale(52)
        val maxH = JBUI.scale(150)
        val desiredH = (conditionRows.size.coerceAtLeast(1) * rowHeightPx + JBUI.scale(10)).coerceIn(minH, maxH)
        conditionsScrollPane.preferredSize = Dimension(conditionsScrollPane.preferredSize.width.coerceAtLeast(JBUI.scale(680)), desiredH)
        conditionsScrollPane.revalidate()
        conditionsScrollPane.repaint()
    }

    private fun loadSchemaForDraftAsync(draftIndex: Int, forceFetch: Boolean) {
        if (draftIndex !in targetDrafts.indices) return
        val draft = targetDrafts[draftIndex]
        val targetColl = draft.targetCollection
        if (targetColl.isBlank()) return

        val isActive = draftIndex == activeTargetIdx

        val cached = schemaCache[targetColl]
        if (cached != null && !forceFetch) {
            applySampledSchemaToDraft(draftIndex, targetColl, cached, null)
            return
        }

        val reqSeq = if (isActive) schemaRequestSeq.incrementAndGet() else -1L
        if (isActive) {
            statusOrMismatchLabel.text = I18n.t("join.dialog.loadingTarget")
            statusOrMismatchLabel.foreground = UiHelpers.BSON_NULL_COLOR
            joinKeyRows.forEach { it.setControlsEnabled(false) }
            autoMatchCommonKeysBtn.isEnabled = false
            isOKActionEnabled = false
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            var sampled: MongoConnectionManager.SampledTopLevelSchema? = null
            var error: Throwable? = null
            try {
                sampled = connectionManager.sampleCollectionTopLevelSchema(
                    profile = profile,
                    database = database,
                    collection = targetColl,
                    sampleSize = 10
                )
            } catch (t: Throwable) {
                error = t
            } finally {
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    if (isActive && reqSeq != schemaRequestSeq.get()) return@invokeLater
                    if (isActive) {
                        joinKeyRows.forEach { it.setControlsEnabled(true) }
                        autoMatchCommonKeysBtn.isEnabled = true
                        isOKActionEnabled = true
                    }
                    if (sampled != null) {
                        schemaCache[targetColl] = sampled
                    }
                    applySampledSchemaToDraft(draftIndex, targetColl, sampled, error)
                }, ModalityState.any())
            }
        }
    }

    private fun applySampledSchemaToDraft(
        draftIndex: Int,
        targetColl: String,
        schema: MongoConnectionManager.SampledTopLevelSchema?,
        error: Throwable?
    ) {
        if (draftIndex !in targetDrafts.indices) return
        val draft = targetDrafts[draftIndex]
        if (draft.targetCollection != targetColl) return

        val restoring = draft.preserveExistingSelectionOnFirstLoad
        draft.preserveExistingSelectionOnFirstLoad = false
        draft.schemaLoaded = true

        if (error != null || schema == null) {
            val errMsg = error?.message ?: error?.javaClass?.simpleName ?: "Unknown error"
            draft.lastError = errMsg
            draft.isEmptyCollection = false
            if (!restoring) {
                draft.allColumns.clear()
                draft.selectedColumns.clear()
                draft.fieldCategories.clear()
                draft.joinKeyFields.clear()
                draft.joinFieldCategories.clear()
            }
        } else if (schema.documentCount == 0 || schema.fieldTypes.isEmpty()) {
            draft.lastError = null
            draft.isEmptyCollection = true
            draft.fieldCategories.clear()
            draft.joinFieldCategories.clear()
            if (!restoring) {
                draft.allColumns.clear()
                draft.selectedColumns.clear()
                draft.joinKeyFields.clear()
            }
        } else {
            draft.lastError = null
            draft.isEmptyCollection = false
            draft.fieldCategories.clear()
            draft.fieldCategories.putAll(schema.fieldTypes)
            draft.joinFieldCategories.clear()
            draft.joinFieldCategories.putAll(schema.joinFieldTypes)

            val sampledKeys = schema.fieldTypes.keys.toList()
            val sampledJoinKeys = (schema.joinFieldTypes.keys + sampledKeys).distinct()
            draft.joinKeyFields.clear()
            draft.joinKeyFields.addAll(
                (sampledJoinKeys + draft.keyPairs.map { it.foreignField } + draft.conditions.map { it.field })
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            )
            if (restoring) {
                val merged = buildList {
                    addAll(sampledKeys)
                    addAll(draft.allColumns)
                    addAll(draft.selectedColumns)
                }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                draft.allColumns.clear()
                draft.allColumns.addAll(merged)
            } else {
                draft.allColumns.clear()
                draft.allColumns.addAll(sampledKeys)
                draft.selectedColumns.clear()
                draft.selectedColumns.addAll(sampledKeys)
                // Auto-match foreign field for the initial 1st key pair on fresh collection selection
                if (draft.keyPairs.isNotEmpty()) {
                    val first = draft.keyPairs[0]
                    val suggested = suggestForeignFieldForLocal(first.localField, draft)
                    if (suggested != null) {
                        draft.keyPairs[0] = first.copy(foreignField = suggested)
                    }
                }
            }
        }

        if (draftIndex == activeTargetIdx) {
            suppressEditorEvents = true
            try {
                joinKeyRows.forEachIndexed { idx, row ->
                    val explicit = if (!restoring && idx == 0) draft.keyPairs.getOrNull(0)?.foreignField else null
                    row.refreshForeignOptions(draft, explicitForeign = explicit)
                }
            } finally {
                suppressEditorEvents = false
            }
            conditionRows.filter { it.currentTarget() == JoinConditionTarget.RIGHT }
                .forEach { it.refreshFieldChoices(preserveCurrentText = true) }
            updateTypeMismatchWarning()
        } else if (draftIndex < activeTargetIdx) {
            suppressEditorEvents = true
            try {
                joinKeyRows.forEach { it.refreshSourceOptions(activeTargetIdx) }
            } finally {
                suppressEditorEvents = false
            }
        }

        rebuildAllColumnCheckboxPanels()
    }

    private fun suggestForeignFieldForLocal(localField: String, draft: MutableTargetDraft): String? {
        val candidates = (draft.joinKeyFields + draft.allColumns).distinct()
        if (candidates.isEmpty()) return null
        val localFull = localField.trim()
        val localLeaf = localFull.substringAfterLast('.')
        val snake = ReferenceJumpHelper.camelToSnake(localLeaf)
        val camel = ReferenceJumpHelper.snakeToCamel(localLeaf)

        return when {
            localFull.isNotEmpty() && localFull != "_id" && localFull in candidates -> localFull
            localLeaf.isNotEmpty() && localLeaf != "_id" && localLeaf in candidates -> localLeaf
            snake.isNotEmpty() && snake != "_id" && snake in candidates -> snake
            camel.isNotEmpty() && camel != "_id" && camel in candidates -> camel
            "_id" in candidates -> "_id"
            else -> candidates.first()
        }
    }

    private fun updateTypeMismatchWarning() {
        val draft = activeDraft()
        val err = draft.lastError
        if (err != null) {
            statusOrMismatchLabel.text = I18n.t("join.dialog.schemaLoadFailed", err)
            statusOrMismatchLabel.foreground = UiHelpers.COLOR_RED_FG
            return
        }
        if (draft.isEmptyCollection) {
            statusOrMismatchLabel.text = I18n.t("join.dialog.targetEmpty")
            statusOrMismatchLabel.foreground = UiHelpers.COLOR_ORANGE_FG
            return
        }

        // Check every key mapping row for potential BSON type mismatch
        for (row in joinKeyRows) {
            val pair = row.toKeyPair()
            if (pair.localField.isBlank() || pair.foreignField.isBlank()) continue
            val localCat: String? = when (val ref = pair.sourceRef) {
                is JoinSourceRef.Base -> sourceJoinKeyCategories[pair.localField]
                    ?: sourceFieldCategories[pair.localField]
                    ?: if (pair.localField == "_id") "ObjectId" else null
                is JoinSourceRef.PreviousTarget -> {
                    val prevDraft = targetDrafts.getOrNull(ref.targetIndex)
                    prevDraft?.joinFieldCategories?.get(pair.localField)
                        ?: prevDraft?.fieldCategories?.get(pair.localField)
                        ?: if (pair.localField == "_id") "ObjectId" else null
                }
            }
            val foreignCat = draft.joinFieldCategories[pair.foreignField] ?: draft.fieldCategories[pair.foreignField]
            if (localCat != null && foreignCat != null && localCat != foreignCat) {
                statusOrMismatchLabel.text = "⚠ ${pair.localField}=${pair.foreignField}: ${I18n.t("join.dialog.typeMismatchWarning", localCat, foreignCat)}"
                statusOrMismatchLabel.foreground = UiHelpers.COLOR_ORANGE_FG
                return
            }
        }
        statusOrMismatchLabel.text = " "
    }

    private fun updateMultiUnwindWarning() {
        val unwindCount = targetDrafts.countIndexed { idx, d ->
            val mode = if (idx == activeTargetIdx) {
                if (unwindAllRadio.isSelected) JoinMatchStrategy.UNWIND_ALL else JoinMatchStrategy.LATEST_ONE
            } else {
                d.matchStrategy
            }
            mode == JoinMatchStrategy.UNWIND_ALL
        }
        multiUnwindWarningLabel.isVisible = unwindCount >= 2
    }

    private inline fun <T> List<T>.countIndexed(predicate: (Int, T) -> Boolean): Int {
        var c = 0
        forEachIndexed { idx, item -> if (predicate(idx, item)) c++ }
        return c
    }

    private fun rebuildAllColumnCheckboxPanels() {
        columnsRowPanel.removeAll()

        val basePanel = buildColumnPanelCard(
            title = I18n.t("join.dialog.colPanelBase", sourceCollection),
            fields = leftFields,
            selectedSet = selectedLeftSet,
            typeCategories = sourceFieldCategories
        )
        columnsRowPanel.add(basePanel)

        targetDrafts.forEachIndexed { idx, draft ->
            columnsRowPanel.add(Box.createHorizontalStrut(JBUI.scale(8)))
            val targetPanel = buildColumnPanelCard(
                title = I18n.t("join.dialog.colPanelTarget", idx + 1, draft.targetCollection),
                fields = (draft.allColumns + draft.selectedColumns).distinct(),
                selectedSet = draft.selectedColumns,
                typeCategories = draft.fieldCategories
            )
            columnsRowPanel.add(targetPanel)
        }

        columnsRowPanel.revalidate()
        columnsRowPanel.repaint()
        columnsScrollPane.revalidate()
        columnsScrollPane.repaint()
    }

    private fun buildColumnPanelCard(
        title: String,
        fields: List<String>,
        selectedSet: LinkedHashSet<String>,
        typeCategories: Map<String, String>
    ): JPanel {
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }
        val checkBoxes = linkedMapOf<String, JBCheckBox>()
        for (f in fields) {
            val typeTag = typeCategories[f]?.let { "  [$it]" }.orEmpty()
            val cb = JBCheckBox("$f$typeTag", f in selectedSet).apply {
                border = JBUI.Borders.empty(2)
                addActionListener {
                    if (isSelected) selectedSet.add(f) else selectedSet.remove(f)
                }
            }
            checkBoxes[f] = cb
            listPanel.add(cb)
        }

        val allBtn = JButton(I18n.t("colSelect.dialog.selectAll")).apply {
            margin = JBUI.insets(1, 5)
            addActionListener {
                fields.forEach { selectedSet.add(it) }
                checkBoxes.values.forEach { it.isSelected = true }
            }
        }
        val noneBtn = JButton(I18n.t("colSelect.dialog.deselectAll")).apply {
            margin = JBUI.insets(1, 5)
            addActionListener {
                selectedSet.clear()
                checkBoxes.values.forEach { it.isSelected = false }
            }
        }

        val header = JPanel(BorderLayout(4, 0)).apply {
            add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.CENTER)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                add(allBtn)
                add(noneBtn)
            }
            add(btns, BorderLayout.EAST)
        }

        val minW = JBUI.scale(240)
        return JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, true),
                JBUI.Borders.empty(4, 6)
            )
            minimumSize = Dimension(minW, JBUI.scale(140))
            preferredSize = Dimension(JBUI.scale(255), JBUI.scale(190))
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(listPanel), BorderLayout.CENTER)
        }
    }

    private fun adjustDialogSizeDynamically(initialOpen: Boolean) {
        val win = window ?: return
        val screen = ScreenUtil.getScreenRectangle(win)
        val maxW = (screen.width * 0.85).toInt().coerceAtLeast(JBUI.scale(700))
        val maxH = (screen.height * 0.85).toInt().coerceAtLeast(JBUI.scale(520))

        val desiredW = when {
            targetDrafts.size <= 1 -> JBUI.scale(820)
            else -> JBUI.scale(1060)
        }.coerceAtMost(maxW)

        val keyExtraH = ((joinKeyRows.size - 1).coerceAtLeast(0) * JBUI.scale(34)).coerceAtMost(JBUI.scale(105))
        val condExtraH = ((conditionRows.size - 1).coerceAtLeast(0) * JBUI.scale(32)).coerceAtMost(JBUI.scale(96))
        val desiredH = (JBUI.scale(640) + keyExtraH + condExtraH).coerceAtMost(maxH)

        val targetW = if (initialOpen) desiredW else maxOf(win.width, desiredW).coerceAtMost(maxW)
        val targetH = if (initialOpen) desiredH else maxOf(win.height, desiredH).coerceAtMost(maxH)

        if (win.width != targetW || win.height != targetH) {
            win.setSize(targetW, targetH)
            if (initialOpen) {
                win.setLocationRelativeTo(win.owner)
            }
            win.revalidate()
            win.repaint()
        }
    }

    override fun createCenterPanel(): JComponent {
        // 1. Multi-Join Target Tab Bar (horizontally scrollable chips with conditional '×' + inline green '+' beside last chip)
        val tabBarRow = JPanel(BorderLayout(6, 0)).apply {
            add(targetTabsScrollPane, BorderLayout.CENTER)
        }
        val topHeaderStack = JPanel(BorderLayout(0, 4)).apply {
            add(tabBarRow, BorderLayout.NORTH)
            add(multiUnwindWarningLabel, BorderLayout.SOUTH)
        }

        // 2. Target Collection + Dynamic Composite Join Keys Section
        val targetSelectRow = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("join.dialog.targetCollection")), targetCollectionCombo, 1, false)
            .panel

        val keysToolbar = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.emptyBottom(4)
            val leftBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(addJoinKeyBtn)
                add(autoMatchCommonKeysBtn)
            }
            add(leftBtns, BorderLayout.WEST)
            add(JBLabel(I18n.t("join.dialog.keyMappingHint")).apply {
                foreground = UiHelpers.BSON_NULL_COLOR
                font = font.deriveFont(font.size2D - 1f)
            }, BorderLayout.EAST)
        }

        val keysSection = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("join.dialog.keysSection")),
                JBUI.Borders.empty(4, 6)
            )
            add(keysToolbar, BorderLayout.NORTH)
            add(joinKeysScrollPane, BorderLayout.CENTER)
            val statusStack = JPanel(BorderLayout(0, 4)).apply {
                isOpaque = false
                add(statusOrMismatchLabel, BorderLayout.NORTH)
                add(compositeKeyInfoLabel, BorderLayout.SOUTH)
            }
            add(statusStack, BorderLayout.SOUTH)
        }

        val matchStack = JPanel(BorderLayout(0, 4)).apply {
            add(targetSelectRow, BorderLayout.NORTH)
            add(keysSection, BorderLayout.CENTER)
        }

        // 3. Match Strategy Radios + Result Limit selector
        val strategyRow = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("join.dialog.strategySection")),
                JBUI.Borders.empty(2, 6)
            )
            val radios = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
                isOpaque = false
                add(latestOneRadio)
                add(unwindAllRadio)
            }
            val limitPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel(I18n.t("join.dialog.limitLabel")))
                add(joinLimitCombo)
            }
            add(radios, BorderLayout.CENTER)
            add(limitPanel, BorderLayout.EAST)
        }

        // 4. WHERE AND Exclusion Conditions Builder
        val whereHeader = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(addConditionBtn, BorderLayout.WEST)
            add(excludeUnmatchedCheckBox, BorderLayout.EAST)
        }
        val whereSection = JPanel(BorderLayout(0, 2)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("join.dialog.whereSection")),
                JBUI.Borders.empty(4, 6)
            )
            add(whereHeader, BorderLayout.NORTH)
            add(conditionsScrollPane, BorderLayout.CENTER)
        }

        val upperConfigPane = JPanel(BorderLayout(0, 6)).apply {
            val midStack = JPanel(BorderLayout(0, 6)).apply {
                add(matchStack, BorderLayout.CENTER)
                add(strategyRow, BorderLayout.SOUTH)
            }
            add(topHeaderStack, BorderLayout.NORTH)
            add(midStack, BorderLayout.CENTER)
            add(whereSection, BorderLayout.SOUTH)
        }

        // 5. Lower Multi-Column Checkboxes Section
        val lowerColumnsPane = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("join.dialog.columnsSection")),
                JBUI.Borders.empty(4, 6)
            )
            add(columnsScrollPane, BorderLayout.CENTER)
        }

        val splitter = JBSplitter(true, 0.62f).apply {
            firstComponent = upperConfigPane
            secondComponent = lowerColumnsPane
        }
        mainSplitter = splitter

        rootContainerPanel.apply {
            border = JBUI.Borders.empty(6)
            preferredSize = Dimension(
                if (targetDrafts.size <= 1) JBUI.scale(800) else JBUI.scale(1040),
                JBUI.scale(640)
            )
            add(splitter, BorderLayout.CENTER)
        }
        return rootContainerPanel
    }

    override fun doValidate(): ValidationInfo? {
        flushActiveTargetToDraft()

        for ((idx, draft) in targetDrafts.withIndex()) {
            if (draft.targetCollection.isBlank()) {
                return ValidationInfo(I18n.t("join.dialog.validationTargetRequired"), targetCollectionCombo)
            }
            if (draft.keyPairs.isEmpty()) {
                return ValidationInfo(I18n.t("join.dialog.validationFieldsRequired"), joinKeysScrollPane)
            }
            val seenPairs = hashSetOf<Triple<JoinSourceRef, String, String>>()
            draft.keyPairs.forEachIndexed { rowIdx, pair ->
                val focusRow = if (idx == activeTargetIdx) joinKeyRows.getOrNull(rowIdx) else null
                if (pair.localField.isBlank()) {
                    return ValidationInfo(
                        I18n.t("join.dialog.validationKeyRowLocalRequired", rowIdx + 1),
                        focusRow?.sourceKeyCombo ?: joinKeysScrollPane
                    )
                }
                if (pair.foreignField.isBlank()) {
                    return ValidationInfo(
                        I18n.t("join.dialog.validationKeyRowForeignRequired", rowIdx + 1),
                        focusRow?.foreignFieldCombo ?: joinKeysScrollPane
                    )
                }
                val triple = Triple(pair.sourceRef, pair.localField.trim(), pair.foreignField.trim())
                if (!seenPairs.add(triple)) {
                    return ValidationInfo(
                        I18n.t("join.dialog.validationDuplicateKeyPair", pair.localField, pair.foreignField),
                        focusRow?.foreignFieldCombo ?: joinKeysScrollPane
                    )
                }
            }
        }

        val anySelectedColumn = selectedLeftSet.isNotEmpty() || targetDrafts.any { it.selectedColumns.isNotEmpty() }
        if (!anySelectedColumn) {
            return ValidationInfo(I18n.t("join.dialog.validationColumnsRequired"), columnsScrollPane)
        }

        for (row in conditionRows) {
            val f = row.currentField()
            val op = row.currentOperator()
            val v = row.currentRawValue()
            if (f.isEmpty()) {
                return ValidationInfo(I18n.t("join.dialog.validationConditionFieldRequired"), row.fieldCombo)
            }
            if (op.requiresValue && v.isEmpty()) {
                return ValidationInfo(I18n.t("join.dialog.validationConditionIncomplete"), row.valueField)
            }
        }
        return null
    }

    override fun createActions(): Array<javax.swing.Action> {
        val copyPipelineAction = object : DialogWrapperAction(I18n.t("join.tab.copyPipelineBtn")) {
            init {
                putValue(javax.swing.Action.SMALL_ICON, AllIcons.Actions.Copy)
                putValue(javax.swing.Action.SHORT_DESCRIPTION, I18n.t("join.tab.copyPipelineTooltip"))
            }

            override fun doAction(e: java.awt.event.ActionEvent?) {
                val liveSpec = buildCurrentJoinConfigFromUi()
                if (!com.maekom.mongolens.mongo.CompassPipelineSerializer.hasValidJoinKeys(liveSpec)) {
                    com.intellij.openapi.ui.Messages.showWarningDialog(
                        project,
                        I18n.t("join.tab.copyPipelineNoValidKeys"),
                        I18n.t("join.tab.copyPipelineBtn")
                    )
                    return
                }
                val pipelineJson = com.maekom.mongolens.mongo.CompassPipelineSerializer.formatCompassPipelineJson(liveSpec)
                UiHelpers.copyToClipboard(pipelineJson)
                UiHelpers.notify(project, I18n.t("join.tab.pipelineCopied"))
            }
        }
        // Place [Copy Pipeline] directly beside [Run in Join Tab ▶]
        return arrayOf(copyPipelineAction, okAction, cancelAction)
    }

    /**
     * Collects the live, unsaved state directly from all active UI components (including un-Enter-committed
     * text in SearchableFieldComboBox editors, radios, checkboxes, limit, and column checkboxes).
     * Incomplete join key rows with blank source/target fields are excluded cleanly without fabricating fallback keys.
     */
    fun buildCurrentJoinConfigFromUi(): JoinSpec = buildJoinSpecInternal(recordHistory = false, allowEmptyKeys = true)

    fun buildJoinSpec(): JoinSpec = buildJoinSpecInternal(recordHistory = true, allowEmptyKeys = false)

    private fun buildJoinSpecInternal(recordHistory: Boolean, allowEmptyKeys: Boolean): JoinSpec {
        flushActiveTargetToDraft()

        val selectedLeft = leftFields.filter { it in selectedLeftSet }
        val builtTargets = targetDrafts.map { d ->
            val cleanPairs = d.keyPairs
                .map {
                    it.copy(
                        localField = com.maekom.mongolens.mongo.CompassPipelineSerializer.stripUiCollectionPrefix(it.localField),
                        foreignField = com.maekom.mongolens.mongo.CompassPipelineSerializer.stripUiCollectionPrefix(it.foreignField)
                    )
                }
                .filter { it.localField.isNotEmpty() && it.foreignField.isNotEmpty() }
                .distinct()
            val primaryPair = cleanPairs.firstOrNull() ?: if (allowEmptyKeys) {
                JoinKeyPair(JoinSourceRef.Base, "", "")
            } else {
                JoinKeyPair(JoinSourceRef.Base, "_id", "_id")
            }
            val allCols = (d.allColumns + d.selectedColumns)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val selectedCols = allCols.filter { it in d.selectedColumns }
            val cleanConds = d.conditions.filter {
                it.field.isNotBlank() && (!it.operator.requiresValue || it.rawValue.isNotBlank())
            }
            if (recordHistory && primaryPair.localField.isNotEmpty() && primaryPair.sourceRef is JoinSourceRef.Base) {
                ReferenceJumpHelper.recordSelection(database, primaryPair.localField, d.targetCollection)
            }
            JoinTargetSpec(
                targetCollection = d.targetCollection,
                sourceRef = primaryPair.sourceRef,
                localField = primaryPair.localField,
                foreignField = primaryPair.foreignField,
                keyPairs = if (allowEmptyKeys) cleanPairs else cleanPairs.ifEmpty { listOf(primaryPair) },
                matchStrategy = d.matchStrategy,
                allColumns = allCols,
                selectedColumns = selectedCols,
                conditions = cleanConds,
                excludeUnmatched = d.excludeUnmatched,
                fieldTypes = d.joinFieldCategories + d.fieldCategories
            )
        }

        val cleanBaseConds = baseConditionDrafts.filter {
            it.field.isNotBlank() && (!it.operator.requiresValue || it.rawValue.isNotBlank())
        }

        val selectedLimitOption: Int? = (joinLimitCombo.selectedItem as? JoinLimitOption)?.value

        return JoinSpec(
            leftCollection = sourceCollection,
            allLeftFields = leftFields,
            selectedLeftFields = selectedLeft,
            baseConditions = cleanBaseConds,
            targets = builtTargets,
            limitOption = selectedLimitOption,
            leftFieldTypes = sourceJoinKeyCategories + sourceFieldCategories
        )
    }
}
