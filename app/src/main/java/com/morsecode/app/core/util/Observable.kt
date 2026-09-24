package com.morsecode.app.core.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** The process-wide main looper, used to marshal state changes onto the UI thread. */
private val MAIN = Handler(Looper.getMainLooper())

private fun isMain(): Boolean = Looper.myLooper() === Looper.getMainLooper()

/**
 * Tiny observable primitives.
 *
 * The spec describes the engine as exposing `StateFlow`s. This build ships without
 * kotlinx-coroutines (the offline toolchain cannot resolve external Maven artifacts), so
 * [State]/[Event] provide the same publish/subscribe contract with the same semantics:
 * a [State] always replays its current value to a new observer, an [Event] never does.
 */
class State<T>(initial: T) {

    @Volatile private var current: T = initial
    private val observers = CopyOnWriteArrayList<(T) -> Unit>()

    /** Coalesces worker-thread updates so the UI is rebuilt once per frame, not once per chunk. */
    @Volatile private var pending = false

    val value: T get() = current

    fun set(v: T) {
        current = v
        if (isMain()) notifyObservers(v) else postLatest()
    }

    private fun postLatest() {
        if (pending) return
        pending = true
        MAIN.post(object : Runnable {
            override fun run() {
                pending = false
                notifyObservers(current)
            }
        })
    }

    private fun notifyObservers(v: T) {
        for (o in observers) runCatching { o(v) }
    }

    fun update(block: (T) -> T) = set(block(current))

    /** Registers [observer] and immediately delivers the current value on the main thread. */
    fun observe(observer: (T) -> Unit): (T) -> Unit {
        observers.add(observer)
        if (isMain()) runCatching { observer(current) }
        else MAIN.post(object : Runnable {
            override fun run() {
                runCatching { observer(current) }
            }
        })
        return observer
    }

    fun unobserve(observer: (T) -> Unit) {
        observers.remove(observer)
    }

    fun clearObservers() = observers.clear()
}

class Event<T> {

    private val observers = CopyOnWriteArrayList<(T) -> Unit>()

    /** Events are never dropped - each one is delivered to observers on the main thread. */
    fun emit(v: T) {
        if (isMain()) {
            for (o in observers) runCatching { o(v) }
        } else {
            MAIN.post(object : Runnable {
                override fun run() {
                    for (o in observers) runCatching { o(v) }
                }
            })
        }
    }

    fun observe(observer: (T) -> Unit): (T) -> Unit {
        observers.add(observer)
        return observer
    }

    fun unobserve(observer: (T) -> Unit) {
        observers.remove(observer)
    }

    fun clearObservers() = observers.clear()
}

/** One shared background worker per engine so the UI thread never blocks on socket setup. */
object Workers {

    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "mc-worker").apply { isDaemon = true }
    }

    fun run(block: () -> Unit) {
        pool.execute {
            try {
                block()
            } catch (ignored: Throwable) {
                // a worker must never take the process down
            }
        }
    }

    fun runNamed(name: String, block: () -> Unit) {
        pool.execute {
            val old = Thread.currentThread().name
            try {
                Thread.currentThread().name = name
                block()
            } catch (ignored: Throwable) {
            } finally {
                Thread.currentThread().name = old
            }
        }
    }
}
