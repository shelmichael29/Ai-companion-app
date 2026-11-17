package com.aicarcompanion.app

import android.app.Application

class CarCompanionApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash during startup is still reported. No-op without Firebase config.
        com.aicarcompanion.app.quality.CrashReporting.initialize(this)
        container = DefaultAppContainer(this)
        container.initializeAi()
        container.autoConnectAdapter()
    }
}
