package to.eyed.seeker.code.ui.editor.vim

import to.eyed.seeker.code.ui.editor.EditorState

/**
 * What the vim layer needs from the world outside its buffer.
 *
 * Everything that is a caret move or an edit is done on the [EditorState]
 * directly; this is the rest — the workspace's save and close, the
 * language server's definition, the clipboard, and the engine's search. It is
 * an interface so the key handler can be driven on the host against a fake
 * buffer with a fake of this beside it, which is the only way the state
 * machine gets tested at all.
 */
interface VimHost {
    /** `:w`. False when there is nothing to save to. */
    fun save(): Boolean

    /**
     * `:q` — close the tab. With [force] false a dirty buffer must be
     * refused (the handler reports Vim's E37 itself); with it true the edits
     * go with the tab. Returns false if there is no tab to close.
     */
    fun closeTab(force: Boolean): Boolean

    /** `:wq` / `:x` — write, then close once the write has landed. */
    fun saveAndClose(): Boolean

    /** `:e path` — open a project-relative path in a tab. */
    fun openPath(path: String): Boolean

    /** `g d` — Zed binds it to `editor::GoToDefinition` (vim.json:99). */
    fun goToDefinition()

    /** `ctrl-o` / `ctrl-i` — Zed's `pane::GoBack` / `pane::GoForward`. */
    fun navigateBack()
    fun navigateForward()

    fun readClipboard(): String?
    fun writeClipboard(text: String)

    /**
     * Every match of [query] in the buffer, ascending, as rows and UTF-16
     * columns — the buffer search the find bar runs, so `/` finds exactly
     * what `Ctrl+F` would. Null when a regex does not compile.
     */
    fun search(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
    ): List<EditorState.SelectionRange>?
}
