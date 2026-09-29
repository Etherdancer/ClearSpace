package app.clearspace.network.discovery

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Newline-delimited message framing for the P2P protocol.
 *
 * Reads raw bytes up to '\n' and decodes them as UTF-8 at the end. (Decoding byte-by-byte
 * with `toChar()` treats UTF-8 as Latin-1, which corrupted every non-ASCII character — the
 * content hash then no longer matched and such posts could never be fetched.)
 * Wrap the socket stream in a BufferedInputStream first: reading an unbuffered socket one
 * byte at a time costs one system call per byte.
 */
internal object LineIO {
    /**
     * Returns the line without its terminator, or null on EOF with no data, or when the line
     * exceeds [maxBytes] (memory-exhaustion protection).
     */
    fun readLine(input: InputStream, maxBytes: Int): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b == '\r'.code) continue
            if (buf.size() >= maxBytes) return null
            buf.write(b)
        }
        return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
    }
}
