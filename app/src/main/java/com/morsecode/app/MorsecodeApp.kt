package com.morsecode.app

import android.app.Application
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.di.AppServices

class MorsecodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
        Log.attach(this)
    }
}
