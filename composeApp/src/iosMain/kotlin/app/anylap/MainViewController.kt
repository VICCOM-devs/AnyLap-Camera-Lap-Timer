package app.anylap

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Called from Swift (iosApp) to host the shared Compose UI. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }

/** Read by the Swift AppDelegate to decide which orientations are allowed right now. */
object OrientationState {
    var landscape: Boolean = false
}
