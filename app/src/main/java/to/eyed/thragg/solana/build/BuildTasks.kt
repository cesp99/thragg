package to.eyed.thragg.solana.build

import android.content.Context
import to.eyed.thragg.terminal.GuestProcess
import to.eyed.thragg.terminal.Userland
import java.io.File

/**
 * What kind of Solana project this is, what it is called, what it builds to,
 * and the exact command line each of the three buttons runs.
 *
 * This is the framework → command table from docs/SOLANA.md ("What runs when
 * you press Build") made into data. Everything above the [probe] and [detect]
 * pair is a pure function over strings, because the interesting failures here
 * are not IO failures: they are a workspace whose `[lib] name` disagrees with
 * its package name (so the artifact is `my_program.so`, not `my-program.so`),
 * an Anchor project that is really a Seahorse one, and a `cargo-build-sbf`
 * that is not installed. All three are decided here and tested on the host.
 *
 * Nothing in this file runs anything. [BuildRunner] does the running.
 */

/** The three buttons at the bottom of the Build destination, left to right. */
enum class BuildAction(val label: String, val progressLabel: String) {
    /** `anchor build` / `cargo build-sbf`. */
    Build("Build", "Building"),

    /** `anchor test --skip-local-validator` / `cargo test`. */
    Test("Test", "Testing"),

    /** `solana program deploy target/deploy/<name>.so` — through P6's [Deployer]. */
    Deploy("Deploy", "Deploying"),
}

/**
 * The three ways to write a Solana program, as *detected on disk* rather than
 * as chosen in the new-project dialog.
 *
 * Deliberately not [to.eyed.thragg.solana.templates.SolanaFramework]: that
 * enum answers "what shall we scaffold", this one answers "what did the user
 * clone", and a cloned project has no memory of which dialog it never came
 * from. The two agree on their three names because they describe the same
 * three worlds.
 */
enum class ProjectFramework {
    /** `Anchor.toml` at the root, programs under `programs/`. */
    Anchor,

    /** A `Cargo.toml` with a `solana-program` dependency and no Anchor.toml. */
    Native,

    /** Anchor, plus `programs_py/` — the Python that compiles down to Anchor. */
    Seahorse,

    /** Nothing here builds a Solana program. The three buttons say so. */
    Unknown,
}

/**
 * One program in the project, in the three spellings that matter.
 *
 * The distinction is not pedantry. cargo's package name may be `my-program`
 * while the `[lib] name` — and therefore the shared object, and therefore
 * `target/deploy/<name>.so`, and therefore what `solana program deploy` is
 * handed — is `my_program`. Picking the wrong one is a deploy that reports
 * "No such file or directory" after a successful 71-second build.
 */
data class ProgramTarget(
    /** cargo's `[package] name`: `my-program`. */
    val crateName: String,
    /** The `[lib] name`, else the crate name with `-` → `_`: `my_program`. */
    val moduleName: String,
    /** Project-relative path of the artifact a build is expected to produce. */
    val artifactPath: String,
)

/** Where the SBF artifact lands under the canonical `cargo-build-sbf` layout. */
private const val DEPLOY_DIR = "target/deploy"

/**
 * The project as the build layer sees it: one framework, zero or more
 * programs, and the directory the build command runs in.
 */
data class ProjectLayout(
    /** Absolute host path of the project root. */
    val root: String,
    val framework: ProjectFramework,
    /** Sorted by crate name, so the header's "which program" chip is stable. */
    val programs: List<ProgramTarget>,
) {
    /** True when there is something here the three buttons can act on. */
    val isBuildable: Boolean get() = framework != ProjectFramework.Unknown

    /** What the header prints next to the project name. */
    val label: String
        get() = when (framework) {
            ProjectFramework.Anchor -> "Anchor"
            ProjectFramework.Native -> "Native"
            ProjectFramework.Seahorse -> "Seahorse"
            ProjectFramework.Unknown -> "No Solana program"
        }

    /** The program a Deploy acts on: the only one, or the first by name. */
    val primary: ProgramTarget? get() = programs.firstOrNull()

    companion object {
        fun unknown(root: String) = ProjectLayout(root, ProjectFramework.Unknown, emptyList())
    }
}

