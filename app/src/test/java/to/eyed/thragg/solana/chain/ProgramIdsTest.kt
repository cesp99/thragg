package to.eyed.thragg.solana.chain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import to.eyed.thragg.solana.build.ProgramTarget
import to.eyed.thragg.solana.build.ProjectFramework
import to.eyed.thragg.solana.build.ProjectLayout
import java.io.File
import java.nio.file.Files

/**
 * The three-way reconciliation, on real temp directories. What matters is
 * the order of authority (keypair, then `declare_id!`, then Anchor.toml) and
 * that "disagree" means two *present* sources differ — a cloned project with
 * no keypair yet is not in disagreement with anything.
 */
class ProgramIdsTest {

    private lateinit var root: File
    private val program = ProgramTarget("my-program", "my_program", "target/deploy/my_program.so")

    private val idA = "Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEfcYkg476zPFsLnS"
    private val idB = "BPFLoaderUpgradeab1e11111111111111111111111"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("thragg-program-ids").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(path: String, text: String) {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun anchorProject(declared: String?, tomlId: String?, cluster: String = "devnet") {
        write(
            "programs/my-program/Cargo.toml",
            "[package]\nname = \"my-program\"\n[lib]\nname = \"my_program\"\n[dependencies]\nanchor-lang = \"0.31\"\n",
        )
        write(
            "programs/my-program/src/lib.rs",
            "use anchor_lang::prelude::*;\n\n" +
                (declared?.let { "declare_id!(\"$it\");\n\n" } ?: "") +
                "#[program]\npub mod my_program {}\n",
        )
        write(
            "Anchor.toml",
            "[provider]\ncluster = \"$cluster\"\n\n" +
                (tomlId?.let { "[programs.$cluster]\nmy_program = \"$it\"\n" } ?: ""),
        )
    }

    private fun writeKeypair(): Keypair {
        val keypair = Keypair.generate()
        Keypair.write(ProgramIds.keypairFile(root.path, program), keypair)
        return keypair
    }

    private fun layout(framework: ProjectFramework) = ProjectLayout(root.path, framework, listOf(program))

    // --- Seahorse's declare_id('…') ---------------------------------------------

    @Test
    fun `a Seahorse declare_id is rewritten keeping its quote, and left alone when it already agrees`() {
        val single = "from seahorse.prelude import *\n\ndeclare_id('$idA')\n"
        assertEquals(
            "from seahorse.prelude import *\n\ndeclare_id('$idB')\n",
            ProgramIds.withSeahorseDeclaredId(single, idB),
        )
        // Double quotes are kept too; the whitespace inside the call is not.
        assertEquals("declare_id(\"$idB\")", ProgramIds.withSeahorseDeclaredId("declare_id( \"$idA\" )", idB))
        assertNull(ProgramIds.withSeahorseDeclaredId(single, idA))
        assertNull(ProgramIds.withSeahorseDeclaredId("# no id here\ndeclare_id('...')\n", idB))
    }

    @Test
    fun `syncSeahorseIds writes the Python once a keypair exists, and only for Seahorse`() {
        write("programs_py/my_program.py", "declare_id('$idA')\n\nclass Counter(Account):\n    count: u64\n")
        // No keypair yet: the first build makes one, nothing to sync to.
        assertEquals(emptyList<String>(), ProgramIds.syncSeahorseIds(layout(ProjectFramework.Seahorse)))
        val keypair = writeKeypair()
        // An Anchor layout has no Python to sync.
        assertEquals(emptyList<String>(), ProgramIds.syncSeahorseIds(layout(ProjectFramework.Anchor)))
        assertEquals(listOf("programs_py/my_program.py"), ProgramIds.syncSeahorseIds(layout(ProjectFramework.Seahorse)))
        val text = File(root, "programs_py/my_program.py").readText()
        assertTrue(text.startsWith("declare_id('${keypair.publicKey.base58}')\n"))
        assertTrue(text.contains("class Counter(Account)"))
        // Idempotent: the second pass finds nothing to change.
        assertEquals(emptyList<String>(), ProgramIds.syncSeahorseIds(layout(ProjectFramework.Seahorse)))
    }

    // --- declare_id! ----------------------------------------------------------

    @Test
    fun `declaredId reads the first declare_id and tolerates spaces`() {
        assertEquals(idA, ProgramIds.declaredId("declare_id!(\"$idA\");"))
        assertEquals(idA, ProgramIds.declaredId("declare_id! ( \"$idA\" ) ;"))
        assertEquals(idA, ProgramIds.declaredId("use x;\ndeclare_id!(\n    \"$idA\"\n);\n"))
        assertEquals(idA, ProgramIds.declaredId("declare_id!(\"$idA\");\ndeclare_id!(\"$idB\");"))
    }

    @Test
    fun `declaredId ignores placeholders and things that are not addresses`() {
        assertNull(ProgramIds.declaredId(""))
        assertNull(ProgramIds.declaredId("declare_id!(\"...\");"))
        assertNull(ProgramIds.declaredId("declare_id!(\"not base58 0OIl\");"))
        assertNull(ProgramIds.declaredId("declare_id!(\"tooShort\");"))
        assertNull(ProgramIds.declaredId("// declare_id!(nope)\nsolana_program::declare_id!(x);"))
    }

    // --- resolve ---------------------------------------------------------------

    @Test
    fun `keypair file is target deploy module dash keypair`() {
        assertEquals(
            File(root, "target/deploy/my_program-keypair.json"),
            ProgramIds.keypairFile(root.path, program),
        )
    }

    @Test
    fun `nothing at all resolves to none`() {
        val resolved = ProgramIds.resolve(root.path, program, Cluster.Devnet)
        assertNull(resolved.id)
        assertEquals(ProgramIds.Source.None, resolved.source)
        assertFalse(resolved.disagree)
    }

    @Test
    fun `the keypair wins over declare_id and Anchor toml`() {
        anchorProject(declared = idA, tomlId = idA)
        val keypair = writeKeypair()
        val resolved = ProgramIds.resolve(root.path, program, Cluster.Devnet)
        assertEquals(keypair.publicKey.base58, resolved.id)
        assertEquals(ProgramIds.Source.Keypair, resolved.source)
        assertEquals(idA, resolved.declaredId)
        assertEquals(idA, resolved.anchorTomlId)
        assertTrue(resolved.disagree)
    }

    @Test
    fun `declare_id wins over Anchor toml when there is no keypair`() {
        anchorProject(declared = idA, tomlId = idB)
        val resolved = ProgramIds.resolve(root.path, program, Cluster.Devnet)
        assertEquals(idA, resolved.id)
        assertEquals(ProgramIds.Source.DeclareId, resolved.source)
        assertNull(resolved.keypairId)
        assertTrue(resolved.disagree)
    }

    @Test
    fun `Anchor toml is the last resort, read for the asked cluster only`() {
        anchorProject(declared = null, tomlId = idB, cluster = "devnet")
        val devnet = ProgramIds.resolve(root.path, program, Cluster.Devnet)
        assertEquals(idB, devnet.id)
        assertEquals(ProgramIds.Source.AnchorToml, devnet.source)
        assertFalse(devnet.disagree)

        val testnet = ProgramIds.resolve(root.path, program, Cluster.Testnet)
        assertNull(testnet.id)
        assertEquals(ProgramIds.Source.None, testnet.source)
    }

    @Test
    fun `a single present source never disagrees`() {
        anchorProject(declared = idA, tomlId = null)
        assertFalse(ProgramIds.resolve(root.path, program, Cluster.Devnet).disagree)
    }

    @Test
    fun `three agreeing sources do not disagree`() {
        val keypair = writeKeypair()
        val id = keypair.publicKey.base58
        anchorProject(declared = id, tomlId = id)
        val resolved = ProgramIds.resolve(root.path, program, Cluster.Devnet)
        assertEquals(id, resolved.id)
        assertFalse(resolved.disagree)
    }

    @Test
    fun `a Native project reads src lib rs at the root`() {
        write("Cargo.toml", "[package]\nname = \"my-program\"\n[dependencies]\nsolana-program = \"2\"\n")
        write("src/lib.rs", "declare_id!(\"$idA\");")
        val resolved = ProgramIds.resolve(root.path, program, Cluster.Devnet)
        assertEquals(idA, resolved.declaredId)
        assertNull(resolved.anchorTomlId)
    }

    @Test
    fun `an Anchor program in a folder named unlike its crate is found through its Cargo toml`() {
        write(
            "programs/some-dir/Cargo.toml",
            "[package]\nname = \"my-program\"\n[dependencies]\nanchor-lang = \"0.31\"\n",
        )
        write("programs/some-dir/src/lib.rs", "declare_id!(\"$idB\");")
        write("Anchor.toml", "[provider]\ncluster = \"devnet\"\n")
        assertEquals(idB, ProgramIds.resolve(root.path, program, Cluster.Devnet).declaredId)
    }

    // --- ensureKeypair ---------------------------------------------------------

    @Test
    fun `ensureKeypair generates once and then reads the same key back`() {
        val first = ProgramIds.ensureKeypair(root.path, program)
        assertTrue(ProgramIds.keypairFile(root.path, program).isFile)
        val second = ProgramIds.ensureKeypair(root.path, program)
        assertEquals(first.publicKey.base58, second.publicKey.base58)
        assertEquals(first.publicKey.base58, ProgramIds.resolve(root.path, program, Cluster.Devnet).keypairId)
    }

    // --- disagree(layout) ------------------------------------------------------

    @Test
    fun `Native and Unknown layouts never disagree`() {
        write("src/lib.rs", "declare_id!(\"$idA\");")
        writeKeypair()
        assertFalse(ProgramIds.disagree(layout(ProjectFramework.Native)))
        assertFalse(ProgramIds.disagree(layout(ProjectFramework.Unknown)))
    }

    @Test
    fun `an Anchor layout disagrees when the keypair and declare_id differ`() {
        anchorProject(declared = idA, tomlId = null)
        writeKeypair()
        assertTrue(ProgramIds.disagree(layout(ProjectFramework.Anchor)))
        assertTrue(ProgramIds.disagree(layout(ProjectFramework.Seahorse)))
    }

    @Test
    fun `an Anchor layout with only a declare_id does not disagree`() {
        anchorProject(declared = idA, tomlId = null)
        assertFalse(ProgramIds.disagree(layout(ProjectFramework.Anchor)))
    }

    @Test
    fun `disagree reads the Anchor toml table for the cluster Anchor toml names`() {
        // localnet is not a cluster we know, so the default (devnet) table is read.
        anchorProject(declared = idA, tomlId = null, cluster = "localnet")
        write("Anchor.toml", "[provider]\ncluster = \"localnet\"\n\n[programs.localnet]\nmy_program = \"$idB\"\n\n[programs.devnet]\nmy_program = \"$idA\"\n")
        assertFalse(ProgramIds.disagree(layout(ProjectFramework.Anchor)))

        write("Anchor.toml", "[provider]\ncluster = \"testnet\"\n\n[programs.testnet]\nmy_program = \"$idB\"\n")
        assertTrue(ProgramIds.disagree(layout(ProjectFramework.Anchor)))
    }
}
