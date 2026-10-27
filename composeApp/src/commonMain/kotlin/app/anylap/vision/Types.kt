package app.anylap.vision

import app.anylap.data.Direction
import app.anylap.data.FinishEdge
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
    /** Top-down picture + true size from a calibration pass, for the fit of doubtful crossings. */
    val template: CarTemplate? = null,
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
    /**
     * Which part of a racer is timed: the track's centre crosses the line to *detect* a crossing,
     * and the time is moved to the front (or rear) edge by half the racer's length over its speed.
     */
    val finishEdge: FinishEdge = FinishEdge.CENTRE,
    /** Capture a top-down picture of the racer on each crossing (calibration passes). */
    val captureTemplates: Boolean = false,
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
    /** The track that crossed (matches [SnapBox.trackId] in other crossings' snapshots). */
    val trackId: Int = 0,
    /** When the racer's centre crossed (the [timestampNs] is the chosen [FinishEdge]). */
    val centreNs: Long = timestampNs,
    /** Blob size at the crossing ÷ one racer's expected size there (0 = no size model yet); ≥ 1.4 = more than one racer in it. */
    val sizeRatio: Float = 0f,
    /** Box (0..1 of the frame) at the crossing moment and velocity (frame widths/heights per second). */
    val box: OverlayBox? = null,
    val vx: Float = 0f,
    val vy: Float = 0f,
    /** The racer's outline where the calibration picture fitted: 4 corners x,y (0..1 of the frame), nose edge first. */
    val quad: FloatArray? = null,
    /** How the identity was decided by the calibration-picture fit (null = not fitted). */
    val fit: FitInfo? = null,
    /** Top-down picture of the racer from this crossing (calibration passes only, [AnalyzerConfig.captureTemplates]). */
    val template: CarTemplate? = null,
)

/** Result of fitting the racers' calibration pictures onto a crossing. */
class FitInfo(
    /** Racers by fit score (0..1, share of the picture explained), best first. */
    val scores: List<Pair<String, Float>>,
    /** How many racers the fit found in the blob. */
    val cars: Int,
    /** True when the fit changed the name the colour histogram gave. */
    val renamed: Boolean,
)

/**
 * Small RGB picture (ARGB pixels, row by row) of a crossing, with every racer that was in it:
 * [boxes] in picture coordinates 0..1, [line] = finish line x1,y1,x2,y2 in the same coordinates,
 * [frameNs] = time between this frame and the one before (for photo-finish ghosts).
 */
class Snapshot(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val boxes: List<SnapBox> = emptyList(),
    val line: FloatArray? = null,
    val timestampNs: Long = 0,
    val frameNs: Long = 0,
    /** Colour planes Y, U, V one after another (memory only, for [CardFit]); null when not kept. */
    val yuv: ByteArray? = null,
    /** Raw foreground (before clean-up), 1 = moving; memory only. */
    val fg: ByteArray? = null,
    /** Median luma of the frame (brightness of the light the pictures are compared under). */
    val lumaRef: Float = 0f,
    /** Where the picture was cut from: top-left corner and the whole frame's size (analysis px). */
    val x0: Int = 0,
    val y0: Int = 0,
    val frameW: Int = 0,
    val frameH: Int = 0,
)

/**
 * A racer's box in a [Snapshot]: [trackId] matches [CrossingEvent.trackId]; [vx], [vy] = its speed
 * in picture widths / heights per second (ghost positions between frames).
 */
class SnapBox(val trackId: Int, val l: Float, val t: Float, val r: Float, val b: Float, val vx: Float, val vy: Float)

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
