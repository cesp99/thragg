package to.eyed.seeker.code.ui.editor

/**
 * The editor's keyboard actions by Zed's names — what keymap.json binds a
 * key to, and what the pane answers for.
 *
 * Each constant is an `editor::` action from Zed's own keymap
 * (assets/keymaps/default-linux.json) whose behaviour this editor has. The
 * names are the whole contract between the keymap and the pane: the
 * workspace's key pass resolves a stroke to a name, and the pane, having
 * registered a handler per name ([editorActionHandlers]), runs it. Nothing
 * else in the app knows which key does what in the editor — which is what
 * lets `"alt-up": null` in keymap.json switch line-moving off, and
 * `"ctrl-shift-up": "editor::MoveLineUp"` put it somewhere else.
 *
 * Adding an action is a constant here, a handler in [editorActionHandlers],
 * and a default binding in `DefaultKeymap` if it deserves a key.
 */
object EditorAction {
    const val ShowCompletions = "editor::ShowCompletions"
    const val Undo = "editor::Undo"
    const val Redo = "editor::Redo"
    const val SelectAll = "editor::SelectAll"
    const val Copy = "editor::Copy"
    const val Cut = "editor::Cut"
    const val Paste = "editor::Paste"
    const val SelectNext = "editor::SelectNext"
    const val SelectAllMatches = "editor::SelectAllMatches"
    const val DeleteLine = "editor::DeleteLine"
    const val JoinLines = "editor::JoinLines"
    const val ToggleComments = "editor::ToggleComments"
    const val ToggleCodeActions = "editor::ToggleCodeActions"
    const val Format = "editor::Format"
    const val Fold = "editor::Fold"
    const val UnfoldLines = "editor::UnfoldLines"
    const val FoldAll = "editor::FoldAll"
    const val UnfoldAll = "editor::UnfoldAll"
    const val Hover = "editor::Hover"
    const val MoveToPreviousWordStart = "editor::MoveToPreviousWordStart"
    const val MoveToNextWordEnd = "editor::MoveToNextWordEnd"
    const val SelectToPreviousWordStart = "editor::SelectToPreviousWordStart"
    const val SelectToNextWordEnd = "editor::SelectToNextWordEnd"
    const val MoveToBeginning = "editor::MoveToBeginning"
    const val MoveToEnd = "editor::MoveToEnd"
    const val SelectToBeginning = "editor::SelectToBeginning"
    const val SelectToEnd = "editor::SelectToEnd"
    // Syntax-aware selection and the bracket pair — Zed's `alt-shift-right`
    // / `alt-shift-left` and `ctrl-m` (default-linux.json:547-548, 573).
    const val SelectLargerSyntaxNode = "editor::SelectLargerSyntaxNode"
    const val SelectSmallerSyntaxNode = "editor::SelectSmallerSyntaxNode"
    const val MoveToEnclosingBracket = "editor::MoveToEnclosingBracket"

    // The line commands Zed has beyond moving and duplicating: whole-line
    // selection (`ctrl-l`, :111), the sorts and the filters, the character
    // swap and the reflow (`ctrl-k ctrl-q`, :66-67).
    const val SelectLine = "editor::SelectLine"
    const val SortLinesCaseSensitive = "editor::SortLinesCaseSensitive"
    const val SortLinesCaseInsensitive = "editor::SortLinesCaseInsensitive"
    const val ReverseLines = "editor::ReverseLines"
    const val ShuffleLines = "editor::ShuffleLines"
    const val UniqueLinesCaseSensitive = "editor::UniqueLinesCaseSensitive"
    const val UniqueLinesCaseInsensitive = "editor::UniqueLinesCaseInsensitive"
    const val Transpose = "editor::Transpose"
    const val Rewrap = "editor::Rewrap"

    // Zed's `ConvertTo*` family (editor.rs:7123-7211). None of them has a
    // chord in default-linux.json: they are palette commands there, and here
    // they are palette commands with a menu row beside them.
    const val ConvertToUpperCase = "editor::ConvertToUpperCase"
    const val ConvertToLowerCase = "editor::ConvertToLowerCase"
    const val ConvertToTitleCase = "editor::ConvertToTitleCase"
    const val ConvertToSnakeCase = "editor::ConvertToSnakeCase"
    const val ConvertToKebabCase = "editor::ConvertToKebabCase"
    const val ConvertToUpperCamelCase = "editor::ConvertToUpperCamelCase"
    const val ConvertToLowerCamelCase = "editor::ConvertToLowerCamelCase"
    const val ConvertToOppositeCase = "editor::ConvertToOppositeCase"

