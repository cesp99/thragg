package to.eyed.thragg.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.Autosave

/**
 * What `settings.autosave` means to this app — G-05.
 *
 * Until 0.0.22 it meant nothing: Settings' "Autosave on leaving a file" switch
 * really wrote `"autosave": "off"` to the file, and the only reader of the key
 * in `app/src/main` was the row that wrote it, so leaving a file saved it
 * either way. These three answers are the whole contract, and CodeScreen's
 * three autosave doors are gated on them.
 */
class AutosavePolicyTest {

    @Test
    fun offSavesNothingAnywhere() {
        assertFalse(Autosave.Off.savesOnLeavingFile())
        assertFalse(Autosave.Off.savesOnLeavingApp())
        assertNull(Autosave.Off.autosaveDelayMs())
    }

    @Test
    fun onFocusChangeIsTheOneThatSavesOnLeavingAFile() {
        assertTrue(Autosave.OnFocusChange.savesOnLeavingFile())
        assertTrue(Autosave.OnFocusChange.savesOnLeavingApp())
        assertFalse(Autosave.OnWindowChange.savesOnLeavingFile())
        assertFalse(Autosave.AfterDelay(500).savesOnLeavingFile())
    }

    /**
     * Everything but Off writes when the app goes away: this process is killed
     * within a minute of leaving the foreground, and a debounce that has not
     * fired yet is exactly the work that would be lost.
     */
    @Test
    fun everythingButOffSavesOnTheWayOut() {
        assertTrue(Autosave.OnWindowChange.savesOnLeavingApp())
        assertTrue(Autosave.AfterDelay(500).savesOnLeavingApp())
    }

    @Test
    fun onlyAfterDelayHasADebounceForThePollToDrive() {
        assertEquals(500L, Autosave.AfterDelay(500).autosaveDelayMs())
        assertNull(Autosave.OnFocusChange.autosaveDelayMs())
        assertNull(Autosave.OnWindowChange.autosaveDelayMs())
    }
}
