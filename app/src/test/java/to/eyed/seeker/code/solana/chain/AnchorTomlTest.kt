package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text rewriter, held to its one promise: the line asked about changes,
 * nothing else does. Every "with" test diffs the result against the input
 * line by line, so a reflowed comment or a dropped blank line fails here
 * rather than in someone's git diff.
 */
class AnchorTomlTest {

    private val scaffold = """
        [toolchain]

        [features]
        resolution = true
        skip-lint = false

        [programs.localnet]
        my_program = "Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEfcYkg476zPFsLnS"

        [registry]
        url = "https://api.apr.dev"

        [provider]
        cluster = "localnet"
        wallet = "~/.config/solana/id.json"

        [scripts]
        test = "yarn run ts-mocha -p ./tsconfig.json -t 1000000 tests/**/*.ts"
    """.trimIndent() + "\n"

    private val id = "Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEfcYkg476zPFsLnS"
    private val other = "BPFLoaderUpgradeab1e11111111111111111111111"

    /** Every line of [before] that is not in [changed] must appear unchanged, in order. */
    private fun assertOnlyTheseLinesChanged(before: String, after: String, changed: Set<String>) {
        val kept = before.lines().filter { it !in changed }
        val afterLines = after.lines()
        var cursor = 0
        for (line in kept) {
            val at = afterLines.subList(cursor, afterLines.size).indexOf(line)
            assertTrue("line lost or reordered: '$line'\n$after", at >= 0)
            cursor += at + 1
        }
    }

    // --- provider cluster ------------------------------------------------------

    @Test
    fun `reads the provider cluster as written`() {
        assertEquals("localnet", AnchorToml.providerCluster(scaffold))
        assertEquals("Devnet", AnchorToml.providerCluster(scaffold.replace("\"localnet\"", "\"Devnet\"")))
        assertEquals("devnet", AnchorToml.providerCluster("[provider]\ncluster = 'devnet'\n"))
        assertEquals("devnet", AnchorToml.providerCluster("[provider]\ncluster = devnet # bare\n"))
        assertEquals("devnet", AnchorToml.providerCluster("[ provider ]\n  \"cluster\" = \"devnet\"\n"))
    }

    @Test
    fun `no provider table or no cluster row reads as null`() {
        assertNull(AnchorToml.providerCluster(""))
        assertNull(AnchorToml.providerCluster("[features]\nresolution = true\n"))
        assertNull(AnchorToml.providerCluster("[provider]\nwallet = \"x\"\n"))
        // A `cluster` key in some other table is not the provider's.
        assertNull(AnchorToml.providerCluster("[scripts]\ncluster = \"devnet\"\n"))
    }

    @Test
    fun `rewrites the cluster row in place and keeps every other line`() {
        val after = AnchorToml.withProviderCluster(scaffold, "devnet")
        assertEquals("devnet", AnchorToml.providerCluster(after))
        assertOnlyTheseLinesChanged(scaffold, after, setOf("cluster = \"localnet\""))
        assertEquals(scaffold.lines().size, after.lines().size)
        assertEquals(scaffold.replace("cluster = \"localnet\"", "cluster = \"devnet\""), after)
    }

    @Test
    fun `rewriting keeps a trailing comment, indentation and single quotes turn double`() {
        val before = "[provider]\n    cluster = 'Localnet'   # where anchor talks to\nwallet = \"w\"\n"
        val after = AnchorToml.withProviderCluster(before, "mainnet")
        assertEquals("[provider]\n    cluster = \"mainnet\"   # where anchor talks to\nwallet = \"w\"\n", after)
    }

    @Test
    fun `adds a cluster row to a provider table that lacks one`() {
        val before = "[provider]\nwallet = \"w\"\n\n[scripts]\ntest = \"t\"\n"
        val after = AnchorToml.withProviderCluster(before, "devnet")
        assertEquals("[provider]\nwallet = \"w\"\ncluster = \"devnet\"\n\n[scripts]\ntest = \"t\"\n", after)
    }

    @Test
    fun `creates the provider table at the end when there is none`() {
        val before = "[features]\nresolution = true\n"
        val after = AnchorToml.withProviderCluster(before, "testnet")
        assertEquals("[features]\nresolution = true\n\n[provider]\ncluster = \"testnet\"\n", after)
        assertEquals("testnet", AnchorToml.providerCluster(after))
    }

    @Test
    fun `creates the provider table in an empty file`() {
        assertEquals("[provider]\ncluster = \"devnet\"\n", AnchorToml.withProviderCluster("", "devnet"))
    }

    @Test
    fun `a file without a final newline gets one before the new table`() {
        val after = AnchorToml.withProviderCluster("[features]\nresolution = true", "devnet")
        assertEquals("[features]\nresolution = true\n\n[provider]\ncluster = \"devnet\"\n", after)
    }

