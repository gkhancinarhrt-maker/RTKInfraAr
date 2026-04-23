package com.tusaga.rtkinfra

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class RTKInfraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Plant Timber debug tree only in debug builds
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
