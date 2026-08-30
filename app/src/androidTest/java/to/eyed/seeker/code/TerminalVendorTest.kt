package to.eyed.seeker.code

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device proof that the vendored Termux terminal actually runs a process:
 * libtermux.so opens a pty, forks a shell, and the emulator turns its output
 * into screen text. None of this is covered by the vendored unit tests, which
 * feed the emulator bytes directly and never touch the JNI or the fork.
 *
 * The shell here is `/system/bin/sh` — always present and executable on
 * Android. The bundled userland (P4-3) replaces it, not this mechanism.
 */
@RunWith(AndroidJUnit4::class)
class TerminalVendorTest {

    /** Records what the session reports; every callback lands on the main thread. */
    private class RecordingClient : TerminalSessionClient {
        val finished = CountDownLatch(1)
        @Volatile var pid = 0
        @Volatile var title: String? = null
        @Volatile var bells = 0

        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) {
            title = changedSession.title
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            finished.countDown()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) {
            bells++
        }

        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            this.pid = pid
        }

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun startShell(client: RecordingClient, args: Array<String>): TerminalSession {
        val filesDir = instrumentation.targetContext.filesDir.absolutePath
        lateinit var session: TerminalSession
        // Both steps must run on the main thread. The constructor builds a
        // Handler bound to the *calling* thread's looper, and updateSize() is
        // what actually forks: it builds the emulator and calls
        // JNI.createSubprocess. (The constructor's looper binding is a real
        // constraint on our session layer, not a test artifact.)
        instrumentation.runOnMainSync {
            session = TerminalSession(
                "/system/bin/sh",
                filesDir,
                args,
                arrayOf("PATH=/system/bin", "HOME=$filesDir", "TERM=xterm-256color"),
                2000,
                client,
            )
            session.updateSize(80, 24, 10, 20)
        }
        return session
    }

    /** Polls the emulator's screen (main-thread state) until [predicate] holds. */
    private fun awaitScreen(session: TerminalSession, timeoutMs: Long, predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                text = session.emulator.screen.transcriptText
            }
            if (predicate(text)) return text
            Thread.sleep(25)
        }
        return text
    }

    @Test
    fun shellRunsAndItsOutputReachesTheScreen() {
        val client = RecordingClient()
        val session = startShell(client, arrayOf("sh"))
        try {
            assertTrue("no pid reported", client.pid > 0)

            val marker = "seeker-pty-ok"
            val command = "echo $marker\n".toByteArray()
            session.write(command, 0, command.size)

            val screen = awaitScreen(session, 10_000) { it.contains("$marker\n") }
            // The echoed command line contains the marker too, so require the
            // output line: the marker on a line of its own.
            assertTrue("shell output missing, screen was:\n$screen", screen.contains("$marker\n"))
        } finally {
            instrumentation.runOnMainSync { session.finishIfRunning() }
        }
    }

    @Test
    fun escapeSequencesAreInterpretedNotPrinted() {
        val client = RecordingClient()
        // -c so the shell exits on its own, which also exercises the waiter
        // thread and the exit-status path.
        val session = startShell(
            client,
            // Bold "styled", a title-setting OSC, and a bell.
            arrayOf("sh", "-c", "printf '\\033]0;seeker-title\\007\\033[1mstyled\\033[0m\\n\\007'"),
        )
        try {
            val screen = awaitScreen(session, 10_000) { it.contains("styled") }
            assertTrue("styled text missing:\n$screen", screen.contains("styled"))
            // The emulator consumed the sequences rather than printing them:
            // no ESC survives, and the SGR/OSC bodies are gone. (The screen
            // does end with the emulator's own "[Process completed]" notice,
            // so a bare "[" is not evidence of a leak.)
            assertTrue("ESC leaked into the screen:\n$screen", !screen.contains('\u001B'))
            assertTrue("SGR leaked into the screen:\n$screen", !screen.contains("[1m"))
            assertTrue("OSC leaked into the screen:\n$screen", !screen.contains("seeker-title"))

            // …and the SGR took effect rather than merely being swallowed: the
            // first cell of the screen is bold.
            var effect = 0
            instrumentation.runOnMainSync {
                effect = TextStyle.decodeEffect(session.emulator.screen.getStyleAt(0, 0))
            }
            assertTrue("first cell is not bold", effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0)

            assertTrue("session did not finish", client.finished.await(10, TimeUnit.SECONDS))
            assertEquals(0, session.exitStatus)
            assertEquals("seeker-title", client.title)
            assertTrue("bell not reported", client.bells > 0)
        } finally {
            instrumentation.runOnMainSync { session.finishIfRunning() }
        }
    }
}
