package to.eyed.seeker.code.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two parts of cloning that are pure logic, and both of which have an
 * obvious wrong implementation:
 *
 * - the project name, where "split on `/` and take the last" is wrong for
 *   `git@host:owner/repo.git`, for a trailing slash, and for `.git` itself;
 * - the progress stream, where "read a line" is wrong because git separates
 *   its updates with carriage returns and only ends a *phase* with a newline.
 *   Run [progressArrivesThroughoutADownload] against a line reader and it
 *   fails the way the UI did: nothing, then 100%.
 */
class GitCloneTest {

    // --- URL → project name --------------------------------------------------

    @Test
    fun derivesNameFromAnHttpsUrl() {
        assertEquals("seeker", GitCloneUrl.projectName("https://github.com/cesp99/seeker"))
        assertEquals("seeker", GitCloneUrl.projectName("https://github.com/cesp99/seeker.git"))
        assertEquals("seeker", GitCloneUrl.projectName("http://example.com/git/seeker.git"))
    }

    @Test
    fun derivesNameFromAnSshUrl() {
        // scp-style: no scheme, and a colon where a slash would be.
        assertEquals("seeker", GitCloneUrl.projectName("git@github.com:eyed/seeker.git"))
        assertEquals("seeker", GitCloneUrl.projectName("git@github.com:eyed/seeker"))
        assertEquals("seeker", GitCloneUrl.projectName("ssh://git@github.com/cesp99/seeker.git"))
        assertEquals("seeker", GitCloneUrl.projectName("ssh://git@host:2222/eyed/seeker.git/"))
    }

    @Test
    fun ignoresTrailingSlashesAndQueryStrings() {
        assertEquals("seeker", GitCloneUrl.projectName("https://github.com/cesp99/seeker/"))
        assertEquals("seeker", GitCloneUrl.projectName("https://github.com/cesp99/seeker.git/"))
        assertEquals("seeker", GitCloneUrl.projectName("  https://github.com/cesp99/seeker  "))
        assertEquals("seeker", GitCloneUrl.projectName("https://github.com/cesp99/seeker.git#main"))
    }

    @Test
    fun acceptsAPlainLocalPath() {
        assertEquals("seeker", GitCloneUrl.projectName("/srv/git/seeker.git"))
        assertEquals("seeker", GitCloneUrl.projectName("seeker"))
    }

    @Test
    fun refusesToInventANameFromNonsense() {
        // Nothing left after the host, so there is no repository component.
        assertNull(GitCloneUrl.projectName(""))
        assertNull(GitCloneUrl.projectName("   "))
        assertNull(GitCloneUrl.projectName("https://"))
        assertNull(GitCloneUrl.projectName("https://github.com/"))
        assertNull(GitCloneUrl.projectName("/"))
        // A project called ".git" or "." would be a hidden directory, or a
        // name ProjectsRoot rejects anyway. Better to suggest nothing.
        assertNull(GitCloneUrl.projectName(".git"))
        assertNull(GitCloneUrl.projectName("https://github.com/cesp99/.git"))
        assertNull(GitCloneUrl.projectName("../"))
    }

    // --- \r-delimited progress ----------------------------------------------

    @Test
    fun splitsRecordsOnCarriageReturnsAsWellAsNewlines() {
        val reader = GitProgressReader()
        val records = reader.feed("Receiving objects:   1% (1/60)\rReceiving objects:   2% (2/60)\r")
        assertEquals(
            listOf("Receiving objects:   1% (1/60)", "Receiving objects:   2% (2/60)"),
            records,
        )
        // \r\n is one separator's worth of nothing, not an empty record.
        assertEquals(listOf("done."), reader.feed("done.\r\n"))
    }

    /**
     * The Build log's half of the story: a `\r`-terminated record is a
     * progress line being redrawn (cargo-build-sbf's tools download did it
     * for 27 minutes on the device), a `\n`-terminated one is a real line,
     * and a `\r\n` pair still yields exactly one record — the `\r` closes it
     * (tagged as a redraw, which a throttle's drain still delivers) and the
     * `\n` closes an empty record that is dropped as it always was.
     */
    @Test
    fun tagsCarriageRedrawsSoABuildLogCanThrottleThem() {
        val reader = GitProgressReader()
        assertEquals(
            listOf(
                GitProgressReader.Record("12.5 / 450.0 MB", carriage = true),
                GitProgressReader.Record("13.1 / 450.0 MB", carriage = true),
                GitProgressReader.Record("done.", carriage = true),
                GitProgressReader.Record("Compiling hello v0.1.0", carriage = false),
            ),
            reader.feedRecords(
                "12.5 / 450.0 MB\r13.1 / 450.0 MB\rdone.\r\nCompiling hello v0.1.0\n"
            ),
        )
        // A separator split across two reads still completes exactly once.
        assertTrue(reader.feedRecords("halfway").isEmpty())
        assertEquals(
            listOf(GitProgressReader.Record("halfway there", carriage = true)),
            reader.feedRecords(" there\r"),
        )
        assertEquals(emptyList<String>(), reader.flush())
    }

