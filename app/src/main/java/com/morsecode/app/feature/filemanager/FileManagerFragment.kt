package com.morsecode.app.feature.filemanager

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import java.io.File
import java.util.concurrent.Executors

/**
 * Files: Photos / Videos / Music / Apps / Files.
 *
 * Three things shape this screen, and all three were user complaints first:
 *
 * 1. **Tapping a file must not rebuild the screen.** It used to call `rebuild()` for every tap,
 *    which re-queried MediaStore, threw away every tile and decoded every thumbnail again - the
 *    freeze-and-die behaviour on a big grid. A tap now updates one check mark and the Send bar.
 * 2. **The Send bar is pinned.** It used to be appended to the scroll content, so on a long grid it
 *    sat below the fold and looked missing. It is an overlay above the bottom navigation now.
 * 3. **Data is read off the main thread.** Opening the tab shows its chrome immediately and fills
 *    in when the query returns, instead of blocking the tab switch on MediaStore.
 *
 * The Files tab itself is the design's category hub: Documents / Ebooks / Archives / APKs / Large
 * files, plus folders (Download, Internal storage, anything the user added) that open into a browser
 * with a clickable address bar. Files and folders are both selectable; a selected folder is
 * expanded into its contents when the batch is sent.
 */
class FileManagerFragment(private val activity: Activity) : Screen {

    enum class Kind(val labelRes: Int) {
        PHOTOS(R.string.sub_photos),
        VIDEOS(R.string.sub_videos),
        MUSIC(R.string.sub_music),
        APPS(R.string.sub_apps),
        FILES(R.string.cat_files)
    }

    /** Anything selectable: a MediaStore row, or a file/folder found on disk. */
    private class Picked(
        val uri: String,
        val name: String,
        val mime: String,
        val size: Long,
        val dateMs: Long,
        val isDir: Boolean,
        val path: String?
    )

    private val lib = MediaLibrary(activity)
    private var kind = Kind.PHOTOS
    private val selected = LinkedHashMap<String, Picked>()
    private var grid = true
    private var sortIndex = 0

    /** Where the Files tab is: the hub, a category list, or a folder. */
    private var group: MediaLibrary.FileGroup? = null
    private var dir: File? = null

    private lateinit var body: LinearLayout
    private var barHost: FrameLayout? = null
    private var bar: View? = null
    private val checks = HashMap<String, CheckBox>()
    private val rings = HashMap<String, View>()
    private var loading: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mc-files").apply { isDaemon = true } }

    /** Guards against a slow query from a previous tab landing on the new one. */
    @Volatile private var generation = 0

    override fun view(ctx: Activity): View {
        val root = FrameLayout(ctx)
        root.setBackgroundColor(ThemeColors.bg(ctx))
        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        body = W.column(ctx, 0, 14)
        scroll.addView(body)
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        barHost = root
        return root
    }

    override fun onShown() = rebuild()

    override fun onHidden() {
        generation++
    }

    override fun onBackPressed(): Boolean {
        if (selected.isNotEmpty()) {
            selected.clear()
            refreshIndicators()
            return true
        }
        if (dir != null) {
            val parent = dir?.parentFile
            if (parent != null && parent.canRead()) dir = parent else dir = null
            rebuild()
            return true
        }
        if (group != null) {
            group = null
            rebuild()
            return true
        }
        return false
    }

    fun selectedUris(): List<Uri> = selected.values.map { Uri.parse(it.uri) }

    // ---------------------------------------------------------------- render

    private fun rebuild() {
        if (!::body.isInitialized) return
        generation++
        val gen = generation
        body.removeAllViews()
        // Indicators from the previous build belong to detached views; keeping them would grow the
        // maps forever and repaint nothing.
        checks.clear()
        rings.clear()
        body.addView(header())
        body.addView(W.gap(activity, 6))
        body.addView(pillRow())
        body.addView(W.gap(activity, 10))
        showContent(gen)
        body.addView(W.gap(activity, 90))
        refreshBar()
    }

    /** The pill row scrolls sideways rather than clipping on a narrow phone. */
    private fun pillRow(): View {
        val ctx = activity
        val scroll = HorizontalScrollView(ctx)
        scroll.isHorizontalScrollBarEnabled = false
        val row = W.row(ctx, 0, 14)
        var first = true
        for (k in Kind.values()) {
            if (!first) row.addView(W.hgap(ctx, 6))
            val active = k == kind
            val pill = W.pill(ctx, ctx.getString(k.labelRes), ThemeColors.accentStart(ctx), filled = active, sizeSp = 12.5f)
            pill.pad(12, 7, 12, 7)
            pill.isClickable = true
            pill.onClick {
                if (kind != k) {
                    kind = k
                    group = null
                    dir = null
                    rebuild()
                }
            }
            row.addView(pill)
            first = false
        }
        scroll.addView(row)
        return scroll
    }

