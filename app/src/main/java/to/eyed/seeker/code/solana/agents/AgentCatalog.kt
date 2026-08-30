package to.eyed.seeker.code.solana.agents

/**
 * The three ACP agents Seeker IDE will install for you, as data.
 *
 * The inherited panel is agent-agnostic on purpose — every agent is an
 * `agent_servers` entry in settings.json, nothing is named in code, and
 * nothing installs itself ([to.eyed.seeker.code.core.Agents]'s doc comment,
 * DECISIONS.md 2026-08-18). That mechanism is untouched: this table adds a
 * *shortcut* for the three agents that have an arm64 Linux build, because
 * "install a coding agent" is otherwise a terminal session most people will
 * not sit through on a phone (docs/SOLANA.md, "Agents"). Everything installed
 * from here ends up in the same `agent_servers` map a hand-written entry lands
 * in, keyed by [name], and the panel cannot tell the two apart.
 *
 * Three agents and no more, and each one for a measured reason: these are the
 * only ACP agents with a published `aarch64` Linux build or an npm package
 * that runs on Debian's Node. Anything else stays a manual entry — which is
 * not a restriction, because the manual route is the whole mechanism.
 */
data class InstallableAgent(
    /** Stable id: the guest directory, the state map's key, nothing user-visible. */
    val id: String,
    /**
     * The `agent_servers` key, which is also what the panel and the settings
     * screen print. Spaces are fine — the name travels whole through
     * [to.eyed.seeker.code.core.AppSettings.saveAgent], never through the
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
    val binary: String
        get() = when (method) {
            is AgentInstallMethod.Npm -> method.binary
            is AgentInstallMethod.ReleaseTarball -> method.binary
        }

    /**
     * Where a tarball agent is unpacked, and therefore the command the entry
     * carries. An npm agent has no directory of ours: npm owns
     * `/usr/local/lib/node_modules` and puts its own shims on the PATH, so
     * that command is resolved with `command -v` after the install instead.
     */
    val installDir: String get() = "$GUEST_INSTALL_ROOT/$id"

    companion object {
        /**
         * Where tarball agents live inside the guest. `/opt` because that is
         * where Debian policy puts software the distribution did not package,
         * and one directory per agent because removing one must not touch the
         * others.
         */
        const val GUEST_INSTALL_ROOT = "/opt/seeker/agents"
    }
}

/**
 * The two shapes an install takes. Both end in a program on the guest's
 * filesystem that speaks ACP over stdio; what differs is who fetches it.
 */
sealed interface AgentInstallMethod {

    /**
     * npm, globally, inside the guest — which means Node has to be there
     * first. Debian's own `nodejs`/`npm` rather than a tarball from
     * nodejs.org: apt already works in the userland, the package is a few
     * tens of megabytes, and it is one thing we then do not maintain. This is
     * the Node install [to.eyed.seeker.code.core.Apt]'s doc comment was
     * written in anticipation of ("Phase 6 needs the same thing for Node").
     */
    data class Npm(
        /** Given to `npm install -g`, in this order. */
        val packages: List<String>,
        /** The shim npm puts on the PATH — resolved with `command -v`, not assumed. */
        val binary: String,
    ) : AgentInstallMethod

    /**
     * A release asset of a GitHub repository, downloaded by the *app* and
     * unpacked by the guest.
     *
     * The download is on this side rather than `curl` inside the guest for
     * two reasons: Debian's slim base image has no curl and no wget (adding
     * one is an apt install before anything can even start), and a
     * `HttpURLConnection` loop is the only way to get a real fraction on the
     * progress bar and a cancel that stops within a chunk — the same loop
     * `DebianUserland.downloadLayer` runs for the rootfs itself.
     */
    data class ReleaseTarball(
        /** `owner/repo`, for the releases API. */
        val repo: String,
        /**
         * The end of the asset's file name. Matched as a suffix rather than
         * whole, because both projects put a version in the middle of it
         * (`spettro_v0.4.1_linux_arm64.tar.gz`), and matching the tail is
         * what keeps the `.sha256` and `.sig` siblings out.
         */
        val assetSuffix: String,
        /** What the entry's command will be called inside [InstallableAgent.installDir]. */
        val binary: String,
        /**
         * `find -name` pattern for the executable inside the archive. Codex
         * ships its binary under the target triple it was built for
         * (`codex-aarch64-unknown-linux-musl`), so the name in the tarball is
         * not the name we want to run; the install links one to the other
         * rather than assuming either.
         */
        val archiveGlob: String,
    ) : AgentInstallMethod
}

