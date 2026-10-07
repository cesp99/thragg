package to.eyed.thragg.solana.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import to.eyed.thragg.solana.build.BuildTasks
import java.io.File

/**
 * The login profile is what puts the toolchain back on `PATH` after Debian's
 * `/etc/profile` resets it for root — the reason the agent's `bash -lc` tool
 * commands could not find `anchor`. These run the shipped text through a real
 * `/bin/sh`, starting from exactly the `PATH` that reset leaves behind.
 */
class LoginProfileTest {

    private fun source(profile: File, env: Map<String, String> = emptyMap()): String {
        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            "PATH=${SolanaToolchain.GUEST_BASE_PATH}; . \"$1\"; . \"$1\"; printf %s \"\$PATH\"",
            "sh",
            profile.absolutePath,
        ).apply {
            environment().remove("VIRTUAL_ENV")
            environment().putAll(env)
        }.start()
        val out = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        return out
    }

    private fun written(): File =
        File.createTempFile("thragg-toolchain", ".sh").apply {
            deleteOnExit()
            writeText(SolanaToolchain.loginProfile())
        }

    @Test
    fun theProfileLeadsPathWithThePrefixOnce() {
        assumeTrue(File("/bin/sh").canExecute())
        assertEquals(
            "${SolanaToolchain.GUEST_PATH_PREFIX}:${SolanaToolchain.GUEST_BASE_PATH}",
            source(written()),
        )
    }

    @Test
    fun aVirtualEnvLeadsTheToolchainOnce() {
        assumeTrue(File("/bin/sh").canExecute())
        assertEquals(
            "/projects/p/.venv/bin:${SolanaToolchain.GUEST_PATH_PREFIX}:" +
                SolanaToolchain.GUEST_BASE_PATH,
            source(written(), mapOf("VIRTUAL_ENV" to "/projects/p/.venv")),
        )
    }

    @Test
    fun theProfileIsDerivedFromThePrefix() {
        val profile = SolanaToolchain.loginProfile()
        assertTrue(profile.contains(SolanaToolchain.GUEST_PATH_PREFIX))
        assertTrue(profile.trimEnd().endsWith("export PATH"))
    }

    /** `/etc/profile` sources `profile.d` through run-parts' name rule. */
    @Test
    fun theFileIsOneEtcProfileSources() {
        val path = SolanaToolchain.LOGIN_PROFILE_PATH
        assertTrue(path.startsWith("etc/profile.d/"))
        assertTrue(
            Regex("^[a-zA-Z0-9_][a-zA-Z0-9._-]*\\.sh$").matches(path.substringAfterLast('/')),
        )
    }

    /** The agent's PATH is the Build tab's, in the Build tab's order. */
    @Test
    fun thePrefixIsTheBuildTabsPath() {
        val build = BuildTasks.guestEnvironment()
            .single { it.startsWith("PATH=") }
            .removePrefix("PATH=")
        assertEquals(
            "${SolanaToolchain.GUEST_PATH_PREFIX}:${SolanaToolchain.GUEST_BASE_PATH}",
            build,
        )
    }
}
