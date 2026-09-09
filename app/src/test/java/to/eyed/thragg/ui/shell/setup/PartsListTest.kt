package to.eyed.thragg.ui.shell.setup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.solana.toolchain.ToolchainManifest

/**
 * The parts list says which parts are optional — P-01.
 *
 * Anchor, Seahorse, Node, Spettro and the editor toolchain are optional in the
 * manifest: the gate opens on the required rows and the button says so while
 * the rest keep going. The list drew all ten rows identically, so the only way
 * to know which 700 MB was compulsory was to read the manifest — on the one
 * screen whose entire job is telling the truth about what an install costs.
 *
 * Asserted against the shipped manifest rather than a fixture, for the reason
 * ToolchainManifestTest gives: the file is the data, and a `required` flag
 * moved by hand should fail here in a second rather than on a phone.
 */
class PartsListTest {

    private val components = manifest().components

    @Test
    fun `an optional row says so before it says anything else`() {
        val optional = components.filter { !it.required }
        assertTrue("the manifest has no optional rows to check", optional.isNotEmpty())
        for (component in optional) {
            assertTrue(
                "${component.id} does not say it is optional",
                rowSummary(component).startsWith("Optional — "),
            )
            assertTrue(rowSummary(component).endsWith(component.summary))
        }
    }

    @Test
    fun `a required row is its own sentence and nothing else`() {
        val required = components.filter { it.required }
        assertTrue(required.isNotEmpty())
        for (component in required) {
            assertEquals(component.summary, rowSummary(component))
        }
    }

    private fun manifest(): ToolchainManifest {
        val relative = "src/main/assets/${ToolchainManifest.ASSET_PATH}"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        return ToolchainManifest.parse(file.readText())
    }
}
