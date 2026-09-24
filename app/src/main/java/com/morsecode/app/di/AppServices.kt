package com.morsecode.app.di

import android.content.Context
import com.morsecode.app.core.data.HistoryStore
import com.morsecode.app.core.data.JournalStore
import com.morsecode.app.core.data.Prefs
import com.morsecode.app.core.logging.LogStore
import com.morsecode.app.core.media.MediaLibrary
import com.morsecode.app.core.storage.SafStore
import com.morsecode.app.core.transfer.TransferEngine
import com.morsecode.app.core.webshare.WebShareController

/**
 * Manual service locator - this app deliberately ships without a DI framework.
 * Everything is created once in [MorsecodeApp] and read back through here.
 */
object AppServices {

    @Volatile private var _prefs: Prefs? = null
    @Volatile private var _logs: LogStore? = null
    @Volatile private var _history: HistoryStore? = null
    @Volatile private var _journal: JournalStore? = null
    @Volatile private var _media: MediaLibrary? = null
    @Volatile private var _engine: TransferEngine? = null
    @Volatile private var _web: WebShareController? = null
    @Volatile private var _saf: SafStore? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        prefs(app); logs(app); history(app); journal(app); media(app); engine(app); web(app); saf(app)
    }

    fun prefs(ctx: Context): Prefs = _prefs ?: synchronized(this) {
        _prefs ?: Prefs(ctx.applicationContext).also { _prefs = it }
    }

    fun logs(ctx: Context): LogStore = _logs ?: synchronized(this) {
        _logs ?: LogStore(ctx.applicationContext).also { _logs = it }
    }

    fun history(ctx: Context): HistoryStore = _history ?: synchronized(this) {
        _history ?: HistoryStore(ctx.applicationContext).also { _history = it }
    }

    fun journal(ctx: Context): JournalStore = _journal ?: synchronized(this) {
        _journal ?: JournalStore(ctx.applicationContext).also { _journal = it }
    }

    fun media(ctx: Context): MediaLibrary = _media ?: synchronized(this) {
        _media ?: MediaLibrary(ctx.applicationContext).also { _media = it }
    }

    fun engine(ctx: Context): TransferEngine = _engine ?: synchronized(this) {
        _engine ?: TransferEngine(ctx.applicationContext).also { _engine = it }
    }

    fun web(ctx: Context): WebShareController = _web ?: synchronized(this) {
        _web ?: WebShareController(ctx.applicationContext).also { _web = it }
    }

    fun saf(ctx: Context): SafStore = _saf ?: synchronized(this) {
        _saf ?: SafStore(ctx.applicationContext).also { _saf = it }
    }
}

/** Short alias used across the codebase. */
object Di {
    fun prefs(ctx: Context): Prefs = AppServices.prefs(ctx)
    fun logs(ctx: Context): LogStore = AppServices.logs(ctx)
    fun history(ctx: Context): HistoryStore = AppServices.history(ctx)
    fun journal(ctx: Context): JournalStore = AppServices.journal(ctx)
    fun media(ctx: Context): MediaLibrary = AppServices.media(ctx)
    fun engine(ctx: Context): TransferEngine = AppServices.engine(ctx)
    fun web(ctx: Context): WebShareController = AppServices.web(ctx)
    fun saf(ctx: Context): SafStore = AppServices.saf(ctx)
}
