package to.eyed.seeker.code.solana.toolchain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Solana toolchain, as data.
 *
 * "Components are data, not code … so a toolchain bump is a manifest edit
 * rather than a release" (docs/SOLANA.md, "The manifest"). Everything that
 * varies between two versions of the toolchain — the URL, the hash, the sizes,
 * where it lands in the guest, and the commands that register it — lives in
 * `assets/solana/toolchain/manifest.json`. This file is the parser and
 * nothing else: it knows the *shape* of a component, never which components
 * there are.
 *
 * ON THE FILE'S LOCATION. docs/UI.md names the manifest
 * `solana/toolchain/manifest.json`, beside this file. It is shipped from
 * `app/src/main/assets/solana/toolchain/manifest.json` instead, for one
 * mechanical reason: the Android Gradle plugin packages `src/main/java` as
 * *source*, and a non-source file there reaches neither the APK nor the unit
 * test classpath — verified against `:app:processFullDebugJavaRes`, whose
 * output contains only `META-INF/app.kotlin_module`. Moving it back would take
 * a `resources.srcDir("src/main/java")` line in app/build.gradle.kts, which is
 * another chunk's file. Assets is also where this app already keeps its data
 * (`assets/themes`), so the package path is the only thing given up.
 *
 * Parsing is deliberately forgiving about *unknown* fields and unforgiving
 * about missing ones: a newer manifest carrying a field this build does not
 * understand still installs, but a component with no id, no method or no
 * marker is a manifest bug that must fail loudly at parse time rather than
 * halfway through a 505 MB download.
 */
