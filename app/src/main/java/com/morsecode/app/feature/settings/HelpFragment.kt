package com.morsecode.app.feature.settings

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.morsecode.app.R
import com.morsecode.app.core.ui.Screen
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.Net
import com.morsecode.app.di.Di
import com.morsecode.app.util.ThemeColors

/**
 * Help & FAQ: an accordion. One question open at a time, everything local - the answers never
 * leave the device.
 */
class HelpFragment(private val activity: Activity) : Screen {

    private var open = 0
    private lateinit var body: LinearLayout
    private lateinit var host: com.morsecode.app.MainActivity

    override fun view(ctx: Activity): View {
        val root = W.column(ctx)
        root.setBackgroundColor(ThemeColors.bg(ctx))
        host = ctx as com.morsecode.app.MainActivity
        root.addView(W.toolbar(ctx, ctx.getString(R.string.help), "How it works", { host.pop() }, emptyList()))
        val scroll = ScrollView(ctx)
        body = W.column(ctx, 0, 16)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onShown() = rebuild()

    override fun onBackPressed(): Boolean {
        host.pop()
        return true
    }

    private fun faq(): List<Pair<String, String>> {
        val ctx = activity
        val extra = ArrayList<Pair<String, String>>()
        extra.add(ctx.getString(R.string.faq_q1) to ctx.getString(R.string.faq_a1))
        extra.add(ctx.getString(R.string.faq_q2) to ctx.getString(R.string.faq_a2))
        extra.add(ctx.getString(R.string.faq_q3) to ctx.getString(R.string.faq_a3))
        extra.add(ctx.getString(R.string.faq_q4) to ctx.getString(R.string.faq_a4))
        extra.add(ctx.getString(R.string.faq_q5) to ctx.getString(R.string.faq_a5))
        extra.add("Why did a transfer pause?" to
            "If the other phone leaves the network, the item is paused and marked \u201cConnection lost\u201d. " +
            "Reconnect both phones and hit Resume - it continues from the byte it stopped on instead of starting over.")
        extra.add("What is the difference between Send and Broadcast?" to
            "Send targets one phone. Broadcast sends the same batch to 2 to 4 phones at once and keeps one summary " +
            "dialog for the whole group.")
        extra.add("Where do received files go?" to
            "Downloads/MorseLink by default. Change it in Settings \u203a Transfer \u203a Default download, or grant a " +
            "folder with the system picker to save straight into it.")
        extra.add("Ports used" to
            "UDP ${com.morsecode.app.BuildConfig.UDP_DISCOVERY_PORT} for discovery, TCP " +
            "${com.morsecode.app.BuildConfig.TCP_PORT} for transfers, HTTP ${com.morsecode.app.BuildConfig.WEB_PORT} for WebShare. " +
            "All three stay inside your local network.")
        return extra
    }

    private fun rebuild() {
        if (!::body.isInitialized) return
        val ctx = activity
        body.removeAllViews()
        val items = faq()
        val card = W.card(ctx, 0, 18f, 0)
        for ((i, qa) in items.withIndex()) {
            val isOpen = i == open
            val head = W.row(ctx, 16, 16)
            head.addView(W.label(ctx, qa.first, 14.5f, ThemeColors.text(ctx), bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(W.icon(ctx,
                if (isOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                18, ThemeColors.muted(ctx)))
            head.isClickable = true
            head.onClick {
                open = if (isOpen) -1 else i
                rebuild()
            }
            card.addView(head)
            if (isOpen) {
                val answer = W.label(ctx, qa.second, 13f, ThemeColors.text2(ctx))
                answer.pad(16, 0, 16, 16)
                answer.setLineSpacing(0f, 1.25f)
                card.addView(answer)
            }
            if (i < items.size - 1) card.addView(W.divider(ctx))
        }
        body.addView(card)

        body.addView(W.gap(ctx, 16))
        body.addView(W.label(ctx, "STILL STUCK?", 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        body.addView(W.gap(ctx, 8))
        val buttons = W.row(ctx)
        val doctor = W.outlineButton(ctx, ctx.getString(R.string.connection_doctor))
        doctor.onClick { host.push(DiagnosticsFragment(ctx)) }
        val replay = W.outlineButton(ctx, ctx.getString(R.string.replay_onboarding))
        replay.onClick {
            activity.startActivity(android.content.Intent(activity,
                com.morsecode.app.feature.onboarding.OnboardingActivity::class.java))
        }
        buttons.addView(doctor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(W.hgap(ctx, 8))
        buttons.addView(replay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(buttons)

        body.addView(W.gap(ctx, 16))
        val facts = W.card(ctx, 0, 18f, 14)
        facts.addView(W.label(ctx, "ON THIS DEVICE", 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        facts.addView(W.gap(ctx, 8))
        facts.addView(W.infoRow(ctx, "WebShare", Di.web(ctx).statusLine()))
        facts.addView(W.infoRow(ctx, "Address", "${Net.localIp()}:${com.morsecode.app.BuildConfig.TCP_PORT}"))
        facts.addView(W.infoRow(ctx, "Storage access",
            if (Compat.hasAllFilesAccess(ctx)) ctx.getString(R.string.granted) else "Limited"))
        facts.addView(W.infoRow(ctx, "Transport", com.morsecode.app.core.model.TransportKind.label(
            Di.engine(ctx).transportKind.value)))
        body.addView(facts)
        body.addView(W.gap(ctx, 24))
    }
}
