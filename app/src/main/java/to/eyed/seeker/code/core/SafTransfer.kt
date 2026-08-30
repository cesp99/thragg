package to.eyed.seeker.code.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Copying project trees in and out through the Storage Access Framework.
 *
 * The engine needs a real filesystem path — Zed's worktree walks it with
 * `std::fs` and watches it with inotify — and a SAF tree offers neither. So
 * importing *copies* into app-private storage rather than opening in place,
 * and exporting copies back out. That is a real cost on large trees, and it
 * is the honest trade for having the engine work at all.
 *
 * These walk with [DocumentsContract] cursors rather than `DocumentFile`.
 * `DocumentFile.listFiles()` issues one query per child and allocates an
 * object per entry; on a project with thousands of files the difference is
 * minutes, not milliseconds.
 *
 * Everything here is **blocking**. Call it from
 * [kotlinx.coroutines.Dispatchers.IO].
 */
object SafTransfer {

    /** Progress while copying, so a big import doesn't look frozen. */
    data class Progress(val files: Int, val currentName: String)

    sealed interface Result {
        data class Imported(val project: File, val files: Int) : Result
        data class Exported(val files: Int) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Copy the SAF tree at [treeUri] into a new project. The project takes
     * the tree's own display name, uniquified if it is already taken.
     */
    fun importAsProject(
        context: Context,
        treeUri: Uri,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return Result.Failed("That folder could not be read")
        val rootName = displayName(context, treeUri, rootId) ?: "imported project"
        val name = ProjectsRoot.uniqueName(context, rootName)
        val destination = ProjectsRoot.projectDir(context, name)
        if (!destination.mkdirs()) return Result.Failed("Could not create $name")

        return try {
            var copied = 0
            copyTreeIn(context, treeUri, rootId, destination) { fileName ->
                copied++
                onProgress(Progress(copied, fileName))
            }
            ProjectsRoot.setLastOpened(context, name)
            Result.Imported(destination, copied)
        } catch (error: Exception) {
            // A half-copied project is worse than none: it would open, look
            // complete, and quietly be missing files.
            SafeDelete.deleteTree(destination)
            Result.Failed(error.message ?: "Import failed")
        }
    }

    /**
     * Copy [project] into the SAF tree at [treeUri], as a directory named
     * after the project.
     */
    fun exportProject(
        context: Context,
        project: File,
        treeUri: Uri,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return Result.Failed("That folder could not be written to")
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        return try {
            val target = DocumentsContract.createDocument(
                context.contentResolver,
                rootUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                project.name,
            ) ?: return Result.Failed("Could not create ${project.name} there")

            var copied = 0
            copyTreeOut(context, project, treeUri, DocumentsContract.getDocumentId(target)) { fileName ->
                copied++
                onProgress(Progress(copied, fileName))
            }
            Result.Exported(copied)
        } catch (error: Exception) {
            Result.Failed(error.message ?: "Export failed")
        }
    }

    // -----------------------------------------------------------------------

    private fun displayName(context: Context, treeUri: Uri, documentId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun copyTreeIn(
        context: Context,
        treeUri: Uri,
        parentId: String,
        destination: File,
        onFile: (String) -> Unit,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                // A provider is free to hand back any display name it likes,
                // including one with separators; never let it walk out of the
                // destination.
                val safe = name.replace('/', '_').replace('\\', '_')
                if (safe == "." || safe == "..") continue
                val child = File(destination, safe)

                if (isDirectory) {
                    child.mkdirs()
                    copyTreeIn(context, treeUri, documentId, child, onFile)
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        child.outputStream().use { output -> input.copyTo(output) }
                    }
                    onFile(safe)
                }
            }
        }
    }

    private fun copyTreeOut(
        context: Context,
        source: File,
        treeUri: Uri,
        parentId: String,
        onFile: (String) -> Unit,
    ) {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        for (child in source.listFiles().orEmpty()) {
            if (child.isDirectory) {
                val created = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    child.name,
                ) ?: continue
                copyTreeOut(context, child, treeUri, DocumentsContract.getDocumentId(created), onFile)
            } else {
                val created = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    // Everything goes out as an opaque stream: guessing MIME
                    // types would only invite providers to transcode source
                    // files or append extensions.
                    "application/octet-stream",
                    child.name,
                ) ?: continue
                context.contentResolver.openOutputStream(created)?.use { output ->
                    child.inputStream().use { input -> input.copyTo(output) }
                }
                onFile(child.name)
            }
        }
    }
}
