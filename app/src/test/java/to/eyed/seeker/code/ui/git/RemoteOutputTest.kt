package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.seeker.code.core.RemoteOpResult

/**
 * The toast rules are Zed's `remote_output::format_output`, rule for rule,
 * and these are Zed's own test outputs where it has them
 * (crates/git_ui/src/remote_output.rs:188-360) — real git transcripts, which
 * is the only thing a pile of string matches can be checked against.
 */
class RemoteOutputTest {

    private fun output(stdout: String = "", stderr: String = "") =
        RemoteOpResult(remote = null, stdout = stdout, stderr = stderr, error = null)

    // --- fetch ---------------------------------------------------------

    @Test
    fun aSilentFetchIsAlreadyUpToDate() {
        // A fetch reports on stderr; an empty one means nothing moved.
        val toast = formatRemoteOutput(RemoteAction.Fetch("origin"), output())
        assertEquals("Fetch: Already up to date", toast.message)
        assertEquals(RemoteToastStyle.Plain, toast.style)
    }

    @Test
    fun aFetchThatMovedNamesItsRemoteAndAFetchAllDoesNot() {
        val moved = output(stderr = "From github.com:cesp99/seeker\n   abc..def  main -> origin/main\n")
        val one = formatRemoteOutput(RemoteAction.Fetch("origin"), moved)
        assertEquals("Synchronized with origin", one.message)
        assertEquals(RemoteToastStyle.WithLog, one.style)

        val all = formatRemoteOutput(RemoteAction.Fetch(null), moved)
        assertEquals("Synchronized with remotes", all.message)
    }

    // --- pull ----------------------------------------------------------

    @Test
    fun aPullReadsItsStdoutNotItsStderr() {
        // The up-to-date sentence is stdout's; fetch progress on stderr must
        // not change the answer.
        val toast = formatRemoteOutput(
            RemoteAction.Pull("origin"),
            output(stdout = "Already up to date.\n", stderr = "From github.com:x/y\n"),
        )
        assertEquals("Pull: Already up to date", toast.message)
        assertEquals(RemoteToastStyle.Plain, toast.style)
    }

    @Test
    fun aFastForwardCountsItsFiles() {
        val toast = formatRemoteOutput(
            RemoteAction.Pull("origin"),
            output(
                stdout = "Updating abc123..def456\nFast-forward\n README | 2 +-\n" +
                    " 1 file changed, 1 insertion(+), 1 deletion(-)\n",
            ),
        )
        assertEquals("Received 1 file change from origin", toast.message)
        assertEquals(RemoteToastStyle.WithLog, toast.style)

        val many = formatRemoteOutput(
            RemoteAction.Pull("origin"),
            output(
                stdout = "Updating abc123..def456\nFast-forward\n" +
                    " 3 files changed, 9 insertions(+)\n",
            ),
        )
        assertEquals("Received 3 file changes from origin", many.message)
    }

    @Test
    fun anUncountablePullFallsBackWithoutInventingANumber() {
        val toast = formatRemoteOutput(
            RemoteAction.Pull("origin"),
            output(stdout = "Updating abc123..def456\nsomething unexpected\n"),
        )
        assertEquals("Fast forwarded from origin", toast.message)
    }

    @Test
    fun aMergeARebaseAndAnythingElseEachGetTheirSentence() {
        val merged = formatRemoteOutput(
            RemoteAction.Pull("origin"),
            output(stdout = "Merge made by the 'ort' strategy.\n 2 files changed, 4 insertions(+)\n"),
        )
        assertEquals("Merged 2 file changes from origin", merged.message)

        val rebased = formatRemoteOutput(
            RemoteAction.Pull("origin"),
            output(stdout = "Successfully rebased and updated refs/heads/main.\n"),
        )
        assertEquals("Successfully rebased from origin", rebased.message)

        val other = formatRemoteOutput(RemoteAction.Pull("origin"), output(stdout = "?\n"))
        assertEquals("Successfully pulled from origin", other.message)
    }

    // --- push ----------------------------------------------------------

    @Test
    fun aPushWithNothingToSendIsPlain() {
        val toast = formatRemoteOutput(
            RemoteAction.Push("main", "origin"),
            output(stderr = "Everything up-to-date\n"),
        )
        assertEquals("Push: Everything is up-to-date", toast.message)
        assertEquals(RemoteToastStyle.Plain, toast.style)
    }

    /** Zed's GitHub transcript: the hint line, then the URL line. */
    @Test
    fun aGithubPushOffersItsPullRequest() {
        val toast = formatRemoteOutput(
            RemoteAction.Push("test_branch", "test_remote"),
            output(
                stderr = """
                    Total 0 (delta 0), reused 0 (delta 0), pack-reused 0 (from 0)
                    remote:
                    remote: Create a pull request for 'test' on GitHub by visiting:
                    remote:      https://example.com/test/test/pull/new/test
                    remote:
                    To example.com:test/test.git
                     * [new branch]      test -> test
                """.trimIndent() + "\n",
            ),
        )
        assertEquals("Pushed test_branch to test_remote", toast.message)
        assertEquals(
            RemoteToastStyle.PullRequestLink(
                "Create Pull Request",
                "https://example.com/test/test/pull/new/test",
            ),
            toast.style,
        )
    }

