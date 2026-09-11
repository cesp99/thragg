package to.eyed.thragg.ui.components

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard

/**
 * The one way the app writes plain text to the system clipboard.
 *
 * Compose 1.10 deprecated `ClipboardManager.setText` for the suspend-only
 * `Clipboard.setClipEntry`; every site here copies from a click handler or
 * a synchronous editor action, and the platform manager underneath
 * ([Clipboard.nativeClipboard]) has always been synchronous, so the copy
 * goes straight to it rather than through a coroutine per click.
 */
fun Clipboard.setPlainText(text: String, label: String = "text") {
    nativeClipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * The clipboard's first item as text, or null when it holds none — what the
 * deprecated `ClipboardManager.getText()` returned, minus the styling the
 * editor never read.
 */
fun Clipboard.plainText(): String? =
    nativeClipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.text
        ?.toString()
