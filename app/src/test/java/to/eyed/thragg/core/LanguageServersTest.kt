package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three parts of installing a language server that are pure logic, and all
 * three of which have an obvious wrong implementation:
 *
 * - the **mapping**, where "one package per language" is wrong for Python —
 *   `python3-pylsp` alone starts, initializes and then publishes nothing at
 *   all, because its linter is a *recommendation* and `--no-install-recommends`
 *   is the house rule;
 * - the **argv**, where dropping `--no-install-recommends` would quietly pull
 *   a hundred megabytes of suggestions into a phone, and where a package name
 *   reaching `/bin/sh` unchecked would be a command rather than a package;
 * - the **reading of apt**, where believing `apt-get install -s` reports a
 *   download size is wrong — it returns before printing one — and where a
 *   size that was never printed must stay null rather than become a zero the
 *   prompt would quote as "~0 B".
 */
class LanguageServersTest {

    // --- the mapping ---------------------------------------------------------

    @Test
    fun coversEveryGrammarTheEngineHasAServerFor() {
        // core/crates/engine/src/lsp.rs:107-147 — the engine's own table.
        val grammars = listOf("rust", "c", "cpp", "go", "python", "typescript", "tsx")
        for (grammar in grammars) {
            val installable = LanguageServers.forGrammar(grammar) != null
            val explained = LanguageServers.unpackagedMessage(grammar) != null
            // Exactly one of the two: something to install, or a sentence
            // saying why there is nothing. Never silence.
            assertTrue("$grammar is neither installable nor explained", installable || explained)
            assertFalse("$grammar is both installable and unpackaged", installable && explained)
        }
    }

    @Test
    fun mapsGrammarsToTheServersTheEngineSpawns() {
        assertEquals("rust-analyzer", LanguageServers.forGrammar("rust")?.server)
        assertEquals("clangd", LanguageServers.forGrammar("c")?.server)
        // One server for two grammars, as lsp.rs:126-127 has it.
        assertEquals("clangd", LanguageServers.forGrammar("cpp")?.server)
        assertEquals("pylsp", LanguageServers.forGrammar("python")?.server)
        assertEquals("gopls", LanguageServers.forGrammar("go")?.server)
        // A language we highlight and Debian has no server for is not a hole.
        assertNull(LanguageServers.forGrammar("markdown"))
        assertNull(LanguageServers.forGrammar(null))
    }

    @Test
    fun namesPythonsLinterExplicitly() {
        val python = LanguageServers.forGrammar("python")
        assertEquals(listOf("python3-pylsp", "python3-pyflakes"), python?.packages)
        assertEquals("python3-pylsp and python3-pyflakes", python?.packageList)
    }

    @Test
    fun findsTheRecipeByServerName() {
        // The status bar reports the binary, not the grammar.
        assertEquals("C and C++", LanguageServers.forServer("clangd")?.language)
        assertEquals("Python", LanguageServers.forServer("pylsp")?.language)
        // The one the engine will try to spawn and Debian does not package.
        assertNull(LanguageServers.forServer("typescript-language-server"))
        assertNull(LanguageServers.forServer(null))
    }

    @Test
    fun saysWhyTypeScriptCannotBeInstalled() {
        val message = LanguageServers.unpackagedMessage("tsx")
        assertNotNull(message)
        assertTrue(message!!.contains("TypeScript"))
        // Nothing to install, and the editor still does its other half.
        assertTrue(message.contains("highlighting"))
        assertNull(LanguageServers.unpackagedMessage("rust"))
        assertNull(LanguageServers.unpackagedMessage(null))
    }

    // --- the argv ------------------------------------------------------------

    @Test
    fun everyPackageNameIsOneDebianWouldAccept() {
        // The install argv reaches `/bin/sh -c`, because apt-get update and
        // apt-get install cannot be one argv. A name with a space, a `;` or a
        // `$` in it would be a command.
        val policy = Regex("[a-z0-9][a-z0-9+.-]+")
        for (target in LanguageServers.ALL) {
            assertTrue("${target.server} names no packages", target.packages.isNotEmpty())
            for (name in target.packages) {
                assertTrue("$name is not a Debian package name", policy.matches(name))
            }
        }
    }

    @Test
    fun estimateAsksAptWithoutInstallingAnything() {
        assertEquals(
            listOf(
                "apt-get", "install", "--assume-no", "--no-install-recommends", "--",
                "python3-pylsp", "python3-pyflakes",
            ),
            LanguageServers.estimateArgv(LanguageServers.forGrammar("python")!!),
        )
        for (target in LanguageServers.ALL) {
            val argv = LanguageServers.estimateArgv(target)
            // Never `-y`, and never anything that writes.
            assertFalse(argv.contains("-y"))
            assertFalse(argv.contains("--assume-yes"))
            assertTrue(argv.contains("--assume-no"))
            assertTrue(argv.contains("--no-install-recommends"))
            // `--` before the names, so a package can never be read as a flag.
            assertTrue(argv.indexOf("--") < argv.indexOf(target.packages.first()))
        }
    }