    /** Zed's `editor::ToggleInlineDiagnostics` — the error-lens switch. */
    const val ToggleInlineDiagnostics = "editor::ToggleInlineDiagnostics"

    /** Zed's `editor::ToggleLineNumbers` (`ctrl-;`, default-linux.json:117). */
    const val ToggleLineNumbers = "editor::ToggleLineNumbers"

    /**
     * Zed's `editor::ToggleRelativeLineNumbers` — the other half of the
     * gutter's two switches, palette-only in Zed as it is here.
     */
    const val ToggleRelativeLineNumbers = "editor::ToggleRelativeLineNumbers"

    /** Zed's `editor::ToggleMinimap`, the minimap's own switch. */
    const val ToggleMinimap = "editor::ToggleMinimap"

    const val MoveLineUp = "editor::MoveLineUp"
    const val MoveLineDown = "editor::MoveLineDown"
    const val DuplicateLineUp = "editor::DuplicateLineUp"
    const val DuplicateLineDown = "editor::DuplicateLineDown"
    const val AddSelectionAbove = "editor::AddSelectionAbove"
    const val AddSelectionBelow = "editor::AddSelectionBelow"
    const val GoToDiagnostic = "editor::GoToDiagnostic"
    const val GoToPreviousDiagnostic = "editor::GoToPreviousDiagnostic"
    const val GoToDefinition = "editor::GoToDefinition"
    const val GoToTypeDefinition = "editor::GoToTypeDefinition"
    const val GoToImplementation = "editor::GoToImplementation"
    const val GoToDeclaration = "editor::GoToDeclaration"
    const val FindAllReferences = "editor::FindAllReferences"
    const val ShowSignatureHelp = "editor::ShowSignatureHelp"
    const val Rename = "editor::Rename"
    const val Cancel = "editor::Cancel"
    const val Backspace = "editor::Backspace"
    const val Delete = "editor::Delete"
    const val DeleteToPreviousWordStart = "editor::DeleteToPreviousWordStart"
    const val DeleteToNextWordEnd = "editor::DeleteToNextWordEnd"
    const val Newline = "editor::Newline"
    const val NewlineBelow = "editor::NewlineBelow"
    const val NewlineAbove = "editor::NewlineAbove"
    const val Tab = "editor::Tab"
    const val Backtab = "editor::Backtab"
    const val Indent = "editor::Indent"
    const val Outdent = "editor::Outdent"
    const val MoveLeft = "editor::MoveLeft"
    const val MoveRight = "editor::MoveRight"
    const val MoveUp = "editor::MoveUp"
    const val MoveDown = "editor::MoveDown"
    const val SelectLeft = "editor::SelectLeft"
    const val SelectRight = "editor::SelectRight"
    const val SelectUp = "editor::SelectUp"
    const val SelectDown = "editor::SelectDown"
    const val MoveToBeginningOfLine = "editor::MoveToBeginningOfLine"
    const val MoveToEndOfLine = "editor::MoveToEndOfLine"
    const val SelectToBeginningOfLine = "editor::SelectToBeginningOfLine"
    const val SelectToEndOfLine = "editor::SelectToEndOfLine"
    const val MovePageUp = "editor::MovePageUp"
    const val MovePageDown = "editor::MovePageDown"
    const val SelectPageUp = "editor::SelectPageUp"
    const val SelectPageDown = "editor::SelectPageDown"

    // Git in the editor. The `editor::` four are Zed's hunk motions and
    // hunk blocks (default-linux.json:118-119, 598-599); the `git::` ones
    // are registered on Zed's editor too (editor/src/git.rs) and bound in
    // its Editor context (:121, 190-193), so they live here beside them.
    const val GoToHunk = "editor::GoToHunk"
    const val GoToPreviousHunk = "editor::GoToPreviousHunk"
    const val ExpandAllDiffHunks = "editor::ExpandAllDiffHunks"
    const val ToggleSelectedDiffHunks = "editor::ToggleSelectedDiffHunks"
    const val ToggleStaged = "git::ToggleStaged"
    const val StageAndNext = "git::StageAndNext"
    const val UnstageAndNext = "git::UnstageAndNext"
    const val Restore = "git::Restore"
    const val Blame = "git::Blame"
}
