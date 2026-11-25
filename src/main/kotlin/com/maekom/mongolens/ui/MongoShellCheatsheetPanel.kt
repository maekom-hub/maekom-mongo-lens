package com.maekom.mongolens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.i18n.I18n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MongoShellCheatsheetPanel(
    private val project: Project,
    private val collectionName: String,
    parentDisposable: Disposable,
    private val onInsertSnippet: (String) -> Unit
) : JPanel(BorderLayout()) {

    private data class CheatItem(
        val titleKey: String,
        val descKey: String,
        val syntax: String,
        val exampleTemplate: String,
        val keywords: String
    )

    private val items = listOf(
        CheatItem(
            titleKey = "shell.cheat.1.title",
            descKey = "shell.cheat.1.desc",
            syntax = "db.<col>.find(filter, projection).sort(sort).skip(n).limit(m)",
            exampleTemplate = """db.<col>.find(
  { "status": "PAID" },
  { "_id": 1, "orderNo": 1, "amount": 1 }
).sort({ "createdAt": -1 }).skip(0).limit(20)""",
            keywords = "find projection sort skip limit chain 체이닝 기본 조회 정렬"
        ),
        CheatItem(
            titleKey = "shell.cheat.2.title",
            descKey = "shell.cheat.2.desc",
            syntax = "\$eq | \$ne | \$gt | \$gte | \$lt | \$lte | \$in | \$nin",
            exampleTemplate = """db.<col>.find({
  "amount": { "${'$'}gte": 10000, "${'$'}lt": 50000 },
  "status": { "${'$'}in": ["PAID", "SHIPPED"] }
}).limit(20)""",
            keywords = "eq ne gt gte lt lte in nin 비교 연산자 범위 comparison"
        ),
        CheatItem(
            titleKey = "shell.cheat.3.title",
            descKey = "shell.cheat.3.desc",
            syntax = "\$and | \$or | \$exists | \$type | \$regex | \$elemMatch",
            exampleTemplate = """db.<col>.find({
  "${'$'}or": [
    { "email": { "${'$'}regex": "@maekom\\.io${'$'}", "${'$'}options": "i" } },
    { "items": { "${'$'}elemMatch": { "qty": { "${'$'}gte": 2 } } } }
  ],
  "deletedAt": { "${'$'}exists": false }
}).limit(20)""",
            keywords = "and or exists type regex elemMatch 논리 요소 문자열 정규식 logical"
        ),
        CheatItem(
            titleKey = "shell.cheat.4.title",
            descKey = "shell.cheat.4.desc",
            syntax = "ObjectId(\"24hex\") | ISODate(\"ISO-8601\") | NumberLong(n) | NumberDecimal(\"n\")",
            exampleTemplate = """db.<col>.find({
  "_id": ObjectId("65f1a2b3c4d5e6f708192a3b"),
  "createdAt": { "${'$'}gte": ISODate("2026-01-01T00:00:00.000Z") },
  "seq": NumberLong(100001),
  "price": NumberDecimal("19900.00")
})""",
            keywords = "ObjectId ISODate NumberLong NumberDecimal bson 타입 헬퍼 date"
        ),
        CheatItem(
            titleKey = "shell.cheat.5.title",
            descKey = "shell.cheat.5.desc",
            syntax = "{{varName}} | \"{{varName}}\" | {{\$now}} | {{\$today}} | {{\$objectId}}",
            exampleTemplate = """db.<col>.find({
  "_id": {{targetId}},
  "status": "{{targetStatus}}",
  "createdAt": { "${'$'}gte": {{${'$'}today}}, "${'$'}lte": {{${'$'}now}} }
}).limit(20)""",
            keywords = "var variable now today objectId 변수 치환 동적 전역 dynamic"
        )
    )

    private val titleLabel = JBLabel(I18n.t("shell.cheatsheet.title")).apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = I18n.t("shell.cheatsheet.search")
    }
    private val cardsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        val top = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.ToolWindow.borderColor()),
                JBUI.Borders.empty(6, 8)
            )
            add(titleLabel, BorderLayout.NORTH)
            add(searchField, BorderLayout.SOUTH)
        }

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(cardsContainer), BorderLayout.CENTER)

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = rebuildCards()
            override fun removeUpdate(e: DocumentEvent?) = rebuildCards()
            override fun changedUpdate(e: DocumentEvent?) = rebuildCards()
        })

        rebuildCards()
        I18n.addListener(parentDisposable) { refreshTexts() }
    }

    private fun rebuildCards() {
        val query = searchField.text.trim().lowercase()
        cardsContainer.removeAll()

        val filtered = items.filter { item ->
            if (query.isEmpty()) true
            else {
                I18n.t(item.titleKey).lowercase().contains(query) ||
                    I18n.t(item.descKey).lowercase().contains(query) ||
                    item.syntax.lowercase().contains(query) ||
                    item.exampleTemplate.lowercase().contains(query) ||
                    item.keywords.lowercase().contains(query)
            }
        }

        for (item in filtered) {
            cardsContainer.add(buildCard(item))
        }
        cardsContainer.add(Box.createVerticalGlue())
        cardsContainer.revalidate()
        cardsContainer.repaint()
    }

    private fun buildCard(item: CheatItem): JPanel {
        val exampleCode = item.exampleTemplate.replace("<col>", collectionName)
        val card = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(4, 6),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, true),
                    JBUI.Borders.empty(6, 8)
                )
            )
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 260)
        }

        val header = JPanel(BorderLayout(4, 0)).apply {
            add(JBLabel(I18n.t(item.titleKey)).apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.CENTER)

            val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(JButton(I18n.t("shell.cheatsheet.copy")).apply {
                    margin = JBUI.insets(1, 6)
                    addActionListener {
                        UiHelpers.copyToClipboard(exampleCode)
                        UiHelpers.notify(project, I18n.t("result.copied"))
                    }
                })
                add(JButton(I18n.t("shell.cheatsheet.insert")).apply {
                    margin = JBUI.insets(1, 6)
                    addActionListener { onInsertSnippet(exampleCode) }
                })
            }
            add(btns, BorderLayout.EAST)
        }

        val infoStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("<html>${I18n.t(item.descKey)}</html>").apply {
                foreground = UiHelpers.BSON_NULL_COLOR
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(3))
            add(JBLabel(item.syntax.replace("<col>", collectionName)).apply {
                font = Font(Font.MONOSPACED, Font.BOLD, 11)
                foreground = UiHelpers.COLOR_BLUE_FG
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }

        val codeArea = JBTextArea(exampleCode).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            lineWrap = false
            border = JBUI.Borders.empty(4)
        }

        val centerWrap = JPanel(BorderLayout(0, 4)).apply {
            add(infoStack, BorderLayout.NORTH)
            add(codeArea, BorderLayout.CENTER)
        }

        card.add(header, BorderLayout.NORTH)
        card.add(centerWrap, BorderLayout.CENTER)
        return card
    }

    private fun refreshTexts() {
        SwingUtilities.invokeLater {
            titleLabel.text = I18n.t("shell.cheatsheet.title")
            searchField.textEditor.emptyText.text = I18n.t("shell.cheatsheet.search")
            rebuildCards()
        }
    }
}
