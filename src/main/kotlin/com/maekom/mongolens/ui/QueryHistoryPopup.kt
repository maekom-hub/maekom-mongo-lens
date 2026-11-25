package com.maekom.mongolens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.config.SavedQuery
import com.maekom.mongolens.config.SavedQueryStore
import com.maekom.mongolens.i18n.I18n
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

object QueryHistoryPopup {

    fun show(
        project: Project,
        invoker: Component,
        savedQueryStore: SavedQueryStore,
        collection: String,
        mode: String?, // null = all modes, or "FIND"/"AGGREGATION"/"SHELL"
        onSaveCurrent: () -> Unit,
        onSelectQuery: (SavedQuery) -> Unit
    ) {
        var popupRef: JBPopup? = null

        val root = JPanel(BorderLayout(0, 4)).apply {
            preferredSize = Dimension(460, 340)
            border = JBUI.Borders.empty(6)
        }

        val savedListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        val historyListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        fun rebuildLists() {
            populateItems(
                container = savedListPanel,
                items = savedQueryStore.getBookmarks(collection, mode),
                onSelect = { q ->
                    popupRef?.cancel()
                    onSelectQuery(q)
                },
                onDelete = { q ->
                    savedQueryStore.deleteById(q.id)
                    rebuildLists()
                }
            )
            populateItems(
                container = historyListPanel,
                items = savedQueryStore.getHistory(collection, mode),
                onSelect = { q ->
                    popupRef?.cancel()
                    onSelectQuery(q)
                },
                onDelete = { q ->
                    savedQueryStore.deleteById(q.id)
                    rebuildLists()
                }
            )
        }

        // Top action bar: Save current query
        val saveBtn = JButton(I18n.t("find.savedQueries.save")).apply {
            margin = JBUI.insets(2, 8)
            addActionListener {
                popupRef?.cancel()
                onSaveCurrent()
            }
        }
        val topBar = JPanel(BorderLayout()).apply {
            add(saveBtn, BorderLayout.WEST)
        }

        // History tab header with Clear All button
        val clearHistoryBtn = JButton(I18n.t("find.savedQueries.clearHistory")).apply {
            margin = JBUI.insets(1, 6)
            addActionListener {
                val confirm = Messages.showYesNoDialog(
                    project,
                    I18n.t("find.savedQueries.clearHistoryConfirm"),
                    I18n.t("find.savedQueries.clearHistory"),
                    Messages.getWarningIcon()
                )
                if (confirm == Messages.YES) {
                    savedQueryStore.clearHistory(collection, mode)
                    rebuildLists()
                }
            }
        }
        val historyTabPanel = JPanel(BorderLayout(0, 4)).apply {
            val histTop = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
                add(clearHistoryBtn)
            }
            add(histTop, BorderLayout.NORTH)
            add(JBScrollPane(historyListPanel), BorderLayout.CENTER)
        }

        val tabbedPane = JBTabbedPane().apply {
            addTab(I18n.t("find.savedQueries.savedTab"), JBScrollPane(savedListPanel))
            addTab(I18n.t("find.savedQueries.historyTab"), historyTabPanel)
        }

        rebuildLists()

        root.add(topBar, BorderLayout.NORTH)
        root.add(tabbedPane, BorderLayout.CENTER)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(root, tabbedPane)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .setMovable(true)
            .setResizable(true)
            .createPopup()

        popupRef = popup
        popup.show(RelativePoint(invoker, Point(0, invoker.height + 2)))
    }

    private fun populateItems(
        container: JPanel,
        items: List<SavedQuery>,
        onSelect: (SavedQuery) -> Unit,
        onDelete: (SavedQuery) -> Unit
    ) {
        container.removeAll()
        if (items.isEmpty()) {
            container.add(JBLabel(I18n.t("find.savedQueries.empty")).apply {
                foreground = UiHelpers.BSON_NULL_COLOR
                border = JBUI.Borders.empty(14)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        } else {
            for (item in items) {
                container.add(createItemRow(item, onSelect, onDelete))
            }
            container.add(Box.createVerticalGlue())
        }
        container.revalidate()
        container.repaint()
    }

    private fun createItemRow(
        item: SavedQuery,
        onSelect: (SavedQuery) -> Unit,
        onDelete: (SavedQuery) -> Unit
    ): JPanel {
        val row = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor()),
                JBUI.Borders.empty(4, 6)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 46)
            alignmentX = JComponent.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val modeShort = when (item.mode) {
            "AGGREGATION" -> "AGG"
            "SHELL" -> "SH"
            else -> "FIND"
        }
        val modeBadge = UiHelpers.createBadgeLabel(modeShort, UiHelpers.COLOR_BLUE_BG, UiHelpers.COLOR_BLUE_FG)

        @Suppress("USELESS_ELVIS")
        val subtitleText = when (item.mode) {
            "AGGREGATION" -> (item.pipelineJson as String?).orEmpty().replace("\n", " ").trim().take(80)
            "SHELL" -> (item.shellScript as String?).orEmpty().replace("\n", " ").trim().take(80)
            else -> buildString {
                append((item.filter as String?).orEmpty().replace("\n", " ").trim().take(55))
                val s = (item.sort as String?)?.trim().orEmpty()
                if (s.isNotEmpty() && s != "{}") {
                    append(" | sort: ").append(s.take(25))
                }
            }
        }

        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(item.name.take(65)).apply {
                font = font.deriveFont(Font.BOLD)
            })
            if (subtitleText.isNotEmpty() && subtitleText != item.name) {
                add(JBLabel(subtitleText).apply {
                    foreground = UiHelpers.BSON_NULL_COLOR
                    font = font.deriveFont((font.size - 1).coerceAtLeast(10).toFloat())
                })
            }
        }

        val leftWrap = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(modeBadge, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
        }

        val clickAdapter = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onSelect(item)
            }
        }
        leftWrap.addMouseListener(clickAdapter)
        textPanel.addMouseListener(clickAdapter)

        val deleteBtn = JButton(AllIcons.Actions.Close).apply {
            rolloverIcon = AllIcons.Actions.CloseHovered
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            preferredSize = Dimension(22, 22)
            toolTipText = I18n.t("find.savedQueries.deleteTooltip")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onDelete(item) }
        }

        row.add(leftWrap, BorderLayout.CENTER)
        row.add(deleteBtn, BorderLayout.EAST)
        return row
    }
}
