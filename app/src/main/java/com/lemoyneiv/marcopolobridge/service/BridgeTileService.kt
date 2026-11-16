package com.lemoyneiv.marcopolobridge.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lemoyneiv.marcopolobridge.audio.AudioBridgeController

/**
 * Quick Settings Tile ("Marco Polo Audio") for manual 1-tap activation or release
 * from the Pixel notification shade.
 */
class BridgeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val controller = AudioBridgeController.getInstance(this)
        controller.toggleManualHold()
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val st = AudioBridgeController.getInstance(this).status.value
        val active = st.snapshot.shouldHoldBridge
        val appName = st.snapshot.activeApp?.displayName
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Car Audio Bridge"
        tile.subtitle = when {
            active && !appName.isNullOrBlank() -> "$appName Active"
            active -> "Car Audio Active"
            else -> "Tap to Hold"
        }
        tile.updateTile()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, BridgeTileService::class.java)
                )
            }
        }
    }
}
