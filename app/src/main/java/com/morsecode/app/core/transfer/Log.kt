package com.morsecode.app.core.transfer

import android.content.Context
import com.morsecode.app.core.logging.LogStore
import com.morsecode.app.di.AppServices

/**
 * Thin global facade over [LogStore] so engine/transport code can log without threading a
 * Context through every call. The store is created once by MorsecodeApp.
 */
object Log {

    @Volatile private var store: LogStore? = null

    fun attach(ctx: Context) {
        store = AppServices.logs(ctx)
    }

    private val s: LogStore? get() = store

    fun info(message: String) = s?.info(message)
    fun warn(message: String) = s?.warn(message)
    fun error(message: String) = s?.error(message)

    fun snapshot(): List<LogStore.Line> = s?.snapshot() ?: emptyList()
    fun exportText(): String = s?.exportText() ?: ""
    fun clear() = s?.clear()
    fun crashCount(): Int = s?.crashCount() ?: 0
    fun crashes(): String = s?.readCrashes() ?: ""
}
