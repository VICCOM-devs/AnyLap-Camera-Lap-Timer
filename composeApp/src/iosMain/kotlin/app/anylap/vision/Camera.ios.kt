@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.anylap.vision

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import app.anylap.platform.LayerHostView
import app.anylap.platform.Platform
import app.anylap.ui.components.EmptyState
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.theme.RT
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusUnknown
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceFormat
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureExposureModeLocked
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetInputPriority
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVCaptureWhiteBalanceModeContinuousAutoWhiteBalance
import platform.AVFoundation.AVCaptureWhiteBalanceModeLocked
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVFrameRateRange
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetWidthOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.numberWithInt
import platform.Foundation.numberWithUnsignedInt
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy
import kotlin.math.PI

// ====================================================================== composables

@Composable
actual fun rememberCameraController(): CameraController? {
    val controller = remember {
        try {
            IosCameraController.create()
        } catch (e: Throwable) {
            println("AnyLap: camera unavailable: $e")
            null
        }
    }
    DisposableEffect(controller) { onDispose { controller?.release() } }
    return controller
}

@Composable
actual fun CameraPreview(controller: CameraController, modifier: Modifier) {
    val c = controller as? IosCameraController ?: return
    UIKitView(
        factory = { LayerHostView(c.previewLayer) },
        modifier = modifier,
    )
}

@Composable
actual fun CameraPermissionGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    var granted by remember {
        mutableStateOf(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized)
    }
    var asked by remember { mutableStateOf(false) }
    fun ask() {
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { ok ->
            dispatch_async(dispatch_get_main_queue()) { granted = ok; asked = true }
        }
    }
    LaunchedEffect(Unit) { if (!granted) ask() }
    if (granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().background(RT.Bg).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                Icons.Rounded.CameraAlt,
                "Camera needed",
                if (asked) "Allow camera access for AnyLap in Settings → Privacy & Security → Camera."
                else "AnyLap watches the finish line through the camera. Nothing leaves your phone.",
            ) {
                PrimaryButton("Allow camera", { ask() })
            }
            TextButton(onClick = onBack) { Text("Go back", color = RT.TextDim) }
        }
    }
}

actual fun cameraReport(): String {
    val device = IosCameraController.backCamera() ?: return "No back camera found."
    val sb = StringBuilder("AnyLap – camera report (iOS)\n")
    val best = IosCameraController.bestFormat(device)
    if (best != null) sb.append("Live timing: ${best.width}x${best.height} @ ${best.fps} fps, field of view ${best.fovDeg.toInt()}°\n\n")
    sb.append("Formats (420f):\n")
    val seen = HashSet<String>()
    for (f in device.formats) {
        val fmt = f as? AVCaptureDeviceFormat ?: continue
        val info = IosCameraController.describe(fmt) ?: continue
        val key = "${info.width}x${info.height}@${info.fps}"
        if (seen.add(key)) sb.append("  $key\n")
    }
    return sb.toString()
}

// ====================================================================== controller

