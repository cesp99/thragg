package to.eyed.seeker.code.ui.preview

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.ui.media.MediaKind
import java.io.File

/**
 * What a `![alt](src)` in the previewed file can be drawn from.
 *
 * Held in a composition local rather than threaded through every block
 * renderer: the same `BlockView` tree draws the agent panel's replies, which
 * have no file and no project behind them, and the alternative was a nullable
 * parameter on nine functions that eight of them only pass along.
 *
 * Nothing here fetches anything. An `http(s)` src stays alt text: a preview of
 * a README somebody cloned an hour ago is not a place this app makes network
 * requests from, and a badge that silently phoned shields.io would be exactly
 * that. See [resolveImagePath].
 */
class MarkdownImages(
    /** The project's absolute root, or null when there is no project. */
    val projectRoot: String?,
    /** The previewed file's project-relative path — what a `src` is relative to. */
    val documentPath: String,
)

val LocalMarkdownImages = staticCompositionLocalOf<MarkdownImages?> { null }

/** Provide [images] to the preview's block renderers. */
@Composable
internal fun WithMarkdownImages(images: MarkdownImages?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMarkdownImages provides images, content = content)
}

/**
 * The absolute file a `src` names, or null when it names nothing local.
 *
 * The path is resolved against the previewed file's own directory and clamped
 * inside the project by [resolveRelativePath], so a `src` of
 * `../../../etc/passwd` in a cloned README resolves inside the project and not
 * out of it. Anything with a scheme — `http:`, `https:`, `data:`, `file:`, a
 * protocol-relative `//host/x` — is refused outright: those are the ones that
 * would be a fetch, and this makes none.
 */
internal fun resolveImagePath(projectRoot: String?, documentPath: String, src: String?): String? {
    val target = src?.trim().orEmpty()
    if (target.isEmpty() || projectRoot.isNullOrEmpty()) return null
    if (target.startsWith("//")) return null
    // A scheme is `letter *( letter / digit / "+" / "-" / "." ) ":"`, which is
    // also what keeps a Windows-shaped `C:\x` out.
    val colon = target.indexOf(':')
    if (colon > 0 && target.take(colon).all { it.isLetterOrDigit() || it in "+-." }) return null
    val relative = relativeLinkTarget(documentPath, target) ?: return null
    return "$projectRoot/$relative"
}

/**
 * Whether a `src` is worth giving its own element rather than leaving as alt
 * text — a suffix this app can actually draw.
 *
 * Deliberately a suffix test and not a probe of the disk: it runs during
 * composition, once per image per frame, and stat(2) on the main thread is
 * exactly the kind of thing that costs a frame on a cold cache. A file that
 * turns out to be missing falls back to its alt text when the decode fails.
 */
internal fun isDrawableImageSource(src: String?): Boolean {
    val name = src?.substringBefore('#')?.substringBefore('?')?.substringAfterLast('/') ?: return false
    return name.endsWith(".svg", ignoreCase = true) || MediaKind.of(name) == MediaKind.Image
}

/** The tallest a picture inside a paragraph is allowed to be. */
private val MaxImageHeight = 420.dp

/** Bigger than any screen; a decode past this is refused rather than attempted. */
private const val MAX_IMAGE_PIXELS = 4096

/** What resolving one `![alt](src)` produced. */
private sealed interface ResolvedImage {
    object Loading : ResolvedImage
    object Failed : ResolvedImage
    class Raster(val bitmap: ImageBitmap) : ResolvedImage
    class Vector(val drawing: SvgDrawing) : ResolvedImage
}

/**
 * One image from the document, drawn at its own size and never wider than the
 * space it has.
 *
 * "Its own size" matters: a badge is 100×20 and a screenshot is 1600×900, and
 * stretching the badge across the panel — which is what `fillMaxWidth` on an
 * aspect ratio does — makes a README's status row look like a billboard.
 */
