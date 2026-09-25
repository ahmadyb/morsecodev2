package com.morsecode.app

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.morsecode.app.core.model.DiscoveredPeer
import com.morsecode.app.core.model.TransportKind
import com.morsecode.app.core.network.TransportSession
import com.morsecode.app.core.storage.SafStore
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.transfer.TransferService
import com.morsecode.app.core.ui.ResultAware
import com.morsecode.app.core.ui.Screen
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Permissions
import com.morsecode.app.di.Di
import com.morsecode.app.feature.connect.ConnectFragment
import com.morsecode.app.feature.filemanager.FileManagerFragment
import com.morsecode.app.feature.history.HistoryFragment
import com.morsecode.app.feature.settings.LogViewerFragment
import com.morsecode.app.feature.settings.SettingsFragment
import com.morsecode.app.feature.transfer.TransferFragment
import com.morsecode.app.feature.onboarding.OnboardingActivity
import com.morsecode.app.util.ThemeColors

/**
 * The single activity: a bottom-nav host (Connect / Files / History / Settings) with the
 * Transfer / Broadcast screen pushed full screen on top of the originating tab.
 * Minimising back to a tab never ends a session - it keeps running in TransferService.
 */
class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val screens = HashMap<Int, Screen>()
    private var currentTab = 0
    private var pushed: Screen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.applyTheme(this)
        super.onCreate(savedInstanceState)
        Log.attach(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ThemeColors.bg(this))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        nav = W.bottomNav(this, currentTab) { index -> selectTab(index) }
        root.addView(nav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)

        Di.engine(this).consentHandler = { peer, transport, session, answer ->
            askConnectionConsent(peer, transport, session, answer)
        }
        Di.web(this).consentHandler = { browser, ip, answer ->
            askBrowserConsent(browser, ip, answer)
        }

        selectTab(0)
        maybeOnboard()
        startSessionService()
        clearNotificationPermissionAsk()
        handleShareIntent(intent)
    }

    private fun maybeOnboard() {
        val prefs = Di.prefs(this)
        if (!prefs.onboarded) {
            prefs.onboarded = true
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun startSessionService() {
        Compat.startServiceCompat(this, Intent(this, TransferService::class.java))
    }

    /** Android 13+ needs POST_NOTIFICATIONS for the persistent notification - never required. */
    private fun clearNotificationPermissionAsk() {
        if (Compat.isApi33 && !Permissions.granted(this, Permissions.notifications())) {
            Permissions.request(this, Permissions.notifications(), Permissions.REQ_NOTIFICATIONS)
        }
        // Ask for the runtime media grants, not all-files access: since Android 13 that is what
        // reading the library needs, and it is a permission users can actually grant in one tap.
        if (!Compat.canReadMedia(this)) {
            Permissions.request(this, Permissions.storage(this), Permissions.REQ_STORAGE)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** SEND and SEND_MULTIPLE of any type queue the shared files straight into the send flow. */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                openTransferWith(listOf(uri))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                if (uris.isNotEmpty()) openTransferWith(uris)
            }
        }
        intent.action = null
    }

    // ------------------------------------------------------------ navigation

    fun selectTab(index: Int) {
        currentTab = index
        pushed = null
        val screen = screens.getOrPut(index) {
            when (index) {
                0 -> ConnectFragment(this, this)
                1 -> FileManagerFragment(this)
                2 -> HistoryFragment(this)
                else -> SettingsFragment(this)
            }
        }
        show(screen)
        rebuildNav()
    }

    private fun rebuildNav() {
        val parent = nav.parent as ViewGroup
        val index = parent.indexOfChild(nav)
        parent.removeViewAt(index)
        nav = W.bottomNav(this, currentTab) { i -> selectTab(i) }
        parent.addView(nav, index, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private var shown: Screen? = null

    private fun show(screen: Screen) {
        if (shown === screen) return
        shown?.onHidden()
        content.removeAllViews()
        content.addView(screen.view(this), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        shown = screen
        screen.onShown()
    }

    /** Push the Transfer / Broadcast screen full screen; the bottom nav stays visible. */
    fun openTransfer(mode: TransferFragment.Mode, title: String? = null) {
        if (!ensureRadio()) return
        val screen = TransferFragment(this, mode, title)
        pushed = screen
        show(screen)
    }

    fun openTransferWith(uris: List<Uri>) {
        if (!ensureRadio()) return
        val screen = TransferFragment(this, TransferFragment.Mode.SEND_PICKED)
        screen.pendingUris = uris
        pushed = screen
        show(screen)
    }

    /**
     * Send and Receive need one radio on: Wi-Fi for the LAN path, Bluetooth for Nearby.
     *
     * Tapping either with both off used to open a screen that could only fail - it scanned for
     * peers on a network that was not there. The radios are the user's to turn on, so the app asks,
     * offers both switches and explains why; it never silently enables anything.
     */
    private fun ensureRadio(): Boolean {
        if (Compat.isWifiConnected(this) || Compat.isBluetoothEnabled(this)) return true
        val content = W.column(this, 8, 8)
        val card = W.card(this, 1, 18f, 16)
        card.addView(W.label(this, getString(R.string.link_needed_title), 18f, ThemeColors.text(this), bold = true))
        card.addView(W.gap(this, 8))
        card.addView(W.label(this, getString(R.string.link_needed_body), 14f, ThemeColors.text(this)))
        content.addView(card)
        content.addView(W.gap(this, 10))
        val row = W.row(this, 4, 4)
        val wifi = W.accentButton(this, getString(R.string.turn_on_wifi))
        val bt = W.outlineButton(this, getString(R.string.turn_on_bluetooth))
        row.addView(wifi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(W.hgap(this, 8))
        row.addView(bt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
        val dialog = AlertDialog.Builder(this).setView(content).setCancelable(true).create()
        wifi.onClick {
            dialog.dismiss()
            Compat.requestWifi(this)
        }
        bt.onClick {
            dialog.dismiss()
            Compat.requestBluetooth(this)
        }
        dialog.show()
        return false
    }

    /** Pushes a secondary screen (Log viewer, Diagnostics, Help) over the current tab. */
    fun push(screen: Screen) {
        pushed = screen
        show(screen)
    }

    /** Pops the pushed screen back to the active tab. */
    fun pop() {
        pushed = null
        selectTab(currentTab)
    }

    fun currentTransfer(): TransferFragment? = pushed as? TransferFragment

    fun closePushed() {
        pushed = null
        selectTab(currentTab)
    }

    override fun onBackPressed() {
        val p = pushed
        if (p != null) {
            p.onBackPressed()
            p.onHidden()
            pushed = null
            selectTab(currentTab)
            return
        }
        if (shown?.onBackPressed() == true) return
        super.onBackPressed()
    }

    // ------------------------------------------------------------ consent popups

    /** Exact copy from the product spec: "Connection request". */
    private fun askConnectionConsent(
        peer: DiscoveredPeer,
        transport: String,
        session: TransportSession,
        answer: (Boolean) -> Unit
    ) {
        runOnUiThread {
            val detail = if (transport == TransportKind.NEARBY) "Bluetooth" else session.peerDetail
            val subtitle = "${getString(R.string.device_type_phone)} \u00b7 ${TransportKind.label(transport)} \u00b7 $detail"
            Ui.consent(
                this,
                getString(R.string.connection_request),
                getString(R.string.connection_request_body, peer.name),
                subtitle,
                getString(R.string.accept),
                getString(R.string.reject)
            ) { accepted -> answer(accepted) }
        }
    }

    /** Exact copy from the product spec: "Browser wants access". */
    private fun askBrowserConsent(browser: String, ip: String, answer: (Boolean) -> Unit) {
        runOnUiThread {
            Ui.consent(
                this,
                getString(R.string.browser_wants_access),
                getString(R.string.browser_wants_body),
                "$browser \u00b7 $ip",
                getString(R.string.accept),
                getString(R.string.reject)
            ) { accepted -> answer(accepted) }
        }
    }

    // ------------------------------------------------------------ plumbing

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = Permissions.grantedResult(grantResults)
        Log.info("Permission result code=$requestCode granted=$ok")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            TransferFragment.REQ_PICK -> {
                if (resultCode != RESULT_OK || data == null) return
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else {
                    @Suppress("DEPRECATION")
                    val single = data.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    val d = data.data
                    if (single != null) uris.add(single) else if (d != null) uris.add(d)
                }
                if (uris.isEmpty()) return
                val transfer = currentTransfer()
                if (transfer != null) transfer.onPickedUris(uris) else openTransferWith(uris)
            }
            SafStore.REQ_TREE -> {
                if (resultCode != RESULT_OK || data?.data == null) return
                Di.saf(this).onTreePicked(data.data!!)
                Ui.toast(this, "Folder granted for received files")
            }
            LogViewerFragment.REQ_EXPORT -> {
                if (resultCode == RESULT_OK && data?.data != null) {
                    val ok = writeExport(data.data!!)
                    if (!ok) Ui.toast(this, getString(R.string.export_failed, "the file could not be written"))
                    val aware = (pushed as? ResultAware) ?: (shown as? ResultAware)
                    aware?.onActivityResultHandled(requestCode, if (ok) RESULT_OK else RESULT_CANCELED)
                    return
                }
                val aware = (pushed as? ResultAware) ?: (shown as? ResultAware)
                aware?.onActivityResultHandled(requestCode, resultCode)
            }
            else -> {
                val aware = (pushed as? ResultAware) ?: (shown as? ResultAware)
                aware?.onActivityResultHandled(requestCode, resultCode)
            }
        }
    }

    /**
     * Writes the exported log to the document the user picked. The text comes from the log store
     * again rather than being handed around, so there is nothing to keep in sync.
     */
    private fun writeExport(uri: Uri): Boolean = try {
        contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(Di.logs(this).exportText().toByteArray())
            out.flush()
        } != null
    } catch (t: Throwable) {
        false
    }

    override fun onResume() {
        super.onResume()
        Log.info("Main resumed - transport=${TransportKind.label(Di.engine(this).transportKind.value)}")
    }

    fun showSheet(title: String, subtitle: String? = null, build: (LinearLayout) -> Unit) {
        val sheet = Ui.bottomSheet(this, title, subtitle)
        build(sheet.root)
        sheet.show()
    }
}
