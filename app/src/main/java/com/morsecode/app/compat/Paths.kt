/*
 * Compiled against android-34 on purpose - see compat/Compat.kt. Guarded at every call site.
 */
package com.morsecode.app.core.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.security.MessageDigest

/** uid + destination paths. */
object Paths {

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "file"
        var name = uri.lastPathSegment ?: "file"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        } catch (ignored: Throwable) {
        }
        return name.substringAfterLast('/')
    }

    fun size(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        } catch (ignored: Throwable) {
        }
        return try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }

    fun mime(ctx: Context, uri: Uri): String =
        try {
            ctx.contentResolver.getType(uri) ?: guessMime(displayName(ctx, uri))
        } catch (t: Throwable) {
            guessMime(displayName(ctx, uri))
        }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "gz", "tar" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            "epub" -> "application/epub+zip"
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            else -> "*/*"
        }
    }

    fun isImage(mime: String) = mime.startsWith("image/")
    fun isVideo(mime: String) = mime.startsWith("video/")
    fun isAudio(mime: String) = mime.startsWith("audio/")
}

