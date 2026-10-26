package com.example.sunwakeup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.service.WindDownService
import com.example.sunwakeup.ui.MainScreen
import com.example.sunwakeup.ui.SunTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A session the system cut short: pick it up again rather than leave the phone dimmed.
        if (Repository.session != null && !WindDownService.running.value) {
            WindDownService.start(this, skipEvening = false)
        }
        enableEdgeToEdge()
        setContent { SunTheme { MainScreen() } }
    }
}
