package to.eyed.seeker.code.core

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing a project file to another app — the other direction of
 * [IncomingFiles].
 *
 * Both go through the FileProvider declared in the manifest (authority
 * `<applicationId>.files`, paths in res/xml/file_paths.xml): a `file://` URI
 * has been refused across app boundaries since Android 7, and the provider
 * is what lets a URI carry a one-off read grant instead. Only the projects
 * directory is exported — see the XML.
 *
 * `ACTION_VIEW` is Zed's `workspace::OpenWithSystem` — the project panel's
 * "Open in Default App" (project_panel.rs:1161), which hands the absolute
 * path to the platform's opener (`cx.open_with_system`). `ACTION_SEND` has
 * no Zed counterpart: a desktop has no share sheet, and it is the way a file
 * leaves a phone.
 */
object ShareOut {
    private fun authority(context: Context) = "${context.packageName}.files"

    /**
     * The provider URI for [file], or null when it is not under the projects
     * directory — the provider would throw, and a file it cannot serve is a
     * file the menu should not have offered.
     */
    fun uriFor(context: Context, file: File): Uri? =
        runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull()

    /** Whether the menus should offer to share or open [file] at all. */
    fun canShare(file: File): Boolean = file.isFile

    /** Android's share sheet, with the file attached. */
    fun share(context: Context, file: File) {
        val uri = uriFor(context, file) ?: return refuse(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeOf(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, Intent.createChooser(send, "Share ${file.name}"), file)
    }

    /**
     * "Open with": the system's chooser of apps that view this type. Always
     * the chooser rather than the default handler, because a developer
     * opening a `.json` from an IDE is usually looking for the *other* app.
     *
     * This app is itself a VIEW handler for text (the manifest's open-with
     * filter), so it is struck from the list first: a chooser with a single
     * candidate launches it without asking, and for a file that nothing
     * else on the device can show that candidate was us — the file came
     * straight back through our own import dialog, offering to add a
     * project file to its own project. With nobody else left, a sentence
     * says so instead. The `<queries>` element in the manifest is what
     * lets the question be asked at all on Android 11+.
     */
    fun openWith(context: Context, file: File) {
        val uri = uriFor(context, file) ?: return refuse(context, file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val handlers = context.packageManager
            .queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .map { ViewHandler(it.activityInfo.packageName, it.activityInfo.name) }
        val (ours, others) = splitOwnHandlers(handlers, context.packageName)
        if (others.isEmpty()) {
            Toast.makeText(context, "No other app can open ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val chooser = Intent.createChooser(view, "Open ${file.name} with").apply {
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                ours.map { ComponentName(it.packageName, it.className) }.toTypedArray(),
            )
        }
        start(context, chooser, file)
    }

    private fun start(context: Context, chooser: Intent, file: File) {
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            // A chooser with no candidates: the system shows its own "no
            // apps" page for a chooser, so this is rare, and worth a sentence.
            Toast.makeText(context, "No app can open ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refuse(context: Context, file: File) {
        Toast.makeText(context, "${file.name} is not in a project", Toast.LENGTH_SHORT).show()
    }

    /**
     * The type another app is told. Source files mostly have no registered
     * type on Android (`.rs`, `.kt`, `.toml` are all unknown to
     * MimeTypeMap), and `application/octet-stream` would hide them from
     * every text editor in the chooser — so anything unknown is offered as
     * text, which is what a file in a code project overwhelmingly is.
     */
    internal fun mimeTypeOf(file: File): String {
        val extension = file.extension.lowercase()
        val known = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return known ?: "text/plain"
    }
}

/** One activity the package manager offers for a VIEW intent. */
data class ViewHandler(val packageName: String, val className: String)

/**
 * The handlers that are this app's own, and the rest — the ones "Open
 * with…" may offer. Pure, so the rule can be tested on the host: every
 * activity in [ownPackage] is ours, whatever it is called.
 */
internal fun splitOwnHandlers(
    handlers: List<ViewHandler>,
    ownPackage: String,
): Pair<List<ViewHandler>, List<ViewHandler>> =
    handlers.partition { it.packageName == ownPackage }
