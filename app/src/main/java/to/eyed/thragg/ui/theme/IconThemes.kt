package to.eyed.thragg.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import to.eyed.thragg.ui.workspace.ZED_ICON_BY_STEM
import to.eyed.thragg.ui.workspace.ZED_ICON_BY_SUFFIX
import to.eyed.thragg.ui.workspace.ZED_ICON_DRAWABLE

/**
 * The one icon theme the app draws the file tree with: Zed's own.
 *
 * The tables in `ZedFileIcons.kt` are generated from Zed's
 * `theme/src/icon_theme.rs`; this gives them an identity and the name Zed
 * gives its set, `"Zed (Default)"`. Nothing else is looked for. The
 * `icon_themes` folder beside `settings.json`, the scan of it at start-up,
 * the `FileObserver` on it and the import/remove pair went with icon-theme
 * extensibility (docs/UI.md, "What is removed", 2026-09-08) — on a phone an
 * icon set that has to be hand-written as JSON with raster art beside it was
 * a feature nobody had reached for, and its scan was one more folder read
 * before the first frame.
 */
object IconThemes {
    /**
     * Zed's own set, named. The tables are generated from Zed's
     * `icon_theme.rs`; this only gives them an identity.
     */
    val bundled: IconTheme = IconTheme(
        name = IconTheme.DEFAULT_NAME,
        fileStems = ZED_ICON_BY_STEM,
        fileSuffixes = ZED_ICON_BY_SUFFIX,
        icons = ZED_ICON_DRAWABLE,
        collapsedDirectory = "ic_file_folder",
        expandedDirectory = "ic_file_folder_open",
        defaultFile = IconTheme.DEFAULT_FILE,
    )
}

/**
 * The icon theme in force. Static rather than threaded through every row:
 * the file tree, the tab strip and the pickers all draw file icons, and none
 * of them has anything else to do with settings.
 */
val LocalIconTheme = staticCompositionLocalOf { IconThemes.bundled }
