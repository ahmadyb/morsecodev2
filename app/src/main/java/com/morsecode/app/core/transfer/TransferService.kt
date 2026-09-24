package com.morsecode.app.core.transfer

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.morsecode.app.BuildConfig
import com.morsecode.app.R
import com.morsecode.app.core.util.Compat
import com.morsecode.app.di.Di
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (type `dataSync`) that keeps every active session alive when the UI is
 * minimised - including every peer of a Broadcast and the WebShare server.
 *
 * The persistent notification shows running state, the transfer summary and the WebShare URL.
 * Nothing here is required for correctness: if the user denies the notification permission or
 * kills the service, sessions still resume from the journal.
 */
class TransferService : Service() {

    private val ticking = AtomicBoolean(false)

    companion object {
        const val CHANNEL_TRANSFERS = "mc-transfers"
        const val CHANNEL_WEB = "mc-webshare"
        const val NOTIF_ID = 4401

        /** True while the foreground session service is alive - shown in Settings. */
        @Volatile var isRunning: Boolean = false
    }

    override fun onCreate() {
        isRunning = true
        super.onCreate()
        Compat.ensureChannel(this, CHANNEL_TRANSFERS, getString(R.string.notif_channel_transfers))
        Compat.ensureChannel(this, CHANNEL_WEB, getString(R.string.notif_channel_webshare))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        startTicker()
        // Persist whatever queue exists so a relaunch can offer resume.
        Di.journal(this).save(Di.engine(this).items.value)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = Compat.buildNotification(
            this,
            CHANNEL_TRANSFERS,
            getString(R.string.notif_transfer_title),
            statusLine(),
            R.drawable.ic_stat_mc
        )
        try {
            if (Compat.isApi29) {
                // The service is declared with foregroundServiceType="dataSync" in the manifest.
                startForeground(NOTIF_ID, notification)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.warn("startForeground failed: ${t.message}")
        }
    }

    private fun startTicker() {
        if (ticking.getAndSet(true)) return
        Thread({
            while (ticking.get()) {
                try {
                    val engine = Di.engine(this)
                    val web = Di.web(this)
                    val stats = engine.summaryStats()
                    val text = buildString {
                        if (stats.sending + stats.receiving > 0) {
                            append(getString(R.string.batch_in_progress)).append(" \u00b7 ")
                            append("${stats.sending + stats.receiving} active, ${stats.queued} queued")
                        } else if (web.isRunning()) {
                            append("WebShare ").append(web.currentUrl())
                        } else {
                            append(getString(R.string.foreground_running))
                        }
                    }
                    if (Di.prefs(this).notifications) {
                        val n = Compat.buildNotification(
                            this, CHANNEL_TRANSFERS,
                            getString(R.string.notif_transfer_title), text, R.drawable.ic_stat_mc
                        )
                        Compat.notify(this, NOTIF_ID, n)
                    }
                    if (web.isRunning()) {
                        val w = Compat.buildNotification(
                            this, CHANNEL_WEB,
                            getString(R.string.notif_webshare_title), web.currentUrl(), R.drawable.ic_stat_mc
                        )
                        Compat.notify(this, NOTIF_ID + 1, w)
                        web.refresh()
                    }
                    Thread.sleep(if (stats.sending + stats.receiving > 0) 1000 else 5000)
                } catch (t: Throwable) {
                    try {
                        Thread.sleep(5000)
                    } catch (ignored: Throwable) {
                        break
                    }
                }
            }
        }, "mc-service-tick").apply { isDaemon = true }.start()
    }

    private fun statusLine(): String =
        "${BuildConfig.VERSION_NAME} \u00b7 ${getString(R.string.foreground_running)}"

    override fun onDestroy() {
        isRunning = false
        ticking.set(false)
        Log.info("Foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
