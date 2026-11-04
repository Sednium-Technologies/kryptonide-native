package com.sednium.kryptonide.ui.editor

import android.view.KeyEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.KeyBindingEvent

enum class IdeAction {
    SAVE,
    FIND,
    UNDO,
    REDO,
    DUPLICATE_LINE,
    DELETE_LINE,
    TOGGLE_COMMENT
}

data class KeyStroke(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false
)

data class KeymapActions(
    val onSave: () -> Unit = {},
    val onFind: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onDuplicateLine: () -> Unit = {},
    val onDeleteLine: () -> Unit = {},
    val onToggleComment: () -> Unit = {}
)

object KeymapManager {

    const val PRESET_VSCODE = "vscode"
    const val PRESET_SUBLIME = "sublime"

    // VS Code key bindings
    private val VSCODE_MAP: Map<KeyStroke, IdeAction> = mapOf(
        KeyStroke(KeyEvent.KEYCODE_S, ctrl = true) to IdeAction.SAVE,
        KeyStroke(KeyEvent.KEYCODE_F, ctrl = true) to IdeAction.FIND,
        KeyStroke(KeyEvent.KEYCODE_Z, ctrl = true, shift = false) to IdeAction.UNDO,
        KeyStroke(KeyEvent.KEYCODE_Y, ctrl = true) to IdeAction.REDO,
        KeyStroke(KeyEvent.KEYCODE_Z, ctrl = true, shift = true) to IdeAction.REDO,
        KeyStroke(KeyEvent.KEYCODE_DPAD_DOWN, alt = true, shift = true) to IdeAction.DUPLICATE_LINE,
        KeyStroke(KeyEvent.KEYCODE_D, ctrl = true, shift = true) to IdeAction.DUPLICATE_LINE,
        KeyStroke(KeyEvent.KEYCODE_K, ctrl = true, shift = true) to IdeAction.DELETE_LINE,
        KeyStroke(KeyEvent.KEYCODE_SLASH, ctrl = true) to IdeAction.TOGGLE_COMMENT
    )

    // Sublime Text key bindings
    private val SUBLIME_MAP: Map<KeyStroke, IdeAction> = mapOf(
        KeyStroke(KeyEvent.KEYCODE_S, ctrl = true) to IdeAction.SAVE,
        KeyStroke(KeyEvent.KEYCODE_F, ctrl = true) to IdeAction.FIND,
        KeyStroke(KeyEvent.KEYCODE_Z, ctrl = true, shift = false) to IdeAction.UNDO,
        KeyStroke(KeyEvent.KEYCODE_Y, ctrl = true) to IdeAction.REDO,
        KeyStroke(KeyEvent.KEYCODE_Z, ctrl = true, shift = true) to IdeAction.REDO,
        KeyStroke(KeyEvent.KEYCODE_D, ctrl = true, shift = true) to IdeAction.DUPLICATE_LINE,
        KeyStroke(KeyEvent.KEYCODE_K, ctrl = true, shift = true) to IdeAction.DELETE_LINE,
        KeyStroke(KeyEvent.KEYCODE_SLASH, ctrl = true) to IdeAction.TOGGLE_COMMENT
    )

    fun handleKeyBinding(
        event: KeyBindingEvent,
        preset: String,
        actions: KeymapActions
    ): Boolean {
        if (event.eventType != EditorKeyEvent.Type.DOWN) return false

        val stroke = KeyStroke(
            keyCode = event.keyCode,
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed
        )

        val map = when (preset.lowercase()) {
            PRESET_SUBLIME -> SUBLIME_MAP
            else -> VSCODE_MAP
        }

        val action = map[stroke] ?: return false

        when (action) {
            IdeAction.SAVE -> actions.onSave()
            IdeAction.FIND -> actions.onFind()
            IdeAction.UNDO -> actions.onUndo()
            IdeAction.REDO -> actions.onRedo()
            IdeAction.DUPLICATE_LINE -> actions.onDuplicateLine()
            IdeAction.DELETE_LINE -> actions.onDeleteLine()
            IdeAction.TOGGLE_COMMENT -> actions.onToggleComment()
        }

        return true
    }
}
