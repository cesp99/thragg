package to.eyed.thragg.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import to.eyed.thragg.R

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

    /**
     * The second channel, and the reason `ask-first` is shippable at all.
     *
     * A parked question or permission **stops the turn**. With the phone in a
     * pocket that is indistinguishable from an app that has hung, and the
     * default-importance channel above does not vibrate — so a run could sit
     * blocked for an hour behind a sheet nobody was told about. This one is
     * IMPORTANCE_HIGH with a vibration pattern, and docs/SPETTRO.md step 5
     * makes it the precondition for shipping ask-first as the default
     * permission level rather than Restricted.
     */
    private const val WAITING_CHANNEL_ID = "agent_waiting"

    /**
     * Its own id, so it can be cancelled the moment the question is answered
     * without taking the ordinary "finished" notification with it — and so a
     * turn that finishes while a second prompt is still parked does not
     * replace the ask with a summary.
     */
    private const val WAITING_NOTIFICATION_ID = 3

    /** How long the phone buzzes: short, twice. Not a ring; it is not a call. */
    private val WAITING_VIBRATION = longArrayOf(0L, 180L, 120L, 180L)

    /** The extra the tap carries, read by `MainActivity` to open the panel. */
    const val EXTRA_OPEN_PANEL = "to.eyed.thragg.OPEN_AGENT_PANEL"

    /**
     * Create the channel. Idempotent; called at startup for the reason the
     * terminal's channel is (`TerminalService.ensureChannel`).
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Both channels or neither: an app updated from a build that had only
        // the first one must still get the second, so the early return tests
        // the *new* channel.
        if (manager.getNotificationChannel(WAITING_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "When an agent finishes a turn or is waiting for you"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                WAITING_CHANNEL_ID,
                "Agent needs an answer",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "When the agent has stopped and is waiting for a decision"
                enableVibration(true)
                vibrationPattern = WAITING_VIBRATION
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

    /**
     * The agent is blocked on the user and the user is not looking.
     *
     * Separate from [notify] rather than a flag on it, because the two say
     * different things and only one of them is urgent: "Finished" can wait for
     * the next time the phone is picked up, while "Waiting for you" is a run
     * that has stopped and will not restart on its own.
     */
    fun waiting(context: Context, title: String, message: String) {
        val app = context.applicationContext
        ensureChannel(app)
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_OPEN_PANEL, true)
        val tap = PendingIntent.getActivity(
            app,
            WAITING_NOTIFICATION_ID,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, WAITING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // On pre-O devices the channel does not exist and these two are
            // what make it heads-up and haptic; on O and later the channel
            // owns both and these are ignored.
            .setVibrate(WAITING_VIBRATION)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Not dismissible by a swipe: swiping away a blocked run does not
            // unblock it, and the notification is the only thing that says so.
            .setOngoing(true)
            .build()
        runCatching {
            app.getSystemService(NotificationManager::class.java)
                ?.notify(WAITING_NOTIFICATION_ID, notification)
        }
    }

    /** Answered, or dismissed by arriving at the screen. Take it down. */
    fun clearWaiting(context: Context) {
        runCatching {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.cancel(WAITING_NOTIFICATION_ID)
        }
    }

    /** What [waiting] says when [count] things are parked. */
    fun waitingMessage(count: Int): String = when {
        count <= 1 -> "Waiting for your answer - the turn is stopped until you reply."
        else -> "$count things are waiting for you - the turn is stopped until you reply."
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
