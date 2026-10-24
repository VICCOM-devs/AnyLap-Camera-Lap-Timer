package app.anylap.vision

import app.anylap.data.Direction
import app.anylap.data.LineSpec
import app.anylap.data.Sport

/**
 * One camera frame prepared for analysis, already upright for the (landscape) screen.
 * [y], [u], [v] are a downscaled copy ([width] x [height]); [lumaCrop] reads full-resolution
 * brightness for marker reading and is only valid while the frame is being processed.
 */
class AnalysisFrame(
    /** Platform.monotonicNanos() clock. */
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val fullWidth: Int,
    val fullHeight: Int,
    val lumaCrop: (x0: Int, y0: Int, w: Int, h: Int) -> ByteArray?,
)

data class RacerRef(val id: String, val fingerprint: FloatArray?, val markerId: Int?)

data class AnalyzerConfig(
    val line: LineSpec = LineSpec(),
    val sport: Sport = Sport.RC,
    val racers: List<RacerRef> = emptyList(),
    val direction: Direction = Direction.BOTH,
    /** Metres per analysis pixel at the finish line; null = no speed. */
    val metersPerProcPx: Float? = null,
    val useMarkers: Boolean = false,
)

enum class IdSource { MARKER, APPEARANCE, ONLY_RACER, NONE }

class CrossingEvent(
    val timestampNs: Long,
    /** +1 or -1 depending on which side the racer came from. */
    val direction: Int,
    val racerId: String?,
    val source: IdSource,
    val confidence: Float,
    val speedKmh: Float?,
    val fingerprint: FloatArray?,
    /** Calibrated racers ranked by colour similarity (best first). */
    val candidates: List<Pair<String, Float>>,
    val markerSeen: Int?,
)

/** Normalised (0..1) box around something moving. */
data class OverlayBox(val l: Float, val t: Float, val r: Float, val b: Float, val nearLine: Boolean)

data class CameraStats(
    val fps: Double = 0.0,
    val exposureNs: Long = 0,
    val iso: Int = 0,
    val error: String? = null,
)

/** What the platform camera is running at. */
data class CameraSetup(
    val previewWidth: Int,
    val previewHeight: Int,
    val fps: Int,
    /** Horizontal field of view in radians, if known. */
    val hfovRad: Float?,
    val description: String,
)
