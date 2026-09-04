package to.eyed.thragg.ui.media

import java.util.Locale

/**
 * The line under a media tab: what the file is, in numbers.
 *
 * Zed's image viewer puts `{width}x{height} • {size} • {format}` in the
 * status bar (image_viewer/src/image_info.rs:57-80); this app has no
 * per-item status bar, so the same line sits under the picture. Sound and
 * video have no Zed precedent — it never plays them — so they borrow the
 * shape: dimensions where there are any, then the running time, then the
 * size on disk.
 *
 * Pure functions, so the formatting is testable without a player or a
 * bitmap behind it.
 */
object MediaInfo {
    /**
     * Zed's `format_file_size` (util/src/size.rs:1-17), binary unit: `999B`,
     * `1.5KiB`, `2.0MiB`. The decimal variant is the `image_viewer.unit`
     * setting's other answer and is kept for the day that setting is read.
     */
    fun fileSize(bytes: Long, decimal: Boolean = false): String {
        val kilo = if (decimal) 1000.0 else 1024.0
        val (kiloName, megaName) = if (decimal) "KB" to "MB" else "KiB" to "MiB"
        return when {
            bytes < kilo -> "${bytes}B"
            bytes < kilo * kilo -> String.format(Locale.ROOT, "%.1f%s", bytes / kilo, kiloName)
            else -> String.format(Locale.ROOT, "%.1f%s", bytes / (kilo * kilo), megaName)
        }
    }

    /**
     * A position or a length as a clock: `0:07`, `3:45`, `1:02:03`. Hours
     * appear only once there are some — a three-minute song does not read
     * `0:03:45` anywhere else. A negative or unknown time (ExoPlayer's
     * `C.TIME_UNSET` is a large negative) reads as zero rather than as a
     * minus sign.
     */
    fun clock(millis: Long): String {
        val totalSeconds = if (millis <= 0) 0L else millis / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** `1920x1080`, written the way Zed's status bar writes it. */
    fun dimensions(width: Int, height: Int): String = "${width}x${height}"

    /**
     * Zed's joiner between the parts (image_info.rs:80), so a picture reads
     * `1920x1080 • 1.2MiB • PNG` and a clip `1280x720 • 0:12 • 3.4MiB`.
     * Unknowns are simply left out: a stream without a reported size, a
     * length not yet read.
     */
    fun summary(vararg parts: String?): String = parts.filterNotNull().joinToString(" • ")

    /**
     * The name Zed's status bar gives the format, from the file's suffix
     * rather than its bytes — the platform decoder does not say which codec
     * it used, and the suffix is what put the file in this tab to begin with.
     * Zed's own table (image_info.rs:66-76) is copied for the formats it
     * lists and extended for the ones Android decodes that it does not.
     */
    fun imageFormat(fileName: String): String? =
        when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> "PNG"
            "jpg", "jpeg", "jfif" -> "JPEG"
            "gif" -> "GIF"
            "webp" -> "WebP"
            "bmp" -> "BMP"
            "ico" -> "ICO"
            "avif" -> "Avif"
            "heic", "heif" -> "HEIF"
            else -> null
        }

    /**
     * Where a seek by [deltaMillis] lands from [position] in a file [duration]
     * long: never before the start, never past the end, and never past
     * anything when the length is not known yet (a duration of zero or less).
     */
    fun seekTarget(position: Long, deltaMillis: Long, duration: Long): Long {
        val target = (position + deltaMillis).coerceAtLeast(0L)
        return if (duration > 0) target.coerceAtMost(duration) else target
    }

    /** The seek bar's fraction for [position] of [duration]; zero until the length is known. */
    fun progress(position: Long, duration: Long): Float =
        if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
}
