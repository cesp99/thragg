package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.AskpassKind
import to.eyed.seeker.code.core.AskpassPrompt
import to.eyed.seeker.code.core.AskpassSetup
import to.eyed.seeker.code.terminal.GitClone

/**
 * The pure half of the credential dialog: what the engine's prompt JSON
 * becomes, what the dialog is titled, what the field starts with, and what
 * the clone's environment carries. The round trip itself — pipe, script,
 * answer — is an engine test (askpass.rs).
 */
class AskpassTest {

    private fun prompt(
        kind: String,
        prompt: String = "Password for 'https://github.com': ",
        subject: String = "https://github.com",
        masked: Boolean = true,
        suggestion: String? = null,
    ): AskpassPrompt = AskpassPrompt.parse(
        """{"id":7,"prompt":${quoted(prompt)},"kind":"$kind","subject":"$subject","masked":$masked,""" +
            """"suggestion":${suggestion?.let { quoted(it) } ?: "null"}}""",
        operation = "git push origin",
    )!!

    private fun quoted(text: String) = "\"" + text.replace("\n", "\\n") + "\""

    @Test
    fun aPromptParsesWithItsKindAndMasking() {
        val password = prompt("password")
        assertEquals(7L, password.id)
        assertEquals("git push origin", password.operation)
        assertEquals(AskpassKind.Password, password.kind)
        assertTrue(password.masked)
        assertTrue(password.rememberable)
        assertEquals("", password.initialText)
        assertNull(password.suggestion)

        val username = prompt("username", prompt = "Username for 'https://github.com': ", masked = false)
        assertEquals(AskpassKind.Username, username.kind)
        assertFalse(username.masked)
        // The username is kept for its host without asking; no checkbox.
        assertFalse(username.rememberable)

        val passphrase = prompt(
            "passphrase",
            prompt = "Enter passphrase for key '/root/.ssh/id_ed25519': ",
            subject = "/root/.ssh/id_ed25519",
        )
        assertEquals(AskpassKind.Passphrase, passphrase.kind)
        assertTrue(passphrase.rememberable)

        // An unknown kind is "other": masked, like Zed masks anything it
        // does not recognise.
        assertEquals(AskpassKind.Other, prompt("whatever").kind)
    }

    @Test
    fun aHostKeyQuestionStartsAsYesAndIsNeverRemembered() {
        val hostKey = prompt(
            "host_key",
            prompt = "The authenticity of host 'github.com (1.2.3.4)' can't be established.\n" +
                "Are you sure you want to continue connecting (yes/no/[fingerprint])? ",
            subject = "github.com (1.2.3.4)",
            masked = false,
        )
        assertEquals(AskpassKind.HostKey, hostKey.kind)
        assertEquals("yes", hostKey.initialText)
        assertFalse(hostKey.rememberable)
        assertEquals("Yes", confirmLabel(AskpassKind.HostKey))
        assertEquals("OK", confirmLabel(AskpassKind.Password))
        assertTrue(hostKey.prompt.contains("\n"))
    }

    @Test
    fun aSuggestedUsernamePreFillsTheField() {
        val corrected = prompt(
            "username",
            prompt = "Username for 'https://github.com': ",
            masked = false,
            suggestion = "cesp99",
        )
        assertEquals("cesp99", corrected.initialText)
    }

    @Test
    fun malformedOrIdlessJsonIsNoPrompt() {
        assertNull(AskpassPrompt.parse("not json", "git fetch"))
        assertNull(AskpassPrompt.parse("""{"prompt":"x"}""", "git fetch"))
    }

    @Test
    fun theDialogIsTitledAsZedTitlesItsModal() {
        assertEquals("git fetch", askpassTitle(RemoteAction.Fetch(null)))
        assertEquals("git fetch", askpassTitle(RemoteAction.Fetch("origin")))
        assertEquals("git pull origin", askpassTitle(RemoteAction.Pull("origin")))
        assertEquals("git push fork", askpassTitle(RemoteAction.Push("main", "fork")))
    }

    @Test
    fun theRememberLabelNamesTheSecret() {
        assertEquals("Remember this passphrase for this session", rememberLabel(AskpassKind.Passphrase))
        assertEquals("Remember this password for this session", rememberLabel(AskpassKind.Password))
    }

    @Test
    fun theCloneEnvironmentCarriesTheHelperAndNothingThatSilencesIt() {
        val setup = AskpassSetup.parse(
            """{"env":["GIT_ASKPASS=/tmp/seeker-askpass-1/askpass.sh",""" +
                """"SSH_ASKPASS=/tmp/seeker-askpass-1/askpass.sh","SSH_ASKPASS_REQUIRE=force"],""" +
                """"args":["-c","credential.helper=cache --timeout=3600"]}""",
        )
        val environment = GitClone.gitEnvironment(setup)
        assertTrue("GIT_TERMINAL_PROMPT=0" in environment)
        assertTrue("GIT_ASKPASS=/tmp/seeker-askpass-1/askpass.sh" in environment)
        assertTrue("SSH_ASKPASS_REQUIRE=force" in environment)
        // BatchMode would silence the passphrase prompt the helper carries.
        assertTrue(environment.none { "BatchMode" in it })
        assertEquals(listOf("-c", "credential.helper=cache --timeout=3600"), setup.gitArgs)

        // No userland: no helper, and the clone fails on a private remote
        // as it always did rather than hanging.
        val none = AskpassSetup.parse("""{"env":[],"args":[]}""")
        assertEquals(listOf("GIT_TERMINAL_PROMPT=0", "COLUMNS=80"), GitClone.gitEnvironment(none))
        assertEquals(AskpassSetup.NONE, AskpassSetup.parse("garbage"))
    }
}
