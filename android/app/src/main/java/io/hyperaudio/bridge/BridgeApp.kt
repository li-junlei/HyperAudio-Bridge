package io.hyperaudio.bridge

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class BridgeApp : Application() {
    override fun onCreate() { super.onCreate(); ensure(this) }
    companion object {
        lateinit var store: DeviceStore
        @Synchronized fun ensure(context: Context): DeviceStore {
            if (!::store.isInitialized) store = DeviceStore(context.applicationContext)
            return store
        }
    }
}
data class Computer(val id: String, val name: String, val host: String, val port: Int, val pin: String, val token: String) {
    fun json() = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port).put("pin", pin).put("token", token)
    companion object {
        fun from(j: JSONObject) = Computer(j.getString("id"), j.getString("name"), j.getString("host"), j.getInt("port"), j.getString("pin"), j.getString("token"))
    }
}
class DeviceStore(context: Context) {
    private val resolver = context.contentResolver
    private val remote = Application.getProcessName() != context.packageName + ":bridge"
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val localDeviceId: String by lazy { prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("deviceId", it).commit()) } }
    val deviceId: String get() = if (remote) requireNotNull(resolver.call(BridgeIpc.URI, "privateState", null, null)?.getString("deviceId")) else localDeviceId
    val computers = MutableStateFlow(if (remote) emptyList() else load())
    private fun load(): List<Computer> {
        val array = JSONArray(prefs.getString("computers", "[]"))
        return (0 until array.length()).map { Computer.from(array.getJSONObject(it)) }
    }
    @Synchronized fun save(computer: Computer) {
        if (remote) { resolver.call(BridgeIpc.URI, "save", computer.json().toString(), null); BridgeIpc.refresh(resolver); return }
        write(computers.value.filter { it.id != computer.id } + computer)
    }
    @Synchronized fun forget(id: String) {
        if (remote) { resolver.call(BridgeIpc.URI, "forget", id, null); BridgeIpc.refresh(resolver); return }
        write(computers.value.filter { it.id != id })
    }
    private fun write(values: List<Computer>) {
        check(prefs.edit().putString("computers", JSONArray(values.map { it.json() }).toString()).commit()) { "无法保存配对" }
        computers.value = values
        resolver.notifyChange(BridgeIpc.URI, null)
    }
    fun rememberSession(id: String?) { prefs.edit().putString("session", id).putLong("sessionUntil", if (id == null) 0 else System.currentTimeMillis() + 90000).commit() }
    fun recoverSession(): String? = prefs.getString("session", null)?.takeIf { SessionRecovery.canRecover(it, prefs.getLong("sessionUntil", 0), System.currentTimeMillis()) }
    val loggingEnabled: Boolean get() = prefs.getBoolean("diagnosticLogging", false)
    fun setLogging(enabled: Boolean) {
        check(!remote)
        check(prefs.edit().putBoolean("diagnosticLogging", enabled).commit())
        resolver.notifyChange(BridgeIpc.URI, null)
    }
}
object BridgeState {
    val rootStatus = MutableStateFlow("未检测")
    val moduleStatus = MutableStateFlow("未连接")
    val nearby = MutableStateFlow<List<NearbyComputer>>(emptyList())
    val message = MutableStateFlow("选择电脑，开始音频投送")
    val activeId = MutableStateFlow<String?>(null)
    val protection = MutableStateFlow("未启用")
    val loggingEnabled = MutableStateFlow(false)
}
