package io.hyperaudio.bridge.hook

import android.content.*
import android.os.*
import de.robv.android.xposed.*
import io.hyperaudio.bridge.FreezeLease

object FreezeHook {
    const val ACTION = "io.hyperaudio.bridge.FREEZE_LEASE"
    const val PERMISSION = "io.hyperaudio.bridge.permission.FREEZE_LEASE"
    private const val APP = "io.hyperaudio.bridge"
    private val lease = FreezeLease()
    private var registered = false
    private var installed = false
    fun install(loader: ClassLoader) {
        val name = "com.miui.server.greeze.GreezeManagerService"
        val type = XposedHelpers.findClassIfExists(name, loader)
        if (type != null) { installType(type); return }
        XposedHelpers.findAndHookMethod(ClassLoader::class.java, "loadClass", String::class.java, Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.args[0] == name) (p.result as? Class<*>)?.let { installType(it) }
                }
            })
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Pre-33 branch has no flags API; signature permission still protects it.
    @Synchronized private fun installType(type: Class<*>) {
        if (installed) return
        installed = true
        // Exact signatures inspected on OS4: both video-mode and ordinary freeze paths.
        for (methodName in listOf("freezeAction", "freezeUid", "freezeUidCgroup", "freezeProcess")) {
            XposedBridge.hookAllMethods(type, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val target = if (methodName == "freezeProcess") runCatching { XposedHelpers.getIntField(p.args[0], "uid") }.getOrDefault(-1) else p.args[0] as? Int ?: return
                    if (!lease.protects(target, SystemClock.elapsedRealtime())) return
                    p.result = when (methodName) { "freezeUid" -> "HyperAudio active stream"; "freezeUidCgroup" -> null; else -> false }
                    android.util.Log.i("HyperAudioFreeze", "blocked $methodName uid=$target")
                }
            })
        }
        // Binder can be frozen independently while the audio threads remain runnable.
        val processUtils = XposedHelpers.findClassIfExists("com.android.server.am.ProcessUtils", type.classLoader)
        if (processUtils != null) XposedBridge.hookAllMethods(processUtils, "freezeBinder", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val pid = p.args.getOrNull(0) as? Int ?: return
                val freezing = p.args.getOrNull(1) as? Boolean ?: return
                val uid = runCatching { XposedHelpers.callStaticMethod(Process::class.java, "getUidForPid", pid) as Int }.getOrDefault(-1)
                if (lease.blocksBinder(uid, freezing, SystemClock.elapsedRealtime())) {
                    p.result = 0
                    android.util.Log.i("HyperAudioFreeze", "blocked Binder freeze pid=$pid")
                }
            }
        })
        XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val service = p.thisObject
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        if (registered) return@runCatching
                        val context = XposedHelpers.getStaticObjectField(type, "mContext") as Context
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(ctx: Context, intent: Intent) {
                                runCatching {
                                    val uid = ctx.packageManager.getApplicationInfo(APP, 0).uid
                                    val enabled = intent.getBooleanExtra("enabled", false)
                                    if (!intent.getBooleanExtra("probe", false)) {
                                        lease.update(uid, enabled, SystemClock.elapsedRealtime())
                                        if (enabled) {
                                            XposedHelpers.callMethod(service, "thawUid", uid, 1000, "HyperAudio stream")
                                            // thawUid may have no cgroup record for a Binder-only freeze.
                                            val pid = intent.getIntExtra("pid", -1)
                                            val owner = runCatching { XposedHelpers.callStaticMethod(Process::class.java, "getUidForPid", pid) as Int }.getOrDefault(-1)
                                            if (pid > 0 && owner == uid && processUtils != null) {
                                                val result = XposedHelpers.callStaticMethod(processUtils, "freezeBinder", pid, false, 0)
                                                android.util.Log.i("HyperAudioFreeze", "Binder thaw pid=$pid result=$result")
                                            }
                                        }
                                    }
                                    @Suppress("DEPRECATION")
                                    val ack = intent.getParcelableExtra<ResultReceiver>("ack")
                                    ack?.send(1, Bundle().apply { putInt("moduleProtocol", 3) })
                                    android.util.Log.i("HyperAudioFreeze", "lease enabled=$enabled uid=$uid")
                                }.onFailure { XposedBridge.log("HyperAudio: freeze lease failed $it") }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(ACTION), PERMISSION, null, Context.RECEIVER_EXPORTED)
                        else context.registerReceiver(receiver, IntentFilter(ACTION), PERMISSION, null)
                        registered = true
                        XposedBridge.log("HyperAudio: session freeze protection ready")
                    }.onFailure { XposedBridge.log("HyperAudio: freeze receiver failed $it") }
                }
            }
        })
    }
}
