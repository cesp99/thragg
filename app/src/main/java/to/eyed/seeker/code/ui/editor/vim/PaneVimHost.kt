package to.eyed.seeker.code.ui.editor.vim

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import to.eyed.seeker.code.core.SearchQuery
import to.eyed.seeker.code.core.searchBuffer
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.search.rangeOf

/**
 * The [VimHost] an editor pane gives its vim layer. Its callbacks are plain
 * fields rewritten on every composition, for the reason
 * `DefinitionState.onOpenElsewhere` is one: the workspace's lambdas are
 * rebuilt each time it recomposes, and a layer remembered across them must
 * call the current one.
 */
internal class PaneVimHost(private val editor: EditorState) : VimHost {
    var onSave: (() -> Boolean)? = null
    var onCloseTab: ((force: Boolean) -> Boolean)? = null
    var onSaveAndClose: (() -> Boolean)? = null
    var onOpenPath: ((String) -> Boolean)? = null
    var onGoToDefinition: (() -> Unit)? = null
    var onNavigate: ((back: Boolean) -> Unit)? = null
    var clipboard: ClipboardManager? = null

    override fun save(): Boolean = onSave?.invoke() ?: false

    override fun closeTab(force: Boolean): Boolean = onCloseTab?.invoke(force) ?: false

    override fun saveAndClose(): Boolean = onSaveAndClose?.invoke() ?: false

    override fun openPath(path: String): Boolean = onOpenPath?.invoke(path) ?: false

    override fun goToDefinition() {
        onGoToDefinition?.invoke()
    }

    override fun navigateBack() {
        onNavigate?.invoke(true)
    }

    override fun navigateForward() {
        onNavigate?.invoke(false)
    }

    override fun readClipboard(): String? = clipboard?.getText()?.text

    override fun writeClipboard(text: String) {
        clipboard?.setText(AnnotatedString(text))
    }

    /**
     * The engine's buffer search, the one the find bar runs. Synchronous:
     * it is a scan of the buffer in a few milliseconds, the same cost
     * `Ctrl+D`'s occurrence search already pays on the keystroke path.
     */
    override fun search(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
    ): List<EditorState.SelectionRange>? {
        val session = editor.sessionOrNull ?: return emptyList()
        val search = SearchQuery(query = query, regex = regex, caseSensitive = caseSensitive, wholeWord = wholeWord)
        if (search.error() != null) return null
        return searchBuffer(session.id, search).matches.map { editor.rangeOf(it) }
    }
}