@Composable
internal fun MarkdownImageView(
    span: InlineSpan,
    style: PreviewStyle,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val images = LocalMarkdownImages.current
    val path = remember(images, span.imageSource) {
        resolveImagePath(images?.projectRoot, images?.documentPath.orEmpty(), span.imageSource)
    }
    val resolved by produceState<ResolvedImage>(ResolvedImage.Loading, path) {
        val file = path?.let(::File)
        value = if (file == null) {
            ResolvedImage.Failed
        } else {
            withContext(Dispatchers.IO) { decode(file) }
        }
    }
    val tap = span.link?.let { destination -> Modifier.clickable { onLink(destination) } } ?: Modifier
    when (val state = resolved) {
        // "Reading…" would flash on every reparse; the alt text is what the
        // paragraph said before and what it falls back to anyway.
        is ResolvedImage.Loading, is ResolvedImage.Failed -> AltText(span, style, modifier.then(tap))
        is ResolvedImage.Raster -> Sized(
            state.bitmap.width.toFloat(),
            state.bitmap.height.toFloat(),
            modifier.then(tap),
        ) { size ->
            Image(
                bitmap = state.bitmap,
                contentDescription = span.text.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size.first, size.second),
            )
        }
        is ResolvedImage.Vector -> {
            val document = state.drawing.document
            val ink = style.theme.color("text")
            if (document == null) {
                AltText(span, style, modifier.then(tap))
            } else {
                Sized(document.viewportWidth, document.viewportHeight, modifier.then(tap)) { size ->
                    Canvas(modifier = Modifier.size(size.first, size.second)) {
                        drawSvgFitted(state.drawing, ink)
                    }
                }
            }
        }
    }
}

/**
 * Give [content] the picture's own size in dp, shrunk to fit the width on
 * offer and capped in height.
 */
@Composable
private fun Sized(
    width: Float,
    height: Float,
    modifier: Modifier,
    content: @Composable (Pair<Dp, Dp>) -> Unit,
) {
    if (width <= 0f || height <= 0f) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.padding(vertical = 4.dp)) {
        val natural = with(density) { width.toDp() }
        var drawWidth = minOf(natural, maxWidth)
        var drawHeight = drawWidth * (height / width)
        if (drawHeight > MaxImageHeight) {
            drawHeight = MaxImageHeight
            drawWidth = drawHeight * (width / height)
        }
        content(drawWidth to drawHeight)
    }
}

/** The old behaviour, kept for everything that cannot be drawn. */
@Composable
private fun AltText(span: InlineSpan, style: PreviewStyle, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Text(
            text = if (span.text.isBlank()) "[image]" else "[image: ${span.text}]",
            style = style.body,
            color = style.theme.color("text.muted"),
        )
    }
}

/**
 * Read one file into something drawable. **Blocks** — it is called on IO.
 *
 * The raster path measures the file before decoding it and refuses anything
 * past [MAX_IMAGE_PIXELS] on either axis rather than allocating it: a README
 * may point at a 12000×9000 scan, and that is 432 MB of ARGB in a heap with no
 * `largeHeap` behind it.
 */
private fun decode(file: File): ResolvedImage = runCatching {
    if (!file.isFile) return ResolvedImage.Failed
    if (file.name.endsWith(".svg", ignoreCase = true)) {
        if (file.length() > SvgDocument.MAX_CHARS) return ResolvedImage.Failed
        val drawing = SvgDrawing.of(file.readText())
        return if (drawing.document == null) ResolvedImage.Failed else ResolvedImage.Vector(drawing)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ResolvedImage.Failed
    val options = BitmapFactory.Options()
    var sample = 1
    while (bounds.outWidth / sample > MAX_IMAGE_PIXELS || bounds.outHeight / sample > MAX_IMAGE_PIXELS) {
        sample *= 2
    }
    options.inSampleSize = sample
    val bitmap = BitmapFactory.decodeFile(file.path, options)
    if (bitmap == null) ResolvedImage.Failed else ResolvedImage.Raster(bitmap.asImageBitmap())
}.getOrDefault(ResolvedImage.Failed)
