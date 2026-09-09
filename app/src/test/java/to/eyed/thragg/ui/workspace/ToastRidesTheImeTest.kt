package to.eyed.thragg.ui.workspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toast column is padded by the keyboard.
 *
 * THE DEFECT THIS PINS was measured on the device: undo hitting the floor of
 * a reload answers with a toast, and undo lives only in the keyboard's own
 * action row — so the answer was drawn in the band the IME was covering and
 * the person who asked the question could never see it. Every warning raised
 * from a control on the keyboard has the same shape.
 *
 * It cannot be checked by running the composable — this module's tests are
 * plain JVM ones, with no window and no insets — so it is checked where the
 * rule lives: the host's own modifier chain, between the `LazyColumn` and the
 * width cap. A future edit that reorders the chain is fine; one that drops
 * the inset is what fails here.
 */
class ToastRidesTheImeTest {

    @Test
    fun `the notification column is padded by the ime`() {
        val source = File(sourceRoot(), "ui/workspace/NotificationHost.kt").readText()
        val chain = source
            .substringAfter("    LazyColumn(")
            .substringBefore(".widthIn(max = ToastWidth)")
        assertTrue(
            "The toast stack no longer clears the keyboard. With the IME up the band\n" +
                "sits underneath it, and the controls that raise most toasts — undo, save,\n" +
                "the find bar — are in the IME dock, so the answer lands where the question\n" +
                "cannot see it. Restore `.imePadding()` on the host's modifier.",
            ".imePadding()" in chain,
        )
    }

    /** `<module>/src/main/java/to/eyed/thragg`, found from wherever Gradle ran us. */
    private fun sourceRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "src/main/res/values/strings.xml").isFile) {
            dir = dir.parentFile ?: error("cannot find the app module from ${File("").absolutePath}")
        }
        return File(dir, "src/main/java/to/eyed/thragg")
    }
}
