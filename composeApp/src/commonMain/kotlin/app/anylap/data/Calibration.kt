package app.anylap.data

import app.anylap.i18n.tr
import app.anylap.vision.Fingerprint

/** How much each kind of sample counts in the racer's combined fingerprint. */
private fun SampleKind.weight(): Float = when (this) {
    // Photos include a bit of the ground around the car, so they count half.
    SampleKind.PHOTO -> 0.5f
    SampleKind.PASS_SLOW, SampleKind.PASS_FAST -> 1f
    // Scaled so that all learned views together never outweigh the calibration (see withCalibration).
    SampleKind.LEARNED -> 1f
}

/** Assisted-calibration plan: what to capture, in order. */
object CalibrationPlan {
    const val PASSES_PER_SPEED = 3
    const val MAX_PASSES_PER_SPEED = 8

    data class PhotoView(val key: String, val title: String, val hint: String, val required: Boolean)

    fun photoViews(sport: Sport): List<PhotoView> = when (sport) {
        Sport.RC -> listOf(
            PhotoView("left", tr("Left side"), tr("Side-on, the way it passes the camera one way."), true),
            PhotoView("right", tr("Right side"), tr("Turn it around – many cars differ side to side."), true),
            PhotoView("front", tr("Front"), tr("Nose towards the camera."), false),
            PhotoView("back", tr("Back"), tr("Rear towards the camera."), false),
        )
        Sport.RUNNING -> listOf(
            PhotoView("front", tr("Front"), tr("Facing the camera, whole body in the box."), true),
            PhotoView("side", tr("Side"), tr("Side-on, as they run past."), true),
            PhotoView("back", tr("Back"), tr("Back to the camera."), false),
        )
    }
}

/**
 * Replaces the calibration samples and recomputes the fingerprint used for matching. Views learned
 * from races together weigh at most as much as the calibration itself (photos + passes), however
 * many there are – a mislabelled crossing can colour the fingerprint a little, never take it over.
 */
fun Racer.withCalibration(samples: List<CalibrationSample>): Racer {
    val calWeight = samples.filter { it.kind != SampleKind.LEARNED }.sumOf { it.kind.weight().toDouble() }.toFloat()
    val learned = samples.count { it.kind == SampleKind.LEARNED }
    val learnedWeight = if (learned == 0) 0f else minOf(1f, (if (calWeight > 0f) calWeight else 1f) / learned)
    val fp = Fingerprint.average(samples.map { it.fingerprint to (if (it.kind == SampleKind.LEARNED) learnedWeight else it.kind.weight()) })
    return copy(calibration = samples, fingerprint = fp, fingerprintSamples = samples.size)
}

/** Per-sample fingerprints for matching (a two-tone car can match its left or its right side, or a learned view under other light). */
fun Racer.matchViews(): List<FloatArray> =
    calibration.filter { it.fingerprint.size == Fingerprint.SIZE }.map { it.fingerprint.toFloatArray() }
