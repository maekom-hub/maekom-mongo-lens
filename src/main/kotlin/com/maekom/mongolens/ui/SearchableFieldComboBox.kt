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
 * - Builds a detached `DefaultComboBoxModel` with `selectedItem = null` BEFORE attaching to `comboBox.model`
 *   so Swing never auto-selects item[0] or resets the editor caret to 0 during continuous typing.
 * - Captures and restores exact `savedText` and `savedCaret` both across model replacement and immediately
 *   after opening the dropdown popup (`isPopupVisible = true`).
 * - `isFiltering` and `isAdjustingSelection` guards prevent arrow keys (`↑`/`↓`), `Enter`, `Esc`, and
 *   mouse click selection from colliding with live filtering.
 */
class SearchableFieldComboBox<T : Any>(
    val comboBox: ComboBox<T>,
    @Suppress("UNUSED_PARAMETER")
    model: DefaultComboBoxModel<T>,
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

    private fun buildDetachedModel(items: List<T>): DefaultComboBoxModel<T> {
        val newModel = DefaultComboBoxModel<T>()
        for (item in items) {
            newModel.addElement(item)
        }
        // Prevent Swing from auto-selecting items[0] and overwriting editor text / resetting caret to 0
        newModel.selectedItem = null
        return newModel
    }

    /**
     * Replaces the master candidate list (e.g. when target collection changes or schema finishes loading)
     * while preserving the user's current text/selection safely.
     */
    fun updateMasterItems(newItems: List<T>, selectedItemOverride: Any? = null, preserveCurrentText: Boolean = true) {
        val savedText = if (preserveCurrentText) currentText() else ""
        masterItems = newItems.toList()
        runGuarded {
            val newModel = buildDetachedModel(masterItems)
            comboBox.model = newModel

            val targetValue: Any = selectedItemOverride ?: savedText
            val matched = masterItems.firstOrNull { it == targetValue || displayLabel(it) == targetValue.toString() }
            if (matched != null) {
                comboBox.selectedItem = matched
                comboBox.editor?.item = matched
                editorTextComponent()?.text = displayLabel(matched)
            } else {
                val text = targetValue.toString()
                comboBox.editor?.item = text
                editorTextComponent()?.text = text
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
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN,
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
                if (isFiltering) return
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
                SwingUtilities.invokeLater {
                    if (isFiltering || isAdjustingSelection) return@invokeLater
                    applyLiveFilter()
                }
            }
        })
    }

    private fun applyLiveFilter() {
        if (isFiltering || isAdjustingSelection) return
        val editorField = editorTextComponent() ?: return

        // Capture freshest typed text & caret position right before model replacement
        val savedText = editorField.text ?: ""
        val savedCaret = editorField.caretPosition
        val filtered = FieldSearchFilter.filterItems(
            masterItems = masterItems,
            query = savedText,
            primaryKey = primaryKey,
            displayLabel = displayLabel
        )

        isFiltering = true
        try {
            val newModel = buildDetachedModel(filtered)
            comboBox.model = newModel
            if (editorField.text != savedText) {
                editorField.text = savedText
            }
            editorField.caretPosition = savedCaret.coerceIn(0, editorField.text.length)
        } finally {
            isFiltering = false
        }

        if (!suppressNextAutoPopup && filtered.isNotEmpty() && editorField.isFocusOwner && comboBox.isShowing) {
            if (!comboBox.isPopupVisible) {
                isFiltering = true
                try {
                    comboBox.isPopupVisible = true
                } finally {
                    isFiltering = false
                }
            }
            // Re-pin focus & caret position after popup opens so continuous typing never jumps to index 0
            isFiltering = true
            try {
                editorField.requestFocusInWindow()
                if (editorField.text != savedText) {
                    editorField.text = savedText
                }
                editorField.caretPosition = savedCaret.coerceIn(0, editorField.text.length)
            } finally {
                isFiltering = false
            }
        } else if (filtered.isEmpty() && comboBox.isPopupVisible) {
            isFiltering = true
            try {
                comboBox.isPopupVisible = false
            } finally {
                isFiltering = false
            }
        }

        onTextOrSelectionChanged()
    }
}
