package com.aicarcompanion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicarcompanion.app.ui.CarCompanionApp
import com.aicarcompanion.app.ui.theme.CarCompanionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CarCompanionApplication).container
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> container.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> container.onAppBackgrounded()
                else -> Unit
            }
        })
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            CarCompanionTheme(themeMode = settings.themeMode, reduceMotion = settings.reduceMotion, colorStyle = settings.colorStyle) {
                CarCompanionApp()
            }
        }
    }
}