/**
 * Which of the four programs a build needs are actually present in the guest.
 *
 * This is read rather than assumed for one measured reason: `cargo-build-sbf`
 * and `anchor` have no arm64 binary anywhere upstream and are *built on the
 * phone* from crates.io during setup (docs/SOLANA.md), so "the toolchain is
 * installed" and "cargo-build-sbf exists" are genuinely different facts —
 * setup runs that compile last, in the background, and a user can press Build
 * in between. The fallback in [buildCommand] is what that gap is for.
 */
data class GuestTools(
    val cargoBuildSbf: Boolean = false,
    val anchor: Boolean = false,
    /** platform-tools' own host cargo, which is what builds everything else. */
    val platformCargo: Boolean = false,
    /** The Agave CLI — `solana program deploy`, `solana balance`. */
    val solanaCli: Boolean = false,
) {
    /** Whether anything at all can compile SBF here. */
    val canCompile: Boolean get() = cargoBuildSbf || platformCargo

    companion object {
        /** What we know before the probe has answered, or without a guest. */
        val NONE = GuestTools()
    }
}

/**
 * One command line, with the two things the log and the parser need to know
 * about it: what to print above the output, and whether cargo was asked for
 * JSON diagnostics.
 */
data class BuildCommand(
    /** The `sh -c` line, run in the project root inside the guest. */
    val line: String,
    /** What the log's first row shows — the command, as a person would type it. */
    val display: String,
    /**
     * True when `--message-format=json…` is on the line, so the parser knows
     * a JSON object is expected and a bare `error:` line is a *cargo* error
     * rather than a rustc one it should have seen as JSON.
     */
    val jsonDiagnostics: Boolean,
    /**
     * A sentence for the log when the command is not the obvious one — the
     * platform-tools fallback below. Null when there is nothing to explain.
     */
    val note: String? = null,
)

object BuildTasks {

    // --- guest paths ---------------------------------------------------------
    //
    // Every one of these is where docs/SOLANA.md's installer puts the thing,
    // and they are guest paths: inside the proot, not on Android's filesystem.

    /** Everything the toolchain setup installs lives under here. */
    const val SOLANA_ROOT = "/opt/solana"

    /** anza-xyz/platform-tools: its own cargo, rustc, clang and lld. */
    const val PLATFORM_TOOLS = "$SOLANA_ROOT/platform-tools"

    /** The Agave CLI's bin, when a build of it exists for this ABI. */
    const val CLI_BIN = "$SOLANA_ROOT/cli/bin"

    /** platform-tools' LLVM — `llvm-readelf`, `llvm-objdump`, `lld`. */
    const val LLVM_BIN = "$PLATFORM_TOOLS/llvm/bin"

    /** The host cargo that built `cargo-build-sbf` on the device. */
    const val PLATFORM_CARGO = "$PLATFORM_TOOLS/rust/bin/cargo"

    const val CARGO_HOME = "/root/.cargo"
    const val RUSTUP_HOME = "/root/.rustup"

    /** Where `cargo install` puts what it built: `cargo-build-sbf`, `anchor`. */
    const val CARGO_BIN = "$CARGO_HOME/bin"

    /**
     * The SBF target triple platform-tools reports. Only the fallback names
     * it explicitly; `cargo-build-sbf` picks it itself.
     */
    const val SBF_TARGET = "sbpf-solana-solana"

    /**
     * Where `cargo-build-sbf` keeps — and, when it thinks it is missing,
     * *downloads* — a platform-tools per version: `<root>/<version>/platform-tools`.
     * The installer and [toolchainGuard] seed symlinks under it pointing at
     * [PLATFORM_TOOLS], which turns that downloader into a no-op.
     */
    const val TOOLS_CACHE = "/root/.cache/solana"

