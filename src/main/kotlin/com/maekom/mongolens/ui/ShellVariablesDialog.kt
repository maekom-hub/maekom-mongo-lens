package com.maekom.mongolens.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.i18n.I18n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

class ShellVariablesDialog(
    project: Project,
    initialVars: Map<String, String>
) : DialogWrapper(project) {

    private val tableModel = DefaultTableModel(
        arrayOf(I18n.t("shell.variables.key"), I18n.t("shell.variables.value")),
        0
    )
    private val table = JBTable(tableModel).apply {
        rowHeight = 24
    }

    init {
        title = I18n.t("shell.variables.title")
        initialVars.forEach { (k, v) -> tableModel.addRow(arrayOf(k, v)) }
        if (tableModel.rowCount == 0) {
            tableModel.addRow(arrayOf("targetId", "65f1a2b3c4d5e6f708192a3b"))
            tableModel.addRow(arrayOf("targetStatus", "ACTIVE"))
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 6)).apply {
            preferredSize = Dimension(500, 280)
            border = JBUI.Borders.empty(6)
        }

        val desc = JBLabel("<html>${I18n.t("shell.variables.desc")}</html>").apply {
            foreground = UiHelpers.BSON_NULL_COLOR
        }

        val btnBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JButton(I18n.t("shell.variables.add")).apply {
                addActionListener {
                    tableModel.addRow(arrayOf("varName", ""))
                    val row = tableModel.rowCount - 1
                    table.setRowSelectionInterval(row, row)
                    table.editCellAt(row, 0)
                }
            })
            add(JButton(I18n.t("shell.variables.remove")).apply {
                addActionListener {
                    val row = table.selectedRow
                    if (row >= 0) {
                        if (table.isEditing) table.cellEditor?.stopCellEditing()
                        tableModel.removeRow(row)
                    }
                }
            })
        }

        root.add(desc, BorderLayout.NORTH)
        root.add(JBScrollPane(table), BorderLayout.CENTER)
        root.add(btnBar, BorderLayout.SOUTH)
        return root
    }

    fun getVariables(): LinkedHashMap<String, String> {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        val out = linkedMapOf<String, String>()
        for (i in 0 until tableModel.rowCount) {
            val k = tableModel.getValueAt(i, 0)?.toString()?.trim().orEmpty()
            val v = tableModel.getValueAt(i, 1)?.toString().orEmpty()
            if (k.isNotEmpty()) {
                out[k] = v
            }
        }
        return out
    }
}

class VariablePreviewDialog(
    project: Project,
    resolvedScript: String
) : DialogWrapper(project) {

    private val previewArea = JBTextArea(resolvedScript).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.empty(6)
    }

    init {
        title = I18n.t("shell.preview.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout(0, 6)).apply {
            preferredSize = Dimension(620, 340)
            border = JBUI.Borders.empty(6)
            add(JBLabel(I18n.t("shell.preview.desc")), BorderLayout.NORTH)
            add(JBScrollPane(previewArea), BorderLayout.CENTER)
        }
    }

    override fun createActions() = arrayOf(okAction)
}
