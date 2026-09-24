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
    private val listener: MutableList<() -> Unit> = mutableListOf()
    private val prefs get() = Di.prefs(ctx)

    companion object {
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
        if (level == Level.ERROR) persistTail()
    }

    fun snapshot(): List<Line> = synchronized(ring) { ArrayList(ring) }

    fun clear() {
        synchronized(ring) { ring.clear() }
        crashFile().delete()
        info("Log cleared")
    }

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
