/*
 * Compiled against android-34 on purpose - see compat/Compat.kt. Every modern call is guarded.
 */
package com.morsecode.app.core.util

import android.content.Context
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import com.morsecode.app.core.model.MediaItem
import java.util.concurrent.Executors

/**
 * Thumbnail loader for the Files grid and the WebShare tiles.
 *
 * Everything is downsampled to the device tier's tile size and kept in a small LRU cache, so a
 * low-RAM phone shows a full grid of real photos without ever decoding a full-resolution bitmap.
 */
object Thumbs {

    private val cache = LruCache<String, Bitmap>(if (DeviceTier.isOldDevice) 60 else 160)
    private val pool = Executors.newFixedThreadPool(if (DeviceTier.isOldDevice) 2 else 4) { r ->
        Thread(r, "mc-thumb").apply { isDaemon = true }
    }

    fun cached(key: String): Bitmap? = cache.get(key)

    /**
     * Convenience overload for grids: decodes (or reuses) a thumbnail sized for the file type and
     * drops it straight into an ImageView. `sizePx` is a hint for the requested cell size.
     */
    /**
     * Thumbnails that failed to decode, and when to give them another chance.
     *
     * MediaStore does not build a video thumbnail until something asks for it, and on a fresh
     * install the first few asks come back empty while the platform is still indexing or while its
     * thumbnail service is warming up - which is why a first launch could show a grid of blank
     * video tiles that fixed itself after a few launches. A failure is remembered briefly and
     * retried, instead of being treated as "this file has no thumbnail" forever.
     */
    private val failed = HashMap<String, Long>()
    private val retryAfterMs = 4_000L

    fun load(view: ImageView, item: MediaItem, sizePx: Int) {
        val ctx = view.context
        val kind = if (item.mime.startsWith("video/")) "video" else "image"
        val key = "${item.id}|${kind}|${if (sizePx > 0) "grid" else "row"}"
        val hit = cache.get(key)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        val failedAt = synchronized(failed) { failed[key] }
        val retry = failedAt == null || System.currentTimeMillis() - failedAt > retryAfterMs
        // A tile mid-load keeps whatever it has: clearing it here is what made a re-render blink.
        if (retry) {
            view.setImageDrawable(null)
            pool.execute {
                val bmp = decode(ctx, item, kind)
                if (bmp != null) {
                    cache.put(key, bmp)
                    synchronized(failed) { failed.remove(key) }
                    view.post(object : Runnable {
                        override fun run() {
                            view.setImageBitmap(bmp)
                        }
                    })
                } else {
                    synchronized(failed) { failed[key] = System.currentTimeMillis() }
                }
            }
        }
    }

    /**
     * A thumbnail for a file the MediaStore never indexed - anything found by walking a folder.
     * Same cache, same worker pool, same "never decode full size" rule.
     */
    fun loadFile(view: ImageView, file: java.io.File) {
        val key = "path|${file.absolutePath}|${file.lastModified()}"
        val hit = cache.get(key)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        val failedAt = synchronized(failed) { failed[key] }
        if (failedAt != null && System.currentTimeMillis() - failedAt <= retryAfterMs) return
        view.setImageDrawable(null)
        pool.execute {
            val bmp = decodePath(file)
            if (bmp != null) {
                cache.put(key, bmp)
                synchronized(failed) { failed.remove(key) }
                view.post(object : Runnable {
                    override fun run() {
                        view.setImageBitmap(bmp)
                    }
                })
            } else {
                synchronized(failed) { failed[key] = System.currentTimeMillis() }
            }
        }
    }

    private fun decodePath(file: java.io.File): Bitmap? {
        val target = DeviceTier.thumbnailPx
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > target * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (t: Throwable) {
            null
        }
    }

    fun load(ctx: Context, item: MediaItem, kind: String, onReady: (Bitmap?) -> Unit) {
        val key = "${item.id}|$kind"
        val hit = cache.get(key)
        if (hit != null) {
            onReady(hit)
            return
        }
        pool.execute {
            val bmp = decode(ctx, item, kind)
            if (bmp != null) cache.put(key, bmp)
            try {
                onReady(bmp)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun decode(ctx: Context, item: MediaItem, kind: String): Bitmap? {
        val target = DeviceTier.thumbnailPx
        try {
            if (build29()) {
                val uri = Uri.parse(item.uri)
                val size = target.coerceAtLeast(48)
                val loaded = try {
                    ctx.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
                } catch (t: Throwable) {
                    null
                }
                if (loaded != null) return loaded
            }
            if (kind == "video") {
                // The platform thumbnail service answers with null for a moment after boot and for
                // a video it has not indexed yet; two quick attempts (and the caller's retry) turn
                // "no thumbnail on the first launches" into "no thumbnail for a second or two".
                repeat(2) { attempt ->
                    @Suppress("DEPRECATION")
                    val bmp = MediaStore.Video.Thumbnails.getThumbnail(
                        ctx.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                    if (bmp != null) return bmp
                    if (attempt == 0) {
                        try {
                            Thread.sleep(250)
                        } catch (ignored: InterruptedException) {
                        }
                    }
                }
            }
            if (kind == "audio") return null
            // generic path: sample the file down
            val uri = Uri.parse(item.uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > target * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (t: Throwable) {
            return null
        }
    }

    private fun build29(): Boolean = Build.VERSION.SDK_INT >= 29

    fun clear() = cache.evictAll()
}
