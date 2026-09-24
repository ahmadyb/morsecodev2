package com.morsecode.app.core.data

import android.content.Context
import android.content.SharedPreferences
import com.morsecode.app.util.ThemeColors

/**
 * Every persisted user setting. Small enough that a plain SharedPreferences file is
 * the right tool - and it is instant even on a 2016 device.
 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("morsecode", Context.MODE_PRIVATE)

    // ---- profile -----------------------------------------------------------
    var deviceName: String
        get() = sp.getString("device_name", null) ?: defaultDeviceName()
        set(v) = sp.edit().putString("device_name", v).apply()

    private fun defaultDeviceName(): String =
        (android.os.Build.MODEL ?: "Android").let { if (it.length > 22) it.substring(0, 22) else it }

    // ---- appearance --------------------------------------------------------
    var accentId: String
        get() = sp.getString("accent", ThemeColors.Accent.YELLOW.id) ?: ThemeColors.Accent.YELLOW.id
        set(v) = sp.edit().putString("accent", v).apply()

    val accent: ThemeColors.Accent get() = ThemeColors.Accent.from(accentId)

    var darkMode: Boolean
        get() = sp.getBoolean("dark_mode", true)
        set(v) = sp.edit().putBoolean("dark_mode", v).apply()

    var sounds: Boolean
        get() = sp.getBoolean("sounds", true)
        set(v) = sp.edit().putBoolean("sounds", v).apply()

    // ---- transfer ----------------------------------------------------------
    /** skip | rename | overwrite - default is rename duplicates. */
    var conflictPolicy: String
        get() = sp.getString("conflict", "rename") ?: "rename"
        set(v) = sp.edit().putString("conflict", v).apply()

    var notifications: Boolean
        get() = sp.getBoolean("notifications", true)
        set(v) = sp.edit().putBoolean("notifications", v).apply()

    var lastTransport: String
        get() = sp.getString("transport", "lan") ?: "lan"
        set(v) = sp.edit().putString("transport", v).apply()

    // ---- webshare ----------------------------------------------------------
    var webShareOn: Boolean
        get() = sp.getBoolean("webshare_on", false)
        set(v) = sp.edit().putBoolean("webshare_on", v).apply()

    var hotspotMode: Boolean
        get() = sp.getBoolean("hotspot", false)
        set(v) = sp.edit().putBoolean("hotspot", v).apply()

    // ---- system ------------------------------------------------------------
    var logEnabled: Boolean
        get() = sp.getBoolean("log_enabled", true)
        set(v) = sp.edit().putBoolean("log_enabled", v).apply()

    var crashReports: Boolean
        get() = sp.getBoolean("crash_reports", true)
        set(v) = sp.edit().putBoolean("crash_reports", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var batteryAsked: Boolean
        get() = sp.getBoolean("battery_asked", false)
        set(v) = sp.edit().putBoolean("battery_asked", v).apply()

    /** one-time tips: key -> shown */
    /** Where received files land by default; overridden by a SAF folder when one is granted. */
    var defaultDownload: String?
        get() = sp.getString("default_download", null)
        set(value) = sp.edit().putString("default_download", value).apply()

    /** Clears every one-time hint so the tips show again. */
    fun clearTips() {
        val ed = sp.edit()
        val keys = sp.all.keys.filter { it.startsWith("tip_") }
        for (k in keys) ed.remove(k)
        ed.apply()
    }

    fun tipShown(key: String): Boolean = sp.getBoolean("tip_$key", false)
    fun markTip(key: String) = sp.edit().putBoolean("tip_$key", true).apply()

    // ---- recent devices ----------------------------------------------------
    fun recentDevices(): List<String> =
        (sp.getString("recent", "") ?: "").split("\n").filter { it.isNotBlank() }

    /** stored as `id\u0001name\u0001transport\u0001lastSeen` */
    fun rememberDevice(id: String, name: String, transport: String) {
        val entry = "$id\u0001$name\u0001$transport\u0001${System.currentTimeMillis()}"
        val list = recentDevices().filterNot { it.startsWith("$id\u0001") }.toMutableList()
        list.add(0, entry)
        val trimmed = list.take(12).joinToString("\n")
        sp.edit().putString("recent", trimmed).apply()
    }

    fun clearRecentDevices() = sp.edit().remove("recent").apply()

    /** Liked tracks (local only - never synced anywhere). */
    fun likedSongs(): MutableSet<String> =
        HashSet(sp.getStringSet("liked", emptySet()) ?: emptySet())

    fun toggleLiked(key: String): Boolean {
        val set = likedSongs()
        val added = if (set.contains(key)) { set.remove(key); false } else { set.add(key); true }
        sp.edit().putStringSet("liked", set).apply()
        return added
    }
}
