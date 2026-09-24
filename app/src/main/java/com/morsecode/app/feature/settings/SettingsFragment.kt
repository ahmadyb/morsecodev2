package com.morsecode.app.feature.settings

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.morsecode.app.BuildConfig
import com.morsecode.app.MainActivity
import com.morsecode.app.R
import com.morsecode.app.core.transfer.TransferService
import com.morsecode.app.core.ui.Screen
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Fmt
import com.morsecode.app.core.util.Net
import com.morsecode.app.core.util.Permissions
import com.morsecode.app.di.Di
import com.morsecode.app.util.ThemeColors

/**
 * Settings: profile, accent swatches, appearance, transfer policy, WebShare, system toggles,
 * logs, diagnostics and about. Every row is functional - nothing is decorative.
 */
class SettingsFragment(private val activity: Activity) : Screen {

    private lateinit var body: LinearLayout

    override fun view(ctx: Activity): View {
        val root = W.column(ctx)
        root.setBackgroundColor(ThemeColors.bg(ctx))
        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        body = W.column(ctx, 0, 14)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onShown() = rebuild()

    private fun rebuild() {
        if (!::body.isInitialized) return
        val ctx = activity
        body.removeAllViews()
        body.addView(W.label(ctx, ctx.getString(R.string.settings), 22f, ThemeColors.text(ctx), bold = true))
        body.addView(W.gap(ctx, 12))
        body.addView(profileCard())
        body.addView(W.gap(ctx, 14))
        body.addView(sectionLabel(ctx.getString(R.string.appearance)))
        body.addView(accentCard())
        body.addView(W.gap(ctx, 8))
        body.addView(appearanceCard())
        body.addView(W.gap(ctx, 14))
        body.addView(sectionLabel(ctx.getString(R.string.transfer_section)))
        body.addView(transferCard())
        body.addView(W.gap(ctx, 14))
        body.addView(sectionLabel(ctx.getString(R.string.webshare)))
        body.addView(webShareCard())
        body.addView(W.gap(ctx, 14))
        body.addView(sectionLabel(ctx.getString(R.string.system_section)))
        body.addView(systemCard())
        body.addView(W.gap(ctx, 14))
        body.addView(sectionLabel(ctx.getString(R.string.about)))
        body.addView(aboutCard())
        body.addView(W.gap(ctx, 26))
    }

    private fun sectionLabel(text: String): View {
        val v = W.label(activity, text.uppercase(), 11f, ThemeColors.muted(activity), bold = true, mono = true)
        v.pad(0, 6, 0, 8)
        return v
    }

    private fun cardOf(vararg rows: View): View {
        val ctx = activity
        val card = W.card(ctx, 0, 18f, 4)
        var first = true
        for (r in rows) {
            if (!first) card.addView(W.divider(ctx))
            card.addView(r)
            first = false
        }
        return card
    }

    // ------------------------------------------------------------------ profile

    private fun profileCard(): View {
        val ctx = activity
        val prefs = Di.prefs(ctx)
        val card = W.outlinedCard(ctx)
        val row = W.row(ctx, 4, 4)
        row.addView(W.selfAvatar(ctx, prefs.deviceName.take(1), 54))
        row.addView(W.hgap(ctx, 14))
        val col = W.column(ctx)
        col.addView(W.label(ctx, prefs.deviceName, 17f, ThemeColors.text(ctx), bold = true))
        col.addView(W.label(ctx, "${ctx.getString(R.string.device_type_phone)} \u00b7 ${Net.localIp()}", 11.5f,
            ThemeColors.text2(ctx), mono = true))
        col.addView(W.label(ctx, ctx.getString(R.string.tap_to_rename), 11f, ThemeColors.accentStart(ctx)))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(W.icon(ctx, R.drawable.ic_edit, 20, ThemeColors.muted(ctx)))
        card.addView(row)
        card.isClickable = true
        card.onClick {
            Ui.prompt(activity, ctx.getString(R.string.rename_device), ctx.getString(R.string.device_name_hint),
                prefs.deviceName, ctx.getString(R.string.save_)) { value ->
                prefs.deviceName = value
                rebuild()
                Ui.toast(activity, "Device name saved")
            }
        }
        card.addView(W.divider(ctx))
        card.addView(W.infoRow(ctx, "Device ID", Di.engine(ctx).deviceId().take(12)))
        return card
    }

    // ------------------------------------------------------------------ theme

    private fun accentCard(): View {
        val ctx = activity
        val prefs = Di.prefs(ctx)
        val card = W.card(ctx)
        card.addView(W.label(ctx, ctx.getString(R.string.accent_colour), 15f, ThemeColors.text(ctx), bold = true))
        card.addView(W.label(ctx, ctx.getString(ThemeColors.accentNameRes(ctx)) + " \u00b7 5 swatches, 10 theme overlays",
            11.5f, ThemeColors.text2(ctx)))
        card.addView(W.gap(ctx, 12))
        val row = W.row(ctx)
        for (accent in ThemeColors.Accent.values()) {
            val col = W.column(ctx)
            col.setGravity(Gravity.CENTER)
            val dot = View(ctx)
            val dark = ThemeColors.isDark(ctx)
            val d = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(accent.darkStart, accent.darkEnd)
                else intArrayOf(accent.lightStart, accent.lightEnd))
            d.setShape(GradientDrawable.OVAL)
            if (accent == prefs.accent) d.setStroke(D.dp(ctx, 3f), ThemeColors.text(ctx))
            dot.background = d
            col.addView(dot, LinearLayout.LayoutParams(D.dp(ctx, 40f), D.dp(ctx, 40f)))
            col.addView(W.gap(ctx, 6))
            col.addView(W.label(ctx, ctx.getString(accent.labelRes), 10.5f,
                if (accent == prefs.accent) ThemeColors.text(ctx) else ThemeColors.muted(ctx), mono = true))
            col.isClickable = true
            col.onClick {
                prefs.accentId = accent.id
                activity.recreate()
            }
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(row)
        card.addView(W.gap(ctx, 12))
        val overlays = W.outlineButton(ctx, "Theme overlays")
        overlays.onClick { pickOverlay() }
        card.addView(overlays)
        return card
    }

    private fun pickOverlay() {
        val ctx = activity
        val prefs = Di.prefs(ctx)
        val names = ArrayList<String>()
        for (accent in ThemeColors.Accent.values()) {
            names.add("Dark \u00b7 ${ctx.getString(accent.labelRes)}")
            names.add("Light \u00b7 ${ctx.getString(accent.labelRes)}")
        }
        val index = ThemeColors.Accent.values().indexOf(prefs.accent) * 2 + if (prefs.darkMode) 0 else 1
        Ui.pick(activity, "Theme overlays", names, index) { i ->
            val accent = ThemeColors.Accent.values()[i / 2]
            prefs.accentId = accent.id
            prefs.darkMode = (i % 2 == 0)
            activity.recreate()
        }
    }

    private fun appearanceCard(): View {
        val ctx = activity
        val prefs = Di.prefs(ctx)
        return cardOf(
            W.switchRow(ctx, ctx.getString(R.string.dark_mode),
                if (prefs.darkMode) "Sunflower Hue \u00b7 dark" else "Sunflower Hue \u00b7 light",
                prefs.darkMode) { on ->
                prefs.darkMode = on
                activity.recreate()
            },
            W.switchRow(ctx, ctx.getString(R.string.sounds), "Tone on connect, done and failure",
                prefs.sounds) { on -> prefs.sounds = on }
        )
    }

    // ------------------------------------------------------------------ transfer

    private fun transferCard(): View {
        val ctx = activity
        val prefs = Di.prefs(ctx)
        val policy = W.settingRow(ctx, ctx.getString(R.string.conflict_policy),
            ctx.getString(policyLabel(prefs.conflictPolicy)), null) {
            val labels = arrayListOf(ctx.getString(R.string.policy_skip), ctx.getString(R.string.policy_rename),
                ctx.getString(R.string.policy_overwrite))
            Ui.pick(activity, ctx.getString(R.string.conflict_policy), labels, policyIndex(prefs.conflictPolicy)) { i ->
                prefs.conflictPolicy = when (i) {
                    0 -> "skip"
                    2 -> "overwrite"
                    else -> "rename"
                }
                rebuild()
            }
        }
        val transport = W.settingRow(ctx, ctx.getString(R.string.switch_transport),
            com.morsecode.app.core.model.TransportKind.label(Di.engine(ctx).transportKind.value), null) {
            Di.engine(ctx).switchTransport()
            rebuild()
        }
        val destination = W.settingRow(ctx, ctx.getString(R.string.default_download),
            if (Di.saf(ctx).defaultFolder() != null) "Saved folder (SAF)" else (prefs.defaultDownload ?: "Downloads/MorseLink"), null) {
            pickDestination()
        }
        return cardOf(policy, transport, destination,
            W.switchRow(ctx, ctx.getString(R.string.notifications), "Progress and completion", prefs.notifications) { on ->
                prefs.notifications = on
            })
    }

    private fun policyIndex(policy: String): Int = when (policy) {
        "skip" -> 0
        "overwrite" -> 2
        else -> 1
    }

    private fun policyLabel(policy: String): Int = when (policy) {
        "skip" -> R.string.policy_skip
        "overwrite" -> R.string.policy_overwrite
        else -> R.string.policy_rename
    }

    private fun pickDestination() {
        val ctx = activity
        val labels = arrayListOf("Downloads/MorseLink", "Downloads", "Pictures", "Movies", "Music", "Documents")
        val current = labels.indexOf(Di.prefs(ctx).defaultDownload).coerceAtLeast(0)
        Ui.pick(activity, ctx.getString(R.string.select_destination), labels, current) { i ->
            Di.prefs(ctx).defaultDownload = labels[i]
            rebuild()
            Ui.toast(activity, ctx.getString(R.string.destination_selected, labels[i]))
        }
    }

    // ------------------------------------------------------------------ webshare

    private fun webShareCard(): View {
        val ctx = activity
        val web = Di.web(ctx)
        val toggle = W.switchRow(ctx, ctx.getString(R.string.webshare), web.statusLine(), web.isRunning()) { on ->
            if (on) {
                if (!Compat.isWifiConnected(ctx) && !web.hotspot.isOn) {
                    Ui.info(activity, ctx.getString(R.string.webshare), ctx.getString(R.string.webshare_requires_wifi))
                    rebuild()
                    return@switchRow
                }
                if (web.start()) Ui.toast(activity, ctx.getString(R.string.webshare_running, web.currentUrl()))
            } else {
                web.stop()
                Ui.toast(activity, "WebShare stopped")
            }
            rebuild()
        }
        val open = W.settingRow(ctx, ctx.getString(R.string.open_in_browser),
            web.currentUrl().ifBlank { "\u2014" }, null) {
            if (!web.isRunning()) {
                Ui.toast(activity, ctx.getString(R.string.webshare_start) + " first")
                return@settingRow
            }
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.currentUrl())))
            } catch (t: Throwable) {
                Ui.info(activity, ctx.getString(R.string.webshare), web.currentUrl() +
                    "\n\n" + ctx.getString(R.string.webshare_url_label))
            }
        }
        val hotspot = W.settingRow(ctx, ctx.getString(R.string.webshare_hotspot),
            ctx.getString(R.string.webshare_hotspot_sub), null) {
            val result = web.startHotspot()
            Ui.info(activity, ctx.getString(R.string.webshare_hotspot),
                if (result.ok) "SSID ${result.ssid}\nPassword ${result.password}"
                else "${result.message}\n\n${web.hotspot.instructions()}")
            rebuild()
        }
        val copy = W.settingRow(ctx, ctx.getString(R.string.copy_address), null, null) {
            if (!web.isRunning()) {
                Ui.toast(activity, ctx.getString(R.string.webshare_start) + " first")
                return@settingRow
            }
            Compat.copy(activity, "WebShare", web.currentUrl())
            Ui.toast(activity, ctx.getString(R.string.copied))
        }
        return cardOf(toggle, open, hotspot, copy)
    }

    // ------------------------------------------------------------------ system

    private fun systemCard(): View {
        val ctx = activity
        val prefs = Di.prefs(ctx)
        val storageSub = if (Compat.hasAllFilesAccess(ctx)) ctx.getString(R.string.granted)
        else ctx.getString(R.string.storage_permission_needed)
        val storage = W.settingRow(ctx, ctx.getString(R.string.storage_access), storageSub, null) {
            if (Compat.hasAllFilesAccess(ctx)) Ui.info(activity, ctx.getString(R.string.storage_access),
                "MorseCode can write received files anywhere you choose.")
            else {
                Ui.confirm(activity, ctx.getString(R.string.storage_access),
                    ctx.getString(R.string.storage_guidance),
                    positive = ctx.getString(R.string.open_settings),
                    onPositive = { Compat.openAllFilesSettings(activity) },
                    negative = ctx.getString(R.string.not_now))
            }
        }
        val battery = W.settingRow(ctx, ctx.getString(R.string.battery_optimization),
            if (Compat.isIgnoringBatteryOptimizations(ctx)) ctx.getString(R.string.doc_battery_ok)
            else ctx.getString(R.string.doc_battery_bad), null) {
            Permissions.requestBattery(activity)
        }
        val logs = W.settingRow(ctx, ctx.getString(R.string.logs),
            Fmt.size(Di.logs(ctx).snapshot().size.toLong() * 120L) + " in memory", null) {
            openScreen(LogViewerFragment(activity))
        }
        val crashSub = if (Di.logs(ctx).crashCount() == 0) ctx.getString(R.string.crash_reports_none)
        else ctx.getString(R.string.crash_reports_sub, Di.logs(ctx).crashCount())
        val crashes = W.settingRow(ctx, ctx.getString(R.string.crash_reports), crashSub, null) {
            val text = Di.logs(ctx).readCrashes().ifBlank { ctx.getString(R.string.crash_reports_none) }
            Ui.info(activity, ctx.getString(R.string.crash_reports), text)
        }
        val diagnostics = W.settingRow(ctx, ctx.getString(R.string.diagnostics),
            "Full logs, journal and network checks", null) {
            openScreen(DiagnosticsFragment(activity))
        }
        val foreground = W.settingRow(ctx, ctx.getString(R.string.foreground_running),
            if (TransferService.isRunning) "Transfer service active" else "Idle", null) {
            Compat.startServiceCompat(activity, Intent(activity, TransferService::class.java))
            Ui.toast(activity, ctx.getString(R.string.foreground_running))
        }
        return cardOf(storage, battery, logs, crashes, diagnostics, foreground)
    }

    // ------------------------------------------------------------------ about

    private fun aboutCard(): View {
        val ctx = activity
        val prefs = Di.prefs(ctx)
        val help = W.settingRow(ctx, ctx.getString(R.string.help_faq), "5 questions, plain answers", null) {
            openScreen(HelpFragment(activity))
        }
        val replay = W.settingRow(ctx, ctx.getString(R.string.replay_onboarding), null, null) {
            activity.startActivity(Intent(activity, com.morsecode.app.feature.onboarding.OnboardingActivity::class.java))
        }
        val reset = W.settingRow(ctx, "Reset tips", "Show the one-time hints again", null) {
            prefs.clearTips()
            Ui.toast(activity, "Tips will show again")
        }
        val share = W.settingRow(ctx, ctx.getString(R.string.share) + " MorseCode", appShareContext(), null) {
            try {
                val msg = "MorseCode - offline file transfer. WebShare address: " +
                    (if (Di.web(ctx).isRunning()) Di.web(ctx).currentUrl() else "\u2014")
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, msg)
                activity.startActivity(Intent.createChooser(intent, ctx.getString(R.string.share_via)))
            } catch (t: Throwable) {
            }
        }
        val version = W.settingRow(ctx, "Version",
            ctx.getString(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), null) {
            Ui.info(activity, ctx.getString(R.string.app_name),
                "${ctx.getString(R.string.app_tagline)}\n\n" +
                    "applicationId com.morsecode.app\nminSdk 21 \u00b7 targetSdk 34\n" +
                    "UDP ${BuildConfig.UDP_DISCOVERY_PORT} \u00b7 TCP ${BuildConfig.TCP_PORT} \u00b7 HTTP ${BuildConfig.WEB_PORT}")
        }
        return cardOf(help, replay, reset, share, version)
    }

    private fun appShareContext(): String = "Version " + BuildConfig.VERSION_NAME

    private fun openScreen(screen: Screen) {
        val host = activity as? MainActivity
        if (host != null) host.push(screen) else Ui.toast(activity, "Open MainActivity first")
    }

    override fun onBackPressed(): Boolean = false
}
