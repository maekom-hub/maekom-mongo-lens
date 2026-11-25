package com.maekom.mongolens.ui

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

class MongoJsonEditor(
    private val projectRef: Project,
    initialText: String = "{}",
    private val multiLine: Boolean = false,
    private val fieldSuggestionsSupplier: () -> List<String> = { emptyList() },
    private val onExecuteShortcut: (() -> Unit)? = null
) : EditorTextField(
    EditorFactory.getInstance().createDocument(initialText),
    projectRef,
    resolveJsonFileType(),
    false,
    !multiLine
) {

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

        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val isCtrlOrMeta = (e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK)) != 0
                if (isCtrlOrMeta && e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    onExecuteShortcut?.invoke()
                    return
                }
                if (isCtrlOrMeta && e.keyCode == KeyEvent.VK_SPACE) {
                    e.consume()
                    showCompletionLookup(editor)
                }
            }

            override fun keyTyped(e: KeyEvent) {
                val ch = e.keyChar
                when (ch) {
                    '{' -> autoClosePair(editor, '}')
                    '[' -> autoClosePair(editor, ']')
                    '$' -> ApplicationManager.getApplication().invokeLater {
                        if (!editor.isDisposed) showCompletionLookup(editor)
                    }
                    '"' -> {
                        val offset = editor.caretModel.offset
                        val text = editor.document.text
                        val nextChar = text.getOrNull(offset)
                        if (nextChar != '"') {
                            autoClosePair(editor, '"')
                        }
                        ApplicationManager.getApplication().invokeLater {
                            if (!editor.isDisposed && fieldSuggestionsSupplier().isNotEmpty()) {
                                showCompletionLookup(editor)
                            }
                        }
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
                WriteCommandAction.runWriteCommandAction(projectRef) {
                    doc.insertString(offset, closingChar.toString())
                    editor.caretModel.moveToOffset(offset)
                }
            }
        }
    }

    private fun showCompletionLookup(editor: EditorEx) {
        if (projectRef.isDisposed || editor.isDisposed) return
        val docText = editor.document.text
        val offset = editor.caretModel.offset.coerceIn(0, docText.length)
        val prefix = extractPrefix(docText, offset)

        val sampledFields = fieldSuggestionsSupplier()
        val candidates = mutableListOf<LookupElementBuilder>()

        for (field in sampledFields) {
            if (prefix.isEmpty() || field.contains(prefix, ignoreCase = true)) {
                candidates.add(
                    LookupElementBuilder.create(field)
                        .withTypeText("field", true)
                        .withInsertHandler { context, _ ->
                            replacePrefix(context.document, offset, prefix, field)
                        }
                )
            }
        }

        for (op in MONGO_OPERATORS) {
            if (prefix.isEmpty() || op.contains(prefix, ignoreCase = true)) {
                candidates.add(
                    LookupElementBuilder.create(op)
                        .withTypeText("operator", true)
                        .bold()
                        .withInsertHandler { context, _ ->
                            replacePrefix(context.document, offset, prefix, op)
                        }
                )
            }
        }

        if (candidates.isNotEmpty()) {
            LookupManager.getInstance(projectRef).showLookup(editor, *candidates.take(40).toTypedArray())
        }
    }

    private fun extractPrefix(text: String, offset: Int): String {
        var i = offset - 1
        while (i >= 0 && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$' || text[i] == '.')) {
            i--
        }
        return text.substring(i + 1, offset)
    }

    private fun replacePrefix(document: com.intellij.openapi.editor.Document, originalOffset: Int, prefix: String, value: String) {
        // LookupManager already inserts value; adjust if '$' prefix duplicated
        val start = (originalOffset - prefix.length).coerceAtLeast(0)
        val currentEnd = (start + prefix.length + value.length).coerceAtMost(document.textLength)
        val segment = document.getText(com.intellij.openapi.util.TextRange(start, currentEnd))
        if (prefix.isNotEmpty() && segment == prefix + value) {
            document.deleteString(start, start + prefix.length)
        }
    }

    fun setPreferredSizeHeight(height: Int) {
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(200), height)
        minimumSize = Dimension(120, height.coerceAtMost(28))
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
