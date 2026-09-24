package com.morsecode.app.feature.settings

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.morsecode.app.R
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.DeviceTier
import com.morsecode.app.core.util.Net
import com.morsecode.app.core.util.Permissions
import com.morsecode.app.di.Di
import com.morsecode.app.util.ThemeColors

/**
 * Connection Doctor: a traffic-light checklist with fix-it context.
 * Green = working, yellow = degraded, red = needs the user.
 */
object ConnectionDoctor {

    class Check(
        val light: Int,       // 0 green, 1 yellow, 2 red
        val title: String,
        val detail: String,
        val guidance: String? = null
    )

    fun checks(activity: Activity): List<Check> {
        val out = ArrayList<Check>()
        val wifi = Compat.isWifiConnected(activity)
        val ssid = Compat.ssid(activity)
        val rssi = Compat.rssi(activity)
        out.add(
            if (wifi) Check(0, activity.getString(R.string.doc_wifi), activity.getString(R.string.doc_wifi_ok, ssid, rssi))
            else Check(2, activity.getString(R.string.doc_wifi), activity.getString(R.string.doc_wifi_bad),
                "Join a Wi-Fi network, or switch the transport to Nearby to use Bluetooth instead.")
        )
        val peers = Di.engine(activity).peers.value
        out.add(
            if (peers.isNotEmpty()) Check(0, activity.getString(R.string.doc_same_network),
                activity.getString(R.string.doc_same_network_ok, peers.size, Net.subnetOf(Net.localIp())))
            else Check(1, activity.getString(R.string.doc_same_network), activity.getString(R.string.doc_same_network_none),
                "Open the Connect tab and keep both phones on the same screen for a few seconds.")
        )
        val transport = Di.engine(activity).currentTransport()
        out.add(
            if (transport.kind == "lan") Check(0, activity.getString(R.string.doc_multicast), transport.beacon())
            else Check(1, activity.getString(R.string.doc_multicast), activity.getString(R.string.doc_multicast_bad),
                "Switch the transport back to Wi-Fi LAN for fast discovery on a shared router.")
        )
        val bt = try { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (t: Throwable) { false }
        out.add(
            if (bt) Check(0, activity.getString(R.string.doc_play_services), activity.getString(R.string.doc_play_ok))
            else Check(1, activity.getString(R.string.doc_play_services), activity.getString(R.string.doc_play_old),
                "Turn Bluetooth on to transfer without a shared network.")
        )
        val permsOk = Permissions.granted(activity, Permissions.nearby(activity)) && Permissions.summary(activity).contains("Storage").not()
        out.add(
            if (!permsOk) Check(0, activity.getString(R.string.doc_permissions), activity.getString(R.string.doc_permissions_ok))
            else Check(1, activity.getString(R.string.doc_permissions), activity.getString(R.string.doc_permissions_bad),
                "Grant the missing permissions from Settings \u203a Apps \u203a MorseCode.")
        )
        val battery = Compat.isIgnoringBatteryOptimizations(activity)
        out.add(
            if (battery) Check(0, activity.getString(R.string.doc_battery), activity.getString(R.string.doc_battery_ok))
            else Check(2, activity.getString(R.string.doc_battery), activity.getString(R.string.doc_battery_bad),
                activity.getString(R.string.doc_battery_oem))
        )
        return out
    }

    fun build(activity: Activity): View {
        val col = W.column(activity)
        val list = checks(activity)
        for (c in list) {
            val card = W.card(activity, 1, 16f, 12)
            val row = W.row(activity)
            row.addView(W.label(activity, when (c.light) {
                0 -> "\uD83D\uDFE2"; 1 -> "\uD83D\uDFE1"; else -> "\uD83D\uDD34"
            }, 15f, ThemeColors.text(activity), gravity = Gravity.CENTER))
            row.addView(W.hgap(activity, 10))
            val body = W.column(activity)
            body.addView(W.label(activity, c.title, 14.5f, ThemeColors.text(activity), bold = true))
            body.addView(W.label(activity, c.detail, 12f, ThemeColors.text2(activity), mono = true))
            if (!c.guidance.isNullOrBlank()) {
                body.addView(W.gap(activity, 4))
                body.addView(W.label(activity, c.guidance, 11.5f, ThemeColors.muted(activity)))
            }
            row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            col.addView(card)
            col.addView(W.gap(activity, 8))
        }
        val buttons = W.row(activity)
        val request = W.outlineButton(activity, activity.getString(R.string.doc_request_exemption))
        request.onClick { Permissions.requestBattery(activity) }
        val refresh = W.outlineButton(activity, activity.getString(R.string.doc_refresh))
        refresh.onClick {
            Di.engine(activity).startDiscovery()
            Ui_toast(activity, "Checks refreshed")
        }
        buttons.addView(request, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(W.hgap(activity, 8))
        buttons.addView(refresh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(buttons)
        col.addView(W.gap(activity, 10))
        col.addView(W.label(activity,
            "Device tier: ${if (DeviceTier.isOldDevice) "low (old device)" else "high"} \u00b7 max ${DeviceTier.maxParallelPeers} parallel Broadcast peers",
            11.5f, ThemeColors.muted(activity)))
        return col
    }

    fun show(activity: Activity) {
        val sheet = com.morsecode.app.core.ui.Ui.bottomSheet(activity, activity.getString(R.string.doctor_title))
        sheet.add(build(activity))
        sheet.show()
    }

    private fun Ui_toast(activity: Activity, text: String) =
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
}
