package to.eyed.seeker.code

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import to.eyed.seeker.code.core.AgentNotifier
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.ImportRequest
import to.eyed.seeker.code.core.IncomingFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.ui.theme.SeekerCodeByEyedTheme
import to.eyed.seeker.code.ui.theme.ThemeStore
import to.eyed.seeker.code.terminal.TerminalService
import to.eyed.seeker.code.ui.workspace.WorkspaceScreen

class MainActivity : ComponentActivity() {

    /** Asked at most once per activity; see [requestNotificationPermission]. */
    private var askedForNotifications = false

    /**
     * A file or text another app handed us, waiting for the workspace to
     * import it. Compose state rather than a field read once: `singleTask`
     * delivers a second share to the *running* activity through
     * [onNewIntent], and the workspace has to see that one too.
     */
    private val incoming = mutableStateOf<ImportRequest?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Denial is not fatal: the service still protects sessions, it
            // just does so invisibly. Nothing to undo, nothing to nag about.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else reaches the engine: it needs to know where the
        // app's private storage is (Android gives a process no $HOME, and the
        // Zed crates require one). Returns without waiting for the engine's
        // gpui runtime — that boots on its own thread while the first frame
        // composes, and the workspace's version-counter polling covers the
        // gap. What this call does pay for is loading the native library.
        CoreBridge.initialize(filesDir.absolutePath, BuildConfig.DEBUG)
        // Read once, synchronously: the theme is chosen from it, and loading
        // it asynchronously would mean painting the wrong theme first and
        // flashing to the right one. It is a single ~700-byte read of
        // app-private storage, not the kind of I/O the main thread must be
        // kept away from.
        val initialSettings = AppSettings.load()
        // Before any session exists: Android only offers the notification
        // prompt to an app targeting API 32 or lower once a channel exists and
        // an activity starts, and the terminal's foreground service wants that
        // notification visible. Cheap, and idempotent.
        TerminalService.ensureChannel(this)
        AgentNotifier.ensureChannel(this)
        // Only on a fresh start: after process death the system re-delivers
        // the launching intent, and a file the user already imported and
        // maybe deleted would come back as a second copy.
        if (savedInstanceState == null) incoming.value = IncomingFiles.requestFrom(intent)
        // A tap on the agent's notification, while the app was not running.
        if (intent?.getBooleanExtra(AgentNotifier.EXTRA_OPEN_PANEL, false) == true) {
            AgentSessions.requestPanel()
        }
        enableEdgeToEdge()
        setContent {
            var settings by remember { mutableStateOf(initialSettings) }
            // The theme names and the interface size used to live in app
            // preferences, because `Settings::theme` was a bare mode enum
            // with nowhere to put them. They are settings.json keys now, so
            // the first launch of a build that understands them carries the
            // old values across — once, and only over what the file does not
            // already say.
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    ThemeStore.migrateLegacyPreferences(this@MainActivity, settings)
                }?.let { settings = it }
            }
            SeekerCodeByEyedTheme(settings) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WorkspaceScreen(
                        settings = settings,
                        settingsPath = File(filesDir, "settings.json").absolutePath,
                        onSettingsChanged = { settings = it },
                        incoming = incoming.value,
                        onIncomingHandled = { incoming.value = null },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    /**
     * A share or open-with while the app is already running. `singleTask`
     * in the manifest routes it here rather than starting a second
     * activity, so the workspace — with its project and its tabs — is the
     * one that receives it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingFiles.requestFrom(intent)?.let { incoming.value = it }
        // The agent's notification, tapped while the app was running: the
        // workspace brings the panel forward.
        if (intent.getBooleanExtra(AgentNotifier.EXTRA_OPEN_PANEL, false)) {
            AgentSessions.requestPanel()
        }
    }

    // Whether the app has a window on screen decides the shape the agent's
    // "waiting for you" takes: a toast while it does, a notification when it
    // does not (AgentNotifier). Start/stop rather than resume/pause, because
    // a dialog over the activity is still "on screen".
    override fun onStart() {
        super.onStart()
        AgentSessions.appInForeground = true
    }

    override fun onStop() {
        super.onStop()
        AgentSessions.appInForeground = false
    }

    override fun onResume() {
        super.onResume()
        requestNotificationPermission()
    }

    /**
     * Ask for notifications, so the terminal's foreground service can show why
     * it is running. Denial is not fatal — the service still protects sessions,
     * it just does so invisibly — so this never blocks anything.
     *
     * **The `full` flavour cannot actually ask.** Measured on the Fold
     * (Android 17): the system starts `GrantPermissionsActivity`, which
     * displays and then finishes itself ~50 ms later with no UI and no
     * `USER_SET` flag — the permission controller does not show this dialog to
     * an app targeting API 32 or lower, which is exactly what the userland
     * costs us (targetSdk 28, see DECISIONS.md). There the user has to enable
     * notifications from system settings, and the service runs invisibly until
     * they do. The `play` flavour targets a modern API and gets a real prompt.
     *
     * Asked from `onResume` rather than `onCreate` so the request happens with
     * a window on screen; that is correct either way, and cost nothing to fix
     * while measuring the above.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (askedForNotifications) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
