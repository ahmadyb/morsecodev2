/*
 * Compiled against android-34 on purpose: this file exists to wrap APIs that do not exist on
 * the API-23 floor. Every call site inside it is guarded by a Build.VERSION check, and the rest
 * of the app only ever talks to the wrapper. It lives in `compat/` because tools/offline_build.py
 * compiles that directory against the newest platform jar and everything else against API 23,
 * which turns "accidentally used a modern API" into a build error.
 */
package com.morsecode.app.core.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime permission helper. It only ever asks for permissions that are declared in the
 * manifest for the running API level - on Android 6 that means the 2016-era set, never
 * the modern split permissions.
 */
object Permissions {

    const val REQ_NEARBY = 9101
    const val REQ_STORAGE = 9102
    const val REQ_NOTIFICATIONS = 9103
    const val REQ_CAMERA = 9104

    /**
     * The Bluetooth grants that apply at this API level.
     *
     * These are two different permission models, not two names for the same thing. Android 12
     * replaced BLUETOOTH/BLUETOOTH_ADMIN with the runtime BLUETOOTH_SCAN/ADVERTISE/CONNECT trio,
     * and this app's manifest caps the two legacy ones at API 30 - on a modern phone they are not
     * installed at all, so asking the system whether BLUETOOTH is granted answers "denied" on a
     * device where Bluetooth works perfectly. That is the answer the Nearby transport used to act
     * on: it refused to start on every Android 12+ phone, and the receiving device never appeared
     * in the sender's list.
     */
    fun bluetooth(ctx: Context): List<String> =
        if (Compat.isApi31) listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        ) else listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )

    fun bluetoothGranted(ctx: Context): Boolean = granted(ctx, bluetooth(ctx))

    /** Permissions the Nearby transport needs on this API level. */
    fun nearby(ctx: Context): List<String> {
        val out = ArrayList(bluetooth(ctx))
        // Below Android 12 a discovery result carries location-adjacent information, so the
        // platform makes it a location permission. From 12 the Bluetooth trio replaces it (our
        // scan declaration carries neverForLocation).
        if (!Compat.isApi31) out.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Compat.isApi33) out.add("android.permission.NEARBY_WIFI_DEVICES")
        return out
    }

    /** Read access to the media library at this API level. */
    fun storage(ctx: Context): List<String> {
        val out = ArrayList<String>()
        when {
            Compat.isApi33 -> {
                out.add(Manifest.permission.READ_MEDIA_IMAGES)
                out.add(Manifest.permission.READ_MEDIA_VIDEO)
                out.add(Manifest.permission.READ_MEDIA_AUDIO)
                out.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            }
            Compat.isApi29 -> out.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> {
                out.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                out.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        return out
    }

    fun notifications(): List<String> =
        if (Compat.isApi33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun camera(): List<String> = listOf(Manifest.permission.CAMERA)

    fun granted(ctx: Context, list: List<String>): Boolean = list.all { Compat.checkSelf(ctx, it) }

    fun missing(ctx: Context, list: List<String>): List<String> = list.filterNot { Compat.checkSelf(ctx, it) }

    fun request(activity: Activity, list: List<String>, code: Int) {
        val missing = missing(activity, list)
        if (missing.isEmpty()) return
        if (!Compat.isApi23) return
        try {
            activity.requestPermissions(missing.toTypedArray(), code)
        } catch (t: Throwable) {
        }
    }

    /** Asks for the battery-optimization exemption. Requested, never required. */
    fun requestBattery(activity: Activity) {
        if (!Compat.isApi23) return
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            )
            intent.data = android.net.Uri.fromParts("package", activity.packageName, null)
            activity.startActivity(intent)
        } catch (t: Throwable) {
            try {
                activity.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (ignored: Throwable) {
            }
        }
    }

    fun grantedResult(grantResults: IntArray?): Boolean =
        grantResults != null && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }

    /** One-line human summary used by the doctor screen. */
    fun summary(ctx: Context): String {
        val n = granted(ctx, nearby(ctx))
        val s = granted(ctx, storage(ctx)) || Compat.hasAllFilesAccess(ctx)
        val b = Compat.isIgnoringBatteryOptimizations(ctx)
        if (n && s && b) return "granted"
        val missing = ArrayList<String>()
        if (!n) missing.add("Nearby")
        if (!s) missing.add("Storage")
        if (!b) missing.add("Battery")
        return "missing: " + missing.joinToString(", ")
    }

    fun verifyManifest(ctx: Context): List<String> {
        // Guards against a build that quietly drops a required permission.
        val required = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.WAKE_LOCK,
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= 23) required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        val out = ArrayList<String>()
        for (p in required) {
            try {
                ctx.packageManager.getPermissionInfo(p, 0)
            } catch (t: Throwable) {
                out.add(p)
            }
        }
        return out
    }
}
