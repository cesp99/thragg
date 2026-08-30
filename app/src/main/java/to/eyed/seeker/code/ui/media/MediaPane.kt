package to.eyed.seeker.code.ui.media

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.ShareOut
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.UiFontFamily
import to.eyed.seeker.code.ui.theme.ZedTheme
import java.io.File

/** How far a seek key moves: five seconds, either way. */
internal const val SEEK_STEP_MS = 5_000L

/** How often the elapsed time under a playing file is refreshed. */
private const val POSITION_TICK_MS = 250L

/**
 * A file that is not text: shown or played, not parsed.
 *
 * Opening a PNG into a text buffer was never right — the engine would hold a
 * megabyte of mojibake and the editor would try to highlight it — and it is
 * the one case where "everything is a buffer" has to give. So a media tab has
 * no buffer at all: nothing to save, nothing to be dirty, and closing it
 * cannot lose work.
 *
 * A picture zooms and pans — a pinch and a drag, or Zed's image viewer chords,
 * which the workspace routes into [zoom] so the palette rows and the buttons
 * under the picture move the same numbers. Sound and video play, with the
 * keys every player answers to: Space, the arrows, Home.
 *
 * Only the active tab is composed, so leaving the tab — by switching, by
 * closing — tears the player down with the composition, and a player is never
 * left running behind another file. The app leaving the screen pauses it.
 */
@Composable
fun MediaPane(
    absolutePath: String,
    kind: MediaKind,
    zoom: ImageZoom,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background")),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == MediaKind.Image) ImageView(absolutePath, zoom) else PlayerView(absolutePath, kind)
    }
}

/** What a decode produced. "Not yet" and "cannot" are different answers. */
private sealed interface Decoded {
    object Loading : Decoded
    object Failed : Decoded
    class Ready(val bitmap: ImageBitmap) : Decoded
}

/** How often the file behind an open picture is re-checked. */
private const val FILE_POLL_MS = 500L

@Composable
private fun ImageView(absolutePath: String, zoom: ImageZoom) {
    val theme = LocalZedTheme.current
    val file = remember(absolutePath) { File(absolutePath) }

    // A picture has no buffer, so nothing else in the app is watching this
    // file: without this poll, overwriting or deleting it from the terminal
    // left the tab showing a bitmap that no longer exists on disk, with no
    // sign that anything had happened. Two longs, twice a second — and none
    // at all from the background.
    var stamp by remember(absolutePath) { mutableStateOf(0L to 0L) }
    ResumedEffect(absolutePath) {
        withContext(Dispatchers.IO) {
            while (true) {
                val next = file.lastModified() to file.length()
                if (next != stamp) withContext(Dispatchers.Main) { stamp = next }
                delay(FILE_POLL_MS)
            }
        }
    }

    // Decoded off the main thread: a photo is tens of megabytes of pixels and
    // the decode is not free.
    val decoded by produceState<Decoded>(Decoded.Loading, absolutePath, stamp) {
        value = Decoded.Loading
        value = withContext(Dispatchers.IO) {
            val bitmap = runCatching { BitmapFactory.decodeFile(absolutePath) }.getOrNull()
            if (bitmap == null) Decoded.Failed else Decoded.Ready(bitmap.asImageBitmap())
        }
    }

    when (val state = decoded) {
        is Decoded.Loading -> Message(title = file.name, detail = "Reading…")
        // Said, rather than left saying "Reading…" for ever. An empty or
        // truncated file is the common case — a download that stopped, a
        // placeholder somebody committed — and "Reading…" reads as a hang.
        is Decoded.Failed -> Message(
            title = file.name,
            detail = when {
                !file.exists() -> "This file is gone from disk."
                file.length() == 0L -> "This file is empty."
                else -> "Android cannot decode this image."
            },
        )
        is Decoded.Ready -> Picture(state.bitmap, file, stamp.second, zoom, theme)
    }
}

