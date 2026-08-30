package to.eyed.seeker.code.ui.editor.vim

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * A hardware key event as the keystroke name [VimState.handleKey] takes —
 * Zed's keystroke syntax (assets/keymaps/vim.json), with one difference
 * that keeps the state machine free of layouts: a shifted letter arrives
 * as the character it types (`A`), not as `shift-a`, because that is what a
 * soft keyboard commits and the two must mean the same thing.
 *
 * Null for a key the layer has no name for — a lone modifier, an Alt chord
 * — which the editor's own handler then sees as usual.
 */
internal fun vimKeystrokeOf(event: KeyEvent): String? {
    when (event.key) {
        Key.Escape -> return "escape"
        Key.Enter, Key.NumPadEnter -> return "enter"
        Key.Backspace -> return "backspace"
        Key.Tab -> return "tab"
        Key.Delete -> return "delete"
        Key.Insert -> return "insert"
        Key.DirectionLeft -> return "left"
        Key.DirectionRight -> return "right"
        Key.DirectionUp -> return "up"
        Key.DirectionDown -> return "down"
        Key.MoveHome -> return "home"
        Key.MoveEnd -> return "end"
        Key.PageUp -> return "pageup"
        Key.PageDown -> return "pagedown"
        else -> {}
    }
    if (event.isAltPressed) return null
    if (event.isCtrlPressed) {
        val letter = CTRL_KEYS[event.key] ?: return null
        return "ctrl-$letter"
    }
    val codePoint = event.utf16CodePoint
    if (codePoint < 32 || codePoint == 127) return null
    return String(Character.toChars(codePoint))
}

private val CTRL_KEYS: Map<Key, String> = mapOf(
    Key.A to "a", Key.B to "b", Key.C to "c", Key.D to "d", Key.E to "e", Key.F to "f",
    Key.G to "g", Key.H to "h", Key.I to "i", Key.J to "j", Key.K to "k", Key.L to "l",
    Key.M to "m", Key.N to "n", Key.O to "o", Key.P to "p", Key.Q to "q", Key.R to "r",
    Key.S to "s", Key.T to "t", Key.U to "u", Key.V to "v", Key.W to "w", Key.X to "x",
    Key.Y to "y", Key.Z to "z", Key.LeftBracket to "[", Key.RightBracket to "]",
)
