package com.morsecode.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage Access Framework destinations: user added folders (Files tab and
 * Settings > System > Storage access) plus the one marked as the default download location.
 * Only *tree* URIs are persisted; the app has no business holding a permanent write grant.
 */
class SafStore(private val ctx: Context) {

    companion object {
        /** Request code for ACTION_OPEN_DOCUMENT_TREE results. */
        const val REQ_TREE = 7901
    }

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("morsecode_saf", Context.MODE_PRIVATE)

    class Folder(val uri: String, val name: String, var isDefault: Boolean)

    fun folders(): MutableList<Folder> {
        val out = mutableListOf<Folder>()
        try {
            val arr = JSONArray(sp.getString("folders", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Folder(o.optString("uri"), o.optString("name"), o.optBoolean("default")))
            }
        } catch (ignored: Throwable) {
        }
        return out
    }

    private fun persist(list: List<Folder>) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().apply {
            put("uri", f.uri); put("name", f.name); put("default", f.isDefault)
        })
        sp.edit().putString("folders", arr.toString()).apply()
    }

    fun add(uri: Uri, name: String) {
        val list = folders().filterNot { it.uri == uri.toString() }.toMutableList()
        list.add(Folder(uri.toString(), name.ifBlank { "Folder" }, list.isEmpty()))
        persist(list)
    }

    fun remove(uri: String) {
        persist(folders().filterNot { it.uri == uri })
    }

    fun setDefault(uri: String) {
        val list = folders()
        for (f in list) f.isDefault = f.uri == uri
        persist(list)
    }

    fun defaultFolder(): String? = folders().firstOrNull { it.isDefault }?.uri

    /** Records a folder granted through ACTION_OPEN_DOCUMENT_TREE. */
    fun onTreePicked(uri: Uri) {
        val resolver = ctx.contentResolver
        var name = uri.lastPathSegment ?: "Folder"
        try {
            val cursor = resolver.query(uri, null, null, null, null)
            if (cursor != null) {
                val idx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: name
                cursor.close()
            }
        } catch (t: Throwable) {
        }
        try {
            resolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (t: Throwable) {
        }
        add(uri, name)
        setDefault(uri.toString())
    }

    fun clear() = sp.edit().clear().apply()
}
