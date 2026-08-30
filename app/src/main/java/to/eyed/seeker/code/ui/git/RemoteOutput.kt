package to.eyed.seeker.code.ui.git

import to.eyed.seeker.code.core.RemoteOpResult

/**
 * Turn what a remote command said into the sentence a toast shows — Zed's
 * `remote_output::format_output`, rule for rule
 * (crates/git_ui/src/remote_output.rs:82-186).
 *
 * Pure, and deliberately: every rule below is a string match over git's own
 * output — where "Already up to date." lands, which stream a push's summary is
 * on — and none of it needs a device to be checked.
 */

/** Which command ran, with what the message templates need to name. */
sealed interface RemoteAction {
    /** [remote] is null for fetch-all — "Synchronized with remotes". */
    data class Fetch(val remote: String?) : RemoteAction

    data class Pull(val remote: String) : RemoteAction

    data class Push(val branch: String, val remote: String) : RemoteAction

    /** "fetch" / "pull" / "push" — the word in "git {name} failed" and the log title. */
    val name: String
        get() = when (this) {
            is Fetch -> "fetch"
            is Pull -> "pull"
            is Push -> "push"
        }
}

/**
 * The credential dialog's title for a command — what Zed hands its
 * `AskPassModal` as the operation: the bare `git fetch` (git_panel.rs:3899),
 * `git pull <remote>` (4072) and `git push <remote>` (4164).
 */
fun askpassTitle(action: RemoteAction): String = when (action) {
    is RemoteAction.Fetch -> "git fetch"
    is RemoteAction.Pull -> "git pull ${action.remote}"
    is RemoteAction.Push -> "git push ${action.remote}"
}

/** How the toast is dressed, beyond its message — Zed's `SuccessStyle`. */
sealed interface RemoteToastStyle {
    /** Nothing but the message ("Already up to date"). */
    data object Plain : RemoteToastStyle

    /** A View Log button showing the command's two streams. */
    data object WithLog : RemoteToastStyle

    /**
     * A push whose remote offered a pull/merge-request URL: the button is
     * [label] and opens [url].
     */
    data class PullRequestLink(val label: String, val url: String) : RemoteToastStyle
}

/** The toast: its sentence, and what rides along. */
data class RemoteToast(val message: String, val style: RemoteToastStyle)

/**
 * The hints a remote prints under `remote:` lines when a push could become a
 * pull request, and the button label each earns (remote_output.rs:7-16).
 */
private val PULL_REQUEST_HINTS = listOf(
    // GitHub: "Create a pull request for 'branch' on GitHub by visiting:"
    "Create a pull request" to "Create Pull Request",
    // Bitbucket: "Create pull request for branch:"
    "Create pull request" to "Create Pull Request",
    // GitLab: "To create a merge request for branch, visit:"
    "create a merge request" to "Create Merge Request",
    // GitLab: "View merge request for branch:"
    "View merge request" to "View Merge Request",
)

/** Zed's `format_output`, over a *successful* command's two streams. */
fun formatRemoteOutput(action: RemoteAction, output: RemoteOpResult): RemoteToast = when (action) {
    is RemoteAction.Fetch ->
        if (output.stderr.isEmpty()) {
            // A fetch reports on stderr; silence means nothing moved.
            RemoteToast("Fetch: Already up to date", RemoteToastStyle.Plain)
        } else {
            val message = when (action.remote) {
                null -> "Synchronized with remotes"
                else -> "Synchronized with ${action.remote}"
            }
            RemoteToast(message, RemoteToastStyle.WithLog)
        }

    is RemoteAction.Pull -> formatPull(action.remote, output)

    is RemoteAction.Push ->
        if (output.stderr.endsWith("Everything up-to-date\n")) {
            RemoteToast("Push: Everything is up-to-date", RemoteToastStyle.Plain)
        } else {
            val link = extractPullRequestLink(output.stderr)
            RemoteToast(
                "Pushed ${action.branch} to ${action.remote}",
                if (link != null) {
                    RemoteToastStyle.PullRequestLink(link.first, link.second)
                } else {
                    RemoteToastStyle.WithLog
                },
            )
        }
}

