package com.morsecode.app.core.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.morsecode.app.R
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.Permissions
import com.morsecode.app.util.ThemeColors

/**
 * What the Nearby (Bluetooth) transport needs before it can do anything: an adapter, the radio
 * switched on, and the Bluetooth permissions of this API level. A screen cannot assume any of
 * them, and until now a missing one produced a search that silently found nothing - the user saw
 * "Nearby" and an empty list and had no way to tell why.
 *
 * [NearbyProblem] tokens come from the transport itself; the wording lives here.
 */
object TransportBanner {

    const val NO_ADAPTER = "no_adapter"
    const val OFF = "off"
    const val PERMISSION = "permission"

    fun text(ctx: Context, token: String): String = ctx.getString(
        when (token) {
            NO_ADAPTER -> R.string.bt_no_adapter
            PERMISSION -> R.string.bt_needs_permission
            else -> R.string.bt_off
        }
    )

    /**
     * A card that says what is wrong and offers the one action that fixes it. [onUseLan] is the
     * transport switch; turning Bluetooth on and granting permissions are system dialogs, so the
     * banner opens them itself.
     */
    fun build(activity: Activity, token: String, onUseLan: () -> Unit): View {
        val ctx = activity
        val card = W.column(ctx, 12, 12)
        card.background = ThemeColors.cardDrawable(ctx, 1, 16f)
        card.addView(W.label(ctx, text(ctx, token), 13f, ThemeColors.text(ctx)))
        card.addView(W.gap(ctx, 10))

        val actions = W.row(ctx)
        val weight = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        var first = true
        fun add(v: View) {
            if (!first) actions.addView(W.hgap(ctx, 8))
            first = false
            actions.addView(v, weight)
        }

        when (token) {
            OFF -> {
                val on = W.accentButton(ctx, ctx.getString(R.string.turn_on_bluetooth))
                on.onClick { Compat.requestBluetooth(activity) }
                add(on)
            }
            PERMISSION -> {
                val allow = W.accentButton(ctx, ctx.getString(R.string.allow))
                allow.onClick {
                    Permissions.request(activity, Permissions.nearby(activity), Permissions.REQ_NEARBY)
                }
                add(allow)
            }
            // No adapter: there is nothing to switch on, so the only offer is the other transport.
        }

        val lan = W.outlineButton(ctx, ctx.getString(R.string.use_lan))
        lan.onClick { onUseLan() }
        add(lan)

        card.addView(actions)
        return card
    }
}
