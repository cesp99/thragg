package to.eyed.thragg

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
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
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import to.eyed.thragg.core.AgentNotifier
import to.eyed.thragg.ui.shell.agent.AgentSeams
import to.eyed.thragg.core.AgentSessions
import to.eyed.thragg.core.AppSettings
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.ImportRequest
import to.eyed.thragg.core.IncomingFiles
import to.eyed.thragg.solana.chain.ChainSeams
import to.eyed.thragg.solana.chain.SeedVaultWallet
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.thragg.ui.theme.ThraggTheme
import to.eyed.thragg.ui.theme.ThemeStore
import to.eyed.thragg.terminal.TerminalService
import to.eyed.thragg.terminal.Userland
import to.eyed.thragg.ui.shell.ThraggShell

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

    /** MWA's launcher: must be registered before the activity starts, like the permission above. */
    private val walletSender = ActivityResultSender(this)

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
        // The agent the user chose last time, back before the panel is looked
        // at. Returns at once — the preferences read is on the sessions' own IO
        // scope — and it stands down if anything has already chosen, so it
        // cannot race the bundled agent registering itself.
        AgentSessions.restoreChoice(this, initialSettings)
        // Before any session exists: Android only offers the notification
        // prompt to an app targeting API 32 or lower once a channel exists and
        // an activity starts, and the terminal's foreground service wants that
        // notification visible. Cheap, and idempotent.
        TerminalService.ensureChannel(this)
        AgentNotifier.ensureChannel(this)
        // The Agent destination's seams — `[ Fix with agent ]` on a failed
        // build and New program's "open a thread afterwards". Both are checked
        // by *other* screens before they navigate to Agent, so registering
        // them from Agent's own composition would make the first use of each
        // on a fresh install fall back to the clipboard and to a toast that
        // says no agent is set up. One call, no I/O (AgentSeams.install).
        AgentSeams.install()
        // The chain layer: the wallet gets the launcher registered above, and
        // its remembered address back off disk (a background read, like the
        // agent choice); the Build screen's Deploy gets its deployer.
        SeedVaultWallet.attach(walletSender)
        SeedVaultWallet.restore(this)
        ChainSeams.install()
        // Warm the guest's resolver file — and ResolverReach's probe cache —
        // before anything spawns in the userland. The rewrite now *probes*
        // each nameserver over TCP (a resolver that cannot speak TCP stalls
        // every guest lookup for minutes under `options use-vc`), and the
        // first terminal builds its command during composition: without this,
        // that first cold probe would ride the main thread. A daemon thread,
        // not a scope: it must not keep the process alive, and there is
        // nothing to cancel.
        Thread { runCatching { Userland.backend.refreshNetwork(this) } }
            .apply { isDaemon = true; name = "resolv-warm" }
            .start()
        // Only on a fresh start: after process death the system re-delivers
        // the launching intent, and a file the user already imported and
        // maybe deleted would come back as a second copy.
        if (savedInstanceState == null) incoming.value = IncomingFiles.requestFrom(intent)
        // A tap on the agent's notification, while the app was not running.
        //
        // Guarded and *consumed*, for the same reason the share above is, and
        // it was got wrong first: an activity's launching intent is sticky.
        // `singleTask` means `onNewIntent` calls `setIntent`, so one tap on
        // the agent's notification replaces this activity's intent for the
        // life of the task — and the system re-delivers that same intent every
        // time it recreates the activity. Measured on the Seeker: tap the
        // notification, go back to Code, let the process be killed (which on a
        // phone holding a 1.4 GB toolchain is routine), relaunch from the
        // launcher — and the app comes back on Agent. docs/UI.md is explicit
        // that Code is the start destination, because Code is the only one
        // that degrades honestly with no agent, no network and no toolchain.
        //
        // So: only on a fresh start, and the extra is removed once it has been
        // answered, so no later recreation can answer it twice.
        val openPanel = opensAgentPanel(
            freshStart = savedInstanceState == null,
            extra = intent?.getBooleanExtra(AgentNotifier.EXTRA_OPEN_PANEL, false) == true,
        )
        intent?.removeExtra(AgentNotifier.EXTRA_OPEN_PANEL)
        if (openPanel) AgentSessions.requestPanel()
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
            ThraggTheme(settings) {
                // No Scaffold. Material's would insert its own insets and its
                // own background between the window and the shell, and the
                // shell's whole layout argument is that it owns those: the
                // status bar and the cutout are padded once above the
                // destination, the gesture inset is padding under the nav bar,
                // and the IME is the editor's to lift its action row onto
                // (docs/UI.md, "Code with the soft keyboard up" — the vertical
                // budget is exact and there is no room in it for a second
                // opinion about insets).
                ThraggShell(
                    settings = settings,
                    settingsPath = File(filesDir, "settings.json").absolutePath,
                    onSettingsChanged = { settings = it },
                    incoming = incoming.value,
                    onIncomingHandled = { incoming.value = null },
                    modifier = Modifier.fillMaxSize(),
                )
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
        // shell brings the conversation forward. Consumed here too — `setIntent`
        // above has just made this the activity's sticky intent, and leaving
        // the extra on it is what sends a later recreation to Agent instead of
        // Code (see onCreate).
        val openPanel = intent.getBooleanExtra(AgentNotifier.EXTRA_OPEN_PANEL, false)
        intent.removeExtra(AgentNotifier.EXTRA_OPEN_PANEL)
        if (openPanel) AgentSessions.requestPanel()
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
     * **This app cannot actually ask.** Measured on the Fold (Android 17):
     * the system starts `GrantPermissionsActivity`, which displays and then
     * finishes itself ~50 ms later with no UI and no `USER_SET` flag — the
     * permission controller does not show this dialog to an app targeting API
     * 32 or lower, which is exactly what the userland costs us (targetSdk 28,
     * see DECISIONS.md). So the user has to enable notifications from system
     * settings, and the service runs invisibly until they do. The call stays:
     * it is free, it is correct on any future target, and the alternative is
     * code that silently does the wrong thing the day the target moves.
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

/**
 * Whether a launching intent carrying [extra] should take the shell to the
 * Agent destination, given whether this is a [freshStart].
 *
 * A value function because `android.content.Intent` cannot be built in a host
 * test and this rule is the whole of a bug that was measured on the device: an
 * activity's launching intent is sticky, `singleTask` makes `onNewIntent`
 * replace it, and the system re-delivers it on every recreation. Without the
 * `freshStart` half, one tap on the agent's notification sent every later
 * relaunch to Agent — and docs/UI.md is explicit that Code is the start
 * destination, because Code is the only one that degrades honestly with no
 * agent, no network and no toolchain.
 */
internal fun opensAgentPanel(freshStart: Boolean, extra: Boolean): Boolean =
    freshStart && extra