    /**
     * The environment a build runs with, appended to the userland's own so
     * these win (see `UserlandBackend.execCommand`).
     *
     * The order of `PATH` is the whole point and it is the order docs/SOLANA.md
     * records as working:
     *
     *  1. `$CARGO_HOME/bin` — `cargo-build-sbf` and `anchor`, the two binaries
     *     the phone compiled for itself.
     *  2. the Agave CLI, for `solana`.
     *  3. platform-tools' LLVM, so `cargo-build-sbf`'s linker invocation finds
     *     the `lld` with the SBF backend rather than Debian's.
     *  4. the guest's own, last.
     *
     * platform-tools' *rust* bin is deliberately **not** on this path.
     * `cargo-build-sbf` reaches that toolchain through rustup — it is linked
     * as the `seeker` toolchain (`rustup toolchain link seeker …`) — and a
     * second `cargo` earlier on the path than `$CARGO_HOME/bin` would shadow
     * the `cargo-build-sbf` shim that the `cargo build-sbf` subcommand form
     * depends on. The fallback in [buildCommand] names that cargo by its
     * absolute path instead, which is the only place it belongs.
     *
     * `RUSTUP_HOME` is here even though nothing we run is a rustup toolchain:
     * `cargo-build-sbf` shells out to `rustup` and dies with "Failed to
     * execute rustup" if it cannot (docs/SOLANA.md). It must be present and it
     * must never own a compiler.
     */
    fun guestEnvironment(): List<String> = listOf(
        "PATH=$CARGO_BIN:$CLI_BIN:$LLVM_BIN:" +
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "CARGO_HOME=$CARGO_HOME",
        "RUSTUP_HOME=$RUSTUP_HOME",
        // A build driven from a pipe is not a terminal, and cargo's progress
        // bar redrawn with escape codes into a log view is noise. The
        // diagnostics keep their own colour where they are asked for it.
        "TERM=dumb",
        "CARGO_TERM_COLOR=never",
        // rustc's own backtrace on an ICE or a proc-macro panic is the only
        // thing that makes those two reports actionable, and it costs nothing
        // when nothing panics.
        "RUST_BACKTRACE=1",
    )

    // --- detection -----------------------------------------------------------

    /**
     * What kind of project sits at [root]. Blocking IO: call it off the main
     * thread.
     *
     * Order matters. Seahorse is checked before Anchor because a Seahorse
     * project *is* an Anchor project with `programs_py/` beside it — it
     * generates `programs/<name>` on the way to `anchor build`, so testing for
     * Anchor.toml first would classify every Seahorse project as Anchor and
     * hide the one command (`seahorse build`) that regenerates the Rust.
     */
    fun detect(root: File): ProjectLayout {
        if (!root.isDirectory) return ProjectLayout.unknown(root.absolutePath)
        val anchorToml = File(root, "Anchor.toml")
        val cargoToml = File(root, "Cargo.toml")

        if (anchorToml.isFile) {
            val programs = anchorPrograms(root, anchorToml)
            val framework = if (File(root, "programs_py").isDirectory) {
                ProjectFramework.Seahorse
            } else {
                ProjectFramework.Anchor
            }
            return ProjectLayout(root.absolutePath, framework, programs)
        }

        if (cargoToml.isFile) {
            val manifest = readManifest(cargoToml) ?: return ProjectLayout.unknown(root.absolutePath)
            if (!manifest.dependsOnSolana) return ProjectLayout.unknown(root.absolutePath)
            return ProjectLayout(
                root.absolutePath,
                ProjectFramework.Native,
                listOfNotNull(manifest.toTarget()),
            )
        }

        return ProjectLayout.unknown(root.absolutePath)
    }

