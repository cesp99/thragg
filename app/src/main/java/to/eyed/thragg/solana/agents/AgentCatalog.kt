package to.eyed.thragg.solana.agents

/**
 * The one agent Thragg installs for you, as data.
 *
 * The inherited panel is agent-agnostic on purpose — every agent is an
 * `agent_servers` entry in settings.json, nothing is named in code, and
 * nothing installs itself ([to.eyed.thragg.core.Agents]'s doc comment,
 * DECISIONS.md 2026-08-18). **That mechanism is untouched and must stay
 * untouched**: this table adds a *shortcut* for the agent this app ships,
 * because "install a coding agent" is otherwise a terminal session most people
 * will not sit through on a phone (docs/SOLANA.md, "Agents"). What is
 * installed from here ends up in the same `agent_servers` map a hand-written
 * entry lands in, keyed by [name], and the panel cannot tell the two apart.
 *
 * **One agent, and the reason is the protocol rather than taste.** The table
 * used to carry Claude Code and Codex as well. Neither speaks ACP: Claude Code
 * needs a third-party Node adapter in front of it, and Codex has moved its ACP
 * entry point more than once and does not carry the extension surface the
 * agent screen is built on. Shipping them meant shipping Node-from-apt, a
 * per-agent capability branch through every sheet, and a picker whose two
 * other rows were worse than the default — for agents a user can still add by
 * hand in one settings entry (docs/SPETTRO.md, W-14; "Deliberately not
 * reproduced on a phone", last bullet). So: one bundled agent, no picker, and
 * the generic mechanism fully intact behind it.
 */
data class InstallableAgent(
    /** Stable id: the guest directory, the state map's key, nothing user-visible. */
    val id: String,
    /**
     * The `agent_servers` key, which is also what the panel and the settings
     * screen print. Spaces are fine — the name travels whole through
     * [to.eyed.thragg.core.AppSettings.saveAgent], never through the
     * dot-split key path.
     */
    val name: String,
    /** One line under the name: what this agent is. */
    val summary: String,
    /** Where it comes from, printed rather than opened — there is no browser here. */
    val homepage: String,
    /** What the install actually puts in the userland, said plainly before it runs. */
    val installs: String,
    /** How it gets there — see [AgentInstallMethod]. */
    val method: AgentInstallMethod,
    /** How it is told to speak ACP once it is there. */
    val acp: AcpEntry,
) {
    /** The binary the `agent_servers` entry will name, before the guest confirms it. */
    val binary: String get() = method.binary

    /** Where the agent is unpacked, and therefore the directory the entry names. */
    val installDir: String get() = "$GUEST_INSTALL_ROOT/$id"

    /**
     * The absolute guest path of the program itself — what `command` in the
     * `agent_servers` entry is, and what the toolchain manifest's `marker`
     * for this component has to agree with.
     *
     * Absolute rather than a bare name on the `PATH`: `/opt/seeker/agents` is
     * not on anybody's `PATH`, and resolving through one would make the entry
     * depend on a login shell we do not control.
     */
    val program: String get() = "$installDir/$binary"

    companion object {
        /**
         * Where agents live inside the guest. `/opt` because that is where
         * Debian policy puts software the distribution did not package, and
         * one directory per agent because removing one must not touch the
         * others.
         *
         * The same literal the toolchain manifest's `spettro` component
         * unpacks into (assets/solana/toolchain/manifest.json), so an agent
         * installed by Setup and one installed from here are the same file in
         * the same place.
         */
        const val GUEST_INSTALL_ROOT = "/opt/seeker/agents"
    }
}

/**
 * How an install happens. One shape, now that the npm route is gone with the
 * two agents that needed it — a sealed interface rather than a bare data class
 * because the next agent with an arm64 build may well not be a tarball, and
 * the `when` that would have to be re-introduced is the cheapest part.
 */
sealed interface AgentInstallMethod {

    /** The program the install ends with, inside [InstallableAgent.installDir]. */
    val binary: String

