package com.morsecode.app.core.webshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.morsecode.app.core.media.MediaLibrary
import com.morsecode.app.core.model.MediaItem
import com.morsecode.app.core.network.Wire
import com.morsecode.app.core.storage.Destinations
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.util.DeviceTier
import com.morsecode.app.core.util.Paths
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * WebShare's HTTP server: a compact, hand written HTTP/1.1 server (same shape and behaviour as
 * the NanoHTTPD the product spec names - plain sockets, one thread per connection, no framework).
 *
 * Address: bare `IP:port` (a token would be security theatre on a LAN - access is consent based
 * instead: a new browser session triggers the "Browser wants access" popup on the phone and
 * until it is accepted the browser only ever sees the waiting screen).
 *
 * INV-4: no idle timeout, no auto teardown, never stops while a browser session is connected.
 * INV-6: exactly one upload progress UI - the server tracks one upload job at a time.
 * INV-7: the Files browser keeps a sticky, segment-clickable address bar (client side).
 */
class WebShareServer(
    private val ctx: Context,
    private val port: Int,
    private val assetDir: String,
    private val consent: (browser: String, ip: String) -> Boolean,
    private val info: () -> ServerInfo
) {

    class ServerInfo(val deviceName: String, val url: String)

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val media: MediaLibrary get() = com.morsecode.app.di.Di.media(ctx)
    private val startedAt = System.currentTimeMillis()

    /** Live browser sessions (after consent). */
    val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()

    class Session(val ip: String, val browser: String, val grantedAt: Long) {
        val lastSeen = AtomicLong(System.currentTimeMillis())
    }

    // upload state (INV-6: one upload at a time)
    class UploadState {
        @Volatile var active = false
        @Volatile var name = ""
        @Volatile var total = 0L
        @Volatile var done = 0L
        @Volatile var error: String? = null
        @Volatile var finished = false
    }

    val upload = UploadState()

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(port))
            server = ss
            running = true
            Thread({ acceptLoop(ss) }, "mc-web-accept").apply { isDaemon = true }.start()
            Log.info("WebShare listening on 0.0.0.0:$port")
            true
        } catch (t: Throwable) {
            Log.error("WebShare could not bind :$port - ${t.message}")
            false
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (ignored: Throwable) {}
        server = null
        sessions.clear()
        Log.info("WebShare stopped by user")
    }

    val isRunning: Boolean get() = running

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (t: Throwable) {
                break
            }
            Thread({ handle(socket) }, "mc-web-conn").apply { isDaemon = true }.start()
        }
    }

    // ---------------------------------------------------------------- request

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
        val contentLength: Long,
        val socket: Socket
    )

    private fun readRequest(socket: Socket): Request? {
        try {
            val ins = socket.getInputStream()
            val reader = WireStream(ins)
            val requestLine = reader.readLine() ?: return null
            val parts = requestLine.split(" ")
            if (parts.size < 2) return null
            val method = parts[0]
            val rawTarget = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val qIdx = rawTarget.indexOf('?')
            val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
            val query = HashMap<String, String>()
            if (qIdx >= 0) {
                for (pair in rawTarget.substring(qIdx + 1).split("&")) {
                    if (pair.isBlank()) continue
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        query[URLDecoder.decode(pair.substring(0, eq), "UTF-8")] =
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    }
                }
            }
            val len = headers["content-length"]?.toLongOrNull() ?: 0L
            return Request(method, URLDecoder.decode(path, "UTF-8"), query, headers, ins, len, socket)
        } catch (t: Throwable) {
            return null
        }
    }

    /** Byte-by-byte header reader - the same discipline the transfer protocol requires. */
    private class WireStream(private val ins: InputStream) {
        fun readLine(limit: Int = 8192): String? {
            val buf = java.io.ByteArrayOutputStream(128)
            var count = 0
            while (true) {
                val b = ins.read()
                if (b < 0) return if (count == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
                if (b == '\n'.code) return String(buf.toByteArray(), Charsets.UTF_8)
                if (b != '\r'.code) buf.write(b)
                if (++count > limit) return null
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val req = readRequest(socket) ?: run { socket.close(); return }
            route(req)
        } catch (t: Throwable) {
            Log.warn("WebShare request failed: ${t.message}")
        } finally {
            try { socket.close() } catch (ignored: Throwable) {}
        }
    }

    private fun route(req: Request) {
        val ip = req.socket.inetAddress?.hostAddress ?: "?"
        val browser = browserName(req.headers["user-agent"] ?: "")

        val openPaths = setOf("/api/hello", "/api/consent", "/favicon.ico")
        if (req.path !in openPaths && !isGranted(ip, browser)) {
            // Until the phone holder accepts, the browser only ever sees the waiting screen.
            if (req.path == "/" || !req.path.startsWith("/api/")) {
                respondHtml(req, waitPage())
            } else {
                respondJson(req, 403, JSONObject().put("error", "pending").toString())
            }
            return
        }
        sessions[ip]?.lastSeen?.set(System.currentTimeMillis())

        when (req.path) {
            "/", "/index.html" -> respondHtml(req, page())
            "/api/hello" -> respondJson(req, 200, JSONObject().apply {
                put("app", "MorseCode")
                put("version", com.morsecode.app.BuildConfig.VERSION_NAME)
                put("device", info().deviceName)
                put("url", info().url)
                put("granted", isGranted(ip, browser))
                put("uptimeMs", System.currentTimeMillis() - startedAt)
                put("theme", if (com.morsecode.app.di.Di.prefs(ctx).darkMode) "dark" else "light")
                put("accent", com.morsecode.app.di.Di.prefs(ctx).accentId)
            }.toString())
            "/api/consent" -> {
                val granted = isGranted(ip, browser) || consent(browser, ip)
                if (granted) sessions[ip] = Session(ip, browser, System.currentTimeMillis())
                respondJson(req, 200, JSONObject().apply { put("granted", granted) }.toString())
            }
            "/api/info" -> respondJson(req, 200, JSONObject().apply {
                put("device", info().deviceName)
                put("url", info().url)
                put("serverTime", System.currentTimeMillis())
                put("transport", "Wi-Fi LAN")
                put("sessionCount", sessions.size)
            }.toString())
            "/api/counts" -> {
                val counts = media.categoryCounts()
                val o = JSONObject()
                for ((k, v) in counts) o.put(k.lowercase(), v)
                respondJson(req, 200, o.toString())
            }
            "/api/files" -> respondJson(req, 200, filesJson(req.query))
            "/api/fs" -> respondJson(req, 200, fsJson(req.query))
            "/api/qr" -> respondJson(req, 200, JSONObject().put("qrDisabled", true)
                .put("url", info().url).toString())
            "/thumbnail" -> respondThumbnail(req)
            "/download" -> respondMedia(req)
            "/download-file" -> respondFile(req)
            "/download-folder" -> respondFolder(req)
            "/download-zip" -> respondZip(req)
            "/upload" -> if (req.method == "POST") handleUpload(req) else respondJson(req, 405, "{}")
            "/api/upload-status" -> respondJson(req, 200, JSONObject().apply {
                put("active", upload.active)
                put("name", upload.name)
                put("total", upload.total)
                put("done", upload.done)
                put("error", upload.error ?: "")
                put("finished", upload.finished)
            }.toString())
            else -> respondJson(req, 404, JSONObject().put("error", "not found").toString())
        }
    }

    private fun browserName(ua: String): String = when {
        ua.contains("Edg/") -> "Edge"
        ua.contains("OPR/") || ua.contains("Opera") -> "Opera"
        ua.contains("Chrome") -> "Chrome"
        ua.contains("Firefox") -> "Firefox"
        ua.contains("Safari") -> "Safari"
        ua.contains("curl") -> "curl"
        ua.isBlank() -> "Browser"
        else -> "Browser"
    }

    private fun isGranted(ip: String, browser: String): Boolean = sessions.containsKey(ip)

    // ---------------------------------------------------------------- responses

    private fun respond(req: Request, status: Int, mime: String, bytes: ByteArray, extra: Map<String, String> = emptyMap()) {
        val out = BufferedOutputStream(req.socket.getOutputStream(), 64 * 1024)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        head.append("Content-Type: ").append(mime).append("\r\n")
        head.append("Content-Length: ").append(bytes.size).append("\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n")
        for ((k, v) in extra) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun respondJson(req: Request, status: Int, json: String) =
        respond(req, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

    private fun respondHtml(req: Request, html: String) =
        respond(req, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        206 -> "Partial Content"; else -> "OK"
    }

    // ---------------------------------------------------------------- payloads

    private fun filesJson(query: Map<String, String>): String {
        val category = (query["category"] ?: "photos").lowercase()
        val search = (query["q"] ?: "").lowercase()
        val sort = query["sort"] ?: "newest"
        val items = when (category) {
            "videos" -> media.all(MediaLibrary.Category.VIDEOS)
            "music" -> media.all(MediaLibrary.Category.MUSIC)
            "docs" -> media.documents("docs")
            "apps" -> media.installedApps()
            "files" -> media.all(MediaLibrary.Category.ALL)
            else -> media.all(MediaLibrary.Category.PHOTOS)
        }
        val filtered = if (search.isBlank()) items else items.filter { it.displayName.lowercase().contains(search) }
        val sorted = when (sort) {
            "oldest" -> filtered.sortedBy { it.dateMs }
            "name" -> filtered.sortedBy { it.displayName.lowercase() }
            "size" -> filtered.sortedByDescending { it.size }
            else -> filtered.sortedByDescending { it.dateMs }
        }
        val arr = JSONArray()
        for (i in sorted) {
            // Media JSON exposes `date` as dateTakenMs when known so the browser sorts/headers
            // by real capture date (the same rule the native grid uses).
            arr.put(JSONObject().apply {
                put("id", i.id)
                put("name", i.displayName)
                put("mime", i.mime)
                put("size", i.size)
                put("duration", i.durationMs)
                put("width", i.width)
                put("height", i.height)
                put("date", if (i.dateMs > 0) i.dateMs else 0)
                put("folder", i.bucket)
                put("uri", i.uri)
                put("isApp", i.isApp)
                put("pkg", i.packageName)
            })
        }
        return JSONObject().apply {
            put("category", category)
            put("count", sorted.size)
            put("items", arr)
        }.toString()
    }

    private fun fsJson(query: Map<String, String>): String {
        val path = query["path"] ?: "/storage/emulated/0"
        val dir = File(path)
        val arr = JSONArray()
        val children = try { dir.listFiles() } catch (t: Throwable) { null } ?: emptyArray()
        // Quick folders for the sidebar
        val quick = JSONArray()
        for (name in listOf("Download", "DCIM", "Documents", "Pictures", "Movies", "Music", "WhatsApp", "Telegram")) {
            val f = File(android.os.Environment.getExternalStorageDirectory(), name)
            if (f.exists()) quick.put(JSONObject().put("name", name).put("path", f.absolutePath))
        }
        for (f in children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("dir", f.isDirectory)
                put("size", if (f.isDirectory) -1 else f.length())
                put("modified", f.lastModified())
                put("path", f.absolutePath)
                put("mime", if (f.isDirectory) "inode/directory" else Paths.guessMime(f.name))
            })
        }
        return JSONObject().apply {
            put("path", dir.absolutePath)
            put("parent", dir.parent ?: "")
            put("segments", JSONArray(dir.absolutePath.split("/").filter { it.isNotBlank() }))
            put("count", arr.length())
            put("items", arr)
            put("quick", quick)
        }.toString()
    }

    private fun respondThumbnail(req: Request) {
        val id = req.query["id"]?.toLongOrNull() ?: return respondJson(req, 400, "{}")
        val kind = req.query["kind"] ?: "image"
        try {
            val uri = android.content.ContentUris.withAppendedId(
                MediaStore.Files.getContentUri("external"), id
            )
            var bmp: Bitmap? = if (kind == "video") {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                )
            }
            if (bmp == null) {
                bmp = decodeSampled(uri, DeviceTier.thumbnailPx)
            }
            if (bmp == null) return respondJson(req, 404, "{}")
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            respond(req, 200, "image/jpeg", out.toByteArray())
        } catch (t: Throwable) {
            respondJson(req, 500, "{}")
        }
    }

    private fun decodeSampled(uri: Uri, target: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > target * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (t: Throwable) {
        null
    }

    private fun respondMedia(req: Request) {
        val id = req.query["id"]?.toLongOrNull() ?: return respondJson(req, 400, "{}")
        streamUri(req, android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))
    }

    private fun respondFile(req: Request) {
        val path = req.query["path"] ?: return respondJson(req, 400, "{}")
        streamFile(req, File(path))
    }

    private fun respondFolder(req: Request) {
        val path = req.query["path"] ?: return respondJson(req, 400, "{}")
        val dir = File(path)
        if (!dir.isDirectory) return respondJson(req, 404, "{}")
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        respondZipStream(req, files.map { it.name to { FileOutputStream(it) } })
    }

    private fun respondZip(req: Request) {
        val ids = (req.query["ids"] ?: "").split(",").mapNotNull { it.trim().toLongOrNull() }
        val uris = ids.map { android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), it) }
        val out = BufferedOutputStream(req.socket.getOutputStream(), 64 * 1024)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        com.morsecode.app.core.storage.ZipUtil.zipUris(ctx, uris, out)
        out.flush()
    }

    private fun respondZipStream(req: Request, entries: List<Pair<String, () -> OutputStream>>) {
        val out = BufferedOutputStream(req.socket.getOutputStream(), 64 * 1024)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nConnection: close\r\n\r\n".toByteArray())
        val zos = java.util.zip.ZipOutputStream(out)
        for ((name, open) in entries) {
            try {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                val file = File(name)
                if (file.exists()) file.inputStream().use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
            } catch (ignored: Throwable) {
            }
        }
        try { zos.finish() } catch (ignored: Throwable) {}
        out.flush()
    }

    private fun streamUri(req: Request, uri: Uri) {
        try {
            val length = Paths.size(ctx, uri)
            if (length <= 0) return respondJson(req, 404, "{}")
            val name = Paths.displayName(ctx, uri)
            val out = BufferedOutputStream(req.socket.getOutputStream(), 256 * 1024)
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Disposition: attachment; filename=\"" + name.replace("\"", "") + "\"\r\n" +
                    "Content-Length: " + length + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(out, 256 * 1024) }
            out.flush()
        } catch (t: Throwable) {
            Log.warn("Download failed: ${t.message}")
        }
    }

    private fun streamFile(req: Request, file: File) {
        if (!file.exists() || !file.isFile) return respondJson(req, 404, "{}")
        try {
            val out = BufferedOutputStream(req.socket.getOutputStream(), 256 * 1024)
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Disposition: attachment; filename=\"" + file.name.replace("\"", "") + "\"\r\n" +
                    "Content-Length: " + file.length() + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            file.inputStream().use { it.copyTo(out, 256 * 1024) }
            out.flush()
        } catch (t: Throwable) {
            Log.warn("File download failed: ${t.message}")
        }
    }

    /** Browser -> phone upload. Files land in the phone's chosen destination. */
    private fun handleUpload(req: Request) {
        upload.active = true
        upload.finished = false
        upload.error = null
        upload.done = 0
        upload.total = req.contentLength
        try {
            val boundary = (req.headers["content-type"] ?: "").substringAfter("boundary=", "")
            if (boundary.isBlank()) {
                upload.error = "missing multipart boundary"
                upload.active = false
                return respondJson(req, 400, "{\"error\":\"boundary\"}")
            }
            val dir = Destinations.receivedDir(ctx, com.morsecode.app.di.Di.saf(ctx).defaultFolder())
            var saved = 0
            val buffer = ByteArray(64 * 1024)
            // Frugal multipart parsing: scan for part headers, then stream the payload.
            val reader = java.io.BufferedInputStream(req.body, 64 * 1024)
            val state = MultipartScanner(reader, boundary)
            while (true) {
                val part = state.nextPart() ?: break
                val name = part.fileName.ifBlank { "upload-${System.currentTimeMillis()}" }
                val target = Destinations.resolve(
                    ctx, dir, name, part.size, null,
                    com.morsecode.app.di.Di.prefs(ctx).conflictPolicy
                )
                upload.name = name
                FileOutputStream(target.finalFile).use { fos ->
                    var read: Int
                    while (true) {
                        read = state.readPayload(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        saved += read
                        upload.done = saved.toLong()
                    }
                }
                Log.info("WebShare upload stored: $name")
            }
            upload.finished = true
            upload.active = false
            respondJson(req, 200, JSONObject().apply {
                put("ok", true)
                put("bytes", saved)
                put("destination", Destinations.humanReadableDestination(ctx))
            }.toString())
        } catch (t: Throwable) {
            upload.error = t.message
            upload.active = false
            respondJson(req, 500, JSONObject().put("error", t.message ?: "upload failed").toString())
        }
    }

    /** Minimal streaming multipart reader (no framework, no full buffering). */
    private class MultipartScanner(private val ins: java.io.BufferedInputStream, private val boundary: String) {
        class Part(val fileName: String, val size: Long)

        private val delimiter = ("--" + boundary).toByteArray(Charsets.ISO_8859_1)
        private val endDelimiter = "--".toByteArray(Charsets.ISO_8859_1)
        private var inPart = false

        fun nextPart(): Part? {
            if (inPart) skipToBoundary()
            val line = readLine() ?: return null
            if (!line.trimEnd().endsWith(boundary) && !line.startsWith("--")) {
                // consume up to the first boundary
                while (true) {
                    val l = readLine() ?: return null
                    if (l.trimEnd().endsWith(boundary)) break
                }
            }
            var fileName = ""
            while (true) {
                val header = readLine() ?: return null
                if (header.isBlank()) break
                if (header.lowercase().startsWith("content-disposition") && header.contains("filename=\"")) {
                    fileName = header.substringAfter("filename=\"").substringBefore("\"")
                }
            }
            inPart = true
            return Part(fileName, 0)
        }

        /** Reads payload bytes until the closing boundary; returns -1 when the part is done. */
        fun readPayload(buffer: ByteArray): Int {
            var read = 0
            while (read < buffer.size) {
                val b = ins.read()
                if (b < 0) return if (read == 0) -1 else read
                buffer[read++] = b.toByte()
                if (read >= delimiter.size && endsWith(buffer, read, delimiter)) {
                    inPart = false
                    return read - delimiter.size
                }
            }
            return read
        }

        private fun endsWith(buffer: ByteArray, length: Int, pattern: ByteArray): Boolean {
            if (length < pattern.size) return false
            val start = length - pattern.size
            for (i in pattern.indices) if (buffer[start + i] != pattern[i]) return false
            return true
        }

        private fun skipToBoundary() {
            val buffer = ByteArray(4096)
            while (readPayload(buffer) >= 4096) {
                // keep discarding until the boundary is found
            }
        }

        private fun readLine(): String? {
            val out = java.io.ByteArrayOutputStream(128)
            while (true) {
                val b = ins.read()
                if (b < 0) return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1)
                if (b == '\n'.code) return String(out.toByteArray(), Charsets.ISO_8859_1)
                out.write(b)
            }
        }
    }

    // ---------------------------------------------------------------- pages

    private fun waitPage(): String {
        val url = info().url
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MorseCode WebShare</title>
<style>
body{margin:0;background:#0B0B0B;color:#F5F5F5;font-family:-apple-system,Segoe UI,Roboto,sans-serif;
display:flex;align-items:center;justify-content:center;height:100vh}
.box{text-align:center;max-width:360px;padding:28px}
.mark{width:64px;height:64px;border-radius:18px;background:linear-gradient(135deg,#FACC15,#F97316);margin:0 auto 18px;
display:flex;align-items:center;justify-content:center;font-weight:800;color:#1A1002;font-size:26px}
h1{font-size:19px;margin:0 0 8px}
p{color:#A3A3A3;font-size:14px;line-height:1.5}
code{color:#FACC15;font-size:13px}
.dot{color:#FACC15}
</style></head><body><div class="box">
<div class="mark">M</div>
<h1>Waiting for the phone to accept<span class="dot">…</span></h1>
<p>A MorseCode session is asking for access on the phone.<br>Tap <b>Accept</b> on the phone to continue.</p>
<p><code>$url</code></p>
</div>
<script>
// The page polls consent; nothing is shown to the browser before the phone holder accepts.
var t=setInterval(function(){
 fetch('/api/consent',{cache:'no-store'}).then(function(r){return r.json()}).then(function(j){
   if(j.granted){clearInterval(t);location.reload();}
 });
},2000);
</script>
</body></html>"""
    }

    private fun page(): String {
        val assets = File(ctx.filesDir, assetDir)
        val fallback = File(assets, "index.html")
        if (fallback.exists()) {
            return try {
                fallback.readText()
            } catch (t: Throwable) {
                builtInPage()
            }
        }
        return builtInPage()
    }

    /**
     * The WebShare single page app. Ship-ready, dependency free, dark by default with an exact
     * light-mode mirror, honouring every WebShare rule in the product spec (INV-4/5/6/7/8).
     */
    private fun builtInPage(): String {
        val boot = JSONObject().apply {
            put("device", info().deviceName)
            put("url", info().url)
        }
        return WebShareAssets.PAGE.replace("__BOOT__", boot.toString())
    }

    fun sessionCount(): Int = sessions.size

    /** Reference kept so the transfer log records WebShare browser sessions. */
    fun logSession(ip: String, browser: String) {
        Log.info("Browser session accepted: $browser @ $ip")
    }
}
