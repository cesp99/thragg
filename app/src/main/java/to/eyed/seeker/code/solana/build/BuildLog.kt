package to.eyed.seeker.code.solana.build

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/**
 * What the Build destination shows: the command, everything it printed, the
 * problems in place, and one summary line at the end.
 *
 * A list of typed rows rather than a string, because half the rows are
 * interactive — an error row is tappable and jumps to `file:line:col`, an
 * unparsed row long-presses to Copy — and because a 5 000-line cargo build
 * appended to a `StringBuilder` and re-laid-out on every append is how a phone
 * stops responding while it compiles.
 *
 * **Written from the reader thread, read from composition.** Snapshot state
 * makes that safe, but not cheap: a build prints in bursts of hundreds of
 * lines and a write per line is a recomposition per line. So writes are
 * coalesced into [FLUSH_INTERVAL_NS] batches — the same 10 Hz ceiling
 * [to.eyed.seeker.code.terminal.GuestProcess.PROGRESS_INTERVAL_NS] puts on a
 * clone's progress, and for the same reason: output arrives far faster than a
 * screen can show it. [flush] exists so the last burst of a build that has
 * gone quiet is not held back by the very throttle meant to help it.
 */
class BuildLog {

    private val entries = mutableStateListOf<BuildLogRow>()

    /** Rows in order, oldest first. Safe to read from composition. */
    val rows: List<BuildLogRow> get() = entries

    /**
     * How many rows fell off the front of the log. Shown rather than hidden:
     * a truncated log that does not say it is truncated is a log that lies
     * about where a build failed.
     */
    var dropped by mutableIntStateOf(0)
        private set

    /** Rows written but not yet visible; see the class comment. */
    private val queued = ArrayList<BuildLogRow>(64)
    private var lastFlush = 0L

    fun clear() {
        synchronized(queued) { queued.clear() }
        entries.clear()
        dropped = 0
        lastFlush = 0L
    }

    /** Append a row, visible on the next flush or within ~100 ms. */
    fun append(row: BuildLogRow) {
        synchronized(queued) {
            queued.add(row)
            val now = System.nanoTime()
            if (now - lastFlush < FLUSH_INTERVAL_NS) return
            lastFlush = now
            drain()
        }
    }

    /** Make everything written so far visible. Cheap when nothing is queued. */
    fun flush() {
        synchronized(queued) {
            if (queued.isEmpty()) return
            lastFlush = System.nanoTime()
            drain()
        }
    }

    /** Caller holds [queued]'s lock. */
    private fun drain() {
        entries.addAll(queued)
        queued.clear()
        val excess = entries.size - MAX_ROWS
        if (excess > 0) {
            // removeRange is not on SnapshotStateList; one subList clear is
            // still a single structural change and one recomposition.
            entries.subList(0, excess).clear()
            dropped += excess
        }
    }

    companion object {
        /** ~10 Hz, as everything else that reads a guest process uses. */
        private const val FLUSH_INTERVAL_NS = 100_000_000L

        /**
         * A cold Anchor build prints a few thousand lines; a `cargo build` with
         * `-vv` prints far more. The cap is on rows rather than bytes because
         * the list is what costs, and 8 000 rows of an average build line is a
         * couple of megabytes.
         */
        const val MAX_ROWS = 8_000
    }
}

/** One row of the log. The four kinds the screen draws differently. */
sealed interface BuildLogRow {

    /** `14:22  anchor build` — the head of a run, with its wall-clock time. */
    data class Command(val text: String, val at: Long) : BuildLogRow

    /** Anything the build printed that is not a problem. Monospace, verbatim. */
    data class Text(val text: String) : BuildLogRow

    /**
     * A problem, tappable when it has a location. This is the row the whole
     * Build screen exists to produce.
     */
    data class Issue(val issue: BuildIssue) : BuildLogRow

    /**
     * Something the app itself is saying — the platform-tools fallback's
     * caveat, "saving 3 files", "running anchor keys sync". Distinguished from
     * [Text] so it is never mistaken for the compiler's own words.
     */
    data class Note(val text: String) : BuildLogRow

    /** `── failed · 1 error, 1 warning · 1m11s ─`. One per run, at the end. */
    data class Summary(val text: String, val failed: Boolean) : BuildLogRow
}
