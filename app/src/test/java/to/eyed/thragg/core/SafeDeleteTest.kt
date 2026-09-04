package to.eyed.thragg.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The property that matters: removing the Linux userland must never reach
 * outside it. Run this against `File.deleteRecursively()` instead and
 * [symlinkedDirectoryIsUnlinkedNotFollowed] fails by destroying the target —
 * which is the bug this class exists to prevent.
 */
class SafeDeleteTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun deletesAnOrdinaryTree() {
        val root = temp.newFolder("rootfs")
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/git").writeText("not really git")
        File(root, "etc").mkdirs()
        File(root, "etc/hostname").writeText("thragg")

        assertTrue(SafeDelete.deleteTree(root))
        assertFalse(root.exists())
    }

    @Test
    fun symlinkedDirectoryIsUnlinkedNotFollowed() {
        val projects = temp.newFolder("projects")
        val work = File(projects, "my-app")
        work.mkdirs()
        val source = File(work, "main.rs")
        source.writeText("fn main() {}")

        val root = temp.newFolder("rootfs")
        File(root, "root").mkdirs()
        Files.createSymbolicLink(File(root, "root/p").toPath(), projects.toPath())

        assertTrue(SafeDelete.deleteTree(root))

        assertFalse("the rootfs is gone", root.exists())
        assertTrue("the link's target survived", projects.isDirectory)
        assertTrue("the user's work survived", source.isFile)
        assertTrue("the file still has its contents", source.readText().isNotEmpty())
    }

    @Test
    fun brokenSymlinkIsRemoved() {
        val root = temp.newFolder("rootfs")
        Files.createSymbolicLink(
            File(root, "dangling").toPath(),
            File(temp.root, "was-never-there").toPath(),
        )

        assertTrue(SafeDelete.deleteTree(root))
        assertFalse(root.exists())
    }

    @Test
    fun symlinkToAFileLeavesTheFile() {
        val keep = temp.newFile("keep.txt")
        keep.writeText("keep me")

        val root = temp.newFolder("rootfs")
        Files.createSymbolicLink(File(root, "link.txt").toPath(), keep.toPath())

        assertTrue(SafeDelete.deleteTree(root))
        assertTrue(keep.isFile)
        assertTrue(keep.readText() == "keep me")
    }

    @Test
    fun missingDirectoryIsNotAFailure() {
        assertTrue(SafeDelete.deleteTree(File(temp.root, "never-existed")))
    }
}
