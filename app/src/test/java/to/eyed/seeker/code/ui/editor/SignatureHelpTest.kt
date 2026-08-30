package to.eyed.seeker.code.ui.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a `textDocument/signatureHelp` answer. The active parameter's
 * offsets are what the popover bolds, so a parse that shifts them is a
 * popover that emphasises the wrong argument on every call.
 */
class SignatureHelpTest {

    private val payload = JSONObject(
        """{"active_signature":1,"signatures":[
            {"label":"fn one(a: i32)","documentation":null,"parameters":[{"start":7,"end":13,"documentation":null}],"active_parameter":0},
            {"label":"fn two(a: i32, b: &str) -> bool","documentation":"Two things.","parameters":[
                {"start":7,"end":13,"documentation":"the first"},
                {"start":15,"end":22,"documentation":"the second"}
            ],"active_parameter":1}
        ]}"""
    )

    @Test
    fun theActiveSignatureAndParameterAreTheServers() {
        val info = SignatureHelpInfo.parse(payload)
        assertEquals(2, info.signatures.size)
        val active = info.active!!
        assertEquals("fn two(a: i32, b: &str) -> bool", active.label)
        assertEquals("Two things.", active.documentation)
        assertEquals(1, active.activeParameter)
        val parameter = active.parameters[active.activeParameter!!]
        assertEquals("b: &str", active.label.substring(parameter.start, parameter.end))
        assertEquals("the second", parameter.documentation)
    }

    @Test
    fun anActiveSignatureOutOfRangeIsClampedRatherThanThrown() {
        val info = SignatureHelpInfo.parse(JSONObject("""{"active_signature":7,"signatures":[{"label":"f()","parameters":[]}]}"""))
        assertEquals(0, info.activeSignature)
        assertEquals("f()", info.active!!.label)
        assertNull(info.active!!.activeParameter)
    }

    @Test
    fun anEmptyOrMissingListIsNotInACall() {
        assertTrue(SignatureHelpInfo.parse(null).signatures.isEmpty())
        assertTrue(SignatureHelpInfo.parse(JSONObject("""{"signatures":[]}""")).signatures.isEmpty())
        assertTrue(SignatureHelpInfo.parse(JSONObject("""{"signatures":[{"label":""}]}""")).signatures.isEmpty())
        assertNull(SignatureHelpInfo.NONE.active)
    }

    @Test
    fun theTriggerCharactersComeFromTheServer() {
        val triggers = BufferTriggers.parse(
            """{"completion":[".","::"],"signature_help":["(",","],"signature_help_retrigger":[")"],"folding_ranges":true,"inlay_hints":true}"""
        )
        assertTrue(triggers.opensSignatureHelp("("))
        assertTrue(triggers.opensSignatureHelp(","))
        assertTrue(triggers.retriggersSignatureHelp(")"))
        assertTrue(!triggers.opensSignatureHelp(")"))
        assertTrue(triggers.opensCompletions("."))
        // A two-character trigger never matches one keystroke (completions.rs:1513-1539).
        assertTrue(!triggers.opensCompletions(":"))
        assertTrue(triggers.foldingRanges)
        assertTrue(triggers.inlayHints)
        // No server: nothing opens signature help, completions keep their defaults.
        assertTrue(!BufferTriggers.NONE.opensSignatureHelp("("))
        assertTrue(BufferTriggers.NONE.opensCompletions("."))
    }
}
