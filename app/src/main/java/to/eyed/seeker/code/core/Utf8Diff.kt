package to.eyed.seeker.code.core

/**
 * Minimal single-range diff between two UTF-8 byte strings: the byte range
 * of [old] to replace, and the replacement text. Both endpoints are kept on
 * UTF-8 code-point boundaries, which the engine requires.
 */
object Utf8Diff {
    data class Edit(val start: Int, val end: Int, val replacement: String)

    /** Returns null when [old] and [new] are identical. */
    fun diff(old: ByteArray, new: ByteArray): Edit? {
        var prefix = 0
        val maxPrefix = minOf(old.size, new.size)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        // Never split a UTF-8 code point: back off over continuation bytes.
        while (prefix > 0 && prefix < new.size && isContinuation(new[prefix])) prefix--

        var suffix = 0
        val maxSuffix = minOf(old.size, new.size) - prefix
        while (suffix < maxSuffix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) suffix++
        while (suffix > 0 && isContinuation(new[new.size - suffix])) suffix--

        if (prefix == old.size && prefix == new.size) return null

        return Edit(
            start = prefix,
            end = old.size - suffix,
            replacement = new.decodeToString(prefix, new.size - suffix),
        )
    }

    private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80
}
