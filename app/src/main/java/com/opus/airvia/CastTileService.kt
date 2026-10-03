package com.opus.airvia

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: shows the cast state at a glance; tapping stops
 * an active cast, or opens Airvia to start one (the capture consent
 * Android requires can only be granted from the app's screen).
 */
class CastTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (CastEngine.isActive) {
            try {
                startService(CastService.stopIntent(this))
            } catch (_: Exception) {
            }
            CastEngine.stopAll()
        } else {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val sp = CastEngine.speaker
        tile.state = if (CastEngine.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Airvia"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                CastEngine.state == CastEngine.State.STREAMING ->
                    "Casting to ${sp?.name ?: "speaker"}"
                CastEngine.state == CastEngine.State.CONNECTING -> "Connecting…"
                else -> "Tap to cast"
            }
        }
        tile.updateTile()
    }
}
