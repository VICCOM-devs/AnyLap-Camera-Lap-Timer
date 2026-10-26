package app.anylap.data

import app.anylap.vision.Fingerprint
import kotlinx.serialization.Serializable

@Serializable
enum class Sport(
    val title: String,
    val unitName: String,
    val maxRacers: Int,
    /** Ignore a second crossing by the same racer within this many seconds. */
    val defaultMinLapSec: Float,
    /** Smallest moving blob we care about, as a fraction of the analysed frame area. */
    val minBlobAreaFrac: Float,
    /** Biggest blob that can still be one racer (bigger = a lighting change, a person, the camera moving). */
    val maxBlobAreaFrac: Float,
    val defaultDistanceM: Float,
) {
    RC("RC cars", "car", 5, 2.5f, 0.0012f, 0.12f, 2.5f),
    RUNNING("Running", "runner", 10, 15f, 0.004f, 0.6f, 5f),
}

@Serializable
data class Racer(
    val id: String,
    val name: String,
    val sport: Sport,
    val number: Int,
    /** ARGB colour used in charts and as avatar background. */
    val color: Long,
    val photoPath: String? = null,
    /** Appearance fingerprint (colour histogram). Empty = not calibrated. */
    val fingerprint: List<Float> = emptyList(),
    val fingerprintSamples: Int = 0,
    /** ArUco marker id on the printed bib (running). */
    val markerId: Int? = null,
    val createdAt: Long = 0,
    /** Set when this racer was added from another account's share code. */
    val ownerUserId: String? = null,
    val ownerName: String? = null,
    /**
     * What the fingerprint was learned from: one entry per photo / pass, each with its own
     * fingerprint and a thumbnail, so bad ones (a crash, someone's shoe) can be removed.
     * Local only – never synced, never copied into sessions.
     */
    val calibration: List<CalibrationSample> = emptyList(),
) {
    /** A fingerprint from the current engine version; older ones can't be matched and need redoing. */
    val isCalibrated: Boolean get() = fingerprint.size == Fingerprint.SIZE

    /** Calibrated with an older build (no samples, or an older fingerprint format): nothing to review, only reset. */
    val hasLegacyCalibration: Boolean get() = fingerprint.isNotEmpty() && (calibration.isEmpty() || fingerprint.size != Fingerprint.SIZE)

    /** (along, area) of this racer's calibration passes: size hints for splitting merged racers. */
    fun sizeHints(): List<FloatArray> = calibration.mapNotNull { s ->
        val a = s.area ?: return@mapNotNull null
        val p = s.along ?: return@mapNotNull null
        if (s.kind == SampleKind.PHOTO || a <= 0) null else floatArrayOf(p, a.toFloat())
    }

    fun samplesOf(kind: SampleKind): List<CalibrationSample> = calibration.filter { it.kind == kind }
}

@Serializable
enum class SampleKind { PHOTO, PASS_SLOW, PASS_FAST }

@Serializable
data class CalibrationSample(
    val id: String,
    val kind: SampleKind,
    /** Photos: which side ("left", "right", "front", "back"). Passes: empty. */
    val view: String = "",
    val fingerprint: List<Float>,
    /** Thumbnail (photo JPEG or BMP crop of the pass); deleted together with the sample. */
    val imagePath: String? = null,
    val createdAt: Long = 0,
    val speedKmh: Float? = null,
    /** +1 / -1 crossing direction for passes, 0 for photos. */
    val direction: Int = 0,
    /** Passes: how much the crossing looked like the racer's other samples (0..1), for tuning. */
    val match: Float? = null,
    /** Passes: blob area (analysis px) and position along the line (0..1) – how big one racer looks there. */
    val area: Int? = null,
    val along: Float? = null,
)

@Serializable
enum class RaceMode(val title: String, val short: String) {
    PRACTICE("Practice", "Practice"),
    LAPS("Race to laps", "Laps"),
    TIME("Timed race", "Timed"),
}

@Serializable
enum class Direction { BOTH, FORWARD, REVERSE }

/** Finish line, normalised 0..1 in the landscape camera image. */
@Serializable
data class LineSpec(
    val x1: Float = 0.5f,
    val y1: Float = 0.1f,
    val x2: Float = 0.5f,
    val y2: Float = 0.9f,
)

