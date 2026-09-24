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

/** Device capability scaling. */
object DeviceTier {

    val lowRam: Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val am = Runtime.getRuntime()
            val max = am.maxMemory() / (1024 * 1024)
            max < 128
        } else true
    } catch (t: Throwable) {
        true
    }

    val isOldDevice: Boolean get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.M || lowRam

    /** How many Broadcast peers may stream simultaneously. */
    val maxParallelPeers: Int get() = if (isOldDevice) 2 else 4

    val thumbnailPx: Int get() = if (isOldDevice) 160 else 256

    /** Pre-hash only files small enough that the hash does not add a noticeable delay. */
    val preHashLimit: Long get() = if (isOldDevice) 64L * 1024 * 1024 else Integrity.PREHASH_LIMIT

    val maxConcurrentPickerItems: Int get() = if (isOldDevice) 200 else 500
}

