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

/** SHA-256 / CRC32 integrity primitives. */
object Integrity {

    const val PREHASH_LIMIT = 256L * 1024 * 1024

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256File(file: File): String? = try {
        file.inputStream().use { sha256(it) }
    } catch (t: Throwable) {
        null
    }

    fun crc32(data: ByteArray, len: Int = data.size): Long {
        val c = java.util.zip.CRC32()
        c.update(data, 0, len)
        return c.value
    }
}

