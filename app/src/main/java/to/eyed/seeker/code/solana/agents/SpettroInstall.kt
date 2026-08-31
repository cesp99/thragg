package to.eyed.seeker.code.solana.agents

import android.content.Context
import android.util.Log
import to.eyed.seeker.code.core.AgentDefinition
import to.eyed.seeker.code.core.Agents
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.solana.toolchain.SolanaToolchain
import to.eyed.seeker.code.terminal.GuestProcess
import to.eyed.seeker.code.terminal.ShellCommand
import to.eyed.seeker.code.terminal.Userland

/**
 * Getting from "the binary is on disk" to "the panel has an agent to talk to".
 *
 * The *download* is not here: `spettro` is a component of the toolchain
 * manifest like any other (assets/solana/toolchain/manifest.json, id
 * `spettro`), fetched, hashed and unpacked by `ToolchainInstaller` into
 * exactly [InstallableAgent.installDir]. What is left afterwards is three
 * things that are this file's, and each of them is a way a working install can
 * still produce an agent that never answers:
 *
 *  1. **Probe the flag.** ACP is behind `-acp`, and a flag is not a stable
 *     interface. The program's own `--help` is asked before the entry is
 *     written ([AcpEntry]), so a release that renames it fails here — where
 *     there is a sentence to show — rather than at the first prompt, where the
 *     user sees a usage message they cannot act on.
 *  2. **Assert `$HOME`.** Spettro keeps its config, its encrypted API keys and
 *     its sessions under `~/.spettro`. With no writable home the handshake
 *     succeeds and every write silently fails — see [Agents.homeProblem] and
 *     docs/SPETTRO.md, W-14.
 *  3. **Write the `agent_servers` entry.** Through
 *     [AppSettings.saveAgent] and nothing else, so the bundled agent is a
 *     pre-filled row in the same settings map a hand-typed one lands in. The
 *     panel cannot tell them apart, and removing the entry by hand removes the
 *     agent — which is the point of not special-casing it.
 *
 * Everything here **blocks**: it stats the rootfs, spawns proot and writes
 * settings through the engine. Call it on `Dispatchers.IO`.
 */
object SpettroInstall {

    private const val TAG = "seeker-agent"

    /** How long the guest gets to answer `--help` before it is killed. */
    private const val PROBE_TIMEOUT_SECONDS = "10"

    /** What the entry is called, and therefore how it is found again. */
    val NAME: String get() = AgentCatalog.SPETTRO.name

    /** The absolute guest path of the program. */
    val PROGRAM: String get() = AgentCatalog.SPETTRO.program

    /**
     * What happened, in the words the setup screen shows.
     *
     * [Registered] carries the definition the panel will launch, so the caller
     * does not have to re-read settings to learn what it just wrote.
     * [Unconfirmed] is a *success*: the entry is written and usable, and the
     * screen says only that the help text did not mention the flag — refusing
     * to install because a wording changed would be worse than an entry the
     * user can edit.
     */
    sealed interface Result {
        data class Registered(val agent: AgentDefinition) : Result
        data class Unconfirmed(val agent: AgentDefinition, val note: String) : Result
        data class Failed(val message: String) : Result
    }

    /** Whether the binary is where the entry would point, host-side. */
    fun isInstalled(context: Context): Boolean =
        SolanaToolchain.hostPath(context, PROGRAM).exists()

    /**
     * The entry already in settings, if it names the program we would write.
     *
     * Matched on the command rather than on the name alone: an entry left
     * behind by an older install (a different path, an npm shim from the two
     * agents that used to be in the catalogue) is *stale*, and answering
     * "already registered" for it would leave the panel launching something
     * that is not there.
     */
    fun registered(settings: AppSettings): AgentDefinition? =
        settings.agents.firstOrNull { it.name == NAME && it.argv.firstOrNull() == PROGRAM }

