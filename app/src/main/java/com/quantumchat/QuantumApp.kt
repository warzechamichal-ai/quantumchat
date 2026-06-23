package com.quantumchat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class QuantumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Plant Timber DebugTree for developer logging
        Timber.plant(Timber.DebugTree())
        Timber.i("QuantumApp has been initialized successfully. Logging enabled.")
    }
}
