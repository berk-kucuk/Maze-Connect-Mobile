package com.mazeconnect.app.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The device-admin component that lets a paired computer lock this phone.
 *
 * It asks for exactly one policy, `force-lock` (res/xml/lock_admin.xml): it
 * can turn the screen off and lock it, and nothing else — it cannot wipe,
 * change the PIN, read anything or keep itself installed once the owner
 * switches it off. Activated only when the owner turns the switch on in
 * Settings, and deactivated again when they turn it off.
 */
class LockAdminReceiver : DeviceAdminReceiver()

object PhoneLock {
    fun component(context: Context) = ComponentName(context, LockAdminReceiver::class.java)

    fun isActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isAdminActive(component(context)) == true

    /** The system screen that asks the owner to allow locking. */
    fun activationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets a computer you paired lock this phone's screen when you ask it to. " +
                    "That is all it can do.",
            )

    fun deactivate(context: Context) {
        runCatching {
            context.getSystemService(DevicePolicyManager::class.java)
                ?.removeActiveAdmin(component(context))
        }
    }

    /** Lock now; null once locked, or why not. */
    fun lock(context: Context): String? {
        if (!PhonePrefs.allowLock(context)) return "locking is switched off on this phone"
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return "this phone cannot be locked remotely"
        if (!dpm.isAdminActive(component(context))) {
            return "this phone has not given Maze Connect permission to lock it"
        }
        dpm.lockNow()
        return null
    }
}
