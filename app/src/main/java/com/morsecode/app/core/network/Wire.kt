package com.morsecode.app.core.network

import com.morsecode.app.BuildConfig
import org.json.JSONObject

/**
 * The MorseCode LAN wire protocol.
 *
 * Control connection (one TCP connection per peer): UTF-8 JSON lines.
 *   HELLO  -> ACCEPT | REJECT
 *   META   -> ACK            (carries resume offset / "already present")
 *   PAUSE_REQ, RESUME_REQ, CANCEL, BYE, PROGRESS, DONE, DONE_OK, DONE_FAIL
 *
 * Data connection (a separate TCP connection per file, per peer):
 *   first line: JSON header terminated by '\n'  -> must be read byte-by-byte
 *   then frames: "MLNK" + int32 seq + int32 len + payload(len) + int32 crc32
 */
object Wire {

    const val MAGIC = "MLNK"
    const val CHUNK = 256 * 1024
    const val MAX_LEN = 4 * CHUNK
    const val READ_TIMEOUT_MS = 25_000
    const val IDLE_WATCHDOG_MS = 45_000

    // control message types
    const val T_HELLO = "HELLO"
    const val T_ACCEPT = "ACCEPT"
    const val T_REJECT = "REJECT"
    const val T_META = "META"
    const val T_ACK = "ACK"
    const val T_DATA = "DATA"
    const val T_PAUSE = "PAUSE_REQ"
    const val T_RESUME = "RESUME_REQ"
    const val T_CANCEL = "CANCEL"
    const val T_BYE = "BYE"
    const val T_PROGRESS = "PROGRESS"
    const val T_DONE = "DONE"
    const val T_DONE_OK = "DONE_OK"
    const val T_DONE_FAIL = "DONE_FAIL"

    const val APP = "morsecode"

    fun hello(deviceId: String, name: String): String = JSONObject().apply {
        put("t", T_HELLO); put("app", APP); put("id", deviceId); put("name", name)
        put("uuid", BuildConfig.APP_UUID)
    }.toString()

    fun accept(deviceId: String, name: String): String = JSONObject().apply {
        put("t", T_ACCEPT); put("id", deviceId); put("name", name)
    }.toString()

    fun reject(reason: String): String = JSONObject().apply {
        put("t", T_REJECT); put("reason", reason)
    }.toString()

    fun meta(
        fileId: String, name: String, size: Long, mime: String,
        sha: String?, resume: Long, batchId: String, groupId: String?
    ): String = JSONObject().apply {
        put("t", T_META); put("fileId", fileId); put("name", name); put("size", size)
        put("mime", mime); put("sha", sha ?: ""); put("resume", resume)
        put("batch", batchId); put("group", groupId ?: "")
    }.toString()

    fun ack(fileId: String, offset: Long, status: String, message: String? = null): String =
        JSONObject().apply {
            put("t", T_ACK); put("fileId", fileId); put("offset", offset); put("status", status)
            if (message != null) put("msg", message)
        }.toString()

    fun dataHeader(fileId: String, name: String, size: Long, offset: Long): String =
        JSONObject().apply {
            put("t", T_DATA); put("fileId", fileId); put("name", name)
            put("size", size); put("offset", offset)
        }.toString()

    fun progress(fileId: String, bytes: Long): String = JSONObject().apply {
        put("t", T_PROGRESS); put("fileId", fileId); put("bytes", bytes)
    }.toString()

    fun done(fileId: String, bytes: Long, crc: Long): String = JSONObject().apply {
        put("t", T_DONE); put("fileId", fileId); put("bytes", bytes); put("crc", crc)
    }.toString()

    fun doneOk(fileId: String): String = JSONObject().apply {
        put("t", T_DONE_OK); put("fileId", fileId)
    }.toString()

    fun doneFail(fileId: String, error: String): String = JSONObject().apply {
        put("t", T_DONE_FAIL); put("fileId", fileId); put("err", error)
    }.toString()

    fun cancel(fileId: String): String = JSONObject().apply {
        put("t", T_CANCEL); put("fileId", fileId)
    }.toString()

    fun pause(fileId: String): String = JSONObject().apply {
        put("t", T_PAUSE); put("fileId", fileId)
    }.toString()

    fun bye(reason: String): String = JSONObject().apply {
        put("t", T_BYE); put("reason", reason)
    }.toString()

    fun parse(line: String): JSONObject? = try {
        JSONObject(line)
    } catch (t: Throwable) {
        null
    }
}
