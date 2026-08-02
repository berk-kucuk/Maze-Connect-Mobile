package com.mazeconnect.app.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mazeconnect.app.R

/**
 * The non-URL half of "open on phone": copies the computer's clipboard text
 * to this phone's clipboard.
 *
 * Only ever reached by tapping the notification [MazeConnectService] posts —
 * see that class for why this is a broadcast rather than an activity. A
 * broadcast receiver carries no background-start restriction either way, so
 * there is nothing this needs to work around.
 */
class OpenOnPhoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        Toast.makeText(context, context.getString(R.string.open_on_phone_copied), Toast.LENGTH_SHORT)
            .show()
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
