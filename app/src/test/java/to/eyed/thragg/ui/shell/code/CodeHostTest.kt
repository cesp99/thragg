package to.eyed.thragg.ui.shell.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.LanguageSettings
import to.eyed.thragg.ui.editor.SoftWrapMode

/**
 * The Code host's two pure rules.
 *
 * The rest of CodeScreen.kt is a composable over an engine and is verified on
 * the phone (see /tmp/qa/fix-batch2.md for the steps); these two are values,
 * and both were a device finding in the 0.0.22 pass.
 */
class CodeHostTest {

    /**
     * G-06. Settings used to carry a "Wrap long lines" switch; it wrote
     * `"soft_wrap": "none"` into the file, the file really said so, and every
     * long line went on wrapping — because this function is what the pane is
     * actually given. The switch is gone and this is why.
     */
    @Test
    fun everySettingWrapsOnThisPhone() {
        for (mode in SoftWrapMode.entries) {
            val wrapped = LanguageSettings(softWrap = mode).wrappedForAPhone()
            assertTrue("$mode must still wrap", wrapped.softWrap.wraps)
        }
    }

    /** A file that already wraps keeps the mode it asked for. */
    @Test
    fun aModeThatAlreadyWrapsIsLeftAlone() {
        val bounded = LanguageSettings(softWrap = SoftWrapMode.Bounded)
        assertEquals(SoftWrapMode.Bounded, bounded.wrappedForAPhone().softWrap)
        val none = LanguageSettings(softWrap = SoftWrapMode.None)
        assertEquals(SoftWrapMode.EditorWidth, none.wrappedForAPhone().softWrap)
    }
}
