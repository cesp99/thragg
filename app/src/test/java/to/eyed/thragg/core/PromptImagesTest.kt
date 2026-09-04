package to.eyed.thragg.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shrink policy for an attached picture — pure, so it can be pinned
 * here. The decode itself is `BitmapFactory`'s and is proved on a device
 * instead (CONVENTIONS § Traps, item 2: a green host suite says nothing
 * about Android's platform classes).
 */
class PromptImagesTest {

    /**
     * The subsample is the largest power of two that still leaves the long
     * edge at or above the target, so the decode allocates as little as it
     * can without landing *under* the target and needing to scale back up.
     */
    @Test
    fun theSampleSizeHalvesUntilOneMoreWouldGoUnder() {
        // Already small: no subsampling at all.
        assertEquals(1, PromptImages.sampleSize(800, 600, maxEdge = 1568))
        assertEquals(1, PromptImages.sampleSize(1568, 1000, maxEdge = 1568))
        // 3136 is exactly twice the target, so one halving is right and a
        // second would go under.
        assertEquals(2, PromptImages.sampleSize(3136, 2000, maxEdge = 1568))
        // A 12 MP photograph.
        assertEquals(2, PromptImages.sampleSize(4000, 3000, maxEdge = 1568))
        assertEquals(4, PromptImages.sampleSize(8000, 6000, maxEdge = 1568))
    }

    /** Orientation is not assumed: the *long* edge is the one that decides. */
    @Test
    fun theLongEdgeDecidesWhicheverItIs() {
        assertEquals(4, PromptImages.sampleSize(6000, 8000, maxEdge = 1568))
        assertEquals(784 to 1568, PromptImages.targetSize(2000, 4000, maxEdge = 1568))
    }

    @Test
    fun anImageThatFitsIsLeftAlone() {
        assertEquals(800 to 600, PromptImages.targetSize(800, 600, maxEdge = 1568))
        assertEquals(1568 to 900, PromptImages.targetSize(1568, 900, maxEdge = 1568))
    }

    /** Scaling keeps the shape, and never rounds a side away to nothing. */
    @Test
    fun scalingKeepsTheAspectRatioAndBothSides() {
        assertEquals(1568 to 1176, PromptImages.targetSize(4000, 3000, maxEdge = 1568))
        val (width, height) = PromptImages.targetSize(10_000, 3, maxEdge = 1568)
        assertEquals(1568, width)
        assertTrue("a side must never round to zero", height >= 1)
    }

    /**
     * The budget is asked *before* an attachment is added, so a refusal can be
     * explained rather than discovered when the prompt will not go.
     */
    @Test
    fun theBudgetIsCheckedBeforeAddingNotAfter() {
        assertTrue(PromptImages.fits(alreadyHeld = 0, next = PromptImages.MAX_TOTAL_BYTES))
        assertFalse(PromptImages.fits(alreadyHeld = 1, next = PromptImages.MAX_TOTAL_BYTES))
        assertTrue(PromptImages.fits(alreadyHeld = 1024, next = 1024))
    }

    /**
     * The wire shape the engine deserializes: `mime_type` and `data`, built
     * with a JSON builder — base64 has no quotes in it, but hand-built JSON
     * is where that stops being true one day.
     */
    @Test
    fun attachmentsBecomeTheEnginesWireShape() {
        val json = PromptImages.toJson(
            listOf(
                PromptAttachment("image/png", "aGVsbG8=", "shot.png"),
                PromptAttachment("image/jpeg", "d29ybGQ=", "photo.jpg"),
            )
        )
        val array = JSONArray(json)
        assertEquals(2, array.length())
        assertEquals("image/png", array.getJSONObject(0).getString("mime_type"))
        assertEquals("aGVsbG8=", array.getJSONObject(0).getString("data"))
        assertEquals("image/jpeg", array.getJSONObject(1).getString("mime_type"))
        // The name is ours, for the chip — it is not part of the wire shape.
        assertFalse(array.getJSONObject(0).has("name"))
    }

    @Test
    fun noAttachmentsIsAnEmptyArrayNotEmptyText() {
        assertEquals("[]", PromptImages.toJson(emptyList()))
    }
}
