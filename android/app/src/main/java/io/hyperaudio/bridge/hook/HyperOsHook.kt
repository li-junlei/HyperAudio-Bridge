package io.hyperaudio.bridge.hook

import android.content.*
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import io.hyperaudio.bridge.R

class HyperOsHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == "android" && param.processName == "android") {
            runCatching { FreezeHook.install(param.classLoader) }.onFailure { XposedBridge.log("HyperAudio: freeze hook failed $it") }
            return
        }
        if (param.packageName !in setOf("com.android.systemui", "miui.systemui.plugin")) return
        XposedBridge.log("HyperAudio: fusion hook loaded ${param.processName}")
        XposedHelpers.findClassIfExists(CONTROLLER, param.classLoader)?.let { install(it) }
        listOf(NativeDetail.ROUTER, NativeDetail.PARAMS, NativeDetail.ANIMATOR).forEach { name -> XposedHelpers.findClassIfExists(name, param.classLoader)?.let(NativeDetail::install) }
        // HyperOS loads its control-center plugin after SystemUI, using a separate ClassLoader.
        XposedHelpers.findAndHookMethod(ClassLoader::class.java, "loadClass", String::class.java, Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.args[0] == CONTROLLER) (p.result as? Class<*>)?.let { install(it) }
                    if (p.args[0] == NativeDetail.ROUTER || p.args[0] == NativeDetail.PARAMS || p.args[0] == NativeDetail.ANIMATOR) (p.result as? Class<*>)?.let(NativeDetail::install)
                }
            })
    }
    private fun install(type: Class<*>) {
        if (!installed.add(type)) return
        try {
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val context = p.args.firstOrNull { it is Context } as? Context ?: return
                    val bridge = FusionBridge(context, p.thisObject, type.classLoader!!)
                    XposedHelpers.setAdditionalInstanceField(p.thisObject, "hyperaudio.fusion", bridge)
                    bridge.start()
                }
            })
            XposedHelpers.findAndHookMethod(type, "updateDeviceList", List::class.java, Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val bridge = XposedHelpers.getAdditionalInstanceField(p.thisObject, "hyperaudio.fusion") as? FusionBridge ?: return
                    val original = (p.args[0] as? List<*>)?.filterNotNull().orEmpty().filterNot { bridge.isOurs(it) }
                    bridge.original = original
                    p.args[0] = ArrayList(original + bridge.records)
                }
            })
            XposedBridge.hookAllMethods(type, "startDiscover", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    (XposedHelpers.getAdditionalInstanceField(p.thisObject, "hyperaudio.fusion") as? FusionBridge)?.refresh()
                }
            })
            XposedBridge.hookAllMethods(type, "deviceClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val wrapper = p.args.firstOrNull() ?: return
                    val id = runCatching { XposedHelpers.callMethod(wrapper, "getId") as? String }.getOrNull() ?: return
                    if (!id.startsWith(PREFIX)) return
                    val bridge = XposedHelpers.getAdditionalInstanceField(p.thisObject, "hyperaudio.fusion") as? FusionBridge ?: return
                    val name = runCatching { XposedHelpers.callMethod(wrapper, "getTitle").toString() }.getOrDefault("Windows 电脑")
                    val source = p.args.getOrNull(1) as? android.view.View ?: return
                    bridge.open(id.removePrefix(PREFIX), name, source); p.result = null
                }
            })
            XposedBridge.log("HyperAudio: native fusion DeviceCenterController hooks installed")
        } catch (e: Throwable) { XposedBridge.log("HyperAudio: fusion hook unavailable ${e.javaClass.simpleName}: ${e.message}") }
    }
    private class FusionBridge(val context: Context, controller: Any, val loader: ClassLoader) {
        private val target = WeakReference(controller)
        private val thread = HandlerThread("HyperAudioFusion").apply { start() }
        private val worker = Handler(thread.looper)
        @Volatile var original: List<Any> = emptyList()
        @Volatile var records: List<Any> = emptyList()
        private var fingerprint = ""
        private var errorLogged = false
        private val observer = object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) {
                val controller = target.get() ?: return
                if (runCatching { XposedHelpers.callMethod(controller, "getListening") as Boolean }.getOrDefault(false)) refresh()
            }
        }
        fun isOurs(info: Any) = runCatching { (XposedHelpers.callMethod(info, "getId") as? String)?.startsWith(PREFIX) == true }.getOrDefault(false)
        fun start() {
            context.contentResolver.registerContentObserver(URI, true, observer)
            worker.post(object : Runnable {
                override fun run() {
                    val controller = target.get()
                    if (controller == null) { context.contentResolver.unregisterContentObserver(observer); thread.quitSafely(); return }
                    if (runCatching { XposedHelpers.callMethod(controller, "getListening") as Boolean }.getOrDefault(false)) refresh()
                    worker.postDelayed(this, 3000)
                }
            })
            refresh()
        }
        fun refresh() { worker.removeCallbacks(readState); worker.post(readState) }
        private val readState = Runnable {
            try {
                val controller = target.get() ?: return@Runnable
                val data = context.contentResolver.call(URI, "publicState", null, null) ?: return@Runnable
                val json = data.getString("devices", "[]")
                if (json == fingerprint) return@Runnable
                val array = JSONArray(json)
                val values = (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optBoolean("online") || it.optBoolean("active") }
                val type = XposedHelpers.findClass("com.miui.circulate.device.api.Constant\$DeviceType", loader)
                val category = XposedHelpers.findClass("com.miui.circulate.device.api.Constant\$DeviceCategory", loader)
                val states = XposedHelpers.findClass("com.miui.circulate.device.api.Constant\$State", loader)
                val iconClass = XposedHelpers.findClass("com.miui.circulate.device.api.Icon", loader)
                val builderClass = XposedHelpers.findClass("com.miui.circulate.device.api.DeviceInfo\$Builder", loader)
                val icon = XposedHelpers.callStaticMethod(iconClass, "fromPublicDrawable", APP, R.drawable.ic_computer)
                records = values.map { pc ->
                    val builder = XposedHelpers.newInstance(builderClass)
                    fun set(name: String, value: Any) { XposedHelpers.callMethod(builder, name, value) }
                    val active = pc.optBoolean("active")
                    var state = XposedHelpers.getStaticIntField(states, "STATE_ONLINE")
                    if (active) state = state or XposedHelpers.getStaticIntField(states, "STATE_AUDIO_CAST")
                    set("setId", PREFIX + pc.getString("id")); set("setTitle", pc.getString("name"))
                    set("setSubtitle", if (active) "正在接收手机音频" else "点击投送声音")
                    set("setDeviceType", XposedHelpers.getStaticObjectField(type, "WINDOWS_PC"))
                    set("setCategory", XposedHelpers.getStaticObjectField(category, "NEARBY"))
                    set("setIcon", icon); set("setState", state)
                    XposedHelpers.callMethod(builder, "build")
                }
                fingerprint = json
                val looper = XposedHelpers.getObjectField(controller, "bgLooper") as Looper
                Handler(looper).post {
                    runCatching { XposedHelpers.callMethod(controller, "updateDeviceList", ArrayList(original), false) }
                        .onFailure { XposedBridge.log("HyperAudio: fusion refresh failed ${it.message}") }
                }
                XposedBridge.log("HyperAudio: fusion devices=${records.size}")
            } catch (e: Throwable) {
                if (!errorLogged) { errorLogged = true; XposedBridge.log("HyperAudio: fusion state failed ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        fun open(id: String, name: String, source: android.view.View) {
            try {
                NativeDetail.show(context, loader, id, name, source)
            } catch (e: Throwable) {
                XposedBridge.log("HyperAudio: fusion panel failed ${e.message}")
                android.widget.Toast.makeText(context, "系统详情面板未就绪，请重载控制中心", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    companion object {
        private const val APP = "io.hyperaudio.bridge"
        private const val PREFIX = "hyperaudio:"
        private const val CONTROLLER = "miui.systemui.devicecenter.DeviceCenterController"
        private val URI = Uri.parse("content://$APP.state")
        private val installed = ConcurrentHashMap.newKeySet<Class<*>>()
    }
}
