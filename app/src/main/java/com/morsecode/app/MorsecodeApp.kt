package com.morsecode.app

import android.app.Application
import android.content.Intent
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.transfer.TransferService
import com.morsecode.app.di.AppServices

class MorsecodeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
        Log.attach(this)
        // Crashes were invisible: the ring buffer died with the process and nothing was written
        // down, so "it crashed" could not be answered. Every uncaught exception is recorded - to
        // the crash file, to the log the viewer shows and to logcat - and then handed to the
        // platform's own handler, so behaviour is otherwise unchanged.
        installCrashHandler()
        AppServices.logs(this).restore()
        Log.info("App process started (pid ${android.os.Process.myPid()})")
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppServices.logs(this).recordCrash(throwable)
                AppServices.logs(this).flush()
            } catch (ignored: Throwable) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
