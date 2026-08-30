package to.eyed.seeker.code.ui.workspace

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import java.util.concurrent.ConcurrentHashMap
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.theme.IconTheme
import to.eyed.seeker.code.ui.theme.IconThemes
import to.eyed.seeker.code.ui.theme.LocalIconTheme

/**
 * Which icon a file gets, from whichever icon theme is in force.
 *
 * The rule is Zed's and lives in [IconTheme] with the tables; this is the
 * platform half — turning the name that rule answers with into something
 * Compose can draw. Two kinds of name arrive: a drawable in the APK
 * (`ic_file_rust`), and an absolute path to an image a user icon theme
 * shipped. The leading `/` tells them apart.
 */
object FileIcons {

    /** The icon for [name] under [theme], as a painter. */
    @Composable
    fun forFile(theme: IconTheme, name: String): Painter =
        painter(theme.iconFor(name, fallback = IconThemes.bundled))

    /** Directories are a folder, open when the row is expanded — as in Zed. */
    @Composable
    fun forDirectory(theme: IconTheme, isExpanded: Boolean): Painter =
        painter(theme.directoryIcon(isExpanded))

    /** The icon for [name] under whatever theme the tree is drawing with. */
    @Composable
    fun forFile(name: String): Painter = forFile(LocalIconTheme.current, name)

    /**
     * The icon [reference] resolved to a painter, and whether it may be
     * tinted.
     *
     * The bundled icons are monochrome and Zed draws them tinted — the icon
     * says what kind of file it is, the row's colour says what git thinks of
     * it. A user icon theme ships its own art, which is very often coloured,
     * and tinting that would paint every icon in the tree one flat colour.
     * So the answer carries both.
     */
    fun tintable(reference: String): Boolean = !reference.startsWith("/")

    /** The icon [name] resolves to under [theme], as a reference. */
    fun referenceFor(theme: IconTheme, name: String, isDir: Boolean, isExpanded: Boolean): String =
        if (isDir) {
            theme.directoryIcon(isExpanded)
        } else {
            theme.iconFor(name, fallback = IconThemes.bundled)
        }

    /** A reference as a painter, for a caller that resolved it itself. */
    @Composable
    fun painterFor(reference: String): Painter = painter(reference)

    /**
     * The bundled theme's drawable name for [fileName].
     *
     * Kept as the shape the icon tests assert against: they are about the
     * *lookup*, which is Zed's and does not change with a theme.
     */
    internal fun resourceFor(fileName: String): String =
        IconThemes.bundled.iconFor(fileName)

    @Composable
    private fun painter(reference: String): Painter {
        if (reference.startsWith("/")) {
            // A user icon theme's own art. Decoded once per path and kept:
            // this runs for every tree row and tab on every recomposition.
            bitmap(reference)?.let { return it }
        }
        return painterResource(drawable(reference))
    }

    @Composable
    private fun bitmap(path: String): BitmapPainter? {
        bitmaps[path]?.let { return it.getOrNull() }
        val decoded = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        val painter = decoded?.let { BitmapPainter(it.asImageBitmap()) }
        // Misses are remembered too: a path that would not decode a moment
        // ago will not decode on the next frame either, and retrying it for
        // every row is how a broken theme becomes a stutter.
        bitmaps[path] = Result.success(painter)
        return painter
    }

    @Composable
    private fun drawable(name: String): Int {
        val context = LocalContext.current
        // Resources by name rather than a generated `when`: the table is
        // generated from Zed's and a name it carries that we have no drawable
        // for should degrade to the file sheet, not fail to compile. The name
        // lookup is a linear scan through the resource tables, and this runs
        // for every file-tree row and editor tab on every recomposition — so
        // each name is resolved once and remembered, misses included (a name
        // with no drawable stays missing; asking again won't change that).
        // Resource ids are stable for the life of the process.
        return ids.getOrPut(name) {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) id else R.drawable.ic_file_file
        }
    }

    private val ids = ConcurrentHashMap<String, Int>()
    private val bitmaps = ConcurrentHashMap<String, Result<BitmapPainter?>>()

    /** Forget decoded art — after the icon-themes folder changed. */
    fun clearImageCache() = bitmaps.clear()
}
