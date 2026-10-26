package app.anylap.vision

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.anylap.platform.Platform
import app.anylap.ui.components.EmptyState
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.theme.RT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.atan
import kotlin.math.min

// ====================================================================== composables

@Composable
actual fun rememberCameraController(): CameraController? {
    val context = LocalContext.current
    val controller = remember {
        try {
            AndroidCameraChoice.pick(context)?.let { AndroidCameraController(context.applicationContext, it) }
        } catch (e: Exception) {
            Log.e("AnyLapCamera", "camera unavailable", e)
            null
        }
    }
    DisposableEffect(controller) { onDispose { controller?.release() } }
    return controller
}

@Composable
actual fun CameraPreview(controller: CameraController, modifier: Modifier) {
    val c = controller as? AndroidCameraController ?: return
    val w = c.choice.previewSize.width
    val h = c.choice.previewSize.height
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFixedSize(w, h)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (width == w && height == h) c.attachSurface(holder.surface)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) = c.detachSurface()
                })
            }
        },
    )
}

@Composable
actual fun CameraPermissionGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().background(RT.Bg).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(Icons.Rounded.CameraAlt, "Camera needed", "AnyLap watches the finish line through the camera. Nothing leaves your phone.") {
                PrimaryButton("Allow camera", { launcher.launch(Manifest.permission.CAMERA) })
            }
            TextButton(onClick = onBack) { Text("Go back", color = RT.TextDim) }
        }
    }
}

actual fun cameraReport(): String {
    val ctx = Platform.context
    val choice = try { AndroidCameraChoice.pick(ctx) } catch (e: Exception) { null }
    val head = if (choice == null) "No usable camera found.\n\n" else
        "Live timing: back camera ${choice.cameraId}, ${choice.previewSize.width}x${choice.previewSize.height} @ ${choice.fps} fps\n" +
            "Analysis frames: ${choice.analysisSize.width}x${choice.analysisSize.height}\n" +
            "Field of view: ${choice.hfovRad?.let { Math.toDegrees(it.toDouble()).toInt().toString() + "°" } ?: "unknown"}\n\n"
    return head + CameraCapabilities.report(CameraCapabilities.probe(ctx))
}

// ====================================================================== setup choice