    @Test
    fun `windows line endings survive a rewrite`() {
        val before = scaffold.replace("\n", "\r\n")
        val after = AnchorToml.withProviderCluster(before, "devnet")
        assertEquals(before.replace("cluster = \"localnet\"", "cluster = \"devnet\""), after)
    }

    // --- program tables --------------------------------------------------------

    @Test
    fun `reads a program id by cluster and module`() {
        assertEquals(id, AnchorToml.programId(scaffold, "localnet", "my_program"))
        assertNull(AnchorToml.programId(scaffold, "devnet", "my_program"))
        assertNull(AnchorToml.programId(scaffold, "localnet", "other_program"))
    }

    @Test
    fun `table names match in any letter case and with quoted parts`() {
        val text = "[programs.\"Devnet\"]\nmy_program = \"$id\"\n[programs.'testnet']\nmy_program = \"$other\"\n"
        assertEquals(id, AnchorToml.programId(text, "devnet", "my_program"))
        assertEquals(id, AnchorToml.programId(text, "DEVNET", "my_program"))
        assertEquals(other, AnchorToml.programId(text, "Testnet", "my_program"))
    }

    @Test
    fun `module keys may be quoted, and match exactly`() {
        val text = "[programs.devnet]\n\"my-program\" = \"$id\"\n"
        assertEquals(id, AnchorToml.programId(text, "devnet", "my-program"))
        assertNull(AnchorToml.programId(text, "devnet", "My-Program"))
    }

    @Test
    fun `programTables lists every cluster table lowercased`() {
        val text = scaffold + "\n[programs.Devnet]\nmy_program = \"$other\"\nsecond = \"$id\"\n"
        val tables = AnchorToml.programTables(text)
        assertEquals(setOf("localnet", "devnet"), tables.keys)
        assertEquals(mapOf("my_program" to id), tables["localnet"])
        assertEquals(mapOf("my_program" to other, "second" to id), tables["devnet"])
    }

    @Test
    fun `programTables ignores tables that are not programs`() {
        assertEquals(emptyMap<String, Map<String, String>>(), AnchorToml.programTables(scaffold.replace("[programs.localnet]", "[program.localnet]")))
        assertEquals(emptyMap<String, Map<String, String>>(), AnchorToml.programTables("[programs]\nx = \"y\"\n"))
    }

    @Test
    fun `rewrites an existing program id in place`() {
        val after = AnchorToml.withProgramId(scaffold, "localnet", "my_program", other)
        assertEquals(other, AnchorToml.programId(after, "localnet", "my_program"))
        assertEquals(scaffold.replace(id, other), after)
    }

    @Test
    fun `adds a module row to an existing table before the blank line that follows it`() {
        val after = AnchorToml.withProgramId(scaffold, "localnet", "second", other)
        val expected = scaffold.replace(
            "my_program = \"$id\"\n",
            "my_program = \"$id\"\nsecond = \"$other\"\n",
        )
        assertEquals(expected, after)
    }

    @Test
    fun `creates a missing programs table at the end`() {
        val after = AnchorToml.withProgramId(scaffold, "devnet", "my_program", other)
        assertEquals(scaffold + "\n[programs.devnet]\nmy_program = \"$other\"\n", after)
        assertEquals(other, AnchorToml.programId(after, "devnet", "my_program"))
        // The localnet table is untouched.
        assertEquals(id, AnchorToml.programId(after, "localnet", "my_program"))
        assertOnlyTheseLinesChanged(scaffold, after, emptySet())
    }

    @Test
    fun `an existing table keeps its own spelling of the cluster`() {
        val text = "[programs.Devnet]\nmy_program = \"$id\"\n"
        val after = AnchorToml.withProgramId(text, "devnet", "second", other)
        assertEquals("[programs.Devnet]\nmy_program = \"$id\"\nsecond = \"$other\"\n", after)
    }

    @Test
    fun `a module name that is not a bare key is quoted in a new table`() {
        val after = AnchorToml.withProgramId("", "devnet", "my.program", id)
        assertEquals("[programs.devnet]\n\"my.program\" = \"$id\"\n", after)
        assertEquals(id, AnchorToml.programId(after, "devnet", "my.program"))
    }

    @Test
    fun `comments and array tables are not rows or headers`() {
        val text = "# cluster = \"old\"\n[[provider]]\ncluster = \"x\"\n[provider]\n# cluster = \"y\"\ncluster = \"devnet\"\n"
        assertEquals("devnet", AnchorToml.providerCluster(text))
        val after = AnchorToml.withProviderCluster(text, "testnet")
        assertEquals(text.replace("cluster = \"devnet\"", "cluster = \"testnet\""), after)
    }
}
