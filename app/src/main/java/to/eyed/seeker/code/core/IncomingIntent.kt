package to.eyed.seeker.code.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import java.io.File
import java.io.IOException

/**
 * What another app handed us, reduced to what the workspace needs to know.
 *
 * The engine works on real paths only (docs/ARCHITECTURE.md, "Where projects
 * live"), so a shared file is never opened *in place*: it is copied into a
 * project first. The two shapes here are the two things that can arrive —
 * files to copy, or a piece of text that has no file yet.
 */
sealed interface ImportRequest {
    /**
     * Files to bring in, as URI strings: a `content://` from a document
     * provider or a share sheet, or a `file://` from an older app.
     * [edit] is a hint that the sender used ACTION_EDIT rather than VIEW;
     * nothing in the flow differs yet, it is kept so the day it does the
     * mapping already carries it.
     */
    data class Files(val uris: List<String>, val edit: Boolean = false) : ImportRequest

    /** ACTION_SEND with EXTRA_TEXT and no stream: text with no file behind it. */
    data class Text(val text: String) : ImportRequest {
        companion object {
            /** The name it is written under; see [ImportRequest]. */
            const val FILE_NAME = "Shared text.txt"
        }
    }
}

/**
 * The parts of an [Intent] the mapping reads, as plain values so it can be
 * unit-tested on the host — android.jar's `Intent` is a stub there.
 */
data class IntentFacts(
    val action: String?,
    val mimeType: String?,
    /** The intent's `data` URI, for VIEW and EDIT. */
    val data: String?,
    /** EXTRA_STREAM — one for SEND, any number for SEND_MULTIPLE. */
    val streams: List<String> = emptyList(),
    /** EXTRA_TEXT, for a SEND with no stream. */
    val text: String? = null,
)

/**
 * The intent → request mapping. A pure function: what the manifest's filters
 * admit is decided here a second time, on purpose, because the manifest is a
 * declaration and this is the check — and because an explicit intent from
 * another app skips the filters entirely.
 *
 * Null means "nothing to import": the plain launch, a SEND with neither a
 * stream nor text, or a URI with a scheme that cannot be read as bytes.
 */
fun importRequestFor(facts: IntentFacts): ImportRequest? = when (facts.action) {
    Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
        val uri = facts.data?.takeIf(::isReadableScheme)
        uri?.let { ImportRequest.Files(listOf(it), edit = facts.action == Intent.ACTION_EDIT) }
    }
    Intent.ACTION_SEND -> {
        val streams = facts.streams.filter(::isReadableScheme)
        val text = facts.text
        when {
            streams.isNotEmpty() -> ImportRequest.Files(streams)
            !text.isNullOrBlank() -> ImportRequest.Text(text)
            else -> null
        }
    }
    Intent.ACTION_SEND_MULTIPLE -> {
        val streams = facts.streams.filter(::isReadableScheme)
        streams.takeIf { it.isNotEmpty() }?.let { ImportRequest.Files(it) }
    }
    else -> null
}

/**
 * Only `content:` and `file:` can be opened as a stream. Anything else — an
 * `http:` link shared from a browser, a `mailto:` — is not a file, and
 * pretending otherwise would import an empty one.
 */
private fun isReadableScheme(uri: String): Boolean {
    val scheme = uri.substringBefore(':', "").lowercase()
    return scheme == "content" || scheme == "file"
}

/**
 * Make [name] safe to write inside a project: a provider may hand back any
 * display name it likes, including one with separators, and a shared file
 * called `../settings.json` must not land where it says.
 */
fun safeFileName(name: String?, fallback: String): String {
    val cleaned = name.orEmpty()
        .replace('/', '_')
        .replace('\\', '_')
        .filter { it.code >= 0x20 }
        .trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") fallback else cleaned
}

/**
 * A shared file with its bytes already on app-private disk, waiting for the
 * workspace to decide where it goes. The temp file is the *only* copy we
 * hold: the URI permission that let us read it lasts for this activity
 * alone, and staging up front means the dialog can take as long as it likes.
 */
