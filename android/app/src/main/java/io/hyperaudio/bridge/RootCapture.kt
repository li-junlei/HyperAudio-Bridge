package io.hyperaudio.bridge

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

class RootCapture(private val context: Context) : AutoCloseable {
    private val scid = SecureRandom().nextInt(Int.MAX_VALUE)
    private val dir = "/data/local/tmp/hyperaudio-${UUID.randomUUID()}"
    private var listener: LocalServerSocket? = null
    private var socket: LocalSocket? = null
    private var process: Process? = null
    @Volatile private var closed = false
    @Volatile var diagnostic = ""
        private set

    fun start(): DataInputStream {
        val jar = File(context.filesDir, "scrcpy-server-5.0.1.jar")
        context.assets.open("scrcpy-server.jar").use { source -> jar.outputStream().use { source.copyTo(it) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha == "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536") { "音频服务文件校验失败" }
        synchronized(this) {
            check(!closed) { "投送已停止" }
            listener = LocalServerSocket("scrcpy_%08x".format(scid))
        }
        val launch = "echo \$\$ > $dir/pid; export CLASSPATH=$dir/server.jar; exec app_process / com.genymobile.scrcpy.Server 5.0.1 scid=${scid.toString(16)} video=false control=false audio=true audio_codec=opus audio_source=output audio_bit_rate=128000 tunnel_forward=false send_device_meta=false send_dummy_byte=false send_stream_meta=true send_frame_meta=true cleanup=false power_on=false"
        // Root only deploys and launches; the audio process uses scrcpy's shell UID.
        val command = "mkdir '$dir' && cp '${jar.absolutePath}' '$dir/server.jar' && chmod 644 '$dir/server.jar' && chown 2000:2000 '$dir' && chmod 700 '$dir' && exec su 2000 -c '$launch'"
        val p = synchronized(this) {
            check(!closed) { "投送已停止" }
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start().also { process = it }
        }
        Thread({
            val failure = drainCaptureLog(p.inputStream) { line -> diagnostic = (diagnostic + "\n" + line).takeLast(2500) }
            if (failure != null && !closed) diagnostic = (diagnostic + "\n日志管道关闭：" + failure.message).takeLast(2500)
            runCatching { listener?.close() }
        }, "capture-log").apply { isDaemon = true; start() }
        val timer = Thread({
            try { Thread.sleep(20000); if (socket == null) listener?.close() }
            catch (_: Exception) { }
        }, "capture-deadline").apply { isDaemon = true; start() }
        try {
            val accepted = listener!!.accept()
            socket = accepted
            check(!closed) { "投送已停止" }
            val uid = accepted.peerCredentials.uid
            check(uid == 2000 || uid == 0) { "拒绝非特权音频进程" }
            accepted.soTimeout = 7000
            val input = DataInputStream(accepted.inputStream)
            if (input.readInt() != AudioFrames.OPUS) throw IOException("手机未能启动音频捕获。${diagnostic.takeLast(800)}")
            // scrcpy's local stream is blocking; a quiet/paused source is not a broken pipe.
            accepted.soTimeout = 0
            return input
        } catch (e: Exception) {
            throw IOException("音频采集启动失败，请检查 Root 授权与 shell/SELinux 兼容性。${diagnostic.takeLast(800)}", e)
        } finally { timer.interrupt(); runCatching { listener?.close() } }
    }
    @Synchronized fun cancel() {
        closed = true
        runCatching { socket?.close() }
        runCatching { listener?.close() }
        process?.destroy()
    }
    override fun close() {
        cancel()
        // Verify the process command before terminating it, guarding against PID reuse.
        val cleanup = "if [ -f '$dir/pid' ]; then read p < '$dir/pid'; case \"\$p\" in ''|*[!0-9]*) ;; *) if tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null | grep -q 'com.genymobile.scrcpy.Server'; then kill \"\$p\"; fi ;; esac; fi; rm -f '$dir/pid' '$dir/server.jar'; rmdir '$dir' 2>/dev/null"
        runCatching {
            val p = ProcessBuilder("su", "-c", cleanup).redirectErrorStream(true).redirectOutput(File(context.cacheDir, "cleanup.log")).start()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
        }
    }
}
