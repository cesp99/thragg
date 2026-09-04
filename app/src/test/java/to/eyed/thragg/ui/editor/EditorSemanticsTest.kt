package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the editor canvas says to a screen reader. There is no TalkBack on the
 * host, so the sentence itself is the thing to check.
 */
class EditorSemanticsTest {

    @Test
    fun theCanvasSaysWhereTheCaretIsAndWhatItIsOn() {
        assertEquals(
            "Editor, main.rs. Line 1, column 1. fn main() {",
            editorAnnouncement(
                fileName = "main.rs",
                row = 0,
                col = 0,
                lineText = "fn main() {",
            ),
        )
        // One-based, as people count and as the status bar prints.
        assertEquals(
            "Editor, main.rs. Line 12, column 5. }",
            editorAnnouncement(fileName = "main.rs", row = 11, col = 4, lineText = "}"),
        )
    }

    @Test
    fun aBufferWithNoOneFileToNameSaysSoByOmission() {
        // A multibuffer, and the pane's own default: "Editor, . Line 1" reads
        // as a bug rather than as a blank.
        assertEquals(
            "Editor. Line 1, column 1. fn main() {",
            editorAnnouncement(fileName = "", row = 0, col = 0, lineText = "fn main() {"),
        )
    }

    @Test
    fun aBlankLineIsSaidRatherThanPassedOverInSilence() {
        assertEquals(
            "Editor, main.rs. Line 3, column 1. Blank line.",
            editorAnnouncement(fileName = "main.rs", row = 2, col = 0, lineText = "   "),
        )
        // The trailing newline the buffer hands back is not content.
        assertEquals(
            "Editor, main.rs. Line 1, column 1. let x = 1;",
            editorAnnouncement(fileName = "main.rs", row = 0, col = 0, lineText = "let x = 1;\n"),
        )
    }

    @Test
    fun aSelectionIsCountedInWhicheverUnitMakesSense() {
        assertEquals(
            "Editor, a.rs. Line 1, column 1. abc 1 character selected.",
            editorAnnouncement("a.rs", 0, 0, "abc", selectionChars = 1),
        )
        assertEquals(
            "Editor, a.rs. Line 1, column 1. abc 3 characters selected.",
            editorAnnouncement("a.rs", 0, 0, "abc", selectionChars = 3),
        )
        // Across lines the number of lines is the useful fact, and counting
        // characters would mean reading the whole buffer.
        assertEquals(
            "Editor, a.rs. Line 1, column 1. abc 4 lines selected.",
            editorAnnouncement("a.rs", 0, 0, "abc", selectionChars = 2, selectionLines = 4),
        )
    }

    @Test
    fun moreThanOneCaretIsWorthSaying() {
        assertEquals(
            "Editor, a.rs. Line 1, column 1. abc 3 cursors.",
            editorAnnouncement("a.rs", 0, 0, "abc", caretCount = 3),
        )
        // One cursor is the ordinary case and says nothing.
        assertEquals(
            "Editor, a.rs. Line 1, column 1. abc",
            editorAnnouncement("a.rs", 0, 0, "abc", caretCount = 1),
        )
    }

    @Test
    fun aDiagnosticIsItsSeverityAndItsFirstLine() {
        val diagnostic = Diagnostic(
            row = 3,
            colUtf16 = 4,
            endRow = 3,
            endColUtf16 = 9,
            severity = DiagnosticSeverity.Error,
            message = "expected `;`\nhelp: add a semicolon",
        )
        assertEquals("Error: expected `;`", diagnosticAnnouncement(diagnostic))
        assertEquals(
            "Warning: unused variable",
            diagnosticAnnouncement(
                diagnostic.copy(
                    severity = DiagnosticSeverity.Warning,
                    message = "unused variable",
                )
            ),
        )
    }
}