    @Test
    fun installRefreshesTheListsAndNamesEveryPackage() {
        assertEquals(
            listOf(
                "/bin/sh", "-c",
                "apt-get update && apt-get install -y --no-install-recommends -- " +
                    "python3-pylsp python3-pyflakes",
            ),
            LanguageServers.installArgv(LanguageServers.forGrammar("python")!!),
        )
        for (target in LanguageServers.ALL) {
            val line = LanguageServers.installArgv(target).last()
            assertTrue(
                "${target.server} would take recommendations",
                line.contains("--no-install-recommends"),
            )
            assertTrue("${target.server} would not update first", line.startsWith("apt-get update &&"))
            for (name in target.packages) {
                assertTrue("$name is not in the command", line.contains(name))
            }
        }
    }

    @Test
    fun theEnvironmentKeepsAptQuietAndInEnglish() {
        // DEBIAN_FRONTEND is what stops dpkg opening a dialog on a terminal
        // that is not there; LC_ALL is what keeps "Need to get" parseable.
        assertTrue(LanguageServers.ENVIRONMENT.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(LanguageServers.ENVIRONMENT.contains("LC_ALL=C"))
    }

    // --- reading apt ---------------------------------------------------------

    @Test
    fun readsTheSizeOutOfAnAptDryRun() {
        val plan = LanguageServers.parsePlan(PYLSP_ESTIMATE)
        assertEquals(12_400_000L, plan.downloadBytes)
        assertEquals(44_000_000L, plan.diskBytes)
        assertEquals(5, plan.newPackages)
        assertTrue(plan.hasSummary)
        assertTrue(plan.missing.isEmpty())
        assertEquals("12.4 MB", LanguageServers.formatBytes(plan.downloadBytes))
    }

    @Test
    fun readsTheTotalRatherThanTheRemainderOfAPartlyCachedDownload() {
        // "Need to get 0 B/12.4 MB": the first number is what is left to
        // fetch, the second is the whole. Quoting the first would promise a
        // free install of a package apt is about to download in full on a
        // machine whose cache we never inspected.
        val plan = LanguageServers.parsePlan(
            "0 upgraded, 1 newly installed, 0 to remove and 0 not upgraded.\n" +
                "Need to get 0 B/12.4 MB of archives.\n"
        )
        assertEquals(12_400_000L, plan.downloadBytes)
    }

    @Test
    fun readsApt3sRewrittenSizeLines() {
        // apt 3.0 with APT::Output-Version >= 30 (private-install.cc:396-411).
        val plan = LanguageServers.parsePlan(
            "  Download size: 0 B / 857 kB\n  Space needed: 3,600 kB / 12.0 GB available\n"
        )
        assertEquals(857_000L, plan.downloadBytes)
        assertEquals(3_600_000L, plan.diskBytes)
    }

    @Test
    fun leavesASizeAptNeverPrintedAsNull() {
        // `apt-get install -s` really does print no size at all: the simulator
        // returns before that block. A parser that answered 0 here would put
        // "~0 B" in the question.
        val plan = LanguageServers.parsePlan(SIMULATION_ONLY)
        assertNull(plan.downloadBytes)
        assertNull(plan.diskBytes)
        assertEquals(1, plan.newPackages)
        assertNull(LanguageServers.formatBytes(null))
    }

    @Test
    fun noticesWhenAptHasNoPackageLists() {
        val plan = LanguageServers.parsePlan(
            "Reading package lists...\nBuilding dependency tree...\n" +
                "E: Unable to locate package python3-pylsp\n" +
                "E: Unable to locate package python3-pyflakes\n"
        )
        assertEquals(listOf("python3-pylsp", "python3-pyflakes"), plan.missing)
        assertEquals(0, plan.newPackages)
        assertNull(plan.downloadBytes)
        // apt never reached its summary, which is what stops this being read
        // as "already installed".
        assertFalse(plan.hasSummary)
    }

    @Test
    fun noticesWhenThereIsNothingToInstall() {
        val plan = LanguageServers.parsePlan(ALREADY_INSTALLED)
        assertEquals(0, plan.newPackages)
        assertTrue(plan.missing.isEmpty())
        assertNull(plan.downloadBytes)
        // The summary *is* there, saying zero — the difference between "apt
        // has nothing to do" and "apt never ran".
        assertTrue(plan.hasSummary)
    }

    @Test
    fun tellsAnAptThatSaidNothingFromAnAptWithNothingToDo() {
        // proot could not start, apt-get is not in the rootfs, sources.list is
        // empty: no summary line at all.
        val silent = LanguageServers.parsePlan("proot error: '/usr/bin/apt-get': No such file")
        assertFalse(silent.hasSummary)
        assertEquals(0, silent.newPackages)
    }

    @Test
    fun spellsSizesTheWayAptDoes() {
        assertEquals("512 B", LanguageServers.formatBytes(512))
        assertEquals("1.0 kB", LanguageServers.formatBytes(1_000))
        assertEquals("857 kB", LanguageServers.formatBytes(857_000))
        assertEquals("12.4 MB", LanguageServers.formatBytes(12_400_000))
        assertEquals("140 MB", LanguageServers.formatBytes(140_000_000))
        assertEquals("1.2 GB", LanguageServers.formatBytes(1_200_000_000))
        assertNull(LanguageServers.formatBytes(-1))
    }

    // --- the sentences -------------------------------------------------------

    @Test
    fun asksTheQuestionWithThePriceInIt() {
        val python = LanguageServers.forGrammar("python")!!
        assertEquals(
            "Python needs a language server — install python3-pylsp and " +
                "python3-pyflakes (~12.4 MB)?",
            LanguageServers.question(python, LanguageServers.parsePlan(PYLSP_ESTIMATE)),
        )
    }

    @Test
    fun asksWithoutAPriceRatherThanInventingOne() {
        val rust = LanguageServers.forGrammar("rust")!!
        // Both packages are named: rust-analyzer builds its crate graph by
        // running `cargo metadata`, and without cargo it starts, looks
        // healthy and answers nothing — proved on the emulator.
        assertEquals(
            "Rust needs a language server — install rust-analyzer and cargo?",
            LanguageServers.question(rust, null),
        )
        val blind = LanguageServers.parsePlan("E: Unable to locate package rust-analyzer\n")
        assertEquals(
            "Rust needs a language server — install rust-analyzer and cargo?",
            LanguageServers.question(rust, blind),
        )
        assertTrue(
            LanguageServers.detail(rust, blind, "Debian")
                .contains("has not downloaded its package lists yet")
        )
    }

    @Test
    fun explainsWhyPythonTakesTwoPackages() {
        val python = LanguageServers.forGrammar("python")!!
        val detail = LanguageServers.detail(python, LanguageServers.parsePlan(PYLSP_ESTIMATE), "Debian")
        assertTrue(detail.contains("pyflakes"))
        // apt spells this one "44.0 MB" — one decimal below a hundred.
        assertTrue(detail.contains("44.0 MB"))
        assertTrue(detail.contains("Debian"))
    }

    @Test
    fun turnsAptsFailuresIntoSomethingActionable() {
        val go = LanguageServers.forGrammar("go")!!
        assertEquals(
            "Could not reach the Debian archive",
            LanguageServers.explainInstall(
                "Err:1 http://deb.debian.org/debian stable/main arm64 gopls\n" +
                    "  Temporary failure resolving 'deb.debian.org'",
                go,
            ),
        )
        // The sentence names the whole package list — since golang-go rode
        // along (gopls loads the workspace with `go list`), "could not find"
        // names both, exactly as the install prompt does.
        assertEquals(
            "Debian could not find gopls and golang-go",
            LanguageServers.explainInstall("E: Unable to locate package gopls", go),
        )
        assertEquals(
            "There is not enough room left on the device",
            LanguageServers.explainInstall(
                "dpkg: unrecoverable fatal error: No space left on device",
                go,
            ),
        )
        assertEquals(
            "Another apt is already running in the userland",
            LanguageServers.explainInstall(
                "E: Could not get lock /var/lib/dpkg/lock-frontend",
                go,
            ),
        )
        // Anything else keeps apt's own words underneath and says the honest
        // little it knows.
        assertEquals(
            "Could not install gopls and golang-go",
            LanguageServers.explainInstall("dpkg: error processing archive", go),
        )
    }
}

/**
 * `apt-get install --assume-no --no-install-recommends python3-pylsp
 * python3-pyflakes` as it arrives: the statistics, then apt answering its own
 * question and aborting without fetching anything.
 */
private val PYLSP_ESTIMATE = """
    Reading package lists...
    Building dependency tree...
    Reading state information...
    The following additional packages will be installed:
      python3-docstring-to-markdown python3-jedi python3-lsp-jsonrpc python3-parso
    The following NEW packages will be installed:
      python3-docstring-to-markdown python3-jedi python3-lsp-jsonrpc python3-parso
      python3-pyflakes python3-pylsp
    0 upgraded, 5 newly installed, 0 to remove and 0 not upgraded.
    Need to get 12.4 MB of archives.
    After this operation, 44.0 MB of additional disk space will be used.
    Do you want to continue? [Y/n] N
    Abort.
""".trimIndent()

/**
 * The same install under `apt-get install -s`, which is what the estimate does
 * *not* use: every line about what would happen, and not one about what it
 * costs (apt 3.0.3 private-install.cc:364-376, returning above the sizes).
 */
private val SIMULATION_ONLY = """
    NOTE: This is only a simulation!
          apt-get needs root privileges for real execution.
    Reading package lists...
    Building dependency tree...
    The following NEW packages will be installed:
      gopls
    0 upgraded, 1 newly installed, 0 to remove and 0 not upgraded.
    Inst gopls (2:0.16.1+ds-1 Debian:stable [arm64])
    Conf gopls (2:0.16.1+ds-1 Debian:stable [arm64])
""".trimIndent()

/** apt with nothing to do: the case that must not become another download. */
private val ALREADY_INSTALLED = """
    Reading package lists...
    Building dependency tree...
    Reading state information...
    clangd is already the newest version (1:19.0-63).
    0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.
""".trimIndent()
