package io.hyperaudio.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** Session-scoped only: no global PowerKeeper changes and no permanent battery-policy override. */
class RootProtection(private val context: Context) : AutoCloseable {
    private fun freezeLease(enabled: Boolean) {
        context.sendBroadcast(Intent("io.hyperaudio.bridge.FREEZE_LEASE").setPackage("android")
            .putExtra("pid", Process.myPid()).putExtra("enabled", enabled).putExtra("ack", object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    BridgeState.moduleStatus.value = if (resultData?.getInt("moduleProtocol") == 3) "已连接" else "需重启"
                    SessionLog(context).write("System freeze lease acknowledged enabled=$enabled")
                }
            }))
    }
    private val originalAdj = runCatching { File("/proc/self/oom_score_adj").readText().trim().toInt() }.getOrDefault(200)
    fun renew() {
        freezeLease(true)
        val pid = Process.myPid()
        val command = "echo -700 > /proc/$pid/oom_score_adj; cmd deviceidle tempwhitelist -d 120000 ${context.packageName}"
        try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true)
                .redirectOutput(File(context.cacheDir, "background-protection.log")).start()
            if (!process.waitFor(4, TimeUnit.SECONDS)) { process.destroy(); throw IllegalStateException("Root 保护超时") }
            check(process.exitValue() == 0) { "Root 后台保护未获授权" }
            BridgeState.rootStatus.value = "已授权"
            BridgeState.protection.value = "独立音频进程 · 前台服务 · Root 临时电池白名单"
        } catch (e: Exception) {
            BridgeState.rootStatus.value = "未授权"
            BridgeState.protection.value = "前台服务运行中；Root 后台保护失败"
            Log.w("HyperAudio", "Background protection: ${e.message}")
        }
    }
    override fun close() {
        runCatching { freezeLease(false) }
        runCatching {
            val p = ProcessBuilder("su", "-c", "echo $originalAdj > /proc/${Process.myPid()}/oom_score_adj")
                .redirectErrorStream(true).redirectOutput(File(context.cacheDir, "background-release.log")).start()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
        }
    }
}
