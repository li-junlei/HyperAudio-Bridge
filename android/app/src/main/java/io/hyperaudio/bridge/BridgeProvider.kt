package io.hyperaudio.bridge

import android.content.*
import android.content.pm.ApplicationInfo
import android.app.PendingIntent
import android.database.Cursor
import android.os.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

/** SystemUI sees only display state. Pairing secrets remain restricted to our own UID. */
class BridgeProvider : ContentProvider() {
    private val handler = Handler(Looper.getMainLooper())
    private var discovery: Discovery? = null
    private var observing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var leaseUntil = 0L
    override fun onCreate() = true
    private fun authorize(privateData: Boolean) {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (privateData) throw SecurityException("仅应用自身可访问凭证")
        val allowed = ctx.packageManager.getPackagesForUid(uid).orEmpty().any { name ->
            name in setOf("com.android.systemui", "miui.systemui.plugin", "com.milink.service") &&
                (ctx.packageManager.getApplicationInfo(name, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }
        if (!allowed) throw SecurityException("仅系统融合设备中心可读取显示状态")
    }
    private fun begin() {
        leaseUntil = SystemClock.elapsedRealtime() + 60000
        handler.post {
            if (discovery == null) {
                discovery = Discovery(requireNotNull(context))
                runCatching { discovery!!.start() }
                scope.launch {
                    val retry = flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(5000) } }
                    combine(discovery!!.devices, retry) { nearby, _ -> nearby }.collect { nearby ->
                        val store = BridgeApp.ensure(requireNotNull(context))
                        for (saved in store.computers.value) {
                            val candidates = nearby.filter { it.id == saved.id }.sortedBy { if (it.host == saved.host && it.port == saved.port) 0 else 1 }
                            for (found in candidates) {
                                val trusted = withContext(Dispatchers.IO) { runCatching { Transport.connect(found.host, found.port, saved.pin, 1500).use { } }.isSuccess }
                                if (trusted) {
                                    if (found.host == saved.host && found.port == saved.port) break
                                    // Discovery never changes identity or credentials; verify the pinned certificate first.
                                    store.computers.value.firstOrNull { it.id == saved.id }?.let { current -> store.save(current.copy(host = found.host, port = found.port)) }
                                    SessionLog(requireNotNull(context)).write("Paired receiver address refreshed")
                                    break
                                }
                            }
                        }
                        changed()
                    }
                }
            } else runCatching { discovery!!.start() }
            if (!observing) {
                observing = true
                scope.launch {
                    combine(BridgeState.activeId, BridgeState.message, BridgeState.protection, BridgeState.rootStatus, BridgeState.moduleStatus) { _, _, _, _, _ -> Unit }.collect { changed() }
                }
                handler.post(object : Runnable {
                    override fun run() {
                        if (BridgeState.activeId.value == null && SystemClock.elapsedRealtime() > leaseUntil) discovery?.close()
                        handler.postDelayed(this, 15000)
                    }
                })
            }
        }
    }
    private fun changed() { context?.contentResolver?.notifyChange(BridgeIpc.URI, null) }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize(method !in setOf("publicState", "controls", "stop"))
        val token = Binder.clearCallingIdentity()
        try {
            begin()
            val store = BridgeApp.ensure(requireNotNull(context))
            if (method == "stop") { BridgeService.stopLocal(requireNotNull(context)); return Bundle() }
            if (method == "inspect") {
                val ctx = requireNotNull(context)
                if (BridgeState.activeId.value == null) ctx.sendBroadcast(Intent("io.hyperaudio.bridge.FREEZE_LEASE").setPackage("android").putExtra("probe", true)
                    .putExtra("ack", object : ResultReceiver(handler) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { BridgeState.moduleStatus.value = if (resultData?.getInt("moduleProtocol") == 3) "已连接" else "需重启" }
                    }))
                scope.launch(Dispatchers.IO) {
                    BridgeState.rootStatus.value = "检测中"
                    BridgeState.rootStatus.value = runCatching {
                        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
                        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroy(); "未授权" }
                        else if (p.exitValue() == 0 && p.inputStream.bufferedReader().readText().trim() == "0") "已授权" else "未授权"
                    }.getOrDefault("未授权")
                }
                return Bundle()
            }
            if (method == "controls") {
                val ctx = requireNotNull(context)
                val pc = store.computers.value.firstOrNull { it.id == arg } ?: throw IllegalArgumentException("设备不存在")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                return Bundle().apply {
                    putParcelable("start", PendingIntent.getForegroundService(ctx, 0,
                        Intent(ctx, BridgeService::class.java).setData(android.net.Uri.parse("hyperaudio://stream/${pc.id}")).putExtra("id", pc.id), flags))
                    putParcelable("stop", PendingIntent.getService(ctx, 1, Intent(ctx, BridgeService::class.java).setAction(BridgeService.STOP), flags))
                }
            }
            when (method) {
                "save" -> store.save(Computer.from(JSONObject(requireNotNull(arg))))
                "forget" -> store.forget(requireNotNull(arg))
                "setLogging" -> store.setLogging(requireNotNull(extras).getBoolean("enabled"))
                "privateState", "publicState" -> { }
                else -> throw IllegalArgumentException("未知操作")
            }
            val nearby = discovery?.devices?.value.orEmpty()
            val active = BridgeState.activeId.value
            return Bundle().apply {
                putString("activeId", active)
                putString("message", BridgeState.message.value)
                putString("protection", BridgeState.protection.value)
                if (method != "publicState") {
                    putString("computers", JSONArray(store.computers.value.map { it.json() }).toString())
                    putString("deviceId", store.deviceId)
                    putBoolean("loggingEnabled", store.loggingEnabled)
                    putString("rootStatus", BridgeState.rootStatus.value)
                    putString("moduleStatus", BridgeState.moduleStatus.value)
                    putString("nearby", JSONArray(nearby.map { pc -> JSONObject().put("id", pc.id).put("name", pc.name).put("host", pc.host).put("port", pc.port) }).toString())
                }
                putString("devices", JSONArray(store.computers.value.map { pc ->
                    JSONObject().put("id", pc.id).put("name", pc.name).put("online", nearby.any { it.id == pc.id }).put("active", pc.id == active)
                }).toString())
            }
        } finally { Binder.restoreCallingIdentity(token) }
    }
    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: android.net.Uri) = "vnd.android.cursor.item/vnd.hyperaudio.state"
    override fun insert(uri: android.net.Uri, values: ContentValues?): android.net.Uri? = throw UnsupportedOperationException()
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: android.net.Uri, values: ContentValues?, s: String?, a: Array<out String>?) = throw UnsupportedOperationException()
}
