package app.clearspace.network.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class LineIOTest {
    private fun stream(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

    @Test
    fun decodesUtf8() {
        assertEquals("čćž 🌌 ok", LineIO.readLine(stream("čćž 🌌 ok\n"), 1024))
    }

    @Test
    fun stripsCrlfAndReadsSequentialLines() {
        val input = stream("first\r\nsecond\n")
        assertEquals("first", LineIO.readLine(input, 64))
        assertEquals("second", LineIO.readLine(input, 64))
        assertNull(LineIO.readLine(input, 64))
    }

    @Test
    fun rejectsOversizedLine() {
        assertNull(LineIO.readLine(stream("x".repeat(100) + "\n"), 10))
    }

    @Test
    fun acceptsLineWithoutTrailingNewline() {
        assertEquals("tail", LineIO.readLine(stream("tail"), 64))
    }
}
