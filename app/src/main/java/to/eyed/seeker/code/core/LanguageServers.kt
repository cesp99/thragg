package to.eyed.seeker.code.core

/**
 * A language server, and the Debian packages that put it on the guest's PATH.
 *
 * [server] is the binary the engine spawns — `core/crates/engine/src/lsp.rs`'s
 * `server_for`, which is the table this one exists to make installable — and
 * [grammars] are the engine's own grammar names, as `BufferSession.language`
 * reports them.
 *
 * **Every package is named.** `--no-install-recommends` is the house rule
 * (agent-docs/research/lsp-approach.md), and it is the reason [packages] is a
 * list rather than a string: `python3-pylsp` alone starts, initializes, reports
 * `sync Incremental` and then publishes *nothing at all*, because `import
 * pyflakes` fails inside it. A server that runs and says nothing is worse than
 * one that is missing, so its linter is named here beside it.
 *
 * The install machinery itself is [Apt]'s — shared with the agent runtime
 * (P6-3), because a second copy of "ask apt what this costs" would be a second
 * behaviour. What stays here is the table and the sentences.
 */
data class LanguageServerPackage(
    /** What to call the language in a sentence: "Python". */
    val language: String,
    /** The binary the engine will try to spawn (lsp.rs:107-147). */
    val server: String,
    /** Engine grammar names this server answers for. */
    val grammars: List<String>,
    /** Debian packages, in the order apt should be given them. */
    override val packages: List<String>,
    /** Why the extra packages are here, for the prompt's second line. */
    val note: String? = null,
) : AptTarget {

    /**
     * The question itself: "Python needs a language server — install
     * python3-pylsp and python3-pyflakes (~12.4 MB)?"
     *
     * The size is in the question rather than in a detail line for the reason
     * the clone dialog puts it there: on a phone, on a metered connection, it
     * is half of what the answer depends on. When apt could not say, the
     * question does not pretend it could.
     */
    override fun question(plan: AptPlan?): String {
        val size = Apt.formatBytes(plan?.downloadBytes)
        val cost = if (size != null) " (~$size)" else ""
        return "$language needs a language server — install $packageList$cost?"
    }

    /**
     * The line under the question: what apt will do, and what it could not
     * say.
     *
     * [userland] is what to call the guest — `Userland.backend.displayName`,
     * passed in rather than read here so this stays a pure function the host
     * tests can run without a flavour.
     */
    override fun detail(plan: AptPlan?, userland: String): String {
        val parts = mutableListOf<String>()
        note?.let { parts += it }
        val disk = Apt.formatBytes(plan?.diskBytes)
        if (disk != null) parts += "About $disk of the userland's storage will be used."
        if (plan != null && plan.missing.isNotEmpty()) {
            parts += "apt has not downloaded its package lists yet, so it cannot " +
                "say what this will cost until it has."
        }
        parts += "$server runs inside $userland, started by the editor " +
            "when a ${grammars.first()} file is open."
        return parts.joinToString(" ")
    }

    override fun installedMessage(): String =
        "$packageList installed. $server starts on its own for open ${grammars.first()} files."

    /**
     * Reached two ways: from the status bar saying a server could not start,
     * and from the palette by someone simply asking. Only the first means
     * anything is wrong, and this state cannot tell them apart — so the
     * sentence must be true either way rather than announcing a failure that
     * may not have happened.
     */
    override fun alreadyInstalledMessage(): String =
        "$packageList $packagesAre already installed. If $server still is not " +
            "working, running it in the terminal will say why."
}

/**
 * The grammar → apt package table, and the sentences said about it.
 *
 * P5-2's whole job, and deliberately small: four servers, each one verified to
 * exist in Debian stable/main on the device (agent-docs/research/
 * lsp-approach.md's table, checked with `apt-cache policy` inside the guest).
 * A grammar that is not here has no packaged server, which is a normal state
 * rather than a hole — we highlight far more languages than Debian packages a
 * server for.
 *
 * **Nothing in this file installs anything.** Zed asks first — it offers the
 * extension for a file type and waits ("Do you want to install the recommended
 * '{}' extension for '{}' files?", extensions_ui/src/extension_suggest.rs:176)
 * — and so does [LanguageServerInstaller]. A missing server is a prompt, never
 * a download that starts itself.
 */
object LanguageServers {

