package io.hyperaudio.bridge

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class CaptureLogTest {
    @Test fun normalLinesAreReported() {
        val lines = mutableListOf<String>()
        assertNull(drainCaptureLog(ByteArrayInputStream("one\ntwo\n".toByteArray()), lines::add))
        assertEquals(listOf("one", "two"), lines)
    }
    @Test fun pipeClosureDoesNotCrashLoggingThread() {
        val closedPipe = object : InputStream() { override fun read(): Int = throw IOException("Stream closed") }
        val failure = drainCaptureLog(closedPipe) { fail("No line expected") }
        assertEquals("Stream closed", failure?.message)
    }
}