data class ToolchainManifest(
    /** Bumped only when the *shape* changes; a component edit is not a bump. */
    val schema: Int,
    /**
     * What `cargo-build-sbf --tools-version` is told. It is the platform-tools
     * release tag and it has to agree with that component's URL, which
     * [ToolchainManifestTest] asserts rather than trusts.
     */
    val platformToolsVersion: String,
    /** Where the toolchain lives inside the guest — `/opt/solana`. */
    val guestRoot: String,
    /**
     * cargo's shared scratch for the on-device builds. Several GB while they
     * run and worth nothing afterwards, so the installer deletes it once the
     * last compile lands.
     */
    val cargoScratch: String,
    /** In install order, which is also the order Setup lists them. */
    val components: List<ToolchainComponent>,
) {

    /** Bytes over the network for a complete, cold install. */
    val totalDownloadBytes: Long get() = components.sumOf { it.downloadBytes }

    /** Bytes on disk once everything is unpacked and built. */
    val totalInstallBytes: Long get() = components.sumOf { it.installBytes }

    fun component(id: String): ToolchainComponent? = components.firstOrNull { it.id == id }

    /** The ids that must be present for Build to work at all. */
    val requiredIds: List<String> get() = components.filter { it.required }.map { it.id }

    companion object {
        /** Where the file is read from. See the note on the class. */
        const val ASSET_PATH = "solana/toolchain/manifest.json"

        /** The shape this build understands. */
        const val SCHEMA = 1

        private var cached: ToolchainManifest? = null

        /**
         * The manifest, parsed once per process.
         *
         * Cached because every Build press and every Setup recomposition asks
         * for it, and because a manifest that changed under a running install
         * would be a component list disagreeing with the rows on screen. Reads
         * a ~6 KB asset on the first call — cheap enough for the main thread,
         * and it is the only file read on the path that decides whether the
         * Build buttons are live.
         */
        fun load(context: Context): ToolchainManifest =
            cached ?: parse(
                context.applicationContext.assets.open(ASSET_PATH)
                    .bufferedReader().use { it.readText() }
            ).also { cached = it }

        fun parse(text: String): ToolchainManifest {
            val root = JSONObject(text)
            val schema = root.optInt("schema", 0)
            require(schema == SCHEMA) {
                "toolchain manifest schema $schema, this build understands $SCHEMA"
            }
            val array = root.getJSONArray("components")
            val components = List(array.length()) { index ->
                component(array.getJSONObject(index))
            }
            require(components.isNotEmpty()) { "the toolchain manifest lists no components" }
            require(components.map { it.id }.toSet().size == components.size) {
                "the toolchain manifest has two components with the same id"
            }
            return ToolchainManifest(
                schema = schema,
                platformToolsVersion = root.getString("platformToolsVersion"),
                guestRoot = root.optString("guestRoot", "/opt/solana"),
                cargoScratch = root.optString("cargoScratch", "/opt/solana/build"),
                components = components,
            )
        }

        private fun component(json: JSONObject): ToolchainComponent {
            val id = json.getString("id")
            val method = InstallMethod.of(json.getString("method"))
                ?: error("component $id has an install method this build does not know")
            val component = ToolchainComponent(
                id = id,
                name = json.getString("name"),
                summary = json.optString("summary"),
                method = method,
                url = json.optStringOrNull("url"),
                sha256 = json.optStringOrNull("sha256"),
                version = json.optStringOrNull("version"),
                downloadBytes = json.optLong("downloadBytes", 0L),
                installBytes = json.optLong("installBytes", 0L),
                approximate = json.optBoolean("approximate", false),
                installPath = json.getString("installPath"),
                marker = json.getString("marker"),
                packages = json.optStringList("packages"),
                crate = json.optStringOrNull("crate"),
                cargoRoot = json.optStringOrNull("root"),
                targetDir = json.optStringOrNull("targetDir"),
                locked = json.optBoolean("locked", false),
                postInstall = json.optArgvList("postInstall"),
                verify = json.optJSONArray("verify")?.let { it.toStringList() },
                required = json.optBoolean("required", false),
            )
            // The checks that would otherwise surface as a confusing failure
            // ten minutes into an install.
            when (method) {
                InstallMethod.Binary,
                InstallMethod.GzSingleBinary,
                InstallMethod.Tarball,
                -> require(component.url != null && component.sha256 != null) {
                    "component $id downloads a file and must pin a url and a sha256"
                }
                InstallMethod.CargoInstall -> require(component.crate != null) {
                    "component $id is a cargo install and must name a crate"
                }
                InstallMethod.Apt -> require(component.packages.isNotEmpty()) {
                    "component $id is an apt step and must name packages"
                }
                InstallMethod.Userland -> Unit
            }
            require(component.marker.startsWith("/")) {
                "component $id has a marker that is not a guest absolute path"
            }
            return component
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        private fun JSONObject.optStringList(key: String): List<String> =
            optJSONArray(key)?.toStringList().orEmpty()

        private fun JSONObject.optArgvList(key: String): List<List<String>> {
            val outer = optJSONArray(key) ?: return emptyList()
            return List(outer.length()) { outer.getJSONArray(it).toStringList() }
        }

        private fun JSONArray.toStringList(): List<String> =
            List(length()) { getString(it) }
    }
}

/**
 * How a component gets into the guest.
 *
 * Five ways, and the difference between them is not cosmetic — each one is a
 * different answer to "who unpacks this, and on which side of proot".
 */
enum class InstallMethod(val key: String) {

    /**
     * The Debian rootfs, installed by the existing
     * [to.eyed.seeker.code.terminal.UserlandBackend]. The only component
     * unpacked *through* proot, because Debian's own image contains a hard
     * link that a host tar cannot reproduce into app storage.
     */
    Userland("userland"),

    /** `apt-get install`, inside the guest, and the only user of `--link2symlink`. */
    Apt("apt"),

    /** One downloaded file, copied in as-is and chmod 755'd. rustup-init. */
    Binary("binary"),

    /** One gzipped file — rust-analyzer ships its server that way. */
    GzSingleBinary("gz-single-binary"),

    /** A `.tar.gz` or `.tar.bz2`, unpacked with the host's tar beside proot. */
    Tarball("tarball"),

    /**
     * `cargo install`, in the guest, through a proot **without**
     * `--link2symlink`. Shows an elapsed timer, never a MB bar: there is no
     * transfer to measure and pretending otherwise would be a fake progress
     * bar on a four-minute compile.
     */
    CargoInstall("cargo-install");

    companion object {
        fun of(key: String): InstallMethod? = entries.firstOrNull { it.key == key }
    }
}

/** One row on the Setup screen, and one step of the install. */
data class ToolchainComponent(
    /** Stable id: the install record's key and the Retry target. Never shown. */
    val id: String,
    /** The row's title. */
    val name: String,
    /** One line under it: what this is for. */
    val summary: String,
    val method: InstallMethod,
    /** Where it is fetched from. Null for the two that are not fetched. */
    val url: String?,
    /**
     * Pinned over the file exactly as served — and also this component's
     * revision, so bumping a hash re-installs one row and leaves the other
     * seven alone. See [revision].
     */
    val sha256: String?,
    /** Printed in Settings → Toolchain, and the revision when there is no hash. */
    val version: String?,
    /** Bytes over the network. Zero for a component that downloads nothing. */
    val downloadBytes: Long,
    /** Bytes on disk when it is done. */
    val installBytes: Long,
    /** Whether the two sizes are ours to know. False for apt, which owns its own. */
    val approximate: Boolean,
    /** Guest path: a directory for an archive, the file itself for a binary. */
    val installPath: String,
    /**
     * A guest absolute path that exists, as a **real file**, only when this
     * component actually landed.
     *
     * Checked from this side, against the rootfs, after every install. That is
     * not belt and braces: `cargo install` under `--link2symlink` reports
     * success and leaves a symlink to a scratch directory it has already
     * deleted, and `File.exists()` follows symlinks — so a dangling one is
     * exactly what this catches (docs/SOLANA.md, "Living with proot").
     */
    val marker: String,
    /** apt's package list. */
    val packages: List<String>,
    /** The crate a [InstallMethod.CargoInstall] builds. */
    val crate: String?,
    /** `cargo install --root`. */
    val cargoRoot: String?,
    /** `cargo install --target-dir`, shared between the compiles and then deleted. */
    val targetDir: String?,
    /** `cargo install --locked`. */
    val locked: Boolean,
    /**
     * Guest argv lines run after the payload is in place, in order.
     *
     * This is how platform-tools registers itself with rustup — `rustup
     * toolchain link solana …` then `rustup default solana` — rather than that
     * pair being two lines of Kotlin nobody would find when the path changes.
     */
    val postInstall: List<List<String>>,
    /** A guest argv that must exit 0 for the component to count as installed. */
    val verify: List<String>?,
    /** Whether Build needs it. rust-analyzer, Spettro and Anchor do not gate it. */
    val required: Boolean,
) {

    /**
     * What an install record is compared against. A change here re-installs
     * this row and only this row — which is the whole point of recording
     * components individually rather than stamping "the toolchain" as done.
     */
    val revision: String
        get() = sha256 ?: version ?: packages.joinToString(",").ifEmpty { "1" }

    /**
     * Whether this row shows bytes.
     *
     * The progress model has exactly two kinds of row and this is the line
     * between them: a sized download counts bytes and a rate, an on-device
     * compile counts *seconds*. A MB bar on a `cargo install` would be an
     * invention (docs/UI.md, "Setup").
     */
    val isCompiled: Boolean get() = method == InstallMethod.CargoInstall

    /** The marker as a path relative to the rootfs directory, for a host-side stat. */
    val markerInRootfs: String get() = marker.trimStart('/')
}

/**
 * Sizes, said the way a phone user reads them: MB up to a gigabyte, then GB.
 *
 * Decimal, because that is what "600 MB" on a download page means and the
 * Setup screen is quoting a download page. Rounded to something a first-run
 * screen can be honest about — 677 MB is a measurement, "680 MB" is a promise.
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 10_000_000L -> "${(bytes + 500_000L) / 1_000_000L} MB"
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "${bytes / 1_000L} kB"
    else -> "$bytes B"
}
