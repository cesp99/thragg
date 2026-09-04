package to.eyed.thragg.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The credential prompts a running git or ssh is waiting on, and the one
 * the dialog is showing.
 *
 * The engine holds the prompt (askpass.rs): git runs with `GIT_ASKPASS`
 * naming a helper that hands the question to the engine and blocks until
 * it is answered. This object is the polling half of that, the way every
 * engine state reaches the screen — [CoreBridge.gitPendingPrompt] is asked
 * a few times a second, but *only* while a command that can prompt is
 * running ([during]), so the idle app makes no JNI calls for this at all.
 *
 * Zed's equivalent is the `AskPassDelegate` each remote command is handed
 * (git_panel.rs:4292-4330), which opens `AskPassModal` per prompt; the
 * dialog in `ui/git/AskpassDialog.kt` reads [pending] instead, from
 * wherever in the tree it sits, which is what lets one dialog serve the
 * panel's push and the project picker's clone alike.
 */
object GitAskpass {

    /** ~7 Hz: a prompt shows within a blink, and the call is a lock and a `stat`. */
    private const val POLL_MS = 150L

    /**
     * The prompt on screen, or null. Main-thread state: written by [during]
     * on the main dispatcher, read by the composition.
     */
    var pending by mutableStateOf<AskpassPrompt?>(null)
        private set

    /** How many [during] blocks are running — each keeps the poll alive. */
    private var watchers = 0

    /**
     * Run [block] — a blocking git command, on whatever dispatcher the caller
     * chose — while watching for the prompts it raises, under [operation]
     * as the dialog's title ("git push origin", as Zed titles its modal,
     * git_panel.rs:4164). The poll stops, and the dialog with it, the moment
     * the block returns: a prompt whose git is gone has nothing to answer.
     */
    suspend fun <T> during(operation: String, block: suspend () -> T): T = coroutineScope {
        val poll = launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { watchers++ }
            try {
                while (isActive) {
                    val json = CoreBridge.gitPendingPrompt()
                    val prompt = json?.let { AskpassPrompt.parse(it, operation) }
                    withContext(Dispatchers.Main) {
                        // Keep the object the dialog is editing: a new one
                        // per poll would reset the text field under the
                        // user's fingers.
                        if (pending?.id != prompt?.id) pending = prompt
                    }
                    delay(POLL_MS)
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    watchers--
                    if (watchers == 0) pending = null
                }
            }
        }
        try {
            block()
        } finally {
            poll.cancel()
        }
    }

    /**
     * Answer the prompt on screen. The dialog closes when the next poll finds
     * the prompt gone — or the next one waiting, which is how a username
     * question is followed by its password.
     */
    fun answer(prompt: AskpassPrompt, answer: String, remember: Boolean) {
        // Off the main thread: the engine's answer waits, briefly, for the
        // helper to open its reply pipe.
        Thread { CoreBridge.gitAnswerPrompt(prompt.id, answer, remember) }.start()
        if (pending?.id == prompt.id) pending = null
    }

    /** Refuse the prompt on screen; git or ssh fails with its own message. */
    fun cancel(prompt: AskpassPrompt) {
        Thread { CoreBridge.gitCancelPrompt(prompt.id) }.start()
        if (pending?.id == prompt.id) pending = null
    }
}

/** What a prompt is asking for — the engine's `PromptKind`, as it serializes. */
enum class AskpassKind {
    Username, Password, Passphrase, HostKey, Other;

    internal companion object {
        fun parse(kind: String): AskpassKind = when (kind) {
            "username" -> Username
            "password" -> Password
            "passphrase" -> Passphrase
            "host_key" -> HostKey
            else -> Other
        }
    }
}

/**
 * One prompt as the dialog draws it: the engine's record plus the operation
 * it belongs to.
 */
data class AskpassPrompt(
    val id: Long,
    /** The dialog's title — "git clone", "git push origin". */
    val operation: String,
    /** git's or ssh's own words, shown verbatim. */
    val prompt: String,
    val kind: AskpassKind,
    /** The host, key file or URL the prompt names; empty when it names none. */
    val subject: String,
    /** Whether the input is hidden — Zed's rule: everything but yes/no and Username. */
    val masked: Boolean,
    /** A remembered username to pre-fill, when the engine wants it corrected. */
    val suggestion: String?,
) {
    /**
     * Whether "Remember for this session" is offered: for a secret the
     * engine can key — a password for a host, a passphrase for a key file.
     * A username is kept for its host regardless, and a host-key "yes" is
     * never kept (askpass.rs, `Memory::learn`).
     */
    val rememberable: Boolean
        get() = (kind == AskpassKind.Password || kind == AskpassKind.Passphrase) &&
            subject.isNotEmpty()

    /**
     * What the text field starts with. A host-key question is answered
     * "yes" or nothing — ssh accepts the fingerprint too, but nobody types
     * one on a phone — so OK on the pre-filled "yes" is the accept; Cancel
     * is the refusal.
     */
    val initialText: String
        get() = suggestion ?: if (kind == AskpassKind.HostKey) "yes" else ""

    internal companion object {
        fun parse(json: String, operation: String): AskpassPrompt? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            return AskpassPrompt(
                id = root.optLong("id", -1L).takeIf { it >= 0 } ?: return null,
                operation = operation,
                prompt = root.optString("prompt"),
                kind = AskpassKind.parse(root.optString("kind")),
                subject = root.optString("subject"),
                masked = root.optBoolean("masked", true),
                suggestion = if (root.isNull("suggestion")) null else root.getString("suggestion"),
            )
        }
    }
}

/**
 * What the app's own `git clone` runs with so it can prompt — the engine's
 * askpass environment and its credential-cache option, from
 * [CoreBridge.gitAskpassSetup].
 */
data class AskpassSetup(
    /** `NAME=value` entries, appended to the clone's environment. */
    val environment: List<String>,
    /** Git-level options, placed before the subcommand. */
    val gitArgs: List<String>,
) {
    internal companion object {
        val NONE = AskpassSetup(emptyList(), emptyList())

        fun parse(json: String): AskpassSetup {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return NONE
            fun strings(key: String): List<String> {
                val array = root.optJSONArray(key) ?: return emptyList()
                return List(array.length()) { index -> array.getString(index) }
            }
            return AskpassSetup(environment = strings("env"), gitArgs = strings("args"))
        }

        /** The live one, from the engine. Cheap, but a JNI call. */
        fun current(): AskpassSetup = parse(CoreBridge.gitAskpassSetup())
    }
}
