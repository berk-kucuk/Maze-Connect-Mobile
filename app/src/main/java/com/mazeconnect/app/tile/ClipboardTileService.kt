package com.mazeconnect.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.mazeconnect.app.share.ClipboardSendActivity

/**
 * Quick Settings: "Send clipboard to computer".
 *
 * A tile runs in the background, where Android refuses clipboard reads, so a
 * tap opens [ClipboardSendActivity] — which reads the clipboard once it has
 * focus and asks before sending, with the text in view. The tile itself
 * reads nothing and sends nothing.
 */
class ClipboardTileService : TileService() {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardSendActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
