package com.mazeconnect.app.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.mazeconnect.core.DeviceManager
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /**
     * The last line of defence against the link killing the app.
     *
     * A SupervisorJob keeps one failed child from cancelling its siblings,
     * but it does nothing about the exception itself: without a handler in
     * the context, an uncaught throw in any coroutine launched on this scope
     * goes to the thread's default handler, which on Android means the
     * process dies. Every one of those coroutines is a background socket,
     * timer, or message handler that runs for days with no screen attached,
     * so the failure mode is the app vanishing with nothing to show for it.
     *
     * Logged rather than swallowed silently — this is a backstop for the
     * unforeseen, not a licence to stop handling errors where they happen.
     */
    private val crashGuard = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "uncaught failure in the link scope", throwable)
    }

    /** The manager, creating it on first use. Safe from any thread. */
    @Synchronized
    fun manager(context: Context): DeviceManager {
        manager?.let { return it }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashGuard)
        val created = DeviceManager(context.applicationContext, newScope)
        // The owner's switches, before anything can ask: a status request
        // arriving in the first second must already see "sharing is off".
        PhonePrefs.apply(context, created)
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

    private const val TAG = "MazeLink"
}
