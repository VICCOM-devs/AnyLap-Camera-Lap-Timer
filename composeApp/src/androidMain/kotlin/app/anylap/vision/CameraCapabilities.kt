package app.anylap.vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size

/**
 * One way of running the camera: a resolution + frame rate, either through a normal
 * capture session or Android's "constrained high-speed" session (the 120/240 fps path).
 */
data class CaptureMode(
    val cameraId: String,
    val cameraLabel: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val highSpeed: Boolean,
    /** Fixed range used while recording, e.g. [120,120]. */
    val recordFpsRange: Range<Int>,
    /** Range used for preview only. High-speed sessions often prefer e.g. [30,120]. */
    val previewFpsRange: Range<Int>,
    /** True if the app can set shutter speed / ISO itself in this mode. */
    val manualShutter: Boolean,
    /** Fastest and slowest shutter allowed in this mode, in nanoseconds. */
    val exposureRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
) {
    val label: String
        get() = "${height}p @ $fps fps" + if (highSpeed) "  (high-speed)" else ""

    val key: String get() = "$cameraId-$width-$height-$fps-$highSpeed"
}

data class CameraInfo(
    val id: String,
    val label: String,
    val hardwareLevel: String,
    val manualSensor: Boolean,
    val highSpeedCapable: Boolean,
    val exposureRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
    val focalLengths: List<Float>,
    val aeFpsRanges: List<Range<Int>>,
    val highSpeedTable: List<Pair<Size, List<Range<Int>>>>,
    val modes: List<CaptureMode>,
)

data class Recommendation(
    /** Best overall: highest frame rate at 720p or better. */
    val best: CaptureMode?,
    /** Best mode where the app controls the shutter (sharper indoors). Null if same as best or none. */
    val bestManualShutter: CaptureMode?,
)

object CameraCapabilities {

