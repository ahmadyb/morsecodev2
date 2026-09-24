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
    fun load(view: ImageView, item: MediaItem, sizePx: Int) {
        val ctx = view.context
        val kind = if (item.mime.startsWith("video/")) "video" else "image"
        val key = "${item.id}|${kind}|${if (sizePx > 0) "grid" else "row"}"
        val hit = cache.get(key)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val bmp = decode(ctx, item, kind)
            if (bmp != null) cache.put(key, bmp)
            if (bmp == null) return@execute
            view.post(object : Runnable {
                override fun run() {
                    view.setImageBitmap(bmp)
                }
            })
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
                @Suppress("DEPRECATION")
                val bmp = MediaStore.Video.Thumbnails.getThumbnail(
                    ctx.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                )
                if (bmp != null) return bmp
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
