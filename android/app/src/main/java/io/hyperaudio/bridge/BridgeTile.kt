package io.hyperaudio.bridge

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class BridgeTile : TileService() {
    override fun onStartListening() {
        runCatching { BridgeIpc.refresh(this) }
        qsTile?.apply {
            state = if (BridgeState.activeId.value != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "音频投送"
            subtitle = if (BridgeState.activeId.value != null) "点击切回本机" else "选择电脑"
            updateTile()
        }
    }
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated") // The Intent overload is required on Android 11–13; API 34+ uses PendingIntent below.
    override fun onClick() {
        if (BridgeState.activeId.value != null) { BridgeService.stop(this); onStartListening(); return }
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else startActivityAndCollapse(intent)
    }
}