@Composable
private fun Picture(
    image: ImageBitmap,
    file: File,
    fileSize: Long,
    zoom: ImageZoom,
    theme: ZedTheme,
) {
    // A new file gets a fresh zoom; the same file re-decoded after a change
    // on disk keeps the one the user set.
    remember(file.path) { zoom.openFile(file.path) }
    val density = LocalDensity.current
    val imageSize = Size(image.width.toFloat(), image.height.toFloat())
    var container by remember { mutableStateOf(Size.Zero) }
    // Zed's first layout fits the picture to the pane, no larger than 100%
    // (image_viewer.rs:424-432); once the pane knows its size, so does this.
    LaunchedEffect(container, imageSize) { zoom.layout(container, imageSize) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { container = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(file.path) {
                    detectTransformGestures { centroid, dragged, zoomed, _ ->
                        // Keep what is under the fingers under the fingers.
                        // The centroid arrives from the pane's corner; the
                        // picture is scaled about its middle.
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        zoom.gesture(centroid - centre, dragged, zoomed)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Drawn at its own pixel size, so a zoom of 1 is one image pixel
            // per screen pixel — Zed's 100% — and everything else is a scale
            // on top. `ContentScale.None` keeps the platform from fitting it
            // again underneath.
            val width = with(density) { image.width.toDp() }
            val height = with(density) { image.height.toDp() }
            Image(
                bitmap = image,
                contentDescription = file.name,
                contentScale = ContentScale.None,
                modifier = Modifier
                    .requiredSize(width, height)
                    .graphicsLayer(
                        scaleX = zoom.zoom,
                        scaleY = zoom.zoom,
                        translationX = zoom.pan.x,
                        translationY = zoom.pan.y,
                    ),
            )
        }
        ImageToolbar(zoom)
        // Zed's status-bar line for a picture: dimensions, size, format
        // (image_info.rs:57-80).
        Text(
            text = MediaInfo.summary(
                MediaInfo.dimensions(image.width, image.height),
                MediaInfo.fileSize(fileSize),
                MediaInfo.imageFormat(file.name),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

/**
 * The finger's route to Zed's five image actions. The chords route through
 * the workspace's commands; these call the same [ImageZoom], so the two
 * cannot disagree.
 */
@Composable
private fun ImageToolbar(zoom: ImageZoom) {
    val theme = LocalZedTheme.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        GhostButton(label = "−", description = "Zoom out", onClick = zoom::zoomOut)
        Text(
            text = "${(zoom.zoom * 100).toInt()}%",
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 12.sp),
            color = theme.color("text.muted"),
            modifier = Modifier.widthIn(min = 44.dp),
        )
        GhostButton(label = "+", description = "Zoom in", onClick = zoom::zoomIn)
        GhostButton(label = "1:1", description = "Zoom to actual size", onClick = zoom::reset)
        GhostButton(label = "Fit", description = "Fit to view") { zoom.fitToView() }
    }
}

/** What the player is doing, as the pane needs to know it. */
private class Playback(val player: ExoPlayer) {
    var isPlaying by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var videoSize by mutableStateOf<VideoSize?>(null)
    var error by mutableStateOf<String?>(null)

    fun refresh() {
        position = player.currentPosition.coerceAtLeast(0L)
        duration = player.duration.coerceAtLeast(0L)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            // Play again from the top once the file has ended, as every
            // player's button does, rather than doing nothing at the end.
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun seekBy(deltaMillis: Long) = seekTo(MediaInfo.seekTarget(position, deltaMillis, duration))

    fun seekTo(millis: Long) {
        player.seekTo(millis)
        position = millis
    }
}

/**
 * Sound and video, played.
 *
 * ExoPlayer rather than the platform `MediaPlayer`: it decodes the containers
 * a repository actually holds — Matroska, WebM, Opus, FLAC — on every API
 * level this app runs on, and tells the pane the video's size before the
 * first frame, so the surface is laid out at the right shape rather than
 * jumping once decoding starts.
 */
@Composable
private fun PlayerView(absolutePath: String, kind: MediaKind) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val file = remember(absolutePath) { File(absolutePath) }
    val focus = remember { FocusRequester() }

    val playback = remember(absolutePath) {
        val player = ExoPlayer.Builder(context).build()
        val state = Playback(player)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.isPlaying = isPlaying
                state.refresh()
            }

            override fun onPlaybackStateChanged(playbackState: Int) = state.refresh()

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) state.videoSize = videoSize
            }

            override fun onPlayerError(error: PlaybackException) {
                state.error = error.errorCodeName.removePrefix("ERROR_CODE_")
                    .lowercase().replace('_', ' ')
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        // Prepared, not played: opening a tab must not start sound.
        player.prepare()
        state
    }

    // The player leaves with the composition — which is when the tab closes
    // or another tab takes its place — and pauses when the app leaves the
    // screen: a video in an IDE has no business playing under the home
    // screen, and it would keep the decoder awake for nothing.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(playback, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playback.player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            playback.player.release()
        }
    }

    // The elapsed time, ticked while playing and left alone otherwise: a
    // paused clock does not need a coroutine to stay still.
    LaunchedEffect(playback, playback.isPlaying) {
        while (playback.isPlaying) {
            playback.refresh()
            delay(POSITION_TICK_MS)
        }
    }

    // The keyboard's route, once the pane has it. Taken on a press, and when
    // the tab opens — the file is what was just asked for.
    LaunchedEffect(playback) { focus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    focus.requestFocus()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar -> playback.togglePlayPause()
                    Key.DirectionLeft -> playback.seekBy(-SEEK_STEP_MS)
                    Key.DirectionRight -> playback.seekBy(SEEK_STEP_MS)
                    Key.MoveHome -> playback.seekTo(0)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                playback.error != null -> HandOver(
                    file = file,
                    detail = "Android cannot play this file (${playback.error}).",
                )
                kind == MediaKind.Video -> VideoSurface(playback)
                else -> HandOver(file = file, detail = "audio")
            }
        }
        Controls(playback, theme)
        Text(
            text = MediaInfo.summary(
                playback.videoSize?.let { MediaInfo.dimensions(it.width, it.height) },
                MediaInfo.clock(playback.duration).takeIf { playback.duration > 0 },
                MediaInfo.fileSize(file.length()),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

/**
 * The file's name over the two ways out of this app: **Open with…** and
 * **Share…** through the FileProvider (core/ShareOut.kt), the same rows the
 * tab's own menu carries. Under a sound file because there is nothing to
 * draw for one, and under the sentence for a file Android cannot decode —
 * handing it to whatever the device *does* have is the honest answer there.
 */
@Composable
private fun HandOver(file: File, detail: String) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Message(title = file.name, detail = detail)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            HandOverAction("Open with…") { ShareOut.openWith(context, file) }
            HandOverAction("Share…") { ShareOut.share(context, file) }
        }
    }
}

