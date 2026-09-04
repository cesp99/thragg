package to.eyed.thragg.ui.terminal

import android.view.KeyEvent
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView

/**
 * What the terminal can be told to do to itself: clear, move through the
 * scrollback, hand over its selection.
 *
 * All of it goes through the vendored view's *public* surface. Its scroll
 * position (`mTopRow`) is package-private, so "scroll to the oldest row" is
 * spelled as pages of `Shift+PageUp` — the view already clamps that at the top
 * of the transcript — and "scroll to the newest" is the same call the view
 * makes whenever the shell prints.
 */

// com.termux.terminal.KeyHandler's modifier bits, which are package-private.
internal const val KEYMOD_ALT = -0x80000000
internal const val KEYMOD_CTRL = 0x40000000
internal const val KEYMOD_SHIFT = 0x20000000

/** Erase the display and the scrollback, as xterm's `ED 2` and `ED 3` do. */
private const val CLEAR_SEQUENCE = "\u001b[H\u001b[2J\u001b[3J"

/**
 * Clear the screen and the scrollback, the way Zed's `terminal: clear` does.
 *
 * Fed to the emulator rather than written to the shell: typing `clear` would
 * land in whatever program is running, and a program that reads stdin would
 * simply eat it.
 */
fun clearTerminal(view: TerminalView) {
    // Not while `less` or `vi` owns the screen. `ESC[3J` clears the *main*
    // buffer's transcript whichever buffer is active, so clearing from inside
    // a pager throws away the build log you are about to return to, and the
    // pager redraws as if nothing happened.
    val emulator = scrollableEmulator(view) ?: return
    val bytes = CLEAR_SEQUENCE.toByteArray()
    emulator.append(bytes, bytes.size)
    view.onScreenUpdated()
}

/** Back to the newest row, where the prompt is. */
fun scrollTerminalToBottom(view: TerminalView) {
    if (scrollableEmulator(view) == null) return
    view.onScreenUpdated()
}

/** To the oldest row the transcript still holds. */
fun scrollTerminalToTop(view: TerminalView) {
    val emulator = scrollableEmulator(view) ?: return
    val rows = emulator.mRows.coerceAtLeast(1)
    val pages = (emulator.screen.activeTranscriptRows + rows - 1) / rows
    repeat(pages) { scrollTerminalPage(view, up = true) }
}

/** One screenful of scrollback, in the direction asked for. */
fun scrollTerminalPage(view: TerminalView, up: Boolean) {
    if (scrollableEmulator(view) == null) return
    val keyCode = if (up) KeyEvent.KEYCODE_PAGE_UP else KeyEvent.KEYCODE_PAGE_DOWN
    view.handleKeyCode(keyCode, KEYMOD_SHIFT)
}

/**
 * The current selection, or the one the vendored view stashed when its toolbar
 * took over. Null when the user has selected nothing.
 */
fun terminalSelection(view: TerminalView): String? =
    (view.selectedText ?: view.storedSelectedText)?.takeIf { it.isNotEmpty() }

/**
 * The emulator, unless a full-screen program owns the screen.
 *
 * `less` and `vi` run on the alternate buffer, which has no scrollback behind
 * it; scrolling there would send arrow keys into the program instead, so we
 * leave it alone — as Zed does, which propagates the key rather than acting.
 */
private fun scrollableEmulator(view: TerminalView): TerminalEmulator? {
    val emulator = view.mEmulator ?: return null
    return if (emulator.isAlternateBufferActive) null else emulator
}