    /**
     * Make sure the bundled agent is installed *and* registered, and say which
     * of the two failed when one does. **Blocking.**
     *
     * Idempotent, and cheap when there is nothing to do: a settings read and a
     * `stat`. It is called on the way into the agent screen, so it must cost
     * nothing on the ninety-ninth launch.
     */
    fun ensureRegistered(context: Context): Result {
        val app = context.applicationContext
        if (!Userland.backend.isSupported) {
            return Result.Failed(
                "This build has no Linux userland, so it cannot run an agent."
            )
        }
        if (!isInstalled(app)) {
            return Result.Failed(
                "Spettro is not installed yet. Install it from Setup — it is a 15 MB " +
                    "download and needs no Node, no Python and no compiler."
            )
        }
        Agents.homeProblem(SolanaToolchain.hostPath(app, Agents.GUEST_HOME))?.let { problem ->
            // Deliberately fatal rather than a warning. An agent launched
            // without a writable home is the failure mode with no symptom:
            // it starts, it answers, and it forgets the API key that was
            // typed into it.
            return Result.Failed(problem)
        }
        val settings = runCatching { AppSettings.load() }.getOrElse { error ->
            Log.w(TAG, "could not read settings while registering the agent", error)
            return Result.Failed("Seeker IDE could not read its settings.")
        }
        registered(settings)?.let { return Result.Registered(it) }

        val probe = probeAcpArgs(app)
        val args = (probe.args ?: AgentCatalog.SPETTRO.acp.fallback) + listOf(
            // Absolute, because `session/new` answers -32602 for a relative
            // cwd — and the token, because the row in settings.json is one
            // static thing while the project changes with every thread.
            "--cwd",
            Agents.PROJECT_ROOT_TOKEN,
        )
        val written = runCatching {
            AppSettings.saveAgent(
                name = NAME,
                command = PROGRAM,
                args = args,
                env = launchEnvironment(),
            )
        }.getOrElse { error ->
            Log.w(TAG, "could not write the agent_servers entry", error)
            null
        } ?: return Result.Failed("Seeker IDE could not save the agent to its settings.")

        val agent = registered(written) ?: return Result.Failed(
            "The agent entry was written but came back unreadable; open Settings → " +
                "Edit settings.json and check `agent_servers`."
        )
        return probe.note?.let { Result.Unconfirmed(agent, it) } ?: Result.Registered(agent)
    }

    /**
     * The environment the entry carries, which the engine layers *over* the
     * guest login environment (acp.rs `start_agent`).
     *
     * `HOME` is restated even though `guest.rs` already exports `/root` for
     * every guest process. It is one line and it removes a whole class of
     * silent failure: the day somebody changes the guest's default home, or
     * runs the agent through a path that does not go through `login_environment`,
     * the agent still writes its keys where this app expects to find them.
     * The engine's layering makes this the last writer, so it wins.
     */
    fun launchEnvironment(): Map<String, String> = mapOf(
        "HOME" to Agents.GUEST_HOME,
        // The agent shells out — `git`, `cargo`, the tools it runs for you —
        // and the toolchain's own directories are not on Debian's PATH.
        "PATH" to "${SolanaToolchain.GUEST_PATH_PREFIX}:${SolanaToolchain.GUEST_BASE_PATH}",
    )

    /** What [probeAcpArgs] learned: the args to write, and why they may be wrong. */
    private data class Probe(val args: List<String>?, val note: String?)

    /**
     * Run the program's own `--help` in the guest and pick the first candidate
     * flag it mentions.
     *
     * Under `timeout` and with stdin closed, because the one program most
     * likely to ignore `--help` is a program whose whole job is to serve
     * stdio: an ACP binary that decided `--help` meant "start a session" would
     * otherwise block this call for ever. Ten seconds is far more than a Go
     * `flag` usage string needs and far less than a user will wait.
     *
     * Never throws. A probe that could not run answers "I do not know", the
     * caller writes [AcpEntry.fallback], and the screen says the help did not
     * confirm it.
     */
    private fun probeAcpArgs(app: Context): Probe {
        val entry = AgentCatalog.SPETTRO.acp
        if (!entry.probesHelp) return Probe(entry.fallback, null)
        val help = runCatching { runHelp(app) }.getOrElse { error ->
            Log.w(TAG, "the agent's --help could not be run", error)
            null
        } ?: return Probe(
            null,
            "Seeker IDE could not run the agent's own help, so it wrote the usual " +
                "flag (${entry.fallback.joinToString(" ")}) without confirming it.",
        )
        val match = entry.candidates.firstOrNull { it.helpToken in help }
        if (match != null) return Probe(match.args, null)
        return Probe(
            null,
            "The agent's help did not mention ${entry.fallback.joinToString(" ")}, " +
                "which is the flag Seeker IDE wrote anyway. If the first message fails " +
                "with a usage error, fix `agent_servers` in Settings → Edit settings.json.",
        )
    }

    /** The merged stdout+stderr of `timeout 10 <program> --help`, lowercased. */
    private fun runHelp(app: Context): String? {
        val command: ShellCommand = Userland.backend.execCommandRealLinks(
            app,
            null,
            listOf("/usr/bin/timeout", PROBE_TIMEOUT_SECONDS, PROGRAM, "--help"),
            SolanaToolchain.guestEnvironment(),
        ) ?: return null
        val output = StringBuilder()
        GuestProcess.run(command) { line ->
            // A usage string is a few hundred bytes; the cap is only there so
            // a program that answered with a log stream cannot be a memory
            // problem as well as a wrong one.
            if (output.length < 64_000) output.append(line).append('\n')
        }
        // The exit status is deliberately ignored: `flag` prints its usage and
        // exits 2, and a nonzero status from a help text is the normal case.
        return output.toString().lowercase()
    }
}
