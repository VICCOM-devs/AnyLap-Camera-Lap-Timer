package app.anylap.vision

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The platform camera: preview, analysis frames, recording and photos. */
interface CameraController {
    val setup: CameraSetup
    val stats: StateFlow<CameraStats>

    /** Frames arrive on a background thread, one at a time, already upright for the landscape screen. */
    fun setFrameListener(listener: ((AnalysisFrame) -> Unit)?)

    /** Locks exposure and white balance (during a session) so brightness changes don't look like motion. */
    fun setExposureLock(locked: Boolean)

    /** [onStarted] gets the Platform.monotonicNanos() time of the first recorded frame. */
    fun startRecording(path: String, onStarted: (Long) -> Unit)

    /** [onDone] gets the saved file path, or null if nothing was saved. */
    fun stopRecording(onDone: (String?) -> Unit)

    /** JPEG of the normalised [crop] (l,t,r,b) from the next frame, scaled to [sizePx] square. */
    fun capturePhoto(crop: FloatArray, sizePx: Int, onResult: (ByteArray?) -> Unit)

    fun release()
}

/** Opens the best camera for live timing; null if unavailable. Released when it leaves composition. */
@Composable
expect fun rememberCameraController(): CameraController?

/** Live preview for [controller], filling [modifier] (keep it at the camera's aspect ratio). */
@Composable
expect fun CameraPreview(controller: CameraController, modifier: Modifier)

/** Shows [content] once the camera permission is granted; asks for it otherwise. */
@Composable
expect fun CameraPermissionGate(onBack: () -> Unit, content: @Composable () -> Unit)

/** Camera + shared analyzer: what the screens actually talk to. */
class VisionSession(val camera: CameraController) {
    private val _crossings = MutableSharedFlow<CrossingEvent>(extraBufferCapacity = 64)
    val crossings: SharedFlow<CrossingEvent> = _crossings
    private val _overlay = MutableStateFlow<List<OverlayBox>>(emptyList())
    val overlay: StateFlow<List<OverlayBox>> = _overlay

    val analyzer = FrameAnalyzer(
        onCrossing = { _crossings.tryEmit(it) },
        onOverlay = { _overlay.value = it },
    )

    init {
        camera.setFrameListener { analyzer.process(it) }
    }

    fun close() {
        camera.setFrameListener(null)
    }
}

/** Human-readable list of the phone's camera capabilities (for support / tuning). */
expect fun cameraReport(): String
