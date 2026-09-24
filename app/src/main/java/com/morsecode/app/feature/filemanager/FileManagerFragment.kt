package com.morsecode.app.feature.filemanager

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.morsecode.app.MainActivity
import com.morsecode.app.R
import com.morsecode.app.core.model.MediaItem
import com.morsecode.app.core.media.MediaLibrary
import com.morsecode.app.core.util.Thumbs
import com.morsecode.app.core.ui.Screen
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Fmt
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.util.Permissions
import com.morsecode.app.core.storage.SafStore
import com.morsecode.app.core.storage.ShareUris
import com.morsecode.app.di.Di
import com.morsecode.app.util.ThemeColors

/**
 * Files: Photos / Videos / Music / Apps / Documents, a day-grouped grid, an in-place folder
 * drill-down and the green Send bar that appears while files are selected.
 *
 * Nothing is copied or rescanned when the tab is left and re-entered - the list is rebuilt from
 * MediaStore with a single paged query per category (INV-10).
 */
class FileManagerFragment(private val activity: Activity) : Screen {

    enum class Kind(val labelRes: Int) {
        PHOTOS(R.string.sub_photos),
        VIDEOS(R.string.sub_videos),
        MUSIC(R.string.sub_music),
        APPS(R.string.sub_apps),
        DOCUMENTS(R.string.cat_documents)
    }

    private val lib = MediaLibrary(activity)
    private var kind = Kind.PHOTOS
    private val selected = LinkedHashMap<String, MediaItem>()
    private var grid = true
    private var sortIndex = 0
    private var openBucket: String? = null
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

    override fun onBackPressed(): Boolean {
        if (selected.isNotEmpty()) {
            selected.clear()
            rebuild()
            return true
        }
        if (openBucket != null) {
            openBucket = null
            rebuild()
            return true
        }
        return false
    }

    fun selectedUris(): List<Uri> = selected.values.map { Uri.parse(it.uri) }

    // ---------------------------------------------------------------- render