/**
 * A pull's message reads its *stdout*: the merge summary is there, while the
 * fetch progress scrolls by on stderr (remote_output.rs:101-166).
 */
private fun formatPull(remote: String, output: RemoteOpResult): RemoteToast {
    // "3 files changed, …" — the first word of the last stdout line, when it
    // is a number; the fallback message does without it, as Zed's does.
    val filesChanged = output.stdout.lines().lastOrNull { it.isNotBlank() }
        ?.trim()?.substringBefore(' ')?.toIntOrNull()
    val counted = filesChanged?.let { "$it file change${if (it == 1) "" else "s"}" }
    return when {
        output.stdout.endsWith("Already up to date.\n") ->
            RemoteToast("Pull: Already up to date", RemoteToastStyle.Plain)

        output.stdout.startsWith("Updating") ->
            RemoteToast(
                if (counted != null) "Received $counted from $remote" else "Fast forwarded from $remote",
                RemoteToastStyle.WithLog,
            )

        output.stdout.startsWith("Merge") ->
            RemoteToast(
                if (counted != null) "Merged $counted from $remote" else "Merged from $remote",
                RemoteToastStyle.WithLog,
            )

        output.stdout.contains("Successfully rebased") ->
            RemoteToast("Successfully rebased from $remote", RemoteToastStyle.WithLog)

        else -> RemoteToast("Successfully pulled from $remote", RemoteToastStyle.WithLog)
    }
}

/**
 * The strip's sentence for a remote command that *failed* — Zed's "git
 * {fetch|pull|push} failed" headline (notifications.rs:36-73), with git's own
 * words carried along, because the bare exit status ("git exited with 2")
 * explains nothing about a network that was down or a ref that was rejected.
 */
fun remoteFailureMessage(action: RemoteAction, output: RemoteOpResult): String {
    val detail = remoteFailureDetail(output) ?: output.error
    return when (detail) {
        null -> "git ${action.name} failed"
        else -> "git ${action.name} failed: $detail"
    }
}

/**
 * What git itself said about the failure, distilled: its `fatal:`/`error:`
 * lines when it wrote any — those carry the reason, while the rest of stderr
 * is fetch progress that would push them past the strip's ellipsis — else the
 * last non-blank line, which is where a one-line refusal lands. Null when the
 * streams are empty, which is an engine-level refusal whose [RemoteOpResult
 * .error] already says everything there is.
 */
internal fun remoteFailureDetail(output: RemoteOpResult): String? {
    val said = output.stderr.ifBlank { output.stdout }
    val lines = said.lines().map(String::trim).filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val telling = lines.filter { it.startsWith("fatal:") || it.startsWith("error:") }
    return (telling.ifEmpty { lines.takeLast(1) }).joinToString("\n")
}

/**
 * The label and URL of a push's pull-request hint, or null. Only `remote:`
 * lines count — an SSH warning with a URL in it must not become a button —
 * and the URL must follow a line (or share one) with a known hint, which is
 * Zed's pending-label walk (remote_output.rs:46-70).
 */
internal fun extractPullRequestLink(stderr: String): Pair<String, String>? {
    var pendingLabel: String? = null
    for (line in stderr.lines()) {
        val remoteLine = line.trimStart().removePrefix("remote:")
        if (remoteLine == line.trimStart()) {
            // Not a `remote:` line: whatever hint was pending is stale.
            pendingLabel = null
            continue
        }
        PULL_REQUEST_HINTS.firstOrNull { (hint, _) -> remoteLine.contains(hint) }
            ?.let { (_, label) -> pendingLabel = label }
        val url = extractUrl(remoteLine)
        val label = pendingLabel
        if (url != null && label != null) return label to url
    }
    return null
}

/** The first http(s) URL in the line, trailing punctuation trimmed (remote_output.rs:72-80). */
internal fun extractUrl(line: String): String? {
    val start = line.indexOf("https://").takeIf { it >= 0 }
        ?: line.indexOf("http://").takeIf { it >= 0 }
        ?: return null
    return line.substring(start)
        .split(Regex("\\s"), limit = 2)
        .first()
        .trimEnd(',', '.', ')', ']', '>')
        .takeIf { it.isNotEmpty() }
}
