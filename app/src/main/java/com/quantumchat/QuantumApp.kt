package com.quantumchat

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import timber.log.Timber

@HiltAndroidApp
class QuantumApp : Application() {

    override fun attachBaseContext(base: Context) {
        // Register Bouncy Castle Security Provider as early as possible
        if (Security.getProvider("BC") == null || Security.getProvider("BC")?.javaClass?.name != "org.bouncycastle.jce.provider.BouncyCastleProvider") {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        
        // Double check Bouncy Castle Security Provider registration
        if (Security.getProvider("BC") == null || Security.getProvider("BC")?.javaClass?.name != "org.bouncycastle.jce.provider.BouncyCastleProvider") {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
        
        // Plant Timber DebugTree for developer logging
        Timber.plant(Timber.DebugTree())
        Timber.i("QuantumApp has been initialized successfully. Logging enabled.")
    }
}
