package to.eyed.thragg.solana.build

/**
 * The valve between a carriage-return progress bar and the Build log.
 *
 * Two failure modes, one on each side of it, both seen on the device:
 *
 *  * **Silence.** `cargo-build-sbf`'s tools download redraws one progress
 *    line with bare `\r` for minutes at a time; a log that only shows
 *    `\n`-terminated lines showed nothing but the command for 27 minutes and
 *    looked hung (the 2026-08 rehearsal). The redraws must reach the log.
 *  * **Flood.** Every redraw as its own row is hundreds of near-identical
 *    rows a second, which both scrolls real output away and eats
 *    [BuildLog.MAX_ROWS] for nothing.
 *
 * So: the latest redraw, at most one per [intervalMs]. Half a second is
 * plenty for a human watching a byte count. Ordinary lines do not pass
 * through here at all — the caller sends only carriage-terminated records
 * (see `GuestProcess.run`'s `onCarriage`) — but before appending an ordinary
 * line the caller should [drain] so the last redraw seen lands *before* the
 * line that followed it and the log stays in order.
 *
 * Pure and clock-free (the caller passes the time) so the 500 ms window is a
 * unit test rather than a `Thread.sleep`.
 */
class ProgressThrottle(private val intervalMs: Long = INTERVAL_MS) {

    /** The newest redraw the interval is holding back. */
    private var heldBack: String? = null
    private var lastEmitAt = 0L

    /**
     * Offer one carriage-terminated record. Returns it when the interval has
     * elapsed since the last emitted one, null when it is held back — in
     * which case it replaces whatever was held before it, because a redraw
     * supersedes the redraw it redrew.
     */
    fun progress(line: String, nowMs: Long): String? {
        if (nowMs - lastEmitAt >= intervalMs) {
            lastEmitAt = nowMs
            heldBack = null
            return line
        }
        heldBack = line
        return null
    }

    /**
     * The redraw still held back, or null. Call before appending a normal
     * line (ordering) and once when the stream ends (the bar's final state).
     */
    fun drain(): String? = heldBack.also { heldBack = null }

    companion object {
        /** "Last line per 500 ms is plenty" for a download byte counter. */
        const val INTERVAL_MS = 500L
    }
}
