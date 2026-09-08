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
    fun `syncProgramIds writes the Python once a keypair exists, and only for Seahorse`() {
        write("programs_py/my_program.py", "declare_id('$idA')\n\nclass Counter(Account):\n    count: u64\n")
        val keypair = writeKeypair()
        // An Anchor layout has no Python to sync, and here no Anchor.toml or lib.rs either.
        assertEquals(emptyList<String>(), ProgramIds.syncProgramIds(layout(ProjectFramework.Anchor)))
        assertEquals(listOf("programs_py/my_program.py"), ProgramIds.syncProgramIds(layout(ProjectFramework.Seahorse)))
        val text = File(root, "programs_py/my_program.py").readText()
        assertTrue(text.startsWith("declare_id('${keypair.publicKey.base58}')\n"))
        assertTrue(text.contains("class Counter(Account)"))
        // Idempotent: the second pass finds nothing to change.
        assertEquals(emptyList<String>(), ProgramIds.syncProgramIds(layout(ProjectFramework.Seahorse)))
    }

    // --- Anchor.toml's [programs.<cluster>] ---------------------------------------

    /** The template's file: a `localnet` table with the placeholder, and the provider on devnet. */
    private val templateToml =
        "[toolchain]\n\n[features]\nresolution = true\nskip-lint = false\n\n" +
            "[programs.localnet]\nmy_program = \"$idA\"\n\n" +
            "[registry]\nurl = \"https://api.apr.dev\"\n\n" +
            "[provider]\ncluster = \"devnet\"\nwallet = \"~/.config/solana/id.json\"\n"

    @Test
    fun `syncProgramIds writes the provider cluster table and fixes the localnet placeholder, once`() {
        // The Seeker, 2026-09-08: after `anchor keys sync` on a devnet project
        // Anchor.toml still had the placeholder under localnet and no devnet table.
        write("Anchor.toml", templateToml)
        write("programs/my-program/src/lib.rs", "declare_id!(\"$idA\");\n")
        val id = writeKeypair().publicKey.base58

        // lib.rs holds the placeholder too, and since 2026-09-08 the sync is
        // `anchor keys sync` done early, so it is rewritten in the same pass.
        assertEquals(
            listOf("Anchor.toml", "programs/my-program/src/lib.rs"),
            ProgramIds.syncProgramIds(layout(ProjectFramework.Anchor)),
        )
        assertEquals("declare_id!(\"$id\");\n", File(root, "programs/my-program/src/lib.rs").readText())
        val text = File(root, "Anchor.toml").readText()
        assertEquals(id, AnchorToml.programId(text, "devnet", "my_program"))
        assertEquals(id, AnchorToml.programId(text, "localnet", "my_program"))
        // Everything else in Anchor.toml is byte-for-byte the template's.
        assertTrue(text.startsWith("[toolchain]\n\n[features]\nresolution = true\nskip-lint = false\n\n"))
        assertTrue(text.contains("[registry]\nurl = \"https://api.apr.dev\"\n\n[provider]\ncluster = \"devnet\"\n"))
        // No Python exists for an Anchor project, and none was invented.
        assertFalse(File(root, "programs_py").exists())

        // Idempotent: the second pass has nothing to write.
        assertEquals(emptyList<String>(), ProgramIds.syncProgramIds(layout(ProjectFramework.Anchor)))
        assertEquals(text, File(root, "Anchor.toml").readText())
    }

    @Test
    fun `syncProgramIds leaves a localnet id that is not the placeholder, and falls back to devnet`() {
        // A real id under localnet is somebody's deliberate map; only the
        // placeholder is a lie `keys sync` was meant to replace. And a provider
        // saying `localnet` names no cluster we have, so the default table is written.
        write("Anchor.toml", "[programs.localnet]\nmy_program = \"$idB\"\n\n[provider]\ncluster = \"localnet\"\n")
        val id = writeKeypair().publicKey.base58
        assertEquals(listOf("Anchor.toml"), ProgramIds.syncProgramIds(layout(ProjectFramework.Anchor)))
        val text = File(root, "Anchor.toml").readText()
        assertEquals(idB, AnchorToml.programId(text, "localnet", "my_program"))
        assertEquals(id, AnchorToml.programId(text, "devnet", "my_program"))
    }

    @Test
    fun `a fresh scaffold's first sync makes the keypair and points every file at it`() {
        // tidepool on the Seeker, 2026-09-08: with no keypair before the first
        // build there was nothing to sync to, seahorse regenerated lib.rs from
        // a Python holding the placeholder, and anchor refused its own build.
        write("Anchor.toml", templateToml)
        write("programs/my-program/src/lib.rs", "use anchor_lang::prelude::*;\n\ndeclare_id!(\"$idA\");\n")
        write("programs_py/my_program.py", "declare_id('$idA')\n")
        val changed = ProgramIds.syncProgramIds(layout(ProjectFramework.Seahorse))
        val keypair = Keypair.read(ProgramIds.keypairFile(root.path, program))
        assertTrue(changed.toString(), keypair != null)
        val id = keypair!!.publicKey.base58
        assertEquals(
            listOf("Anchor.toml", "target/deploy/my_program-keypair.json", "programs/my-program/src/lib.rs", "programs_py/my_program.py"),
            changed,
        )
        assertEquals("use anchor_lang::prelude::*;\n\ndeclare_id!(\"$id\");\n", File(root, "programs/my-program/src/lib.rs").readText())
        assertEquals("declare_id('$id')\n", File(root, "programs_py/my_program.py").readText())
        assertEquals(id, AnchorToml.programId(File(root, "Anchor.toml").readText(), "devnet", "my_program"))
        // The second pass has nothing left to do, and the keypair is the same one.
        assertEquals(emptyList<String>(), ProgramIds.syncProgramIds(layout(ProjectFramework.Seahorse)))
        assertEquals(id, Keypair.read(ProgramIds.keypairFile(root.path, program))!!.publicKey.base58)
    }

    @Test
    fun `withDeclaredId moves only the address`() {
        assertEquals("declare_id!(\"$idB\"); // keep", ProgramIds.withDeclaredId("declare_id!(\"$idA\"); // keep", idB))
        assertEquals("declare_id!( \"$idB\" )", ProgramIds.withDeclaredId("declare_id!( \"$idA\" )", idB))
        assertNull(ProgramIds.withDeclaredId("declare_id!(\"$idA\");", idA))
        assertNull(ProgramIds.withDeclaredId("pub mod x {}", idB))
    }

    @Test
    fun `syncProgramIds rewrites a row keyed by the crate name instead of adding a module row`() {
        // A cloned or hand-written Anchor.toml keys the table the way resolve()
        // also reads it — by crate name. Adding `my_program = …` beside
        // `my-program = …` would leave the file holding two ids for one program.
        write("Anchor.toml", "[programs.localnet]\nmy-program = \"$idA\"\n\n[programs.devnet]\nmy-program = \"$idB\"\n\n[provider]\ncluster = \"devnet\"\n")
        val id = writeKeypair().publicKey.base58
        assertEquals(listOf("Anchor.toml"), ProgramIds.syncProgramIds(layout(ProjectFramework.Anchor)))
        val text = File(root, "Anchor.toml").readText()
        assertEquals(id, AnchorToml.programId(text, "devnet", "my-program"))
        assertEquals(id, AnchorToml.programId(text, "localnet", "my-program"))
        assertNull(AnchorToml.programId(text, "devnet", "my_program"))
        assertNull(AnchorToml.programId(text, "localnet", "my_program"))
        assertEquals(emptyList<String>(), ProgramIds.syncProgramIds(layout(ProjectFramework.Anchor)))
    }

    @Test
    fun `a Seahorse project gets both the Python and Anchor toml, Anchor toml first`() {
        write("Anchor.toml", templateToml)
        write("programs_py/my_program.py", "declare_id('$idA')\n")
        val id = writeKeypair().publicKey.base58
        assertEquals(
            listOf("Anchor.toml", "programs_py/my_program.py"),
            ProgramIds.syncProgramIds(layout(ProjectFramework.Seahorse)),
        )
        assertEquals(id, AnchorToml.programId(File(root, "Anchor.toml").readText(), "devnet", "my_program"))
        assertEquals("declare_id('$id')\n", File(root, "programs_py/my_program.py").readText())
        assertEquals(emptyList<String>(), ProgramIds.syncProgramIds(layout(ProjectFramework.Seahorse)))
    }

    @Test
    fun `withAnchorTomlId is a no-op when both tables already agree`() {
        val text = "[programs.localnet]\nmy_program = \"$idB\"\n\n[programs.devnet]\nmy_program = \"$idB\"\n"
        assertEquals(text, ProgramIds.withAnchorTomlId(text, Cluster.Devnet, "my_program", idB))
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
