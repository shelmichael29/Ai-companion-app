package com.aicarcompanion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aicarcompanion.app.ui.CarCompanionApp
import com.aicarcompanion.app.ui.theme.CarCompanionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CarCompanionApplication).container
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) container.onAppBackgrounded()
        })
        setContent {
            CarCompanionTheme {
                CarCompanionApp()
            }
        }
    }
}