    /** Every server we can install, in the order the picker lists them. */
    val ALL: List<LanguageServerPackage> = listOf(
        LanguageServerPackage(
            language = "Rust",
            server = "rust-analyzer",
            grammars = listOf("rust"),
            // Debian stable/main, 1.85.0+dfsg3-1 — **and `cargo`**, which it
            // does not depend on and cannot work without: rust-analyzer
            // builds its crate graph by running `cargo metadata`, so without
            // cargo it starts, initializes, reports a clean file and answers
            // no completion at all. Proved on the emulator: with
            // rust-analyzer alone, `which cargo` says nothing and `x.` in a
            // Rust file offers nothing while the server sits there refreshing
            // semantic tokens. This is the pyflakes lesson again — a server
            // that runs and says nothing is the failure mode this table
            // exists to prevent.
            packages = listOf("rust-analyzer", "cargo"),
            note = "rust-analyzer reads the crate graph with cargo, so both are installed.",
        ),
        LanguageServerPackage(
            language = "C and C++",
            server = "clangd",
            // One server, two grammars — lsp.rs:126-127 sends both to clangd.
            grammars = listOf("c", "cpp"),
            // Debian stable/main, 1:19.0-63. The metapackage pulls the
            // versioned clangd-19; naming the versioned one instead would rot
            // at the next Debian release.
            packages = listOf("clangd"),
        ),
        LanguageServerPackage(
            language = "Python",
            server = "pylsp",
            grammars = listOf("python"),
            // Debian stable/main, 1.12.0-3 — and python3-pyflakes, which is
            // only a *recommendation* of it. With --no-install-recommends and
            // pylsp alone the server starts, initializes and publishes zero
            // diagnostics forever; this was proven on the device before the
            // table was written.
            packages = listOf("python3-pylsp", "python3-pyflakes"),
            note = "pylsp reports nothing without pyflakes, so both are installed.",
        ),
        LanguageServerPackage(
            language = "Go",
            server = "gopls",
            grammars = listOf("go"),
            // Debian stable/main, 2:0.16.1+ds-1 — **and `golang-go`**, which
            // gopls does not depend on and cannot work without: it loads the
            // workspace by running `go list`, so without the toolchain it
            // starts, initializes, and marks every file "No active builds
            // contain <file>: consider opening a new workspace folder".
            // Proved on the device. The cargo and pyflakes lesson, a third
            // time — a server that runs and says nothing useful is the
            // failure mode this table exists to prevent.
            packages = listOf("gopls", "golang-go"),
            note = "gopls loads the workspace with the go tool, so both are installed.",
        ),
    )

    /**
     * Grammars the engine will ask a server for and Debian stable does not
     * package, with what to say instead of offering an install that must fail.
     *
     * `typescript-language-server` is in npm and nowhere in the Debian archive
     * — `packages.debian.org/stable/node-typescript-language-server` is a 404,
     * and `sources.debian.org`'s search for it comes back empty. The engine
     * still tries to spawn it (lsp.rs:145-146) and reports `unavailable` with
     * "command not found", which is honest; what would not be honest is a
     * button that runs `apt-get install` on a package that does not exist.
     */
    val UNPACKAGED: Map<String, String> = mapOf(
        "typescript" to "TypeScript",
        "tsx" to "TypeScript",
    )

    /** The recipe for [grammar], or null when there is none to install. */
    fun forGrammar(grammar: String?): LanguageServerPackage? {
        if (grammar == null) return null
        return ALL.firstOrNull { grammar in it.grammars }
    }

    /**
     * The recipe for a server by name — the status bar's route in, because
     * `lspServers` reports the binary ("clangd"), not the grammar.
     */
    fun forServer(server: String?): LanguageServerPackage? {
        if (server == null) return null
        return ALL.firstOrNull { it.server == server }
    }

    /**
     * What to say about a grammar we cannot install a server for, or null when
     * there is nothing to say — the ordinary case, a language with no server.
     */
    fun unpackagedMessage(grammar: String?): String? {
        val language = UNPACKAGED[grammar ?: return null] ?: return null
        return "Debian stable has no $language language server to install. " +
            "The editor will keep highlighting and folding $language; only " +
            "diagnostics, completions and go-to-definition need a server."
    }

    // --- apt, as this table's callers still spell it -------------------------
    //
    // Thin delegates to [Apt], kept so the P5-2 tests and callers say
    // "LanguageServers.parsePlan" about a language server. The behaviour is
    // Apt's, in one place, shared with the agent runtime.

    fun estimateArgv(target: LanguageServerPackage): List<String> =
        Apt.estimateArgv(target.packages)

    fun installArgv(target: LanguageServerPackage): List<String> =
        Apt.installArgv(target.packages)

    val ENVIRONMENT: List<String> get() = Apt.ENVIRONMENT

    fun parsePlan(output: String): AptPlan = Apt.parsePlan(output)

    fun formatBytes(bytes: Long?): String? = Apt.formatBytes(bytes)

    fun question(target: LanguageServerPackage, plan: AptPlan?): String = target.question(plan)

    fun detail(target: LanguageServerPackage, plan: AptPlan?, userland: String): String =
        target.detail(plan, userland)

    fun explainInstall(output: String, target: LanguageServerPackage): String =
        Apt.explain(output, target.packageList)
}

/**
 * Installing a language server from apt, asked first and cancellable
 * throughout. See [AptInstaller] for how it behaves; this is the instance the
 * language-server prompt drives.
 */
val LanguageServerInstaller = AptInstaller("seeker-lsp-install")