    /**
     * The programs of an Anchor workspace.
     *
     * Each `programs/<name>/Cargo.toml` is the source of truth, because the artifact is
     * named after the crate's `[lib]`, not after anything in Anchor.toml. The
     * `[programs.<cluster>]` table is the fallback for a workspace whose
     * members live somewhere else — its keys are already module-spelled, which
     * is exactly what the `.so` is called.
     */
    private fun anchorPrograms(root: File, anchorToml: File): List<ProgramTarget> {
        val fromDisk = File(root, "programs").listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> readManifest(File(dir, "Cargo.toml"))?.toTarget() }
        if (fromDisk.isNotEmpty()) return fromDisk.sortedBy { it.crateName }
        return anchorTomlPrograms(runCatching { anchorToml.readText() }.getOrDefault(""))
    }

    /**
     * The `[programs.<cluster>]` keys of an Anchor.toml, as targets.
     *
     * Every cluster table lists the same programs, so the first one that has
     * any is enough and duplicates are dropped by name.
     */
    fun anchorTomlPrograms(text: String): List<ProgramTarget> {
        val names = LinkedHashSet<String>()
        var inPrograms = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inPrograms = line.removePrefix("[").removeSuffix("]").trim()
                    .startsWith("programs.")
                continue
            }
            if (!inPrograms) continue
            val key = line.substringBefore('=', "").trim().trim('"')
            if (key.isNotEmpty() && '=' in line) names.add(key)
        }
        return names.sorted().map { name ->
            ProgramTarget(name, name, "$DEPLOY_DIR/$name.so")
        }
    }

    /** A crate manifest, reduced to the three facts a build cares about. */
    data class CargoManifest(
        val packageName: String?,
        val libName: String?,
        /** Whether `solana-program` or `anchor-lang` is a dependency. */
        val dependsOnSolana: Boolean,
    ) {
        fun toTarget(): ProgramTarget? {
            val crate = packageName ?: return null
            val module = libName ?: crate.replace('-', '_')
            return ProgramTarget(crate, module, "$DEPLOY_DIR/$module.so")
        }
    }

    private fun readManifest(file: File): CargoManifest? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return parseManifest(text)
    }

    /**
     * Enough TOML to answer three questions, and no more.
     *
     * A real TOML parser is a dependency this file does not need: `name` is
     * only meaningful under `[package]` and `[lib]`, and the dependency check
     * is a substring test against the `[dependencies]` section that survives
     * both `solana-program = "2.2"` and the table form
     * `solana-program = { version = "2.2" }`. Anything more clever would be a
     * second implementation of cargo's own manifest reading, which is the
     * thing that decides for real when the build runs.
     */
    fun parseManifest(text: String): CargoManifest {
        var section = ""
        var packageName: String? = null
        var libName: String? = null
        var dependsOnSolana = false
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) {
                section = line.removePrefix("[").removeSuffix("]").trim()
                continue
            }
            if (line.isEmpty()) continue
            val key = line.substringBefore('=', "").trim()
            if (key.isNotEmpty() && '=' in line) {
                val value = line.substringAfter('=').trim().trim('"', '\'')
                when {
                    section == "package" && key == "name" -> packageName = value
                    section == "lib" && key == "name" -> libName = value
                }
            }
            // `[dependencies]`, `[dev-dependencies]`, `[target.…dependencies]`
            // and `[workspace.dependencies]` all count: any of them naming the
            // SDK means this is a Solana crate.
            if (section.endsWith("dependencies") && (
                    key == "solana-program" || key == "anchor-lang" || key == "pinocchio"
                    )
            ) {
                dependsOnSolana = true
            }
        }
        return CargoManifest(
            packageName = packageName?.takeIf { it.isNotEmpty() },
            libName = libName?.takeIf { it.isNotEmpty() },
            dependsOnSolana = dependsOnSolana,
        )
    }

    // --- probing the guest ----------------------------------------------------

    /**
     * Which build programs the guest actually has. Blocking; call it off the
     * main thread. [GuestTools.NONE] when there is no userland at all, which
     * is the answer before Debian is installed.
     *
     * One `sh -c` rather than four `command -v` round trips: each one is a
     * whole proot start-up, and this runs every time the Build destination is
     * entered.
     */
    fun probe(context: Context): GuestTools {
        val script = buildString {
            append("for t in cargo-build-sbf anchor solana; do ")
            append("command -v \$t >/dev/null 2>&1 && echo have:\$t; ")
            append("done; ")
            append("[ -x $PLATFORM_CARGO ] && echo have:platform-cargo")
        }
        val command = Userland.backend.execCommand(
            context,
            null,
            listOf("/bin/sh", "-c", script),
            guestEnvironment(),
        ) ?: return GuestTools.NONE

        val found = HashSet<String>()
        val exit = runCatching {
            GuestProcess.run(command) { record ->
                if (record.startsWith("have:")) found.add(record.removePrefix("have:").trim())
            }
        }.getOrElse { return GuestTools.NONE }
        // A non-zero exit is expected: the `[ -x … ]` test is the last command
        // and it fails when platform-tools is not there. What was printed
        // before it is still the answer.
        if (exit != 0 && found.isEmpty()) return GuestTools.NONE
        return GuestTools(
            cargoBuildSbf = "cargo-build-sbf" in found,
            anchor = "anchor" in found,
            platformCargo = "platform-cargo" in found,
            solanaCli = "solana" in found,
        )
    }

    // --- the command table ----------------------------------------------------

    /**
     * The `sh` prefix that makes a `cargo-build-sbf` build survive its own
     * driver, prepended to every command line that reaches `cargo-build-sbf`
     * (directly, or through `anchor`). Two repairs, both learned in the same
     * device rehearsal (2026-08) and both idempotent:
     *
     *  1. **Relink rustup's default toolchain, named `seeker`.**
     *     `cargo-build-sbf` (4.2.0, src/toolchain.rs) takes the first rustup
     *     toolchain whose *name contains "solana"* and, unless it is the
     *     `<rustc>-sbpf-solana-<tag>` entry it wants, *uninstalls* it before
     *     linking its own. Linked as `solana` — as it was until 2026-09-02 —
     *     the default was deleted by every build, and the IDL step of the
     *     same `anchor build` (which runs on the default toolchain) died
     *     with "override toolchain 'solana' is not installed". Named
     *     `seeker`, the driver never looks at it. The link+default pair is
     *     re-run unconditionally — both are idempotent, ~100 ms under proot
     *     — so a phone set up while the name was `solana`, or one whose
     *     default was left dangling, is repaired by its next build.
     *  2. **Seed the tools cache.** Without a populated
     *     `$TOOLS_CACHE/<v>/platform-tools`, `cargo-build-sbf` downloads its
     *     own platform-tools (~450 MB) for `<v>` — on the rehearsal device
     *     that download ran 27 minutes and died, with 1.4 GB of the identical
     *     toolchain already at [PLATFORM_TOOLS]. A symlink into place makes
     *     the downloader a no-op. Seeded for the manifest's version *and* for
     *     whatever version the installed driver itself pins (read from
     *     `cargo-build-sbf --version`; 4.2.0 pins v1.56), because the pinned
     *     default is what an `anchor build` with no `--tools-version` asks for.
     *
     * The whole group is wrapped in one redirect to /dev/null: the build's
     * stdout is a JSON diagnostics pipe ([BuildCommand.jsonDiagnostics]) and
     * nothing the guard prints may leak into it. `ln -sfn` and `mkdir -p`
     * make re-running it — and running it over the hand-made symlinks that
     * exist on devices set up before this guard did — a no-op.
     */
    fun toolchainGuard(platformToolsVersion: String?, seeds: List<String> = emptyList()): String {
        val versions = buildString {
            platformToolsVersion?.let { append(it).append(' ') }
            // The manifest's toolsCacheSeeds: the tags the installed drivers
            // hard-code (anchor-cli 1.1.2 asks for v1.52 on every build and
            // prints it nowhere, so it cannot be discovered like the next one).
            for (seed in seeds) append(seed).append(' ')
            append("\$(cargo-build-sbf --version 2>/dev/null | grep -oE 'v[0-9]+\\.[0-9]+' | head -n1)")
        }
        return "{ [ -d $PLATFORM_TOOLS/rust ] && { " +
            "rustup toolchain link seeker $PLATFORM_TOOLS/rust; rustup default seeker; " +
            "for v in $versions; do " +
            "mkdir -p $TOOLS_CACHE/\$v && ln -sfn $PLATFORM_TOOLS $TOOLS_CACHE/\$v/platform-tools; " +
            "done; }; } >/dev/null 2>&1; "
    }

    /**
     * What Build runs, or null when nothing here can be built.
     *
     * The table is docs/SOLANA.md's, with one documented fallback. Anchor and
     * Seahorse go through `anchor build`, which itself calls `cargo-build-sbf`;
     * Native calls it directly. When `cargo-build-sbf` is absent — the window
     * during setup while the phone is still compiling it from crates.io, which
     * takes 3 min 45 s — a Native build falls back to platform-tools' own
     * cargo at the SBF target. That fallback is honest about its one
     * difference and the log says so: cargo writes to
     * `target/sbpf-solana-solana/release/`, not to the `target/deploy/` layout
     * that `cargo-build-sbf` produces, so the artifact is in the wrong place
     * for a deploy and there is no program keypair beside it.
     *
     * `--message-format` is asked for wherever cargo is the process being
     * driven. `anchor build` swallows its child's stdout formatting, so
     * Anchor's diagnostics arrive as rendered text and are read by
     * [CargoDiagnostics]'s line parser instead — which is why that parser is
     * not a fallback in the apologetic sense but the main path for two of the
     * three frameworks.
     *
     * [platformToolsVersion] is the manifest's platform-tools release tag
     * (`ToolchainManifest.platformToolsVersion`), and passing it as
     * `--tools-version` is load-bearing, not decorative: without it,
     * `cargo-build-sbf` 4.2.0 ignores the installed v1.57 entirely and
     * downloads its own pinned v1.56 (~450 MB) on the first build — on the
     * device rehearsal that download ran 27 min 39 s and then died. The flag
     * plus [toolchainGuard]'s seeded cache is belt and braces: either alone
     * keeps the demo build offline. Null (a manifest that failed to load)
     * degrades to the guard alone.
     */
    fun buildCommand(
        layout: ProjectLayout,
        tools: GuestTools,
        platformToolsVersion: String? = null,
        seeds: List<String> = emptyList(),
    ): BuildCommand? = when {
        layout.framework == ProjectFramework.Unknown -> null

        // Seahorse and Anchor both end in `anchor build`, which drives
        // cargo-build-sbf itself — with no --tools-version, so the guard's
        // seeded cache is what keeps their tools download from happening.
        layout.framework == ProjectFramework.Seahorse ->
            BuildCommand(
                line = toolchainGuard(platformToolsVersion, seeds) + "seahorse build",
                display = "seahorse build",
                jsonDiagnostics = false,
            )

        layout.framework == ProjectFramework.Anchor ->
            BuildCommand(
                line = toolchainGuard(platformToolsVersion, seeds) + "anchor build",
                display = "anchor build",
                jsonDiagnostics = false,
            )

        // Native, with cargo-build-sbf present: the canonical path, and the
        // one that produces target/deploy/.
        tools.cargoBuildSbf -> {
            val versionFlag = platformToolsVersion?.let { " --tools-version $it" }.orEmpty()
            BuildCommand(
                line = toolchainGuard(platformToolsVersion, seeds) +
                    "cargo build-sbf$versionFlag -- --message-format=json-diagnostic-rendered-ansi",
                display = "cargo build-sbf$versionFlag",
                jsonDiagnostics = true,
            )
        }

        // Native, without it: platform-tools' cargo, by absolute path.
        tools.platformCargo ->
            BuildCommand(
                line = "$PLATFORM_CARGO build --release --target $SBF_TARGET " +
                    "--message-format=json-diagnostic-rendered-ansi",
                display = "cargo build --release --target $SBF_TARGET",
                jsonDiagnostics = true,
                note = "cargo-build-sbf is not installed yet, so this is " +
                    "platform-tools' own cargo. It compiles, but it writes to " +
                    "target/$SBF_TARGET/release/ and produces no program keypair, " +
                    "so the result cannot be deployed until setup finishes.",
            )

        else -> null
    }

    /**
     * What Test runs — and this is where the honesty in docs/UI.md lives.
     *
     * Native's `cargo test` works: it is a host build of the program's own
     * Rust tests and needs nothing but the toolchain. Anchor's scaffolded
     * `[scripts] test` is `yarn run ts-mocha …` and the manifest ships no
     * Node, so [anchorTestNeedsNode] is what the screen asks first, and
     * [cargoTestCommand] is the alternative it offers. `--skip-local-validator`
     * is unconditional: Agave has no arm64 build, so there is no local
     * validator on this phone to start.
     */
    fun testCommand(
        layout: ProjectLayout,
        platformToolsVersion: String? = null,
        seeds: List<String> = emptyList(),
    ): BuildCommand? = when (layout.framework) {
        ProjectFramework.Unknown -> null
        ProjectFramework.Native -> cargoTestCommand(platformToolsVersion, seeds)
        // `anchor test` builds first, so it reaches cargo-build-sbf and needs
        // the same guard a Build does. Native's `cargo test` is a host build
        // through platform-tools' own cargo and touches neither rustup's
        // `solana` link nor the tools cache.
        ProjectFramework.Anchor, ProjectFramework.Seahorse ->
            BuildCommand(
                line = toolchainGuard(platformToolsVersion, seeds) + "anchor test --skip-local-validator",
                display = "anchor test --skip-local-validator",
                jsonDiagnostics = false,
            )
    }

    /**
     * The Rust half of an Anchor project's tests, which needs no Node.
     * Guarded too: a bare `cargo` here is rustup's shim resolving the
     * default toolchain, which is exactly what `cargo-build-sbf`'s tools
     * step un-links (see [toolchainGuard]) — so a `cargo test` pressed right
     * after a Build is the second victim of that vandalism.
     */
    fun cargoTestCommand(platformToolsVersion: String? = null, seeds: List<String> = emptyList()): BuildCommand = BuildCommand(
        line = toolchainGuard(platformToolsVersion, seeds) +
            "cargo test --message-format=json-diagnostic-rendered-ansi",
        display = "cargo test",
        jsonDiagnostics = true,
    )

    /**
     * Whether pressing Test on this project is about to need Node and yarn.
     * The screen turns this into a question rather than a failed run.
     */
    fun anchorTestNeedsNode(layout: ProjectLayout): Boolean =
        layout.framework == ProjectFramework.Anchor ||
            layout.framework == ProjectFramework.Seahorse

    /**
     * Whether the artifact [program] should produce is newer than every source
     * file under [root]. Blocking IO.
     *
     * "Newer than the sources" rather than "exists" because deploying a `.so`
     * from before the edit you are trying to test is the failure this row is
     * on the screen to prevent (docs/UI.md, "Build — the payoff loop":
     * `stale — edited since the last build`). The walk skips `target/`,
     * `node_modules/` and dot-directories, which is the difference between a
     * few hundred stats and a few hundred thousand.
     */
    fun freshness(root: File, program: ProgramTarget?): ArtifactFreshness {
        program ?: return ArtifactFreshness.Missing
        val artifact = File(root, program.artifactPath)
        if (!artifact.isFile) return ArtifactFreshness.Missing
        val built = artifact.lastModified()
        return if (newestSource(root, built)) {
            ArtifactFreshness.Stale(built)
        } else {
            ArtifactFreshness.Fresh(built)
        }
    }

    /** True as soon as one source file is newer than [than] — a short walk. */
    private fun newestSource(root: File, than: Long): Boolean {
        val skip = setOf("target", "node_modules", "test-ledger")
        val stack = ArrayDeque(listOf(root))
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_FRESHNESS_ENTRIES) {
            val dir = stack.removeLast()
            for (child in dir.listFiles().orEmpty()) {
                visited++
                val name = child.name
                if (name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (name !in skip) stack.addLast(child)
                    continue
                }
                val relevant = name.endsWith(".rs") || name.endsWith(".py") ||
                    name.endsWith(".toml")
                if (relevant && child.lastModified() > than) return true
            }
        }
        return false
    }

    /** A ceiling on the freshness walk; a project bigger than this reads fresh. */
    private const val MAX_FRESHNESS_ENTRIES = 4_000
}

/** What the program row says about `target/deploy/<name>.so`. */
sealed interface ArtifactFreshness {
    /** No build has produced it, or it was cleaned away. */
    data object Missing : ArtifactFreshness

    /** Built at [at], and nothing has been edited since. */
    data class Fresh(val at: Long) : ArtifactFreshness

    /** Built at [at], but a source file is newer — deploying it ships old code. */
    data class Stale(val at: Long) : ArtifactFreshness
}