    @Test
    fun holdsAPartialRecordUntilItsSeparatorArrives() {
        val reader = GitProgressReader()
        assertTrue(reader.feed("Receiving objects:  4").isEmpty())
        assertEquals(listOf("Receiving objects:  43% (26/60)"), reader.feed("3% (26/60)\r"))
        // An unterminated tail is only reported when the stream ends.
        assertTrue(reader.feed("fatal: early EOF").isEmpty())
        assertEquals(listOf("fatal: early EOF"), reader.flush())
        assertTrue(reader.flush().isEmpty())
    }

    @Test
    fun readsPhasesAndPercentages() {
        assertEquals(
            CloneProgress("Receiving objects", 0.43f),
            GitProgress.parse("Receiving objects:  43% (26/60), 1.20 MiB | 600.00 KiB/s"),
        )
        assertEquals(
            CloneProgress("Counting objects", 1f),
            GitProgress.parse("remote: Counting objects: 100% (40/40), done."),
        )
        assertEquals(
            CloneProgress("Enumerating objects", null),
            GitProgress.parse("remote: Enumerating objects: 40, done."),
        )
        assertEquals(CloneProgress("Cloning", null), GitProgress.parse("Cloning into 'seeker'..."))
    }

    @Test
    fun leavesGitsOwnDiagnosticsAlone() {
        assertNull(GitProgress.parse("fatal: repository 'https://example.com/x.git/' not found"))
        assertNull(GitProgress.parse("remote: Support for password authentication was removed."))
        assertNull(GitProgress.parse("warning: You appear to have cloned an empty repository."))
        assertNull(GitProgress.parse(""))
    }

    /**
     * The point of the whole exercise: feed a captured transcript in chunks
     * that fall wherever a pipe would put them, and check that the fraction
     * moves the whole way down rather than appearing at the end.
     */
    @Test
    fun progressArrivesThroughoutADownload() {
        val reader = GitProgressReader()
        val seen = mutableListOf<CloneProgress>()
        // 7 is deliberately coprime with everything in the transcript: the
        // splits land mid-record, mid-percentage and mid-separator.
        for (chunk in TRANSCRIPT.chunked(7)) {
            for (record in reader.feed(chunk)) {
                GitProgress.parse(record)?.let { seen += it }
            }
        }
        for (record in reader.flush()) GitProgress.parse(record)?.let { seen += it }

        assertEquals("Cloning", seen.first().phase)

        val receiving = seen.filter { it.phase == "Receiving objects" }.mapNotNull { it.fraction }
        // Not one jump from nothing to done: the real transcript has eight
        // receiving updates, and every one of them must have been surfaced.
        assertEquals(8, receiving.size)
        assertEquals(0.03f, receiving.first(), 0.001f)
        assertEquals(1f, receiving.last(), 0.001f)
        assertEquals(receiving.sorted(), receiving)

        assertEquals(
            listOf(
                "Cloning",
                "Enumerating objects",
                "Counting objects",
                "Compressing objects",
                "Receiving objects",
                "Resolving deltas",
            ),
            seen.map { it.phase }.distinct(),
        )
        assertEquals(CloneProgress("Resolving deltas", 1f), seen.last())
    }
}

/**
 * `git clone --progress https://…` as it actually arrives: carriage returns
 * within a phase, a newline only when the phase is finished, and the counting
 * phases prefixed by `remote:` because they happen on the server.
 */
private val TRANSCRIPT = buildString {
    append("Cloning into 'seeker'...\n")
    append("remote: Enumerating objects: 60, done.\r\n")
    append("remote: Counting objects:   1% (1/60)\r")
    append("remote: Counting objects:  50% (30/60)\r")
    append("remote: Counting objects: 100% (60/60)\r")
    append("remote: Counting objects: 100% (60/60), done.\r\n")
    append("remote: Compressing objects:  25% (10/40)\r")
    append("remote: Compressing objects: 100% (40/40)\r")
    append("remote: Compressing objects: 100% (40/40), done.\r\n")
    append("Receiving objects:   3% (2/60)\r")
    append("Receiving objects:  10% (6/60)\r")
    append("Receiving objects:  25% (15/60), 620.00 KiB | 310.00 KiB/s\r")
    append("Receiving objects:  43% (26/60), 1.20 MiB | 600.00 KiB/s\r")
    append("Receiving objects:  68% (41/60), 2.80 MiB | 700.00 KiB/s\r")
    append("Receiving objects:  91% (55/60), 3.90 MiB | 780.00 KiB/s\r")
    append("Receiving objects: 100% (60/60), 4.20 MiB | 820.00 KiB/s\r")
    append("Receiving objects: 100% (60/60), 4.20 MiB | 820.00 KiB/s, done.\n")
    append("Resolving deltas:  20% (4/20)\r")
    append("Resolving deltas: 100% (20/20)\r")
    append("Resolving deltas: 100% (20/20), done.\n")
}