    /**
     * A release asset of a GitHub repository, downloaded by the *app* and
     * unpacked by the guest.
     *
     * The download is on this side rather than `curl` inside the guest for
     * two reasons: Debian's slim base image has no curl and no wget (adding
     * one is an apt install before anything can even start), and a
     * `HttpURLConnection` loop is the only way to get a real fraction on the
     * progress bar and a cancel that stops within a chunk — the same loop
     * `DebianUserland.downloadLayer` runs for the rootfs itself, and the one
     * `ToolchainInstaller` runs for every component of the toolchain.
     */
    data class ReleaseTarball(
        /** `owner/repo`, for the releases API. */
        val repo: String,
        /**
         * The end of the asset's file name. Matched as a suffix rather than
         * whole, because the version sits in the middle of it
         * (`spettro_v2.7.3_linux_arm64.tar.gz`), and matching the tail is
         * what keeps the `.sha256` and `.sig` siblings out.
         */
        val assetSuffix: String,
        /** What the entry's command is called inside [InstallableAgent.installDir]. */
        override val binary: String,
        /** `find -name` pattern for the executable inside the archive. */
        val archiveGlob: String,
    ) : AgentInstallMethod
}

/**
 * How an agent is asked to speak ACP, and how that is *checked* rather than
 * assumed.
 *
 * A flag is not a stable interface, and an `agent_servers` entry naming the
 * wrong one fails at the first prompt with a usage message the user has no way
 * to read (the panel shows the agent's last line of stderr —
 * [to.eyed.thragg.core.Agents.looksLikeMissingProgram]). So the install
 * runs the program's own `--help` in the guest and looks for the flag it is
 * about to write. When none of the candidates is mentioned, [fallback] is
 * written anyway and the screen says the help did not confirm it: refusing to
 * install because a help text changed wording would be worse than an entry the
 * user can edit.
 */
data class AcpEntry(
    /** Tried in order; the first [AcpCandidate] whose token the help mentions wins. */
    val candidates: List<AcpCandidate>,
    /** Written when the help mentions none of them, and when there is nothing to probe. */
    val fallback: List<String>,
) {
    /** No candidates means no `--help` run at all. */
    val probesHelp: Boolean get() = candidates.isNotEmpty()
}

/** One way of invoking ACP, and the word in `--help` that proves it exists. */
data class AcpCandidate(val args: List<String>, val helpToken: String)

object AgentCatalog {

    /**
     * Spettro, which speaks ACP behind a flag rather than a subcommand.
     *
     * **`-acp`, one dash.** Verified on the device. Spettro is a Go program
     * using the standard library's `flag` package, whose own help prints
     * `-acp`; docs/SPETTRO.md W-14 claims `--acp` works identically and on
     * this build it does not. One dash is what the help says and one dash is
     * what is written, so the probe token and the flag are the same string
     * and cannot drift apart.
     *
     * The `--cwd` that follows carries [to.eyed.thragg.core.Agents.PROJECT_ROOT_TOKEN]
     * rather than a path: the entry in settings.json is one static thing,
     * while the project it is pointed at changes with every thread. See
     * [to.eyed.thragg.core.AgentDefinition.forProjectRoot], which is the
     * only place the token is resolved.
     */
    val SPETTRO = InstallableAgent(
        id = "spettro",
        name = "Spettro",
        summary = "The agent built into Thragg. One static binary, no Node anywhere.",
        homepage = "github.com/aploide/spettro",
        installs = "The spettro binary for arm64 Linux, from the latest release.",
        method = AgentInstallMethod.ReleaseTarball(
            repo = "aploide/spettro",
            assetSuffix = "_linux_arm64.tar.gz",
            binary = "spettro",
            archiveGlob = "spettro*",
        ),
        acp = AcpEntry(
            candidates = listOf(AcpCandidate(listOf("-acp"), "-acp")),
            fallback = listOf("-acp"),
        ),
    )

    /**
     * Every agent with a one-tap install — one of them, and the list stays a
     * list so the screens that iterate it need no change if a second ever
     * earns its place.
     */
    val ALL: List<InstallableAgent> = listOf(SPETTRO)

    /** The catalogue entry for an `agent_servers` name, or null for a hand-written one. */
    fun forName(name: String): InstallableAgent? = ALL.firstOrNull { it.name == name }
}
