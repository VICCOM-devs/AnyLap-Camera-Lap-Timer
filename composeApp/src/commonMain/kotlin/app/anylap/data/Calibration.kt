package app.anylap.data

import app.anylap.i18n.tr
import app.anylap.vision.Fingerprint

/** How much each kind of sample counts in the racer's combined fingerprint. */
private fun SampleKind.weight(): Float = when (this) {
    // Photos include a bit of the ground around the car, so they count half.
    SampleKind.PHOTO -> 0.5f
    SampleKind.PASS_SLOW, SampleKind.PASS_FAST -> 1f
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

/** Replaces the calibration samples and recomputes the fingerprint used for matching. */
fun Racer.withCalibration(samples: List<CalibrationSample>): Racer {
    val fp = Fingerprint.average(samples.map { it.fingerprint to it.kind.weight() })
    return copy(calibration = samples, fingerprint = fp, fingerprintSamples = samples.size)
}

/** Per-sample fingerprints for matching (a two-tone car can match its left or its right side). */
fun Racer.matchViews(): List<FloatArray> =
    calibration.filter { it.fingerprint.size == Fingerprint.SIZE }.map { it.fingerprint.toFloatArray() }
