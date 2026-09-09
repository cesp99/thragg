package to.eyed.thragg.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * proot's `--link2symlink` debris, and what has to survive a move.
 *
 * The shapes here are the ones taken off the device: the `qa_git.zip` a
 * 0.0.23 export produced held `.git/objects/2b/.l2s.tmp_obj_srq4HD0001.0001`
 * and no `objects/2b/<sha>` at all, because the object name was a symlink
 * whose text was an absolute host path (QA 0.0.23, G-17/G-24 findings). A
 * rename left every one of those dangling and git stopped being able to read
 * the repository.
 */
class GuestLinksTest {

    @get:Rule val temp = TemporaryFolder()

    /** The device's shape: one hidden file, one object name pointing at it. */
    private fun debrisPair(dir: File, name: String, debrisName: String, bytes: String): File {
        dir.mkdirs()
        val debris = File(dir, debrisName)
        debris.writeText(bytes)
        val link = File(dir, name)
        Files.createSymbolicLink(link.toPath(), debris.toPath().toAbsolutePath())
        return link
    }

    @Test
    fun `a git object written under proot becomes a real file again`() {
        val project = temp.newFolder("qa_git")
        val objects = File(project, ".git/objects/2b")
        val link = debrisPair(objects, "abc123", ".l2s.tmp_obj_srq4HD0001.0001", "object bytes")

        val repair = GuestLinks.repair(project)

        assertEquals(1, repair.repaired)
        assertTrue(repair.isClean)
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("object bytes", link.readText())
        // The stepping stone goes: it was never a file anyone asked for.
        assertFalse(File(objects, ".l2s.tmp_obj_srq4HD0001.0001").exists())
    }

    /** The whole point: what is repaired still reads after the directory moves. */
    @Test
    fun `a repaired project survives being renamed`() {
        val project = temp.newFolder("qa_git")
        debrisPair(File(project, ".git/objects/2b"), "abc123", ".l2s.tmp_obj_a0001.0001", "hello")

        GuestLinks.repair(project)
        val moved = File(project.parentFile, "qa_git2")
        assertTrue(project.renameTo(moved))

        assertEquals("hello", File(moved, ".git/objects/2b/abc123").readText())
    }

    /** Without the repair, the same move is what destroyed the repository. */
    @Test
    fun `an unrepaired project does not`() {
        val project = temp.newFolder("qa_git")
        debrisPair(File(project, ".git/objects/2b"), "abc123", ".l2s.tmp_obj_a0001.0001", "hello")

        val moved = File(project.parentFile, "qa_git2")
        assertTrue(project.renameTo(moved))

        val object2b = File(moved, ".git/objects/2b/abc123")
        assertTrue(Files.isSymbolicLink(object2b.toPath()))
        assertFalse(object2b.exists()) // dangling: `exists` follows the link
    }

    /** A real hard link has two names; both must end up with the bytes. */
    @Test
    fun `two names for one hidden file both come back`() {
        val dir = temp.newFolder("proj")
        val debris = File(dir, ".l2s.uplift0002.0002")
        debris.writeText("elf")
        val first = File(dir, "deps.so")
        val second = File(dir, "deploy.so")
        Files.createSymbolicLink(first.toPath(), debris.toPath().toAbsolutePath())
        Files.createSymbolicLink(second.toPath(), debris.toPath().toAbsolutePath())

        val repair = GuestLinks.repair(dir)

        assertEquals(2, repair.repaired)
        assertEquals("elf", first.readText())
        assertEquals("elf", second.readText())
        assertFalse(Files.isSymbolicLink(second.toPath()))
    }

    /** A link the user or a package made is not this function's business. */
    @Test
    fun `an ordinary symlink is left exactly as it was`() {
        val dir = temp.newFolder("proj")
        val real = File(dir, "real.txt").apply { writeText("mine") }
        val relative = File(dir, "near")
        Files.createSymbolicLink(relative.toPath(), File("real.txt").toPath())
        val outside = temp.newFile("elsewhere.txt").apply { writeText("theirs") }
        val absolute = File(dir, "far")
        Files.createSymbolicLink(absolute.toPath(), outside.toPath().toAbsolutePath())

        val repair = GuestLinks.repair(dir)

        assertEquals(0, repair.repaired)
        assertTrue(repair.isClean)
        assertTrue(Files.isSymbolicLink(relative.toPath()))
        assertTrue(Files.isSymbolicLink(absolute.toPath()))
        assertEquals("mine", real.readText())
        assertEquals("theirs", outside.readText())
    }

    /** Idempotent: the second pass over a healthy tree changes nothing. */
    @Test
    fun `repairing twice is repairing once`() {
        val project = temp.newFolder("qa_git")
        val link = debrisPair(File(project, ".git/objects/2b"), "abc", ".l2s.t0001.0001", "bytes")

        assertEquals(1, GuestLinks.repair(project).repaired)
        assertEquals(0, GuestLinks.repair(project).repaired)
        assertEquals("bytes", link.readText())
    }

    /**
     * A project moved by an older build: the bytes are gone with the old path,
     * and an export has to say so rather than ship a `.git` git will refuse.
     */
    @Test
    fun `debris that no longer exists is reported and not invented`() {
        val project = temp.newFolder("qa_git2")
        val objects = File(project, ".git/objects/2b").apply { mkdirs() }
        val link = File(objects, "abc123")
        Files.createSymbolicLink(
            link.toPath(),
            File(project.parentFile, "qa_git/.git/objects/2b/.l2s.tmp_obj_x0001.0001").toPath(),
        )

        val repair = GuestLinks.repair(project)

        assertEquals(0, repair.repaired)
        // The path it names is inside the *old* project, so it is not
        // "inside root" and repair leaves it alone rather than guessing.
        assertTrue(GuestLinks.hasDanglingDebris(File(project, ".git")))
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    /** A clean repository has nothing for the export to warn about. */
    @Test
    fun `a healthy git directory is not reported as dangling`() {
        val project = temp.newFolder("clean")
        File(project, ".git/objects/2b").mkdirs()
        File(project, ".git/objects/2b/abc").writeText("object")

        assertFalse(GuestLinks.hasDanglingDebris(File(project, ".git")))
        assertFalse(GuestLinks.hasDanglingDebris(File(project, ".git-that-is-not-there")))
    }

    /**
     * The walk never follows a link out of the tree — the trap
     * [SafeDelete] exists for, met again here.
     */
    @Test
    fun `a symlinked directory is not walked into`() {
        val project = temp.newFolder("proj")
        val outside = temp.newFolder("outside")
        val victim = debrisPair(outside, "target", ".l2s.other0001.0001", "not ours")
        Files.createSymbolicLink(File(project, "link").toPath(), outside.toPath().toAbsolutePath())

        val repair = GuestLinks.repair(project)

        assertEquals(0, repair.repaired)
        assertTrue(Files.isSymbolicLink(victim.toPath()))
        assertTrue(File(outside, ".l2s.other0001.0001").isFile)
    }
}