@Serializable
data class SessionConfig(
    val sport: Sport = Sport.RC,
    val mode: RaceMode = RaceMode.PRACTICE,
    val targetLaps: Int = 10,
    val timeLimitSec: Int = 300,
    val racerIds: List<String> = emptyList(),
    val cameraDistanceM: Float = sport.defaultDistanceM,
    /** Optional, enables pace (min/km) and distance stats. */
    val lapLengthM: Float? = null,
    val minLapSec: Float = sport.defaultMinLapSec,
    val direction: Direction = Direction.BOTH,
    val line: LineSpec = LineSpec(),
)

@Serializable
enum class LapSource { AUTO, MARKER, MANUAL, ASSIGNED }

@Serializable
data class Lap(
    val racerId: String,
    /** 1-based lap number for this racer. */
    val number: Int,
    /** Lap duration. */
    val timeMs: Long,
    /** When the lap ended, measured from session start. */
    val atMs: Long,
    val speedKmh: Float? = null,
    val source: LapSource = LapSource.AUTO,
    val confidence: Float = 1f,
)

/** Why a crossing is in the "Check these" tray. */
@Serializable
enum class ReviewKind(val title: String) {
    /** Nobody matched well enough; the app counted it for no one. */
    UNKNOWN("Unknown car"),
    /** Named, but the runner-up was close. */
    LOW_MARGIN("Close call"),
    /** Two racers were separated from one blob at the line. */
    SPLIT("Cars touching"),
    /** Turned down as not looking like any racer (a shadow, a hand, a dog). */
    REJECTED("Not a racer?"),
    /** Passed the check line beyond the finish without being counted there – a possible missed lap. */
    MISSED("Missed?"),
}

@Serializable
data class ReviewCandidate(val racerId: String, val score: Float)

/**
 * A crossing the camera wasn't sure about, with its picture, for a human verdict after (or during)
 * the race. Resolving one re-derives the laps from [Session.crossings].
 */
@Serializable
data class ReviewItem(
    val id: String,
    val atMs: Long,
    val kind: ReviewKind,
    /** BMP crop of what crossed; local only, deleted with the session. */
    val imagePath: String? = null,
    val candidates: List<ReviewCandidate> = emptyList(),
    /** What the app counted it as (racer id), or null for nobody. */
    val decided: String? = null,
    /** The human's answer: a racer id, "" for "not a car", null while unanswered. */
    val resolvedTo: String? = null,
    val speedKmh: Float? = null,
    val direction: Int = 0,
)

/** One counted crossing, kept so laps can be rebuilt after a review. */
@Serializable
data class CrossRec(val atMs: Long, val speedKmh: Float? = null, val source: LapSource = LapSource.AUTO, val confidence: Float = 1f)

@Serializable
data class Session(
    val id: String,
    val startedAt: Long,
    val config: SessionConfig,
    val racers: List<Racer>,
    val laps: List<Lap>,
    val durationMs: Long,
    /** Racer ids in finishing order (races) or by best lap (practice). */
    val order: List<String>,
    val videoPath: String? = null,
    /** Position in the video file (ms) that corresponds to session time 0. */
    val videoOffsetMs: Long = 0,
    val unassignedCrossings: Int = 0,
    /** Counted crossings per racer (session ms), the source [laps] are derived from. */
    val crossings: Map<String, List<CrossRec>> = emptyMap(),
    /** Crossings for a human to check, with pictures. */
    val reviews: List<ReviewItem> = emptyList(),
    /** Set when the session was timed on someone else's phone and synced here. */
    val timedByUserId: String? = null,
    val timedByName: String? = null,
) {
    val title: String
        get() = when (config.mode) {
            RaceMode.PRACTICE -> "${config.sport.title} practice"
            RaceMode.LAPS -> "${config.targetLaps}-lap race"
            RaceMode.TIME -> "${config.timeLimitSec / 60}-min race"
        }

    fun racer(id: String): Racer? = racers.firstOrNull { it.id == id }
    fun lapsOf(id: String): List<Lap> = laps.filter { it.racerId == id }.sortedBy { it.number }
}

@Serializable
data class AppSettings(
    val speedUnitKmh: Boolean = true,
    val lastConfig: SessionConfig = SessionConfig(),
    val announceLaps: Boolean = true,
    /** Pre-release only: Pro switched on for testing (no store yet). */
    val testingProUnlocked: Boolean = false,
)

val RacerPalette: List<Long> = listOf(
    0xFFE10600, 0xFF2D7FF9, 0xFF1FC16B, 0xFFF5B700, 0xFFB45CFF,
    0xFFFF7A1A, 0xFF00C2D1, 0xFFFF4FA3, 0xFF9BE15D, 0xFFE8E8EA,
)
