package to.eyed.thragg.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * A picture attached to a prompt, ready for the wire.
 *
 * [data] is base64 because that is what ACP's `ImageContent` carries, and the
 * encoding happens here rather than in the engine: Android is the side that
 * has an image codec, and the engine is deliberately kept free of one.
 */
data class PromptAttachment(
    /** What the bytes are, after re-encoding — not what the file was called. */
    val mimeType: String,
    /** The re-encoded bytes, base64. */
    val data: String,
    /** What to call it in the composer's chip. */
    val name: String,
) {
    /** Roughly what this costs on the wire, for the composer's own limit. */
    val approximateBytes: Int get() = data.length / 4 * 3
}

/**
 * Turning a picked image into something an agent can be sent.
 *
 * **Why any of this happens at all.** A phone camera writes 8–12 MP JPEGs, and
 * base64 adds a third on top; the whole prompt goes down one pipe to the agent
 * (the reason `MAX_EMBEDDED_MENTION_BYTES` exists in the engine), and a
 * twenty-megabyte message would starve the turn it belongs to before the agent
 * had read a word of it. So a picked image is decoded at a subsample, scaled
 * to at most [MAX_EDGE] on its long side, and re-encoded.
 *
 * The policy — which subsample, what size, what quality — is pure and tested
 * on the host. The decode itself is Android's, and is proved on a device:
 * `BitmapFactory` is exactly the kind of platform class the host JVM does not
 * have (agent-docs/CONVENTIONS.md § Traps, item 2).
 */
object PromptImages {

    private const val TAG = "seeker-agent"

    /**
     * The longest edge an attachment keeps.
     *
     * 1568 px is what the vision models behind most agents downscale to
     * anyway, so anything larger costs upload and buys nothing.
     */
    const val MAX_EDGE = 1568

    /** Quality for a re-encoded JPEG. Below ~70 the artefacts start showing. */
    private const val JPEG_QUALITY = 85

    /**
     * The most one prompt's pictures may weigh, encoded.
     *
     * Not a protocol limit — a politeness one: past this the prompt takes
     * long enough to write that the agent looks hung.
     */
    const val MAX_TOTAL_BYTES = 6 * 1024 * 1024

    /**
     * The `inSampleSize` for an image of [width] × [height]: the largest power
     * of two that still leaves the long edge at or above [maxEdge], so the
     * decode allocates as little as it can without going under the target and
     * having to scale back up.
     */
    internal fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= maxEdge && sample < 1 shl 16) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * The size [width] × [height] becomes: unchanged when it already fits,
     * otherwise scaled down to [maxEdge] on the long side with the aspect
     * ratio kept and neither side allowed to round to nothing.
     */
    internal fun targetSize(
        width: Int,
        height: Int,
        maxEdge: Int = MAX_EDGE,
    ): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxEdge || longest == 0) return width to height
        val scale = maxEdge.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * Whether an attachment of [next] bytes still fits beside [alreadyHeld].
     * The composer asks before adding, so a refusal can be explained rather
     * than discovered when the prompt fails to arrive.
     */
    fun fits(alreadyHeld: Int, next: Int): Boolean = alreadyHeld + next <= MAX_TOTAL_BYTES

    /**
     * Read [uri], shrink it, and encode it — or null when it cannot be read
     * or is not an image at all. Blocking; call it off the main thread.
     */
    fun load(context: Context, uri: Uri): PromptAttachment? {
        val resolver = context.contentResolver
        // Bounds first: decoding a 12 MP photo just to find out how big it is
        // costs 48 MB of heap, and this app has an editor and a guest in the
        // same process.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.onFailure { error ->
            Log.w(TAG, "could not measure $uri", error)
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "not a decodable image: $uri")
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
        if (decoded == null) {
            Log.w(TAG, "could not decode $uri")
            return null
        }

        val (width, height) = targetSize(decoded.width, decoded.height)
        val scaled = if (width == decoded.width && height == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, width, height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }

        // PNG for something that arrived as a PNG — a screenshot of code is
        // the likeliest attachment in this app, and JPEG turns small text
        // into mush. Everything else goes out as JPEG, which for a photo is
        // several times smaller at a quality nobody can see.
        val sourceType = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        val png = sourceType.equals("image/png", ignoreCase = true) ||
            sourceType.equals("image/webp", ignoreCase = true)
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mimeType = if (png) "image/png" else "image/jpeg"

        val bytes = ByteArrayOutputStream()
        val compressed = runCatching { scaled.compress(format, JPEG_QUALITY, bytes) }
            .getOrDefault(false)
        scaled.recycle()
        if (!compressed || bytes.size() == 0) {
            Log.w(TAG, "could not re-encode $uri")
            return null
        }

        return PromptAttachment(
            mimeType = mimeType,
            // NO_WRAP: the default inserts newlines every 76 characters, which
            // is legal base64 and *not* legal inside a JSON string the way we
            // build it — and would bloat the prompt besides.
            data = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
            name = displayName(context, uri),
        )
    }

    /** What the picker called it, falling back to the last path segment. */
    private fun displayName(context: Context, uri: Uri): String {
        val fromCursor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return fromCursor ?: uri.lastPathSegment ?: "image"
    }

    /**
     * The attachments as the engine's `images_json`: `mime_type` and `data`,
     * built with `JSONObject` rather than by hand because base64 is not the
     * only thing in here — a file name never reaches it, but the same rule
     * that makes `AgentDefinition.toSpecJson` use a builder applies.
     */
    fun toJson(attachments: List<PromptAttachment>): String {
        val array = JSONArray()
        for (attachment in attachments) {
            array.put(
                JSONObject().apply {
                    put("mime_type", attachment.mimeType)
                    put("data", attachment.data)
                }
            )
        }
        return array.toString()
    }
}
