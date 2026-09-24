package com.morsecode.app.core.webshare

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.morsecode.app.BuildConfig
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.util.State
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.Event
import com.morsecode.app.core.util.Net
import com.morsecode.app.di.Di

/**
 * Owns the WebShare server lifetime and the phone-side consent prompt.
 *
 * INV-4: once started it stays on until the user stops it - no idle timeout, no auto teardown,
 * screen-off is never a reason to close, and it never shuts down while a browser session is
 * connected.
 *
 * The address is a bare `IP:port` with no token; access is consent based instead:
 * a new browser session triggers the "Browser wants access" popup on the phone, and the browser
 * only sees a waiting screen until the phone holder accepts.
 */
class WebShareController(private val ctx: Context) {

    val running = State(false)
    val url = State("")
    val sessionCount = State(0)
    val events = Event<String>()

    @Volatile private var server: WebShareServer? = null
    @Volatile var consentHandler: ((browser: String, ip: String, answer: (Boolean) -> Unit) -> Unit)? = null

    private val prefs get() = Di.prefs(ctx)

    val hotspot = HotspotController(ctx)

    fun isRunning(): Boolean = server?.isRunning == true

    fun currentUrl(): String = url.value

    /** Starts (or restarts) the server and returns the reachable address. */
    fun start(): Boolean {
        if (isRunning()) return true
        val ip = Net.localIp()
        val address = "http://$ip:${BuildConfig.WEB_PORT}"
        val s = WebShareServer(
            ctx,
            BuildConfig.WEB_PORT,
            "webshare",
            consent = { browser, remoteIp ->
                askConsent(browser, remoteIp)
            },
            info = { WebShareServer.ServerInfo(prefs.deviceName, address) }
        )
        val ok = s.start()
        if (!ok) {
            events.emit("Could not start WebShare on port ${BuildConfig.WEB_PORT}")
            Log.error("WebShare start failed")
            return false
        }
        server = s
        prefs.webShareOn = true
        running.set(true)
        url.set(address)
        acquireWifiLock()
        Log.info("WebShare mode started - $address")
        return true
    }

    fun stop() {
        val s = server ?: return
        // Never tear down while a browser session is connected; the user stops it explicitly.
        s.stop()
        server = null
        prefs.webShareOn = false
        running.set(false)
        url.set("")
        sessionCount.set(0)
        releaseWifiLock()
        Log.info("WebShare stopped by user")
    }

    fun toggle(): Boolean = if (isRunning()) {
        stop(); false
    } else start()

    /** Called from the service tick so the UI shows live session counts. */
    fun refresh() {
        val s = server ?: return
        sessionCount.set(s.sessionCount())
    }

    private fun askConsent(browser: String, ip: String): Boolean {
        Log.info("Browser wants access: $browser @ $ip")
        val handler = consentHandler
        if (handler == null) {
            Log.warn("Browser session auto-rejected - no UI attached")
            return false
        }
        var decided = false
        var granted = false
        val lock = Object()
        handler(browser, ip) { accepted ->
            synchronized(lock) {
                decided = true
                granted = accepted
                (lock as Object).notifyAll()
            }
        }
        val deadline = System.currentTimeMillis() + 30_000
        synchronized(lock) {
            while (!decided && System.currentTimeMillis() < deadline) {
                try {
                    (lock as Object).wait(500)
                } catch (ignored: Throwable) {
                    break
                }
            }
        }
        if (granted) {
            server?.logSession(ip, browser)
            events.emit("$browser connected")
        } else {
            Log.info("Browser session rejected - reason=USER_REJECT")
        }
        return granted
    }

    // ---- hotspot (optional path when there is no router) --------------------

    fun startHotspot(): HotspotController.Result {
        val r = hotspot.start()
        if (r.ok) {
            prefs.hotspotMode = true
            Log.info("Hotspot started: ${r.ssid}")
        } else {
            Log.warn("Hotspot unavailable: ${r.message}")
        }
        return r
    }

    fun stopHotspot() {
        hotspot.stop()
        prefs.hotspotMode = false
    }

    // ---- wifi lock ---------------------------------------------------------

    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireWifiLock() {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                wifiLock = if (Build.VERSION.SDK_INT >= 12) {
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "morsecode-webshare")
                } else {
                    @Suppress("DEPRECATION")
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "morsecode-webshare")
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (t: Throwable) {
            Log.warn("Wi-Fi lock unavailable: ${t.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Throwable) {
        }
    }

    fun statusLine(): String = when {
        isRunning() && sessionCount.value > 0 -> "Running at ${url.value} \u00b7 ${sessionCount.value} browser"
        isRunning() -> "Running at ${url.value}"
        !Compat.isWifiConnected(ctx) -> ctx.getString(com.morsecode.app.R.string.webshare_requires_wifi)
        else -> "Phone to browser over Wi-Fi"
    }
}
