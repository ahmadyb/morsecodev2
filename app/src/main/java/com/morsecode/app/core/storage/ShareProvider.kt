package com.morsecode.app.core.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.morsecode.app.core.util.Paths
import java.io.File
import java.io.FileNotFoundException

/**
 * A framework-only ContentProvider (no AndroidX FileProvider) that hands received files to other
 * apps through `content://<applicationId>.share/files/<relative path>`.
 *
 * The authority is derived from the running package rather than hard-coded: the manifest declares
 * `${applicationId}.share`, so a debug build installed next to a release build owns its own
 * authority instead of being rejected with INSTALL_FAILED_CONFLICTING_PROVIDER.
 *
 * Reads are limited to the app's own download root, the received folder and the app files dir -
 * a caller can never walk out of those trees.
 */
class ShareProvider : ContentProvider() {

    companion object {
        const val SCHEME = "content"
        const val PATH = "files"

        /** `com.morsecode.app.share` for a release build, `...debug.share` for a debug build. */
        fun authority(ctx: Context): String = ctx.packageName + ".share"

        fun uriFor(ctx: Context, file: File): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(authority(ctx))
            .appendPath(PATH)
            .appendPath(file.absolutePath)
            .build()
    }

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File {
        val parts = uri.pathSegments
        if (parts.size < 2 || parts[0] != PATH) throw FileNotFoundException("Unsupported path: $uri")
        val requested = File(parts.drop(1).joinToString("/"))
        val ctx = context ?: throw FileNotFoundException("No context")
        val roots = arrayListOf(
            Destinations.downloadRoot(ctx),
            Destinations.receivedDir(ctx),
            File(ctx.filesDir, "shared")
        )
        val canonical = try {
            requested.canonicalFile
        } catch (t: Throwable) {
            throw FileNotFoundException("Unresolvable path: $requested")
        }
        for (root in roots) {
            val rc = try {
                root.canonicalFile
            } catch (t: Throwable) {
                root
            }
            if (canonical.path == rc.path || canonical.path.startsWith(rc.path + File.separator)) {
                if (!canonical.exists()) throw FileNotFoundException("Missing: $canonical")
                return canonical
            }
        }
        throw FileNotFoundException("Outside the shared trees: $canonical")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.startsWith("r")) throw FileNotFoundException("Write access is not granted")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = try {
        Paths.guessMime(resolve(uri).name)
    } catch (t: Throwable) {
        "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = try {
            resolve(uri)
        } catch (t: Throwable) {
            return null
        }
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        cursor.addRow(arrayOf<Any>(file.name, file.length()))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
