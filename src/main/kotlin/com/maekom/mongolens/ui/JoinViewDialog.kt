package com.maekom.mongolens.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.DocumentAdapter
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
    allCollectionsInDb: List<String>,
    private val existingJoinSpec: JoinSpec? = null
) : DialogWrapper(project) {

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
        var sourceRef: JoinSourceRef = JoinSourceRef.Base,
        var localField: String = "_id",
        var foreignField: String = "_id",
        var matchStrategy: JoinMatchStrategy = JoinMatchStrategy.LATEST_ONE,
        var allColumns: MutableList<String> = mutableListOf(),
        var selectedColumns: LinkedHashSet<String> = linkedSetOf(),
        var fieldCategories: MutableMap<String, String> = linkedMapOf(),
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
            spec.baseConditions.forEach { add(it.field) }
            spec.targets.filter { it.sourceRef is JoinSourceRef.Base }.forEach { add(it.localField) }
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
            fieldPath = existingJoinSpec?.targets?.firstOrNull()?.localField ?: initialLocalField,
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

    // Top bar: [ ① <target1> ] [ ② <target2> ] ... [+ 조인 추가]  |  [이 조인 삭제]
    private val targetTabsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    private val addJoinBtn = JButton(I18n.t("join.dialog.addJoinTarget")).apply {
        margin = JBUI.insets(2, 8)
    }
    private val removeJoinBtn = JButton(I18n.t("join.dialog.removeJoinTarget")).apply {
        margin = JBUI.insets(2, 8)
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
    private val sourceKeyModel = DefaultComboBoxModel<SourceKeyOption>()
    private val sourceKeyCombo = ComboBox(sourceKeyModel)
    private val foreignFieldModel = DefaultComboBoxModel<String>()
    private val foreignFieldCombo = ComboBox(foreignFieldModel).apply {
        isEditable = true
    }
    private val statusOrMismatchLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD)
    }

    private val latestOneRadio = JBRadioButton(I18n.t("join.dialog.strategyLatestOne"), true)
    private val unwindAllRadio = JBRadioButton(I18n.t("join.dialog.strategyUnwindAll"), false)
    private val excludeUnmatchedCheckBox = JBCheckBox(I18n.t("join.dialog.innerJoinCheck"), true)

    // WHERE AND conditions builder
    private val addConditionBtn = JButton(I18n.t("join.dialog.addCondition")).apply {
        margin = JBUI.insets(1, 6)
    }
    private val conditionsListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(2, 4)
    }
    private val conditionsScrollPane = JBScrollPane(conditionsListPanel).apply {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(68))
        minimumSize = Dimension(JBUI.scale(400), JBUI.scale(52))
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
        preferredSize = Dimension(JBUI.scale(760), JBUI.scale(215))
        minimumSize = Dimension(JBUI.scale(400), JBUI.scale(150))
    }

    private val rootContainerPanel = JPanel(BorderLayout(0, 6))

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
            val activeRightFields = activeDraft().allColumns
            val choices = when (currentTarget()) {
                JoinConditionTarget.LEFT -> leftFields
                JoinConditionTarget.RIGHT -> activeRightFields
            }
            val mergedChoices = buildList {
                addAll(choices)
                if (!savedText.isNullOrBlank() && savedText !in choices) {
                    add(savedText)
                }
            }.distinct()

            fieldModel.removeAllElements()
            mergedChoices.forEach { fieldModel.addElement(it) }

            val targetText = when {
                !savedText.isNullOrBlank() -> savedText
                mergedChoices.isNotEmpty() -> mergedChoices.first()
                else -> ""
            }
            fieldCombo.selectedItem = targetText
            fieldCombo.editor?.item = targetText
            (fieldCombo.editor?.editorComponent as? JTextComponent)?.text = targetText
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
                val mergedCols = (t.allColumns + t.selectedColumns + t.foreignField + t.conditions.map { it.field })
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .toMutableList()
                targetDrafts.add(
                    MutableTargetDraft(
                        targetCollection = t.targetCollection,
                        sourceRef = t.sourceRef,
                        localField = t.localField,
                        foreignField = t.foreignField,
                        matchStrategy = t.matchStrategy,
                        allColumns = mergedCols,
                        selectedColumns = LinkedHashSet(t.selectedColumns),
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
            val defaultLocal = initialLocalField?.substringBefore('.')?.takeIf { it in leftFields }
                ?: leftFields.firstOrNull { it != "_id" && (it.endsWith("Id") || it.endsWith("_id")) }
                ?: leftFields.first()
            targetDrafts.add(
                MutableTargetDraft(
                    targetCollection = defaultTarget,
                    sourceRef = JoinSourceRef.Base,
                    localField = defaultLocal,
                    foreignField = defaultLocal,
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

        init()

        // Force initial schema sampling for active target immediately after DialogWrapper initialization
        SwingUtilities.invokeLater {
            if (!isDisposed) {
                loadSchemaForDraftAsync(activeTargetIdx, forceFetch = true)
                // Also warm remaining targets in edit mode so their column panels have type tags
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

    private fun currentForeignFieldText(): String {
        val editorComp = foreignFieldCombo.editor?.editorComponent as? JTextComponent
        if (editorComp != null) {
            return (editorComp.text as String?)?.trim().orEmpty()
        }
        return (foreignFieldCombo.editor?.item?.toString() ?: foreignFieldCombo.selectedItem?.toString())?.trim().orEmpty()
    }

    private fun wireListeners() {
        addJoinBtn.addActionListener {
            flushActiveTargetToDraft()
            val usedColls = targetDrafts.map { it.targetCollection }.toSet()
            val nextColl = candidateCollections.firstOrNull { it != sourceCollection && it !in usedColls }
                ?: candidateCollections.firstOrNull { it != sourceCollection }
                ?: sourceCollection
            val defaultLocal = leftFields.firstOrNull { it != "_id" && (it.endsWith("Id") || it.endsWith("_id")) }
                ?: leftFields.first()
            targetDrafts.add(
                MutableTargetDraft(
                    targetCollection = nextColl,
                    sourceRef = JoinSourceRef.Base,
                    localField = defaultLocal,
                    foreignField = defaultLocal,
                    matchStrategy = JoinMatchStrategy.LATEST_ONE,
                    excludeUnmatched = true
                )
            )
            activeTargetIdx = targetDrafts.lastIndex
            rebuildTargetTabsBar()
            loadActiveDraftIntoEditor(triggerSchemaFetch = true)
            rebuildAllColumnCheckboxPanels()
            updateMultiUnwindWarning()
            adjustDialogSizeDynamically(initialOpen = false)
        }

        removeJoinBtn.addActionListener {
            if (targetDrafts.size <= 1) return@addActionListener
            flushActiveTargetToDraft()
            val removedIdx = activeTargetIdx
            targetDrafts.removeAt(removedIdx)

            // Re-index any subsequent target referencing an earlier join target
            for (i in removedIdx until targetDrafts.size) {
                val d = targetDrafts[i]
                val ref = d.sourceRef
                if (ref is JoinSourceRef.PreviousTarget) {
                    if (ref.targetIndex == removedIdx) {
                        // Fallback safely to Base collection's first field
                        d.sourceRef = JoinSourceRef.Base
                        d.localField = leftFields.firstOrNull() ?: "_id"
                    } else if (ref.targetIndex > removedIdx) {
                        d.sourceRef = JoinSourceRef.PreviousTarget(ref.targetIndex - 1)
                    }
                }
            }

            activeTargetIdx = (removedIdx - 1).coerceAtLeast(0)
            rebuildTargetTabsBar()
            loadActiveDraftIntoEditor(triggerSchemaFetch = false)
            rebuildAllColumnCheckboxPanels()
            updateMultiUnwindWarning()
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
                rebuildTargetTabsBar()
                conditionRows.forEach { it.refreshTargetDisplay() }
                loadSchemaForDraftAsync(activeTargetIdx, forceFetch = true)
            }
        }

        sourceKeyCombo.addActionListener {
            if (suppressEditorEvents) return@addActionListener
            val selectedOpt = sourceKeyCombo.selectedItem as? SourceKeyOption ?: return@addActionListener
            val draft = activeDraft()
            draft.sourceRef = selectedOpt.sourceRef
            draft.localField = selectedOpt.fieldName
            if (draft.allColumns.isNotEmpty() && !draft.preserveExistingSelectionOnFirstLoad) {
                suppressEditorEvents = true
                try {
                    autoMatchForeignField(draft)
                } finally {
                    suppressEditorEvents = false
                }
            }
            updateTypeMismatchWarning()
        }

        foreignFieldCombo.addActionListener {
            if (!suppressEditorEvents) {
                activeDraft().foreignField = currentForeignFieldText()
                updateTypeMismatchWarning()
            }
        }

        (foreignFieldCombo.editor?.editorComponent as? JTextComponent)?.document?.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (!suppressEditorEvents) {
                        activeDraft().foreignField = currentForeignFieldText()
                        updateTypeMismatchWarning()
                    }
                }
            }
        )

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

    private fun flushActiveTargetToDraft() {
        if (activeTargetIdx !in targetDrafts.indices) return
        val draft = activeDraft()
        val chosenTarget = currentTargetCollectionName()
        if (chosenTarget.isNotEmpty()) {
            draft.targetCollection = chosenTarget
        }
        (sourceKeyCombo.selectedItem as? SourceKeyOption)?.let { opt ->
            draft.sourceRef = opt.sourceRef
            draft.localField = opt.fieldName
        }
        draft.foreignField = currentForeignFieldText()
        draft.matchStrategy = if (latestOneRadio.isSelected) JoinMatchStrategy.LATEST_ONE else JoinMatchStrategy.UNWIND_ALL
        draft.excludeUnmatched = excludeUnmatchedCheckBox.isSelected

        // Split active condition rows back into baseConditionDrafts (LEFT) & draft.conditions (RIGHT)
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

    private fun rebuildTargetTabsBar() {
        targetTabsPanel.removeAll()
        val circledNums = arrayOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
        targetDrafts.forEachIndexed { idx, draft ->
            val num = circledNums.getOrElse(idx) { "#${idx + 1}" }
            val btn = JToggleButton("$num ${draft.targetCollection}", idx == activeTargetIdx).apply {
                margin = JBUI.insets(2, 8)
                if (idx == activeTargetIdx) {
                    font = font.deriveFont(Font.BOLD)
                }
                addActionListener {
                    if (idx == activeTargetIdx) {
                        isSelected = true
                        return@addActionListener
                    }
                    flushActiveTargetToDraft()
                    activeTargetIdx = idx
                    rebuildTargetTabsBar()
                    loadActiveDraftIntoEditor(triggerSchemaFetch = !targetDrafts[idx].schemaLoaded)
                    updateMultiUnwindWarning()
                }
            }
            targetTabsPanel.add(btn)
        }
        targetTabsPanel.add(addJoinBtn)
        removeJoinBtn.isEnabled = targetDrafts.size > 1
        targetTabsPanel.revalidate()
        targetTabsPanel.repaint()
    }

    private fun loadActiveDraftIntoEditor(triggerSchemaFetch: Boolean) {
        val draft = activeDraft()
        suppressEditorEvents = true
        try {
            targetCollectionCombo.selectedItem = draft.targetCollection
            rebuildSourceKeyCombo(activeTargetIdx, draft)

            foreignFieldModel.removeAllElements()
            val foreignPool = (draft.allColumns + draft.foreignField).filter { it.isNotBlank() }.distinct()
            foreignPool.forEach { foreignFieldModel.addElement(it) }
            foreignFieldCombo.selectedItem = draft.foreignField
            foreignFieldCombo.editor?.item = draft.foreignField
            (foreignFieldCombo.editor?.editorComponent as? JTextComponent)?.text = draft.foreignField

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

    private fun rebuildSourceKeyCombo(targetIdx: Int, draft: MutableTargetDraft) {
        sourceKeyModel.removeAllElements()
        val options = mutableListOf<SourceKeyOption>()
        for (lf in leftFields) {
            options.add(SourceKeyOption(JoinSourceRef.Base, sourceCollection, lf))
        }
        // Chain Join support: allow linking from ANY previous join target (0 until targetIdx)
        for (prevIdx in 0 until targetIdx) {
            val prev = targetDrafts[prevIdx]
            val prevFields = (prev.allColumns + prev.selectedColumns + prev.foreignField)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .ifEmpty { listOf("_id") }
            for (pf in prevFields) {
                options.add(SourceKeyOption(JoinSourceRef.PreviousTarget(prevIdx), prev.targetCollection, pf))
            }
        }
        options.forEach { sourceKeyModel.addElement(it) }

        val matched = options.firstOrNull {
            it.sourceRef == draft.sourceRef && it.fieldName == draft.localField
        } ?: options.firstOrNull {
            it.sourceRef is JoinSourceRef.Base && it.fieldName == draft.localField
        } ?: options.firstOrNull()

        if (matched != null) {
            sourceKeyCombo.selectedItem = matched
            draft.sourceRef = matched.sourceRef
            draft.localField = matched.fieldName
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
        val minH = JBUI.scale(56)
        val maxH = JBUI.scale(170)
        val desiredH = (conditionRows.size.coerceAtLeast(1) * rowHeightPx + JBUI.scale(12)).coerceIn(minH, maxH)
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

        // Fast path: cached schema already available
        val cached = schemaCache[targetColl]
        if (cached != null && !forceFetch) {
            applySampledSchemaToDraft(draftIndex, targetColl, cached, null)
            return
        }

        val reqSeq = if (isActive) schemaRequestSeq.incrementAndGet() else -1L
        if (isActive) {
            statusOrMismatchLabel.text = I18n.t("join.dialog.loadingTarget")
            statusOrMismatchLabel.foreground = UiHelpers.BSON_NULL_COLOR
            foreignFieldCombo.isEnabled = false
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
                        foreignFieldCombo.isEnabled = true
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
            }
        } else if (schema.documentCount == 0 || schema.fieldTypes.isEmpty()) {
            draft.lastError = null
            draft.isEmptyCollection = true
            draft.fieldCategories.clear()
            if (!restoring) {
                draft.allColumns.clear()
                draft.selectedColumns.clear()
                draft.foreignField = draft.localField.ifBlank { "_id" }
            }
        } else {
            draft.lastError = null
            draft.isEmptyCollection = false
            draft.fieldCategories.clear()
            draft.fieldCategories.putAll(schema.fieldTypes)

            val sampledKeys = schema.fieldTypes.keys.toList()
            if (restoring) {
                val merged = buildList {
                    addAll(sampledKeys)
                    addAll(draft.allColumns)
                    addAll(draft.selectedColumns)
                    add(draft.foreignField)
                    draft.conditions.forEach { add(it.field) }
                }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                draft.allColumns.clear()
                draft.allColumns.addAll(merged)
            } else {
                draft.allColumns.clear()
                draft.allColumns.addAll(sampledKeys)
                draft.selectedColumns.clear()
                draft.selectedColumns.addAll(sampledKeys)
                autoMatchForeignField(draft)
            }
        }

        if (draftIndex == activeTargetIdx) {
            suppressEditorEvents = true
            try {
                foreignFieldModel.removeAllElements()
                val pool = (draft.allColumns + draft.foreignField).filter { it.isNotBlank() }.distinct()
                pool.forEach { foreignFieldModel.addElement(it) }
                foreignFieldCombo.selectedItem = draft.foreignField
                foreignFieldCombo.editor?.item = draft.foreignField
                (foreignFieldCombo.editor?.editorComponent as? JTextComponent)?.text = draft.foreignField
            } finally {
                suppressEditorEvents = false
            }
            conditionRows.filter { it.currentTarget() == JoinConditionTarget.RIGHT }
                .forEach { it.refreshFieldChoices(preserveCurrentText = true) }
            updateTypeMismatchWarning()
        } else if (draftIndex < activeTargetIdx) {
            // If an earlier target finished loading, refresh active target's Chain Join sourceKeyCombo!
            suppressEditorEvents = true
            try {
                rebuildSourceKeyCombo(activeTargetIdx, activeDraft())
            } finally {
                suppressEditorEvents = false
            }
        }

        rebuildAllColumnCheckboxPanels()
    }

    private fun autoMatchForeignField(draft: MutableTargetDraft) {
        if (draft.allColumns.isEmpty()) return
        val local = draft.localField.trim()
        val snake = ReferenceJumpHelper.camelToSnake(local)
        val camel = ReferenceJumpHelper.snakeToCamel(local)

        val matched = when {
            local.isNotEmpty() && local != "_id" && local in draft.allColumns -> local
            snake.isNotEmpty() && snake != "_id" && snake in draft.allColumns -> snake
            camel.isNotEmpty() && camel != "_id" && camel in draft.allColumns -> camel
            "_id" in draft.allColumns -> "_id"
            else -> draft.allColumns.first()
        }
        draft.foreignField = matched
        if (draft === activeDraft()) {
            foreignFieldCombo.selectedItem = matched
            foreignFieldCombo.editor?.item = matched
            (foreignFieldCombo.editor?.editorComponent as? JTextComponent)?.text = matched
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

        val sourceOpt = sourceKeyCombo.selectedItem as? SourceKeyOption
        val localCat: String? = when (val ref = sourceOpt?.sourceRef) {
            is JoinSourceRef.Base -> sourceFieldCategories[sourceOpt.fieldName]
                ?: if (sourceOpt.fieldName == "_id") "ObjectId" else null
            is JoinSourceRef.PreviousTarget -> targetDrafts.getOrNull(ref.targetIndex)?.fieldCategories?.get(sourceOpt.fieldName)
                ?: if (sourceOpt.fieldName == "_id") "ObjectId" else null
            null -> null
        }
        val foreign = currentForeignFieldText()
        val foreignCat = draft.fieldCategories[foreign]

        if (localCat != null && foreignCat != null && localCat != foreignCat) {
            statusOrMismatchLabel.text = "⚠ ${I18n.t("join.dialog.typeMismatchWarning", localCat, foreignCat)}"
            statusOrMismatchLabel.foreground = UiHelpers.COLOR_ORANGE_FG
        } else {
            statusOrMismatchLabel.text = " "
        }
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

        // Panel 0: Base collection
        val basePanel = buildColumnPanelCard(
            title = I18n.t("join.dialog.colPanelBase", sourceCollection),
            fields = leftFields,
            selectedSet = selectedLeftSet,
            typeCategories = sourceFieldCategories
        )
        columnsRowPanel.add(basePanel)

        // Panels 1..N: Each Join Target
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
            minimumSize = Dimension(minW, JBUI.scale(150))
            preferredSize = Dimension(JBUI.scale(255), JBUI.scale(200))
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(listPanel), BorderLayout.CENTER)
        }
    }

    private fun adjustDialogSizeDynamically(initialOpen: Boolean) {
        val win = window ?: return
        val screen = ScreenUtil.getScreenRectangle(win)
        val maxW = (screen.width * 0.85).toInt().coerceAtLeast(JBUI.scale(680))
        val maxH = (screen.height * 0.85).toInt().coerceAtLeast(JBUI.scale(500))

        val desiredW = when {
            targetDrafts.size <= 1 -> JBUI.scale(800)
            else -> JBUI.scale(1060)
        }.coerceAtMost(maxW)

        val condExtraH = ((conditionRows.size - 1).coerceAtLeast(0) * JBUI.scale(32)).coerceAtMost(JBUI.scale(110))
        val desiredH = (JBUI.scale(600) + condExtraH).coerceAtMost(maxH)

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
        // 1. Multi-Join Target Tab Bar + Remove button + Multi-1:N warning
        val tabBarRow = JPanel(BorderLayout(6, 0)).apply {
            add(targetTabsPanel, BorderLayout.CENTER)
            add(removeJoinBtn, BorderLayout.EAST)
        }
        val topHeaderStack = JPanel(BorderLayout(0, 4)).apply {
            add(tabBarRow, BorderLayout.NORTH)
            add(multiUnwindWarningLabel, BorderLayout.SOUTH)
        }

        // 2. Match Key Form
        val matchForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("join.dialog.targetCollection")), targetCollectionCombo, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("join.dialog.sourceKeyLabel")), sourceKeyCombo, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("join.dialog.foreignField")), foreignFieldCombo, 1, false)
            .addComponent(statusOrMismatchLabel, 2)
            .panel

        // 3. Match Strategy Radios
        val strategyRow = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("join.dialog.strategySection")),
                JBUI.Borders.empty(2, 6)
            )
            add(latestOneRadio)
            add(unwindAllRadio)
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
                add(matchForm, BorderLayout.NORTH)
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

        val splitter = JBSplitter(true, 0.56f).apply {
            firstComponent = upperConfigPane
            secondComponent = lowerColumnsPane
        }

        rootContainerPanel.apply {
            border = JBUI.Borders.empty(6)
            preferredSize = Dimension(
                if (targetDrafts.size <= 1) JBUI.scale(780) else JBUI.scale(1020),
                JBUI.scale(580)
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
            if (draft.localField.isBlank()) {
                return ValidationInfo(I18n.t("join.dialog.validationFieldsRequired"), sourceKeyCombo)
            }
            if (draft.foreignField.isBlank()) {
                return ValidationInfo(
                    "#${idx + 1}: ${I18n.t("join.dialog.validationForeignFieldRequired")}",
                    foreignFieldCombo
                )
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

    fun buildJoinSpec(): JoinSpec {
        flushActiveTargetToDraft()

        val selectedLeft = leftFields.filter { it in selectedLeftSet }
        val builtTargets = targetDrafts.map { d ->
            val allCols = (d.allColumns + d.selectedColumns + d.foreignField)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val selectedCols = allCols.filter { it in d.selectedColumns }
            val cleanConds = d.conditions.filter {
                it.field.isNotBlank() && (!it.operator.requiresValue || it.rawValue.isNotBlank())
            }
            if (d.sourceRef is JoinSourceRef.Base) {
                ReferenceJumpHelper.recordSelection(database, d.localField, d.targetCollection)
            }
            JoinTargetSpec(
                targetCollection = d.targetCollection,
                sourceRef = d.sourceRef,
                localField = d.localField,
                foreignField = d.foreignField,
                matchStrategy = d.matchStrategy,
                allColumns = allCols,
                selectedColumns = selectedCols,
                conditions = cleanConds,
                excludeUnmatched = d.excludeUnmatched
            )
        }

        val cleanBaseConds = baseConditionDrafts.filter {
            it.field.isNotBlank() && (!it.operator.requiresValue || it.rawValue.isNotBlank())
        }

        return JoinSpec(
            leftCollection = sourceCollection,
            allLeftFields = leftFields,
            selectedLeftFields = selectedLeft,
            baseConditions = cleanBaseConds,
            targets = builtTargets
        )
    }
}
