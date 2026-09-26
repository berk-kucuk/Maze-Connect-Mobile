package com.mazeconnect.core.device

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock

/**
 * Reads [PhoneReading] from the platform.
 *
 * Every probe is wrapped on its own: one that throws (a vendor build that
 * refuses a system service, a StatFs on an odd mount) costs that one field,
 * never the whole reading. And nothing here needs a runtime permission — the
 * battery comes from the sticky broadcast, the rest from services any app may
 * query.
 */
class PhoneStatusCollector(context: Context) {

    private val app = context.applicationContext

    fun collect(): PhoneReading {
        val battery = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        return PhoneReading(
            batteryLevel = battery?.let(::batteryPercent),
            charging = battery?.let {
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            },
            plug = battery?.let {
                when (it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
                    0 -> "none"
                    else -> null
                }
            },
            batteryTenthsC = battery
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            batteryHealth = battery?.let {
                when (it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "overvoltage"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                    else -> "unknown"
                }
            },
            storageFreeBytes = storage?.first,
            storageTotalBytes = storage?.second,
            memoryAvailableBytes = memory?.first,
            memoryTotalBytes = memory?.second,
            network = network?.type,
            signal = network?.signal,
            metered = network?.metered,
            ringer = runCatching {
                when ((app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    AudioManager.RINGER_MODE_NORMAL -> "normal"
                    else -> null
                }
            }.getOrNull(),
            doNotDisturb = runCatching {
                app.getSystemService(NotificationManager::class.java).currentInterruptionFilter !=
                    NotificationManager.INTERRUPTION_FILTER_ALL
            }.getOrNull(),
            powerSave = runCatching {
                app.getSystemService(PowerManager::class.java).isPowerSaveMode
            }.getOrNull(),
            screenOn = runCatching {
                app.getSystemService(PowerManager::class.java).isInteractive
            }.getOrNull(),
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidRelease = Build.VERSION.RELEASE,
            uptimeMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun batteryPercent(intent: Intent): Int? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }

    /** Internal storage the user's files live on: free, total. */
    private val storage: Pair<Long, Long>?
        get() = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes to stat.totalBytes
        }.getOrNull()

    private val memory: Pair<Long, Long>?
        get() = runCatching {
            val info = ActivityManager.MemoryInfo()
            app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            info.availMem to info.totalMem
        }.getOrNull()

    private data class Net(val type: String, val signal: Int?, val metered: Boolean?)

    private val network: Net?
        get() = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val active = cm.activeNetwork ?: return@runCatching Net("none", null, null)
            val caps = cm.getNetworkCapabilities(active)
                ?: return@runCatching Net("none", null, null)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "none"
            }
            // RSSI from the network's own capabilities: no location permission
            // and no Wi-Fi scan, just the strength of the link already up.
            val signal = if (type == "wifi" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
                    ?.let(::bars)
            } else {
                null
            }
            Net(
                type = type,
                signal = signal,
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }.getOrNull()

    companion object {
        /** dBm to 0-4 bars, on the thresholds Android's own status bar uses
         *  closely enough that the two will not disagree by more than one. */
        fun bars(rssi: Int): Int = when {
            rssi >= -55 -> 4
            rssi >= -66 -> 3
            rssi >= -77 -> 2
            rssi >= -88 -> 1
            else -> 0
        }
    }
}
