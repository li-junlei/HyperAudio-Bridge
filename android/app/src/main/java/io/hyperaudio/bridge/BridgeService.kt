package io.hyperaudio.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataOutputStream
import javax.net.ssl.SSLSocket

class BridgeService : Service() {
    override fun onCreate() { super.onCreate(); running = this }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var capture: RootCapture? = null
    @Volatile private var explicitStop = false
    @Volatile private var destroying = false
    private val diagnostics by lazy { SessionLog(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { cancel(); return START_NOT_STICKY }
        if (job != null) return START_STICKY
        val id = intent?.getStringExtra("id") ?: BridgeApp.store.recoverSession()
        val computer = BridgeApp.store.computers.value.firstOrNull { it.id == id }
        if (computer == null) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("stream", "音频投送", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("正在连接 ${computer.name}"))
        explicitStop = false
        BridgeApp.store.rememberSession(id)
        BridgeState.activeId.value = id
        diagnostics.write("Session start pid=${Process.myPid()} recovery=${intent == null}")
        job = scope.launch {
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hyperaudio:stream")
            @Suppress("DEPRECATION") val wifi = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hyperaudio:stream")
            var protection: Job? = null
            val guard = RootProtection(this@BridgeService)
            try {
                wake.acquire(6 * 60 * 60 * 1000L); wifi.acquire()
                protection = launch {
                    var tick = 0
                    while (isActive) {
                        BridgeApp.store.rememberSession(id)
                        if (tick++ % 3 == 0) guard.renew()
                        if (!wake.isHeld) wake.acquire(6 * 60 * 60 * 1000L)
                        delay(10000)
                    }
                }
                var failures = 0
                while (isActive && !explicitStop) {
                    try {
                        BridgeState.message.value = "正在连接 ${computer.name}"
                        stream(BridgeApp.store.computers.value.firstOrNull { it.id == id } ?: computer, manager) { failures = 0 }
                        break // Only an explicit stop control message ends the user's session.
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val backoff = SessionRecovery.delayMillis(failures++)
                        diagnostics.write("Audio interrupted ${e.javaClass.simpleName}: ${e.message}")
                        if (backoff == null) throw e
                        BridgeState.message.value = "连接中断，正在恢复（$failures/5）"
                        manager.notify(1, notification(BridgeState.message.value))
                        delay(backoff)
                    }
                }
                if (!explicitStop) BridgeState.message.value = "电脑已结束投送，已切回本机"
            } catch (e: Exception) {
                if (isActive) BridgeState.message.value = "投送结束：${e.message ?: e.javaClass.simpleName}"
            } finally {
                withContext(NonCancellable) {
                protection?.cancelAndJoin()
                guard.close()
                closeTransport()
                if (wake.isHeld) wake.release()
                if (wifi.isHeld) wifi.release()
                BridgeState.activeId.value = null
                BridgeState.protection.value = "未启用"
                if (explicitStop) BridgeState.message.value = "未投送"
                job = null
                if (!destroying || explicitStop) BridgeApp.store.rememberSession(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (!destroying) stopSelf()
                diagnostics.write("Session released requestedStop=$explicitStop destroying=$destroying")
                }
            }
        }
        return START_STICKY
    }
    private suspend fun stream(computer: Computer, manager: NotificationManager, stable: () -> Unit) = coroutineScope {
        var monitor: Job? = null
        var watchdog: Job? = null
        var heartbeatJob: Job? = null
        val remoteEnded = java.util.concurrent.atomic.AtomicBoolean(false)
        val captured = java.util.concurrent.atomic.AtomicLong()
        val sent = java.util.concurrent.atomic.AtomicLong()
        val beats = java.util.concurrent.atomic.AtomicLong()
        try {
            val tls = Transport.connect(computer.host, computer.port, computer.pin)
            socket = tls; ensureActive()
            val response = Transport.request(tls, JSONObject().put("version", 1).put("op", "stream").put("heartbeat", true).put("diagnostics", BridgeApp.store.loggingEnabled).put("deviceId", BridgeApp.store.deviceId).put("token", computer.token))
            val heartbeat = response.optBoolean("heartbeat")
            diagnostics.write("TLS ready heartbeat=$heartbeat")
            tls.soTimeout = 0
            val output = DataOutputStream(tls.outputStream)
            val lastProgress = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
            val source = RootCapture(this@BridgeService)
            capture = source
            monitor = launch {
                try {
                    val event = Transport.readMessage(tls.inputStream)
                    remoteEnded.set(event.optString("type") == "stop")
                    diagnostics.write("Receiver control type=${event.optString("type", "unknown")}")
                } catch (e: Exception) { if (!explicitStop) diagnostics.write("Receiver channel closed ${e.javaClass.simpleName}: ${e.message}") }
                source.cancel(); runCatching { tls.close() }
            }
            if (heartbeat) heartbeatJob = launch {
                while (isActive) {
                    delay(2000)
                    if (SystemClock.elapsedRealtime() - lastProgress.get() >= 1500) {
                        try {
                            synchronized(output) { output.writeInt(0); output.flush() }
                            beats.incrementAndGet(); lastProgress.set(SystemClock.elapsedRealtime())
                        } catch (e: Exception) {
                            diagnostics.write("Heartbeat write failed ${e.javaClass.simpleName}")
                            source.cancel(); runCatching { tls.close() }; break
                        }
                    }
                }
            }
            ensureActive()
            val input = source.start()
            watchdog = launch {
                var nextLog = SystemClock.elapsedRealtime() + 5000
                while (isActive) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextLog) {
                        diagnostics.write("captured=${captured.get()} sent=${sent.get()} heartbeats=${beats.get()} lastWriteMs=${now-lastProgress.get()}")
                        nextLog = now + 5000
                    }
                    if (heartbeat && now - lastProgress.get() > 30000) {
                        diagnostics.write("Network writer stalled; closing connection")
                        source.cancel(); runCatching { tls.close() }; break
                    }
                }
            }
            BridgeState.message.value = "正在投送到 ${computer.name}"
            manager.notify(1, notification(BridgeState.message.value))
            var frames = 0
            while (isActive) {
                val packet = AudioFrames.read(input) ?: continue
                captured.incrementAndGet()
                synchronized(output) { output.writeInt(packet.size); output.write(packet); output.flush() }
                sent.incrementAndGet()
                lastProgress.set(SystemClock.elapsedRealtime())
                if (++frames == 100) stable()
            }
        } catch (e: Exception) {
            if (!explicitStop) diagnostics.write("Stream exit stopControl=${remoteEnded.get()} error=${e.javaClass.simpleName}: ${e.message} capture=${capture?.diagnostic?.takeLast(700)}")
            if (!remoteEnded.get() || e is CancellationException) throw e
        } finally {
            closeTransport()
            monitor?.cancel(); watchdog?.cancel(); heartbeatJob?.cancel()
        }
    }
    private fun closeTransport() {
        runCatching { socket?.close() }; socket = null
        capture?.close(); capture = null
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "stream").setSmallIcon(R.drawable.ic_bridge).setContentTitle("HyperAudio Bridge").setContentText(text)
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "本机播放", stop).build()).build()
    }
    private fun cancel() {
        explicitStop = true
        BridgeApp.store.rememberSession(null)
        BridgeState.message.value = "正在停止"
        diagnostics.write("Stop requested pid=${Process.myPid()} hasJob=${job != null}")
        val currentJob = job
        currentJob?.cancel()
        // Closing root pipes and TLS can block; never do this on the service main thread.
        scope.launch { closeTransport() }
        if (job == null) stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { Log.i("HyperAudio", "Task removed; audio service remains foreground") }
    override fun onDestroy() {
        if (running === this) running = null
        destroying = true
        capture?.cancel(); runCatching { socket?.close() }; scope.cancel()
        super.onDestroy()
    }
    companion object {
        @Volatile private var running: BridgeService? = null
        const val STOP = "io.hyperaudio.bridge.STOP"
        fun start(context: Context, id: String) { context.startForegroundService(Intent(context, BridgeService::class.java).putExtra("id", id)) }
        fun stop(context: Context) {
            // Service delivery does not depend on the display-state provider Binder.
            context.startService(Intent(context, BridgeService::class.java).setAction(STOP))
        }
        fun stopLocal(context: Context) {
            val service = running
            if (service != null) Handler(Looper.getMainLooper()).post { service.cancel() }
            else {
                BridgeApp.ensure(context).rememberSession(null)
                BridgeState.activeId.value = null
                BridgeState.message.value = "未投送"
            }
        }
    }
}