class IosCameraController private constructor(
    private val device: AVCaptureDevice,
    private val format: FormatInfo,
) : CameraController {

    data class FormatInfo(val format: AVCaptureDeviceFormat, val width: Int, val height: Int, val fps: Int, val fovDeg: Float)

    companion object {
        fun backCamera(): AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithDeviceType(
            AVCaptureDeviceTypeBuiltInWideAngleCamera, AVMediaTypeVideo, AVCaptureDevicePositionBack,
        )

        fun describe(f: AVCaptureDeviceFormat): FormatInfo? {
            val desc = f.formatDescription ?: return null
            if (CMFormatDescriptionGetMediaSubType(desc) != kCVPixelFormatType_420YpCbCr8BiPlanarFullRange) return null
            var w = 0
            var h = 0
            CMVideoFormatDescriptionGetDimensions(desc).useContents { w = width; h = height }
            var maxFps = 0.0
            for (r in f.videoSupportedFrameRateRanges) {
                val range = r as? AVFrameRateRange ?: continue
                if (range.maxFrameRate > maxFps) maxFps = range.maxFrameRate
            }
            return FormatInfo(f, w, h, maxFps.toInt(), f.videoFieldOfView)
        }

        /** Highest frame rate (up to 240) at 1280–1920 wide; ties go to the larger image. */
        fun bestFormat(device: AVCaptureDevice): FormatInfo? =
            device.formats.mapNotNull { (it as? AVCaptureDeviceFormat)?.let(::describe) }
                .filter { it.width in 1280..1920 && it.fps >= 30 }
                .maxWithOrNull(compareBy<FormatInfo> { minOf(it.fps, 240) }.thenBy { it.width })

        fun create(): IosCameraController? {
            val device = backCamera() ?: return null
            val format = bestFormat(device) ?: return null
            return IosCameraController(device, format)
        }
    }

    private val fps = minOf(format.fps, 240)

    override val setup = CameraSetup(
        previewWidth = format.width,
        previewHeight = format.height,
        fps = fps,
        hfovRad = (format.fovDeg * PI / 180.0).toFloat(),
        description = "${format.height}p @ $fps fps",
    )

    private val _stats = MutableStateFlow(CameraStats())
    override val stats: StateFlow<CameraStats> = _stats

    private val session = AVCaptureSession()
    val previewLayer: AVCaptureVideoPreviewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspect
    }
    private val output = AVCaptureVideoDataOutput()
    private val sessionQueue = dispatch_queue_create("anylap.session", null)
    private val frameQueue = dispatch_queue_create("anylap.frames", null)
    private val ciContext = CIContext()

    private var listener: ((AnalysisFrame) -> Unit)? = null
    private var photoRequest: Triple<FloatArray, Int, (ByteArray?) -> Unit>? = null

    // recording (touched on frameQueue)
    private var writer: AVAssetWriter? = null
    private var writerInput: AVAssetWriterInput? = null
    private var recordingPath: String? = null
    private var onRecordingStarted: ((Long) -> Unit)? = null

    // analysis buffers
    private val procW = FrameAnalyzer.PROC_W
    private var procH = 0
    private var yS = ByteArray(0)
    private var uS = ByteArray(0)
    private var vS = ByteArray(0)
    private val fpsTimes = ArrayDeque<Long>()

    private val delegate = FrameDelegate(this)

    init {
        dispatch_async(sessionQueue) { configureAndStart() }
    }

    private fun configureAndStart() {
        try {
            session.beginConfiguration()
            session.sessionPreset = AVCaptureSessionPresetInputPriority
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
            if (input == null || !session.canAddInput(input)) {
                postError("Could not open the camera.")
                session.commitConfiguration()
                return
            }
            session.addInput(input)
            output.videoSettings = mapOf<Any?, Any?>(
                "PixelFormatType" to NSNumber.numberWithUnsignedInt(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange),
            )
            output.alwaysDiscardsLateVideoFrames = true
            output.setSampleBufferDelegate(delegate, queue = frameQueue)
            if (session.canAddOutput(output)) session.addOutput(output)

            if (device.lockForConfiguration(null)) {
                device.activeFormat = format.format
                device.activeVideoMinFrameDuration = CMTimeMake(1, fps)
                device.activeVideoMaxFrameDuration = CMTimeMake(1, fps)
                if (device.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) device.focusMode = AVCaptureFocusModeContinuousAutoFocus
                device.unlockForConfiguration()
            }
            // Upright buffers for the landscape-right screen used by camera screens.
            (output.connectionWithMediaType(AVMediaTypeVideo) as AVCaptureConnection?)?.let { c ->
                if (c.isVideoOrientationSupported()) c.videoOrientation = AVCaptureVideoOrientationLandscapeRight
            }
            session.commitConfiguration()
            previewLayer.connection?.let { c ->
                if (c.isVideoOrientationSupported()) c.videoOrientation = AVCaptureVideoOrientationLandscapeRight
            }
            session.startRunning()
        } catch (e: Throwable) {
            postError("Camera failed: ${e.message}")
        }
    }

    private fun postError(msg: String) {
        _stats.value = _stats.value.copy(error = msg)
    }

    override fun setFrameListener(listener: ((AnalysisFrame) -> Unit)?) {
        this.listener = listener
    }

    override fun setExposureLock(locked: Boolean) {
        dispatch_async(sessionQueue) {
            if (device.lockForConfiguration(null)) {
                if (locked) {
                    if (device.isExposureModeSupported(AVCaptureExposureModeLocked)) device.exposureMode = AVCaptureExposureModeLocked
                    if (device.isWhiteBalanceModeSupported(AVCaptureWhiteBalanceModeLocked)) device.whiteBalanceMode = AVCaptureWhiteBalanceModeLocked
                } else {
                    if (device.isExposureModeSupported(AVCaptureExposureModeContinuousAutoExposure)) device.exposureMode = AVCaptureExposureModeContinuousAutoExposure
                    if (device.isWhiteBalanceModeSupported(AVCaptureWhiteBalanceModeContinuousAutoWhiteBalance)) device.whiteBalanceMode = AVCaptureWhiteBalanceModeContinuousAutoWhiteBalance
                }
                device.unlockForConfiguration()
            }
        }
    }

    override fun startRecording(path: String, onStarted: (Long) -> Unit) {
        dispatch_async(frameQueue) {
            if (writer != null) return@dispatch_async
            Platform.delete(path)
            val w = AVAssetWriter(uRL = NSURL.fileURLWithPath(path), fileType = AVFileTypeMPEG4, error = null)
            val bitrate = (format.width.toLong() * format.height * fps * 0.12).toLong().coerceIn(8_000_000L, 80_000_000L)
            val settings = mapOf<Any?, Any?>(
                AVVideoCodecKey to AVVideoCodecTypeH264,
                AVVideoWidthKey to NSNumber.numberWithInt(format.width),
                AVVideoHeightKey to NSNumber.numberWithInt(format.height),
                AVVideoCompressionPropertiesKey to mapOf<Any?, Any?>(
                    AVVideoAverageBitRateKey to NSNumber.numberWithInt(bitrate.toInt()),
                ),
            )
            val input = AVAssetWriterInput(mediaType = AVMediaTypeVideo, outputSettings = settings)
            input.expectsMediaDataInRealTime = true
            if (w.canAddInput(input)) w.addInput(input)
            writer = w
            writerInput = input
            recordingPath = path
            onRecordingStarted = onStarted
        }
    }

    override fun stopRecording(onDone: (String?) -> Unit) {
        dispatch_async(frameQueue) {
            val w = writer
            val input = writerInput
            val path = recordingPath
            writer = null
            writerInput = null
            recordingPath = null
            onRecordingStarted = null
            if (w == null || input == null || w.status != AVAssetWriterStatusWriting) {
                onDone(null)
                return@dispatch_async
            }
            input.markAsFinished()
            w.finishWritingWithCompletionHandler {
                onDone(if (path != null && Platform.exists(path)) path else null)
            }
        }
    }

    override fun capturePhoto(crop: FloatArray, sizePx: Int, onResult: (ByteArray?) -> Unit) {
        photoRequest = Triple(crop, sizePx, onResult)
    }

    override fun release() {
        listener = null
        output.setSampleBufferDelegate(null, queue = null)
        dispatch_async(sessionQueue) { session.stopRunning() }
    }

    // ------------------------------------------------------------------ frames (frameQueue)

    internal fun onFrame(sampleBuffer: CMSampleBufferRef) {
        val pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        val ts = (CMTimeGetSeconds(pts) * 1_000_000_000.0).toLong()
        updateFps(ts)

        // Recording
        val w = writer
        val input = writerInput
        if (w != null && input != null) {
            if (w.status == AVAssetWriterStatusUnknown) {
                if (w.startWriting()) {
                    w.startSessionAtSourceTime(pts)
                    onRecordingStarted?.invoke(ts)
                    onRecordingStarted = null
                }
            }
            if (w.status == AVAssetWriterStatusWriting && input.readyForMoreMediaData) input.appendSampleBuffer(sampleBuffer)
        }

        val pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) ?: return
        photoRequest?.let { (crop, size, cb) ->
            photoRequest = null
            cb(photo(pixelBuffer, crop, size))
        }
        val l = listener ?: return
        CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
        try {
            val fw = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0u).toInt()
            val fh = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0u).toInt()
            val yBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0u)?.reinterpret<ByteVar>() ?: return
            val uvBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1u)?.reinterpret<ByteVar>() ?: return
            val yStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0u).toInt()
            val uvStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1u).toInt()
            val ph = (procW.toLong() * fh / fw).toInt().coerceAtLeast(1)
            if (ph != procH) {
                procH = ph
                yS = ByteArray(procW * procH); uS = ByteArray(procW * procH); vS = ByteArray(procW * procH)
            }
            var i = 0
            for (py in 0 until procH) {
                val sy = py * fh / procH
                val yRow = sy * yStride
                val uvRow = (sy / 2) * uvStride
                for (px in 0 until procW) {
                    val sx = px * fw / procW
                    yS[i] = yBase[yRow + sx]
                    val uvi = uvRow + (sx / 2) * 2
                    uS[i] = uvBase[uvi]
                    vS[i] = uvBase[uvi + 1]
                    i++
                }
            }
            l(
                AnalysisFrame(
                    timestampNs = ts,
                    width = procW,
                    height = procH,
                    y = yS, u = uS, v = vS,
                    fullWidth = fw,
                    fullHeight = fh,
                    lumaCrop = { x0, y0, cw, ch -> cropLuma(yBase, yStride, fw, fh, x0, y0, cw, ch) },
                ),
            )
        } finally {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
        }
    }

    private fun cropLuma(base: CPointer<ByteVar>, stride: Int, fw: Int, fh: Int, x0: Int, y0: Int, cw: Int, ch: Int): ByteArray? {
        if (cw <= 0 || ch <= 0 || x0 < 0 || y0 < 0 || x0 + cw > fw || y0 + ch > fh) return null
        val out = ByteArray(cw * ch)
        out.usePinned { pinned ->
            for (r in 0 until ch) {
                memcpy(pinned.addressOf(r * cw), base + ((y0 + r) * stride + x0).toLong(), cw.toULong())
            }
        }
        return out
    }

    private fun updateFps(ts: Long) {
        fpsTimes.addLast(ts)
        while (fpsTimes.size > 2 && ts - fpsTimes.first() > 1_000_000_000L) fpsTimes.removeFirst()
        if (fpsTimes.size >= 2 && fpsTimes.size % 10 == 0) {
            val exposureNs = (CMTimeGetSeconds(device.exposureDuration) * 1e9).toLong()
            _stats.value = _stats.value.copy(
                fps = (fpsTimes.size - 1) * 1e9 / (fpsTimes.last() - fpsTimes.first()),
                exposureNs = exposureNs,
                iso = device.ISO.toInt(),
            )
        }
    }

    private fun photo(pixelBuffer: platform.CoreVideo.CVPixelBufferRef, crop: FloatArray, sizePx: Int): ByteArray? = try {
        val image = CIImage.imageWithCVPixelBuffer(pixelBuffer)
        var iw = 0.0
        var ih = 0.0
        image.extent.useContents { iw = size.width; ih = size.height }
        val cw = (crop[2] - crop[0]) * iw
        val chh = (crop[3] - crop[1]) * ih
        val side = minOf(cw, chh)
        val left = crop[0] * iw + (cw - side) / 2
        val topFromTop = crop[1] * ih + (chh - side) / 2
        // Core Image's origin is bottom-left.
        val rect = CGRectMake(left, ih - topFromTop - side, side, side)
        val scale = sizePx / side
        val cropped = image.imageByCroppingToRect(rect).imageByApplyingTransform(CGAffineTransformMakeScale(scale, scale))
        val cg = ciContext.createCGImage(cropped, fromRect = cropped.extent)
        if (cg == null) null else {
            val ui = UIImage.imageWithCGImage(cg)
            val data = UIImageJPEGRepresentation(ui, 0.88)
            if (data == null) null else {
                val out = ByteArray(data.length.toInt())
                out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
                out
            }
        }
    } catch (e: Throwable) {
        null
    }
}

/** AVFoundation frame callback → controller. */
private class FrameDelegate(private val owner: IosCameraController) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    @ObjCSignatureOverride
    override fun captureOutput(output: AVCaptureOutput, didOutputSampleBuffer: CMSampleBufferRef?, fromConnection: AVCaptureConnection) {
        val sb = didOutputSampleBuffer ?: return
        owner.onFrame(sb)
    }
}
