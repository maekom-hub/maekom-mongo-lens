package com.maekom.mongolens.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.maekom.mongolens.mongo.VariableResolver
import java.awt.Dimension
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

class MongoShellAutocomplete(
    private val project: Project,
    private val editor: EditorEx,
    private val collectionsSupplier: () -> List<String>,
    private val fieldsSupplier: () -> List<String>,
    private val userVariablesSupplier: () -> Map<String, String>,
    private val onExecuteShortcut: () -> Unit
) {
    private data class ShellCandidate(
        val display: String,
        val insertText: String,
        val kind: String,
        val replaceLength: Int,
        val caretBackOffset: Int = 0
    )

    private var activePopup: JBPopup? = null
    private var suppressListener = false
    private val listModel = DefaultListModel<ShellCandidate>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<ShellCandidate>() {
            override fun customizeCellRenderer(
                list: JList<out ShellCandidate>,
                value: ShellCandidate?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                append(value.display, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${value.kind}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    init {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx >= 0) {
                    list.selectedIndex = idx
                    acceptSelection()
                }
            }
        })

        // Priority key listener prevents conflicts between popup and editor Enter/Tab/Up/Down/Esc
        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val popup = activePopup
                if (popup != null && popup.isVisible) {
                    when (e.keyCode) {
                        KeyEvent.VK_DOWN -> {
                            e.consume()
                            val next = (list.selectedIndex + 1).coerceAtMost(listModel.size() - 1)
                            list.selectedIndex = next
                            list.ensureIndexIsVisible(next)
                            return
                        }
                        KeyEvent.VK_UP -> {
                            e.consume()
                            val prev = (list.selectedIndex - 1).coerceAtLeast(0)
                            list.selectedIndex = prev
                            list.ensureIndexIsVisible(prev)
                            return
                        }
                        KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                            e.consume()
                            acceptSelection()
                            return
                        }
                        KeyEvent.VK_ESCAPE -> {
                            e.consume()
                            dismiss()
                            return
                        }
                    }
                }

                val isCtrlOrMeta = (e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK)) != 0
                if (isCtrlOrMeta && e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    dismiss()
                    onExecuteShortcut()
                    return
                }
                if (isCtrlOrMeta && e.keyCode == KeyEvent.VK_SPACE) {
                    e.consume()
                    showSuggestions(force = true)
                }
            }
        })

        editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                ApplicationManager.getApplication().invokeLater {
                    if (!editor.contentComponent.isFocusOwner) dismiss()
                }
            }
        })

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (suppressListener || !editor.contentComponent.isFocusOwner) return
                ApplicationManager.getApplication().invokeLater {
                    if (!editor.isDisposed && editor.contentComponent.isFocusOwner) {
                        showSuggestions(force = false)
                    }
                }
            }
        })
    }

    private fun showSuggestions(force: Boolean) {
        if (project.isDisposed || editor.isDisposed) return
        val text = editor.document.text
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        val before = text.substring(0, offset)

        val candidates = computeCandidates(before, force)
        if (candidates.isEmpty()) {
            dismiss()
            return
        }

        listModel.clear()
        candidates.take(25).forEach { listModel.addElement(it) }
        list.selectedIndex = 0
        list.visibleRowCount = candidates.size.coerceIn(1, 8)

        val existing = activePopup
        if (existing != null && existing.isVisible) {
            existing.pack(true, true)
            return
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(300, (list.visibleRowCount * 22 + 8).coerceAtMost(210))
            }, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()

        activePopup = popup
        val xy = editor.visualPositionToXY(editor.caretModel.visualPosition)
        popup.show(RelativePoint(editor.contentComponent, Point(xy.x, xy.y + editor.lineHeight)))
    }

    private fun computeCandidates(before: String, force: Boolean): List<ShellCandidate> {
        // 1. Variable substitution: {{...
        val varMatch = Regex("""\{\{([$\w.-]*)$""").find(before)
        if (varMatch != null) {
            val typed = varMatch.groupValues[1]
            val replaceLen = varMatch.value.length
            val allVars = mutableListOf<Pair<String, String>>()
            VariableResolver.BUILTIN_DYNAMIC_VARS.forEach { (k, desc) -> allVars.add(k to desc) }
            userVariablesSupplier().forEach { (k, v) -> allVars.add(k to "var = ${v.take(24)}") }
            return rankFilter(allVars, typed) { it.first }.map { (name, desc) ->
                ShellCandidate("{{$name}}", "{{$name}}", desc, replaceLen)
            }
        }

        // 2. Chaining after `).`: .sort / .limit / .skip
        val chainMatch = Regex("""\)\s*\.(\w*)$""").find(before)
        if (chainMatch != null) {
            val typed = chainMatch.groupValues[1]
            val chains = listOf(
                Triple("sort({ \"_id\": -1 })", "sort({ \"_id\": -1 })", "chain"),
                Triple("limit(20)", "limit(20)", "chain"),
                Triple("skip(0)", "skip(0)", "chain"),
                Triple("count()", "count()", "chain")
            )
            return rankFilter(chains, typed) { it.first }.map { (disp, ins, kind) ->
                ShellCandidate(".$disp", ins, kind, typed.length)
            }
        }

        // 3. Method on db.<col>.<method>
        val methodMatch = Regex("""db\.(?:[A-Za-z0-9_$-]+|getCollection\([^)]+\))\.(\w*)$""").find(before)
        if (methodMatch != null) {
            val typed = methodMatch.groupValues[1]
            val methods = listOf(
                Triple("find({})", "find({})", 2),
                Triple("findOne({})", "findOne({})", 2),
                Triple("countDocuments({})", "countDocuments({})", 2),
                Triple("distinct(\"field\")", "distinct(\"\")", 2),
                Triple("aggregate([])", "aggregate([\n  { \"\$match\": {} }\n])", 4),
                Triple("updateOne({}, { \$set: {} })", "updateOne({}, { \"\$set\": {} })", 2),
                Triple("updateMany({}, { \$set: {} })", "updateMany({}, { \"\$set\": {} })", 2),
                Triple("deleteOne({})", "deleteOne({})", 2),
                Triple("insertOne({})", "insertOne({})", 2)
            )
            return rankFilter(methods, typed) { it.first }.map { (disp, ins, back) ->
                ShellCandidate(disp, ins, "method", typed.length, back)
            }
        }

        // 4. Collection name after db.<prefix>
        val collMatch = Regex("""\bdb\.([A-Za-z0-9_$-]*)$""").find(before)
        if (collMatch != null) {
            val typed = collMatch.groupValues[1]
            val colls = collectionsSupplier()
            return rankFilter(colls, typed) { it }.map { coll ->
                ShellCandidate(coll, coll, "collection", typed.length)
            }
        }

        // 5. MongoDB operator starting with '$'
        val opMatch = Regex("""(\$[A-Za-z]*)$""").find(before)
        if (opMatch != null) {
            val typed = opMatch.groupValues[1]
            return rankFilter(SHELL_OPERATORS, typed) { it }.map { op ->
                ShellCandidate(op, op, "operator", typed.length)
            }
        }

        // 6. Field names inside quotes or forced Ctrl+Space
        val fieldMatch = Regex(""""([A-Za-z0-9_.-]+)$""").find(before)
        if (fieldMatch != null) {
            val typed = fieldMatch.groupValues[1]
            return rankFilter(fieldsSupplier(), typed) { it }.map { field ->
                ShellCandidate(field, field, "field", typed.length)
            }
        }

        if (force) {
            return fieldsSupplier().map { ShellCandidate(it, "\"$it\"", "field", 0) } +
                SHELL_OPERATORS.map { ShellCandidate(it, "\"$it\"", "operator", 0) }
        }

        return emptyList()
    }

    private fun <T> rankFilter(items: List<T>, query: String, keySelector: (T) -> String): List<T> {
        if (query.isEmpty()) return items
        val starts = items.filter { keySelector(it).startsWith(query, ignoreCase = true) }
        val contains = items.filter {
            !keySelector(it).startsWith(query, ignoreCase = true) && keySelector(it).contains(query, ignoreCase = true)
        }
        return starts + contains
    }

    private fun acceptSelection() {
        val selected = list.selectedValue ?: return
        dismiss()
        if (project.isDisposed || editor.isDisposed) return

        val doc = editor.document
        val offset = editor.caretModel.offset.coerceIn(0, doc.textLength)
        val start = (offset - selected.replaceLength).coerceAtLeast(0)

        suppressListener = true
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                doc.replaceString(start, offset, selected.insertText)
                val targetCaret = (start + selected.insertText.length - selected.caretBackOffset)
                    .coerceIn(0, doc.textLength)
                editor.caretModel.moveToOffset(targetCaret)
            }
        } finally {
            suppressListener = false
        }
    }

    fun dismiss() {
        activePopup?.let {
            if (!it.isDisposed) it.cancel()
        }
        activePopup = null
    }

    companion object {
        private val SHELL_OPERATORS = listOf(
            "\$eq", "\$ne", "\$gt", "\$gte", "\$lt", "\$lte", "\$in", "\$nin",
            "\$and", "\$or", "\$nor", "\$not", "\$exists", "\$type", "\$regex", "\$options",
            "\$elemMatch", "\$size", "\$all", "\$set", "\$unset", "\$inc", "\$push", "\$pull",
            "\$match", "\$group", "\$project", "\$sort", "\$limit", "\$skip",
            "\$unwind", "\$lookup", "\$addFields", "\$count", "\$facet", "\$sum", "\$avg"
        )
    }
}
