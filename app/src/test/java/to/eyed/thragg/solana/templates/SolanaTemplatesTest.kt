package to.eyed.thragg.solana.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three names, and the four files that have to agree about them.
 *
 * These are host tests because the templates are pure strings: a scaffold's
 * correctness is knowable without a phone, and the alternative — finding out
 * that `Anchor.toml` and `lib.rs` disagree — costs the several minutes of SBF
 * build time it takes for `anchor keys sync` to silently do nothing.
 */
class SolanaNamesTest {

    @Test
    fun theExampleFromTheDocs() {
        assertEquals("my-project", SolanaNames.crateName("My Project"))
        assertEquals("my_project", SolanaNames.moduleName("My Project"))
        assertEquals("MyProject", SolanaNames.typeName("My Project"))
    }

    /** The point of the camelCase split: three spellings, one crate. */
    @Test
    fun theThreeSpellingsLandOnTheSameCrate() {
        for (typed in listOf("My Project", "my-project", "MyProject", "my_project", "my.project")) {
            assertEquals(typed, "my-project", SolanaNames.crateName(typed))
            assertEquals(typed, "my_project", SolanaNames.moduleName(typed))
            assertEquals(typed, "MyProject", SolanaNames.typeName(typed))
        }
    }

    @Test
    fun aLeadingDigitCannotStartARustIdentifier() {
        assertEquals("program_2048", SolanaNames.moduleName("2048"))
        assertEquals("program-2048", SolanaNames.crateName("2048"))
    }

    /** `pub mod match { … }` does not compile, and finding out costs a build. */
    @Test
    fun aKeywordIsNotAModuleName() {
        assertEquals("match_program", SolanaNames.moduleName("match"))
        assertEquals("loop_program", SolanaNames.moduleName("Loop"))
        // Only when the keyword is the whole name — `match maker` is fine.
        assertEquals("match_maker", SolanaNames.moduleName("match maker"))
    }

    @Test
    fun onlyANameWithNothingUsableInItIsRefused() {
        assertNotNull(SolanaNames.error("…"))
        assertNotNull(SolanaNames.error("   "))
        assertNull(SolanaNames.error("escrow"))
        // Refusing "My Project" for cargo's sake would be refusing a name the
        // filesystem accepts — the derivation is what handles it.
        assertNull(SolanaNames.error("My Project"))
        assertNull(SolanaNames.error("2048"))
    }
}

class SolanaTemplatesTest {

    private val program = SolanaProgram.of("My Project")

    @Test
    fun everyTemplateWritesTheFileItSaysItOpens() {
        for (framework in SolanaFramework.entries) {
            val paths = framework.files(program).map { it.path }
            assertTrue(
                "${framework.name} has no ${framework.entryPath(program)}",
                framework.entryPath(program) in paths,
            )
        }
    }

    @Test
    fun noTemplateWritesTheSameFileTwice() {
        for (framework in SolanaFramework.entries) {
            val paths = framework.files(program).map { it.path }
            assertEquals(framework.name, paths.size, paths.toSet().size)
        }
    }

    /**
     * The four places the program is named. `[programs.localnet]` is keyed by
     * the **lib name**, not the package name — that is the key `anchor keys
     * sync` rewrites, and the whole failure this test exists for is the two
     * disagreeing.
     */
    @Test
    fun anchorsFourFilesAgreeAboutTheProgram() {
        val files = SolanaFramework.Anchor.files(program).associateBy { it.path }
        val anchorToml = files.getValue("Anchor.toml").contents
        val manifest = files.getValue("programs/my-project/Cargo.toml").contents
        val lib = files.getValue("programs/my-project/src/lib.rs").contents
        val test = files.getValue("tests/my-project.ts").contents

        assertTrue(anchorToml.contains("my_project = \"${SolanaProgram.PLACEHOLDER_ID}\""))
        assertTrue(manifest.contains("name = \"my-project\""))
        assertTrue(manifest.contains("name = \"my_project\""))
        assertTrue(lib.contains("declare_id!(\"${SolanaProgram.PLACEHOLDER_ID}\")"))
        assertTrue(lib.contains("pub mod my_project {"))
        assertTrue(test.contains("anchor.workspace.MyProject"))
        assertTrue(test.contains("from \"../target/types/my_project\""))
    }

    /** cdylib is the deployable `.so`; the plain lib is what a test links against. */
    @Test
    fun bothRustTemplatesBuildADeployableLibrary() {
        val anchor = SolanaFramework.Anchor.files(program)
            .first { it.path.endsWith("programs/my-project/Cargo.toml") }.contents
        val native = SolanaFramework.Native.files(program)
            .first { it.path == "Cargo.toml" }.contents
        for (manifest in listOf(anchor, native)) {
            assertTrue(manifest.contains("""crate-type = ["cdylib", "lib"]"""))
        }
        // The native program is the one verified on the device (docs/SOLANA.md):
        // solana-program, an entrypoint, and nothing else.
        assertTrue(native.contains("solana-program = "))
        val lib = SolanaFramework.Native.files(program).first { it.path == "src/lib.rs" }.contents
        assertTrue(lib.contains("entrypoint!(process_instruction);"))
        assertTrue(lib.contains("declare_id!(\"${SolanaProgram.PLACEHOLDER_ID}\")"))
    }

    /** A silent wrap in a balance is how programs lose money. */
    @Test
    fun everyRustTemplateKeepsOverflowChecksOn() {
        for (framework in listOf(SolanaFramework.Anchor, SolanaFramework.Native, SolanaFramework.Seahorse)) {
            val workspace = framework.files(program).first { it.path == "Cargo.toml" }.contents
            assertTrue(framework.name, workspace.contains("overflow-checks = true"))
        }
    }

    @Test
    fun theClusterLandsInAnchorToml() {
        for (framework in listOf(SolanaFramework.Anchor, SolanaFramework.Seahorse)) {
            val toml = framework.files(program, "Testnet")
                .first { it.path == "Anchor.toml" }.contents
            assertTrue(framework.name, toml.contains("""cluster = "Testnet""""))
        }
        // Devnet, not Anchor's Localnet: there is no second terminal on a
        // phone to leave solana-test-validator running in.
        assertEquals("Devnet", SolanaProgram.DEFAULT_CLUSTER)
        assertTrue(SolanaProgram.DEFAULT_CLUSTER in SolanaProgram.CLUSTERS)
    }

    /** Seahorse scaffolds no Rust: `seahorse build` generates it. */
    @Test
    fun seahorseScaffoldsPythonAndNoRust() {
        val paths = SolanaFramework.Seahorse.files(program).map { it.path }
        assertTrue("programs_py/my_project.py" in paths)
        assertTrue(paths.none { it.startsWith("programs/") })
    }

    /** Interpolating a user-supplied name into a path must not escape the project. */
    @Test
    fun everyTemplatePathIsRelativeAndInsideTheProject() {
        for (framework in SolanaFramework.entries) {
            for (file in framework.files(SolanaProgram.of("../../etc"))) {
                val parts = file.path.split('/')
                assertTrue(file.path, parts.none { it.isEmpty() || it == "." || it == ".." })
                assertTrue(file.path, file.contents.isNotEmpty())
            }
        }
    }
}
