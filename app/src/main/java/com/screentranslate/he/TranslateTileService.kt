package com.screentranslate.he

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: turns the floating "תרגם" button on or off.
 * Turning it on opens the app briefly, because Android asks for screen-capture consent each time.
 */
class TranslateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (ScreenTranslateService.isRunning) {
            startService(
                Intent(this, ScreenTranslateService::class.java)
                    .setAction(ScreenTranslateService.ACTION_STOP)
            )
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                updateTile()
            }
        } else {
            openAppAndStart()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppAndStart() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_AUTO_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (ScreenTranslateService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) {
                subtitle = if (ScreenTranslateService.isRunning) "פעיל" else "כבוי"
            }
            updateTile()
        }
    }
}
