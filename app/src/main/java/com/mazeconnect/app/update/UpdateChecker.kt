package com.mazeconnect.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.mazeconnect.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What the app knows about a newer build, for the Settings line. */
data class UpdateStatus(
    val checking: Boolean = false,
    val versionName: String? = null,
    val versionCode: Long = 0,
    /** Where to get it. Null when the manifest offered no usable link — see
     *  [hostOf] for what "usable" means here. */
    val downloadUrl: String? = null,
    val notes: String? = null,
    val lastCheckedMs: Long = 0,
    val error: String? = null,
    /** Decided in [UpdateChecker], which is the only place that holds both
     *  sides of the comparison. */
    val newer: Boolean = false,
)

/**
 * Asks a small JSON file whether a newer build exists. Nothing more.
 *
 * **This never downloads and never installs.** It reports a version number
 * and, at most, offers a link the user taps. Keeping it to that is what lets
 * the feature stay small: there is no package handling, no installer intent,
 * no permission to request, and nothing here that can run code.
 *
 * Transport is https to a public name, so the platform's certificate
 * validation applies and `network_security_config.xml` keeps its "no
 * cleartext, no exceptions" rule untouched. (An http endpoint would have
 * needed a scoped exception and would have made every field below
 * attacker-controlled on any hostile network; it is worth keeping this on
 * https for exactly that reason.)
 *
 * The reply is still treated as untrusted input, on the same principle the
 * rest of this codebase applies to anything arriving over a socket:
 *  * the body is read with a hard cap and every field is length- and
 *    control-character-bounded before it can reach a screen;
 *  * redirects are not followed, so the endpoint cannot bounce the request
 *    somewhere else;
 *  * the download link is accepted **only if its host matches the configured
 *    host**, so a compromised manifest cannot point the user at an unrelated
 *    server;
 *  * nothing about the device is sent — no query string, no cookies, no
 *    device id. The one header set is a fixed User-Agent naming the app and
 *    its version, which replaces a platform default that would otherwise
 *    have carried the phone's model and build id.
 */
object UpdateChecker {

    /**
     * Where the version manifest lives.
     *
     * Must stay https: the app forbids cleartext outright, so an http URL
     * here is refused by the platform before the request leaves the process.
     * That is the intended behaviour, not an obstacle to work around.
     */
    const val MANIFEST_URL = "https://mazelinux.berkkucukk.com.tr/api/maze-connect/latest"

    /**
     * Where the packages live — deliberately separate from [MANIFEST_URL].
     *
     * The manifest names a bare filename ("maze-connect-0.10.7.apk"), and the
     * obvious reading of that is "relative to the manifest". It used to be:
     * the manifest sat in the same directory as the APKs. It no longer does —
     * the manifest moved to /api/maze-connect/ while the packages stayed put —
     * so resolving against the manifest now yields a 404, and a Download
     * button that reliably leads nowhere is worse than no button at all.
     *
     * Both constants must name the same host, or the host check below
     * discards the resulting link.
     */
    const val DOWNLOAD_BASE_URL = "https://mazelinux.berkkucukk.com.tr/maze-connect-apk/"

    private const val TAG = "MazeUpdate"
    private const val PREFS = "maze-update"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_CHECK = "lastCheckMs"
    private const val KEY_NOTIFIED_CODE = "notifiedVersionCode"

    private const val CHANNEL_ID = "maze-connect-updates"
    private const val NOTIFICATION_ID = 4

    /** Untrusted body: read no more than this, whatever the server claims. */
    private const val MAX_BODY_BYTES = 8 * 1024
    private const val MAX_VERSION_NAME_CHARS = 32
    private const val MAX_URL_CHARS = 512
    private const val MAX_NOTES_CHARS = 300

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /** At most one automatic check a day. A reminder is not news. */
    private const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /** The versionCode of the build actually running. */
    fun installedVersionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /** Its versionName. Read from the package rather than BuildConfig so the
     *  two can never disagree, and so this module needs no build-time
     *  generated class. */
    fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    /**
     * The automatic path: honours the switch and the once-a-day interval.
     * Returns null when it decided not to look.
     */
    suspend fun checkIfDue(context: Context): UpdateStatus? {
        if (!isEnabled(context)) return null
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return null
        return check(context, notify = true)
    }

    /**
     * The manual path: the Settings button. Ignores the interval — a person
     * pressing "check now" is entitled to an answer now — but still posts no
     * notification, because they are already looking at the result.
     */
    suspend fun checkNow(context: Context): UpdateStatus = check(context, notify = false)

    private suspend fun check(context: Context, notify: Boolean): UpdateStatus =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            prefs(context).edit { putLong(KEY_LAST_CHECK, now) }

            val body = runCatching {
                fetch(MANIFEST_URL, installedVersionName(context))
            }.getOrElse {
                Log.i(TAG, "update check failed: ${it.message}")
                return@withContext UpdateStatus(
                    lastCheckedMs = now,
                    // Named plainly rather than dressed up: no connectivity is
                    // the overwhelmingly likely cause and is not something to
                    // alarm anyone about.
                    error = context.getString(R.string.update_unreachable),
                )
            }

            val status = runCatching {
                parse(body, now, installedVersionCode(context), installedVersionName(context))
            }.getOrElse {
                return@withContext UpdateStatus(
                    lastCheckedMs = now,
                    error = context.getString(R.string.update_unreadable),
                )
            }

