package to.eyed.thragg.solana.chain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import to.eyed.thragg.terminal.TerminalSessions

/**
 * What keeps a deploy alive when the phone is in a pocket.
 *
 * A deploy is two hundred small transactions over five to ten minutes on the
 * public endpoint's rate limit, and Android has three ways to end it early:
 *
 *  1. **The process is cached and killed.** The terminal's foreground service
 *     is the app's one defence against that (terminal/TerminalService.kt),
 *     and BuildRunner already holds it for a build; [hold] holds it for the
 *     chain work that starts from Settings too — a close, a buffer reclaim.
 *  2. **The CPU sleeps.** With the screen off nothing keeps it awake, and a
 *     coroutine waiting on a socket read simply stops advancing until the
 *     next wake. A partial wake lock for the length of the work is the
 *     answer, with a ceiling so a bug cannot hold it for ever.
 *  3. **Doze suspends the network.** After a while off the charger and still,
 *     Android defers all network access except for apps the user has exempted
 *     from battery optimisation. Nothing an app does to itself lifts that;
 *     [requestUnrestricted] asks the user, once, from the Deploy sheet, and
 *     [isUnrestricted] is how the sheet knows whether to ask.
 *
 * Child processes are not involved in any of this — the chain layer is
 * Kotlin — so the phantom-process reaper that hunts a proot build has nothing
 * here to take.
 */
object BackgroundWork {

    /** Long enough for the slowest deploy seen on a phone, short enough to be a ceiling. */
    private const val WAKE_LOCK_MS = 30L * 60L * 1_000L

    /**
     * Run [block] with the foreground service held under [tag] and the CPU
     * awake. Both are released however [block] ends.
     */
    suspend fun <T> hold(context: Context, tag: String, block: suspend () -> T): T {
        val app = context.applicationContext
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "thragg:$tag")
        runCatching { lock?.acquire(WAKE_LOCK_MS) }
        runCatching { TerminalSessions.of(app).holdForBackgroundWork(tag, true) }
        try {
            return block()
        } finally {
            runCatching { TerminalSessions.of(app).holdForBackgroundWork(tag, false) }
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    /** Whether the user has exempted this app from battery optimisation (Doze). */
    fun isUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Ask Android to exempt this app. A system dialog, so it needs an
     * activity context or the new-task flag; the request is a no-op on a
     * device that has already granted it.
     */
    fun requestUnrestricted(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            // Some builds hide the per-app dialog; the list is the fallback.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
