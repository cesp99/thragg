package to.eyed.seeker.code.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import to.eyed.seeker.code.core.AppSettings

/**
 * The app's settings, provided once at the root.
 *
 * A composition local rather than a parameter chain: the editor's font size,
 * the tab width and the panel's ignored-file filter are read in leaves far
 * from where settings are loaded, and threading them through every layout
 * would add noise to signatures that have nothing else to do with settings.
 */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