            if (notify && status.newer) notifyOnce(context, status)
            status
        }

    private fun fetch(url: String, versionName: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        // A stated User-Agent, rather than whatever the platform defaults to.
        //
        // The endpoint sits behind a filter that answers 403 to user agents it
        // does not like — Python's default is refused outright today. Android's
        // default ("Dalvik/2.1.0 (Linux; U; Android 14; SM-…)") is accepted at
        // the moment, but relying on that means one filter-rule change turns
        // this feature off silently and forever, with nothing on either side
        // to say why. A name the server operator can allow-list on purpose is
        // the difference between a dependency and a coincidence.
        //
        // It also stops the platform default from going out, and that default
        // carries the device model and build id. This one carries the app
        // version and nothing else — which is the only fact the server needs
        // in order to answer, and says nothing about the phone.
        connection.setRequestProperty("User-Agent", "MazeConnect/$versionName (Android)")
        // No query string, no cookies, no device id, no redirects.
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val buffer = ByteArray(MAX_BODY_BYTES)
            var read = 0
            connection.inputStream.use { stream ->
                while (read < MAX_BODY_BYTES) {
                    val n = stream.read(buffer, read, MAX_BODY_BYTES - read)
                    if (n < 0) break
                    read += n
                }
            }
            return String(buffer, 0, read, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads the manifest that is already published, and a stricter one if it
     * ever appears.
     *
     * The file in production today is two fields:
     *
     *     { "file": "maze-connect-0.10.2.apk", "version": "0.10.2" }
     *
     * so `version`/`file` are the supported shape, and `versionName`/
     * `versionCode`/`url` are accepted as the more precise alternatives.
     * `versionCode` is preferred whenever present because it is the number
     * Android itself orders installs by, and it removes every question a
     * name string can raise; without it the names are compared numerically
     * component by component.
     *
     * `file` is resolved against [DOWNLOAD_BASE_URL] — not the manifest's own
     * directory, which is no longer where the packages are. Both are
     * constants naming the same host, so a resolved link cannot point
     * off-host; an explicit `url` is checked against that host directly.
     */
    private fun parse(body: String, now: Long, installedCode: Long, installedName: String):
        UpdateStatus {
        val obj = JSONObject(body)

        val name = bounded(obj, "versionName", MAX_VERSION_NAME_CHARS)
            ?: bounded(obj, "version", MAX_VERSION_NAME_CHARS)
        val code = obj.optLong("versionCode", 0L).coerceAtLeast(0L)
        if (code == 0L && name == null) {
            throw IllegalArgumentException("manifest carries no version")
        }

        val notes = bounded(obj, "notes", MAX_NOTES_CHARS)

        val explicit = bounded(obj, "url", MAX_URL_CHARS)
            ?.takeIf { hostOf(it) == hostOf(MANIFEST_URL) }
        val relative = bounded(obj, "file", MAX_URL_CHARS)
            ?.let { runCatching { URL(URL(DOWNLOAD_BASE_URL), it).toString() }.getOrNull() }
            ?.takeIf { hostOf(it) == hostOf(MANIFEST_URL) }
        val url = explicit ?: relative

        val newer = when {
            code > 0L && installedCode > 0L -> code > installedCode
            name != null -> compareVersions(name, installedName) > 0
            else -> false
        }

        return UpdateStatus(
            versionName = name,
            versionCode = code,
            downloadUrl = url,
            notes = notes,
            lastCheckedMs = now,
            newer = newer,
        )
    }

    /** A bounded, control-character-free string field, or null. Same rule the
     *  protocol applies to anything a peer sends. */
    private fun bounded(obj: JSONObject, key: String, maxChars: Int): String? =
        obj.optString(key).takeIf {
            it.isNotEmpty() && it.length <= maxChars && it.none(::isControl)
        }

    /**
     * Compare dotted version names numerically.
     *
     * Plain string ordering gets this backwards at exactly the moment it
     * starts to matter: "0.10.10" sorts *below* "0.10.2" as text, so an app
     * on 0.10.9 would be told it was up to date. Each component is compared
     * as a number, missing components count as zero, and any non-numeric
     * suffix ("0.11.0-beta1") is ignored rather than rejected.
     */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(i)?.takeWhile(Char::isDigit)?.toLongOrNull() ?: 0L
            val r = right.getOrNull(i)?.takeWhile(Char::isDigit)?.toLongOrNull() ?: 0L
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun isControl(c: Char): Boolean =
        c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F

    /** Host only, so a reply cannot point the user at another server. Null on
     *  anything unparseable, which then fails the comparison. */
    private fun hostOf(url: String): String? = runCatching { URL(url).host }.getOrNull()

    /**
     * One notification per version, ever.
     *
     * A reminder that returns every day for a version the user has already
     * decided not to install is not a reminder, it is nagging — and the
     * Settings line is there for anyone who wants to look again.
     */
    private fun notifyOnce(context: Context, status: UpdateStatus) {
        val prefs = prefs(context)
        if (prefs.getLong(KEY_NOTIFIED_CODE, 0L) >= status.versionCode) return
        prefs.edit { putLong(KEY_NOTIFIED_CODE, status.versionCode) }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.update_channel_description)
                setShowBadge(false)
            }
        )

        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(
                context.getString(
                    R.string.update_notification_text,
                    status.versionName ?: status.versionCode.toString(),
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
