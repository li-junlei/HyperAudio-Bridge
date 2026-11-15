package io.hyperaudio.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class AudioFramesTest {
    private fun input(flags: Long, size: Int, payload: ByteArray = byteArrayOf()): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeLong(flags); writeInt(size); write(payload) }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }
    @Test fun mediaPacketIsUnchanged() { assertArrayEquals(byteArrayOf(1, 2, 3), AudioFrames.read(input(1234, 3, byteArrayOf(1, 2, 3)))) }
    @Test fun v5ConfigIsSkipped() {
        val config = ByteArray(19)
        "OpusHead".toByteArray().copyInto(config); config[9] = 2
        assertNull(AudioFrames.read(input(AudioFrames.CONFIG, 19, config)))
    }
    @Test(expected = IOException::class) fun oversizedFrameRejected() { AudioFrames.read(input(0, 65537)) }
    @Test(expected = IOException::class) fun emptyFrameRejected() { AudioFrames.read(input(0, 0)) }
    @Test(expected = EOFException::class) fun truncatedFrameRejected() { AudioFrames.read(input(0, 3, byteArrayOf(1))) }
    @Test(expected = IOException::class) fun sessionHeaderRejected() { AudioFrames.read(input(AudioFrames.SESSION, 0)) }
    @Test(expected = IllegalArgumentException::class) fun monoConfigRejected() {
        val config = ByteArray(19); "OpusHead".toByteArray().copyInto(config); config[9] = 1
        AudioFrames.read(input(AudioFrames.CONFIG, 19, config))
    }
}
