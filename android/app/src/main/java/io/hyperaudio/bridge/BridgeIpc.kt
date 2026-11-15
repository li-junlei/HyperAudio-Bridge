package io.hyperaudio.bridge

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray

object BridgeIpc {
    val URI: Uri = Uri.parse("content://io.hyperaudio.bridge.state")
    fun refresh(context: Context) = refresh(context.contentResolver)
    fun refresh(resolver: ContentResolver) {
        val state = resolver.call(URI, "privateState", null, null) ?: return
        val devices = JSONArray(state.getString("computers", "[]"))
        BridgeApp.store.computers.value = (0 until devices.length()).map { Computer.from(devices.getJSONObject(it)) }
        BridgeState.activeId.value = state.getString("activeId")
        BridgeState.message.value = state.getString("message", "本机播放")
        BridgeState.protection.value = state.getString("protection", "未启用")
        BridgeState.loggingEnabled.value = state.getBoolean("loggingEnabled")
        BridgeState.rootStatus.value = state.getString("rootStatus", "未检测")
        BridgeState.moduleStatus.value = state.getString("moduleStatus", "未连接")
        val nearby = JSONArray(state.getString("nearby", "[]"))
        BridgeState.nearby.value = (0 until nearby.length()).map { i -> nearby.getJSONObject(i).let { NearbyComputer(it.getString("id"), it.getString("name"), it.getString("host"), it.getInt("port")) } }
    }
    fun observe(context: Context): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { runCatching { refresh(context) } }
        }
        context.contentResolver.registerContentObserver(URI, true, observer)
        refresh(context)
        return observer
    }
}
