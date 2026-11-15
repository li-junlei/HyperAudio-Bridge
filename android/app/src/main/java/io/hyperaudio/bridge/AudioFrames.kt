package io.hyperaudio.bridge

import java.io.DataInputStream
import java.io.IOException

object AudioFrames {
    const val OPUS = 0x6f707573
    const val CONFIG = 1L shl 62
    const val SESSION = Long.MIN_VALUE
    fun read(input: DataInputStream): ByteArray? {
        val flags = input.readLong()
        if (flags and SESSION != 0L) throw IOException("意外的音频会话头")
        val size = input.readInt()
        if (size !in 1..65536) throw IOException("采集帧长度无效")
        val packet = ByteArray(size)
        input.readFully(packet)
        if (flags and CONFIG != 0L) {
            require(size >= 19 && packet.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray())) { "无效的 Opus 配置" }
            require(packet[9].toInt() == 2) { "当前版本要求双声道音频" }
            return null
        }
        return packet
    }
}
