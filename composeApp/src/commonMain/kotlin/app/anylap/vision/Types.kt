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

data class RacerRef(
    val id: String,
    val fingerprint: FloatArray?,
    val markerId: Int?,
    /** Per-sample fingerprints (sides, passes); the best-matching one also counts. */
    val views: List<FloatArray> = emptyList(),
)

data class AnalyzerConfig(
    val line: LineSpec = LineSpec(),
    val sport: Sport = Sport.RC,
    val racers: List<RacerRef> = emptyList(),
    val direction: Direction = Direction.BOTH,
    /** Metres per analysis pixel at the finish line; null = no speed. */
    val metersPerProcPx: Float? = null,
    val useMarkers: Boolean = false,
    /** Attach a small picture of what crossed to each event (calibration review). */
    val captureSnapshots: Boolean = false,
    /**
     * Appearance gate: a crossing must look at least this much like one of the racers (their
     * photos and passes) to be accepted; 0 = off. Only applies once every racer has samples.
     * Rejected crossings are still reported with [CrossingEvent.accepted] = false.
     */
    val minMatch: Float = 0f,
    /**
     * How big one racer looks at a point of the line: (along 0..1, blob area px) from calibration
     * passes ([CrossingEvent.along], [CrossingEvent.area]). Lets the analyzer split a blob that is
     * two racers merged (nose to tail, side by side) from the first lap on; it keeps learning
     * from identified crossings during the session.
     */
    val sizeHints: List<FloatArray> = emptyList(),
    /**
     * Exposure time of the camera (s), for motion-blur-aware colour sampling. Default = the worst
     * an indoor 60 fps session locks to; the app passes the real value once exposure is locked.
     */
    val exposureS: Float = 1f / 120f,
    /** Which part of a racer times a crossing: the nose touching the line (motorsport rule, default), its centre, or its tail clearing the line. */
    val timingEdge: TimingEdge = TimingEdge.FRONT,
)

/** Settings → Timing → "Finish counts when": the front touches the line / the whole car has passed. */
enum class TimingEdge { FRONT, CENTRE, REAR }

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
    /** Crop of the racer at the moment it crossed; only when [AnalyzerConfig.captureSnapshots]. */
    val snapshot: Snapshot? = null,
    /** Best colour similarity to any racer (0..1); 0 when nothing to compare with. */
    val match: Float = 0f,
    /** False when the appearance gate ([AnalyzerConfig.minMatch]) turned this crossing down. */
    val accepted: Boolean = true,
    /** Blob area (analysis px) and position along the line (0..1) at the crossing – size hints. */
    val area: Int = 0,
    val along: Float = 0f,
    /** True when the racer was split out of a merged blob at the crossing. */
    val split: Boolean = false,
    /**
     * True for a crossing *reconstructed* from the check line: the track passed a line 15 % of the
     * frame beyond the finish without having been counted there. Always [accepted] = false – it is
     * for the review tray, never for timing; [timestampNs] is the estimated finish-line time.
     */
    val rescue: Boolean = false,
    /** When the racer's centre crossed (the [timestampNs] is the chosen [TimingEdge]). */
    val centreNs: Long = timestampNs,
    /**
     * Blob size at the crossing ÷ one racer's expected size there (0 = no size model yet). Clearly
     * above 1 (≥ 1.5) means more than one racer was in the blob – one of them may be uncounted.
     */
    val sizeRatio: Float = 0f,
    /** The racer's box (0..1) in the frame where it crossed, and its velocity (frame widths/heights per second) – for Check-card overlays and photo-finish ghosts. */
    val box: OverlayBox? = null,
    val vx: Float = 0f,
    val vy: Float = 0f,
)

/** Small RGB picture (ARGB pixels, row by row). */
class Snapshot(val width: Int, val height: Int, val argb: IntArray)

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
