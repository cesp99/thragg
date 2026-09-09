package to.eyed.thragg.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * G-18 — the guest processes a terminal session started must go with it.
 *
 * Deleting the open project took the foreground service, the directory and
 * the session record, and left `bash --login` (reparented to init) and its
 * `sleep` running with `cwd -> …/projects/qa_git2 (deleted)`: the emulator
 * SIGKILLs proot, and SIGKILL is the one signal proot cannot run its
 * `--kill-on-exit` path for. The fix reaches every process in the pty's
 * session instead, and this is the part of it that can be checked without a
 * phone: finding them.
 */
class GuestSessionReaperTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun proc(vararg entries: Triple<Int, String, Int>): File {
        val root = temp.newFolder("proc")
        for ((pid, comm, session) in entries) {
            val dir = File(root, "$pid").apply { mkdirs() }
            // pid (comm) state ppid pgrp session tty_nr …
            File(dir, "stat").writeText("$pid ($comm) S 1 $pid $session 34816 $pid 4194304 0\n")
        }
        return root
    }

    @Test
    fun theSessionIdIsReadPastACommandNameWithSpacesAndBracketsInIt() {
        val stat = "30926 (bash --login (2)) S 30900 30926 30900 34816 30926 4194304"
        assertEquals(30900, GuestSessionReaper.sessionIdIn(stat))
    }

    @Test
    fun aLineThatIsNotAStatLineAnswersNothing() {
        assertNull(GuestSessionReaper.sessionIdIn(""))
        assertNull(GuestSessionReaper.sessionIdIn("nothing like a stat line"))
    }

    /**
     * proot, the bash it traces and the bash's own child are one session;
     * the app's own process is another and must not be touched.
     */
    @Test
    fun everyProcessInTheSessionIsFoundAndNothingElseIs() {
        val root = proc(
            Triple(30900, "proot", 30900),
            Triple(30926, "bash", 30900),
            Triple(30971, "sleep", 30900),
            Triple(1974, "to.eyed.thragg", 1974),
            Triple(2050, "Binder:1974_2", 1974),
        )

        val doomed = GuestSessionReaper.sessionPids(30900, root)

        assertEquals(listOf(30971, 30926, 30900), doomed)
    }

    /** The leader goes last, so the polite signal reaches its tracees first. */
    @Test
    fun theLeaderIsSignalledAfterEverythingItTraces() {
        val root = proc(
            Triple(100, "proot", 100),
            Triple(120, "bash", 100),
        )
        assertEquals(100, GuestSessionReaper.sessionPids(100, root).last())
    }

    /** A pid that has already gone leaves an empty list, not an exception. */
    @Test
    fun aSessionThatIsAlreadyGoneIsEmpty() {
        val root = proc(Triple(1974, "to.eyed.thragg", 1974))
        assertEquals(emptyList<Int>(), GuestSessionReaper.sessionPids(30900, root))
    }
}
