package com.mazeconnect.app.service

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.mazeconnect.core.DeviceManager

/**
 * This phone's half of clipboard sync.
 *
 * Android lets an app **write** the clipboard at any time but **read** it
 * only while one of its windows has focus. So a computer's clipboard lands
 * here immediately, while this phone's own goes out when Maze Connect is
 * opened (and from the tile or the Share menu). That is a platform rule, not
 * a missing feature — and a system that let any app read the clipboard in
 * the background would be worse than this.
 *
 * What is never sent: text the copying app marked sensitive (password
 * managers do, on Android 13+), and the text a computer just sent, which
 * would otherwise bounce straight back.
 */
object ClipboardBridge {

    @Volatile private var lastRemote: String? = null
    @Volatile private var lastSent: String? = null

    /** Put a computer's clipboard on this phone. Marked sensitive-free on
     *  purpose: it came from the owner's own computer and is meant to be
     *  pasted, so it shows in the keyboard's clipboard strip as usual. */
    fun receive(context: Context, text: String) {
        lastRemote = text
        runCatching {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Maze Connect", text))
        }
    }

    /** Called when an app window gains focus: send what is on the clipboard
     *  now, if it is new, ordinary text. */
    fun sendIfChanged(context: Context, manager: DeviceManager?) {
        manager ?: return
        if (!manager.clipboardSyncEnabled) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0 || isSensitive(clip.description)) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text == lastRemote || text == lastSent) return
        if (manager.sendClipboard(text) > 0) lastSent = text
    }

    private fun isSensitive(description: ClipDescription?): Boolean {
        val extras: PersistableBundle = description?.extras ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
        } else {
            // The same key older password managers already set.
            extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
        }
    }
}
