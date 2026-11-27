package com.maekom.mongolens.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.maekom.mongolens.mongo.FieldSearchFilter
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.JTextComponent

/**
 * Reusable real-time searchable `ComboBox<T>` helper for Join configuration field selectors.
 *
 * Features:
 * - Live case-insensitive substring filtering with `startsWith` priority via `FieldSearchFilter`.
 * - Automatically opens dropdown popup when matches exist and the editor holds focus.
 * - `isFiltering` and `isAdjustingSelection` re-entrancy guards prevent keyboard arrow (`↑`/`↓`),
 *   `Enter`, `Esc`, and mouse click selection from colliding with model updates.
 * - Preserves typed text & caret position across model updates and allows typing custom nested paths
 *   not present in `masterItems` (e.g. `a_hts.sub.key`).
 */
class SearchableFieldComboBox<T : Any>(
    val comboBox: ComboBox<T>,
    private val model: DefaultComboBoxModel<T>,
    private val primaryKey: (T) -> String = { it.toString() },
    private val displayLabel: (T) -> String = { it.toString() },
    private val onTextOrSelectionChanged: () -> Unit = {}
) {
    private var masterItems: List<T> = emptyList()
    private var isFiltering: Boolean = false
    private var isAdjustingSelection: Boolean = false
    private var suppressNextAutoPopup: Boolean = false

    init {
        comboBox.isEditable = true
        installHandlers()
    }

    private fun editorTextComponent(): JTextComponent? =
        comboBox.editor?.editorComponent as? JTextComponent

    fun currentText(): String =
        editorTextComponent()?.text ?: comboBox.selectedItem?.toString().orEmpty()

    /**
     * Replaces the master candidate list (e.g. when target collection changes or schema finishes loading)
     * while preserving the user's current text/selection safely.
     */
    fun updateMasterItems(newItems: List<T>, selectedItemOverride: Any? = null, preserveCurrentText: Boolean = true) {
        val savedText = if (preserveCurrentText) currentText() else ""
        masterItems = newItems.toList()
        runGuarded {
            model.removeAllElements()
            for (item in masterItems) {
                model.addElement(item)
            }
            val targetValue = selectedItemOverride ?: savedText
            if (targetValue != null) {
                val matched = masterItems.firstOrNull { it == targetValue || displayLabel(it) == targetValue.toString() }
                if (matched != null) {
                    comboBox.selectedItem = matched
                    comboBox.editor?.item = matched
                    editorTextComponent()?.text = displayLabel(matched)
                } else {
                    val text = targetValue.toString()
                    comboBox.selectedItem = text
                    comboBox.editor?.item = text
                    editorTextComponent()?.text = text
                }
            }
        }
    }

    /**
     * Programmatically sets the editor text without triggering re-filtering or auto-popup.
     */
    fun setTextProgrammatically(text: String) {
        runGuarded {
            val matched = masterItems.firstOrNull { displayLabel(it) == text || primaryKey(it) == text }
            if (matched != null) {
                comboBox.selectedItem = matched
                comboBox.editor?.item = matched
                editorTextComponent()?.text = displayLabel(matched)
            } else {
                comboBox.selectedItem = text
                comboBox.editor?.item = text
                editorTextComponent()?.text = text
            }
        }
    }

    private inline fun runGuarded(block: () -> Unit) {
        val prevFiltering = isFiltering
        val prevAdjusting = isAdjustingSelection
        isFiltering = true
        isAdjustingSelection = true
        try {
            block()
        } finally {
            isFiltering = prevFiltering
            isAdjustingSelection = prevAdjusting
        }
    }

    private fun installHandlers() {
        val textComp = editorTextComponent()

        textComp?.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN -> {
                        // Guard so Swing's popup navigation updating editor text never re-filters the list
                        isAdjustingSelection = true
                    }
                    KeyEvent.VK_ENTER -> {
                        isAdjustingSelection = true
                        suppressNextAutoPopup = true
                    }
                    KeyEvent.VK_ESCAPE -> {
                        isAdjustingSelection = true
                        suppressNextAutoPopup = true
                        if (comboBox.isPopupVisible) {
                            comboBox.isPopupVisible = false
                        }
                    }
                    else -> {
                        isAdjustingSelection = false
                        suppressNextAutoPopup = false
                    }
                }
            }

            override fun keyReleased(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN -> {
                        SwingUtilities.invokeLater { isAdjustingSelection = false }
                    }
                    KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE -> {
                        SwingUtilities.invokeLater { isAdjustingSelection = false }
                    }
                }
            }
        })

        comboBox.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = Unit

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {
                // Mouse click or Enter committing an item from the dropdown
                isAdjustingSelection = true
                suppressNextAutoPopup = true
                SwingUtilities.invokeLater {
                    isAdjustingSelection = false
                    onTextOrSelectionChanged()
                }
            }

            override fun popupMenuCanceled(e: PopupMenuEvent?) {
                isAdjustingSelection = false
            }
        })

        comboBox.addActionListener {
            if (isFiltering) return@addActionListener
            isAdjustingSelection = true
            try {
                onTextOrSelectionChanged()
            } finally {
                SwingUtilities.invokeLater { isAdjustingSelection = false }
            }
        }

        textComp?.document?.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (isFiltering || isAdjustingSelection) return
                val typed = textComp.text ?: ""
                val caretPos = textComp.caretPosition
                SwingUtilities.invokeLater {
                    if (isFiltering || isAdjustingSelection) return@invokeLater
                    applyLiveFilter(typed, caretPos)
                }
            }
        })
    }

    private fun applyLiveFilter(query: String, caretPos: Int) {
        if (isFiltering || isAdjustingSelection) return
        val textComp = editorTextComponent() ?: return
        val filtered = FieldSearchFilter.filterItems(
            masterItems = masterItems,
            query = query,
            primaryKey = primaryKey,
            displayLabel = displayLabel
        )

        isFiltering = true
        try {
            model.removeAllElements()
            for (item in filtered) {
                model.addElement(item)
            }
            // Keep exact user-typed text (even if custom nested path not in masterItems) and restore caret
            comboBox.editor?.item = query
            if (textComp.text != query) {
                textComp.text = query
            }
            val safeCaret = caretPos.coerceIn(0, textComp.text.length)
            textComp.caretPosition = safeCaret
        } finally {
            isFiltering = false
        }

        if (!suppressNextAutoPopup && filtered.isNotEmpty() && textComp.isFocusOwner && comboBox.isShowing) {
            if (!comboBox.isPopupVisible) {
                comboBox.isPopupVisible = true
            }
        } else if (filtered.isEmpty() && comboBox.isPopupVisible) {
            comboBox.isPopupVisible = false
        }

        onTextOrSelectionChanged()
    }
}
