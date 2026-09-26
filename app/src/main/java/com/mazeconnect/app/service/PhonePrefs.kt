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

    /** Copy the stored switches onto a freshly created manager. */
    fun apply(context: Context, manager: DeviceManager) {
        manager.phoneStatusSharing = shareStatus(context)
        manager.findPhoneAllowed = allowRing(context)
    }
}
