package to.eyed.thragg.solana.build

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A program redrawing one line in place — Seahorse's spinner, cargo-build-sbf's
 * download counter — is one live row, not a row per frame. Seen on the
 * Seeker 2026-09-08: a six-minute `seahorse build` had filled the log with
 * the same "Compiling sea_counter…" line hundreds of times.
 */
class BuildLogTest {

    @Test
    fun `a redraw replaces the previous redraw and nothing else`() {
        val log = BuildLog()
        log.append(BuildLogRow.Text("Compiling foo"))
        log.progress(BuildLogRow.Progress("⠋ Compiling bar…"))
        log.progress(BuildLogRow.Progress("⠙ Compiling bar…"))
        log.progress(BuildLogRow.Progress("⠹ Compiling bar…"))
        log.flush()
        assertEquals(
            listOf<BuildLogRow>(BuildLogRow.Text("Compiling foo"), BuildLogRow.Progress("⠹ Compiling bar…")),
            log.rows,
        )
        // A real line ends the redraw; the next redraw starts a new live row
        // rather than rewriting history above the line.
        log.append(BuildLogRow.Text("Finished"))
        log.progress(BuildLogRow.Progress("⠋ Linking…"))
        log.flush()
        assertEquals(4, log.rows.size)
        assertEquals(BuildLogRow.Progress("⠋ Linking…"), log.rows.last())
        // …and replaces across a flush boundary too: the row already on
        // screen is the one that changes.
        log.progress(BuildLogRow.Progress("⠙ Linking…"))
        log.flush()
        assertEquals(4, log.rows.size)
        assertEquals(BuildLogRow.Progress("⠙ Linking…"), log.rows.last())
    }
}
