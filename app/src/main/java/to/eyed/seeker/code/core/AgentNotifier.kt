package to.eyed.seeker.code.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import to.eyed.seeker.code.R

/**
 * Telling the user the agent wants them while they are not looking at it —
 * Zed's agent notification pop-up (`AgentNotification`, agent_panel.rs:
 * 2692-2790), which appears when a turn finishes or a permission is asked
 * for while the window is not focused, and brings the panel forward when
 * clicked.
 *
 * Two shapes, by where the user is. With the app in the **background**, an
 * Android notification on its own channel (`agent`, default importance — it
 * is there to be noticed, unlike the terminal's keep-alive) whose tap brings
 * the app back and opens the panel. With the app in the **foreground** but
 * the panel hidden — another dock showing, or the phone-sized layout on a
 * different surface — a toast, because a system notification for an app
 * you are already in is noise.
 *
 * Gated by Zed's `agent.notify_when_agent_waiting`, read by
 * [AgentSessions.watch]; this object only draws.
 */
object AgentNotifier {
    private const val CHANNEL_ID = "agent"
    private const val NOTIFICATION_ID = 2

    /** The extra the tap carries, read by `MainActivity` to open the panel. */
    const val EXTRA_OPEN_PANEL = "to.eyed.seeker.code.OPEN_AGENT_PANEL"

    /**
     * Create the channel. Idempotent; called at startup for the reason the
     * terminal's channel is (`TerminalService.ensureChannel`).
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "When an agent finishes a turn or is waiting for you"
            }
        )
    }

    /**
     * Post the notification: [title] is the agent's name, [message] what
     * happened. One notification id, so a second event replaces the first
     * rather than stacking — the user wants to know the panel needs them,
     * not how many times.
     */
    fun notify(context: Context, title: String, message: String) {
        val app = context.applicationContext
        ensureChannel(app)
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_OPEN_PANEL, true)
        val tap = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // Posting without permission on Android 13+ is refused quietly by the
        // system, and `notify` itself throws on some builds without it:
        // denial costs the notification, never the session.
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification) }
    }

    /** Take the notification down — the panel is showing again. */
    fun dismiss(context: Context) {
        runCatching {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        }
    }

    /** The in-app shape: a toast, on the main thread. */
    fun toast(context: Context, title: String, message: String) {
        runCatching { Toast.makeText(context.applicationContext, "$title: $message", Toast.LENGTH_LONG).show() }
    }
}
