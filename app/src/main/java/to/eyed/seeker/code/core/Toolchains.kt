package to.eyed.seeker.code.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One interpreter the project can run with — Zed's `language::Toolchain`,
 * flattened to what a picker row and a status-bar label need.
 */
data class Toolchain(
    /** The row's title: "Python (.venv)", "Rust (stable-aarch64-…)". */
    val name: String,
    /** Absolute path to the program itself, inside the userland. */
    val path: String,
    /** The language's display name, as the selector spells it: "Python". */
    val language: String,
    /** Where it was found: ".venv", "poetry", "rustup", "system". */
    val source: String,
)

/**
 * The toolchain store, read through the engine.
 *
 * Nothing is cached: the answer depends on the project's files (a `.venv`
 * appears the moment somebody runs `python -m venv`) and on what is installed
 * in the userland, both of which change while the app is open.
 *
 * [available] is **blocking** — it runs `poetry` and `rustup` inside the
 * guest — so it belongs on a background dispatcher. [active] only reads a
 * small file.
 */
object Toolchains {
    fun available(projectId: Long): List<Toolchain> = parse(CoreBridge.toolchains(projectId))

    fun active(projectId: Long): List<Toolchain> = parse(CoreBridge.activeToolchains(projectId))

    /** Choose [toolchain] for its language, or clear the language's choice. */
    fun select(projectId: Long, language: String, toolchain: Toolchain?): Boolean =
        CoreBridge.setToolchain(projectId, language, toolchain?.let(::encode))

    private fun encode(toolchain: Toolchain): String =
        JSONObject()
            .put("name", toolchain.name)
            .put("path", toolchain.path)
            .put("language", toolchain.language)
            .put("source", toolchain.source)
            .toString()

    private fun parse(json: String): List<Toolchain> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            Toolchain(
                name = entry.optString("name"),
                path = entry.optString("path"),
                language = entry.optString("language"),
                source = entry.optString("source"),
            )
        }
    }
}
