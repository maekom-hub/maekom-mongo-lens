package com.maekom.mongolens.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
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
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

class MongoJsonEditor(
    private val projectRef: Project,
    initialText: String = "",
    private val multiLine: Boolean = false,
    private val autoGrow: Boolean = false,
    private val maxVisibleLines: Int = 6,
    private val unifiedInputStyle: Boolean = false,
    private val placeholderText: String? = null,
    private val completionMode: CompletionMode = CompletionMode.GENERIC,
    private val fieldSuggestionsSupplier: () -> List<String> = { emptyList() },
    private val onExecuteShortcut: (() -> Unit)? = null,
    private val onAutoHeightChanged: (() -> Unit)? = null
) : EditorTextField(
    EditorFactory.getInstance().createDocument(StringUtil.convertLineSeparators(initialText)),
    projectRef,
    resolveJsonFileType(),
    false,
    !(multiLine || autoGrow)
) {
    enum class CompletionMode {
        FILTER,
        SORT,
        PROJECT,
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
    private var suppressReopenUntilNextTyping = false
    private var swallowNextControlCharTyped = false
    private var activePrefixStartOffset: Int = -1
    private var activePopupKeyDispatcher: KeyEventDispatcher? = null
    private var savedFocusTraversalKeysEnabled: Boolean? = null
    private val baseSingleLineHeight: Int = JBUI.scale(32)
    private var currentHeightPx: Int = baseSingleLineHeight

    init {
        if (multiLine || autoGrow) {
            setOneLineMode(false)
        }
        if (unifiedInputStyle || autoGrow) {
            applyUnifiedFixedDimensions(baseSingleLineHeight)
        }

        // Apply unified padding, vertical alignment, scrollbar rules, and gray placeholder via SettingsProvider
        addSettingsProvider { ed ->
            applyUnifiedEditorSettings(ed)
        }
    }

    /**
     * Common EditorEx configuration shared by Filter, Sort, and Project inputs so all three
     * have pixel-identical padding, 1-line height, vertical centering, and zero horizontal scrollbar intrusion.
     */
    private fun applyUnifiedEditorSettings(ed: EditorEx) {
        if (!placeholderText.isNullOrEmpty()) {
            ed.setPlaceholder(placeholderText)
            ed.setShowPlaceholderWhenFocused(true)
        }
        if (unifiedInputStyle || autoGrow) {
            ed.scrollPane.viewportBorder = JBUI.Borders.empty(5, 8)
            ed.setHorizontalScrollbarVisible(false)
            ed.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            ed.settings.apply {
                isLineNumbersShown = false
                isFoldingOutlineShown = false
                isAutoCodeFoldingEnabled = false
                additionalLinesCount = 0
                additionalColumnsCount = 0
                isRightMarginShown = false
                isUseSoftWraps = false
                isHighlightSelectionOccurrences = true
            }
        }
    }

    override fun getMinimumSize(): Dimension {
        if (unifiedInputStyle) {
            return Dimension(0, currentHeightPx)
        }
        return super.getMinimumSize()
    }

    override fun getPreferredSize(): Dimension {
        if (unifiedInputStyle) {
            return Dimension(0, currentHeightPx)
        }
        return super.getPreferredSize()
    }

    override fun getMaximumSize(): Dimension {
        if (unifiedInputStyle) {
            return Dimension(Int.MAX_VALUE, currentHeightPx)
        }
        return super.getMaximumSize()
    }

    fun safeText(): String = (text as String?)?.trim().orEmpty()

    fun refreshAutoHeightNow() {
        val ed = editor as? EditorEx
        if (ed != null && !ed.isDisposed) {
            updateAutoGrowHeight(ed)
        } else {
            val lines = document.lineCount.coerceAtLeast(1)
            val clamped = lines.coerceAtMost(maxVisibleLines.coerceAtLeast(1))
            val target = if (clamped <= 1) {
                baseSingleLineHeight
            } else {
                baseSingleLineHeight + (clamped - 1) * JBUI.scale(20)
            }
            applyUnifiedFixedDimensions(target)
            ApplicationManager.getApplication().invokeLater {
                val realized = editor as? EditorEx
                if (realized != null && !realized.isDisposed) {
                    applyUnifiedEditorSettings(realized)
                    updateAutoGrowHeight(realized)
                }
            }
        }
    }

    override fun createEditor(): EditorEx {
        val editor = super.createEditor()
        if (unifiedInputStyle || autoGrow) {
            applyUnifiedEditorSettings(editor)
        } else {
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
        }
        if (autoGrow) {
            editor.setVerticalScrollbarVisible(false)
            editor.scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            updateAutoGrowHeight(editor)
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

        // Register highest-priority component-scoped AnActions on editor.contentComponent so
        // IdeKeyEventDispatcher intercepts Up/Down/Enter/Tab/Escape BEFORE EditorEnter/EditorTab/EditorUp/EditorDown!
        registerPopupEditorActions(editor)

        // Fallback key listener for shortcuts when popup is closed & auto-pair closing
        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (isCompletionPopupActive()) {
                    // Handled at KeyboardFocusManager level before Editor actions & Tab traversal
                    if (e.keyCode == KeyEvent.VK_DOWN ||
                        e.keyCode == KeyEvent.VK_UP ||
                        e.keyCode == KeyEvent.VK_ENTER ||
                        e.keyCode == KeyEvent.VK_TAB ||
                        e.keyCode == KeyEvent.VK_ESCAPE
                    ) {
                        e.consume()
                        return
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
                    suppressReopenUntilNextTyping = false
                    triggerCompletionPopup(editor, forceShow = true)
                    return
                }
                // Single-line Sort/Project: plain Enter executes query when popup is NOT open and in oneLineMode
                if (isOneLineMode && e.keyCode == KeyEvent.VK_ENTER && !isCtrlOrMeta && onExecuteShortcut != null) {
                    e.consume()
                    onExecuteShortcut.invoke()
                }
            }

            override fun keyTyped(e: KeyEvent) {
                val ch = e.keyChar
                // Re-enable automatic completion once the user types the next actual character
                if (ch != KeyEvent.CHAR_UNDEFINED && !ch.isISOControl()) {
                    suppressReopenUntilNextTyping = false
                }
                when (ch) {
                    '{' -> autoClosePair(editor, '}')
                    '[' -> autoClosePair(editor, ']')
                    '"' -> {
                        val offset = editor.caretModel.offset
                        val text = (editor.document.text as String?).orEmpty()
                        if (text.getOrNull(offset) != '"') {
                            autoClosePair(editor, '"')
                        }
                    }
                }
            }
        })

        editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                ApplicationManager.getApplication().invokeLater {
                    if (!editor.contentComponent.isFocusOwner) {
                        dismissPopup()
                    }
                }
            }
        })

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (autoGrow) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!editor.isDisposed) {
                            updateAutoGrowHeight(editor)
                        }
                    }
                }
                if (suppressAutoPopup || suppressReopenUntilNextTyping || !editor.contentComponent.isFocusOwner) return
                ApplicationManager.getApplication().invokeLater {
                    if (!editor.isDisposed &&
                        editor.contentComponent.isFocusOwner &&
                        !suppressAutoPopup &&
                        !suppressReopenUntilNextTyping
                    ) {
                        triggerCompletionPopup(editor, forceShow = false)
                    }
                }
            }
        })

        return editor
    }

    private fun isCompletionPopupActive(): Boolean {
        val popup = activePopup ?: return false
        return !popup.isDisposed && popup.isVisible && listModel.size() > 0
    }

    /**
     * Registers component-scoped DumbAwareActions on `editor.contentComponent`.
     * Because IdeKeyEventDispatcher checks component shortcuts BEFORE global EditorEnter/EditorTab/EditorUp/EditorDown
     * actions, pressing Enter or Tab when the popup is visible NEVER inserts a newline or tab first!
     */
    private fun registerPopupEditorActions(editor: EditorEx) {
        fun registerKey(keyCode: Int, action: () -> Unit) {
            val anAction = object : DumbAwareAction() {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !editor.isDisposed && isCompletionPopupActive()
                }
                override fun actionPerformed(e: AnActionEvent) {
                    if (!editor.isDisposed && isCompletionPopupActive()) {
                        action()
                    }
                }
            }
            anAction.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(keyCode, 0)), editor.contentComponent)
        }

        registerKey(KeyEvent.VK_DOWN) { moveSelection(+1) }
        registerKey(KeyEvent.VK_UP) { moveSelection(-1) }
        registerKey(KeyEvent.VK_ENTER) {
            swallowNextControlCharTyped = true
            acceptSelectedCompletion(editor)
        }
        registerKey(KeyEvent.VK_TAB) {
            swallowNextControlCharTyped = true
            acceptSelectedCompletion(editor)
        }
        registerKey(KeyEvent.VK_ESCAPE) {
            swallowNextControlCharTyped = true
            dismissPopup()
        }
    }

    private fun moveSelection(delta: Int) {
        val count = listModel.size()
        if (count <= 0) return
        val cur = suggestionList.selectedIndex
        val next = when {
            delta > 0 -> if (cur < 0 || cur >= count - 1) 0 else cur + 1
            else -> if (cur <= 0) count - 1 else cur - 1
        }
        suggestionList.selectedIndex = next
        suggestionList.ensureIndexIsVisible(next)
    }

    /**
     * Secondary guard attached ONLY while the completion popup is visible:
     * prevents Swing's FocusManager from stealing Tab and swallows residual control KEY_TYPED events.
     */
    private fun installPopupKeyDispatcher(editor: EditorEx) {
        removePopupKeyDispatcher()
        if (savedFocusTraversalKeysEnabled == null && !editor.isDisposed) {
            savedFocusTraversalKeysEnabled = editor.contentComponent.focusTraversalKeysEnabled
            editor.contentComponent.focusTraversalKeysEnabled = false
        }
        val dispatcher = KeyEventDispatcher { e ->
            if (editor.isDisposed || !editor.contentComponent.isFocusOwner) {
                return@KeyEventDispatcher false
            }

            if (e.id == KeyEvent.KEY_TYPED && swallowNextControlCharTyped) {
                if (e.keyChar == '\n' || e.keyChar == '\r' || e.keyChar == '\t' || e.keyChar == '\u001B') {
                    swallowNextControlCharTyped = false
                    e.consume()
                    return@KeyEventDispatcher true
                }
                swallowNextControlCharTyped = false
                return@KeyEventDispatcher false
            }

            if (!isCompletionPopupActive() || e.id != KeyEvent.KEY_PRESSED) {
                return@KeyEventDispatcher false
            }

            val isCtrlOrMeta = (e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK)) != 0
            when (e.keyCode) {
                KeyEvent.VK_DOWN -> {
                    moveSelection(+1)
                    e.consume()
                    true
                }
                KeyEvent.VK_UP -> {
                    moveSelection(-1)
                    e.consume()
                    true
                }
                KeyEvent.VK_ENTER -> {
                    if (isCtrlOrMeta) {
                        dismissPopup()
                        e.consume()
                        onExecuteShortcut?.invoke()
                        true
                    } else {
                        swallowNextControlCharTyped = true
                        e.consume()
                        acceptSelectedCompletion(editor)
                        true
                    }
                }
                KeyEvent.VK_TAB -> {
                    swallowNextControlCharTyped = true
                    e.consume()
                    acceptSelectedCompletion(editor)
                    true
                }
                KeyEvent.VK_ESCAPE -> {
                    swallowNextControlCharTyped = true
                    e.consume()
                    dismissPopup()
                    true
                }
                else -> false
            }
        }
        activePopupKeyDispatcher = dispatcher
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    }

    private fun removePopupKeyDispatcher() {
        activePopupKeyDispatcher?.let { disp ->
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(disp)
        }
        activePopupKeyDispatcher = null
        savedFocusTraversalKeysEnabled?.let { prev ->
            (editor as? EditorEx)?.takeIf { !it.isDisposed }?.contentComponent?.focusTraversalKeysEnabled = prev
        }
        savedFocusTraversalKeysEnabled = null
        swallowNextControlCharTyped = false
    }

    private fun updateAutoGrowHeight(editor: EditorEx) {
        val maxLines = maxVisibleLines.coerceAtLeast(1)
        val lines = editor.document.lineCount.coerceAtLeast(1)
        val clampedLines = lines.coerceAtMost(maxLines)
        val lineH = editor.lineHeight.coerceIn(18, 24)
        val targetHeight = if (clampedLines <= 1) {
            baseSingleLineHeight
        } else {
            baseSingleLineHeight + (clampedLines - 1) * lineH
        }
        val needScrollbar = lines > maxLines
        editor.setVerticalScrollbarVisible(needScrollbar)
        editor.scrollPane.verticalScrollBarPolicy = if (needScrollbar) {
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        } else {
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }
        if (unifiedInputStyle) {
            editor.setHorizontalScrollbarVisible(false)
            editor.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        if (targetHeight != currentHeightPx) {
            applyUnifiedFixedDimensions(targetHeight)
            onAutoHeightChanged?.invoke()
        }
    }

    private fun applyUnifiedFixedDimensions(heightPx: Int) {
        currentHeightPx = heightPx
        if (unifiedInputStyle) {
            minimumSize = Dimension(0, heightPx)
            preferredSize = Dimension(0, heightPx)
            maximumSize = Dimension(Int.MAX_VALUE, heightPx)
        } else {
            minimumSize = Dimension(100, heightPx)
            preferredSize = Dimension(preferredSize.width.coerceAtLeast(140), heightPx)
            maximumSize = Dimension(Int.MAX_VALUE, heightPx)
        }
        revalidate()
        repaint()
    }

    private fun autoClosePair(editor: EditorEx, closingChar: Char) {
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || projectRef.isDisposed) return@invokeLater
            val offset = editor.caretModel.offset
            val doc = editor.document
            val nextChar = (doc.text as String?).orEmpty().getOrNull(offset)
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
        val text = (editor.document.text as String?).orEmpty()
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        val tokenInfo = extractCurrentToken(text, offset)
        val prefix = tokenInfo.token

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

        // Remember exact prefix start offset (including opening quote if present) for clean single-line replacement
        var prefixStart = tokenInfo.tokenStart
        if (prefixStart > 0 && text[prefixStart - 1] == '"') {
            prefixStart--
        }
        activePrefixStartOffset = prefixStart

        listModel.clear()
        candidates.take(25).forEach { listModel.addElement(it) }
        suggestionList.selectedIndex = 0
        suggestionList.ensureIndexIsVisible(0)
        suggestionList.visibleRowCount = candidates.size.coerceIn(1, 8)

        val current = activePopup
        if (current != null && !current.isDisposed && current.isVisible) {
            current.pack(true, true)
            if (activePopupKeyDispatcher == null) {
                installPopupKeyDispatcher(editor)
            }
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

        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (activePopup === popup) {
                    activePopup = null
                }
                removePopupKeyDispatcher()
            }
        })

        activePopup = popup
        installPopupKeyDispatcher(editor)
        val caretPoint = editor.visualPositionToXY(editor.caretModel.visualPosition)
        val showPoint = Point(caretPoint.x, caretPoint.y + editor.lineHeight)
        popup.show(RelativePoint(editor.contentComponent, showPoint))
    }

    private fun buildRankedCandidates(prefix: String): List<CompletionCandidate> {
        val fields = fieldSuggestionsSupplier()
        val p = prefix.trim()

        val allFields = fields.map { CompletionCandidate(it, "field") }
        val allOps = if (completionMode == CompletionMode.SORT || completionMode == CompletionMode.PROJECT) {
            emptyList()
        } else {
            MONGO_OPERATORS.map { CompletionCandidate(it, "operator") }
        }

        if (p.isEmpty()) {
            return allFields + allOps
        }

        val pool = if (p.startsWith("$")) allOps + allFields else allFields + allOps
        val startsWithMatches = pool.filter { it.text.startsWith(p, ignoreCase = true) }
        val containsMatches = pool.filter {
            !it.text.startsWith(p, ignoreCase = true) && it.text.contains(p, ignoreCase = true)
        }
        return (startsWithMatches + containsMatches).distinctBy { it.text }
    }

    private fun acceptSelectedCompletion(editor: EditorEx) {
        val selected = suggestionList.selectedValue ?: return
        val capturedPrefixStart = activePrefixStartOffset
        dismissPopup()

        if (projectRef.isDisposed || editor.isDisposed) return
        val doc = editor.document
        val text = (doc.text as String?).orEmpty()
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        val info = extractCurrentToken(text, offset)

        var replaceStart = info.tokenStart
        var replaceEnd = offset

        if (replaceStart > 0 && text[replaceStart - 1] == '"') {
            replaceStart--
        }
        // Prefer the captured prefixStartOffset when valid and on the same token span (e.g. `{cr}` -> `{"created_at": }`)
        if (capturedPrefixStart in 0..offset) {
            val spanBetween = text.substring(capturedPrefixStart, offset)
            if (!spanBetween.contains('\n') && !spanBetween.contains('{') && !spanBetween.contains('}') && !spanBetween.contains(',')) {
                replaceStart = minOf(replaceStart, capturedPrefixStart)
            }
        }
        if (replaceEnd < text.length && text[replaceEnd] == '"') {
            replaceEnd++
        }

        val hasColonAfter = text.substring(replaceEnd).trimStart().startsWith(":")
        val trimmedAll = text.trim()
        val isQueryBarMode = completionMode == CompletionMode.FILTER ||
            completionMode == CompletionMode.SORT ||
            completionMode == CompletionMode.PROJECT
        val isSortOrProject = completionMode == CompletionMode.SORT || completionMode == CompletionMode.PROJECT
        val needsOuterBraces = isQueryBarMode && !trimmedAll.startsWith("{")

        // Suppress automatic popup reopen right after confirmation until the user types the next character
        suppressReopenUntilNextTyping = true

        if (needsOuterBraces) {
            val (ins, caretIdx, selStart, selEnd) = when {
                isSortOrProject -> {
                    val s = "{ \"${selected.text}\": 1 }"
                    val oneStart = s.length - 3
                    Quad(s, oneStart + 1, oneStart, oneStart + 1)
                }
                selected.kind == "operator" && selected.text in ARRAY_VALUE_OPERATORS -> {
                    val s = "{ \"${selected.text}\": [] }"
                    Quad(s, s.length - 3, -1, -1)
                }
                else -> {
                    val s = "{ \"${selected.text}\":  }"
                    Quad(s, s.length - 2, -1, -1)
                }
            }
            suppressAutoPopup = true
            try {
                WriteCommandAction.runWriteCommandAction(projectRef) {
                    doc.replaceString(0, doc.textLength, ins)
                    editor.caretModel.moveToOffset(caretIdx)
                    if (selStart >= 0 && selEnd > selStart) {
                        editor.selectionModel.setSelection(selStart, selEnd)
                    }
                }
            } finally {
                suppressAutoPopup = false
            }
            return
        }

        val (insertion, caretDelta, selectStartDelta, selectEndDelta) = when {
            hasColonAfter -> Quad("\"${selected.text}\"", selected.text.length + 2, -1, -1)
            isSortOrProject && selected.kind == "field" -> {
                val ins = "\"${selected.text}\": 1"
                Quad(ins, ins.length, ins.length - 1, ins.length)
            }
            selected.kind == "operator" && selected.text in ARRAY_VALUE_OPERATORS -> {
                val ins = "\"${selected.text}\": []"
                Quad(ins, ins.length - 1, -1, -1)
            }
            isQueryBarMode && selected.kind == "field" -> {
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
        removePopupKeyDispatcher()
        activePrefixStartOffset = -1
        activePopup?.let {
            if (!it.isDisposed) it.cancel()
        }
        activePopup = null
    }

    override fun removeNotify() {
        dismissPopup()
        super.removeNotify()
    }

    fun setPreferredSizeHeight(height: Int) {
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(140), height)
        minimumSize = Dimension(100, height.coerceAtMost(32))
    }

    companion object {
        fun resolveJsonFileType(): FileType {
            val jsonType = FileTypeManager.getInstance().getFileTypeByExtension("json")
            return if (jsonType.name == "UNKNOWN") PlainTextFileType.INSTANCE else jsonType
        }

        private val ARRAY_VALUE_OPERATORS = setOf("\$in", "\$nin", "\$all", "\$and", "\$or", "\$nor")

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
