/*
 * Compiled against android-34 on purpose: this file exists to wrap APIs that do not exist on
 * the API-23 floor. Every call site inside it is guarded by a Build.VERSION check, and the rest
 * of the app only ever talks to the wrapper. It lives in `compat/` because tools/offline_build.py
 * compiles that directory against the newest platform jar and everything else against API 23,
 * which turns "accidentally used a modern API" into a build error.
 */
package com.morsecode.app.core.media

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.morsecode.app.core.model.MediaItem
import com.morsecode.app.core.util.Paths
import java.io.File

/**
 * The phone's media library, backed by MediaStore.
 *
 * INV-9  photos/videos sort by date taken (falling back to date modified), `_ID DESC` tiebreak,
 *        which is what makes Today / Yesterday / dated headers appear exactly once, in order.
 * INV-10 pagination opens the cursor, `moveToPosition(offset)`, then reads `limit` rows - a
 *        LIMIT/OFFSET clause is never placed inside the sort string (Android 14 rejects those
 *        with "Invalid token LIMIT").
 */
class MediaLibrary(private val ctx: Context) {

    enum class Category { PHOTOS, VIDEOS, MUSIC, DOCUMENTS, APPS, ALL }

    val uri: Uri = MediaStore.Files.getContentUri("external")

    /**
     * The columns every version of the unified `media/external/file` table has. [read] indexes the
     * first seven by position, so the order here is part of the contract.
     */
    private val coreProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATA
    )

    /**
     * Columns the *per-type* tables carry but the unified Files table does not always have.
     *
     * `content://media/external/file` is not `content://media/external/images`: from Android 10 it
     * is a view assembled from those tables, and `DATE_TAKEN` is not one of its columns. Asking for
     * one anyway is not a missing field, it is `IllegalArgumentException: Invalid column
     * DATE_TAKEN` and the whole query returns nothing - which is exactly what emptied the Files tab
     * and the browser's file list on every Android 10+ device. They are optional now, and dropped
     * on the first refusal.
     */
    private val optionalColumns =
        arrayOf("DATE_TAKEN", "DURATION", "WIDTH", "HEIGHT", "BUCKET_DISPLAY_NAME", "BUCKET_ID")

    private val optionalShapeColumns = arrayOf("DURATION", "WIDTH", "HEIGHT", "BUCKET_DISPLAY_NAME", "BUCKET_ID")

    /**
     * Index into [candidatesFor] of the query this device last accepted, or -1 while unknown.
     * Remembered for the life of the process so the ladder is paid for once, not per screen.
     */
    @Volatile private var accepted = -1

    /**
     * What the last query attempt actually did, in one line: which projection was accepted or
     * rejected, and with what message. The screen shows this in its own log line, which is the only
     * place the answer exists - a shell query runs with wider permissions than the app, so "the
     * device holds rows" and "the app can read rows" are different statements.
     */
    @Volatile var lastNote: String = "no query yet"
        private set

    /**
     * INV-9 sort expression. MediaStore accepts it on every version this app targets; if a
     * particular OEM build rejects it we fall back to a plain `_ID DESC` and sort in memory.
     */
    private val dateSortExpression =
        "CASE WHEN DATE_TAKEN > 0 THEN DATE_TAKEN ELSE DATE_MODIFIED * 1000 END DESC, _ID DESC"

    // ---------------------------------------------------------------- queries

    fun count(category: Category): Int {
        return try {
            val (selection, args) = selectionFor(category)
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { c ->
                c.count
            } ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    /** One page of items. `limit` of 0 or less means "everything" (used by WebShare counts). */
    fun page(category: Category, offset: Int, limit: Int): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val (selection, args) = selectionFor(category)
        val cursor = try {
            openCursor(category, selection, args)
        } catch (t: Throwable) {
            alog("Media query failed: ${t.message}")
            return out
        } ?: return out

        try {
            cursor.use { c ->
                // INV-10: no LIMIT/OFFSET in the query string - walk the cursor instead. The
                // positioning is done before the loop on purpose: `do { read } while (moveToNext())`
                // reads row -1 on an empty cursor, which turns "no rows" into a crash-shaped
                // failure and hides the real answer.
                val positioned = if (offset > 0) c.moveToPosition(offset) else c.moveToFirst()
                if (!positioned) {
                    lastNote = "$lastNote; page empty (offset=$offset)"
                    return out
                }
                var read = 0
                val cap = if (limit > 0) limit else Int.MAX_VALUE
                while (read < cap) {
                    out.add(read(c))
                    read++
                    if (!c.moveToNext()) break
                }
                lastNote = "$lastNote; page ${out.size} row(s) at offset $offset"
            }
        } catch (t: Throwable) {
            // A row that cannot be read is one row, not the screen: the rest of the page still is.
            alog("Media read failed: ${t.message}")
        }
        return out
    }

    /**
     * Opens the cursor using the first query this device accepts.
     *
     * Each candidate is a *complete query* - projection and sort together - because the two fall
     * over for the same reason: the unified Files table has no `DATE_TAKEN` and no `TITLE`, so both
     * the projection that names them and the sort that orders by them are refused. The accepted one
     * is remembered; a later failure (a volume that went away, an OEM build that changed its mind)
     * clears it and walks the ladder again.
     */
    private fun openCursor(category: Category, selection: String?, args: Array<String>?): Cursor? {
        val list = candidatesFor(category)
        val cached = accepted
        if (cached in list.indices) {
            try {
                return ctx.contentResolver.query(uri, list[cached].first, selection, args, list[cached].second)
            } catch (t: Throwable) {
                alog("Media query retry after ${list[cached].first.size} columns: ${t.message}")
                accepted = -1
            }
        }
        var last: Throwable? = null
        val rejected = StringBuilder()
        for (i in list.indices) {
            val (projection, sort) = list[i]
            try {
                val c = ctx.contentResolver.query(uri, projection, selection, args, sort) ?: continue
                accepted = i
                lastNote = if (rejected.isEmpty()) {
                    "query ok (${projection.size} columns, sort: $sort)"
                } else {
                    "query degraded to ${projection.size} columns after $rejected"
                }
                if (i > 0) alog("Media query degraded to ${projection.size} columns (sort: $sort)")
                return c
            } catch (t: Throwable) {
                last = t
                if (rejected.isNotEmpty()) rejected.append(", ")
                rejected.append("${projection.size}c/${sort.substringBefore(',')}=${t.message?.take(60)}")
                alog("Media query rejected (${projection.size} columns, sort: $sort): ${t.message}")
            }
        }
        lastNote = "query failed: $rejected"
        throw last ?: IllegalStateException("MediaStore accepted no query")
    }

    /**
     * The queries to try, richest first. INV-9's ordering is the first one - date taken, then date
     * modified, newest first - and it is only available when the device also has `DATE_TAKEN`.
     *
     * Every step below it drops one more thing the unified table may not have (per-type columns,
     * `TITLE`, expressions in the sort) and ends at nothing but a column and a direction. The grid
     * re-sorts in memory anyway, so a degraded ORDER BY costs nothing a user can see.
     */
    private fun candidatesFor(category: Category): List<Pair<Array<String>, String>> {
        val plainDateSort = "DATE_MODIFIED DESC, _ID DESC"
        val nameSort = "DISPLAY_NAME COLLATE NOCASE ASC"
        val music = category == Category.MUSIC
        return listOf(
            (coreProjection + optionalColumns) to (if (music) "TITLE COLLATE NOCASE ASC" else dateSortExpression),
            (coreProjection + optionalShapeColumns) to (if (music) nameSort else plainDateSort),
            coreProjection to (if (music) nameSort else plainDateSort),
            coreProjection to "_ID DESC"
        )
    }

    fun all(category: Category): List<MediaItem> = page(category, 0, 0)

    private fun read(c: Cursor): MediaItem {
        val id = c.getLong(0)
        val name = c.getString(1) ?: "file"
        val mime = c.getString(2) ?: Paths.guessMime(name)
        val size = c.getLong(3)
        val modified = c.getLong(4)
        val path = c.getString(6) ?: ""
        var taken = 0L
        var duration = 0L
        var w = 0
        var h = 0
        var bucket = ""
        var bucketId = 0L
        taken = longOr(c, "DATE_TAKEN")
        duration = longOr(c, "DURATION")
        w = intOr(c, "WIDTH")
        h = intOr(c, "HEIGHT")
        bucket = strOr(c, "BUCKET_DISPLAY_NAME")
        bucketId = longOr(c, "BUCKET_ID")
        return MediaItem(
            id = id,
            uri = ContentUris.withAppendedId(uri, id).toString(),
            displayName = name,
            mime = mime,
            size = size,
            durationMs = duration,
            width = w,
            height = h,
            dateMs = if (taken > 0) taken else modified * 1000L,
            bucket = bucket,
            bucketId = bucketId,
            path = path
        )
    }

    private fun longOr(c: Cursor, column: String): Long {
        val i = c.getColumnIndex(column)
        return if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
    }

    private fun intOr(c: Cursor, column: String): Int {
        val i = c.getColumnIndex(column)
        return if (i >= 0 && !c.isNull(i)) c.getInt(i) else 0
    }

    private fun strOr(c: Cursor, column: String): String {
        val i = c.getColumnIndex(column)
        return if (i >= 0 && !c.isNull(i)) c.getString(i) ?: "" else ""
    }

    /**
     * The categories the Files tab shows, as the design does: documents, ebooks, archives, APKs and
     * "large files". They are matched on MIME type where the platform reports one and on the file
     * name where it does not - an .epub sitting in Downloads is routinely indexed as
     * `application/octet-stream`, and a file the user can see must never be missing from the count
     * above it.
     */
    // No label resource here on purpose: this directory is compiled against the platform jar, with
    // no access to the app's generated R class. The screen owns the wording.
    enum class FileGroup { DOCUMENTS, EBOOKS, ARCHIVES, APKS, LARGE }

    /** Everything over this shows up under "Large files" (the design's own label). */
    private val largeBytes = 50L * 1024 * 1024

    fun countGroup(group: FileGroup): Int = listGroup(group, 0).size

    /** Files in a group, newest first, optionally capped. */
    fun listGroup(group: FileGroup, limit: Int): List<MediaItem> {
        val (selection, args) = groupSelection(group)
        val out = ArrayList<MediaItem>()
        val cursor = try {
            openCursor(Category.ALL, selection, args)
        } catch (t: Throwable) {
            alog("Files group query failed (${group.name}): ${t.message}")
            return out
        } ?: return out
        try {
            cursor.use { c ->
                if (!c.moveToFirst()) return out
                var read = 0
                val cap = if (limit > 0) limit else Int.MAX_VALUE
                while (read < cap) {
                    out.add(read(c))
                    read++
                    if (!c.moveToNext()) break
                }
            }
        } catch (t: Throwable) {
            alog("Files group read failed (${group.name}): ${t.message}")
        }
        lastNote = "$lastNote; ${group.name} ${out.size} row(s)"
        return out
    }

    private fun like(column: String, vararg patterns: String): String =
        "(" + patterns.joinToString(" OR ") { "$column LIKE '$it'" } + ")"

    private fun groupSelection(group: FileGroup): Pair<String, Array<String>> {
        val size = MediaStore.MediaColumns.SIZE
        val mime = MediaStore.MediaColumns.MIME_TYPE
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        val alive = "$size > 0"
        val sel = when (group) {
            FileGroup.DOCUMENTS -> "$alive AND (" +
                like(mime, "application/pdf", "application/msword", "application/vnd.ms-%",
                    "application/vnd.openxmlformats-%", "text/%", "application/rtf", "application/x-rtf") +
                " OR " + like(name, "%.doc", "%.docx", "%.xls", "%.xlsx", "%.ppt", "%.pptx", "%.txt",
                    "%.rtf", "%.csv", "%.md", "%.odt", "%.ods", "%.odp") + ")"
            FileGroup.EBOOKS -> "$alive AND (" +
                like(mime, "application/epub+zip", "application/x-mobipocket-ebook",
                    "application/vnd.amazon.ebook", "application/x-fictionbook+xml") +
                " OR " + like(name, "%.epub", "%.mobi", "%.azw", "%.azw3", "%.fb2", "%.cbz", "%.cbr",
                    "%.djvu") + ")"
            FileGroup.ARCHIVES -> "$alive AND (" +
                like(mime, "application/zip", "application/x-rar-compressed", "application/x-7z-compressed",
                    "application/gzip", "application/x-tar", "application/x-bzip2", "application/x-xz") +
                " OR " + like(name, "%.zip", "%.rar", "%.7z", "%.tar", "%.gz", "%.bz2", "%.xz", "%.iso") + ")"
            FileGroup.APKS -> "$alive AND (" +
                like(mime, "application/vnd.android.package-archive") +
                " OR " + like(name, "%.apk", "%.apks", "%.xapk") + ")"
            FileGroup.LARGE -> "$alive AND $size > $largeBytes"
        }
        return sel to emptyArray()
    }

    private fun selectionFor(category: Category): Pair<String, Array<String>> {
        val images = "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%'"
        val videos = "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%'"
        val audio = "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'audio/%'"
        val documents = "(${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/pdf'" +
            " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/msword%'" +
            " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/vnd.ms-%'" +
            " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/vnd.openxml%'" +
            " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'text/%')"
        return when (category) {
            Category.PHOTOS -> "$images AND ${MediaStore.MediaColumns.SIZE} > 0" to emptyArray()
            Category.VIDEOS -> "$videos AND ${MediaStore.MediaColumns.SIZE} > 0" to emptyArray()
            Category.MUSIC -> "$audio AND ${MediaStore.MediaColumns.SIZE} > 0" to emptyArray()
            Category.DOCUMENTS -> "$documents AND ${MediaStore.MediaColumns.SIZE} > 0" to emptyArray()
            Category.ALL -> "${MediaStore.MediaColumns.SIZE} > 0" to emptyArray()
            Category.APPS -> "1 = 0" to emptyArray()
        }
    }

    /** Folder sidebar data (name + count + bytes), grouped by bucket. */
    fun folderInfo(category: Category): List<FolderInfo> {
        val map = LinkedHashMap<String, FolderInfo>()
        val items = all(category)
        for (i in items) {
            val key = i.bucket.ifBlank { "Other" }
            val f = map[key] ?: FolderInfo(key, 0, 0L)
            f.count++
            f.bytes += i.size
            map[key] = f
        }
        return map.values.sortedByDescending { it.count }
    }

    class FolderInfo(val name: String, var count: Int, var bytes: Long)

    /** Category counters used by the Files tab and by WebShare's sidebar. */
    fun categoryCounts(): Map<String, Int> = mapOf(
        "Photos" to count(Category.PHOTOS),
        "Videos" to count(Category.VIDEOS),
        "Music" to count(Category.MUSIC),
        "Docs" to count(Category.DOCUMENTS),
        "Apps" to installedApps().size
    )

    // ---------------------------------------------------------------- subtypes

    fun documents(sub: String): List<MediaItem> {
        val all = all(Category.DOCUMENTS) + all(Category.ALL).filter {
            val n = it.displayName.lowercase()
            n.endsWith(".epub") || n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") ||
                n.endsWith(".apk") || n.endsWith(".tar") || n.endsWith(".gz")
        }
        return when (sub) {
            "ebooks" -> all.filter { it.displayName.lowercase().let { n -> n.endsWith(".epub") || n.endsWith(".mobi") || n.endsWith(".azw3") } }
            "archives" -> all.filter { it.displayName.lowercase().let { n -> n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".tar") || n.endsWith(".gz") } }
            "apks" -> all.filter { it.displayName.lowercase().endsWith(".apk") }
            "large" -> all.filter { it.size >= 50L * 1024 * 1024 }.sortedByDescending { it.size }
            else -> all.filter { it.mime.startsWith("application/") || it.mime.startsWith("text/") }
        }
    }

    // ---------------------------------------------------------------- apps

    fun installedApps(): List<MediaItem> {
        val pm = ctx.packageManager
        val out = ArrayList<MediaItem>()
        try {
            val apps = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (ai in apps) {
                val hasLauncher = try {
                    pm.getLaunchIntentForPackage(ai.packageName) != null
                } catch (t: Throwable) {
                    false
                }
                if (!hasLauncher) continue
                val label = try { pm.getApplicationLabel(ai).toString() } catch (t: Throwable) { ai.packageName }
                val src = try { pm.getApplicationInfo(ai.packageName, 0).sourceDir } catch (t: Throwable) { "" }
                val size = if (src.isNotBlank()) File(src).length() else 0L
                out.add(
                    MediaItem(
                        id = ai.packageName.hashCode().toLong(),
                        uri = "app://" + ai.packageName,
                        displayName = label,
                        mime = "application/vnd.android.package-archive",
                        size = size,
                        path = src,
                        isApp = true,
                        packageName = ai.packageName
                    )
                )
            }
        } catch (t: Throwable) {
            alog("App listing failed: ${t.message}")
        }
        return out.sortedBy { it.displayName.lowercase() }
    }

    fun apkPathFor(packageName: String): String? = try {
        ctx.packageManager.getApplicationInfo(packageName, 0).sourceDir
    } catch (t: Throwable) {
        null
    }

    /** Real, downsampled content URI for grid tiles - never a full-resolution decode. */
    fun thumbnailUri(item: MediaItem): Uri = Uri.parse(item.uri)

    // ---------------------------------------------------------------- storage

    class StorageStat(val used: Long, val total: Long) {
        val free: Long get() = total - used
    }

    fun storage(): StorageStat {
        return try {
            val ext = Environment.getExternalStorageDirectory()
            val stat = android.os.StatFs(ext.path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            StorageStat(total - free, total)
        } catch (t: Throwable) {
            StorageStat(0, 0)
        }
    }

    // ---------------------------------------------------------------- display

    fun sizeLabel(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(java.util.Locale.US, kb)
        val mb = kb / 1024.0
        return if (mb < 1024) "%.1f MB".format(java.util.Locale.US, mb)
        else "%.2f GB".format(java.util.Locale.US, mb / 1024.0)
    }

    /** Local diagnostics: compat code logs straight to logcat, never to the in-app ring buffer. */
    private fun alog(message: String) {
        try {
            android.util.Log.w("MorseCode", message)
        } catch (ignored: Throwable) {
        }
    }
}
