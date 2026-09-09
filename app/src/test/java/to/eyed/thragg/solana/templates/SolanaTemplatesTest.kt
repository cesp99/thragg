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
        val test = files.getValue("tests/anchor.test.ts").contents

        assertTrue(anchorToml.contains("my_project = \"${SolanaProgram.PLACEHOLDER_ID}\""))
        assertTrue(manifest.contains("name = \"my-project\""))
        assertTrue(manifest.contains("name = \"my_project\""))
        assertTrue(lib.contains("declare_id!(\"${SolanaProgram.PLACEHOLDER_ID}\")"))
        assertTrue(lib.contains("mod my_project {"))
        assertTrue(test.contains("anchor.workspace.MyProject"))
        assertTrue(test.contains("from \"../target/types/my_project\""))
    }

    /**
     * The point of the templates: a user arriving from Solana Playground opens
     * the program they know. The files under src/test/resources/playground are
     * Playground's `client/src/frameworks/<framework>/files/src/` as fetched
     * on 2026-09-08 (URLs in the docs); the rendered source must equal them
     * but for the program id and, for Anchor, the module name.
     */
    @Test
    fun programSourcesArePlaygroundsByteForByte() {
        val anchor = SolanaFramework.Anchor.files(program)
            .first { it.path == "programs/my-project/src/lib.rs" }.contents
        assertEquals(
            playground("anchor-lib.rs")
                .replace("11111111111111111111111111111111", SolanaProgram.PLACEHOLDER_ID)
                .replace("mod hello_anchor {", "mod my_project {"),
            anchor,
        )

        val native = SolanaFramework.Native.files(program)
            .first { it.path == "src/lib.rs" }.contents
        assertEquals(playground("native-lib.rs"), native)

        // Asked for by the path the framework says it opens, because that path
        // now carries the project's name: what has to be Playground's, and is
        // still checked here byte for byte, is the file's CONTENTS — the
        // `FizzBuzz` account, the `'fizzbuzz'` seeds, the header comment and
        // all — with nothing but the id substituted.
        val seahorse = SolanaFramework.Seahorse.files(program)
            .first { it.path == SolanaFramework.Seahorse.entryPath(program) }.contents
        assertEquals("programs_py/my_project.py", SolanaFramework.Seahorse.entryPath(program))
        assertEquals(
            playground("fizzbuzz.py")
                .replace("11111111111111111111111111111111", SolanaProgram.PLACEHOLDER_ID),
            seahorse,
        )
    }

    /**
     * The id sync (chain/ProgramIds.kt) finds `declare_id` by regex and the
     * Anchor.toml row by the placeholder, so every id the templates write has
     * to be the one it looks for — Playground's `1111…` would be left alone.
     */
    @Test
    fun everyIdTheTemplatesWriteIsTheSyncsPlaceholder() {
        for (framework in SolanaFramework.entries) {
            for (file in framework.files(program)) {
                assertTrue(file.path, !file.contents.contains("11111111111111111111111111111111"))
            }
        }
    }

    /**
     * Playground's tests are the model, but its `pg.*` globals and `async
     * describe` do not exist under mocha; the scaffolded tests must say the
     * same thing in standard Anchor. This pins the shape the device run
     * verified (docs/SOLANA.md, "How tests run").
     */
    @Test
    fun testsMirrorPlaygroundsWithoutItsGlobals() {
        val anchor = SolanaFramework.Anchor.files(program)
            .first { it.path == "tests/anchor.test.ts" }.contents
        val seahorse = SolanaFramework.Seahorse.files(program)
            .first { it.path == "tests/seahorse.test.ts" }.contents
        for ((name, test) in listOf("anchor" to anchor, "seahorse" to seahorse)) {
            assertTrue(name, test.startsWith("// Mirrors Solana Playground's default"))
            assertTrue(name, test.codeLines().none { "pg." in it })
            assertTrue(name, test.contains("anchor.AnchorProvider.env()"))
            assertTrue(name, test.contains("import { assert } from \"chai\""))
            // Playground's `.accounts({...})` names resolvable accounts, which
            // Anchor 0.30+'s typed accounts() refuses.
            assertTrue(name, !test.contains(".accounts({"))
            assertTrue(name, test.contains(".accountsPartial({"))
            assertTrue(name, test.contains("Use 'solana confirm -v \${txHash}' to see the logs"))
        }
        assertTrue(anchor.contains("describe(\"Test\""))
        assertTrue(anchor.contains("it(\"initialize\""))
        assertTrue(anchor.contains("new BN(42)"))
        assertTrue(anchor.contains("assert(data.eq(newAccount.data));"))
        assertTrue(seahorse.contains("describe(\"FizzBuzz\", () => {"))
        assertTrue(seahorse.contains("findProgramAddressSync("))
        assertTrue(seahorse.contains("it(\"init\""))
        assertTrue(seahorse.contains("it(\"doFizzbuzz\""))
        assertTrue(seahorse.contains(".doFizzbuzz(new BN(6000))"))
        assertTrue(seahorse.contains("assert.equal(fizzBuzzAccount.n, 0);"))
    }

    /** Playground's third default file, runnable through Anchor.toml's `[scripts] client`. */
    @Test
    fun theClientScriptShipsWithAScriptToRunIt() {
        for (framework in listOf(SolanaFramework.Anchor, SolanaFramework.Seahorse)) {
            val files = framework.files(program).associateBy { it.path }
            val client = files.getValue("client/client.ts").contents
            assertTrue(framework.name, client.contains("console.log(\"My address:\""))
            assertTrue(framework.name, client.contains("LAMPORTS_PER_SOL"))
            assertTrue(framework.name, client.codeLines().none { "pg." in it })
            assertTrue(
                framework.name,
                files.getValue("Anchor.toml").contents.contains("client = \"yarn run ts-node client/*.ts\""),
            )
        }
    }

    /** The lines that run — the header comments are allowed to name `pg.*`. */
    private fun String.codeLines(): List<String> =
        lines().filter { !it.trimStart().startsWith("//") }

    private fun playground(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/playground/$name")) { "missing test resource $name" }
            .bufferedReader().use { it.readText() }
            .let { if (it.endsWith("\n")) it else it + "\n" }

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
        // Playground's native starter derives Borsh, and borsh 1 no longer
        // turns `derive` on by default — a manifest without it is a build
        // that fails after the toolchain download.
        assertTrue(native.contains("solana-program = "))
        assertTrue(native.contains("borsh = { version = \"1.5\", features = [\"derive\"] }"))
        val lib = SolanaFramework.Native.files(program).first { it.path == "src/lib.rs" }.contents
        assertTrue(lib.contains("entrypoint!(process_instruction);"))
        // No declare_id!, as in Playground: ProgramIds reads a Native id from
        // the keypair, so a fresh Native scaffold has one claim and cannot
        // disagree with itself (QA B-05).
        assertTrue(!lib.contains("declare_id!"))
        // P-24: `entrypoint!` names two features this crate does not declare,
        // and a scaffold's first Test printed four warnings about them.
        assertTrue(native.contains("[lints.rust]"))
        assertTrue(native.contains("custom-heap"))
        assertTrue(native.contains("custom-panic"))
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

    /**
     * The picker offers only the three real networks. There is no validator
     * on the phone (chain/Cluster.kt refuses `localnet` for the same reason),
     * so a project must not be born pointing at one — while Anchor's
     * `[programs.localnet]` program-id table stays, because `anchor keys
     * sync` looks it up there whatever the provider cluster says.
     */
    @Test
    fun localnetIsNotOfferedButItsProgramTableStays() {
        assertEquals(listOf("Devnet", "Testnet", "Mainnet"), SolanaProgram.CLUSTERS)
        for (cluster in SolanaProgram.CLUSTERS) {
            val toml = SolanaFramework.Anchor.files(program, cluster)
                .first { it.path == "Anchor.toml" }.contents
            assertTrue(cluster, toml.contains("""cluster = "$cluster""""))
            assertTrue(cluster, toml.contains("[programs.localnet]"))
        }
    }

    /**
     * Seahorse writes the program's `src/` itself and nothing else, so the
     * scaffold ships the Python, the crate manifest Seahorse never writes,
     * and a placeholder `lib.rs` that gives cargo a target until the first
     * build replaces it.
     *
     * Every name in it is the **project's**, as it is for Anchor and Native:
     * the `.py` stem *is* the program name in Seahorse, and the directory, the
     * crate, the Anchor.toml row and the IDL type all follow the stem. It used
     * to be Playground's `fizzbuzz` whatever was typed, so a project created
     * as `r4_seahorse` deployed a program called `fizzbuzz` while the form's
     * own derivation line said the same and was disbelieved (QA 0.0.23, r4).
     * What stayed Playground's is the program's text —
     * `programSourcesArePlaygroundsByteForByte` is the test for that.
     */
    @Test
    fun seahorseScaffoldsPlaygroundsFizzbuzzUnderTheProjectsName() {
        val files = SolanaFramework.Seahorse.files(program)
        val paths = files.map { it.path }
        assertTrue("programs_py/my_project.py" in paths)
        assertTrue("programs/my_project/Cargo.toml" in paths)
        assertTrue("programs/my_project/src/lib.rs" in paths)
        assertTrue(paths.none { it.contains("fizzbuzz") })
        assertEquals("programs_py/my_project.py", SolanaFramework.Seahorse.entryPath(program))
        assertEquals("my_project", SolanaFramework.Seahorse.programNames(program).moduleName)
        assertEquals("my_project", SolanaFramework.Anchor.programNames(program).moduleName)

        val manifest = files.first { it.path == "programs/my_project/Cargo.toml" }.contents
        assertTrue(manifest.contains("name = \"my_project\""))
        assertTrue(manifest.contains("anchor-spl"))
        assertTrue(manifest.contains("idl-build"))
        val lib = files.first { it.path == "programs/my_project/src/lib.rs" }.contents
        assertTrue(lib.contains("declare_id!(\"${SolanaProgram.PLACEHOLDER_ID}\")"))
        assertTrue(lib.contains("Replaced by `seahorse build`"))
        // The id sync rewrites this row and the Python's declare_id together.
        val toml = files.first { it.path == "Anchor.toml" }.contents
        assertTrue(toml.contains("my_project = \"${SolanaProgram.PLACEHOLDER_ID}\""))
        // Anchor.toml's `[scripts] test` runs tests/**/*.ts; an empty glob is a
        // failing `anchor test`, so the suite ships with the Python.
        val suite = files.first { it.path == "tests/seahorse.test.ts" }.contents
        assertTrue(suite.contains("../target/types/my_project"))
        assertTrue(suite.contains("anchor.workspace.MyProject"))
        assertTrue(suite.contains("owner: provider.publicKey"))
        // The program Playground wrote is still the one in the file: its
        // account, its seeds and its two instructions, under the new name.
        val source = files.first { it.path == "programs_py/my_project.py" }.contents
        assertTrue(source.contains("class FizzBuzz(Account):"))
        assertTrue(source.contains("seeds = ['fizzbuzz', owner]"))
        // The project's own name survives where it is a title, not an identifier.
        assertTrue(files.first { it.path == "README.md" }.contents.startsWith("# My Project\n"))
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
