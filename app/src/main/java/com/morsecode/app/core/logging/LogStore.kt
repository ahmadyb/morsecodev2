package com.morsecode.app.core.logging

import android.content.Context
import android.os.Build
import com.morsecode.app.BuildConfig
import com.morsecode.app.di.Di
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device log. A bounded ring buffer in memory plus crash reports on disk.
 * Nothing here ever leaves the phone unless the user exports it.
 */
class LogStore(private val ctx: Context) {

    enum class Level { INFO, WARN, ERROR }

    class Line(val timeMs: Long, val level: Level, val message: String) {
        fun render(): String = format(timeMs, level, message)
    }

    private val ring = ArrayDeque<Line>()
    private val max = 1200

    /**
     * The log lives on disk as well as in memory.
     *
     * It used to be a ring buffer only, so the trail a crash would explain vanished the moment the
     * process died - "the app crashed several times but there are no crash logs" was exactly that.
     * The ring is written out (debounced) and read back on the next start, and it stays until the
     * user clears it, which is what the Log viewer promises.
     */
    private val trailFile: File get() = File(ctx.filesDir, "morsecode-log.txt")
    private val io = Object()
    private var flushScheduled = false
    private var loaded = false
    private val listener: MutableList<() -> Unit> = mutableListOf()
    private val prefs get() = Di.prefs(ctx)

    companion object {
        /** logcat tag: `adb logcat -s MorseCode` */
        const val TAG = "MorseCode"
        private const val VERSION_LINE = "MorseCode"
        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)

        /** Severity, then message - the exact sample shape from the product spec. */
        fun format(timeMs: Long, level: Level, message: String): String =
            synchronized(TIME) { TIME.format(Date(timeMs)) } + " " + level.name.padEnd(5) + " " + message
    }

    fun addListener(l: () -> Unit) { synchronized(listener) { listener.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listener) { listener.remove(l) } }

    fun info(message: String) = append(Level.INFO, message)
    fun warn(message: String) = append(Level.WARN, message)
    fun error(message: String) = append(Level.ERROR, message)

    private fun append(level: Level, message: String) {
        // Mirror to logcat so `adb logcat -s MorseCode` shows the same trail the Log viewer does.
        // This is what makes "did the app actually open and which screen did it reach" answerable
        // from a bug report or from a CI emulator run.
        when (level) {
            Level.INFO -> android.util.Log.i(TAG, message)
            Level.WARN -> android.util.Log.w(TAG, message)
            Level.ERROR -> android.util.Log.e(TAG, message)
        }
        if (!prefs.logEnabled && level != Level.ERROR) return
        synchronized(ring) {
            ring.addLast(Line(System.currentTimeMillis(), level, message))
            while (ring.size > max) ring.removeFirst()
        }
        val snapshot: List<() -> Unit>
        synchronized(listener) { snapshot = ArrayList(listener) }
        for (l in snapshot) {
            try { l() } catch (t: Throwable) { /* a listener must never break logging */ }
        }
        scheduleFlush()
        if (level == Level.ERROR) persistTail()
    }

    fun snapshot(): List<Line> = synchronized(ring) { ArrayList(ring) }

    /**
     * Reads the previous session's lines back. Called once, from the first thread that touches the
     * store, so the Log viewer opens on the history rather than on whatever happened since launch.
     */
    fun restore() {
        synchronized(io) {
            if (loaded) return
            loaded = true
            try {
                val f = trailFile
                if (!f.exists()) return
                val lines = f.readLines()
                val kept = if (lines.size > max) lines.subList(lines.size - max, lines.size) else lines
                synchronized(ring) {
                    for (raw in kept) {
                        val line = parse(raw) ?: continue
                        ring.addLast(line)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    /** `HH:mm:ss LEVEL message` back into a [Line]; anything older is dropped rather than guessed. */
    private fun parse(raw: String): Line? {
        if (raw.length < 18) return null
        val level = when {
            raw.startsWith("WARN", 9) -> Level.WARN
            raw.startsWith("ERROR", 9) -> Level.ERROR
            raw.startsWith("INFO", 9) -> Level.INFO
            else -> return null
        }
        val time = try {
            synchronized(TIME) { TIME.parse(raw.substring(0, 8))?.time } ?: System.currentTimeMillis()
        } catch (t: Throwable) {
            System.currentTimeMillis()
        }
        return Line(time, level, raw.substring(16))
    }

    private fun scheduleFlush() {
        synchronized(io) {
            if (flushScheduled) return
            flushScheduled = true
        }
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                synchronized(io) { flushScheduled = false }
                flush()
            }, 1500)
        } catch (t: Throwable) {
            flush()
        }
    }

    /** Writes the whole (bounded) ring out. Small - at most a few hundred KB - and rare. */
    fun flush() {
        try {
            val sb = StringBuilder()
            for (line in snapshot()) sb.append(line.render()).append('\n')
            val f = trailFile
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(sb.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (ignored: Throwable) {
        }
    }

    fun clear() {
        synchronized(ring) { ring.clear() }
        crashFile().delete()
        trailFile.delete()
        info("Log cleared")
    }

    /** True when there is history on disk, so the viewer can say where it came from. */
    fun hasStoredTrail(): Boolean = try { trailFile.exists() && trailFile.length() > 0 } catch (t: Throwable) { false }

    // ---- crash reports -----------------------------------------------------

    fun crashFile(): File = File(ctx.filesDir, "crash-reports.txt")

    fun crashCount(): Int {
        val f = crashFile()
        if (!f.exists()) return 0
        return f.readText().split("\n\n").count { it.contains("FATAL") }
    }

    fun readCrashes(): String =
        if (crashFile().exists()) crashFile().readText() else ""

    fun recordCrash(t: Throwable) {
        try {
            val head = StringBuilder()
            head.append("FATAL ").append(TIME.format(Date())).append(" ")
            head.append(t.javaClass.name).append(": ").append(t.message).append("\n")
            for (el in t.stackTrace.take(24)) head.append("    at ").append(el).append("\n")
            head.append("    build=").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")")
                .append(" device=").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" api=").append(Build.VERSION.SDK_INT).append("\n\n")
            crashFile().appendText(head.toString())
            append(Level.ERROR, "Crash captured: ${t.javaClass.simpleName}")
        } catch (ignored: Throwable) {
            // never throw from the crash handler
        }
    }

    /**
     * One .txt, starting with `MorseCode <version> (<versionCode>)` exactly as specified,
     * containing the full app log plus every captured crash report.
     */
    fun exportText(): String {
        val sb = StringBuilder()
        sb.append("$VERSION_LINE ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")")
            .append(" - started ").append(Date().toString()).append("\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" - Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("=".repeat(64)).append("\n\n")
        for (line in snapshot()) sb.append(line.render()).append("\n")
        val crashes = readCrashes()
        if (crashes.isNotBlank()) {
            sb.append("\n").append("=".repeat(64)).append("\nCRASH REPORTS\n")
            sb.append("=".repeat(64)).append("\n").append(crashes)
        }
        return sb.toString()
    }

    fun persistTail() {
        try {
            val f = File(ctx.filesDir, "last-log.txt")
            f.writeText(exportText())
        } catch (ignored: Throwable) {
        }
    }

    /** Called on cold start so exported logs always carry the version header. */
    fun startup() {
        info("$VERSION_LINE ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
        info("Device ${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }
}
