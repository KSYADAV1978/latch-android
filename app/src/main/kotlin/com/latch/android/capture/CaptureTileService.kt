package com.latch.android.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * FR-213: tapping the tile brings the capture window to the foreground and reads the
 * clipboard. This is the supported path for apps — WhatsApp is the one the SRS names — where
 * partial text selection never offers the toolbar action (§8.3).
 *
 * The tile itself reads nothing. FR-214 and the Android 10 restriction mean the clipboard can
 * only be read once [CaptureActivity] has focus, so all this does is start it.
 */
class CaptureTileService : TileService() {

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_READ_CLIPBOARD, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            // The Intent overload is the only one that exists below API 34, and minSdk is 26.
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