    fun probe(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(CameraManager::class.java)
        val result = mutableListOf<CameraInfo>()
        for (id in manager.cameraIdList) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                continue
            }
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            // Only back cameras matter for filming a track.
            if (facing != CameraMetadata.LENS_FACING_BACK) continue
            result += describe(id, chars)
        }
        return result
    }

    private fun describe(id: String, chars: CameraCharacteristics): CameraInfo {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val label = "Back camera $id" + (focal.firstOrNull()?.let { " (%.1f mm)".format(it) } ?: "")
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val highSpeedCapable =
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in caps
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val aeRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
            ?: emptyList()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val modes = mutableListOf<CaptureMode>()
        val highSpeedTable = mutableListOf<Pair<Size, List<Range<Int>>>>()

        if (map != null) {
            // ---- Normal sessions (30 / 60 fps, sometimes more) ----
            val recorderSizes = map.getOutputSizes(MediaRecorder::class.java)?.toSet() ?: emptySet()
            val wanted = listOf(Size(1920, 1080), Size(1280, 720))
            val uppers = aeRanges.map { it.upper }.filter { it >= 30 }.toSortedSet()
            for (size in wanted) {
                if (size !in recorderSizes) continue
                val minDuration = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                val maxFps = if (minDuration > 0) (1_000_000_000.0 / minDuration).toInt() + 1 else 30
                for (fps in uppers) {
                    if (fps > maxFps) continue
                    val range = aeRanges.firstOrNull { it.lower == fps && it.upper == fps }
                        ?: aeRanges.filter { it.upper == fps }.maxByOrNull { it.lower }
                        ?: continue
                    val frameNs = 1_000_000_000L / fps
                    val expRange = exposureRange?.let {
                        Range(it.lower, minOf(it.upper, frameNs).coerceAtLeast(it.lower))
                    }
                    modes += CaptureMode(
                        cameraId = id, cameraLabel = label,
                        width = size.width, height = size.height, fps = fps,
                        highSpeed = false,
                        recordFpsRange = range, previewFpsRange = range,
                        manualShutter = manualSensor && exposureRange != null && isoRange != null,
                        exposureRangeNs = expRange, isoRange = isoRange,
                    )
                }
            }

            // ---- Constrained high-speed sessions (120 / 240 / 480 fps) ----
            if (highSpeedCapable) {
                val sizes = try { map.highSpeedVideoSizes?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
                for (size in sizes) {
                    val ranges = try {
                        map.getHighSpeedVideoFpsRangesFor(size).toList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    highSpeedTable += size to ranges
                    if (size.height < 480) continue
                    for (fixed in ranges.filter { it.lower == it.upper }) {
                        val fps = fixed.upper
                        val preview = ranges.filter { it.upper == fps && it.lower < fps }
                            .minByOrNull { it.lower } ?: fixed
                        modes += CaptureMode(
                            cameraId = id, cameraLabel = label,
                            width = size.width, height = size.height, fps = fps,
                            highSpeed = true,
                            recordFpsRange = fixed, previewFpsRange = preview,
                            // Android ignores manual shutter/ISO in high-speed sessions.
                            manualShutter = false,
                            exposureRangeNs = null, isoRange = null,
                        )
                    }
                }
            }
        }

        val sorted = modes
            .distinctBy { it.key }
            .sortedWith(compareByDescending<CaptureMode> { it.fps }.thenByDescending { it.height })

        return CameraInfo(
            id = id,
            label = label,
            hardwareLevel = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            manualSensor = manualSensor,
            highSpeedCapable = highSpeedCapable,
            exposureRangeNs = exposureRange,
            isoRange = isoRange,
            focalLengths = focal,
            aeFpsRanges = aeRanges,
            highSpeedTable = highSpeedTable,
            modes = sorted,
        )
    }

    fun recommend(cameras: List<CameraInfo>): Recommendation {
        val all = cameras.flatMap { it.modes }.filter { it.height >= 720 }
        val order = compareBy<CaptureMode> { it.fps }.thenBy { it.height }.thenBy { if (it.cameraId == "0") 1 else 0 }
        val best = all.maxWithOrNull(order)
        val manual = all.filter { it.manualShutter }.maxWithOrNull(order)
        return Recommendation(best, manual?.takeIf { it.key != best?.key })
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN ($level)"
    }

    fun shutterLabel(ns: Long): String {
        if (ns <= 0) return "—"
        val denom = 1_000_000_000.0 / ns
        return if (denom >= 1.5) "1/${denom.toInt()} s" else "%.2f s".format(ns / 1e9)
    }

    fun report(cameras: List<CameraInfo>): String = buildString {
        appendLine("AnyLap – camera report")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
        val rec = recommend(cameras)
        appendLine("Recommended: ${rec.best?.let { "${it.cameraLabel} ${it.label}" } ?: "none"}")
        rec.bestManualShutter?.let { appendLine("Best with manual shutter: ${it.cameraLabel} ${it.label}") }
        appendLine()
        for (c in cameras) {
            appendLine("== ${c.label} ==")
            appendLine("Hardware level: ${c.hardwareLevel}")
            appendLine("Manual shutter/ISO: ${if (c.manualSensor) "yes" else "no"}")
            appendLine("High-speed video: ${if (c.highSpeedCapable) "yes" else "no"}")
            c.exposureRangeNs?.let {
                appendLine("Shutter range: ${shutterLabel(it.lower)} … ${shutterLabel(it.upper)}")
            }
            c.isoRange?.let { appendLine("ISO range: ${it.lower} … ${it.upper}") }
            appendLine("AE fps ranges: ${c.aeFpsRanges.joinToString()}")
            if (c.highSpeedTable.isNotEmpty()) {
                appendLine("High-speed table:")
                for ((size, ranges) in c.highSpeedTable) {
                    appendLine("  ${size.width}x${size.height}: ${ranges.joinToString()}")
                }
            }
            appendLine("Usable modes:")
            for (m in c.modes) {
                appendLine("  ${m.width}x${m.height} @ ${m.fps}${if (m.highSpeed) " high-speed" else ""}${if (m.manualShutter) " manual-shutter" else ""}")
            }
            appendLine()
        }
    }
}
