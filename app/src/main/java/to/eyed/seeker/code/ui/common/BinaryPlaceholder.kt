package to.eyed.seeker.code.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.ShareOut
import to.eyed.seeker.code.ui.media.MediaInfo
import to.eyed.seeker.code.ui.media.MediaKind
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget
import java.io.File

/**
 * What a file that is not text looks like when it is opened.
 *
 * The routing this stands at the end of is the load-bearing part, and it is
 * `MediaKind.of(name)` at OpenFiles.kt: a file it names is never handed to the
 * engine, so a 1.4 MB `target/deploy/escrow.so` or a screenshot never becomes
 * a text rope full of mojibake that the editor then tries to highlight. What
 * *draws* at the end of that route is this, and it is deliberately almost
 * nothing:
 *
 * - a picture is fitted to the view and left alone — no zoom, no pan, no 1:1,
 *   no toolbar. `ui/media/ImageZoom.kt` and Zed's five image-viewer chords go
 *   with the tab strip they were reachable from; a photograph on a 400dp
 *   portrait column is looked at, not inspected.
 * - anything else is one line — `escrow.so` over `binary · 214.0KiB` — and the
 *   two ways out of the app, **Open with…** and **Share…**, through the
 *   FileProvider (core/ShareOut.kt). Handing the file to whatever the device
 *   *does* have is the honest answer for a `.so`, an `.mp3` or a `.mp4`; this
 *   app no longer ships a player for the last two (the media3/ExoPlayer
 *   dependency goes with `ui/media/MediaPane.kt`).
 *
 * A decode that fails falls through to the same line rather than sitting on
 * "Reading…" for ever: an empty or truncated file is the common case — a
 * download that stopped, an artefact half-written by a build — and a spinner
 * that never resolves reads as a hang.
 */
@Composable
fun BinaryPlaceholder(
    absolutePath: String,
    kind: MediaKind?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val file = remember(absolutePath) { File(absolutePath) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background")),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == MediaKind.Image) ImageOrLine(file) else HandOver(file)
    }
}

/** What a decode produced. "Not yet" and "cannot" are different answers. */
private sealed interface Decoded {
    data object Loading : Decoded

    data object Failed : Decoded

    class Ready(val bitmap: ImageBitmap) : Decoded
}

@Composable
private fun ImageOrLine(file: File) {
    // Decoded off the main thread: a phone camera's JPEG is tens of megabytes
    // of pixels once it is a bitmap, and the decode is not free.
    val decoded by produceState<Decoded>(Decoded.Loading, file.path) {
        value = withContext(Dispatchers.IO) {
            val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
            if (bitmap == null) Decoded.Failed else Decoded.Ready(bitmap.asImageBitmap())
        }
    }
    when (val state = decoded) {
        is Decoded.Loading -> Line(file.name, "reading…")
        // Not a dead end: an image Android cannot decode is still a file the
        // device may have something else for.
        is Decoded.Failed -> HandOver(file)
        is Decoded.Ready -> Image(
            bitmap = state.bitmap,
            contentDescription = file.name,
            // Fit, not None: the whole picture, at the largest size that fits,
            // never enlarged past the view. This is the one behaviour the zoom
            // machinery had that anybody actually used (Zed's own first layout,
            // image_viewer.rs:424-432).
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}

/** The file's name, what it is, and the two ways out of this app. */
@Composable
private fun HandOver(file: File) {
    val context = LocalContext.current
    // `length()` and `exists()` are two stats of a file that is not being
    // watched by anything — there is no buffer behind it — so they are read
    // once, here, rather than per frame.
    val detail = remember(file.path) { binaryDetail(file) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        Line(file.name, detail)
        if (ShareOut.canShare(file)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Action("Open with…") { ShareOut.openWith(context, file) }
                Action("Share…") { ShareOut.share(context, file) }
            }
        }
    }
}

@Composable
private fun Line(title: String, detail: String) {
    val theme = LocalZedTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            textAlign = TextAlign.Center,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A text button in the pane's own colours; Material's would bring a filled
 * pill. [touchTarget] rather than the padding alone: this is a phone, and the
 * only two actions on the screen are the ones a finger has to be able to hit.
 */
@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .touchTarget()
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}

/**
 * The line under the name: what the file is, in numbers.
 *
 * Deliberately the word "binary" and a size rather than a MIME type or a
 * decoded format — the point of the line is to say *why there is nothing to
 * read here*, and 214.0KiB of it. The size is Zed's `format_file_size`
 * (MediaInfo.fileSize, kept for exactly this).
 */
private fun binaryDetail(file: File): String = when {
    !file.exists() -> "not on disk"
    file.isDirectory -> "a directory"
    else -> "binary · ${MediaInfo.fileSize(file.length())}"
}
