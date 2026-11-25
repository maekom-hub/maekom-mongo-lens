package com.maekom.mongolens.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
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

class MongoJsonEditor(
    private val projectRef: Project,
    initialText: String = "{}",
    private val multiLine: Boolean = false,
    private val completionMode: CompletionMode = CompletionMode.GENERIC,
    private val fieldSuggestionsSupplier: () -> List<String> = { emptyList() },
    private val onExecuteShortcut: (() -> Unit)? = null
) : EditorTextField(
    EditorFactory.getInstance().createDocument(initialText),
    projectRef,
    resolveJsonFileType(),
    false,
    !multiLine
) {
    enum class CompletionMode {
        FILTER,
        SORT,
        GENERIC
    }

    private data class CompletionCandidate(
        val text: String,
        val kind: String // "field" or "operator"
    )

    private var activePopup: JBPopup? = null
    private val listModel = DefaultListModel<CompletionCandidate>()
    private val suggestionList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<CompletionCandidate>() {
            override fun customizeCellRenderer(
                list: JList<out CompletionCandidate>,
                value: CompletionCandidate?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                val style = if (value.kind == "operator") SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
                append(value.text, style)
                append("  ${value.kind}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private var suppressAutoPopup = false

    init {
        if (multiLine) {
            setOneLineMode(false)
        }
    }

    override fun createEditor(): EditorEx {
        val editor = super.createEditor()
        editor.settings.apply {
            isLineNumbersShown = multiLine
            isFoldingOutlineShown = multiLine
            isAutoCodeFoldingEnabled = multiLine
            isUseSoftWraps = true
            isRightMarginShown = false
            isHighlightSelectionOccurrences = true
            additionalLinesCount = if (multiLine) 1 else 0
            additionalColumnsCount = 2
        }

        suggestionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = suggestionList.locationToIndex(e.point)
                if (idx >= 0) {
                    suggestionList.selectedIndex = idx
                    acceptSelectedCompletion(editor)
                }
            }
        })

        // Priority key listener: when completion popup is visible, intercept navigation & confirm keys FIRST
        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val popup = activePopup
                if (popup != null && popup.isVisible) {
                    when (e.keyCode) {
                        KeyEvent.VK_DOWN -> {
                            e.consume()
                            val next = (suggestionList.selectedIndex + 1).coerceAtMost(listModel.size() - 1)
                            suggestionList.selectedIndex = next
                            suggestionList.ensureIndexIsVisible(next)
                            return
                        }
                        KeyEvent.VK_UP -> {
                            e.consume()
                            val prev = (suggestionList.selectedIndex - 1).coerceAtLeast(0)
                            suggestionList.selectedIndex = prev
                            suggestionList.ensureIndexIsVisible(prev)
                            return
                        }
                        KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                            e.consume()
                            acceptSelectedCompletion(editor)
                            return
                        }
                        KeyEvent.VK_ESCAPE -> {
                            e.consume()
                            dismissPopup()
                            return
                        }
                    }
                }

                val isCtrlOrMeta = (e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK)) != 0
                if (isCtrlOrMeta && e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    dismissPopup()
                    onExecuteShortcut?.invoke()
                    return
                }
                if (isCtrlOrMeta && e.keyCode == KeyEvent.VK_SPACE) {
                    e.consume()
                    triggerCompletionPopup(editor, forceShow = true)
                    return
                }
                // Single-line Filter/Sort: plain Enter executes query when popup is NOT open
                if (!multiLine && e.keyCode == KeyEvent.VK_ENTER && !isCtrlOrMeta && onExecuteShortcut != null) {
                    e.consume()
                    onExecuteShortcut.invoke()
                }
            }

            override fun keyTyped(e: KeyEvent) {
                val ch = e.keyChar
                when (ch) {
                    '{' -> autoClosePair(editor, '}')
                    '[' -> autoClosePair(editor, ']')
                    '"' -> {
                        val offset = editor.caretModel.offset
                        val text = editor.document.text
                        if (text.getOrNull(offset) != '"') {
                            autoClosePair(editor, '"')
                        }
                    }
                }
            }
        })

        editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                // Keep popup alive briefly if mouse clicked popup itself
                ApplicationManager.getApplication().invokeLater {
                    if (!editor.contentComponent.isFocusOwner) {
                        dismissPopup()
                    }
                }
            }
        })

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (suppressAutoPopup || !editor.contentComponent.isFocusOwner) return
                ApplicationManager.getApplication().invokeLater {
                    if (!editor.isDisposed && editor.contentComponent.isFocusOwner) {
                        triggerCompletionPopup(editor, forceShow = false)
                    }
                }
            }
        })

        return editor
    }

    private fun autoClosePair(editor: EditorEx, closingChar: Char) {
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || projectRef.isDisposed) return@invokeLater
            val offset = editor.caretModel.offset
            val doc = editor.document
            val nextChar = doc.text.getOrNull(offset)
            if (nextChar == null || nextChar.isWhitespace() || nextChar == ',' || nextChar == '}' || nextChar == ']') {
                suppressAutoPopup = true
                try {
                    WriteCommandAction.runWriteCommandAction(projectRef) {
                        doc.insertString(offset, closingChar.toString())
                        editor.caretModel.moveToOffset(offset)
                    }
                } finally {
                    suppressAutoPopup = false
                }
            }
        }
    }

    private fun triggerCompletionPopup(editor: EditorEx, forceShow: Boolean) {
        if (projectRef.isDisposed || editor.isDisposed) return
        val text = editor.document.text
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        val tokenInfo = extractCurrentToken(text, offset)
        val prefix = tokenInfo.token

        // Auto-show on typing at least 1 character (or '$') when in FILTER/SORT mode, or on Ctrl+Space
        if (!forceShow) {
            if (prefix.isEmpty()) {
                dismissPopup()
                return
            }
            if (completionMode == CompletionMode.GENERIC && !prefix.startsWith("$") && !tokenInfo.insideQuotes) {
                dismissPopup()
                return
            }
            if (!isLikelyKeyPosition(text, tokenInfo.tokenStart) && !prefix.startsWith("$")) {
                dismissPopup()
                return
            }
        }

        val candidates = buildRankedCandidates(prefix)
        if (candidates.isEmpty()) {
            dismissPopup()
            return
        }

        listModel.clear()
        candidates.take(25).forEach { listModel.addElement(it) }
        suggestionList.selectedIndex = 0
        suggestionList.visibleRowCount = candidates.size.coerceIn(1, 8)

        val current = activePopup
        if (current != null && current.isVisible) {
            current.pack(true, true)
            return
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(com.intellij.ui.components.JBScrollPane(suggestionList).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(260, (suggestionList.visibleRowCount * 22 + 8).coerceAtMost(200))
            }, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()

        activePopup = popup
        val caretPoint = editor.visualPositionToXY(editor.caretModel.visualPosition)
        val showPoint = Point(caretPoint.x, caretPoint.y + editor.lineHeight)
        popup.show(RelativePoint(editor.contentComponent, showPoint))
    }

    private fun buildRankedCandidates(prefix: String): List<CompletionCandidate> {
        val fields = fieldSuggestionsSupplier()
        val p = prefix.trim()

        val allFields = fields.map { CompletionCandidate(it, "field") }
        val allOps = if (completionMode == CompletionMode.SORT) {
            emptyList()
        } else {
            MONGO_OPERATORS.map { CompletionCandidate(it, "operator") }
        }

        if (p.isEmpty()) {
            return allFields + allOps
        }

        // Prefix matches first, then contains matches (fields prioritized over operators unless starts with '$')
        val pool = if (p.startsWith("$")) allOps + allFields else allFields + allOps
        val startsWithMatches = pool.filter { it.text.startsWith(p, ignoreCase = true) }
        val containsMatches = pool.filter {
            !it.text.startsWith(p, ignoreCase = true) && it.text.contains(p, ignoreCase = true)
        }
        return (startsWithMatches + containsMatches).distinctBy { it.text }
    }

    private fun acceptSelectedCompletion(editor: EditorEx) {
        val selected = suggestionList.selectedValue ?: return
        dismissPopup()

        if (projectRef.isDisposed || editor.isDisposed) return
        val doc = editor.document
        val text = doc.text
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        val info = extractCurrentToken(text, offset)

        var replaceStart = info.tokenStart
        var replaceEnd = offset

        // Consume surrounding quotes if user already typed `"sta` or `"sta"`
        if (replaceStart > 0 && text[replaceStart - 1] == '"') {
            replaceStart--
        }
        if (replaceEnd < text.length && text[replaceEnd] == '"') {
            replaceEnd++
        }

        val hasColonAfter = text.substring(replaceEnd).trimStart().startsWith(":")

        val (insertion, caretDelta, selectStartDelta, selectEndDelta) = when {
            hasColonAfter -> Quad("\"${selected.text}\"", selected.text.length + 2, -1, -1)
            completionMode == CompletionMode.SORT && selected.kind == "field" -> {
                val ins = "\"${selected.text}\": -1"
                Quad(ins, ins.length, ins.length - 2, ins.length)
            }
            completionMode == CompletionMode.FILTER && selected.kind == "field" -> {
                val ins = "\"${selected.text}\": "
                Quad(ins, ins.length, -1, -1)
            }
            selected.kind == "operator" -> {
                val ins = "\"${selected.text}\": "
                Quad(ins, ins.length, -1, -1)
            }
            else -> Quad("\"${selected.text}\"", selected.text.length + 2, -1, -1)
        }

        suppressAutoPopup = true
        try {
            WriteCommandAction.runWriteCommandAction(projectRef) {
                doc.replaceString(replaceStart, replaceEnd, insertion)
                editor.caretModel.moveToOffset(replaceStart + caretDelta)
                if (selectStartDelta >= 0 && selectEndDelta > selectStartDelta) {
                    editor.selectionModel.setSelection(replaceStart + selectStartDelta, replaceStart + selectEndDelta)
                }
            }
        } finally {
            suppressAutoPopup = false
        }
    }

    private data class Quad(val text: String, val caretDelta: Int, val selStart: Int, val selEnd: Int)

    private data class TokenInfo(
        val token: String,
        val tokenStart: Int,
        val insideQuotes: Boolean
    )

    private fun extractCurrentToken(text: String, offset: Int): TokenInfo {
        var i = offset - 1
        while (i >= 0 && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$' || text[i] == '.' || text[i] == '-')) {
            i--
        }
        val start = i + 1
        val insideQuotes = start > 0 && text[start - 1] == '"'
        return TokenInfo(text.substring(start, offset), start, insideQuotes)
    }

    private fun isLikelyKeyPosition(text: String, tokenStart: Int): Boolean {
        var idx = tokenStart - 1
        if (idx >= 0 && text[idx] == '"') idx--
        while (idx >= 0 && text[idx].isWhitespace()) idx--
        if (idx < 0) return true
        val prev = text[idx]
        return prev == '{' || prev == ','
    }

    fun dismissPopup() {
        activePopup?.let {
            if (!it.isDisposed) it.cancel()
        }
        activePopup = null
    }

    fun setPreferredSizeHeight(height: Int) {
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(140), height)
        minimumSize = Dimension(100, height.coerceAtMost(26))
    }

    companion object {
        fun resolveJsonFileType(): FileType {
            val jsonType = FileTypeManager.getInstance().getFileTypeByExtension("json")
            return if (jsonType.name == "UNKNOWN") PlainTextFileType.INSTANCE else jsonType
        }

        private val MONGO_OPERATORS = listOf(
            "\$eq", "\$ne", "\$in", "\$nin", "\$gt", "\$gte", "\$lt", "\$lte",
            "\$exists", "\$type", "\$regex", "\$options", "\$and", "\$or", "\$nor", "\$not",
            "\$elemMatch", "\$size", "\$all", "\$oid", "\$date", "\$numberLong", "\$numberDecimal",
            "\$match", "\$group", "\$project", "\$sort", "\$limit", "\$skip",
            "\$unwind", "\$lookup", "\$addFields", "\$count", "\$facet",
            "\$sum", "\$avg", "\$min", "\$max", "\$push", "\$addToSet", "\$first", "\$last"
        )
    }
}
