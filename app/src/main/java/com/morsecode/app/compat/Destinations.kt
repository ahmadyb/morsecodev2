/*
 * Compiled against android-34 on purpose: this file exists to wrap APIs that do not exist on
 * the API-23 floor. Every call site inside it is guarded by a Build.VERSION check, and the rest
 * of the app only ever talks to the wrapper. It lives in `compat/` because tools/offline_build.py
 * compiles that directory against the newest platform jar and everything else against API 23,
 * which turns "accidentally used a modern API" into a build error.
 */
package com.morsecode.app.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.Integrity
import java.io.File

/**
 * Where received files land, and how name collisions are handled.
 *
 *  API <= 28 : public Download/MorseCode via the classic File API
 *  API 29+   : the same public folder when all-files access is granted, otherwise the app's
 *              own external files/MorseCode folder; media files are also indexed so they show
 *              up in the gallery immediately.
 */
object Destinations {

    class Resolved(
        val finalFile: File,
        val partFile: File,
        val alreadyPresent: Boolean,
        val existingLength: Long
    )

    fun downloadRoot(ctx: Context): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "MorseCode")
    }

    fun receivedDir(ctx: Context, safDefault: String? = null): File {
        val saf = safDefault
        if (saf != null && !Compat.isApi29) {
            val f = File(saf)
            if (f.exists() || f.mkdirs()) return f
        }
        val dir = if (Compat.hasAllFilesAccess(ctx) || !Compat.isApi29) {
            downloadRoot(ctx)
        } else {
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "MorseCode")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun humanReadableDestination(ctx: Context, safDefault: String? = null): String {
        val saf = safDefault
        if (saf != null && !Compat.isApi29) return saf
        return if (Compat.hasAllFilesAccess(ctx) || !Compat.isApi29) "Download/MorseCode"
        else "Android/data/com.morsecode.app/files/MorseCode"
    }

    /**
     * Applies the conflict policy. `rename` is the default: "report.pdf" becomes
     * "report (1).pdf", then "report (2).pdf" ...
     */
    fun resolve(
        ctx: Context,
        dir: File,
        name: String,
        size: Long,
        sha: String?,
        policy: String = "rename"
    ): Resolved {
        if (!dir.exists()) dir.mkdirs()
        val safeName = sanitize(name)

        // de-duplication: an identical file (same size + same sha) already in the destination
        if (sha != null && sha.isNotBlank()) {
            val candidates = dir.listFiles { f -> f.isFile && f.length() == size } ?: emptyArray()
            for (c in candidates) {
                val h = Integrity.sha256File(c)
                if (h != null && h.equals(sha, ignoreCase = true)) {
                    return Resolved(c, partFileFor(dir, safeName), true, c.length())
                }
            }
        }

        val candidate = File(dir, safeName)
        val existing = candidate.exists()
        val finalFile: File = when {
            !existing -> candidate
            policy == "overwrite" -> candidate
            policy == "skip" -> candidate
            else -> uniqueName(dir, safeName)
        }
        if (existing && policy == "skip") {
            return Resolved(finalFile, partFileFor(dir, safeName), true, finalFile.length())
        }
        return Resolved(finalFile, partFileFor(dir, safeName), false, partFileFor(dir, safeName).length())
    }

    fun partFileFor(dir: File, name: String) = File(dir, ".$name.part")

    private fun uniqueName(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 500) {
            val f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
        return File(dir, "$base-${System.currentTimeMillis()}$ext")
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "file" else cleaned
    }

    /** Atomic promotion of the `.part` file, plus a MediaStore index so media shows up. */
    fun promote(part: File, finalFile: File): Boolean {
        return try {
            if (finalFile.exists()) finalFile.delete()
            val ok = part.renameTo(finalFile)
            if (!ok) {
                part.copyTo(finalFile, overwrite = true)
                part.delete()
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun indexInMediaStore(ctx: Context, file: File, mime: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.SIZE, file.length())
                put(MediaStore.MediaColumns.DATA, file.absolutePath)
            }
            val collection = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> return
            }
            ctx.contentResolver.insert(collection, values)
        } catch (ignored: Throwable) {
        }
    }

    fun uriOf(file: File): Uri = Uri.fromFile(file)
}
