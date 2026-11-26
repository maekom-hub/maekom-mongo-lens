package com.maekom.mongolens.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.MongoConnectionProfile
import com.maekom.mongolens.i18n.I18n
import com.maekom.mongolens.mongo.JoinMatchStrategy
import com.maekom.mongolens.mongo.JoinSpec
import com.maekom.mongolens.mongo.MongoConnectionManager
import com.maekom.mongolens.mongo.ReferenceJumpHelper
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class JoinViewDialog(
    private val project: Project,
    private val connectionManager: MongoConnectionManager,
    private val profile: MongoConnectionProfile,
    private val database: String,
    private val sourceCollection: String,
    initialLocalField: String?,
    sourceTopFields: List<String>,
    private val sourceFieldCategories: Map<String, String>,
    allCollectionsInDb: List<String>
) : DialogWrapper(project) {

    private val leftFields: List<String> = sourceTopFields
        .map { it.substringBefore('.') }
        .distinct()
        .ifEmpty { listOf("_id") }

    private val candidateCollections: List<String> = ReferenceJumpHelper.rankCollections(
        database = database,
        currentCollection = sourceCollection,
        fieldPath = initialLocalField,
        allCollections = allCollectionsInDb.filter { it != sourceCollection }.ifEmpty { allCollectionsInDb }
    ).map { it.name }

    private val localFieldCombo = ComboBox(leftFields.toTypedArray()).apply {
        val cleanInitial = initialLocalField?.substringBefore('.')?.takeIf { it in leftFields }
            ?: leftFields.firstOrNull { it != "_id" && (it.endsWith("Id") || it.endsWith("_id")) }
            ?: leftFields.first()
        selectedItem = cleanInitial
    }

    private val targetCollectionCombo = ComboBox(candidateCollections.toTypedArray())
    private val foreignFieldModel = DefaultComboBoxModel<String>()
    private val foreignFieldCombo = ComboBox(foreignFieldModel)

    private val statusOrMismatchLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD)
    }

    private var rightFields: List<String> = emptyList()
    private var rightFieldCategories: Map<String, String> = emptyMap()

    private val leftCheckBoxes = linkedMapOf<String, JBCheckBox>()
    private val rightCheckBoxes = linkedMapOf<String, JBCheckBox>()

    private val leftListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }
    private val rightListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }
    private val rightHeaderLabel = JBLabel(I18n.t("join.dialog.rightColsHeader", "-")).apply {
        font = font.deriveFont(Font.BOLD)
    }

    private val latestOneRadio = JBRadioButton(I18n.t("join.dialog.strategyLatestOne"), true)
    private val unwindAllRadio = JBRadioButton(I18n.t("join.dialog.strategyUnwindAll"), false)

    private val sampleSeq = AtomicInteger(0)
    private var suppressForeignComboEvent = false

    init {
        title = "$sourceCollection — ${I18n.t("join.dialog.title")}"
        setOKButtonText(I18n.t("join.dialog.runInNewTab"))

        @Suppress("DEPRECATION")
        ComboboxSpeedSearch(targetCollectionCombo)
        @Suppress("DEPRECATION")
        ComboboxSpeedSearch(localFieldCombo)
        @Suppress("DEPRECATION")
        ComboboxSpeedSearch(foreignFieldCombo)

        ButtonGroup().apply {
            add(latestOneRadio)
            add(unwindAllRadio)
        }

        populateLeftCheckBoxes()
        wireListeners()
        init()

        // Immediately sample the pre-selected target collection in the background
        val firstTarget = targetCollectionCombo.selectedItem?.toString()
        if (!firstTarget.isNullOrBlank()) {
            loadTargetCollectionSchemaAsync(firstTarget)
        }
    }

    private fun populateLeftCheckBoxes() {
        leftCheckBoxes.clear()
        leftListPanel.removeAll()
        for (lf in leftFields) {
            val typeTag = sourceFieldCategories[lf]?.let { "  [$it]" }.orEmpty()
            val cb = JBCheckBox("$lf$typeTag", true).apply {
                border = JBUI.Borders.empty(2)
                addActionListener { refreshRightAliasPreviews() }
            }
            leftCheckBoxes[lf] = cb
            leftListPanel.add(cb)
        }
    }

    private fun wireListeners() {
        targetCollectionCombo.addActionListener {
            val target = targetCollectionCombo.selectedItem?.toString()?.trim().orEmpty()
            if (target.isNotEmpty()) {
                loadTargetCollectionSchemaAsync(target)
            }
        }

        localFieldCombo.addActionListener {
            autoMatchForeignField()
            updateTypeMismatchWarning()
        }

        foreignFieldCombo.addActionListener {
            if (!suppressForeignComboEvent) {
                updateTypeMismatchWarning()
            }
        }
    }

    private fun loadTargetCollectionSchemaAsync(targetColl: String) {
        val seq = sampleSeq.incrementAndGet()
        rightHeaderLabel.text = I18n.t("join.dialog.rightColsHeader", targetColl)
        statusOrMismatchLabel.text = I18n.t("join.dialog.loadingTarget")
        statusOrMismatchLabel.foreground = UiHelpers.BSON_NULL_COLOR

        ApplicationManager.getApplication().executeOnPooledThread {
            val sampledTypes: LinkedHashMap<String, String> = try {
                connectionManager.sampleCollectionTopLevelTypes(profile, database, targetColl, 20)
            } catch (_: Throwable) {
                linkedMapOf("_id" to "ObjectId")
            }

            ApplicationManager.getApplication().invokeLater {
                if (isDisposed || seq != sampleSeq.get()) return@invokeLater
                val fields = sampledTypes.keys.toList().ifEmpty { listOf("_id") }
                rightFields = fields
                rightFieldCategories = sampledTypes

                suppressForeignComboEvent = true
                try {
                    foreignFieldModel.removeAllElements()
                    fields.forEach { foreignFieldModel.addElement(it) }
                    autoMatchForeignField()
                } finally {
                    suppressForeignComboEvent = false
                }

                rebuildRightCheckBoxes()
                updateTypeMismatchWarning()
            }
        }
    }

    private fun autoMatchForeignField() {
        if (rightFields.isEmpty()) return
        val local = localFieldCombo.selectedItem?.toString()?.trim().orEmpty()
        val snake = ReferenceJumpHelper.camelToSnake(local)
        val camel = ReferenceJumpHelper.snakeToCamel(local)

        val matched = when {
            local.isNotEmpty() && local != "_id" && local in rightFields -> local
            snake.isNotEmpty() && snake != "_id" && snake in rightFields -> snake
            camel.isNotEmpty() && camel != "_id" && camel in rightFields -> camel
            "_id" in rightFields -> "_id"
            else -> rightFields.first()
        }
        foreignFieldCombo.selectedItem = matched
    }

    private fun rebuildRightCheckBoxes() {
        rightCheckBoxes.clear()
        rightListPanel.removeAll()
        for (rf in rightFields) {
            val cb = JBCheckBox(rf, true).apply {
                border = JBUI.Borders.empty(2)
            }
            rightCheckBoxes[rf] = cb
            rightListPanel.add(cb)
        }
        refreshRightAliasPreviews()
        rightListPanel.revalidate()
        rightListPanel.repaint()
    }

    private fun refreshRightAliasPreviews() {
        val targetColl = targetCollectionCombo.selectedItem?.toString()?.trim().orEmpty()
        val safePrefix = targetColl.replace('.', '_')
        val selectedLeftSet = leftCheckBoxes.filterValues { it.isSelected }.keys

        for ((rf, cb) in rightCheckBoxes) {
            val typeTag = rightFieldCategories[rf]?.let { "  [$it]" }.orEmpty()
            val cleanRight = rf.replace('.', '_')
            cb.text = if (rf in selectedLeftSet && safePrefix.isNotEmpty()) {
                "${I18n.t("join.dialog.aliasPreview", rf, "${safePrefix}_$cleanRight")}$typeTag"
            } else {
                "$rf$typeTag"
            }
        }
    }

    private fun updateTypeMismatchWarning() {
        val local = localFieldCombo.selectedItem?.toString()?.trim().orEmpty()
        val foreign = foreignFieldCombo.selectedItem?.toString()?.trim().orEmpty()
        val localCat = sourceFieldCategories[local] ?: if (local == "_id") "ObjectId" else null
        val foreignCat = rightFieldCategories[foreign]

        if (localCat != null && foreignCat != null && localCat != foreignCat) {
            statusOrMismatchLabel.text = "⚠ ${I18n.t("join.dialog.typeMismatchWarning", localCat, foreignCat)}"
            statusOrMismatchLabel.foreground = UiHelpers.COLOR_ORANGE_FG
        } else {
            statusOrMismatchLabel.text = " "
        }
    }

    override fun createCenterPanel(): JComponent {
        val topForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(I18n.t("join.dialog.targetCollection")), targetCollectionCombo, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("join.dialog.leftField", sourceCollection)), localFieldCombo, 1, false)
            .addLabeledComponent(JBLabel(I18n.t("join.dialog.foreignField")), foreignFieldCombo, 1, false)
            .addComponent(statusOrMismatchLabel, 4)
            .panel

        val leftColBox = buildColumnSelectionCard(
            headerComponent = JBLabel(I18n.t("join.dialog.leftColsHeader", sourceCollection)).apply {
                font = font.deriveFont(Font.BOLD)
            },
            listPanel = leftListPanel,
            onToggleAll = { checked ->
                leftCheckBoxes.values.forEach { it.isSelected = checked }
                refreshRightAliasPreviews()
            }
        )

        val rightColBox = buildColumnSelectionCard(
            headerComponent = rightHeaderLabel,
            listPanel = rightListPanel,
            onToggleAll = { checked ->
                rightCheckBoxes.values.forEach { it.isSelected = checked }
            }
        )

        val twoColSection = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("join.dialog.columnsSection")),
                JBUI.Borders.empty(4, 6)
            )
            val splitGrid = JPanel(GridLayout(1, 2, 10, 0)).apply {
                add(leftColBox)
                add(rightColBox)
            }
            add(splitGrid, BorderLayout.CENTER)
        }

        val strategyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("join.dialog.strategySection")),
                JBUI.Borders.empty(4, 6)
            )
            add(latestOneRadio)
            add(unwindAllRadio)
        }

        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(660, 490)
            border = JBUI.Borders.empty(6)
            add(topForm, BorderLayout.NORTH)
            add(twoColSection, BorderLayout.CENTER)
            add(strategyPanel, BorderLayout.SOUTH)
        }
    }

    private fun buildColumnSelectionCard(
        headerComponent: JBLabel,
        listPanel: JPanel,
        onToggleAll: (Boolean) -> Unit
    ): JPanel {
        val allBtn = JButton(I18n.t("colSelect.dialog.selectAll")).apply {
            margin = JBUI.insets(1, 5)
            addActionListener { onToggleAll(true) }
        }
        val noneBtn = JButton(I18n.t("colSelect.dialog.deselectAll")).apply {
            margin = JBUI.insets(1, 5)
            addActionListener { onToggleAll(false) }
        }
        val headerBar = JPanel(BorderLayout(4, 0)).apply {
            add(headerComponent, BorderLayout.CENTER)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                add(allBtn)
                add(noneBtn)
            }
            add(btns, BorderLayout.EAST)
        }
        return JPanel(BorderLayout(0, 4)).apply {
            add(headerBar, BorderLayout.NORTH)
            add(JBScrollPane(listPanel).apply { preferredSize = Dimension(290, 210) }, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        val target = targetCollectionCombo.selectedItem?.toString()?.trim().orEmpty()
        if (target.isEmpty()) {
            return ValidationInfo(I18n.t("join.dialog.validationTargetRequired"), targetCollectionCombo)
        }
        val local = localFieldCombo.selectedItem?.toString()?.trim().orEmpty()
        val foreign = foreignFieldCombo.selectedItem?.toString()?.trim().orEmpty()
        if (local.isEmpty() || foreign.isEmpty()) {
            return ValidationInfo(I18n.t("join.dialog.validationFieldsRequired"), foreignFieldCombo)
        }
        val anySelected = leftCheckBoxes.values.any { it.isSelected } || rightCheckBoxes.values.any { it.isSelected }
        if (!anySelected) {
            return ValidationInfo(I18n.t("join.dialog.validationColumnsRequired"), leftListPanel)
        }
        return null
    }

    fun buildJoinSpec(): JoinSpec {
        val target = targetCollectionCombo.selectedItem?.toString()?.trim().orEmpty()
        val local = localFieldCombo.selectedItem?.toString()?.trim().orEmpty()
        val foreign = foreignFieldCombo.selectedItem?.toString()?.trim().orEmpty()
        val selectedLeft = leftFields.filter { leftCheckBoxes[it]?.isSelected == true }
        val selectedRight = rightFields.filter { rightCheckBoxes[it]?.isSelected == true }
        val strategy = if (latestOneRadio.isSelected) JoinMatchStrategy.LATEST_ONE else JoinMatchStrategy.UNWIND_ALL

        ReferenceJumpHelper.recordSelection(database, local, target)

        return JoinSpec(
            leftCollection = sourceCollection,
            rightCollection = target,
            localField = local,
            foreignField = foreign,
            matchStrategy = strategy,
            allLeftFields = leftFields,
            allRightFields = rightFields,
            selectedLeftFields = selectedLeft,
            selectedRightFields = selectedRight
        )
    }
}
