package com.morsecode.app.core.storage

import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Turns "a file we know about" into a URI another app is allowed to read.
 *
 * MediaStore entries already carry a `content://` URI; files we received live on disk as plain
 * paths. Handing a `file://` URI to another app throws FileUriExposedException on API 24+, so
 * those are routed through [ShareProvider] instead.
 */
object ShareUris {

    fun of(value: String): Uri {
        if (value.startsWith("content://")) return Uri.parse(value)
        val path = if (value.startsWith("file://")) Uri.parse(value).path ?: value else value
        return Uri.parse(ShareProvider.uriFor(File(path)).toString())
    }

    fun ofAll(values: List<String>): List<Uri> {
        val out = ArrayList<Uri>(values.size)
        for (v in values) {
            val uri = try {
                of(v)
            } catch (t: Throwable) {
                null
            }
            if (uri != null) out.add(uri)
        }
        return out
    }

    fun viewIntent(value: String, mime: String): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(of(value), if (mime.isBlank()) "*/*" else mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(values: List<String>, mime: String): Intent {
        val uris = ofAll(values)
        val type = if (mime.isBlank()) "*/*" else mime
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}
