package to.eyed.seeker.code.ui.workspace

/**
 * The bookkeeping behind `"autosave": {"after_delay": …}` — Zed's
 * `pending_autosave.fire_new(delay, …)` on every `ItemEvent::Edit`
 * (workspace/src/item.rs:936-956), a debounce that restarts with each edit
 * and saves once the buffer has sat still for the delay.
 *
 * This editor has no edit event to hook — the buffer's version counter is
 * what moves — so the debounce is read off the counter by the workspace's
 * status poll: [observe] notes each tab's version and when it last moved,
 * and [due] names the dirty tabs whose version has not moved for the delay.
 * Pure and clock-free so it can be tested on the host; the poll supplies
 * both the versions and the time.
 */
class AutosaveTracker {
    private class Seen(var version: Long, var changedAt: Long) {
        /** A save went out at this version; nothing to do until it edits again. */
        var saved: Boolean = false
    }

    private val seen = HashMap<String, Seen>()

    /**
     * Record [version] for the tab keyed [key] as of [now]. A version that
     * differs from the last one restarts that tab's clock; the first
     * sighting starts it, so a tab that was dirty when the setting was
     * turned on saves a delay later rather than at once.
     */
    fun observe(key: String, version: Long, now: Long) {
        val entry = seen[key]
        if (entry == null) {
            seen[key] = Seen(version, now)
        } else if (entry.version != version) {
            entry.version = version
            entry.changedAt = now
            entry.saved = false
        }
    }

    /**
     * The keys whose version has been unchanged for at least [delayMs] as of
     * [now], among [dirty], and not already saved at that version — a dirty
     * buffer whose save was refused must not be retried on every poll.
     */
    fun due(dirty: Collection<String>, delayMs: Long, now: Long): List<String> =
        dirty.filter { key ->
            val entry = seen[key] ?: return@filter false
            !entry.saved && now - entry.changedAt >= delayMs
        }

    /** A save went out for [key] at its current version. */
    fun saved(key: String) {
        seen[key]?.saved = true
    }

    /** Only these keys survive — the open tabs, after a close or a project switch. */
    fun retain(keys: Collection<String>) {
        seen.keys.retainAll(keys.toSet())
    }
}
