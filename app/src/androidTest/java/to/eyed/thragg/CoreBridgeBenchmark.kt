package to.eyed.thragg

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import to.eyed.thragg.core.CoreBridge

/**
 * P1-4 benchmark: 10k random edits on a 100k-line buffer through the JNI
 * bridge, interleaved with visible-window line reads (the per-frame read
 * pattern of the future editor). Results go to logcat under the tag
 * "thragg-bench"; record them in agent-docs when run on real hardware.
 *
 * Deterministic (fixed seed), ASCII-only so random byte offsets can be
 * clamped to valid boundaries cheaply.
 */
@RunWith(AndroidJUnit4::class)
class CoreBridgeBenchmark {
    @Test
    fun tenThousandRandomEditsOn100kLines() {
        val lines = 100_000
        val edits = 10_000
        val text = buildString(lines * 30) {
            for (i in 0 until lines) {
                append("line ").append(i).append(": the quick brown fox\n")
            }
        }
        var length = text.encodeToByteArray().size.toLong()
        val id = CoreBridge.createBuffer(text)
        try {
            assertEquals((lines + 1).toLong(), CoreBridge.bufferLineCount(id))

            val rng = Random(42)
            val editNanos = LongArray(edits)
            val readNanos = LongArray(edits)
            for (i in 0 until edits) {
                val start = rng.nextLong(length + 1)
                val end = minOf(length, start + rng.nextLong(6))
                val replacement = if (rng.nextBoolean()) "x".repeat(rng.nextInt(1, 6)) else ""

                var t0 = System.nanoTime()
                val version = CoreBridge.applyEdit(id, start, end, replacement)
                editNanos[i] = System.nanoTime() - t0
                assertTrue("edit $i failed", version > 0)
                length += replacement.length - (end - start)

                // A frame-sized read: 60 lines around a random row.
                val lineCount = CoreBridge.bufferLineCount(id)
                val first = rng.nextLong(lineCount)
                t0 = System.nanoTime()
                CoreBridge.bufferLines(id, first, first + 60)
                readNanos[i] = System.nanoTime() - t0
            }

            editNanos.sort()
            readNanos.sort()
            fun stats(name: String, sorted: LongArray) {
                val toUs = { n: Long -> n / 1_000.0 }
                Log.i(
                    "thragg-bench",
                    "$name: p50=${toUs(sorted[sorted.size / 2])}us " +
                        "p95=${toUs(sorted[(sorted.size * 95) / 100])}us " +
                        "p99=${toUs(sorted[(sorted.size * 99) / 100])}us " +
                        "max=${toUs(sorted.last())}us " +
                        "mean=${toUs(sorted.sum() / sorted.size)}us"
                )
            }
            stats("applyEdit", editNanos)
            stats("bufferLines(60)", readNanos)

            // Sanity: engine agrees with our length bookkeeping.
            assertEquals(length, CoreBridge.bufferText(id)!!.encodeToByteArray().size.toLong())
        } finally {
            CoreBridge.closeBuffer(id)
        }
    }
}
