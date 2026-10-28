package app.anylap

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.anylap.platform.Platform

class AnyLapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Platform.init(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Platform.attach(this)
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContent { App() }
        handleJoin(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleJoin(intent)
    }

    /** A scanned / tapped join link opens the race in the app. */
    private fun handleJoin(intent: android.content.Intent?) {
        val data = intent?.dataString ?: return
        app.anylap.ui.session.liveCodeFrom(data)?.let { AppGraph.state.pendingJoin.value = it }
    }

    override fun onResume() {
        super.onResume()
        Platform.attach(this)
    }
}
