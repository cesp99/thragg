package to.eyed.thragg.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the baseline profile for the one journey every session begins with:
 * a cold start of [MainActivity] into the workspace.
 *
 * Deliberately minimal. The app opens straight into a workspace whose
 * contents depend on device state (userland installed or not, previous
 * session, fold posture), so scripting deeper interactions here would flake.
 * A cold start already covers what matters most — process init, Compose
 * setup, the JNI bridge, and the first frame of the workspace.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        rule.collect(
            // One applicationId, one variant — the `full`/`play` flavour
            // split and its versionName suffixes are gone.
            packageName = "to.eyed.thragg",
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }
    }
}
