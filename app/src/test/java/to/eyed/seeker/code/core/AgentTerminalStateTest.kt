package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a terminal card reads the engine's answers.
 *
 * Three of these encode a contract that is easy to get subtly wrong and
 * impossible to notice until a command is actually running: revision 0 means
 * *gone*, an unchanged revision carries no payload at all, and the card must
 * keep what it last read rather than blank itself.
 */
class AgentTerminalStateTest {

    @Test
    fun aFullAnswerIsParsed() {
        val state = AgentTerminalState.parse(
            """{"revision":7,"label":"cargo test","output":"ok\n",
               "truncated":false,"running":false,"exitStatus":{"exitCode":0}}""",
            null,
        )
        assertEquals(7L, state.revision)
        assertEquals("cargo test", state.label)
        assertEquals("ok\n", state.output)
        assertFalse(state.running)
        assertEquals(0, state.exitCode)
        assertNull(state.signal)
    }

    /**
     * The cheap answer. The engine sends only the revision when nothing has
     * moved, so a card that re-parsed it would blank its own output every
     * poll — which at 120ms is a flicker, not a subtlety.
     */
    @Test
    fun anUnchangedRevisionKeepsWhatWasAlreadyRead() {
        val first = AgentTerminalState.parse(
            """{"revision":3,"label":"sleep 5","output":"tick\n","running":true}""",
            null,
        )
        val again = AgentTerminalState.parse("""{"revision":3}""", first)
        assertSame(first, again)
        assertEquals("tick\n", again.output)
        assertTrue(again.running)
    }

    /**
     * Zero is the engine's "I no longer have this" — the agent released the
     * terminal, or its session closed. A live terminal that has printed
     * nothing starts at 1 precisely so it cannot be mistaken for this.
     */
    @Test
    fun revisionZeroIsGoneWhateverElseIsInTheAnswer() {
        val previous = AgentTerminalState.parse(
            """{"revision":2,"output":"partial","running":true}""",
            null,
        )
        val gone = AgentTerminalState.parse("""{"revision":0}""", previous)
        assertSame(AgentTerminalState.Gone, gone)
        assertFalse(gone.running)
    }

    @Test
    fun aKilledCommandReportsItsSignalAndNoExitCode() {
        val state = AgentTerminalState.parse(
            """{"revision":4,"label":"sleep 900","output":"","running":false,
               "exitStatus":{"signal":"SIGQUIT"}}""",
            null,
        )
        assertEquals("SIGQUIT", state.signal)
        assertNull(state.exitCode)
    }

    /** Garbage from the bridge is "gone", never a crash in a transcript row. */
    @Test
    fun anUnparseableAnswerIsGoneRatherThanAThrow() {
        assertSame(AgentTerminalState.Gone, AgentTerminalState.parse("not json", null))
    }
}
