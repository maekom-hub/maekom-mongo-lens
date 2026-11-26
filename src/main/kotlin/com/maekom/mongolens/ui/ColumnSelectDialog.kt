package com.maekom.mongolens.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.i18n.I18n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class ColumnSelectDialog(
    project: Project,
    allColumns: List<String>,
    initiallySelectedColumns: Collection<String>
) : DialogWrapper(project) {

    private val orderedColumns: List<String> = allColumns.distinct()
    private val selectedSet: LinkedHashSet<String> = LinkedHashSet(initiallySelectedColumns)

    private val searchField = JBTextField().apply {
        emptyText.text = I18n.t("colSelect.dialog.searchPlaceholder")
    }
    private val selectAllBtn = JButton(I18n.t("colSelect.dialog.selectAll")).apply {
        margin = JBUI.insets(2, 8)
    }
    private val deselectAllBtn = JButton(I18n.t("colSelect.dialog.deselectAll")).apply {
        margin = JBUI.insets(2, 8)
    }
    private val countLabel = JBLabel("")

    private val checkBoxesByCol = linkedMapOf<String, JBCheckBox>()
    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 6)
    }

    init {
        title = I18n.t("colSelect.dialog.title")
        setOKButtonText(I18n.t("colSelect.dialog.applyAndRun"))

        for (col in orderedColumns) {
            val cb = JBCheckBox(col, col in selectedSet).apply {
                border = JBUI.Borders.empty(3, 2)
                addActionListener {
                    if (isSelected) selectedSet.add(col) else selectedSet.remove(col)
                    updateCountLabel()
                }
            }
            checkBoxesByCol[col] = cb
            listPanel.add(cb)
        }

        selectAllBtn.addActionListener {
            val visibleCols = visibleFilteredColumns()
            visibleCols.forEach { col ->
                selectedSet.add(col)
                checkBoxesByCol[col]?.isSelected = true
            }
            updateCountLabel()
        }

        deselectAllBtn.addActionListener {
            val visibleCols = visibleFilteredColumns()
            visibleCols.forEach { col ->
                selectedSet.remove(col)
                checkBoxesByCol[col]?.isSelected = false
            }
            updateCountLabel()
        }

        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilter()
            }
        })

        updateCountLabel()
        init()
    }

    private fun visibleFilteredColumns(): List<String> {
        val q = (searchField.text as String?)?.trim().orEmpty()
        if (q.isEmpty()) return orderedColumns
        return orderedColumns.filter { it.contains(q, ignoreCase = true) }
    }

    private fun applyFilter() {
        val q = (searchField.text as String?)?.trim().orEmpty()
        for ((col, cb) in checkBoxesByCol) {
            cb.isVisible = q.isEmpty() || col.contains(q, ignoreCase = true)
        }
        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun updateCountLabel() {
        countLabel.text = I18n.t("colSelect.dialog.selectedCount", selectedSet.size, orderedColumns.size)
        countLabel.foreground = if (selectedSet.isEmpty()) UiHelpers.COLOR_RED_FG else UiHelpers.BSON_NULL_COLOR
    }

    override fun createCenterPanel(): JComponent {
        val topControls = JPanel(BorderLayout(6, 4)).apply {
            add(searchField, BorderLayout.NORTH)
            val btnBar = JPanel(BorderLayout()).apply {
                val leftBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(selectAllBtn)
                    add(deselectAllBtn)
                }
                add(leftBtns, BorderLayout.WEST)
                add(countLabel, BorderLayout.EAST)
            }
            add(btnBar, BorderLayout.SOUTH)
        }

        val scrollPane = JBScrollPane(listPanel).apply {
            preferredSize = Dimension(380, 300)
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(4)
            add(topControls, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    override fun doValidate(): ValidationInfo? {
        if (selectedSet.isEmpty()) {
            return ValidationInfo(I18n.t("colSelect.dialog.minOneWarning"), listPanel)
        }
        return null
    }

    fun getSelectedColumns(): List<String> = orderedColumns.filter { it in selectedSet }

    fun isAllColumnsSelected(): Boolean = selectedSet.size == orderedColumns.size
}
