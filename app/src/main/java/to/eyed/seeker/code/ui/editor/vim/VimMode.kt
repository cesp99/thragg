package to.eyed.seeker.code.ui.editor.vim

/**
 * The modes Zed's vim layer has (crates/vim/src/state.rs:44-54), minus the
 * two Helix ones this editor does not offer. The labels are Zed's own
 * `Display` strings (state.rs:56-67), which its mode indicator wraps as
 * `-- NORMAL --` (mode_indicator.rs:143).
 */
enum class VimMode(val label: String, val settingsKey: String) {
    Normal("NORMAL", "normal"),
    Insert("INSERT", "insert"),
    Replace("REPLACE", "replace"),
    Visual("VISUAL", "visual"),
    VisualLine("VISUAL LINE", "visual_line"),
    VisualBlock("VISUAL BLOCK", "visual_block");

    val isVisual: Boolean get() = this == Visual || this == VisualLine || this == VisualBlock

    companion object {
        /** Zed's `vim.default_mode` value; anything unknown is Normal. */
        fun fromSettingsKey(key: String): VimMode =
            entries.firstOrNull { it.settingsKey == key } ?: Normal
    }
}

/**
 * What the caret looks like, per mode — Zed's `Vim::cursor_shape`
 * (vim.rs:1397-1435): a block in normal and visual, a bar in insert, an
 * underline in replace and while a `f`/`t`/`r` waits for its character.
 */
enum class VimCursorShape { Block, Bar, Underline }

/** A buffer position: 0-based row, UTF-16 column. */
data class Pos(val row: Int, val col: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int =
        if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}
