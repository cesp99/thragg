package to.eyed.thragg.terminal

import java.io.InputStream

/**
 * One hard-link entry from a tar stream: [path] is meant to be another name
 * for [target]. Both are archive-relative — "usr/bin/perl" style, no leading
 * slash — exactly as they will be joined onto the rootfs directory.
 */
internal data class TarHardLink(val path: String, val target: String)

/**
 * The pure half of surviving tar hard links on Android.
 *
 * Debian's container image carries a hard link (`/usr/bin/perl` and
 * `perl5.40.1` are one inode), and no unpack an app can run reproduces it:
 * SELinux denies `link(2)`/`linkat(2)` to app-domain processes, and proot's
 * `--link2symlink` rewrite runs on already-translated paths, so on the one
 * invocation that has no `-r` it fabricates symlinks whose text is an
 * absolute *host* path — dangling the moment a session proot mounts the
 * rootfs under `-r`. Measured on a Seeker, 2026-08: the guest came up with
 * `/usr/bin/perl` simply absent while tar exited 0, debconf's perl frontend
 * exec'd 127, ca-certificates' postinst died, apt exited 100.
 *
 * So the tar stream itself is the source of truth: this object reads the
 * archive's own index of hard-link entries (typeflag '1', through GNU
 * 'L'/'K' long names and pax 'x' overrides), and computes the relative
 * symlink that stands in for each one. The [java.io.File]/`Os.symlink` side
 * lives in DebianUserland; everything here is testable without a device.
 */
internal object TarHardLinks {

    private const val BLOCK = 512

    /**
     * Every hard-link entry in [source], a plain (already un-gzipped) tar
     * stream, in archive order.
     *
     * Deliberately lenient: no checksum or magic validation, because the
     * bytes were digest-verified against the registry before they got here,
     * and a "bad" header worth rejecting would already have broken the real
     * unpack that ran first. Sparse ('S') payloads with continuation maps
     * would desynchronise this walk; Debian's base image has none, and a
     * desync can only lose links we would then also have lost before.
     */
    fun index(source: InputStream): List<TarHardLink> {
        val links = mutableListOf<TarHardLink>()
        val block = ByteArray(BLOCK)
        var zeroBlocks = 0
        // GNU 'L'/'K' entries and pax 'x' records describe the *next* real
        // header; they accumulate here and are consumed (and cleared) by it.
        var longName: String? = null
        var longTarget: String? = null
        var paxPath: String? = null
        var paxTarget: String? = null

        while (readBlock(source, block)) {
            if (block.all { it == 0.toByte() }) {
                if (++zeroBlocks == 2) break
                continue
            }
            zeroBlocks = 0
            val type = (block[156].toInt() and 0xff).toChar()
            val size = parseSize(block)
            when (type) {
                'L' -> longName = payloadString(source, size)
                'K' -> longTarget = payloadString(source, size)
                'x' -> {
                    val records = paxRecords(payloadBytes(source, size))
                    records["path"]?.let { paxPath = it }
                    records["linkpath"]?.let { paxTarget = it }
                }
                'g' -> skipPadded(source, size) // global pax: nothing we use
                else -> {
                    val name = normalize(longName ?: paxPath ?: headerName(block))
                    val target = normalize(longTarget ?: paxTarget ?: headerString(block, 157, 100))
                    if (type == '1' && name.isNotEmpty() && target.isNotEmpty()) {
                        links += TarHardLink(name, target)
                    }
                    // Links, directories and specials store no data no matter
                    // what their size field says (toybox does the same); only
                    // regular-file flavours are followed by payload blocks.
                    if (type !in '1'..'6') skipPadded(source, size)
                    longName = null; longTarget = null; paxPath = null; paxTarget = null
                }
            }
        }
        return links
    }

