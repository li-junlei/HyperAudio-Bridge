package io.hyperaudio.bridge.hook

import android.app.PendingIntent
import android.content.*
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.util.SparseArray
import android.view.*
import android.widget.*
import de.robv.android.xposed.*
import java.lang.ref.WeakReference
import java.lang.reflect.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The system owns the panel, blur, header and More Settings footer; no app Activity is opened. */
object NativeDetail {
    const val ROUTER = "miui.systemui.controlcenter.panel.SecondaryPanelRouter"
    const val PARAMS = "miui.systemui.controlcenter.panel.secondary.SecondaryParamsKt"
    const val ANIMATOR = "miui.systemui.controlcenter.panel.secondary.detail.DetailPanelAnimator"
    private val routers = CopyOnWriteArrayList<WeakReference<Any>>()
    private val installed = ConcurrentHashMap.newKeySet<Class<*>>()
    private val uri = Uri.parse("content://io.hyperaudio.bridge.state")
    private const val METRICS = 14392
    fun install(type: Class<*>) {
        if (!installed.add(type)) return
        runCatching {
            if (type.name == ROUTER) XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    routers.removeAll { it.get() == null }
                    routers.add(WeakReference(p.thisObject))
                }
            })
            if (type.name == PARAMS) {
                val adapter = XposedHelpers.findClass("com.android.systemui.plugins.qs.DetailAdapter", type.classLoader)
                XposedHelpers.findAndHookMethod(type, "getType", adapter, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val candidate = p.args[0] ?: return
                        if (Proxy.isProxyClass(candidate.javaClass) && Proxy.getInvocationHandler(candidate) is Adapter) {
                            // Reuse the generic detail host, with our own unique content-cache key.
                            p.result = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("miui.systemui.controlcenter.PanelType", type.classLoader), "WIFI_SECONDARY_PANEL")
                        }
                    }
                })
            }
            if (type.name == ANIMATOR) {
                XposedHelpers.findAndHookMethod(type, "checkAndUpdateFromView", object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val params = XposedHelpers.callMethod(p.thisObject, "getPanelParams") ?: return
                        val adapter = XposedHelpers.callMethod(params, "getAdapter") ?: return
                        if (!Proxy.isProxyClass(adapter.javaClass) || Proxy.getInvocationHandler(adapter) !is Adapter) return
                        val origin = XposedHelpers.callMethod(params, "getFromView") ?: return
                        val frame = XposedHelpers.callMethod(origin, "getItemFrame") as? View ?: return
                        // Our origin is a device item, not a QS tile. Returning false forces a synchronous
                        // collapse, completing the router before MainPanel has started its reveal animation.
                        if (frame.isAttachedToWindow && frame.width > 0 && frame.height > 0) p.result = true
                    }
                })
            }
        }.onFailure { XposedBridge.log("HyperAudio: native detail hook ${it.message}") }
    }
    fun show(context: Context, loader: ClassLoader, id: String, name: String, source: View) {
        // Rotation / multiple displays can create more than one router. Never animate another window.
        val host = routers.asReversed().mapNotNull { it.get() }.firstOrNull { candidate ->
            val window = runCatching { XposedHelpers.getObjectField(candidate, "windowView") as? View }.getOrNull()
            window?.isAttachedToWindow == true && window.rootView === source.rootView
        } ?: throw IllegalStateException("当前控制中心窗口未就绪，请重载系统界面")
        // The router owns both transitions; do not replace its completion callback mid-animation.
        if (XposedHelpers.callMethod(host, "getInMainPanelIdle") != true) return
        val frame = (source as? ViewGroup) ?: (source.parent as? ViewGroup)
            ?: throw IllegalStateException("设备入口没有动画容器")
        check(frame.isAttachedToWindow && frame.width > 0 && frame.height > 0) { "设备入口尚未完成布局" }
        val provider = XposedHelpers.getObjectField(host, "detailPanelController")
        val controller = XposedHelpers.callMethod(provider, "get")
        val delegate = XposedHelpers.callMethod(controller, "getDelegate")
        // Only invalidate our slot; system Wi-Fi/Bluetooth caches remain owned by the system.
        (XposedHelpers.getObjectField(delegate, "detailViews") as SparseArray<*>).remove(METRICS)
        val iface = XposedHelpers.findClass("com.android.systemui.plugins.qs.DetailAdapter", loader)
        val adapter = Proxy.newProxyInstance(loader, arrayOf(iface), Adapter(context, loader, id, name))
        val origin = DetailOrigin(frame).create(loader)
        val params = XposedHelpers.newInstance(XposedHelpers.findClass("miui.systemui.controlcenter.panel.secondary.DetailPanelParams", loader), adapter, origin, false, false)
        XposedHelpers.callMethod(host, "routeToDetail", params)
    }
    private class Adapter(val context: Context, val loader: ClassLoader, val id: String, val name: String) : InvocationHandler {
        private var button: TextView? = null
        private var observer: ContentObserver? = null
        private var stopAction: PendingIntent? = null
        private var active = false
        private var stopping = false
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getTitle" -> name
            "hasHeader" -> true
            "getToggleState" -> null
            "getToggleEnabled" -> false
            "getMetricsCategory" -> METRICS
            "getSettingsIntent" -> Intent().setComponent(ComponentName("io.hyperaudio.bridge", "io.hyperaudio.bridge.MainActivity")).putExtra("page", "settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            "createDetailView" -> create(args!![0] as Context)
            "getBackgroundColor", "getButtonBackgroundColor", "getButtonTextColor", "getTitleTextColor" -> throw AbstractMethodError("Use system theme fallback")
            "getBackgroundBlendColors" -> null
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "HyperAudioNativeDetail"
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        }
        private fun refresh() {
            runCatching {
                val state = context.contentResolver.call(uri, "publicState", null, null) ?: return
                if (stopAction == null) {
                    @Suppress("DEPRECATION")
                    val action = context.contentResolver.call(uri, "controls", id, null)?.getParcelable<PendingIntent>("stop")
                    stopAction = action
                }
                active = state.getString("activeId") == id
                stopping = state.getString("message") == "正在停止"
                button?.text = if (stopping) "正在停止" else if (active) "停止投送" else "投送声音"
                button?.isEnabled = !stopping
            }
        }
        private fun create(ctx: Context): View {
            val view = XposedHelpers.newInstance(XposedHelpers.findClass("miui.systemui.controlcenter.widget.DetailPanelMoreButtonView", loader), ctx) as TextView
            button = view
            view.gravity = Gravity.CENTER
            view.textSize = 18f
            val background = ctx.resources.getIdentifier("detail_panel_button", "drawable", "miui.systemui.plugin")
            if (background != 0) view.setBackgroundResource(background)
            val color = ctx.resources.getIdentifier("detail_panel_button_text_color", "color", "miui.systemui.plugin")
            if (color != 0) view.setTextColor(ctx.getColor(color))
            val bg = ctx.resources.getIdentifier("detail_panel_button_background_color_state", "color", "miui.systemui.plugin")
            if (bg != 0) view.backgroundTintList = ctx.getColorStateList(bg)
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            val box = DetailContent(ctx) { refresh() }.apply {
                orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, pad)
                addView(view, LinearLayout.LayoutParams(-1, (60 * resources.displayMetrics.density).toInt()))
            }
            view.setOnClickListener {
                runCatching {
                    refresh()
                    if (stopping) return@runCatching
                    if (active) {
                        val stop = stopAction ?: error("停止入口未就绪")
                        stop.send()
                        view.text = "正在停止"
                        return@runCatching
                    }
                    val controls = context.contentResolver.call(uri, "controls", id, null) ?: return@runCatching
                    @Suppress("DEPRECATION") val action = controls.getParcelable<PendingIntent>("start")
                    action?.send()
                }.onFailure { Toast.makeText(ctx, "操作失败，请在 App 中查看诊断日志", Toast.LENGTH_SHORT).show() }
            }
            box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { refresh() } }
                    context.contentResolver.registerContentObserver(uri, true, observer!!); refresh()
                }
                override fun onViewDetachedFromWindow(v: View) { observer?.let { context.contentResolver.unregisterContentObserver(it) }; observer = null }
            })
            refresh()
            return box
        }
    }
}
