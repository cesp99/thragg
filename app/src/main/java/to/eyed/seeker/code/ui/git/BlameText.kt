package to.eyed.seeker.code.ui.git

import to.eyed.seeker.code.core.BlameLine
import java.util.concurrent.TimeUnit

/**
 * What the end of the caret's line says — Zed's inline blame, worded as Zed
 * words it: `"{author}, {relative time} - {summary}"`
 * (git_ui/src/blame_ui.rs:37-51).
 *
 * A line nobody has committed yet says so, rather than naming the all-zero
 * commit git uses as a placeholder.
 */
fun blameText(line: BlameLine, nowSeconds: Long): String {
    if (!line.isCommitted) return "Uncommitted"
    val author = line.author.ifBlank { "Unknown" }
    val time = relativeTime(line.authorTime, nowSeconds)
    return if (line.summary.isBlank()) "$author, $time" else "$author, $time - ${line.summary}"
}

/**
 * "3 days ago", the way Zed says it (time_format/src/time_format.rs:259-305,
 * which itself follows git's `show_date_relative`).
 *
 * Deliberately calendar-free below the day: minutes and hours are exact
 * differences, and everything from a day up is counted in whole days, which is
 * what makes this a pure function of two numbers and testable on the host.
 */
fun relativeTime(thenSeconds: Long, nowSeconds: Long): String {
    val elapsed = nowSeconds - thenSeconds
    // A commit stamped in the future is a clock that disagrees, not an error
    // worth a message of its own.
    if (elapsed < 0) return "Just now"
    val minutes = TimeUnit.SECONDS.toMinutes(elapsed)
    if (minutes < 1) return "Just now"
    if (minutes == 1L) return "1 minute ago"
    if (minutes < 60) return "$minutes minutes ago"
    val hours = TimeUnit.SECONDS.toHours(elapsed)
    if (hours == 1L) return "1 hour ago"
    if (hours < 24) return "$hours hours ago"
    val days = TimeUnit.SECONDS.toDays(elapsed)
    if (days == 1L) return "Yesterday"
    if (days < 7) return "$days days ago"
    val weeks = days / 7
    if (weeks == 1L) return "1 week ago"
    if (weeks <= 4) return "$weeks weeks ago"
    val months = days / 30
    if (months <= 1L) return "1 month ago"
    if (months < 12) return "$months months ago"
    val years = days / 365
    return if (years <= 1L) "1 year ago" else "$years years ago"
}
