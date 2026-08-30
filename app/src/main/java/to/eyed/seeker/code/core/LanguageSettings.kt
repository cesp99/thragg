package to.eyed.seeker.code.core

import org.json.JSONObject
import to.eyed.seeker.code.ui.editor.ShowWhitespaces
import to.eyed.seeker.code.ui.editor.SoftWrapMode

/** Zed's `format_on_save`, as the engine resolves it per buffer. */
enum class FormatOnSave(val key: String) {
    On("on"),
    Off("off"),
    /** On, but only ever through the language server — never an external program. */
    LanguageServer("language_server");

    companion object {
        fun fromKey(key: String?): FormatOnSave = entries.firstOrNull { it.key == key } ?: Off
    }
}

/**
 * Zed's `formatter`, reduced to what the save path can run. The engine
 * normalises the file's many spellings into these shapes before they cross
 * the bridge (`config.rs`, `Formatter`'s serializer), so this parser sees one
 * form per kind.
 */
sealed class FormatterSpec {
    /** The language server, when one is running and offers formatting. */
    data object Auto : FormatterSpec()

    /** Never format, even with `format_on_save` on. */
    data object None : FormatterSpec()

    /** The language server, by name when one was given. */
    data class LanguageServer(val name: String?) : FormatterSpec()

    /** A program in the userland; the buffer goes in on stdin. */
    data class External(val command: String, val arguments: List<String>) : FormatterSpec()

    /** A code-action kind the server runs as the formatter. */
    data class CodeAction(val kind: String) : FormatterSpec()

    /** Whether the save path asks the language server for this formatter. */
    val usesLanguageServer: Boolean
        get() = this is Auto || this is LanguageServer

    companion object {
        fun parse(value: Any?): FormatterSpec = when (value) {
            "none" -> None
            "language_server" -> LanguageServer(null)
            is JSONObject -> when {
                value.has("external") -> {
                    val external = value.optJSONObject("external")
                    val args = external?.optJSONArray("arguments")
                    External(
                        command = external?.optString("command").orEmpty(),
                        arguments = List(args?.length() ?: 0) { args!!.optString(it) },
                    )
                }
                value.has("language_server") ->
                    LanguageServer(
                        value.optJSONObject("language_server")?.optString("name")
                            ?.takeIf { it.isNotEmpty() }
                    )
                value.has("code_action") -> CodeAction(value.optString("code_action"))
                else -> Auto
            }
            else -> Auto
        }
    }
}

/**
 * The settings in force for one buffer, every layer resolved by the engine
 * — the user file, the project's `.zed/settings.json`, and the `languages`
 * entry for the buffer's language in each (`config.rs`,
 * `LanguageSettings::resolve`). The editor reads *these*, never the global
 * [AppSettings] values, for anything a language or a project may override.
 *
 * The defaults here are Zed's (assets/settings/default.json), so a buffer
 * whose settings have not arrived yet behaves as Zed does out of the box.
 */
data class LanguageSettings(
    val tabSize: Int = 4,
    val hardTabs: Boolean = false,
    val softWrap: SoftWrapMode = SoftWrapMode.None,
    /** The column `bounded` wraps at, and the active wrap guide. */
    val preferredLineLength: Int = 80,
    /** Further columns to draw a guide at — Zed's `wrap_guides`. */
    val wrapGuides: List<Int> = emptyList(),
    val formatOnSave: FormatOnSave = FormatOnSave.Off,
    val formatter: FormatterSpec = FormatterSpec.Auto,
    /** Code-action kinds to run before formatting, only the enabled ones. */
    val codeActionsOnFormat: List<String> = emptyList(),
    val enableLanguageServer: Boolean = true,
    /** `git.inline_blame.enabled`, which a project may override. */
    val inlineBlame: Boolean = true,
    /** Which whitespace gets a visible glyph — Zed's `show_whitespaces`. */
    val showWhitespaces: ShowWhitespaces = ShowWhitespaces.Selection,
    /** Whether [wrapGuides] are drawn — Zed's `show_wrap_guides`. */
    val showWrapGuides: Boolean = true,
    /** Zed's `remove_trailing_whitespace_on_save`, applied by the save path. */
    val removeTrailingWhitespaceOnSave: Boolean = true,
    /** Zed's `ensure_final_newline_on_save`, likewise. */
    val ensureFinalNewlineOnSave: Boolean = true,
) {
    /** Whether saving this buffer runs the engine's whitespace rules first. */
    val cleansOnSave: Boolean
        get() = removeTrailingWhitespaceOnSave || ensureFinalNewlineOnSave

    /** Whether saving this buffer formats it first. */
    val formatsOnSave: Boolean
        get() = formatOnSave != FormatOnSave.Off && formatter !is FormatterSpec.None

    /**
     * The formatter a save actually runs: `language_server` as
     * `format_on_save` means the server even when an external program is
     * configured — that is what the value is for.
     */
    val saveFormatter: FormatterSpec
        get() = if (formatOnSave == FormatOnSave.LanguageServer && formatter is FormatterSpec.External) {
            FormatterSpec.LanguageServer(null)
        } else {
            formatter
        }

    companion object {
        fun parse(json: String): LanguageSettings = runCatching {
            val root = JSONObject(json)
            val guides = root.optJSONArray("wrap_guides")
            val actions = root.optJSONObject("code_actions_on_format")
            LanguageSettings(
                tabSize = root.optInt("tab_size", 4).coerceIn(1, 16),
                hardTabs = root.optBoolean("hard_tabs", false),
                softWrap = SoftWrapMode.fromKey(root.optString("soft_wrap", "none")),
                preferredLineLength = root.optInt("preferred_line_length", 80),
                wrapGuides = List(guides?.length() ?: 0) { guides!!.optInt(it) }.filter { it > 0 },
                formatOnSave = FormatOnSave.fromKey(root.optString("format_on_save", "off")),
                formatter = FormatterSpec.parse(root.opt("formatter")),
                codeActionsOnFormat = actions?.keys()?.asSequence()
                    ?.filter { actions.optBoolean(it, false) }
                    ?.sorted()
                    ?.toList()
                    .orEmpty(),
                enableLanguageServer = root.optBoolean("enable_language_server", true),
                inlineBlame = root.optBoolean("inline_blame", true),
                showWhitespaces = ShowWhitespaces.fromKey(
                    root.optString("show_whitespaces", "selection")
                ),
                showWrapGuides = root.optBoolean("show_wrap_guides", true),
                removeTrailingWhitespaceOnSave = root.optBoolean(
                    "remove_trailing_whitespace_on_save",
                    true,
                ),
                ensureFinalNewlineOnSave = root.optBoolean("ensure_final_newline_on_save", true),
            )
        }.getOrDefault(LanguageSettings())

        /** The engine's answer for [bufferId]. **Blocking** — off the main thread. */
        fun load(bufferId: Long): LanguageSettings = parse(CoreBridge.bufferLanguageSettings(bufferId))
    }
}
