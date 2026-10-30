package app.anylap.engine

import app.anylap.data.CalibrationSample
import app.anylap.data.Racer
import app.anylap.data.SampleKind
import app.anylap.data.Sport
import app.anylap.data.withCalibration
import app.anylap.vision.CrossingEvent
import app.anylap.vision.Fingerprint
import app.anylap.vision.FitInfo
import app.anylap.vision.IdSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningTest {
    /** A fingerprint with most of its weight in one hue bin (+ a little everywhere so similarities are sane). */
    private fun fp(bin: Int, spread: Float = 0.1f): List<Float> {
        val a = FloatArray(Fingerprint.SIZE) { spread }
        a[bin] += 1f
        return Fingerprint.normalize(a).toList()
    }

    private fun sample(kind: SampleKind, f: List<Float>, by: String? = null, id: String = "s${f.hashCode()}${kind}") =
        CalibrationSample(id = id, kind = kind, fingerprint = f, learnedBy = by)

    private fun racer(): Racer = Racer("r", "Red 7", Sport.RC, 7, 0xFFE10600).withCalibration(
        listOf(sample(SampleKind.PHOTO, fp(0), id = "p1"), sample(SampleKind.PASS_SLOW, fp(0), id = "s1"), sample(SampleKind.PASS_FAST, fp(1), id = "f1")),
    )

    private fun event(
        racer: String?, match: Float, confidence: Float, source: IdSource = IdSource.APPEARANCE,
        split: Boolean = false, accepted: Boolean = true, sizeRatio: Float = 1f, fit: FitInfo? = null, fpBin: Int = 0,
    ) = CrossingEvent(
        timestampNs = 1_000_000_000L, direction = 1, racerId = racer, source = source, confidence = confidence, speedKmh = 20f,
        fingerprint = fp(fpBin).toFloatArray(), candidates = listOf("r" to match, "b" to (match - confidence).coerceAtLeast(0f)),
        markerSeen = null, match = match, accepted = accepted, area = 400, along = 0.5f, split = split, sizeRatio = sizeRatio, fit = fit,
    )

    private val laps = listOf(18_000L, 18_200L, 17_900L, 18_100L)

    @Test
    fun clearCrossingThatFitsTheLapRhythmIsLearned() {
        assertEquals(Learning.By.AUTO, Learning.certainty("r", event("r", 0.9f, 0.3f), laps, 18_050L, onCard = false, locked = false))
    }

    @Test
    fun anythingOnACheckCardOrSplitOrOffPaceIsNot() {
        assertNull(Learning.certainty("r", event("r", 0.9f, 0.3f), laps, 18_050L, onCard = true, locked = false))
        assertNull(Learning.certainty("r", event("r", 0.9f, 0.3f, split = true), laps, 18_050L, onCard = false, locked = false))
        assertNull(Learning.certainty("r", event("r", 0.9f, 0.3f), laps, 11_000L, onCard = false, locked = false)) // a lap far too short
        assertNull(Learning.certainty("r", event("r", 0.9f, 0.3f), laps, null, onCard = false, locked = false))    // no lap ended
        assertNull(Learning.certainty("r", event("r", 0.9f, 0.3f), laps.take(2), 18_000L, onCard = false, locked = false)) // too few laps
        assertNull(Learning.certainty("r", event("r", 0.9f, 0.3f, sizeRatio = 1.6f), laps, 18_050L, onCard = false, locked = false)) // two cars in the blob
        assertNull(Learning.certainty("r", event("r", 0.9f, 0.3f, accepted = false), laps, 18_050L, onCard = false, locked = false))
    }

    @Test
    fun weakColourNeedsEitherAClearMarginOrAClearMatch() {
        assertNull(Learning.certainty("r", event("r", 0.72f, 0.05f), laps, 18_000L, onCard = false, locked = false))
        assertEquals(Learning.By.AUTO, Learning.certainty("r", event("r", 0.72f, 0.2f), laps, 18_000L, onCard = false, locked = false))
        assertEquals(Learning.By.AUTO, Learning.certainty("r", event("r", 0.9f, 0.05f), laps, 18_000L, onCard = false, locked = false))
        assertNull(Learning.certainty("r", event("r", 0.6f, 0.4f), laps, 18_000L, onCard = false, locked = false))
    }

    @Test
    fun humanAssignmentAndConfidentFitCount() {
        assertEquals(Learning.By.VERDICT, Learning.certainty("r", event("r", 0.5f, 0.0f), laps, 18_000L, onCard = false, locked = true))
        val renamed = FitInfo(listOf("r" to 0.8f, "b" to 0.3f), 1, renamed = true)
        assertEquals(Learning.By.FIT, Learning.certainty("r", event("r", 0.5f, 0.1f, fit = renamed), laps, 18_000L, onCard = false, locked = false))
        val weakFit = FitInfo(listOf("r" to 0.55f, "b" to 0.3f), 1, renamed = true)
        assertNull(Learning.certainty("r", event("r", 0.5f, 0.1f, fit = weakFit), laps, 18_000L, onCard = false, locked = false))
    }

    @Test
    fun mergeAddsViewsSkipsDuplicatesAndKeepsTheCap() {
        var r = racer()
        val before = r.fingerprint
        // a view under other light (hue bin 2) is taken; the same one again is a near-duplicate
        val (r1, dropped1) = Learning.merge(r, listOf(sample(SampleKind.LEARNED, fp(2), "auto", id = "l1")))
        assertEquals(1, r1.samplesOf(SampleKind.LEARNED).size)
        assertTrue(dropped1.isEmpty())
        val (r2, dropped2) = Learning.merge(r1, listOf(sample(SampleKind.LEARNED, fp(2), "auto", id = "l2")))
        assertEquals(1, r2.samplesOf(SampleKind.LEARNED).size)
        assertEquals(listOf("l2"), dropped2.map { it.id })
        // the calibration still has the bigger say in the combined fingerprint
        r = r2
        assertTrue(Fingerprint.similarity(r.fingerprint.toFloatArray(), before.toFloatArray()) > 0.8f)
        // per-session cap: 20 different views in one go → PER_SESSION taken
        val many = (3 until 23).map { sample(SampleKind.LEARNED, fp(it), "auto", id = "m$it") }
        val (r3, dropped3) = Learning.merge(r, many)
        assertEquals(1 + Learning.PER_SESSION, r3.samplesOf(SampleKind.LEARNED).size)
        assertEquals(many.size - Learning.PER_SESSION, dropped3.size)
        // overall cap: keep feeding sessions until full, the count never passes MAX_LEARNED
        var rr = r3
        for (round in 0 until 6) {
            rr = Learning.merge(rr, (0 until Learning.PER_SESSION).map { sample(SampleKind.LEARNED, fp((round * 7 + it) % Fingerprint.SIZE, spread = 0.1f + 0.02f * round), "auto", id = "x$round-$it") }).first
            assertTrue(rr.samplesOf(SampleKind.LEARNED).size <= Learning.MAX_LEARNED)
        }
        // the calibration samples are untouched
        assertEquals(3, rr.calibration.count { it.kind != SampleKind.LEARNED })
    }

    @Test
    fun aViewUnlikeTheCalibrationIsOnlyTakenOnAVerdict() {
        val r = racer()
        val odd = fp(20, spread = 0f) // nothing in common with hue bins 0/1
        assertTrue(Fingerprint.similarity(r.fingerprint.toFloatArray(), odd.toFloatArray()) < Learning.MIN_CAL_SIMILARITY)
        assertEquals(0, Learning.merge(r, listOf(sample(SampleKind.LEARNED, odd, "auto", id = "o1"))).first.samplesOf(SampleKind.LEARNED).size)
        assertEquals(1, Learning.merge(r, listOf(sample(SampleKind.LEARNED, odd, "verdict", id = "o2"))).first.samplesOf(SampleKind.LEARNED).size)
    }

    @Test
    fun learnedViewsNeverOutweighTheCalibration() {
        val r = racer()
        val cal = r.fingerprint.toFloatArray()
        val full = (0 until Learning.MAX_LEARNED).map { sample(SampleKind.LEARNED, fp(10 + it % 8), "verdict", id = "v$it") }
        var rr = r
        for (chunk in full.chunked(Learning.PER_SESSION)) rr = Learning.merge(rr, chunk).first
        val mixed = rr.fingerprint.toFloatArray()
        val learnedMean = Fingerprint.average(full.map { it.fingerprint to 1f }).toFloatArray()
        // the combined fingerprint sits between the two, and no nearer the learned pile than the calibration
        assertTrue(Fingerprint.similarity(mixed, cal) >= Fingerprint.similarity(mixed, learnedMean) - 0.05f)
    }

    @Test
    fun lapEndingAtACrossing() {
        val cr = listOf(18_000L, 36_100L, 54_000L)
        assertEquals(18_100L, Learning.lapEndingAt(cr, 36_100L, raceStart = true))
        assertEquals(18_000L, Learning.lapEndingAt(cr, 18_000L, raceStart = true))
        assertNull(Learning.lapEndingAt(cr, 18_000L, raceStart = false))
        assertNull(Learning.lapEndingAt(cr, 40_000L, raceStart = true))
    }
}
