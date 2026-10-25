package app.anylap.data

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
    val defaultDistanceM: Float,
) {
    RC("RC cars", "car", 5, 2.5f, 0.0012f, 2.5f),
    RUNNING("Running", "runner", 10, 15f, 0.004f, 5f),
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
) {
    val isCalibrated: Boolean get() = fingerprint.isNotEmpty()
}

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