data class StagedFile(val name: String, val temp: File)

/** The Android half of [importRequestFor]: reading intents and content URIs. */
object IncomingFiles {

    /** Everything a plain launch does not carry. */
    fun requestFrom(intent: Intent?): ImportRequest? {
        if (intent == null) return null
        val streams = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
            else -> emptyList()
        }
        return importRequestFor(
            IntentFacts(
                action = intent.action,
                mimeType = intent.type,
                data = intent.data?.toString(),
                streams = streams.map(Uri::toString),
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            )
        )
    }

    /**
     * Where one request's files wait: a directory of their own, so a second
     * share arriving while the first is still being asked about cannot pull
     * the first's bytes out from under its dialog. Placing or cancelling
     * removes the files; anything left by a crash is in cache, which
     * Android reclaims.
     */
    private fun stagingDir(context: Context): File =
        File(File(context.cacheDir, "incoming"), System.nanoTime().toString()).apply { mkdirs() }

    /**
     * Copy every file the request names into app-private cache and learn
     * its display name. Blocking — call it from
     * [kotlinx.coroutines.Dispatchers.IO].
     *
     * A file that cannot be read is an error for the *whole* request rather
     * than a quiet omission: three files shared and two arriving is the kind
     * of thing nobody notices until the third one matters.
     */
    fun stage(context: Context, request: ImportRequest): Result<List<StagedFile>> {
        val dir = stagingDir(context)
        return runCatching {
            when (request) {
                is ImportRequest.Text -> {
                    val temp = File(dir, "0")
                    temp.writeText(request.text)
                    listOf(StagedFile(ImportRequest.Text.FILE_NAME, temp))
                }
                is ImportRequest.Files -> request.uris.mapIndexed { index, raw ->
                    val uri = Uri.parse(raw)
                    val temp = File(dir, index.toString())
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("${displayName(context, uri) ?: raw} could not be read")
                    input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                    StagedFile(safeFileName(displayName(context, uri), fallbackName(context, uri, index)), temp)
                }
            }
        }.onFailure { SafeDelete.deleteTree(dir) }
    }

    /**
     * Put a staged file into [root] at [relativePath]. Directories on the
     * way are created; a name already taken gets Zed's ` copy` suffix
     * rather than overwriting — a shared file must never replace one the
     * user was editing. Returns the project-relative path it landed at.
     */
    fun place(root: File, relativePath: String, staged: StagedFile): Result<String> {
        val parts = relativePath.trim().split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) {
            return Result.failure(IOException("$relativePath is not a place in the project"))
        }
        val parent = parts.dropLast(1).fold(root) { dir, part -> File(dir, part) }
        if (!SafeDelete.resolvesInside(root, parent)) {
            return Result.failure(IOException("$relativePath is outside the project"))
        }
        return runCatching {
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create ${parent.name}")
            val name = uniqueName(parent, parts.last())
            val target = File(parent, name)
            // rename() across the cache/files boundary is the same filesystem
            // on every device we know of, but copy when it is not.
            if (!staged.temp.renameTo(target)) {
                staged.temp.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                staged.temp.delete()
            }
            // The request's staging directory, once its last file has gone.
            staged.temp.parentFile?.delete()
            (parts.dropLast(1) + name).joinToString("/")
        }
    }

    /** `main.rs`, `main copy.rs`, `main copy 1.rs`… — Zed's scheme, shared with the panel. */
    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot <= 0) name else name.substring(0, dot)
        val extension = if (dot <= 0) "" else name.substring(dot)
        var index = 0
        while (true) {
            val suffix = if (index == 0) " copy" else " copy $index"
            val candidate = "$stem$suffix$extension"
            if (!File(dir, candidate).exists()) return candidate
            index++
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** A name for a stream that has none, with the extension its type suggests. */
    private fun fallbackName(context: Context, uri: Uri, index: Int): String {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val extension = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: if (type?.startsWith("text/") == true) "txt" else null
        val stem = if (index == 0) "shared file" else "shared file ${index + 1}"
        return if (extension != null) "$stem.$extension" else stem
    }
}
