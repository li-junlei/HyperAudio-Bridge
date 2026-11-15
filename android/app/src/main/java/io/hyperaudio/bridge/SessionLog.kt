package io.hyperaudio.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

class SessionLog(private val context: Context) {
    private val file = File(context.filesDir, "session.log")
    @Synchronized fun write(message: String) {
        if (!BridgeApp.ensure(context).loggingEnabled) return
        Log.i("HyperAudio", message)
        runCatching {
            if (file.length() > 262144) file.renameTo(File(file.parentFile, "session.previous.log").apply { delete() })
            file.appendText("${Instant.now()} $message\n")
        }
    }
}