    private fun rebuild() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        body.addView(header())
        body.addView(W.gap(activity, 10))
        body.addView(W.segmented(activity, Kind.values().map { activity.getString(it.labelRes) }, kind.ordinal) { i ->
            kind = Kind.values()[i]
            openBucket = null
            selected.clear()
            rebuild()
        })
        body.addView(W.gap(activity, 12))
        val bucket = openBucket
        when {
            bucket != null -> rebuildBucket(bucket)
            kind == Kind.MUSIC -> rebuildMusic()
            kind == Kind.APPS -> rebuildApps()
            kind == Kind.DOCUMENTS -> rebuildFolders()
            else -> rebuildGrid()
        }
        if (selected.isNotEmpty()) body.addView(sendBar())
        body.addView(W.gap(activity, 26))
    }

    private fun header(): View {
        val ctx = activity
        val row = W.row(ctx)
        val bucket = openBucket
        if (bucket != null) {
            val back = W.iconButton(ctx, R.drawable.ic_back, 34)
            back.onClick { openBucket = null; rebuild() }
            row.addView(back)
            row.addView(W.hgap(ctx, 8))
            row.addView(W.label(ctx, bucket, 19f, ThemeColors.text(ctx), bold = true))
        } else {
            row.addView(W.label(ctx, ctx.getString(R.string.tab_files), 22f, ThemeColors.text(ctx), bold = true))
        }
        row.addView(W.spacer(ctx))
        val view = W.pill(ctx, if (grid) "GRID" else "LIST", ThemeColors.accentStart(ctx))
        view.isClickable = true
        view.onClick { grid = !grid; rebuild() }
        row.addView(view)
        row.addView(W.hgap(ctx, 6))
        val sort = W.iconButton(ctx, R.drawable.ic_list, 34)
        sort.onClick { pickSort() }
        row.addView(sort)
        return row
    }

    private fun pickSort() {
        val opts = arrayListOf("Newest first", "Oldest first", "Largest first", "Name (A-Z)")
        Ui.pick(activity, activity.getString(R.string.sort_by), opts, sortIndex) { i ->
            sortIndex = i
            rebuild()
        }
    }

    private fun sorted(list: List<MediaItem>): List<MediaItem> = when (sortIndex) {
        1 -> list.sortedBy { it.dateMs }
        2 -> list.sortedByDescending { it.size }
        3 -> list.sortedBy { it.displayName.lowercase() }
        else -> list.sortedByDescending { it.dateMs }
    }

    private fun category(): MediaLibrary.Category = when (kind) {
        Kind.VIDEOS -> MediaLibrary.Category.VIDEOS
        Kind.MUSIC -> MediaLibrary.Category.MUSIC
        Kind.APPS -> MediaLibrary.Category.APPS
        Kind.DOCUMENTS -> MediaLibrary.Category.DOCUMENTS
        else -> MediaLibrary.Category.PHOTOS
    }

    private fun emptyText(): String = when (kind) {
        Kind.VIDEOS -> activity.getString(R.string.empty_videos)
        Kind.MUSIC -> activity.getString(R.string.empty_music)
        Kind.APPS -> activity.getString(R.string.empty_apps)
        Kind.DOCUMENTS -> activity.getString(R.string.empty_files)
        else -> activity.getString(R.string.empty_photos)
    }

    private fun rebuildGrid() {
        val ctx = activity
        val items = sorted(lib.all(category()))
        // Says what the library actually returned. A grid that is empty while the phone has
        // photos is then a one-line answer in Settings > Log viewer (and in logcat) instead of a
        // mystery: the count and the category are the two things that tell them apart.
        // The count, the whole-library count and the volume names are the three facts that
        // separate "this phone has no media", "the app is asking the wrong volume" and "the
        // platform is filtering the library to a user-selected subset".
        Log.info(
            "Files: ${items.size} ${kind.name.lowercase()} item(s) from the media library" +
                " (all=${lib.count(MediaLibrary.Category.ALL)}" +
                " volumes=${Compat.externalVolumes(ctx).joinToString(",").ifEmpty { "none" }}" +
                " full=${Compat.hasFullMediaAccess(ctx)} partial=${Compat.hasPartialMediaAccess(ctx)})"
        )
        if (items.isEmpty()) {
            body.addView(emptyState())
            return
        }
        val byDay = LinkedHashMap<String, MutableList<MediaItem>>()
        for (i in items) byDay.getOrPut(Fmt.dayLabel(i.dateMs)) { ArrayList() }.add(i)
        for ((day, dayItems) in byDay) {
            body.addView(dayHeader(day, dayItems))
            if (!grid) {
                val card = W.card(ctx, 0, 18f, 6)
                for ((n, item) in dayItems.withIndex()) {
                    card.addView(fileRow(item, false))
                    if (n < dayItems.size - 1) card.addView(W.divider(ctx))
                }
                body.addView(card)
            } else {
                val rows = (dayItems.size + 2) / 3
                for (r in 0 until rows) {
                    val line = W.row(ctx, 0, 0)
                    for (c in 0 until 3) {
                        val idx = r * 3 + c
                        if (idx >= dayItems.size) line.addView(W.spacer(ctx, 1f))
                        else line.addView(tile(dayItems[idx]), tileParams())
                    }
                    body.addView(line)
                    body.addView(W.gap(ctx, 5))
                }
            }
            body.addView(W.gap(ctx, 12))
        }
    }

    /**
     * "Nothing here" plus, only when it is actually true, a way to grant access.
     *
     * The gate is [Compat.canReadMedia] - the runtime media grants the platform documents - and
     * not all-files access. All-files is still offered, as a second button, because reading
     * non-media documents on Android 11+ needs it; asking for it as the *first* step is what made
     * this screen claim permission was missing when everything needed was already granted.
     */
    private fun emptyState(): View {
        val ctx = activity
        // Android 14 can leave the app holding only `READ_MEDIA_VISUAL_USER_SELECTED`: reading
        // media works, but MediaStore then answers every query with just the items the user picked,
        // so an empty grid is the platform being correct. That is not the same thing as "no
        // permission", and it is not something to hide behind a silent empty state either - the
        // one thing that fixes it is asking for the full grant again.
        val partial = Compat.hasPartialMediaAccess(ctx)
        val readable = Compat.canReadMedia(ctx) && !partial
        val col = W.emptyState(ctx, R.drawable.ic_nav_files, emptyText(),
            when {
                readable -> null
                partial -> ctx.getString(R.string.partial_media_access)
                else -> ctx.getString(R.string.storage_permission_needed)
            })
        if (readable) return col

        col.addView(W.gap(ctx, 8))
        val grant = W.accentButton(ctx, ctx.getString(R.string.grant))
        grant.onClick {
            val wanted = Permissions.storage(activity)
            if (Compat.isApi23 && wanted.isNotEmpty()) {
                Permissions.request(activity, wanted, Permissions.REQ_STORAGE)
            } else {
                Compat.openAllFilesSettings(activity)
            }
        }
        col.addView(grant)

        if (Compat.isApi30) {
            col.addView(W.gap(ctx, 6))
            val allFiles = W.outlineButton(ctx, ctx.getString(R.string.all_files_access))
            allFiles.onClick { Compat.openAllFilesSettings(activity) }
            col.addView(allFiles)
        }
        return col
    }

    private fun dayHeader(day: String, dayItems: List<MediaItem>): View {
        val ctx = activity
        val row = W.row(ctx)
        row.addView(W.label(ctx, day, 14.5f, ThemeColors.text(ctx), bold = true))
        row.addView(W.hgap(ctx, 6))
        val label = if (day == ctx.getString(R.string.today)) ctx.getString(R.string.today_n_items, dayItems.size)
        else if (day == ctx.getString(R.string.yesterday)) ctx.getString(R.string.yesterday_n_items, dayItems.size)
        else ctx.getString(R.string.n_items, dayItems.size)
        row.addView(W.label(ctx, label, 11.5f, ThemeColors.muted(ctx), mono = true))
        row.addView(W.spacer(ctx))
        val selectAll = W.label(ctx, ctx.getString(R.string.select_all), 12f, ThemeColors.accentStart(ctx), bold = true)
        selectAll.isClickable = true
        selectAll.pad(6, 4, 2, 4)
        selectAll.onClick {
            for (i in dayItems) selected[i.uri] = i
            rebuild()
        }
        row.addView(selectAll)
        return row
    }

    private fun tileParams(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, D.dp(activity, 104f), 1f)
        lp.setMargins(D.dp(activity, 4f), 0, D.dp(activity, 4f), 0)
        return lp
    }

    private fun tile(item: MediaItem): View {
        val ctx = activity
        val frame = FrameLayout(ctx)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(D.dp(ctx, 14f).toFloat())
        d.setColor(ThemeColors.card2(ctx))
        frame.background = d
        frame.clipToOutline = true
        frame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val img = ImageView(ctx)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Thumbs.load(img, item, if (kind == Kind.VIDEOS) 300 else 200)
        if (item.durationMs > 0) {
            val badge = W.pill(ctx, Fmt.duration(item.durationMs), 0xE6000000.toInt(), sizeSp = 9.5f)
            badge.setTextColor(0xFFFFFFFF.toInt())
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.setMargins(0, 0, D.dp(ctx, 6f), D.dp(ctx, 6f))
            frame.addView(badge, lp)
        }
        val checked = selected.containsKey(item.uri)
        val check = CheckBox(ctx)
        check.isChecked = checked
        check.isClickable = false
        check.isFocusable = false
        val clp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clp.gravity = Gravity.TOP or Gravity.END
        clp.setMargins(0, D.dp(ctx, 2f), D.dp(ctx, 6f), 0)
        frame.addView(check, clp)
        if (checked) {
            val ring = GradientDrawable()
            ring.setShape(GradientDrawable.RECTANGLE)
            ring.setCornerRadius(D.dp(ctx, 14f).toFloat())
            ring.setStroke(D.dp(ctx, 2.5f), ThemeColors.accentStart(ctx))
            ring.setColor(ThemeColors.withAlpha(ThemeColors.accentStart(ctx), 0.18f))
            val overlay = View(ctx)
            overlay.background = ring
            frame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        frame.isClickable = true
        frame.onClick {
            if (selected.containsKey(item.uri)) selected.remove(item.uri) else selected[item.uri] = item
            rebuild()
        }
        frame.setOnLongClickListener(object : View.OnLongClickListener {
            override fun onLongClick(v: View?): Boolean {
                open(item)
                return true
            }
        })
        return frame
    }

    private fun fileRow(item: MediaItem, showCheck: Boolean): View {
        val ctx = activity
        val row = W.row(ctx, 10, 8)
        val icon = ImageView(ctx)
        icon.layoutParams = LinearLayout.LayoutParams(D.dp(ctx, 40f), D.dp(ctx, 40f))
        var filled = false
        if (kind == Kind.APPS && item.packageName.isNotBlank()) {
            try {
                icon.setImageDrawable(activity.packageManager.getApplicationIcon(item.packageName))
                filled = true
            } catch (t: Throwable) {
            }
        }
        if (!filled && (item.mime.startsWith("image/") || item.mime.startsWith("video/"))) {
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            val dd = GradientDrawable()
            dd.setShape(GradientDrawable.RECTANGLE)
            dd.setCornerRadius(D.dp(ctx, 10f).toFloat())
            dd.setColor(ThemeColors.card2(ctx))
            icon.background = dd
            icon.clipToOutline = true
            Thumbs.load(icon, item, if (item.mime.startsWith("video/")) 128 else 96)
            filled = true
        }
        if (!filled) {
            icon.setImageResource(iconFor(item))
            icon.setColorFilter(ThemeColors.accentStart(ctx))
            icon.setPadding(D.dp(ctx, 9f), D.dp(ctx, 9f), D.dp(ctx, 9f), D.dp(ctx, 9f))
            val dd = GradientDrawable()
            dd.setShape(GradientDrawable.RECTANGLE)
            dd.setCornerRadius(D.dp(ctx, 10f).toFloat())
            dd.setColor(ThemeColors.accentSoft(ctx))
            icon.background = dd
        }
        row.addView(icon)
        row.addView(W.hgap(ctx, 12))
        val col = W.column(ctx)
        col.addView(W.label(ctx, item.displayName, 14f, ThemeColors.text(ctx), bold = true))
        col.addView(W.label(ctx, "${Fmt.size(item.size)} \u00b7 ${Fmt.timeAgo(item.dateMs)}",
            11.5f, ThemeColors.text2(ctx), mono = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (showCheck) {
            val check = CheckBox(ctx)
            check.isChecked = selected.containsKey(item.uri)
            check.isClickable = false
            check.isFocusable = false
            row.addView(check)
        }
        row.addView(W.icon(ctx, R.drawable.ic_more, 18, ThemeColors.muted(ctx)))
        row.isClickable = true
        row.onClick { open(item) }
        return row
    }

    private fun iconFor(item: MediaItem): Int {
        val n = item.displayName.lowercase()
        return when {
            item.mime.startsWith("audio/") -> R.drawable.ic_music
            item.mime.startsWith("image/") -> R.drawable.ic_image
            item.mime.startsWith("video/") -> R.drawable.ic_video
            item.mime == "application/vnd.android.package-archive" || n.endsWith(".apk") -> R.drawable.ic_apk
            n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") -> R.drawable.ic_file
            item.mime == "application/pdf" -> R.drawable.ic_file
            item.mime.startsWith("text/") -> R.drawable.ic_file
            else -> R.drawable.ic_file
        }
    }

    private fun open(item: MediaItem) {
        if (selected.isNotEmpty()) {
            if (selected.containsKey(item.uri)) selected.remove(item.uri) else selected[item.uri] = item
            rebuild()
            return
        }
        if (item.mime.startsWith("image/")) {
            activity.startActivity(Intent(activity, com.morsecode.app.feature.viewer.ViewerActivity::class.java)
                .putExtra("uri", item.uri)
                .putExtra("name", item.displayName)
                .putExtra("mime", item.mime)
                .putExtra("size", item.size)
                .putExtra("width", item.width)
                .putExtra("height", item.height))
        } else if (item.mime.startsWith("video/") || item.mime.startsWith("audio/")) {
            activity.startActivity(Intent(activity, com.morsecode.app.feature.viewer.PlayerActivity::class.java)
                .putExtra("uri", item.uri)
                .putExtra("name", item.displayName)
                .putExtra("mime", item.mime)
                .putExtra("size", item.size))
        } else {
            try {
                activity.startActivity(ShareUris.viewIntent(activity, item.uri, item.mime))
            } catch (t: Throwable) {
                Ui.toast(activity, activity.getString(R.string.no_app_for_share))
            }
        }
    }

    // ---------------------------------------------------------------- music / apps

    private fun rebuildMusic() {
        val ctx = activity
        val items = sorted(lib.all(MediaLibrary.Category.MUSIC))
        if (items.isEmpty()) {
            body.addView(emptyState())
            return
        }
        val card = W.card(ctx, 0, 18f, 6)
        for ((i, song) in items.withIndex()) {
            val row = W.row(ctx, 12, 8)
            row.addView(W.icon(ctx, R.drawable.ic_music, 22, ThemeColors.accentStart(ctx)))
            row.addView(W.hgap(ctx, 12))
            val col = W.column(ctx)
            col.addView(W.label(ctx, song.displayName, 14f, ThemeColors.text(ctx), bold = true))
            col.addView(W.label(ctx, "${song.bucket} \u00b7 ${Fmt.size(song.size)}", 11.5f, ThemeColors.text2(ctx), mono = true))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val check = CheckBox(ctx)
            check.isChecked = selected.containsKey(song.uri)
            check.isClickable = false
            check.isFocusable = false
            row.addView(check)
            row.isClickable = true
            row.onClick {
                if (selected.containsKey(song.uri)) selected.remove(song.uri) else selected[song.uri] = song
                rebuild()
            }
            row.setOnLongClickListener(object : View.OnLongClickListener {
                override fun onLongClick(v: View?): Boolean {
                    open(song)
                    return true
                }
            })
            card.addView(row)
            if (i < items.size - 1) card.addView(W.divider(ctx))
        }
        body.addView(card)
    }

    private fun rebuildApps() {
        val ctx = activity
        val apps = lib.installedApps()
        if (apps.isEmpty()) {
            body.addView(emptyState())
            return
        }
        body.addView(W.label(ctx, ctx.getString(R.string.send_apk).uppercase(), 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        body.addView(W.gap(ctx, 6))
        val card = W.card(ctx, 0, 18f, 6)
        for ((i, app) in apps.withIndex()) {
            card.addView(fileRow(app, true))
            if (i < apps.size - 1) card.addView(W.divider(ctx))
        }
        body.addView(card)
    }

    // ---------------------------------------------------------------- folders

    private fun rebuildFolders() {
        val ctx = activity
        val docs = lib.all(MediaLibrary.Category.DOCUMENTS)
        val folders = LinkedHashMap<String, MutableList<MediaItem>>()
        for (d in docs) folders.getOrPut(d.bucket.ifBlank { ctx.getString(R.string.folder_download) }) { ArrayList() }.add(d)
        if (folders.isEmpty()) {
            body.addView(emptyState())
            return
        }
        body.addView(W.label(ctx, ctx.getString(R.string.folders).uppercase(), 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        body.addView(W.gap(ctx, 6))
        val card = W.card(ctx, 0, 18f, 6)
        var first = true
        for ((name, list) in folders) {
            val row = W.row(ctx, 12, 8)
            row.addView(W.icon(ctx, R.drawable.ic_folder, 22, ThemeColors.accentStart(ctx)))
            row.addView(W.hgap(ctx, 12))
            val col = W.column(ctx)
            col.addView(W.label(ctx, name, 14.5f, ThemeColors.text(ctx), bold = true))
            col.addView(W.label(ctx, "${list.size} \u00b7 ${Fmt.size(list.sumOf { it.size })}",
                11.5f, ThemeColors.text2(ctx), mono = true))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(W.icon(ctx, R.drawable.ic_chevron_right, 18, ThemeColors.muted(ctx)))
            row.isClickable = true
            row.onClick {
                openBucket = name
                rebuild()
            }
            if (!first) card.addView(W.divider(ctx))
            card.addView(row)
            first = false
        }
        body.addView(card)
        body.addView(W.gap(activity, 10))
        val add = W.outlineButton(activity, activity.getString(R.string.add_folder))
        add.onClick {
            val host = activity as? MainActivity
            host?.openTransfer(com.morsecode.app.feature.transfer.TransferFragment.Mode.SEND_PICKED,
                activity.getString(R.string.send_files))
        }
        body.addView(add)
        body.addView(W.gap(activity, 12))
        body.addView(storageCard())
    }

    private fun rebuildBucket(bucket: String) {
        val ctx = activity
        val docs = sorted(lib.all(MediaLibrary.Category.DOCUMENTS).filter { it.bucket == bucket })
        if (docs.isEmpty()) {
            body.addView(emptyState())
            return
        }
        val card = W.card(ctx, 0, 18f, 6)
        for ((i, file) in docs.withIndex()) {
            val row = W.row(ctx, 12, 8)
            row.addView(W.icon(ctx, iconFor(file), 22, ThemeColors.accentStart(ctx)))
            row.addView(W.hgap(ctx, 12))
            val col = W.column(ctx)
            col.addView(W.label(ctx, file.displayName, 14f, ThemeColors.text(ctx), bold = true))
            col.addView(W.label(ctx, "${Fmt.size(file.size)} \u00b7 ${Fmt.timeAgo(file.dateMs)}",
                11.5f, ThemeColors.text2(ctx), mono = true))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val check = CheckBox(ctx)
            check.isChecked = selected.containsKey(file.uri)
            check.isClickable = false
            check.isFocusable = false
            row.addView(check)
            row.isClickable = true
            row.onClick {
                if (selected.containsKey(file.uri)) selected.remove(file.uri) else selected[file.uri] = file
                rebuild()
            }
            card.addView(row)
            if (i < docs.size - 1) card.addView(W.divider(ctx))
        }
        body.addView(card)
    }

    private fun storageCard(): View {
        val ctx = activity
        val stat = lib.storage()
        val card = W.card(ctx)
        card.addView(W.label(ctx, ctx.getString(R.string.folder_internal), 14.5f, ThemeColors.text(ctx), bold = true))
        card.addView(W.label(ctx, "${Fmt.size(stat.free)} free of ${Fmt.size(stat.total)}",
            12f, ThemeColors.text2(ctx), mono = true))
        card.addView(W.gap(ctx, 8))
        val bar = W.progressBar(ctx, 6)
        bar.setTrack(ThemeColors.accentStart(ctx))
        bar.setProgress(if (stat.total <= 0) 0f else stat.used.toFloat() / stat.total.toFloat())
        card.addView(bar)
        card.addView(W.gap(ctx, 10))
        card.addView(W.divider(ctx))
        val row = W.row(ctx)
        row.addView(W.label(ctx, ctx.getString(R.string.set_default_download), 14f, ThemeColors.text(ctx)))
        row.addView(W.spacer(ctx))
        row.addView(W.icon(ctx, R.drawable.ic_chevron_right, 18, ThemeColors.muted(ctx)))
        row.isClickable = true
        row.onClick { pickDownloadFolder() }
        card.addView(row)
        return card
    }

    private fun pickDownloadFolder() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            activity.startActivityForResult(intent, SafStore.REQ_TREE)
        } catch (t: Throwable) {
            Ui.toast(activity, "No system file picker on this device")
        }
    }

    // ---------------------------------------------------------------- selection bar

    private fun sendBar(): View {
        val ctx = activity
        val total = selected.values.sumOf { it.size }
        val bar = W.row(ctx, 10, 14)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setColor(ThemeColors.card2(ctx))
        d.setStroke(D.dp(ctx, 1f), ThemeColors.line2(ctx))
        bar.background = d
        bar.addView(W.label(ctx, ctx.getString(R.string.n_selected, selected.size), 13f, ThemeColors.text(ctx), bold = true))
        bar.addView(W.hgap(ctx, 8))
        bar.addView(W.label(ctx, Fmt.size(total), 11.5f, ThemeColors.text2(ctx), mono = true))
        bar.addView(W.spacer(ctx))
        val share = W.iconButton(ctx, R.drawable.ic_share, 38, 0, false)
        share.onClick { shareSelected() }
        val delete = W.iconButton(ctx, R.drawable.ic_trash, 38, 0, false)
        delete.onClick { confirmDelete() }
        val close = W.iconButton(ctx, R.drawable.ic_close, 38, 0, false)
        close.onClick { selected.clear(); rebuild() }
        bar.addView(share)
        bar.addView(W.hgap(ctx, 4))
        bar.addView(delete)
        bar.addView(W.hgap(ctx, 4))
        bar.addView(close)
        bar.addView(W.hgap(ctx, 8))
        val send = W.successButton(ctx, ctx.getString(R.string.send))
        send.onClick { startSendFlow() }
        bar.addView(send)
        return bar
    }

    private fun startSendFlow() {
        val uris = selectedUris()
        if (uris.isEmpty()) return
        val host = activity as? MainActivity ?: return
        selected.clear()
        host.openTransferWith(uris)
    }

    private fun shareSelected() {
        val picked = selected.values.map { it.uri }
        if (picked.isEmpty()) return
        try {
            activity.startActivity(ShareUris.shareIntent(activity, picked, "*/*"))
        } catch (t: Throwable) {
            Ui.toast(activity, activity.getString(R.string.no_app_for_share))
        }
    }

    private fun confirmDelete() {
        val count = selected.size
        Ui.confirm(activity, activity.getString(R.string.delete_files_title),
            activity.getString(R.string.delete_files_body, count),
            positive = activity.getString(R.string.delete),
            onPositive = {
                var deleted = 0
                for (item in selected.values) {
                    try {
                        val rows = activity.contentResolver.delete(Uri.parse(item.uri), null, null)
                        if (rows > 0) deleted++
                    } catch (t: Throwable) {
                    }
                }
                selected.clear()
                rebuild()
                Ui.toast(activity, "$deleted deleted")
            },
            negative = activity.getString(R.string.cancel),
            destructive = true)
    }
}
