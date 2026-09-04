package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine's replace-all summary, as the panel reads it: the counts, the
 * buffers the workspace has to resync, and the sentence under the query.
 */
class ProjectReplaceReceiptTest {

    @Test
    fun parsesEveryFieldTheEngineSends() {
        val receipt = ProjectReplaceReceipt.parse(
            """{"files":3,"replacements":12,"buffers":[4,9],"errors":["src/gone.rs: No such file"]}"""
        )
        assertEquals(3, receipt.files)
        assertEquals(12, receipt.replacements)
        assertEquals(listOf(4L, 9L), receipt.bufferIds)
        assertEquals(listOf("src/gone.rs: No such file"), receipt.errors)
    }

    @Test
    fun missingListsAreEmptyRatherThanACrash() {
        val receipt = ProjectReplaceReceipt.parse("""{"files":0,"replacements":0}""")
        assertEquals(emptyList<Long>(), receipt.bufferIds)
        assertEquals(emptyList<String>(), receipt.errors)
    }

    @Test
    fun theSummaryCountsInEnglish() {
        assertEquals(
            "Replaced 12 matches in 3 files",
            ProjectReplaceReceipt(files = 3, replacements = 12, bufferIds = emptyList(), errors = emptyList()).summary,
        )
        assertEquals(
            "Replaced 1 match in 1 file",
            ProjectReplaceReceipt(files = 1, replacements = 1, bufferIds = emptyList(), errors = emptyList()).summary,
        )
        assertEquals(
            "Replaced 0 matches in 0 files",
            ProjectReplaceReceipt(files = 0, replacements = 0, bufferIds = emptyList(), errors = emptyList()).summary,
        )
    }
}
