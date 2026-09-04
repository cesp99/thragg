package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zed's `reduce_motion`, and the Android answer it defers to.
 *
 * The decision is a pure function of the setting and one system value, so it
 * is checkable here; reading `Settings.Global.ANIMATOR_DURATION_SCALE` is the
 * caller's job and is the only part that needs a device.
 */
class ReduceMotionTest {

    @Test
    fun autoFollowsTheSystemAndTheOtherTwoOverrideIt() {
        // Android's Accessibility ▸ Remove animations, on and off.
        assertTrue(ReduceMotion.Auto.applies(systemAnimationsOff = true))
        assertFalse(ReduceMotion.Auto.applies(systemAnimationsOff = false))
        // Zed's two words mean what they say, whatever the system was told —
        // someone who wants the editor still on a phone that animates, or
        // animated on a phone that does not, has asked for it explicitly.
        assertTrue(ReduceMotion.On.applies(systemAnimationsOff = false))
        assertTrue(ReduceMotion.On.applies(systemAnimationsOff = true))
        assertFalse(ReduceMotion.Off.applies(systemAnimationsOff = true))
        assertFalse(ReduceMotion.Off.applies(systemAnimationsOff = false))
    }

    @Test
    fun theDefaultDefersToTheSystemRatherThanIgnoringIt() {
        assertEquals(ReduceMotion.Auto, AppSettings().reduceMotion)
        assertEquals(ReduceMotion.Auto, ReduceMotion.fromKey(null))
        assertEquals(ReduceMotion.Auto, ReduceMotion.fromKey("nonsense"))
        // Zed's spellings are read as written, so a settings file copied from
        // Zed means there what it meant there.
        assertEquals(ReduceMotion.On, ReduceMotion.fromKey("on"))
        assertEquals(ReduceMotion.Off, ReduceMotion.fromKey("off"))
    }

    @Test
    fun theSettingIsReadOutOfTheEnginesJson() {
        assertEquals(
            ReduceMotion.On,
            AppSettings.parse("""{"reduce_motion":"on"}""").reduceMotion,
        )
        assertEquals(
            ReduceMotion.Off,
            AppSettings.parse("""{"reduce_motion":"off"}""").reduceMotion,
        )
        assertEquals(ReduceMotion.Auto, AppSettings.parse("{}").reduceMotion)
    }
}
