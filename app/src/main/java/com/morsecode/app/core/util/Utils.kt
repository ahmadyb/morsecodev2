package com.morsecode.app.core.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.TypedValue
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale

/** Human readable sizes / speeds / durations. */
object Fmt {

    fun size(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(Locale.US, kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(Locale.US, mb)
        return "%.2f GB".format(Locale.US, mb / 1024.0)
    }

    /** Compact form used inside progress rows, e.g. "48.9 MB / 144 MB". */
    fun sizeShort(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1000) return "%.0f KB".format(Locale.US, kb)
        val mb = kb / 1024.0
        if (mb < 1000) return if (mb < 10) "%.1f MB".format(Locale.US, mb) else "%.0f MB".format(Locale.US, mb)
        return "%.2f GB".format(Locale.US, mb / 1024.0)
    }

    fun speed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0"
        val mb = bytesPerSec / 1048576.0
        if (mb >= 1) return "%.1f".format(Locale.US, mb)
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1) "%.0f KB".format(Locale.US, kb) else "0"
    }

    fun speedUnit(bytesPerSec: Long): String = if (bytesPerSec >= 1048576) "MB/s" else "KB/s"

    fun mbSpeed(bytesPerSec: Long): String = "%.1f".format(Locale.US, bytesPerSec / 1048576.0)

    fun duration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s)
        else "%d:%02d".format(Locale.US, m, s)
    }

    fun timeAgo(ms: Long): String {
        val d = System.currentTimeMillis() - ms
        return when {
            d < 60_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 86_400_000 -> "${d / 3_600_000} h ago"
            d < 7 * 86_400_000L -> "${d / 86_400_000} d ago"
            else -> DAY.format(java.util.Date(ms))
        }
    }

    private val DAY = java.text.SimpleDateFormat("d MMM", Locale.US)

    fun dayLabel(ms: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = cal.clone() as java.util.Calendar
        today.set(java.util.Calendar.HOUR_OF_DAY, 0); today.set(java.util.Calendar.MINUTE, 0)
        today.set(java.util.Calendar.SECOND, 0); today.set(java.util.Calendar.MILLISECOND, 0)
        val yesterday = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        return when {
            ms >= today.timeInMillis -> "Today"
            ms >= yesterday.timeInMillis -> "Yesterday"
            else -> DAY.format(java.util.Date(ms))
        }
    }
}

/** Network address helpers used by LAN discovery and WebShare. */
object Net {

    fun localIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            var fallback = "127.0.0.1"
            for (nif in java.util.Collections.list(ifaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in java.util.Collections.list(nif.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.startsWith("wlan") || nif.name.startsWith("ap") || nif.name.startsWith("eth")) {
                            return ip
                        }
                        fallback = ip
                    }
                }
            }
            return fallback
        } catch (t: Throwable) {
            return "127.0.0.1"
        }
    }

    fun subnetOf(ip: String): String {
        val parts = ip.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0/24" else ip
    }

    fun broadcastAddress(ip: String): InetAddress? = try {
        val parts = ip.split(".")
        if (parts.size != 4) null
        else InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
    } catch (t: Throwable) {
        null
    }
}

/** dp / sp helpers so layout code stays terse. */
object D {
    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics).toInt()

    fun sp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, ctx.resources.displayMetrics)
}
