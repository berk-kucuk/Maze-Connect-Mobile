package com.mazeconnect.app.service

import android.content.Context
import android.os.Build
import com.mazeconnect.core.DeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The one [DeviceManager] for the process, and the reason it outlives any screen.
 *
 * It used to be created by the ViewModel and cancelled in `onCleared()`, which
 * meant the link died with the Activity: the foreground notification stayed up
 * claiming a connection that had already been torn down, the beacon stopped, and
 * the listening socket closed. Two reported faults were the same fault —
 *
 *  * the widget could not reach the computer, because nothing was connected once
 *    the app left the screen;
 *  * pairing from the computer failed, because a phone that has stopped
 *    announcing itself and stopped listening cannot be discovered or dialled.
 *
 * So ownership belongs to the process, not to a screen. The scope here is never
 * cancelled by UI lifecycle; only [shutdown], called when the service is
 * destroyed — which is the point at which the user really has stopped the link.
 *
 * Not a leak: the manager holds the *application* context, and its lifetime is
 * exactly the lifetime of the foreground service the user can see and stop.
 */
object Link {

    private var scope: CoroutineScope? = null
    private var manager: DeviceManager? = null
    private var started = false

    /** The manager, creating it on first use. Safe from any thread. */
    @Synchronized
    fun manager(context: Context): DeviceManager {
        manager?.let { return it }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val created = DeviceManager(context.applicationContext, newScope)
        scope = newScope
        manager = created
        return created
    }

    /**
     * Bring the link up if it is not already.
     *
     * Idempotent on purpose: this is called both from the first screen and from
     * the service, and the service may be started by a widget tap with no screen
     * involved at all. Starting twice would open a second listening socket and
     * announce two ports for one device.
     *
     * @return false only when the identity could not be established.
     */
    @Synchronized
    fun ensureStarted(context: Context, deviceName: String = defaultName()): Boolean {
        if (started) return true
        started = manager(context).start(deviceName)
        return started
    }

    @Synchronized
    fun isStarted(): Boolean = started

    /** Called only from the service's onDestroy. */
    @Synchronized
    fun shutdown() {
        manager?.stop()
        scope?.cancel()
        manager = null
        scope = null
        started = false
    }

    private fun defaultName(): String = Build.MODEL ?: "Android device"
}
