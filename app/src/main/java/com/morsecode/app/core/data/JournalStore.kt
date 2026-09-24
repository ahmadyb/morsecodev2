package com.morsecode.app.core.data

import android.content.Context
import com.morsecode.app.core.model.Direction
import com.morsecode.app.core.model.ItemState
import com.morsecode.app.core.model.TransferItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Outgoing queues (per peer) and in-progress receiving files are journaled to disk.
 * On relaunch every item comes back PAUSED, ready to be offered for resume.
 */
class JournalStore(private val ctx: Context) {

    private val file: File get() = File(ctx.filesDir, "journal.json")
    private val lock = Any()

    fun save(items: List<TransferItem>) = synchronized(lock) {
        val arr = JSONArray()
        for (i in items) {
            if (i.state == ItemState.COMPLETED || i.state == ItemState.CANCELLED || i.state == ItemState.SKIPPED) continue
            arr.put(JSONObject().apply {
                put("id", i.id); put("batch", i.batchId); put("group", i.groupId ?: "")
                put("peerId", i.peerId); put("peerName", i.peerName)
                put("dir", i.direction.name)
                put("name", i.displayName); put("uri", i.uri); put("mime", i.mime)
                put("size", i.size); put("total", i.totalBytes)
                put("bytes", i.bytesTransferred); put("resume", i.resumeOffset)
                put("state", i.state.name); put("err", i.lastError ?: "")
                put("sha", i.sha256 ?: "")
            })
        }
        try {
            file.writeText(arr.toString())
        } catch (ignored: Throwable) {
        }
    }

    fun load(): List<TransferItem> = synchronized(lock) {
        val out = ArrayList<TransferItem>()
        if (!file.exists()) return out
        try {
            val arr = JSONArray(file.readText())
            for (idx in 0 until arr.length()) {
                val o = arr.getJSONObject(idx)
                val dir = if (o.optString("dir") == Direction.RECEIVING.name) Direction.RECEIVING else Direction.SENDING
                val item = TransferItem(
                    id = o.optString("id"),
                    batchId = o.optString("batch"),
                    groupId = o.optString("group").ifBlank { null },
                    peerId = o.optString("peerId"),
                    peerName = o.optString("peerName"),
                    direction = dir,
                    displayName = o.optString("name"),
                    uri = o.optString("uri"),
                    mime = o.optString("mime"),
                    size = o.optLong("size")
                )
                item.totalBytes = o.optLong("total", item.size)
                item.bytesTransferred = o.optLong("bytes")
                item.resumeOffset = o.optLong("resume")
                item.sha256 = o.optString("sha").ifBlank { null }
                item.lastError = o.optString("err").ifBlank { null }
                item.state = ItemState.PAUSED
                out.add(item)
            }
        } catch (ignored: Throwable) {
        }
        out
    }

    fun recordResumable(peerName: String, fileName: String, bytes: Long) {
        try {
            File(ctx.filesDir, "resumable.log").appendText("$peerName|$fileName|$bytes|${System.currentTimeMillis()}\n")
        } catch (ignored: Throwable) {
        }
    }

    fun clear() = synchronized(lock) { file.delete() }
}
