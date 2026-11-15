package io.hyperaudio.bridge

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import dalvik.system.PathClassLoader

/** On-device checks run as our UID and never return pairing tokens. */
class BridgeSmoke : Instrumentation() {
    private var mode = "native"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode = arguments?.getString("mode") ?: "native"; start() }
    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync { BridgeApp.ensure(targetContext) }
            val ctx = targetContext
            when (mode) {
                "detailOrigin" -> {
                    val apk = ctx.packageManager.getApplicationInfo("miui.systemui.plugin", 0).sourceDir
                    val loader = PathClassLoader(apk, ctx.classLoader)
                    runOnMainSync {
                        val frame = android.widget.FrameLayout(ctx).apply { layout(0, 0, 160, 160) }
                        val origin = io.hyperaudio.bridge.hook.DetailOrigin(frame).create(loader)
                        val type = loader.loadClass("miui.systemui.controlcenter.panel.secondary.DetailFromView")
                        check(type.getMethod("getItemFrame").invoke(origin) === frame)
                        check(type.getMethod("getChangeFrame").invoke(origin) === frame)
                        check(type.getMethod("getIconFrame").invoke(origin) == null)
                        type.getMethod("setCornerRadius", Float::class.javaPrimitiveType).invoke(origin, 24f)
                        check(type.getMethod("getCornerRadius").invoke(origin) == 24f)
                        val targetType = loader.loadClass("miui.systemui.controlcenter.panel.secondary.ChangeToView")
                        val target = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(targetType)) { _, _, _ -> frame }
                        type.getMethod("setChangeToView", targetType).invoke(origin, target)
                        check(type.getMethod("getChangeToView").invoke(origin) === target)
                        type.getMethod("changeAction").invoke(origin)
                    }
                    result.putString("origin", "PASS: actual native origin interface, geometry and change target")
                }
                "native" -> {
                    val apk = ctx.packageManager.getApplicationInfo("miui.systemui.plugin", 0).sourceDir
                    val loader = PathClassLoader(apk, ctx.classLoader)
                    val base = "com.miui.circulate.device.api."
                    val builderType = loader.loadClass(base + "DeviceInfo\$Builder")
                    val builder = builderType.getDeclaredConstructor().newInstance()
                    fun set(name: String, type: Class<*>, value: Any) { builderType.getMethod(name, type).invoke(builder, value) }
                    set("setId", String::class.java, "hyperaudio:smoke")
                    set("setTitle", String::class.java, "HyperAudio test")
                    set("setSubtitle", String::class.java, "点击投送声音")
                    val pc = loader.loadClass(base + "Constant\$DeviceType").getDeclaredField("WINDOWS_PC").get(null)
                    val category = loader.loadClass(base + "Constant\$DeviceCategory").getDeclaredField("NEARBY").get(null)
                    val state = loader.loadClass(base + "Constant\$State").getDeclaredField("STATE_ONLINE").getInt(null)
                    set("setDeviceType", String::class.java, requireNotNull(pc))
                    set("setCategory", String::class.java, requireNotNull(category))
                    set("setState", Int::class.javaPrimitiveType!!, state)
                    val iconType = loader.loadClass(base + "Icon")
                    val icon = iconType.getDeclaredMethod("fromPublicDrawable", String::class.java, Int::class.javaPrimitiveType).invoke(null, ctx.packageName, R.drawable.ic_computer)
                    set("setIcon", iconType, requireNotNull(icon))
                    val info = builderType.getMethod("build").invoke(builder)
                    check(info.javaClass.getMethod("getId").invoke(info) == "hyperaudio:smoke")
                    result.putString("native", "PASS: actual HyperOS Windows DeviceInfo builder / icon / state")
                }
                "start" -> {
                    BridgeIpc.refresh(ctx)
                    val pc = BridgeApp.store.computers.value.firstOrNull() ?: error("没有已配对电脑")
                    BridgeService.start(ctx, pc.id)
                    result.putString("session", "requested")
                }
                "stop" -> { BridgeService.stop(ctx); result.putString("session", "stopped") }
                "inspect" -> {
                    result.putString("package", ctx.packageName)
                    result.putString("process", android.app.Application.getProcessName())
                    result.putInt("localCount", BridgeApp.store.computers.value.size)
                    result.putString("dataDir", ctx.applicationInfo.dataDir)
                    val direct = ctx.getSharedPreferences("devices", Context.MODE_PRIVATE).getString("computers", "[]")
                    result.putInt("diskCount", org.json.JSONArray(direct).length())
                    val raw = ctx.contentResolver.call(BridgeIpc.URI, "privateState", null, null)
                    result.putInt("remoteCount", org.json.JSONArray(raw?.getString("computers", "[]")).length())
                    BridgeIpc.refresh(ctx)
                    result.putString("active", (BridgeState.activeId.value != null).toString())
                    result.putString("message", BridgeState.message.value)
                    result.putString("protection", BridgeState.protection.value)
                }
                "background" -> {
                    BridgeIpc.refresh(ctx)
                    val pc = BridgeApp.store.computers.value.firstOrNull() ?: error("没有已配对电脑")
                    try {
                        ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        Thread.sleep(1500)
                        BridgeService.start(ctx, pc.id)
                        Thread.sleep(15000)
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        repeat(9) {
                            Thread.sleep(10000)
                            BridgeIpc.refresh(ctx)
                            check(BridgeState.activeId.value == pc.id) { BridgeState.message.value }
                            check(BridgeState.message.value.startsWith("正在投送到")) { BridgeState.message.value }
                        }
                        result.putString("background", "PASS: 90 seconds streaming with activity in background")
                        result.putString("protection", BridgeState.protection.value)
                    } finally { BridgeService.stop(ctx); Thread.sleep(2000) }
                }
                "reloadSystemUI" -> {
                    val p = ProcessBuilder("su", "-c", "killall com.android.systemui").redirectErrorStream(true).start()
                    check(p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) { "无法刷新 SystemUI" }
                    result.putString("reload", "SystemUI restarted")
                }
                else -> error("Unknown mode")
            }
            finish(-1, result)
        } catch (e: Throwable) { result.putString("failure", "${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}"); finish(0, result) }
    }
}
