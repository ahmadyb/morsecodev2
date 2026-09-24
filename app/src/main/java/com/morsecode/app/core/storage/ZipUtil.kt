package com.morsecode.app.core.storage

import android.content.Context
import android.net.Uri
import com.morsecode.app.core.util.Paths
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams a set of content URIs into one zip entry per file. Used by the download-as-zip
 * paths (and by the native "share as zip" affordance).
 */
object ZipUtil {

    fun zipUris(ctx: Context, uris: List<Uri>, out: OutputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        return try {
            ZipOutputStream(out.buffered(64 * 1024)).use { zos ->
                var index = 0
                for (u in uris) {
                    val name = Paths.displayName(ctx, u)
                    val entry = ZipEntry(uniqueName(name, zos))
                    zos.putNextEntry(entry)
                    ctx.contentResolver.openInputStream(u)?.use { ins ->
                        ins.copyTo(zos, 64 * 1024)
                    }
                    zos.closeEntry()
                    index++
                    onProgress(index, uris.size)
                }
                zos.finish()
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun zipFiles(files: List<File>, out: OutputStream): Boolean = try {
        ZipOutputStream(out.buffered(64 * 1024)).use { zos ->
            for (f in files) {
                if (f.isDirectory) continue
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
            }
            zos.finish()
        }
        true
    } catch (t: Throwable) {
        false
    }

    private val seen = HashSet<String>()

    private fun uniqueName(name: String, zos: ZipOutputStream): String {
        // ZipOutputStream rejects duplicate entries; a per-call set keeps names unique.
        if (seen.add(name)) return name
        var i = 1
        while (true) {
            val candidate = name.substringBeforeLast('.', name) + " ($i)" +
                name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
            if (seen.add(candidate)) return candidate
            i++
        }
    }
}
