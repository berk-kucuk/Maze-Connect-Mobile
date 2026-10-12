package com.mazeconnect.app.service

import android.content.Context
import androidx.core.content.edit
import com.mazeconnect.core.DeviceManager

/**
 * The phone owner's own switches over what paired computers may do *to this
 * phone*: read its status, and make it ring.
 *
 * Both default on — pairing is the decision, as everywhere else in the app —
 * and both are refused out loud when off, so a computer's dashboard can say
 * "sharing is off on the phone" rather than wait forever.
 */
object PhonePrefs {
    private const val FILE = "phone_prefs"
    private const val KEY_SHARE_STATUS = "share_status"
    private const val KEY_ALLOW_RING = "allow_ring"
    private const val KEY_CLIPBOARD_SYNC = "clipboard_sync"
    private const val KEY_ALLOW_LOCK = "allow_lock"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun shareStatus(context: Context): Boolean = prefs(context).getBoolean(KEY_SHARE_STATUS, true)

    fun allowRing(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_RING, true)

    fun setShareStatus(context: Context, value: Boolean, manager: DeviceManager?) {
        prefs(context).edit { putBoolean(KEY_SHARE_STATUS, value) }
        manager?.phoneStatusSharing = value
    }

    fun setAllowRing(context: Context, value: Boolean, manager: DeviceManager?) {
        prefs(context).edit { putBoolean(KEY_ALLOW_RING, value) }
        manager?.findPhoneAllowed = value
        if (!value) FindPhoneRinger.stop(context)
    }

    /** Clipboard sync: off until the owner turns it on. */
    fun clipboardSync(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLIPBOARD_SYNC, false)

    fun setClipboardSync(context: Context, value: Boolean, manager: DeviceManager?) {
        prefs(context).edit { putBoolean(KEY_CLIPBOARD_SYNC, value) }
        manager?.clipboardSyncEnabled = value
    }

    /**
     * Whether a paired computer may lock this phone. Off until the owner
     * turns it on — and turning it on also needs the system's device-admin
     * consent, which [PhoneLock] asks for.
     */
    fun allowLock(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_LOCK, false)

    fun setAllowLock(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_ALLOW_LOCK, value) }
        // Off means off: the admin grant goes too, so nothing is left behind
        // that would stand in the way of uninstalling the app.
        if (!value) PhoneLock.deactivate(context)
    }

    /** Copy the stored switches onto a freshly created manager. */
    fun apply(context: Context, manager: DeviceManager) {
        manager.phoneStatusSharing = shareStatus(context)
        manager.findPhoneAllowed = allowRing(context)
        manager.clipboardSyncEnabled = clipboardSync(context)
        val app = context.applicationContext
        manager.phoneLocker = { _ -> PhoneLock.lock(app) }
        manager.setApprovalKey(com.mazeconnect.app.security.ApprovalKeys.publicKey())
    }
}
