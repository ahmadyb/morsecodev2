/*
 * Compiled against android-34 on purpose - see compat/Compat.kt.
 */
package com.morsecode.app.core.model

/** MediaStore row. */
class MediaItem(
    val id: Long,
    val uri: String,
    val displayName: String,
    val mime: String,
    val size: Long,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val dateMs: Long = 0L,
    val bucket: String = "",
    val bucketId: Long = 0L,
    val path: String = "",
    val isApp: Boolean = false,
    val packageName: String = ""
)

