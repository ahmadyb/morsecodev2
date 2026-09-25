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
import android.provider.MediaStore
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
    val isApi34 get() = Build.VERSION.SDK_INT >= 34

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

    fun buildNotification(ctx: Context, channel: String, title: String, text: String, smallIcon: Int): Notification =
        buildNotification(ctx, channel, title, text, smallIcon, null, null)

    /**
     * The same notification, optionally with one action button.
     *
     * The transfer service's notification is the app's face once the screen is gone, and an ongoing
     * notification the user cannot dismiss - with no way to stop the service behind it - is a trap:
     * the only escape was force-stopping the app from Settings. It now carries its own Stop.
     */
    fun buildNotification(
        ctx: Context,
        channel: String,
        title: String,
        text: String,
        smallIcon: Int,
        actionTitle: String?,
        actionIntent: Intent?
    ): Notification {
        val b = if (isApi26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)
        b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (actionTitle != null && actionIntent != null) {
            val flags = if (isApi23) android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val pi = android.app.PendingIntent.getService(ctx, 9001, actionIntent, flags)
            b.addAction(Notification.Action.Builder(null, actionTitle, pi).build())
        }
        return b.build()
    }

    /** Show a share sheet for plain text (used by the log viewer's "copy as text" fallback). */
    fun shareText(ctx: Context, title: String, text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, text)
            ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
        }
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
    /**
     * Is there a Wi-Fi network this phone can talk over?
     *
     * This used to ask only about the *active* network, which is the one Android routes default
     * traffic through. A phone joined to a PC's hotspot that has no internet keeps mobile data as
     * the default route, so the active network is cellular while Wi-Fi is connected perfectly well -
     * and the app told the user to "connect to Wi-Fi or hotspot first" while they were sitting on
     * one. Every network is checked now, and a Wi-Fi network that is merely unvalidated (no
     * internet) counts, because file transfer never needs the internet.
     */
    fun isWifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            try {
                if (isApi23) {
                    for (n in cm.allNetworks) {
                        val caps = cm.getNetworkCapabilities(n) ?: continue
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        ) {
                            return true
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val nets = cm.allNetworkInfo
                    if (nets != null) {
                        for (ni in nets) {
                            if (ni != null && ni.isConnected &&
                                (ni.type == ConnectivityManager.TYPE_WIFI ||
                                    ni.type == ConnectivityManager.TYPE_ETHERNET)
                            ) {
                                return true
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // fall through to the Wi-Fi manager, which knows about joined networks too
            }
        }
        // Last resort, and the one that works on a brand new hotspot: the radio is on and joined to
        // something. `NetworkCapabilities` is empty for a moment right after connecting.
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm != null && wm.isWifiEnabled
        } catch (t: Throwable) {
            false
        }
    }

    /** Any usable link at all: Wi-Fi, ethernet, or mobile data. */
    fun hasAnyNetwork(ctx: Context): Boolean {
        if (isWifiConnected(ctx)) return true
        return try {
            isOnline(ctx)
        } catch (t: Throwable) {
            false
        }
    }

    // ---- file locations ----------------------------------------------------

    /** The shared storage root: `/storage/emulated/0` on a normal phone. */
    fun externalRoot(): java.io.File? = try {
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            Environment.getExternalStorageDirectory()
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    /** A public folder such as Download. Null when storage is not mounted. */
    fun publicDir(name: String): java.io.File? = try {
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            Environment.getExternalStoragePublicDirectory(name)
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * The path behind a document-tree URI, when the storage provider is the normal one.
     *
     * SAF trees are the only way to reach folders the app has no blanket access to, and the picker
     * hands back an opaque `content://` id. The Files tab needs a directory to walk, and for the
     * primary volume that id contains the path verbatim (`.../tree/primary%3ADownload`), which is
     * all this decodes. Anything else (a cloud provider, an SD card) returns null and is listed
     * without a path.
     */
    fun pathFromTreeUri(value: String): java.io.File? = try {
        val decoded = java.net.URLDecoder.decode(value, "UTF-8")
        val marker = ":"
        val idx = decoded.indexOf("/tree/")
        if (idx < 0) null else {
            val id = decoded.substring(idx + "/tree/".length).substringBefore('/')
            val colon = id.indexOf(marker)
            if (colon < 0) null else {
                val volume = id.substring(0, colon)
                val path = id.substring(colon + 1)
                val root = externalRoot()
                if (volume == "primary" && root != null) java.io.File(root, path) else null
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** Bluetooth, for the Nearby path. */
    fun isBluetoothEnabled(ctx: Context): Boolean = try {
        val a = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        a != null && a.isEnabled
    } catch (t: Throwable) {
        false
    }

    /**
     * Ask the system to turn the radio on.
     *
     * Apps have not been allowed to flip Wi-Fi themselves since Android 10, so the honest move is
     * the system's own panel (a one-tap switch the user is already looking at); Bluetooth still
     * supports the classic enable request, which shows a dialog. On Android 9 and older both can be
     * set directly.
     */
    fun requestWifi(activity: Activity) {
        try {
            if (isApi29) {
                activity.startActivity(Intent("android.settings.panel.action.WIFI")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else if (isApi23) {
                @Suppress("DEPRECATION")
                val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = true
                toastTip(activity, "Turning Wi-Fi on\u2026")
            }
        } catch (t: Throwable) {
            try {
                activity.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: Throwable) {
            }
        }
    }

    /** Take down a foreground service's notification, on any API level. */
    fun stopForeground(service: android.app.Service) {
        try {
            if (isApi24) {
                service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service.stopForeground(true)
            }
        } catch (t: Throwable) {
        }
    }

    fun requestBluetooth(activity: Activity) {
        try {
            val a = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (a != null && !a.isEnabled) {
                activity.startActivityForResult(
                    Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 4242
                )
            }
        } catch (t: Throwable) {
        }
    }

    private fun toastTip(activity: Activity, message: String) {
        try {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
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

    /**
     * The full media grant, i.e. the app may see *every* photo, video and track.
     *
     * Android 14 offers a middle state: the user picks a handful of pictures and the app is left
     * holding only `READ_MEDIA_VISUAL_USER_SELECTED`. [canReadMedia] is true in that state - the app
     * really can read media - but MediaStore then filters every query down to the selected items,
     * so a library that looks empty is the platform doing its job rather than a bug. The two
     * questions ("may I read media at all" and "may I read all of it") are different, so they are
     * asked separately.
     */
    fun hasFullMediaAccess(ctx: Context): Boolean = when {
        hasAllFilesAccess(ctx) -> true
        isApi33 -> checkSelf(ctx, "android.permission.READ_MEDIA_IMAGES") ||
            checkSelf(ctx, "android.permission.READ_MEDIA_VIDEO")
        isApi23 -> checkSelf(ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> true
    }

    /** True when Android 14 gave the app only the user-selected subset of the library. */
    fun hasPartialMediaAccess(ctx: Context): Boolean =
        isApi34 && !hasFullMediaAccess(ctx) &&
            checkSelf(ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

    /**
     * The MediaStore volumes this device actually has. "external" is the legacy name for the
     * primary volume and is what the library queries; on Android 10+ the real names are
     * `external_primary` and the SD card's UUID, and seeing them in a bug report is the difference
     * between "the phone has no media" and "the app is asking the wrong volume".
     */
    fun externalVolumes(ctx: Context): List<String> = try {
        if (isApi29) MediaStore.getExternalVolumeNames(ctx).toList().sorted()
        else listOf("external")
    } catch (t: Throwable) {
        emptyList()
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
