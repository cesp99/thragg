package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * The two JSON records, round-tripped through their pure parse/render pair
 * and through a real file on a temp directory. No Context: the shape is the
 * contract, and the objects' Context-taking entry points are three lines of
 * file plumbing around these.
 */
class ChainRecordsTest {

    private val idA = "Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEfcYkg476zPFsLnS"
    private val idB = "BPFLoaderUpgradeab1e11111111111111111111111"
    private val wallet = "7NJdqYhPoKZgZ5Wn1cXqM6TJrH8gjTfVzVQ9xk3W4kQz"

    private val program = DeployedProgram(
        name = "my_program",
        programId = idA,
        cluster = Cluster.Devnet,
        authority = wallet,
        deployedAt = 1_756_800_000_000L,
        signature = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW",
        projectRoot = "/data/user/0/to.eyed.seeker.code/files/projects/escrow",
    )

    private val bare = DeployedProgram(
        name = "other",
        programId = idB,
        cluster = Cluster.MainnetBeta,
        authority = null,
        deployedAt = 0L,
        signature = null,
        projectRoot = null,
    )

    private val buffer = OpenBuffer(
        address = idB,
        cluster = Cluster.Testnet,
        authority = wallet,
        createdAt = 1_756_800_000_000L,
        programId = idA,
    )

    // --- deployed programs -----------------------------------------------------

    @Test
    fun `deployed programs round trip through render and parse`() {
        val list = listOf(program, bare)
        assertEquals(list, DeployedPrograms.parse(DeployedPrograms.render(list)))
    }

    @Test
    fun `null fields are left out of the JSON rather than written as null`() {
        val json = JSONObject(DeployedPrograms.render(listOf(bare)))
        val entry = json.getJSONArray("programs").getJSONObject(0)
        assertFalse(entry.has("authority"))
        assertFalse(entry.has("signature"))
        assertFalse(entry.has("projectRoot"))
        assertEquals("mainnet-beta", entry.getString("cluster"))
    }

    @Test
    fun `a JSON null reads back as a Kotlin null`() {
        val text = """{"programs":[{"name":"x","programId":"$idA","cluster":"devnet","authority":null,"deployedAt":1}]}"""
        val parsed = DeployedPrograms.parse(text)
        assertEquals(1, parsed.size)
        assertNull(parsed[0].authority)
        assertNull(parsed[0].signature)
    }

    @Test
    fun `an entry on a cluster this build does not know is dropped, not fatal`() {
        val text = """{"programs":[
            {"name":"a","programId":"$idA","cluster":"localnet","deployedAt":1},
            {"name":"b","programId":"$idB","cluster":"Devnet","deployedAt":2},
            {"name":"c","cluster":"devnet","deployedAt":3}
        ]}"""
        val parsed = DeployedPrograms.parse(text)
        assertEquals(listOf(idB), parsed.map { it.programId })
        assertEquals(Cluster.Devnet, parsed[0].cluster)
    }

    @Test
    fun `empty, blank and shapeless text parse as no programs`() {
        assertEquals(emptyList<DeployedProgram>(), DeployedPrograms.parse(""))
        assertEquals(emptyList<DeployedProgram>(), DeployedPrograms.parse("  \n"))
        assertEquals(emptyList<DeployedProgram>(), DeployedPrograms.parse("{}"))
        assertEquals(emptyList<DeployedProgram>(), DeployedPrograms.parse("""{"programs":[]}"""))
    }

    @Test
    fun `the rendered file is one JSON object with a programs array`() {
        val json = JSONObject(DeployedPrograms.render(emptyList()))
        assertEquals(0, json.getJSONArray("programs").length())
    }

    // --- open buffers ----------------------------------------------------------

    @Test
    fun `open buffers round trip through render and parse`() {
        val list = listOf(buffer, buffer.copy(address = idA, programId = null, cluster = Cluster.Devnet))
        assertEquals(list, OpenBuffers.parse(OpenBuffers.render(list)))
    }

    @Test
    fun `a buffer without a program id leaves the key out`() {
        val json = JSONObject(OpenBuffers.render(listOf(buffer.copy(programId = null))))
        val entry = json.getJSONArray("buffers").getJSONObject(0)
        assertFalse(entry.has("programId"))
        assertEquals("testnet", entry.getString("cluster"))
        assertEquals(wallet, entry.getString("authority"))
    }

    @Test
    fun `buffers on unknown clusters or without an address are dropped`() {
        val text = """{"buffers":[
            {"address":"$idA","cluster":"nope","authority":"$wallet","createdAt":1},
            {"cluster":"devnet","authority":"$wallet","createdAt":2},
            {"address":"$idB","cluster":"devnet","authority":"$wallet","createdAt":3}
        ]}"""
        val parsed = OpenBuffers.parse(text)
        assertEquals(listOf(idB), parsed.map { it.address })
        assertEquals(3L, parsed[0].createdAt)
    }

    @Test
    fun `empty text parses as no buffers`() {
        assertEquals(emptyList<OpenBuffer>(), OpenBuffers.parse(""))
        assertEquals(emptyList<OpenBuffer>(), OpenBuffers.parse("{}"))
    }

    // --- on disk ---------------------------------------------------------------

    @Test
    fun `what render writes to a file, parse reads back`() {
        val dir = Files.createTempDirectory("seeker-chain").toFile()
        try {
            val file = File(dir, "chain/deployed-programs.json")
            file.parentFile.mkdirs()
            file.writeText(DeployedPrograms.render(listOf(program)))
            assertEquals(listOf(program), DeployedPrograms.parse(file.readText()))

            val buffers = File(dir, "chain/open-buffers.json")
            buffers.writeText(OpenBuffers.render(listOf(buffer)))
            assertEquals(listOf(buffer), OpenBuffers.parse(buffers.readText()))
            assertTrue(buffers.isFile)
        } finally {
            dir.deleteRecursively()
        }
    }
}