    private fun header(): View {
        val ctx = activity
        val row = W.row(ctx)
        val inFolder = dir != null
        if (inFolder || group != null) {
            val back = W.iconButton(ctx, R.drawable.ic_back, 34)
            back.onClick {
                if (inFolder) {
                    val parent = dir?.parentFile
                    dir = if (parent != null && parent.canRead()) parent else null
                } else {
                    group = null
                }
                rebuild()
            }
            row.addView(back)
            row.addView(W.hgap(ctx, 8))
        }
        val title = when {
            inFolder -> dir?.name?.ifBlank { ctx.getString(R.string.storage_root) } ?: ""
            group != null -> groupLabel(group!!)
            else -> ctx.getString(R.string.tab_files)
        }
        row.addView(W.label(ctx, title, 21f, ThemeColors.text(ctx), bold = true))
        row.addView(W.spacer(ctx))
        if (!inFolder && group == null && (kind == Kind.PHOTOS || kind == Kind.VIDEOS)) {
            val view = W.pill(ctx, if (grid) ctx.getString(R.string.grid) else ctx.getString(R.string.list), ThemeColors.accentStart(ctx))
            view.isClickable = true
            view.onClick { grid = !grid; rebuild() }
            row.addView(view)
            row.addView(W.hgap(ctx, 6))
        }
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

    // ---------------------------------------------------------------- content

    /**
     * The tab's chrome is on screen before any query runs; the list appears when the data does.
     * MediaStore on a phone with thousands of photos takes a moment, and making the tab switch wait
     * for it was the "it takes some seconds before switching" complaint.
     */
    private fun showContent(gen: Int) {
        val ctx = activity
        val placeholder = W.column(ctx, 24, 20)
        placeholder.setGravity(Gravity.CENTER)
        placeholder.addView(W.label(ctx, ctx.getString(R.string.loading), 13f, ThemeColors.text2(ctx), mono = true))
        loading = placeholder
        body.addView(placeholder)

        when {
            dir != null -> loadFolder(dir!!, gen)
            group != null -> loadGroup(group!!, gen)
            kind == Kind.APPS -> loadApps(gen)
            kind == Kind.MUSIC -> loadMedia(MediaLibrary.Category.MUSIC, gen)
            kind == Kind.FILES -> loadHub(gen)
            else -> loadMedia(
                if (kind == Kind.VIDEOS) MediaLibrary.Category.VIDEOS else MediaLibrary.Category.PHOTOS, gen
            )
        }
    }

    private inline fun offThread(crossinline work: () -> Unit) {
        io.execute { work() }
    }

    private fun onUi(gen: Int, build: () -> View) {
        ui.post {
            if (gen != generation || !::body.isInitialized) return@post
            replaceLoading(build())
            refreshIndicators()
            refreshBar()
        }
    }

    /** Swaps the "Loading" placeholder for the real content, in place. */
    private fun replaceLoading(content: View) {
        val placeholder = loading
        loading = null
        if (placeholder != null) {
            val at = body.indexOfChild(placeholder)
            if (at >= 0) {
                body.removeViewAt(at)
                body.addView(content, at)
                return
            }
        }
        body.addView(content)
    }

    private fun loadMedia(category: MediaLibrary.Category, gen: Int) {
        offThread {
            val items = sorted(lib.all(category))
            Log.info("Files: ${items.size} ${category.name.lowercase()} item(s) (${lib.lastNote})")
            onUi(gen) { mediaList(items) }
        }
    }

    private fun loadApps(gen: Int) {
        offThread {
            val apps = lib.installedApps()
            onUi(gen) { appList(apps) }
        }
    }

    private fun loadGroup(g: MediaLibrary.FileGroup, gen: Int) {
        offThread {
            val items = sorted(lib.listGroup(g, 0))
            Log.info("Files: ${g.name} ${items.size} item(s) (${lib.lastNote})")
            onUi(gen) { mediaList(items) }
        }
    }

    // ---------------------------------------------------------------- the hub

    /**
     * The design's Files landing page: category rows with live counts, then folders.
     * Counts are queried off the main thread and filled in as they arrive, so the page is
     * interactive immediately on a phone with a large library.
     */
    private fun loadHub(gen: Int) {
        val ctx = activity
        val col = W.column(ctx)

        col.addView(W.label(ctx, ctx.getString(R.string.categories).uppercase(), 11f,
            ThemeColors.muted(ctx), bold = true, mono = true))
        col.addView(W.gap(ctx, 6))
        val catCard = W.card(ctx, 0, 18f, 6)
        val countViews = HashMap<MediaLibrary.FileGroup, android.widget.TextView>()
        var first = true
        for (g in MediaLibrary.FileGroup.values()) {
            if (!first) catCard.addView(W.divider(ctx))
            first = false
            val row = W.row(ctx, 12, 8)
            row.addView(categoryIcon(g))
            row.addView(W.hgap(ctx, 12))
            row.addView(W.label(ctx, groupLabel(g), 14.5f, ThemeColors.text(ctx), bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val count = W.label(ctx, "\u2026", 12f, ThemeColors.text2(ctx), mono = true)
            countViews[g] = count
            row.addView(count)
            row.addView(W.hgap(ctx, 8))
            row.addView(W.icon(ctx, R.drawable.ic_chevron_right, 18, ThemeColors.muted(ctx)))
            row.isClickable = true
            row.onClick { group = g; dir = null; selected.clear(); rebuild() }
            catCard.addView(row)
        }
        col.addView(catCard)
        col.addView(W.gap(ctx, 16))

        col.addView(W.label(ctx, ctx.getString(R.string.folders).uppercase(), 11f,
            ThemeColors.muted(ctx), bold = true, mono = true))
        col.addView(W.gap(ctx, 6))
        val folderCard = W.card(ctx, 0, 18f, 6)
        first = true
        for ((name, f) in folderEntries()) {
            if (!first) folderCard.addView(W.divider(ctx))
            first = false
            folderCard.addView(folderRow(name, f))
        }
        folderCard.addView(W.divider(ctx))
        val add = W.row(ctx, 12, 8)
        add.addView(W.icon(ctx, R.drawable.ic_add, 22, ThemeColors.accentStart(ctx)))
        add.addView(W.hgap(ctx, 12))
        add.addView(W.label(ctx, ctx.getString(R.string.add_folder), 14.5f,
            ThemeColors.accentStart(ctx), bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add.isClickable = true
        add.onClick { pickTree() }
        folderCard.addView(add)
        col.addView(folderCard)
        col.addView(W.gap(ctx, 14))
        col.addView(storageCard())

        replaceLoading(col)

        // Counts arrive when the queries do.
        offThread {
            val counts = LinkedHashMap<MediaLibrary.FileGroup, Int>()
            for (g in MediaLibrary.FileGroup.values()) counts[g] = lib.listGroup(g, 0).size
            ui.post {
                if (gen != generation) return@post
                for ((g, n) in counts) countViews[g]?.text = n.toString()
            }
        }
    }

    /** The wording lives here, not in the library: compat/ has no R class. */
    private fun groupLabel(g: MediaLibrary.FileGroup): String = activity.getString(
        when (g) {
            MediaLibrary.FileGroup.DOCUMENTS -> R.string.cat_documents
            MediaLibrary.FileGroup.EBOOKS -> R.string.cat_ebooks
            MediaLibrary.FileGroup.ARCHIVES -> R.string.cat_archives
            MediaLibrary.FileGroup.APKS -> R.string.cat_apks
            MediaLibrary.FileGroup.LARGE -> R.string.cat_large
        }
    )

    private fun categoryIcon(g: MediaLibrary.FileGroup): View {
        val ctx = activity
        val res = when (g) {
            MediaLibrary.FileGroup.DOCUMENTS -> R.drawable.ic_file
            MediaLibrary.FileGroup.EBOOKS -> R.drawable.ic_book
            MediaLibrary.FileGroup.ARCHIVES -> R.drawable.ic_archive
            MediaLibrary.FileGroup.APKS -> R.drawable.ic_apk
            MediaLibrary.FileGroup.LARGE -> R.drawable.ic_stats
        }
        return W.icon(ctx, res, 22, ThemeColors.accentStart(ctx))
    }

    /** Download, Internal storage, and anything the user has added. */
    private fun folderEntries(): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        val downloads = Compat.publicDir("Download")
        if (downloads != null) out.add(activity.getString(R.string.folder_download) to downloads)
        val root = Compat.externalRoot()
        if (root != null) out.add(activity.getString(R.string.storage_root) to root)
        for (f in Di.saf(activity).folders()) {
            val path = Compat.pathFromTreeUri(f.uri)
            if (path != null) out.add(f.name to path)
        }
        return out
    }

    private fun folderRow(name: String, f: File): View {
        val ctx = activity
        val row = W.row(ctx, 12, 8)
        row.addView(W.icon(ctx, R.drawable.ic_folder, 22, ThemeColors.accentStart(ctx)))
        row.addView(W.hgap(ctx, 12))
        val col = W.column(ctx)
        col.addView(W.label(ctx, name, 14.5f, ThemeColors.text(ctx), bold = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(W.icon(ctx, R.drawable.ic_chevron_right, 18, ThemeColors.muted(ctx)))
        row.isClickable = true
        row.onClick { openDir(f) }
        return row
    }

    private fun openDir(f: File) {
        dir = f
        selected.clear()
        rebuild()
    }

    private fun pickTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            activity.startActivityForResult(intent, SafStore.REQ_TREE)
        } catch (t: Throwable) {
            Ui.toast(activity, "No system file picker on this device")
        }
    }

    // ---------------------------------------------------------------- browser

    /**
     * A real folder, with a clickable address bar and real file names.
     *
     * MediaStore's unified table reports a document's name, type and size but nothing about the
     * folders around it, which is why "Documents" looked like a flat list of files named after
     * whatever the provider called them. This walks the directory itself.
     */
    private fun loadFolder(folder: File, gen: Int) {
        offThread {
            val listing = try {
                folder.listFiles()
            } catch (t: Throwable) {
                null
            }
            val readable = folder.canRead() && listing != null
            val dirs = ArrayList<File>()
            val files = ArrayList<File>()
            if (listing != null) {
                for (f in listing) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) dirs.add(f) else files.add(f)
                }
            }
            val byName = compareBy<File> { it.name.lowercase() }
            dirs.sortWith(byName)
            files.sortWith(byName)
            Log.info("Files: folder ${folder.name} -> ${dirs.size} folder(s), ${files.size} file(s), readable=$readable")
            onUi(gen) { folderListing(folder, readable, dirs, files) }
        }
    }

    private fun folderListing(folder: File, readable: Boolean, dirs: List<File>, files: List<File>): View {
        val ctx = activity
        val col = W.column(ctx)
        col.addView(addressBar(folder))
        col.addView(W.gap(ctx, 10))
        if (!readable) {
            col.addView(W.emptyState(ctx, R.drawable.ic_folder, ctx.getString(R.string.folder_needs_access)))
            col.addView(W.gap(ctx, 8))
            val allow = W.accentButton(ctx, ctx.getString(R.string.all_files_access))
            allow.onClick { Compat.openAllFilesSettings(activity) }
            col.addView(allow)
            col.addView(W.gap(ctx, 6))
            val pick = W.outlineButton(ctx, ctx.getString(R.string.add_folder))
            pick.onClick { pickTree() }
            col.addView(pick)
            return col
        }
        if (dirs.isEmpty() && files.isEmpty()) {
            col.addView(W.emptyState(ctx, R.drawable.ic_folder, ctx.getString(R.string.empty_folder)))
            return col
        }
        val card = W.card(ctx, 0, 18f, 6)
        var first = true
        for (d in dirs) {
            if (!first) card.addView(W.divider(ctx))
            first = false
            card.addView(browserRow(d, isDir = true))
        }
        for (f in files) {
            if (!first) card.addView(W.divider(ctx))
            first = false
            card.addView(browserRow(f, isDir = false))
        }
        col.addView(card)
        return col
    }

    /** Breadcrumb: every segment is a button that navigates straight to it. */
    private fun addressBar(folder: File): View {
        val ctx = activity
        val scroll = HorizontalScrollView(ctx)
        scroll.isHorizontalScrollBarEnabled = false
        val row = W.row(ctx, 6, 10)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(D.dp(ctx, 12f).toFloat())
        d.setColor(ThemeColors.card2(ctx))
        row.background = d

        val chain = ArrayList<File>()
        var f: File? = folder
        while (f != null) {
            chain.add(f)
            f = f.parentFile
        }
        chain.reverse()
        val root = Compat.externalRoot()
        var first = true
        for (part in chain) {
            if (!first) row.addView(W.label(ctx, "\u203a", 13f, ThemeColors.muted(ctx)))
            first = false
            val isRoot = root != null && part.absolutePath == root.absolutePath
            val name = if (isRoot) ctx.getString(R.string.storage_root) else part.name
            val seg = W.label(ctx, name, 12.5f,
                if (part == folder) ThemeColors.accentStart(ctx) else ThemeColors.text2(ctx),
                bold = part == folder, mono = true)
            seg.isClickable = true
            seg.pad(6, 4, 6, 4)
            seg.onClick {
                if (part != folder) {
                    dir = part
                    rebuild()
                }
            }
            row.addView(seg)
        }
        scroll.addView(row)
        return scroll
    }

    /**
     * One row in a folder. Tapping toggles selection (the whole point of the screen is choosing
     * files to send, and folders can be chosen too); the chevron opens a folder, and a long press
     * opens a file instead of selecting it.
     */
    private fun browserRow(f: File, isDir: Boolean): View {
        val ctx = activity
        val uri = Uri.fromFile(f).toString()
        val row = W.row(ctx, 10, 8)
        val icon = ImageView(ctx)
        icon.layoutParams = LinearLayout.LayoutParams(D.dp(ctx, 40f), D.dp(ctx, 40f))
        if (!isDir && isImageName(f.name)) {
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            val dd = GradientDrawable()
            dd.setShape(GradientDrawable.RECTANGLE)
            dd.setCornerRadius(D.dp(ctx, 10f).toFloat())
            dd.setColor(ThemeColors.card2(ctx))
            icon.background = dd
            icon.clipToOutline = true
            Thumbs.loadFile(icon, f)
        } else {
            icon.setImageResource(if (isDir) R.drawable.ic_folder else iconForName(f.name))
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
        col.addView(W.label(ctx, f.name, 14f, ThemeColors.text(ctx), bold = true))
        val meta = if (isDir) {
            val n = try { f.listFiles()?.size ?: 0 } catch (t: Throwable) { 0 }
            "$n \u00b7 ${Fmt.size(folderSize(f))}"
        } else {
            "${Fmt.size(f.length())} \u00b7 ${Fmt.timeAgo(f.lastModified())}"
        }
        col.addView(W.label(ctx, meta, 11.5f, ThemeColors.text2(ctx), mono = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val check = CheckBox(ctx)
        check.isChecked = selected.containsKey(uri)
        check.isClickable = false
        check.isFocusable = false
        checks[uri] = check
        row.addView(check)
        if (isDir) {
            val open = W.iconButton(ctx, R.drawable.ic_chevron_right, 30, ThemeColors.muted(ctx), false)
            open.onClick { openDir(f) }
            row.addView(open)
        }
        val picked = Picked(uri, f.name, mimeForName(f.name), f.length(), f.lastModified(), isDir, f.absolutePath)
        row.isClickable = true
        row.onClick { toggle(picked) }
        row.setOnLongClickListener(object : View.OnLongClickListener {
            override fun onLongClick(v: View?): Boolean {
                if (isDir) openDir(f) else openFile(f)
                return true
            }
        })
        return row
    }

    private fun folderSize(f: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(f)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 5000) {
            val cur = stack.removeFirst()
            val kids = try { cur.listFiles() } catch (t: Throwable) { null } ?: continue
            for (k in kids) {
                if (k.isDirectory) stack.addLast(k) else total += k.length()
            }
        }
        return total
    }

    private fun openFile(f: File) {
        try {
            activity.startActivity(ShareUris.viewIntent(activity, Uri.fromFile(f).toString(), mimeForName(f.name)))
        } catch (t: Throwable) {
            Ui.toast(activity, activity.getString(R.string.no_app_for_share))
        }
    }

    // ---------------------------------------------------------------- lists

    private fun mediaList(items: List<MediaItem>): View {
        val ctx = activity
        if (items.isEmpty()) return emptyState()
        if (kind == Kind.MUSIC || kind == Kind.APPS) return rowList(items)
        if (grid) return gridList(items)
        return rowList(items)
    }

    private fun appList(apps: List<MediaItem>): View {
        val ctx = activity
        if (apps.isEmpty()) return emptyState()
        return rowList(apps)
    }

    private fun gridList(items: List<MediaItem>): View {
        val ctx = activity
        val col = W.column(ctx)
        val byDay = LinkedHashMap<String, MutableList<MediaItem>>()
        for (i in items) byDay.getOrPut(Fmt.dayLabel(i.dateMs)) { ArrayList() }.add(i)
        for ((day, dayItems) in byDay) {
            col.addView(dayHeader(day, dayItems))
            val perRow = gridColumns()
            val rows = (dayItems.size + perRow - 1) / perRow
            for (r in 0 until rows) {
                val line = W.row(ctx, 0, 0)
                for (c in 0 until perRow) {
                    val idx = r * perRow + c
                    if (idx >= dayItems.size) line.addView(W.spacer(ctx, 1f))
                    else line.addView(tile(dayItems[idx]), tileParams(perRow))
                }
                col.addView(line)
                col.addView(W.gap(ctx, 5))
            }
            col.addView(W.gap(ctx, 12))
        }
        return col
    }

    /** Row list: grouped by day for the media tabs, one flat card for music and apps. */
    private fun rowList(items: List<MediaItem>): View {
        val ctx = activity
        val col = W.column(ctx)
        val grouped = kind == Kind.PHOTOS || kind == Kind.VIDEOS
        if (!grouped) {
            val card = W.card(ctx, 0, 18f, 6)
            for ((i, item) in items.withIndex()) {
                card.addView(mediaRow(item))
                if (i < items.size - 1) card.addView(W.divider(ctx))
            }
            col.addView(card)
            return col
        }
        val byDay = LinkedHashMap<String, MutableList<MediaItem>>()
        for (i in items) byDay.getOrPut(Fmt.dayLabel(i.dateMs)) { ArrayList() }.add(i)
        for ((day, dayItems) in byDay) {
            col.addView(dayHeader(day, dayItems))
            val card = W.card(ctx, 0, 18f, 6)
            for ((n, item) in dayItems.withIndex()) {
                card.addView(mediaRow(item))
                if (n < dayItems.size - 1) card.addView(W.divider(ctx))
            }
            col.addView(card)
            col.addView(W.gap(ctx, 12))
        }
        return col
    }

    /**
     * "Today · 3 items", never "Today Today 3 items".
     *
     * The count label used to repeat the day name (`yesterday_n_items` is "Yesterday %d items"),
     * which is the duplicate the user saw. The count stands alone now, and Select all is a toggle:
     * tapping it while the whole day is selected clears it again.
     */
    private fun dayHeader(day: String, dayItems: List<MediaItem>): View {
        val ctx = activity
        val row = W.row(ctx)
        row.addView(W.label(ctx, day, 14.5f, ThemeColors.text(ctx), bold = true))
        row.addView(W.hgap(ctx, 8))
        row.addView(W.label(ctx, ctx.getString(R.string.n_items, dayItems.size), 11.5f,
            ThemeColors.muted(ctx), mono = true))
        row.addView(W.spacer(ctx))
        val allSelected = dayItems.all { selected.containsKey(it.uri) }
        val selectAll = W.label(ctx, ctx.getString(if (allSelected) R.string.clear_all else R.string.select_all),
            12f, ThemeColors.accentStart(ctx), bold = true)
        selectAll.isClickable = true
        selectAll.pad(6, 4, 2, 4)
        selectAll.onClick {
            if (allSelected) {
                for (i in dayItems) selected.remove(i.uri)
            } else {
                for (i in dayItems) selected[i.uri] = mediaPicked(i)
            }
            refreshIndicators()
            rebuild()
        }
        row.addView(selectAll)
        return row
    }

    private fun gridColumns(): Int {
        val widthDp = activity.resources.configuration.screenWidthDp
        return (widthDp / 112).coerceIn(2, 6)
    }

    private fun tileParams(perRow: Int): LinearLayout.LayoutParams {
        val widthDp = activity.resources.configuration.screenWidthDp
        val cell = (widthDp / perRow) - 10
        val height = (cell * 1.12f).toInt().coerceIn(84, 220)
        val lp = LinearLayout.LayoutParams(0, D.dp(activity, height.toFloat()), 1f)
        lp.setMargins(D.dp(activity, 4f), 0, D.dp(activity, 4f), 0)
        return lp
    }

    private fun mediaPicked(i: MediaItem) =
        Picked(i.uri, i.displayName, i.mime, i.size, i.dateMs, false, i.path)

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
        val check = CheckBox(ctx)
        check.isChecked = selected.containsKey(item.uri)
        check.isClickable = false
        check.isFocusable = false
        val clp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clp.gravity = Gravity.TOP or Gravity.END
        clp.setMargins(0, D.dp(ctx, 2f), D.dp(ctx, 6f), 0)
        frame.addView(check, clp)
        checks[item.uri] = check
        val ring = View(ctx)
        val rd = GradientDrawable()
        rd.setShape(GradientDrawable.RECTANGLE)
        rd.setCornerRadius(D.dp(ctx, 14f).toFloat())
        rd.setStroke(D.dp(ctx, 2.5f), ThemeColors.accentStart(ctx))
        rd.setColor(ThemeColors.withAlpha(ThemeColors.accentStart(ctx), 0.18f))
        ring.background = rd
        ring.visibility = if (selected.containsKey(item.uri)) View.VISIBLE else View.GONE
        frame.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rings[item.uri] = ring
        val picked = mediaPicked(item)
        frame.isClickable = true
        frame.onClick { toggle(picked) }
        frame.setOnLongClickListener(object : View.OnLongClickListener {
            override fun onLongClick(v: View?): Boolean {
                open(item)
                return true
            }
        })
        return frame
    }

    private fun mediaRow(item: MediaItem): View {
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
        val check = CheckBox(ctx)
        check.isChecked = selected.containsKey(item.uri)
        check.isClickable = false
        check.isFocusable = false
        checks[item.uri] = check
        row.addView(check)
        val picked = mediaPicked(item)
        row.isClickable = true
        row.onClick { toggle(picked) }
        row.setOnLongClickListener(object : View.OnLongClickListener {
            override fun onLongClick(v: View?): Boolean {
                open(item)
                return true
            }
        })
        return row
    }

    private fun iconFor(item: MediaItem): Int {
        val n = item.displayName.lowercase()
        return when {
            item.mime.startsWith("audio/") -> R.drawable.ic_music
            item.mime.startsWith("image/") -> R.drawable.ic_image
            item.mime.startsWith("video/") -> R.drawable.ic_video
            item.mime == "application/vnd.android.package-archive" || n.endsWith(".apk") -> R.drawable.ic_apk
            isArchive(n) -> R.drawable.ic_archive
            isEbook(n) -> R.drawable.ic_book
            else -> R.drawable.ic_file
        }
    }

    private fun iconForName(name: String): Int {
        val n = name.lowercase()
        return when {
            n.endsWith(".apk") -> R.drawable.ic_apk
            isArchive(n) -> R.drawable.ic_archive
            isEbook(n) -> R.drawable.ic_book
            n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") || n.endsWith(".ogg") -> R.drawable.ic_music
            n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".webm") -> R.drawable.ic_video
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") ||
                n.endsWith(".gif") || n.endsWith(".heic") -> R.drawable.ic_image
            else -> R.drawable.ic_file
        }
    }

    private fun isArchive(n: String) = n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") ||
        n.endsWith(".tar") || n.endsWith(".gz") || n.endsWith(".bz2") || n.endsWith(".xz")

    private fun isEbook(n: String) = n.endsWith(".epub") || n.endsWith(".mobi") || n.endsWith(".azw") ||
        n.endsWith(".azw3") || n.endsWith(".fb2") || n.endsWith(".cbz") || n.endsWith(".cbr") || n.endsWith(".djvu")

    private fun isImageName(n: String): Boolean {
        val l = n.lowercase()
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp") ||
            l.endsWith(".gif") || l.endsWith(".heic") || l.endsWith(".bmp")
    }

    private fun mimeForName(name: String): String = when {
        isImageName(name) -> "image/*"
        name.endsWith(".mp4") || name.endsWith(".mkv") -> "video/*"
        name.endsWith(".mp3") || name.endsWith(".m4a") -> "audio/*"
        name.endsWith(".pdf") -> "application/pdf"
        name.endsWith(".apk") -> "application/vnd.android.package-archive"
        isArchive(name.lowercase()) -> "application/zip"
        else -> "*/*"
    }

    private fun emptyState(): View {
        val ctx = activity
        val partial = Compat.hasPartialMediaAccess(ctx)
        val readable = Compat.canReadMedia(ctx) && !partial
        val message = when {
            group == MediaLibrary.FileGroup.DOCUMENTS -> ctx.getString(R.string.empty_files)
            kind == Kind.VIDEOS -> ctx.getString(R.string.empty_videos)
            kind == Kind.MUSIC -> ctx.getString(R.string.empty_music)
            kind == Kind.APPS -> ctx.getString(R.string.empty_apps)
            else -> ctx.getString(R.string.empty_photos)
        }
        val col = W.emptyState(ctx, R.drawable.ic_nav_files, message,
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
        return card
    }

    private fun open(item: MediaItem) {
        if (selected.isNotEmpty()) {
            toggle(mediaPicked(item))
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

    // ---------------------------------------------------------------- selection

    /**
     * One tap: flip the entry and repaint the indicator. Nothing is re-queried and no thumbnail is
     * decoded again - that is what used to freeze the screen (and, with a large library, take the
     * process down with it).
     */
    private fun toggle(p: Picked) {
        if (selected.containsKey(p.uri)) selected.remove(p.uri) else selected[p.uri] = p
        refreshIndicators()
        refreshBar()
    }

    private fun refreshIndicators() {
        for ((uri, check) in checks) {
            check.isChecked = selected.containsKey(uri)
        }
        for ((uri, ring) in rings) {
            ring.visibility = if (selected.containsKey(uri)) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------- selection bar

    private fun refreshBar() {
        val host = barHost ?: return
        bar?.let { host.removeView(it) }
        bar = null
        if (selected.isEmpty()) return
        val ctx = activity
        val view = W.row(ctx, 10, 14)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setColor(ThemeColors.card2(ctx))
        d.setStroke(D.dp(ctx, 1f), ThemeColors.line2(ctx))
        view.background = d
        val total = selected.values.sumOf { it.size }
        view.addView(W.label(ctx, ctx.getString(R.string.n_selected, selected.size), 13f, ThemeColors.text(ctx), bold = true))
        view.addView(W.hgap(ctx, 8))
        view.addView(W.label(ctx, Fmt.size(total), 11.5f, ThemeColors.text2(ctx), mono = true))
        view.addView(W.spacer(ctx))
        val share = W.iconButton(ctx, R.drawable.ic_share, 38, 0, false)
        share.onClick { shareSelected() }
        val delete = W.iconButton(ctx, R.drawable.ic_trash, 38, 0, false)
        delete.onClick { confirmDelete() }
        val close = W.iconButton(ctx, R.drawable.ic_close, 38, 0, false)
        close.onClick { selected.clear(); refreshIndicators(); refreshBar() }
        view.addView(share)
        view.addView(W.hgap(ctx, 4))
        view.addView(delete)
        view.addView(W.hgap(ctx, 4))
        view.addView(close)
        view.addView(W.hgap(ctx, 8))
        val send = W.successButton(ctx, ctx.getString(R.string.send))
        send.onClick { startSendFlow() }
        view.addView(send)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.BOTTOM
        lp.setMargins(D.dp(ctx, 10f), 0, D.dp(ctx, 10f), D.dp(ctx, 10f))
        host.addView(view, lp)
        bar = view
    }

    /**
     * Sends what is selected. A selected folder is expanded into the files inside it (bounded, and
     * the bound is reported) because the transfer protocol moves files, not directory trees.
     */
    private fun startSendFlow() {
        val uris = ArrayList<String>()
        var skipped = 0
        for (p in selected.values) {
            if (p.isDir) {
                val path = p.path
                val f = if (path != null) File(path) else null
                val files = if (f != null) walkFiles(f, FOLDER_SEND_LIMIT - uris.size) else emptyList()
                if (files.isEmpty()) skipped++ else for (x in files) uris.add(Uri.fromFile(x).toString())
            } else {
                uris.add(p.uri)
            }
        }
        if (uris.isEmpty()) {
            Ui.toast(activity, activity.getString(R.string.empty_folder))
            return
        }
        if (skipped > 0) Ui.toast(activity, "$skipped empty folder(s) skipped")
        if (uris.size >= FOLDER_SEND_LIMIT) Ui.toast(activity, "Sending the first $FOLDER_SEND_LIMIT files")
        val host = activity as? MainActivity ?: return
        selected.clear()
        refreshIndicators()
        refreshBar()
        host.openTransferWith(uris.map { Uri.parse(it) })
    }

    private fun walkFiles(root: File, limit: Int): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < limit) {
            val cur = stack.removeFirst()
            val kids = try { cur.listFiles() } catch (t: Throwable) { null } ?: continue
            for (k in kids) {
                if (out.size >= limit) break
                if (k.isDirectory) stack.addLast(k)
                else if (!k.name.startsWith(".")) out.add(k)
            }
        }
        return out
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
        val hasDir = selected.values.any { it.isDir }
        Ui.confirm(activity, activity.getString(R.string.delete_files_title),
            activity.getString(R.string.delete_files_body, count) + if (hasDir) " Folders are deleted with their contents." else "",
            positive = activity.getString(R.string.delete),
            onPositive = {
                var deleted = 0
                for (item in selected.values) {
                    try {
                        if (item.isDir) {
                            val f = item.path?.let { File(it) }
                            if (f != null && deleteTree(f)) deleted++
                        } else if (item.uri.startsWith("content://")) {
                            if (activity.contentResolver.delete(Uri.parse(item.uri), null, null) > 0) deleted++
                        } else {
                            if (File(Uri.parse(item.uri).path ?: "").delete()) deleted++
                        }
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

    private fun deleteTree(f: File): Boolean {
        var ok = true
        val kids = try { f.listFiles() } catch (t: Throwable) { null }
        if (kids != null) for (k in kids) ok = if (k.isDirectory) deleteTree(k) && ok else k.delete() && ok
        return f.delete() && ok
    }

    private companion object {
        /** A folder can stand in for a lot of files; this is the ceiling for one batch. */
        const val FOLDER_SEND_LIMIT = 500
    }
}