data class AndroidCameraChoice(
    val cameraId: String,
    val previewSize: Size,
    val analysisSize: Size,
    val fps: Int,
    val fpsRange: Range<Int>,
    val hfovRad: Float?,
    val sensorOrientation: Int,
) {
    companion object {
        /** Best setup with live analysis: highest normal-session fps at 1080p, ~1280-wide analysis frames. */
        fun pick(context: Context): AndroidCameraChoice? {
            val manager = context.getSystemService(CameraManager::class.java)
            val cameras = CameraCapabilities.probe(context)
            val cam = cameras.minByOrNull { it.id.toIntOrNull() ?: 99 } ?: return null
            val normal = cam.modes.filter { !it.highSpeed && it.fps <= 60 }
            val mode = normal.filter { it.height == 1080 }.maxByOrNull { it.fps }
                ?: normal.maxByOrNull { it.fps * 10_000 + it.height }
                ?: return null
            val chars = manager.getCameraCharacteristics(cam.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
            val wide = yuv.filter { it.width * 9 == it.height * 16 }
            val analysis = wide.firstOrNull { it.width == 1280 }
                ?: wide.filter { it.width <= 1280 }.maxByOrNull { it.width }
                ?: yuv.filter { it.width <= 1280 }.maxByOrNull { it.width }
                ?: return null
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val hfov = if (sensor != null && focal != null && focal > 0f) (2.0 * atan(sensor.width / (2.0 * focal))).toFloat() else null
            return AndroidCameraChoice(
                cameraId = cam.id,
                previewSize = Size(mode.width, mode.height),
                analysisSize = analysis,
                fps = mode.fps,
                fpsRange = mode.recordFpsRange,
                hfovRad = hfov,
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            )
        }
    }
}

// ====================================================================== controller

/** Camera2: preview + analysis frames (+ recording for replays). Screen is locked to landscape. */
class AndroidCameraController(private val context: Context, val choice: AndroidCameraChoice) : CameraController {

    private val camThread = HandlerThread("cam").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val camExecutor = Executor { camHandler.post(it) }
    private val anaThread = HandlerThread("analysis").apply { start() }
    private val anaHandler = Handler(anaThread.looper)
    private val manager = context.getSystemService(CameraManager::class.java)

    override val setup = CameraSetup(
        previewWidth = choice.previewSize.width,
        previewHeight = choice.previewSize.height,
        fps = choice.fps,
        hfovRad = choice.hfovRad,
        description = "${choice.previewSize.height}p @ ${choice.fps} fps",
    )

    private val _stats = MutableStateFlow(CameraStats())
    override val stats: StateFlow<CameraStats> = _stats

    @Volatile private var listener: ((AnalysisFrame) -> Unit)? = null

    private val timestampIsRealtime: Boolean = try {
        manager.getCameraCharacteristics(choice.cameraId).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    } catch (e: Exception) {
        false
    }
    private var clockOffsetNs = Long.MAX_VALUE

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    /** Bumped on every configure(): callbacks from an older, replaced session are ignored. */
    private var sessionGen = 0
    private var previewSurface: Surface? = null
    private val reader = ImageReader.newInstance(choice.analysisSize.width, choice.analysisSize.height, ImageFormat.YUV_420_888, 3)
    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var recordingFile: File? = null
    @Volatile private var aeLocked = false
    @Volatile private var photoRequest: Triple<FloatArray, Int, (ByteArray?) -> Unit>? = null

    /** 0 or 180: analysis image rotation relative to the landscape screen. */
    private val imageRotation: Int = ((choice.sensorOrientation - 90) + 360) % 360
    private val rotate180 = imageRotation == 180

    // reusable analysis buffers
    private var procW = FrameAnalyzer.PROC_W
    private var procH = 0
    private var yS = ByteArray(0)
    private var uS = ByteArray(0)
    private var vS = ByteArray(0)
    private val fpsTimes = ArrayDeque<Long>()

    init {
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val ts = toMonotonic(img.timestamp)
                updateFps(ts)
                photoRequest?.let { (crop, size, cb) ->
                    photoRequest = null
                    cb(photo(img, crop, size))
                }
                listener?.let { l -> l(toFrame(img, ts)) }
            } catch (e: Exception) {
                Log.e(TAG, "analysis failed", e)
            } finally {
                img.close()
            }
        }, anaHandler)
    }

    override fun setFrameListener(listener: ((AnalysisFrame) -> Unit)?) {
        this.listener = listener
    }

    private fun toMonotonic(sensorTs: Long): Long {
        if (timestampIsRealtime) return sensorTs
        val off = SystemClock.elapsedRealtimeNanos() - sensorTs
        if (off < clockOffsetNs) clockOffsetNs = off
        return sensorTs + clockOffsetNs
    }

    private fun updateFps(ts: Long) {
        fpsTimes.addLast(ts)
        while (fpsTimes.size > 2 && ts - fpsTimes.first() > 1_000_000_000L) fpsTimes.removeFirst()
        if (fpsTimes.size >= 2 && fpsTimes.size % 10 == 0) {
            _stats.value = _stats.value.copy(fps = (fpsTimes.size - 1) * 1e9 / (fpsTimes.last() - fpsTimes.first()))
        }
    }

    // ------------------------------------------------------------------ frames

    private fun toFrame(img: Image, ts: Long): AnalysisFrame {
        val w = img.width
        val h = img.height
        val ph = (procW.toLong() * h / w).toInt().coerceAtLeast(1)
        if (ph != procH) {
            procH = ph
            yS = ByteArray(procW * procH); uS = ByteArray(procW * procH); vS = ByteArray(procW * procH)
        }
        val planes = img.planes
        val yBuf = planes[0].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uBuf = planes[1].buffer
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vBuf = planes[2].buffer
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        val n = procW * procH
        var i = 0
        for (py in 0 until procH) {
            val sy = py * h / procH
            val yBase = sy * yRow
            val uBase = (sy / 2) * uRow
            val vBase = (sy / 2) * vRow
            for (px in 0 until procW) {
                val sx = px * w / procW
                val o = if (rotate180) n - 1 - i else i
                yS[o] = yBuf.get(yBase + sx * yPix)
                uS[o] = uBuf.get(uBase + (sx / 2) * uPix)
                vS[o] = vBuf.get(vBase + (sx / 2) * vPix)
                i++
            }
        }
        val yPlane = planes[0]
        return AnalysisFrame(
            timestampNs = ts,
            width = procW,
            height = procH,
            y = yS, u = uS, v = vS,
            fullWidth = w,
            fullHeight = h,
            lumaCrop = { x0, y0, cw, ch -> cropLuma(yPlane, w, h, x0, y0, cw, ch) },
        )
    }

    private fun cropLuma(plane: Image.Plane, w: Int, h: Int, x0: Int, y0: Int, cw: Int, ch: Int): ByteArray? {
        if (cw <= 0 || ch <= 0) return null
        // Screen coords → image coords when the image is upside down.
        val ix0 = if (rotate180) w - x0 - cw else x0
        val iy0 = if (rotate180) h - y0 - ch else y0
        if (ix0 < 0 || iy0 < 0 || ix0 + cw > w || iy0 + ch > h) return null
        val buf = plane.buffer.duplicate()
        val stride = plane.rowStride
        val out = ByteArray(cw * ch)
        for (r in 0 until ch) {
            buf.position((iy0 + r) * stride + ix0)
            buf.get(out, r * cw, cw)
        }
        if (rotate180) out.reverse()
        return out
    }

    // ------------------------------------------------------------------ lifecycle

    fun attachSurface(surface: Surface) {
        camHandler.post {
            // surfaceChanged can fire more than once for the same surface: don't rebuild a live session.
            if (previewSurface === surface && device != null && session != null) return@post
            previewSurface = surface
            if (device == null) open() else configure()
        }
    }

    fun detachSurface() {
        val latch = CountDownLatch(1)
        val posted = camHandler.post {
            try {
                stopRecordingInternal()
                closeInternal()
                previewSurface = null
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(2, TimeUnit.SECONDS)
    }

    override fun release() {
        listener = null
        detachSurface()
        camThread.quitSafely()
        anaHandler.post { reader.close() }
        anaThread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        try {
            manager.openCamera(choice.cameraId, camExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (previewSurface == null) {
                        camera.close(); return
                    }
                    device = camera
                    configure()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device == camera) device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device == camera) device = null
                    postError("Camera error $error – is another app using the camera?")
                }
            })
        } catch (e: Exception) {
            postError("Could not open camera: ${e.message}")
        }
    }

    private fun closeInternal() {
        sessionGen++
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
    }

    private fun configure(onReady: (() -> Unit)? = null) {
        val camera = device ?: return
        val preview = previewSurface ?: return
        val outputs = listOfNotNull(preview, reader.surface, recorderSurface).map { OutputConfiguration(it) }
        try { session?.stopRepeating() } catch (_: Exception) {}
        // Creating a new session closes the old one; never send requests to it again.
        session = null
        val gen = ++sessionGen
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, camExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != sessionGen) {
                        // A newer configure() is on its way; this one is already stale.
                        try { s.close() } catch (_: Exception) {}
                        return
                    }
                    session = s
                    repeat()
                    onReady?.invoke()
                }

                override fun onClosed(s: CameraCaptureSession) {
                    if (session === s) session = null
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != sessionGen) return
                    if (recorderSurface != null) {
                        postError("This phone can't record video while timing – replays are off for this session.")
                        cleanupRecorder()
                        configure()
                    } else {
                        postError("The camera refused this setup.")
                    }
                }
            },
        )
        try {
            camera.createCaptureSession(cfg)
        } catch (e: Exception) {
            postError("Could not start camera: ${e.message}")
        }
    }

    private fun repeat() {
        val camera = device ?: return
        val s = session ?: return
        try {
            val b = camera.createCaptureRequest(
                if (recorderSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW,
            )
            previewSurface?.let { b.addTarget(it) }
            b.addTarget(reader.surface)
            recorderSurface?.let { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, choice.fpsRange)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            val manual = if (aeLocked) manualExposure() else null
            if (manual != null) {
                // Shutter policy: a short shutter keeps a racer sharp (at 20 km/h, 1/120 s smears it
                // by 4.6 cm, 1/500 s by 1 cm) – trade ISO for it as far as the sensor allows, keeping
                // the brightness auto-exposure had chosen.
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manual.first)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, manual.second)
            } else {
                b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            }
            b.set(CaptureRequest.CONTROL_AWB_LOCK, aeLocked)
            s.setRepeatingRequest(b.build(), resultCallback, camHandler)
            clearError()
        } catch (e: IllegalStateException) {
            // The session was closed under us (a new one is being set up): not the user's problem.
            Log.w(TAG, "repeat on closed session", e)
            if (session === s) session = null
        } catch (e: Exception) {
            postError("Camera preview failed: ${e.message}")
        }
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        private var last = 0L
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            if (!aeLocked && exp > 0 && iso > 0) { autoExposureNs = exp; autoIso = iso }
            val now = SystemClock.elapsedRealtime()
            if (now - last < 400) return
            last = now
            _stats.value = _stats.value.copy(exposureNs = exp, iso = iso)
        }
    }

    /** What auto-exposure last chose, kept while unlocked: the brightness a manual lock must match. */
    @Volatile private var autoExposureNs = 0L
    @Volatile private var autoIso = 0

    /**
     * Manual (exposure ns, ISO) for a locked session: the same brightness as auto-exposure's last
     * pick, but with the shutter no longer than [TARGET_EXPOSURE_NS] when the sensor's ISO range
     * allows (ISO goes up as the shutter comes down). Null when the sensor has no manual control or
     * nothing was measured yet – then the plain AE lock is used.
     */
    private fun manualExposure(): Pair<Long, Int>? {
        val exp = autoExposureNs; val iso = autoIso
        if (exp <= 0L || iso <= 0) return null
        val chars = try { manager.getCameraCharacteristics(choice.cameraId) } catch (e: Exception) { return null }
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in caps) return null
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        if (exp <= TARGET_EXPOSURE_NS) return exp.coerceIn(expRange.lower, expRange.upper) to iso.coerceIn(isoRange.lower, isoRange.upper)
        // same light: exp × iso = const. ISO is capped to limit grain; then the shutter stays as
        // long as it has to.
        val maxIso = min(isoRange.upper, MAX_ISO)
        var newIso = (iso.toDouble() * exp / TARGET_EXPOSURE_NS).toLong()
        var newExp = TARGET_EXPOSURE_NS
        if (newIso > maxIso) { newExp = exp * iso / maxIso; newIso = maxIso.toLong() }
        return newExp.coerceIn(expRange.lower, expRange.upper) to newIso.toInt().coerceIn(isoRange.lower, isoRange.upper)
    }

    override fun setExposureLock(locked: Boolean) {
        camHandler.post {
            aeLocked = locked
            repeat()
        }
    }

    /** Errors are shown until the camera works again. */
    private fun clearError() {
        if (_stats.value.error != null) _stats.value = _stats.value.copy(error = null)
    }

    private fun postError(msg: String) {
        Log.e(TAG, msg)
        _stats.value = _stats.value.copy(error = msg)
    }

    // ------------------------------------------------------------------ recording

    override fun startRecording(path: String, onStarted: (Long) -> Unit) {
        camHandler.post {
            if (recorder != null || device == null) return@post
            val file = File(path)
            try {
                val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setOutputFile(file)
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                r.setVideoSize(choice.previewSize.width, choice.previewSize.height)
                r.setVideoFrameRate(choice.fps)
                val bitrate = (choice.previewSize.width.toLong() * choice.previewSize.height * choice.fps * 0.15)
                    .toLong().coerceIn(6_000_000L, 60_000_000L).toInt()
                r.setVideoEncodingBitRate(bitrate)
                r.setOrientationHint(imageRotation)
                r.prepare()
                recorder = r
                recorderSurface = r.surface
                recordingFile = file
                configure {
                    try {
                        r.start()
                        onStarted(SystemClock.elapsedRealtimeNanos())
                    } catch (e: Exception) {
                        postError("Recording failed to start: ${e.message}")
                        cleanupRecorder()
                        configure()
                    }
                }
            } catch (e: Exception) {
                postError("Recording unavailable: ${e.message}")
                cleanupRecorder()
            }
        }
    }

    override fun stopRecording(onDone: (String?) -> Unit) {
        val posted = camHandler.post {
            val f = stopRecordingInternal()
            configure()
            onDone(f?.absolutePath)
        }
        if (!posted) onDone(null)
    }

    private fun stopRecordingInternal(): File? {
        val r = recorder ?: return null
        try { session?.stopRepeating() } catch (_: Exception) {}
        val ok = try {
            r.stop(); true
        } catch (e: Exception) {
            false
        }
        val f = recordingFile
        cleanupRecorder()
        if (!ok || f == null || f.length() == 0L) {
            f?.delete(); return null
        }
        return f
    }

    private fun cleanupRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        recorderSurface = null
        recordingFile = null
    }

    // ------------------------------------------------------------------ photo

    override fun capturePhoto(crop: FloatArray, sizePx: Int, onResult: (ByteArray?) -> Unit) {
        photoRequest = Triple(crop, sizePx, onResult)
    }

    private fun photo(img: Image, crop: FloatArray, sizePx: Int): ByteArray? = try {
        val nv21 = toNv21(img)
        val yuv = YuvImage(nv21, ImageFormat.NV21, img.width, img.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, img.width, img.height), 92, out)
        val bytes = out.toByteArray()
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (rotate180) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(180f) }, true)
        val l = (crop[0] * bmp.width).toInt()
        val t = (crop[1] * bmp.height).toInt()
        val cw = ((crop[2] - crop[0]) * bmp.width).toInt()
        val ch = ((crop[3] - crop[1]) * bmp.height).toInt()
        val side = minOf(cw, ch)
        val square = Bitmap.createBitmap(bmp, l + (cw - side) / 2, t + (ch - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    } catch (e: Exception) {
        Log.e(TAG, "photo failed", e)
        null
    }

    private fun toNv21(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yBuf = yP.buffer
        var o = 0
        for (row in 0 until h) {
            val base = row * yP.rowStride
            for (col in 0 until w) out[o++] = yBuf.get(base + col * yP.pixelStride)
        }
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[o++] = vBuf.get(row * vP.rowStride + col * vP.pixelStride)
                out[o++] = uBuf.get(row * uP.rowStride + col * uP.pixelStride)
            }
        }
        return out
    }

    companion object {
        /** Shutter the session aims for when locking exposure: 1/500 s. */
        const val TARGET_EXPOSURE_NS = 2_000_000L
        /** ISO ceiling for the shutter policy (grain beyond this hurts more than blur). */
        const val MAX_ISO = 3200

        private const val TAG = "AnyLapCamera"
    }
}
