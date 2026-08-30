package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.ui.editor.SoftWrapMode

/**
 * The per-buffer settings blob, as `config.rs`'s `LanguageSettings`
 * serializes it. The engine's tests cover the resolution; these cover the
 * bridge's other side reading what it sends — the `formatter` shapes above
 * all, which its serializer normalises and this parser must recognise.
 */
class LanguageSettingsTest {

    @Test
    fun readsEveryKeyTheEngineSends() {
        val parsed = LanguageSettings.parse(
            """
            {
              "tab_size": 2, "hard_tabs": true, "soft_wrap": "bounded",
              "preferred_line_length": 100, "wrap_guides": [80, 120],
              "format_on_save": "on", "formatter": "language_server",
              "code_actions_on_format": {"source.organizeImports": true, "source.fixAll": false},
              "enable_language_server": false, "inline_blame": false
            }
            """.trimIndent()
        )
        assertEquals(2, parsed.tabSize)
        assertTrue(parsed.hardTabs)
        assertEquals(SoftWrapMode.Bounded, parsed.softWrap)
        assertEquals(100, parsed.preferredLineLength)
        assertEquals(listOf(80, 120), parsed.wrapGuides)
        assertEquals(FormatOnSave.On, parsed.formatOnSave)
        assertEquals(FormatterSpec.LanguageServer(null), parsed.formatter)
        // Only the kinds set to true, in a stable order.
        assertEquals(listOf("source.organizeImports"), parsed.codeActionsOnFormat)
        assertFalse(parsed.enableLanguageServer)
        assertFalse(parsed.inlineBlame)
    }

    @Test
    fun formatterShapesAreRecognised() {
        assertEquals(FormatterSpec.Auto, FormatterSpec.parse("auto"))
        assertEquals(FormatterSpec.None, FormatterSpec.parse("none"))
        assertEquals(
            FormatterSpec.External("rustfmt", listOf("--edition", "2021")),
            LanguageSettings.parse(
                """{"formatter": {"external": {"command": "rustfmt", "arguments": ["--edition", "2021"]}}}"""
            ).formatter,
        )
        assertEquals(
            FormatterSpec.LanguageServer("ruff"),
            LanguageSettings.parse("""{"formatter": {"language_server": {"name": "ruff"}}}""").formatter,
        )
        assertEquals(
            FormatterSpec.CodeAction("source.fixAll.eslint"),
            LanguageSettings.parse("""{"formatter": {"code_action": "source.fixAll.eslint"}}""").formatter,
        )
    }

    /** Zed's defaults when the engine has not answered yet, or answered rubbish. */
    @Test
    fun defaultsAreZeds() {
        val parsed = LanguageSettings.parse("not json")
        assertEquals(LanguageSettings(), parsed)
        assertEquals(4, parsed.tabSize)
        assertFalse(parsed.hardTabs)
        assertEquals(SoftWrapMode.None, parsed.softWrap)
        assertEquals(80, parsed.preferredLineLength)
        assertEquals(FormatOnSave.Off, parsed.formatOnSave)
        assertFalse(parsed.formatsOnSave)
    }

    /**
     * `format_on_save: "language_server"` means the server even when an
     * external program is the configured formatter — that is the value's
     * whole purpose; and `formatter: "none"` means no format at all.
     */
    @Test
    fun theSaveFormatterFollowsFormatOnSave() {
        val external = FormatterSpec.External("prettier", emptyList())
        val on = LanguageSettings(formatOnSave = FormatOnSave.On, formatter = external)
        assertTrue(on.formatsOnSave)
        assertEquals(external, on.saveFormatter)

        val serverOnly = on.copy(formatOnSave = FormatOnSave.LanguageServer)
        assertEquals(FormatterSpec.LanguageServer(null), serverOnly.saveFormatter)

        val never = on.copy(formatter = FormatterSpec.None)
        assertFalse(never.formatsOnSave)
    }
}
