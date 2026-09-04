package to.eyed.thragg.ui.workspace

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed

/**
 * Which modifiers were down when a project-panel row was clicked — Zed's
 * `ctrl` toggles a mark and `shift` extends the range from the anchor
 * (project_panel.rs:1754-1760).
 *
 * Read off the *press* rather than the click: Compose's `clickable` reports no
 * modifiers at all, and the pointer event is where they still are.
 */
data class RowModifiers(val ctrl: Boolean = false, val shift: Boolean = false)

/**
 * The conversion, in a file of its own for one dull reason: the pointer
 * package's `isCtrlPressed`/`isShiftPressed` are extension properties whose
 * names collide with the key package's, and `ProjectPanel.kt` needs the key
 * ones for its own keyboard handler. Two imports of one name is a compile
 * error, so the pointer half lives here.
 */
fun rowModifiersOf(modifiers: PointerKeyboardModifiers): RowModifiers =
    RowModifiers(ctrl = modifiers.isCtrlPressed, shift = modifiers.isShiftPressed)
