package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a project-panel row says out loud. Everything it carries besides the
 * name is a colour or a glyph, and none of that survives being read aloud —
 * this is the sentence that replaces it.
 */
class ProjectRowDescriptionTest {

    private fun describe(
        name: String = "main.rs",
        isDir: Boolean = false,
        isExpanded: Boolean = false,
        status: GitFileStatus = GitFileStatus.None,
        isOpen: Boolean = false,
        isMarked: Boolean = false,
        diagnostic: DiagnosticMark? = null,
        depth: Int = 0,
    ) = projectRowDescription(
        name, isDir, isExpanded, status, isOpen, isMarked, diagnostic, depth,
    )

    @Test
    fun aPlainFileIsItsNameAndThatItIsAFile() {
        assertEquals("main.rs, file", describe())
    }

    @Test
    fun aFolderSaysWhetherItIsOpen() {
        assertEquals("src, folder, collapsed", describe(name = "src", isDir = true))
        assertEquals(
            "src, folder, expanded",
            describe(name = "src", isDir = true, isExpanded = true),
        )
    }

    @Test
    fun theGitTintBecomesAWord() {
        assertEquals("main.rs, file, modified", describe(status = GitFileStatus.Modified))
        assertEquals("new.rs, file, untracked", describe(name = "new.rs", status = GitFileStatus.Untracked))
        assertEquals("a.rs, file, conflicted", describe(name = "a.rs", status = GitFileStatus.Conflicted))
        assertEquals("b.rs, file, ignored", describe(name = "b.rs", status = GitFileStatus.Ignored))
    }

    @Test
    fun theRestOfTheStatesFollowInTheOrderTheyAreLookedFor() {
        assertEquals(
            "main.rs, file, modified, has errors, open, selected, level 3",
            describe(
                status = GitFileStatus.Modified,
                isOpen = true,
                isMarked = true,
                diagnostic = DiagnosticMark.Error,
                depth = 2,
            ),
        )
        assertEquals(
            "lib.rs, file, has warnings",
            describe(name = "lib.rs", diagnostic = DiagnosticMark.Warning),
        )
    }

    @Test
    fun theRootRowSaysNothingAboutItsLevel() {
        // Depth 0 is the root, and "level 1" would be noise on every row of a
        // flat project.
        assertEquals("Cargo.toml, file", describe(name = "Cargo.toml", depth = 0))
        assertEquals("Cargo.toml, file, level 2", describe(name = "Cargo.toml", depth = 1))
    }
}
