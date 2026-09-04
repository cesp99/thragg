package to.eyed.thragg.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import to.eyed.thragg.MainActivity
import to.eyed.thragg.R

/**
 * Keeps terminal sessions alive while the app is in the background.
 *
 * Android 12 and later kill the child processes of a *cached* app — the
 * "phantom process" reaper, with a 32-process cap across the device. A single
 * terminal session here is proot, plus bash, plus whatever the user ran, so a
 * build or an `apt install` is exactly the shape of thing that gets reaped the
 * moment you switch away to read documentation. A foreground service keeps the
 * process out of the cached bucket, which is the only mitigation an app can
 * apply to itself.
 *
 * It is honest about what it cannot do: the cap itself is a device setting
 * (`settings put global settings_enable_monitor_phantom_procs false`), and
 * aggressive vendor battery managers can still intervene. What this buys is
 * that ordinary backgrounding stops killing your build.
 *
 * The service holds no state — [TerminalSessions] owns the sessions, because
 * they must outlive both the composition and any single binding. This only
 * exists to hold the notification that keeps the process alive.
 */
class TerminalService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            // The notification's only affordance from outside the app: end every
            // session. TerminalPanelState.closeAll() calls back into sync(),
            // which stops this service.
            TerminalSessions.of(this).closeAll()
            return START_NOT_STICKY
        }
        val sessions = intent?.getIntExtra(EXTRA_SESSIONS, 1) ?: 1
        if (pendingStarts > 0) pendingStarts--
        // Always, and first: a service begun with startForegroundService()
        // that never reaches this call is a crash — "did not then call
        // Service.startForeground()" — and a run that failed within the same
        // moment it started (an offline phone, 2026-09-02) used to stop the
        // service before this line ran. With zero sessions it shows the
        // notification for one frame and takes it straight down again.
        startForeground(NOTIFICATION_ID, buildNotification(maxOf(sessions, 1)))
        if (sessions <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // The session state lives elsewhere and is not recoverable from an
        // Intent, so a restart after the process dies would be a lie.
        return START_NOT_STICKY
    }

    private fun buildNotification(sessions: Int): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (sessions == 1) "1 session running" else "$sessions sessions running"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Terminal")
            .setContentText(text)
            // A flat silhouette: the status bar tints this, and a launcher icon
            // would render as a white blob.
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop all", stopAll).build()
            )
            .apply {
                // Otherwise Android may hold the notification back for ten
                // seconds, which reads as "nothing happened".
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun createChannel() = ensureChannel(this)

    companion object {
        private const val CHANNEL_ID = "terminal-sessions"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_SESSIONS = "sessions"
        private const val ACTION_STOP_ALL = "to.eyed.thragg.STOP_ALL_SESSIONS"

        /**
         * Create the notification channel.
         *
         * Called at startup rather than when the service first runs: for apps
         * targeting API 32 or lower — which this app does, for the userland —
         * Android
         * only offers the user the notification prompt the first time an
         * activity starts *after* a channel exists. Creating it late means the
         * first session runs with its notification silently blocked.
         */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Terminal sessions",
                    // Low: this exists to keep the process alive, not to
                    // interrupt anyone.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description =
                        "Shown while a terminal session is running, so Android does not kill it"
                    setShowBadge(false)
                }
            )
        }

        /**
         * startForegroundService() calls that have not reached
         * [onStartCommand] yet. Both sides run on the main thread, so this
         * is a plain counter: while it is positive a stop must go *through*
         * the service (a start with zero sessions) rather than around it.
         */
        private var pendingStarts = 0

        /** Start or update the notification. Safe to call repeatedly. */
        fun sync(context: Context, sessionCount: Int) {
            val app = context.applicationContext
            if (sessionCount <= 0 && pendingStarts == 0) {
                app.stopService(Intent(app, TerminalService::class.java))
                return
            }
            val intent = Intent(app, TerminalService::class.java)
                .putExtra(EXTRA_SESSIONS, maxOf(sessionCount, 0))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pendingStarts++
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }
    }
}
