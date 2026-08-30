package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `editor::SelectLargerSyntaxNode` and `SelectSmallerSyntaxNode`.
 *
 * The tree's half — which node encloses a range — is the engine's and is
 * tested in Rust (`highlight::syntax_selection_tests`). What is tested here is
 * the pane's half: the *stack*, which is the only thing that makes shrinking
 * retrace rather than guess, and the rule that any other caret change ends the
 * run.
 */
class SyntaxSelectionTest {

    /** `let value = compute(x);` with the ladder the tree would answer. */
    private fun editorOf(): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer("let value = compute(x);")
        buffer.syntaxLadder += listOf(
            Caret(0, 20, 0, 21), // x
            Caret(0, 19, 0, 22), // (x)
            Caret(0, 12, 0, 22), // compute(x)
            Caret(0, 0, 0, 23), // the whole statement
        )
        return EditorState(buffer) to buffer
    }

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.selection(): String = selectionText()

    @Test
    fun growingClimbsTheLadderAndShrinkingComesBackDownIt() {
        val (state, _) = editorOf()
        state.caretAt(0, 20)

        assertTrue(state.selectLargerSyntaxNode())
        assertEquals("x", state.selection())
        assertTrue(state.selectLargerSyntaxNode())
        assertEquals("(x)", state.selection())
        assertTrue(state.selectLargerSyntaxNode())
        assertEquals("compute(x)", state.selection())

        // And back down, exactly the way it came.
        assertTrue(state.selectSmallerSyntaxNode())
        assertEquals("(x)", state.selection())
        assertTrue(state.selectSmallerSyntaxNode())
        assertEquals("x", state.selection())
        assertTrue(state.selectSmallerSyntaxNode())
        assertEquals("", state.selection())

        // Nothing left on the stack: the chord goes unhandled.
        assertFalse(state.selectSmallerSyntaxNode())
    }

    @Test
    fun aCaretMoveEndsTheRunSoShrinkingCannotJumpBackIntoIt() {
        val (state, _) = editorOf()
        state.caretAt(0, 20)
        state.selectLargerSyntaxNode()
        state.selectLargerSyntaxNode()
        assertEquals(2, state.syntaxSelectionDepth)

        // A click somewhere else: the ladder under the user is gone.
        state.caretAt(0, 4)
        assertEquals(0, state.syntaxSelectionDepth)
        assertFalse(state.selectSmallerSyntaxNode())
    }

    @Test
    fun aBufferWithNothingWiderLeavesTheSelectionAlone() {
        val state = EditorState(FakeEditorBuffer("plain text"))
        state.caretAt(0, 3)
        assertFalse(state.selectLargerSyntaxNode())
        assertEquals("", state.selectionText())
        assertEquals(0, state.syntaxSelectionDepth)
    }

    // ---- the bracket jump --------------------------------------------------

    @Test
    fun theBracketJumpGoesToTheCloserAndBackToTheOpener() {
        val state = EditorState(FakeEditorBuffer("fn main() {\n    let x = 1;\n}\n"))
        state.caretAt(1, 8)

        // From inside the pair the caret goes to the closing brace on row 2.
        assertTrue(state.moveToEnclosingBracket())
        assertEquals(2 to 0, state.cursorRow to state.cursorCol)

        // And from on the closer, back to just past the opener.
        assertTrue(state.moveToEnclosingBracket())
        assertEquals(0 to 11, state.cursorRow to state.cursorCol)
    }

    @Test
    fun aCaretInsideNoPairIsUnhandled() {
        val state = EditorState(FakeEditorBuffer("no brackets here\n"))
        state.caretAt(0, 4)
        assertFalse(state.moveToEnclosingBracket())
    }
}
