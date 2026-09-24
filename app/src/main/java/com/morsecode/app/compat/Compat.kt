/*
 * Compiled against android-34 on purpose: this file exists to wrap APIs that do not exist on
 * the API-23 floor. Every call site inside it is guarded by a Build.VERSION check, and the rest
 * of the app only ever talks to the wrapper. It lives in `compat/` because tools/offline_build.py
 * compiles that directory against the newest platform jar and everything else against API 23,
 * which turns "accidentally used a modern API" into a build error.
 */
@file:Suppress("DEPRECATION")

package com.morsecode.app.core.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Every API above the API-23 floor is funnelled through this file so the rest of the app
 * can pretend Android 6 is the only version that exists.
 */
object Compat {

    val isApi23 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    val isApi24 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    val isApi26 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    val isApi28 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val isApi29 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val isApi30 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val isApi31 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isApi33 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // ---- notifications -----------------------------------------------------

    /**
     * Starts a foreground-capable service the API-correct way: `startForegroundService` needs
     * API 26, which the main sources cannot reference because they compile against android-23.
     * The fallback keeps a service start from ever crashing on an unusual OEM build.
     */
    fun startServiceCompat(ctx: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        } catch (t: Throwable) {
            try {
                ctx.startService(intent)
            } catch (ignored: Throwable) {
            }
        }
    }

    fun ensureChannel(ctx: Context, id: String, name: String) {
        if (!isApi26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    fun buildNotification(ctx: Context, channel: String, title: String, text: String, smallIcon: Int): Notification {
        val b = if (isApi26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)
        return b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun notify(ctx: Context, id: Int, n: Notification) {
        if (!isApi33 || checkSelf(ctx, "android.permission.POST_NOTIFICATIONS")) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        }
    }

    fun cancel(ctx: Context, id: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id)
    }

    // ---- window / display --------------------------------------------------

    fun keepScreenOn(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun edgeToEdge(activity: Activity) {
        if (!isApi21CompatibleNotch()) return
        // Nothing to do: the app draws its own status bar padding and never uses insets APIs
        // that are unavailable on API 23.
    }

    private fun isApi21CompatibleNotch() = false

    fun immersive(activity: Activity) {
        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = flags
    }

    // ---- network -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun isWifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            if (isApi23) {
                val n = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(n) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                val ni = cm.activeNetworkInfo
                ni != null && ni.isConnected && ni.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (t: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            if (isApi23) {
                val n = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(n) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun ssid(ctx: Context): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            @Suppress("DEPRECATION")
            var s = info?.ssid ?: ""
            if (s.startsWith("\"") && s.endsWith("\"") && s.length > 1) s = s.substring(1, s.length - 1)
            if (s == "<unknown ssid>" || s.isBlank()) "Wi-Fi" else s
        } catch (t: Throwable) {
            "Wi-Fi"
        }
    }

    fun rssi(ctx: Context): Int = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.connectionInfo?.rssi ?: 0
    } catch (t: Throwable) {
        0
    }

    // ---- permissions -------------------------------------------------------

    fun checkSelf(ctx: Context, permission: String): Boolean =
        if (!isApi23) true else ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasAllFilesAccess(ctx: Context): Boolean = when {
        isApi30 -> Environment.isExternalStorageManager()
        isApi23 -> checkSelf(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> true
    }

    /** The runtime grants that open the media library from Android 13 onwards. */
    private val MEDIA_GRANTS_33 = listOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    )

    /**
     * Can the app read the media library? This is the capability the Files tab needs, and it is
     * deliberately **not** [hasAllFilesAccess].
     *
     * From Android 13 the documented way in is the runtime `READ_MEDIA_*` family; all-files access
     * is a separate, Play-restricted privilege for browsing *non*-media files. Using the second as
     * the only gate made the Files tab announce "Storage permission is needed to read your files"
     * on a phone where the media grants were already in place.
     */
    fun canReadMedia(ctx: Context): Boolean = when {
        hasAllFilesAccess(ctx) -> true
        isApi33 -> MEDIA_GRANTS_33.any { checkSelf(ctx, it) }
        isApi23 -> checkSelf(ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> true
    }

    /** Android 11+ can hand out all-files access; older versions go through the app settings. */
    fun allFilesSettingsAvailable(ctx: Context): Boolean = true

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (!isApi23) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    // ---- intents -----------------------------------------------------------

    fun viewIntent(ctx: Context, uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(ctx: Context, uris: List<Uri>, mime: String): Intent {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(if (mime.isBlank()) "*/*" else mime)
                .putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(if (mime.isBlank()) "*/*" else mime)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        return Intent.createChooser(send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
    }

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun openAppSettings(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (ignored: Throwable) {
        }
    }

    fun openAllFilesSettings(ctx: Context) {
        if (!isApi30) {
            openAppSettings(ctx); return
        }
        try {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ignored: Throwable) {
                openAppSettings(ctx)
            }
        }
    }

    fun openNotificationsSettings(ctx: Context, channelId: String?) {
        try {
            val i = if (isApi26 && channelId != null) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", ctx.packageName, null))
            }
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: Throwable) {
        }
    }
}
