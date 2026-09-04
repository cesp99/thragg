package to.eyed.thragg.ui.media

/**
 * What a file *is*, when it is not text at all.
 *
 * The project panel already knows this — Zed's icon table maps a hundred
 * suffixes onto `image`, `audio` and `video` — but the icon table is a UI
 * detail and this decides whether a file is loaded into a text buffer at all,
 * so it says so itself and stays small.
 *
 * Deliberately not "is this binary": a `.bin` we cannot show is still better
 * opened as text, where the user at least sees what is in it. This list is
 * only the kinds we can *render*.
 */
enum class MediaKind {
    /** A raster image: shown, not parsed. */
    Image,

    /** Sound and video: played, with the keys every player answers to. */
    Audio,
    Video;

    companion object {
        /**
         * The kind of [fileName], or null when it should open as text.
         *
         * Extension-driven and case-insensitive, matching the suffixes Zed's
         * own icon table calls `image`, `audio` and `video` — so a file that
         * *looks* like a picture in the panel opens as one.
         */
        fun of(fileName: String): MediaKind? {
            val suffix = fileName.substringAfterLast('.', "").lowercase()
            if (suffix.isEmpty()) return null
            return when {
                // Not SVG: it is text first, and Zed opens it in the editor
                // with a preview a button away (image_store.rs:261 excludes
                // it from the image viewer by name). See PreviewKind.
                suffix in IMAGES -> Image
                suffix in AUDIO -> Audio
                suffix in VIDEO -> Video
                else -> null
            }
        }

        /**
         * What Android's own decoder reads. Zed's `image` list also has
         * `psd`, `j2k`, `jp2`, `jxl`, `qoi` and `tiff`, which `BitmapFactory`
         * does not — those stay text rather than opening onto an error.
         */
        private val IMAGES = setOf(
            "avif", "bmp", "gif", "heic", "heif", "ico", "jfif", "jpeg", "jpg",
            "png", "webp",
        )

        private val AUDIO = setOf(
            "aac", "flac", "m4a", "mka", "mp3", "ogg", "opus", "wav", "wma", "wv",
        )

        private val VIDEO = setOf(
            "avi", "m4v", "mkv", "mov", "mp4", "webm", "wmv",
        )
    }
}
