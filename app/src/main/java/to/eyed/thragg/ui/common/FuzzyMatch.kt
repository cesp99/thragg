package to.eyed.thragg.ui.common

/*
 * The in-app fuzzy matcher, lifted out of the command palette that used to own
 * it.
 *
 * It lived at the bottom of ui/workspace/Commands.kt, next to the palette's
 * ranking. The palette is gone (docs/UI.md, "What is removed") and the matcher
 * is not: the branch picker rows ranked their branch names through this exact
 * function and still do, which is the whole reason the palette's own doc
 * called it out. It has no Compose and no colour in it, so it belongs in
 * ui/common/ beside MarkdownText and BinaryPlaceholder rather than in either
 * half of the seam.
 *
 * Carried across verbatim — the scoring table, the exhaustive search and the
 * arguments for both are unchanged, because BranchPickerRowsTest pins the
 * order it produces.
 */

/** What a hit is worth, and which characters to highlight. */
internal class Hit(val score: Int, val positions: List<Int>)

private const val UNREACHABLE = Int.MIN_VALUE / 2

/** A character that matched at all. */
private const val MATCH_SCORE = 1

/** …at the start of a word, which is what people type first. */
private const val WORD_START_SCORE = 6

/** …immediately after the previous one: "term" beats t…e…r…m. */
private const val RUN_SCORE = 4

/** The most a single gap can cost, so one long skip doesn't sink a good hit. */
private const val MAX_GAP_PENALTY = 3

private const val WORD_SEPARATORS = " :_-./"

/**
 * Fuzzy subsequence match, scored so that word starts and unbroken runs win.
 *
 * This is the app's own matcher rather than the engine's. The engine's
 * `fuzzy` crate is reachable only through `find_files`, which matches a
 * project's paths and nothing else; matching a handful of short strings in
 * Kotlin is not worth a JNI call, and a `match_strings` entry point is the
 * right way to share the real thing later (noted for the bridge).
 *
 * The search is exhaustive rather than greedy — every way of laying the query
 * over the name, best kept — because greedy matching highlights the wrong
 * characters as soon as a letter repeats, and "toggle" against "terminal
 * panel: toggle" is exactly that case. It costs O(query × name²) over a
 * table this size, which is nothing.
 */
internal fun fuzzyMatch(name: String, query: String, smartCase: Boolean): Hit? {
    if (query.length > name.length) return null
    val rows = query.length
    val columns = name.length
    val score = Array(rows) { IntArray(columns) { UNREACHABLE } }
    val cameFrom = Array(rows) { IntArray(columns) { -1 } }

    for (i in 0 until rows) {
        // The i-th query character cannot land before column i.
        for (j in i until columns) {
            if (!same(query[i], name[j], smartCase)) continue
            val gain = MATCH_SCORE + if (isWordStart(name, j)) WORD_START_SCORE else 0
            if (i == 0) {
                score[0][j] = gain
                continue
            }
            var best = UNREACHABLE
            var bestFrom = -1
            for (k in i - 1 until j) {
                val previous = score[i - 1][k]
                if (previous == UNREACHABLE) continue
                val gap = j - k - 1
                val candidate = previous + gain +
                    if (gap == 0) RUN_SCORE else -minOf(gap, MAX_GAP_PENALTY)
                if (candidate > best) {
                    best = candidate
                    bestFrom = k
                }
            }
            if (bestFrom < 0) continue
            score[i][j] = best
            cameFrom[i][j] = bestFrom
        }
    }

    var end = -1
    var best = UNREACHABLE
    // Ascending with a strict comparison, so equal scores keep the earliest
    // match — the one the reader's eye is already on.
    for (j in rows - 1 until columns) {
        if (score[rows - 1][j] > best) {
            best = score[rows - 1][j]
            end = j
        }
    }
    if (end < 0) return null

    val positions = IntArray(rows)
    var column = end
    for (i in rows - 1 downTo 0) {
        positions[i] = column
        column = cameFrom[i][column]
    }
    return Hit(best, positions.toList())
}

private fun same(query: Char, candidate: Char, smartCase: Boolean): Boolean =
    if (smartCase) query == candidate else query.lowercaseChar() == candidate.lowercaseChar()

private fun isWordStart(name: String, index: Int): Boolean =
    index == 0 || name[index - 1] in WORD_SEPARATORS
