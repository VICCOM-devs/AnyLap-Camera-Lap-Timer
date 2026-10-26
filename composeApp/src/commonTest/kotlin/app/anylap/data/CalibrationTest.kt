package app.anylap.data

import app.anylap.ui.racers.CalStep
import app.anylap.ui.racers.firstOpenStep
import app.anylap.ui.racers.nextStep
import app.anylap.util.Bmp
import app.anylap.vision.Fingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationTest {
    private fun fp(bin: Int): List<Float> = List(Fingerprint.SIZE) { if (it == bin) 1f else 0f }

    private fun sample(id: String, kind: SampleKind, bin: Int, view: String = "") =
        CalibrationSample(id = id, kind = kind, view = view, fingerprint = fp(bin))

    private val car = Racer("r", "Car 1", Sport.RC, 1, 0xFFE10600)

    @Test
    fun fingerprintIsTheWeightedAverageOfSamples() {
        val r = car.withCalibration(
            listOf(
                sample("p", SampleKind.PHOTO, 0, "left"),
                sample("s", SampleKind.PASS_SLOW, 1),
            ),
        )
        // photo counts half: 0.5 / 1.5 and 1 / 1.5
        assertEquals(1f / 3f, r.fingerprint[0], 1e-5f)
        assertEquals(2f / 3f, r.fingerprint[1], 1e-5f)
        assertEquals(2, r.fingerprintSamples)
        assertTrue(r.isCalibrated)
        assertTrue(!r.hasLegacyCalibration)
    }

    @Test
    fun removingEverySampleUncalibrates() {
        val r = car.withCalibration(listOf(sample("s", SampleKind.PASS_FAST, 5)))
        val empty = r.withCalibration(emptyList())
        assertTrue(!empty.isCalibrated)
        assertEquals(0, empty.fingerprintSamples)
    }

    @Test
    fun legacyCalibrationIsRecognised() {
        val legacy = car.copy(fingerprint = fp(2), fingerprintSamples = 54)
        assertTrue(legacy.hasLegacyCalibration)
        assertEquals(CalStep.Photo(0), firstOpenStep(legacy))
    }

    @Test
    fun wizardStartsAtTheFirstMissingStep() {
        val photos = listOf(sample("l", SampleKind.PHOTO, 0, "left"), sample("r", SampleKind.PHOTO, 1, "right"))
        assertEquals(CalStep.Pass(SampleKind.PASS_SLOW), firstOpenStep(car.withCalibration(photos)))
        val slow = (1..3).map { sample("s$it", SampleKind.PASS_SLOW, 3) }
        assertEquals(CalStep.Pass(SampleKind.PASS_FAST), firstOpenStep(car.withCalibration(photos + slow)))
        val fast = (1..3).map { sample("f$it", SampleKind.PASS_FAST, 4) }
        assertEquals(CalStep.Review, firstOpenStep(car.withCalibration(photos + slow + fast)))
        // optional photos (front/back) are not required
        assertEquals(CalStep.Photo(2), nextStep(Sport.RC, CalStep.Photo(1)))
        assertEquals(CalStep.Pass(SampleKind.PASS_SLOW), nextStep(Sport.RC, CalStep.Photo(3)))
        assertEquals(CalStep.Review, nextStep(Sport.RC, CalStep.Pass(SampleKind.PASS_FAST)))
    }

    @Test
    fun syncAndSessionsNeverCarrySamples() {
        val r = car.withCalibration(listOf(sample("s", SampleKind.PASS_SLOW, 1).copy(imagePath = "/x.bmp")))
        assertTrue(app.anylap.cloud.SyncLogic.cloudRacer(r).calibration.isEmpty())
        assertEquals(r.fingerprint, app.anylap.cloud.SyncLogic.cloudRacer(r).fingerprint)
    }

    @Test
    fun bmpHeaderAndPixelOrder() {
        // 2 x 2: red, green / blue, white (top row first)
        val px = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val b = Bmp.encode(2, 2, px)
        val row = 8 // 2 px * 3 bytes = 6, padded to 8
        assertEquals(54 + row * 2, b.size)
        assertEquals('B'.code.toByte(), b[0]); assertEquals('M'.code.toByte(), b[1])
        assertEquals(2, b[18].toInt()); assertEquals(2, b[22].toInt()); assertEquals(24, b[28].toInt())
        // bottom-up: first stored row is the blue/white row, stored as B,G,R
        assertEquals(listOf(0xFF, 0, 0), (54 until 57).map { b[it].toInt() and 0xFF })
        assertEquals(listOf(0, 0, 0xFF), (54 + row until 57 + row).map { b[it].toInt() and 0xFF })
    }
}