/**
 * How an agent is asked to speak ACP, and how that is *checked* rather than
 * assumed.
 *
 * A subcommand is not a stable interface: Codex has moved its ACP entry point
 * more than once, and an `agent_servers` entry naming the wrong one fails at
 * the first prompt with a usage message the user has no way to read (the
 * panel shows the agent's last line of stderr —
 * [to.eyed.seeker.code.core.Agents.looksLikeMissingProgram]). So the install
 * runs the program's own `--help` in the guest and looks for the flag it is
 * about to write. When none of the candidates is mentioned, [fallback] is
 * written anyway and the screen says the help did not confirm it: refusing to
 * install because a help text changed wording would be worse than an entry
 * the user can edit.
 */
data class AcpEntry(
    /** Tried in order; the first [AcpCandidate] whose token the help mentions wins. */
    val candidates: List<AcpCandidate>,
    /** Written when the help mentions none of them, and when there is nothing to probe. */
    val fallback: List<String>,
) {
    /** No candidates means no `--help` run at all — see [AgentCatalog.CLAUDE_CODE]. */
    val probesHelp: Boolean get() = candidates.isNotEmpty()
}

/** One way of invoking ACP, and the word in `--help` that proves it exists. */
data class AcpCandidate(val args: List<String>, val helpToken: String)

object AgentCatalog {

    /**
     * Claude Code, through Zed's own ACP adapter.
     *
     * Two npm packages, not one: `@anthropic-ai/claude-code` is the CLI, and
     * `@zed-industries/claude-code-acp` is the adapter that speaks ACP to us
     * and drives the CLI underneath. The adapter is what `agent_servers`
     * names; installing only the adapter gives a program that starts and then
     * cannot find the agent it is adapting.
     *
     * No `--help` probe: the adapter is an ACP server and nothing else, so it
     * takes no arguments — and a program whose whole job is to serve stdio is
     * exactly the one that would answer `--help` by starting a session and
     * waiting forever. (The version probe guards against that with `timeout`
     * and a closed stdin regardless; see [AgentInstaller].)
     */
    val CLAUDE_CODE = InstallableAgent(
        id = "claude-code",
        name = "Claude Code",
        summary = "Anthropic's coding agent, through Zed's ACP adapter.",
        homepage = "github.com/anthropics/claude-code",
        installs = "Node from apt, then the claude-code and claude-code-acp npm packages.",
        method = AgentInstallMethod.Npm(
            packages = listOf("@anthropic-ai/claude-code", "@zed-industries/claude-code-acp"),
            binary = "claude-code-acp",
        ),
        acp = AcpEntry(candidates = emptyList(), fallback = emptyList()),
    )

    /**
     * Codex, which speaks ACP itself.
     *
     * The musl build rather than the gnu one: it is statically linked, so it
     * does not care what glibc the rootfs has, and it is the asset OpenAI
     * publishes for `aarch64` Linux.
     */
    val CODEX = InstallableAgent(
        id = "codex",
        name = "Codex",
        summary = "OpenAI's coding agent. Speaks ACP natively.",
        homepage = "github.com/openai/codex",
        installs = "The codex binary for aarch64 Linux, from the latest release.",
        method = AgentInstallMethod.ReleaseTarball(
            repo = "openai/codex",
            assetSuffix = "aarch64-unknown-linux-musl.tar.gz",
            binary = "codex",
            archiveGlob = "codex*",
        ),
        acp = AcpEntry(
            candidates = listOf(AcpCandidate(listOf("acp"), "acp")),
            fallback = listOf("acp"),
        ),
    )

    /**
     * Spettro, which speaks ACP behind a flag rather than a subcommand.
     */
    val SPETTRO = InstallableAgent(
        id = "spettro",
        name = "Spettro",
        summary = "Aploide's coding agent. Speaks ACP with --acp.",
        homepage = "github.com/aploide/spettro",
        installs = "The spettro binary for arm64 Linux, from the latest release.",
        method = AgentInstallMethod.ReleaseTarball(
            repo = "aploide/spettro",
            assetSuffix = "_linux_arm64.tar.gz",
            binary = "spettro",
            archiveGlob = "spettro*",
        ),
        acp = AcpEntry(
            candidates = listOf(AcpCandidate(listOf("--acp"), "--acp")),
            fallback = listOf("--acp"),
        ),
    )

    /** In the order the screen lists them. */
    val ALL: List<InstallableAgent> = listOf(CLAUDE_CODE, CODEX, SPETTRO)

    /** The catalogue entry for an `agent_servers` name, or null for a hand-written one. */
    fun forName(name: String): InstallableAgent? = ALL.firstOrNull { it.name == name }
}