    /**
     * The text of a symlink standing in for a hard link: from [linkPath]'s
     * directory to [targetPath], both archive-relative. Relative on purpose —
     * it means the same thing from the host, from inside the guest, and after
     * the app's data directory moves (a backup/restore changes the prefix).
     */
    fun relativeTarget(linkPath: String, targetPath: String): String {
        val dir = normalize(linkPath).split('/').dropLast(1)
        val target = normalize(targetPath).split('/')
        var common = 0
        while (common < dir.size && common < target.size - 1 && dir[common] == target[common]) common++
        return (List(dir.size - common) { ".." } + target.drop(common)).joinToString("/")
    }

    // --- the on-disk index, for self-healing after install --------------------

    /** One `path<TAB>target` line per link; tar paths cannot contain either. */
    fun format(links: List<TarHardLink>): String =
        links.filter { '\t' !in it.path && '\n' !in it.path && '\t' !in it.target && '\n' !in it.target }
            .joinToString("") { "${it.path}\t${it.target}\n" }

    fun parse(text: String): List<TarHardLink> =
        text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) null
            else TarHardLink(line.take(tab), line.substring(tab + 1))
        }.toList()

    // --- header plumbing ------------------------------------------------------

    /** ustar name + prefix glued with a slash, as every tar reader does. */
    private fun headerName(block: ByteArray): String {
        val name = headerString(block, 0, 100)
        val prefix = headerString(block, 345, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun headerString(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && block[end] != 0.toByte()) end++
        return String(block, offset, end - offset, Charsets.UTF_8)
    }

    /** Octal size field, or GNU base-256 when the first byte's high bit is set. */
    private fun parseSize(block: ByteArray): Long {
        if (block[124].toInt() and 0x80 != 0) {
            var value = (block[124].toInt() and 0x7f).toLong()
            for (i in 125 until 136) value = (value shl 8) or (block[i].toLong() and 0xff)
            return value
        }
        var value = 0L
        for (i in 124 until 136) {
            val c = block[i].toInt().toChar()
            if (c in '0'..'7') value = (value shl 3) or (c - '0').toLong()
            else if (value != 0L) break
        }
        return value
    }

    /** "len key=value\n" records, length counted in bytes including itself. */
    private fun paxRecords(payload: ByteArray): Map<String, String> {
        val records = mutableMapOf<String, String>()
        var i = 0
        while (i < payload.size) {
            var space = i
            while (space < payload.size && payload[space] != ' '.code.toByte()) space++
            val length = String(payload, i, space - i).toIntOrNull() ?: break
            if (length <= 0 || i + length > payload.size || space >= i + length) break
            // Between the space and the record's trailing newline.
            val record = String(payload, space + 1, i + length - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) records[record.take(eq)] = record.substring(eq + 1)
            i += length
        }
        return records
    }

    /** Strip the "./" and "/" spellings tar writers disagree on. */
    private fun normalize(path: String): String {
        var p = path
        while (p.startsWith("./")) p = p.substring(2)
        return p.trimStart('/').trimEnd('/')
    }

    private fun payloadString(source: InputStream, size: Long): String =
        String(payloadBytes(source, size), Charsets.UTF_8).trimEnd('\u0000', ' ')

    private fun payloadBytes(source: InputStream, size: Long): ByteArray {
        // 'L'/'K'/'x' payloads are path-sized; anything huge is not one.
        require(size in 0..(1L shl 20)) { "unreasonable tar metadata payload: $size" }
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = source.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        skipPadded(source, 0, alreadyRead = size)
        return bytes
    }

    /** Skip [size] bytes of payload plus its padding to the 512 boundary. */
    private fun skipPadded(source: InputStream, size: Long, alreadyRead: Long = 0) {
        val total = size + alreadyRead
        var remaining = (size) + (BLOCK - total % BLOCK) % BLOCK
        while (remaining > 0) {
            val skipped = source.skip(remaining)
            if (skipped > 0) { remaining -= skipped; continue }
            if (source.read() < 0) break
            remaining--
        }
    }

    private fun readBlock(source: InputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < BLOCK) {
            val n = source.read(block, read, BLOCK - read)
            if (n < 0) return false // clean or truncated EOF: either way, done
            read += n
        }
        return true
    }
}
