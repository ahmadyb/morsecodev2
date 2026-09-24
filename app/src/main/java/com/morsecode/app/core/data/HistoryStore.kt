package com.morsecode.app.core.data

import android.content.Context
import com.morsecode.app.core.model.GroupHistoryEntry
import com.morsecode.app.core.model.HistoryEntry
import com.morsecode.app.core.model.PeerOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Completed transfers, newest first. A Broadcast lands here as one GroupHistoryEntry.
 * Stored as JSON lines in the app's private files dir - no database engine needed.
 */
class HistoryStore(private val ctx: Context) {

    private val file: File get() = File(ctx.filesDir, "history.jsonl")
    private val groupsFile: File get() = File(ctx.filesDir, "history-groups.jsonl")
    private val lock = Any()

    fun add(entry: HistoryEntry) = synchronized(lock) {
        file.appendText(toJson(entry).toString() + "\n")
        trim(file, 400)
    }

    fun addGroup(group: GroupHistoryEntry) = synchronized(lock) {
        groupsFile.appendText(toJson(group).toString() + "\n")
        trim(groupsFile, 120)
    }

    fun entries(direction: String? = null): List<HistoryEntry> = synchronized(lock) {
        val out = ArrayList<HistoryEntry>()
        if (file.exists()) {
            file.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val o = JSONObject(line)
                    val e = fromJson(o)
                    if (direction == null || e.direction == direction) out.add(e)
                } catch (ignored: Throwable) {
                }
            }
        }
        out.sortedByDescending { it.whenMs }
    }

    fun groups(direction: String? = null): List<GroupHistoryEntry> = synchronized(lock) {
        val out = ArrayList<GroupHistoryEntry>()
        if (groupsFile.exists()) {
            groupsFile.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val g = groupFromJson(JSONObject(line))
                    if (direction == null || g.direction == direction) out.add(g)
                } catch (ignored: Throwable) {
                }
            }
        }
        out.sortedByDescending { it.whenMs }
    }

    fun count(direction: String? = null): Int = entries(direction).size + groups(direction).size

    fun clear() = synchronized(lock) {
        file.delete()
        groupsFile.delete()
    }

    private fun trim(f: File, keep: Int) {
        try {
            if (!f.exists()) return
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.size <= keep) return
            f.writeText(lines.takeLast(keep).joinToString("\n") + "\n")
        } catch (ignored: Throwable) {
        }
    }

    // ---- json --------------------------------------------------------------

    private fun toJson(e: HistoryEntry) = JSONObject().apply {
        put("id", e.id); put("name", e.fileName); put("peer", e.peerName)
        put("dir", e.direction); put("size", e.size); put("ok", e.ok)
        put("when", e.whenMs); put("err", e.error ?: ""); put("transport", e.transport)
        put("uri", e.uri)
    }

    private fun fromJson(o: JSONObject) = HistoryEntry(
        id = o.optString("id"),
        fileName = o.optString("name"),
        peerName = o.optString("peer"),
        direction = o.optString("dir", "sent"),
        size = o.optLong("size"),
        ok = o.optBoolean("ok", true),
        whenMs = o.optLong("when"),
        error = o.optString("err").ifBlank { null },
        transport = o.optString("transport", "lan"),
        uri = o.optString("uri")
    )

    private fun toJson(g: GroupHistoryEntry) = JSONObject().apply {
        put("id", g.id); put("dir", g.direction); put("when", g.whenMs)
        put("files", g.fileCount); put("bytes", g.totalBytes); put("label", g.label)
        val arr = JSONArray()
        for (p in g.peerOutcomes) {
            arr.put(JSONObject().apply {
                put("peerId", p.peerId); put("peer", p.peerName); put("transport", p.transport)
                put("sent", p.sent); put("failed", p.failed); put("skipped", p.skipped)
                put("avg", p.avgSpeedBps); put("outcome", p.outcome)
            })
        }
        put("peers", arr)
    }

    private fun groupFromJson(o: JSONObject): GroupHistoryEntry {
        val g = GroupHistoryEntry(
            id = o.optString("id"),
            direction = o.optString("dir", "sent"),
            whenMs = o.optLong("when"),
            fileCount = o.optInt("files"),
            totalBytes = o.optLong("bytes"),
            label = o.optString("label")
        )
        val arr = o.optJSONArray("peers") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            g.peerOutcomes.add(
                PeerOutcome(
                    peerId = p.optString("peerId"),
                    peerName = p.optString("peer"),
                    transport = p.optString("transport", "lan"),
                    sent = p.optInt("sent"),
                    failed = p.optInt("failed"),
                    skipped = p.optInt("skipped"),
                    avgSpeedBps = p.optLong("avg"),
                    outcome = p.optString("outcome", "completed")
                )
            )
        }
        return g
    }
}