/** A text button in the pane's own colours; Material's would bring a filled pill. */
@Composable
private fun HandOverAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}

/**
 * The video's own shape. Until the stream reports a size the surface is
 * 16:9, the common case, and it snaps to the real ratio when the size
 * arrives — `aspectRatio` inside a box that fills the pane picks the largest
 * rectangle of that shape that fits, which is what "sized properly" means.
 */
@Composable
private fun VideoSurface(playback: Playback) {
    val size = playback.videoSize
    val ratio = if (size == null) 16f / 9f else {
        // Anamorphic pixels stretch the picture horizontally.
        size.width * size.pixelWidthHeightRatio / size.height
    }
    AndroidView(
        factory = { context ->
            SurfaceView(context).also { playback.player.setVideoSurfaceView(it) }
        },
        // No `fillMaxSize` first: that would pin the minimum constraints and
        // leave `aspectRatio` nothing to choose between. Loose constraints
        // from the box let it take the largest rectangle of this shape.
        modifier = Modifier
            .aspectRatio(ratio)
            .background(Color.Black),
    )
}

/** Play/pause, the seek bar, and the clock beside it. */
@Composable
private fun Controls(playback: Playback, theme: ZedTheme) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GhostButton(
            label = if (playback.isPlaying) "❚❚" else "▶",
            description = if (playback.isPlaying) "Pause" else "Play",
            onClick = playback::togglePlayPause,
        )
        GhostButton(label = "⇤", description = "Restart") { playback.seekTo(0) }
        Text(
            text = MediaInfo.clock(playback.position),
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 12.sp),
            color = theme.color("text"),
        )
        // The bar is dragged rather than the player, and the player is told
        // where to go when the finger lifts: seeking on every pixel of a drag
        // would have the decoder chasing the thumb.
        var dragging by remember { mutableStateOf<Float?>(null) }
        val fraction = dragging ?: MediaInfo.progress(playback.position, playback.duration)
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { playback.seekTo((it * playback.duration).toLong()) }
                dragging = null
            },
            enabled = playback.duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = theme.color("text.accent", theme.color("text")),
                activeTrackColor = theme.color("text.accent", theme.color("text")),
                inactiveTrackColor = theme.color("element.background", theme.color("border")),
            ),
            modifier = Modifier.weight(1f).height(24.dp),
        )
        Text(
            text = MediaInfo.clock(playback.duration),
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 12.sp),
            color = theme.color("text.muted"),
        )
    }
}

/**
 * A ghost button, the way Zed's toolbars draw one: transparent at rest,
 * `ghost_element.hover` under the pointer, `ghost_element.active` while
 * pressed (button_like.rs:298-303, 324-329). The 22dp box is the visual; the
 * taller wrapper is the target a finger gets.
 */
@Composable
private fun GhostButton(label: String, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .widthIn(min = 30.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        pressed -> theme.color("ghost_element.active", Color.Transparent)
                        hovered -> theme.color("ghost_element.hover", Color.Transparent)
                        else -> Color.Transparent
                    }
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = TextStyle(fontFamily = UiFontFamily, fontSize = 12.sp),
                color = theme.color("text"),
            )
        }
    }
}

@Composable
private fun Message(title: String, detail: String) {
    val theme = LocalZedTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
        )
    }
}