    /** Zed's GitLab transcripts: the create hint, and the view-existing one. */
    @Test
    fun aGitlabPushOffersItsMergeRequest() {
        val create = formatRemoteOutput(
            RemoteAction.Push("test_branch", "test_remote"),
            output(
                stderr = """
                    remote:
                    remote: To create a merge request for test, visit:
                    remote:   https://example.com/test/test/-/merge_requests/new?merge_request%5Bsource_branch%5D=test
                    remote:
                """.trimIndent() + "\n",
            ),
        )
        assertEquals(
            RemoteToastStyle.PullRequestLink(
                "Create Merge Request",
                "https://example.com/test/test/-/merge_requests/new?merge_request%5Bsource_branch%5D=test",
            ),
            create.style,
        )

        // An unrelated URL outside the `remote:` lines — Zed's OpenSSH
        // warning case — must not be mistaken for the link.
        val view = formatRemoteOutput(
            RemoteAction.Push("test_branch", "test_remote"),
            output(
                stderr = """
                    ** The server may need to be upgraded. See https://openssh.com/pq.html
                    Total 0 (delta 0), reused 0 (delta 0), pack-reused 0 (from 0)
                    remote:
                    remote: View merge request for test:
                    remote:    https://example.com/test/test/-/merge_requests/99999
                    remote:
                    To example.com:test/test.git
                        + 80bd3c83be...e03d499d2e test -> test
                """.trimIndent() + "\n",
            ),
        )
        assertEquals(
            RemoteToastStyle.PullRequestLink(
                "View Merge Request",
                "https://example.com/test/test/-/merge_requests/99999",
            ),
            view.style,
        )
    }

    /** Zed's Bitbucket transcript, through the extractor alone. */
    @Test
    fun aBitbucketHintExtracts() {
        val link = extractPullRequestLink(
            """
                remote:
                remote: Create pull request for test:
                remote:   https://bitbucket.example.com/projects/TEST/repos/test/pull-requests?create&sourceBranch=refs/heads/test
            """.trimIndent() + "\n",
        )
        assertEquals(
            "Create Pull Request" to
                "https://bitbucket.example.com/projects/TEST/repos/test/pull-requests?create&sourceBranch=refs/heads/test",
            link,
        )
    }

    @Test
    fun aPushWithNoHintStillCarriesItsLog() {
        val toast = formatRemoteOutput(
            RemoteAction.Push("test_branch", "test_remote"),
            output(stderr = "To http://example.com/test/test.git\n * [new branch]      test -> test\n"),
        )
        assertEquals("Pushed test_branch to test_remote", toast.message)
        assertEquals(RemoteToastStyle.WithLog, toast.style)
    }

    @Test
    fun urlsShedTheirTrailingPunctuation() {
        assertEquals(
            "https://example.com/x",
            extractUrl("visit https://example.com/x, please"),
        )
        assertEquals("https://example.com/x", extractUrl("(https://example.com/x)"))
        assertNull(extractUrl("no url here"))
    }

    @Test
    fun theActionNamesMatchZeds() {
        assertEquals("fetch", RemoteAction.Fetch(null).name)
        assertEquals("pull", RemoteAction.Pull("origin").name)
        assertEquals("push", RemoteAction.Push("main", "origin").name)
    }

    // --- failures ------------------------------------------------------

    @Test
    fun aFailureCarriesGitsOwnWordsNotTheExitStatus() {
        // Real transcript of a fetch with no network: the fatal line is the
        // whole story, and "git exited with 2" was none of it.
        val failed = RemoteOpResult(
            stderr = "fatal: unable to access 'https://github.com/x/y/': " +
                "Could not resolve host: github.com\n",
            error = "git exited with 2",
        )
        assertEquals(
            "git fetch failed: fatal: unable to access 'https://github.com/x/y/': " +
                "Could not resolve host: github.com",
            remoteFailureMessage(RemoteAction.Fetch("origin"), failed),
        )
    }

    @Test
    fun theFatalLineOutranksTheProgressAboveIt() {
        // A push's stderr opens with counting/compressing progress; the strip
        // ellipsizes at three lines, so the reason must come first, alone.
        val failed = RemoteOpResult(
            stderr = "Enumerating objects: 5, done.\n" +
                "Counting objects: 100% (5/5), done.\n" +
                "error: failed to push some refs to 'github.com:x/y.git'\n",
            error = "git exited with 1",
        )
        assertEquals(
            "git push failed: error: failed to push some refs to 'github.com:x/y.git'",
            remoteFailureMessage(RemoteAction.Push("main", "origin"), failed),
        )
    }

    @Test
    fun aFailureWithNoTellingLineKeepsItsLastWordAndAnEmptyOneTheError() {
        // No fatal:/error: prefix — the last non-blank line is the best guess.
        val odd = RemoteOpResult(
            stderr = "Permission denied (publickey).\n",
            error = "git exited with 128",
        )
        assertEquals(
            "git pull failed: Permission denied (publickey).",
            remoteFailureMessage(RemoteAction.Pull("origin"), odd),
        )
        // Silent streams are an engine-level refusal: its error is everything.
        val silent = RemoteOpResult(error = "Could not run git in the Linux userland")
        assertEquals(
            "git fetch failed: Could not run git in the Linux userland",
            remoteFailureMessage(RemoteAction.Fetch(null), silent),
        )
    }
}
