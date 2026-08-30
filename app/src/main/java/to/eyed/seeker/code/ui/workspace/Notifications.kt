package to.eyed.seeker.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How loud a notification is, and how long it lives.
 *
 * Zed's own three levels — its `MessageNotification` is built from a severity
 * that picks the icon and the accent (workspace/src/notifications.rs:640-720,
 * ui/src/components/icon.rs `IconName::Info`/`Warning`/`XCircle`).
 */
enum class NotificationSeverity {
    /** Something finished. Goes away on its own. */
    Info,

    /** Something is off but the work landed — a formatter that could not run. */
    Warning,

    /** Something did not happen. Stays until the user says they have read it. */
    Error,
}

/** The one button a toast may carry: "Show log", "Retry", "Undo". */
data class NotificationAction(
    val label: String,
    /**
     * What the button does. Dismissing the toast is the host's job, not the
     * caller's: every action so far wants the toast gone once it has run, and
     * a caller that forgot left a dead button on screen.
     */
    val run: () -> Unit,
)

/**
 * One toast: what it says, how loud it is, when it stops saying it.
 *
 * [key] is Zed's `NotificationId` (notifications.rs:42-63) — showing the same
 * key twice replaces the first rather than stacking a second copy, which is
 * what stops a save that fails once a second from filling the screen.
 */
data class AppNotification(
    val id: Long,
    val message: String,
    val severity: NotificationSeverity,
    val action: NotificationAction? = null,
    val key: String? = null,
    /**
     * Wall-clock milliseconds after which the toast removes itself, or null
     * when it stays until dismissed. See [NotificationStack.lifetimeOf].
     */
    val expiresAt: Long? = null,
)

/**
 * The workspace's toast stack: what has been said, in what order, and what is
 * still worth showing.
 *
 * This is Zed's `Workspace::notifications` (workspace/src/notifications.rs:23)
 * with the parts a phone needs added: a cap on how many are drawn, because a
 * toast column four items tall already covers half a compact screen, and an
 * expiry, because Zed's notifications are dismissed with a mouse that is not
 * always here.
 *
 * Ordering is **newest first**: the newest one is the one the user is waiting
 * for, and on a compact layout the stack grows *up* from the status bar, so
 * index 0 is nearest the thumb.
 *
 * Every rule in here is pure and clock-injected so it can be driven on the
 * host — see `NotificationStackTest`.
 */
open class NotificationStack(private val now: () -> Long = System::currentTimeMillis) {

    private val items = mutableStateListOf<AppNotification>()
    private var nextId = 1L

    /** Whether the user asked to see past the [MAX_VISIBLE] cap. */
    var isExpanded by mutableStateOf(false)
        private set

    /** Everything held, newest first — including what the cap hides. */
    val all: List<AppNotification> get() = items

    /** What the host draws: the newest [MAX_VISIBLE], or all of them once expanded. */
    val visible: List<AppNotification>
        get() = if (isExpanded) items else items.take(MAX_VISIBLE)

    /** How many the cap is hiding — the "+N more" row's N. Zero when expanded. */
    val hidden: Int get() = (items.size - visible.size).coerceAtLeast(0)

    /**
     * Say something. Returns the notification's id, so a caller that knows its
     * message has gone stale can take it back.
     *
     * A [key] replaces the notification that carries the same one and keeps
     * its place at the top; without one every call is a new toast.
     */
    fun show(
        message: String,
        severity: NotificationSeverity = NotificationSeverity.Info,
        action: NotificationAction? = null,
        key: String? = null,
    ): Long {
        val id = nextId++
        val notification = AppNotification(
            id = id,
            message = message,
            severity = severity,
            action = action,
            key = key,
            expiresAt = lifetimeOf(severity, action)?.let { now() + it },
        )
        if (key != null) items.removeAll { it.key == key }
        items.add(0, notification)
        // The oldest fall off the back rather than the stack growing without
        // bound: nothing reads a toast from an hour ago, and "+N more" that
        // says 400 is not a number, it is a leak.
        while (items.size > MAX_HELD) items.removeAt(items.lastIndex)
        return id
    }

    /** Sugar, so a caller reads as the sentence it is raising. */
    fun info(message: String, action: NotificationAction? = null, key: String? = null): Long =
        show(message, NotificationSeverity.Info, action, key)

    fun warn(message: String, action: NotificationAction? = null, key: String? = null): Long =
        show(message, NotificationSeverity.Warning, action, key)

    fun error(message: String, action: NotificationAction? = null, key: String? = null): Long =
        show(message, NotificationSeverity.Error, action, key)

    fun dismiss(id: Long) {
        items.removeAll { it.id == id }
        if (items.size <= MAX_VISIBLE) isExpanded = false
    }

    fun dismissKey(key: String) {
        items.removeAll { it.key == key }
        if (items.size <= MAX_VISIBLE) isExpanded = false
    }

    /** Zed's `workspace::ClearAllNotifications` (workspace.rs). */
    fun clearAll() {
        items.clear()
        isExpanded = false
    }

    fun expand() {
        isExpanded = true
    }

    fun collapse() {
        isExpanded = false
    }

    /**
     * Drop whatever has run out of time. Returns true when something went, so
     * the host's timer knows whether to look again.
     */
    fun expire(): Boolean {
        val at = now()
        val before = items.size
        items.removeAll { it.expiresAt != null && it.expiresAt <= at }
        if (items.size <= MAX_VISIBLE) isExpanded = false
        return items.size != before
    }

    /**
     * When the next toast expires, or null when nothing is on a clock — what
     * the host sleeps until rather than polling.
     */
    fun nextExpiry(): Long? = items.mapNotNull { it.expiresAt }.minOrNull()

    companion object {
        /**
         * How many are drawn before the rest collapse into "+N more". Four
         * toasts at the width Zed gives them (`w_112`, notifications are
         * 448px wide — workspace.rs:6629) is already a third of a tablet and
         * most of a phone.
         */
        const val MAX_VISIBLE = 4

        /** How many are remembered at all. See [show]. */
        const val MAX_HELD = 16

        /** Zed's own toast timeout is seconds, not minutes; six is the ask. */
        const val INFO_MS = 6_000L

        /**
         * An info toast with a button waits twice as long: the button is the
         * point of it, and six seconds is not long enough to read a sentence
         * and decide to press something.
         */
        const val INFO_WITH_ACTION_MS = 12_000L

        /**
         * How long a notification of this shape lives, or null for "until
         * dismissed". Warnings and errors are never on a clock: a warning
         * nobody saw is a warning wasted, and this is the only place either
         * is reported.
         */
        fun lifetimeOf(severity: NotificationSeverity, action: NotificationAction?): Long? =
            when (severity) {
                NotificationSeverity.Info ->
                    if (action == null) INFO_MS else INFO_WITH_ACTION_MS
                NotificationSeverity.Warning, NotificationSeverity.Error -> null
            }
    }
}

/**
 * The one stack the workspace draws.
 *
 * A global for the reason [KeymapStore] is one: the things that need to report
 * a failure — a git operation deep in a panel, a language server that died
 * under a poll, an agent's transport — are nowhere near the composable that
 * draws the toast, and threading a callback through six panels to reach it
 * would be the ad-hoc wiring this replaces. There is exactly one workspace on
 * screen, so there is exactly one stack.
 */
object Notifications : NotificationStack()
