package to.eyed.thragg.core

import android.os.Build
import android.system.Os
import android.system.OsConstants
import to.eyed.thragg.BuildConfig

/**
 * What a bug report needs, gathered in one place — Zed's `system_specs`
 * (crates/system_specs/src/system_specs.rs), which its `zed: copy system
 * specs into clipboard` and its issue template both read.
 *
 * Zed's list is app version, release channel, OS name and version, memory,
 * architecture and GPU. Ours keeps every line that means something on Android
 * and adds the three that only mean something here:
 *
 *  * whether there is a **userland**, because half the reports about git or a
 *    language server are really about that. This used to be the build
 *    *flavour*, back when a Play-compatible edition shipped without one; there
 *    is a single edition now, so the line states the capability instead of
 *    naming an edition that no longer varies;
 *  * the **ABI**, because the engine is a native library and an arm64 bug and
 *    an x86_64 bug are different bugs;
 *  * the **page size**, because Android 15 began shipping devices with 16 KiB
 *    pages, and a native library linked for 4 KiB will not load on one. It is
 *    the first thing to ask when the engine fails to start at all.
 *
 * The Zed commit is the one `core/vendor/VENDOR.md` records, read at build
 * time into `BuildConfig.ZED_COMMIT`, so a report can be traced to the exact
 * upstream the vendored crates came from.
 *
 * Everything here is a plain value, and [lines] and [report] are pure, so the
 * text a user is about to paste into an issue is checkable on the host.
 */
data class SystemSpecs(
    /** `0.0.5`, as the launcher shows it. */
    val appVersion: String,
    val versionCode: Int,
    /** Whether this build can run a Linux userland at all. */
    val hasUserland: Boolean,
    /** The Rust engine's own version string, from `CoreBridge.engineVersion`. */
    val engineVersion: String,
    /** The Zed commit the vendored crates were copied from. */
    val zedCommit: String,
    val deviceModel: String,
    /** `Android 17 (API 37)`. */
    val androidVersion: String,
    /** The ABI this process is actually running, not the ones it supports. */
    val abi: String,
    /** The kernel's page size in bytes — 4096, or 16384 on newer devices. */
    val pageSizeBytes: Long,
) {
    /** The report as label/value pairs, which is what the dialog draws. */
    fun lines(): List<Pair<String, String>> = listOf(
        "App" to "$appVersion ($versionCode)",
        "Userland" to if (hasUserland) "Linux userland" else "no userland",
        "Engine" to engineVersion,
        "Zed" to zedCommit,
        "Device" to deviceModel,
        "Android" to androidVersion,
        "ABI" to abi,
        "Page size" to "${pageSizeBytes / 1024} KiB",
    )

    /**
     * The same, as the block of text the Copy button puts on the clipboard —
     * Zed's `system_specs` `Display`, which is plain `Label: value` lines so
     * it survives being pasted into an issue body unchanged.
     */
    fun report(): String = lines().joinToString("\n") { (label, value) -> "$label: $value" }

    companion object {
        /**
         * Everything this device can be asked about.
         *
         * [engineVersion] is a parameter rather than a call because it is the
         * one JNI hop here, and the caller already knows to make it off the
         * main thread.
         */
        fun of(engineVersion: String): SystemSpecs = SystemSpecs(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            hasUserland = BuildConfig.USERLAND,
            engineVersion = engineVersion,
            zedCommit = BuildConfig.ZED_COMMIT,
            deviceModel = deviceModel(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            // `SUPPORTED_ABIS[0]` is the best ABI the *device* has; what this
            // process loaded is what matters, and on a 64-bit device running
            // a 32-bit split those differ. `Build.SUPPORTED_ABIS` is still the
            // only public answer, so it is the one reported, and the engine's
            // own version string names its target triple beside it.
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifEmpty { "unknown" },
            pageSizeBytes = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(0L),
        )

        /**
         * "Pixel 9 Pro" rather than "Google Pixel 9 Pro" — Android's `MODEL`
         * usually already carries the make, and a report that says "Google
         * Google Pixel" reads as a typo rather than as data.
         */
        internal fun deviceModel(
            manufacturer: String = Build.MANUFACTURER,
            model: String = Build.MODEL,
        ): String {
            val make = manufacturer.trim()
            val name = model.trim()
            if (make.isEmpty()) return name.ifEmpty { "unknown" }
            if (name.isEmpty()) return make
            return if (name.startsWith(make, ignoreCase = true)) name else "$make $name"
        }
    }
}
