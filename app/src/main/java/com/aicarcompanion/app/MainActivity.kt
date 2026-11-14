package com.aicarcompanion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aicarcompanion.app.ui.CarCompanionApp
import com.aicarcompanion.app.ui.theme.CarCompanionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarCompanionTheme {
                CarCompanionApp()
            }
        }
    }
}
