package com.morsecode.app.core.webshare

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.util.Compat
import java.lang.reflect.Method

/**
 * Optional hotspot mode: when there is no router to share, the phone can hand out its own network
 * so a laptop can reach WebShare directly.
 *
 * Hotspot control sits behind `WifiManager.setWifiApEnabled`, which is hidden on most modern
 * builds and forbidden to third party apps from Android 10 - so every path here is reflective and
 * failure is expected and handled: the UI falls back to written manual-setup instructions rather
 * than pretending the toggle worked.
 */
class HotspotController(private val ctx: Context) {

    class Result(val ok: Boolean, val ssid: String = "", val password: String = "", val message: String = "")

    @Volatile private var apEnabled = false

    fun start(): Result {
        if (!Compat.isWifiConnected(ctx) && Build.VERSION.SDK_INT >= 26) {
            // There is nothing to share and Android 10+ blocks programmatic AP control anyway.
            return Result(false, message = "Hotspot control is restricted on this device")
        }
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val config = WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wm)
            val ssid = config.javaClass.getField("SSID").get(config) as? String ?: "MorseCode"
            val preShared = config.javaClass.getField("preSharedKey").get(config) as? String ?: ""
            val method: Method = WifiManager::class.java.getMethod("setWifiApEnabled", config.javaClass, Boolean::class.javaPrimitiveType)
            val enabled = method.invoke(wm, config, true) as? Boolean ?: false
            apEnabled = enabled
            if (enabled) {
                Log.info("Hotspot enabled - SSID $ssid")
                Result(true, ssid, preShared)
            } else {
                Result(false, message = "The system refused to start the hotspot")
            }
        } catch (t: Throwable) {
            Log.warn("Hotspot control unavailable: ${t.message}")
            Result(false, message = t.message ?: "Hotspot control unavailable")
        }
    }

    fun stop() {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val config = WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wm)
            val method = WifiManager::class.java.getMethod("setWifiApEnabled", config.javaClass, Boolean::class.javaPrimitiveType)
            method.invoke(wm, config, false)
        } catch (ignored: Throwable) {
        }
        apEnabled = false
    }

    val isOn: Boolean get() = apEnabled

    fun instructions(): String =
        "1. Open Settings \u203a Network \u203a Hotspot and turn on the portable hotspot.\n" +
            "2. Connect the laptop to that Wi-Fi network.\n" +
            "3. Come back to WebShare - the address stays the same."
}
