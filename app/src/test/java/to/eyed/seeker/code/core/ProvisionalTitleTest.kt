package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a thread is called before its agent names it: the first line of the
 * first thing the user said (Zed's provisional title,
 * agent_ui/src/conversation_view/thread_view.rs:1728-1730).
 */
class ProvisionalTitleTest {

    @Test
    fun aTitleIsTheFirstLineOfTheMessage() {
        assertEquals("hi", AgentSessions.provisionalTitle("hi"))
        assertEquals(
            "Fix the crash on open",
            AgentSessions.provisionalTitle("Fix the crash on open\n\nIt happens every time."),
        )
    }

    /** Leading blank lines are not a name; the first line with something in it is. */
    @Test
    fun blankLinesAreSkippedAndTheNameIsTrimmed() {
        assertEquals("what is this", AgentSessions.provisionalTitle("\n\n   what is this   \n"))
    }

    /**
     * A message with nothing in it leaves the thread its number rather than
     * naming it the empty string, which would render as a nameless bar.
     */
    @Test
    fun aMessageOfWhitespaceHasNoTitle() {
        assertNull(AgentSessions.provisionalTitle(""))
        assertNull(AgentSessions.provisionalTitle("   \n\t\n  "))
    }

    /**
     * Long first lines are cut where Zed cuts them, with an ellipsis, so a
     * pasted paragraph cannot become a 4,000-character thread name.
     */
    @Test
    fun aLongLineIsCutWithAnEllipsis() {
        val title = AgentSessions.provisionalTitle("x".repeat(500))
        assertEquals(201, title?.length)
        assertEquals("x".repeat(200) + "…", title)
    }
}
