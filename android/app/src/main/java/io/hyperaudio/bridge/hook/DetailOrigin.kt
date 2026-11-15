package io.hyperaudio.bridge.hook

import android.graphics.Outline
import android.view.ViewGroup
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Implements the actual device item's animation origin without pretending it is a QS tile. */
class DetailOrigin(private val frame: ViewGroup) : InvocationHandler {
    private var target: Any? = null
    private var radius = Outline().let { outline ->
        frame.outlineProvider?.getOutline(frame, outline)
        outline.radius.takeIf { it >= 0f } ?: (minOf(frame.width, frame.height) / 2f)
    }
    fun create(loader: ClassLoader): Any = Proxy.newProxyInstance(loader,
        arrayOf(loader.loadClass("miui.systemui.controlcenter.panel.secondary.DetailFromView")), this)

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "getItemFrame", "getChangeFrame" -> frame
        "getIconFrame", "getIcon" -> null
        "getCornerRadius" -> radius
        "setCornerRadius" -> { radius = args!![0] as Float; null }
        "getChangeToView" -> target
        "setChangeToView" -> { target = args?.firstOrNull(); null }
        "changeAction" -> null
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        "toString" -> "HyperAudioDeviceOrigin"
        else -> throw UnsupportedOperationException("Unknown DetailFromView method: ${method.name}")
    }
}
