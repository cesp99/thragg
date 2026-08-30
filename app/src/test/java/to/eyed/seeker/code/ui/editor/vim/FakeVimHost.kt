package to.eyed.seeker.code.ui.editor.vim

import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.editor.FakeEditorBuffer

/**
 * The world outside the buffer, faked: a clipboard that is a string, a
 * search that scans the [FakeEditorBuffer] the way the engine scans its
 * rope, and a log of what the workspace was asked to do — so a test can say
 * `:wq` and check that a save and a close were requested, in that order.
 */
internal class FakeVimHost(private val buffer: FakeEditorBuffer) : VimHost {
    var clipboard: String? = null
    val calls = ArrayList<String>()
    var dirty = false

    override fun save(): Boolean {
        calls.add("save")
        dirty = false
        return true
    }

    override fun closeTab(force: Boolean): Boolean {
        calls.add(if (force) "close!" else "close")
        return true
    }

    override fun saveAndClose(): Boolean {
        calls.add("save+close")
        return true
    }

    override fun openPath(path: String): Boolean {
        calls.add("open $path")
        return path.isNotEmpty()
    }

    override fun goToDefinition() {
        calls.add("definition")
    }

    override fun navigateBack() {
        calls.add("back")
    }

    override fun navigateForward() {
        calls.add("forward")
    }

    override fun readClipboard(): String? = clipboard

    override fun writeClipboard(text: String) {
        clipboard = text
    }

    override fun search(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
    ): List<EditorState.SelectionRange>? {
        val options = HashSet<RegexOption>()
        if (!caseSensitive) options.add(RegexOption.IGNORE_CASE)
        val pattern = try {
            val body = if (regex) query else Regex.escape(query)
            Regex(if (wholeWord) "\\b(?:$body)\\b" else body, options)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val rows = buffer.text.split('\n')
        val found = ArrayList<EditorState.SelectionRange>()
        for ((row, text) in rows.withIndex()) {
            for (m in pattern.findAll(text)) {
                if (m.value.isEmpty()) continue
                found.add(EditorState.SelectionRange(row, m.range.first, row, m.range.last + 1))
            }
        }
        return found
    }
}
