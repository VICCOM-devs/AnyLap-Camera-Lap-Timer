package app.anylap.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import app.anylap.platform.Platform

/** Landscape, full-screen and screen kept on while this composable is shown (camera screens). */
@Composable
fun CameraScreenMode() {
    DisposableEffect(Unit) {
        Platform.setCameraMode(true)
        onDispose { Platform.setCameraMode(false) }
    }
}
