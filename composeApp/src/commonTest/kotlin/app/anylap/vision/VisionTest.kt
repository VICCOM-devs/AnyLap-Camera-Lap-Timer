package app.anylap.vision

import app.anylap.data.LineSpec
import app.anylap.data.Sport
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VisionTest {

    // ------------------------------------------------------------------ markers

    /** Renders a marker (with white margin) into a grey image using a rotated/scaled/skewed square. */
    private fun renderMarker(id: Int, w: Int, h: Int, cx: Float, cy: Float, side: Float, angleDeg: Float, skew: Float, rnd: Random): ByteArray {
        val img = ByteArray(w * h)
        for (i in img.indices) img[i] = (110 + rnd.nextInt(30)).toByte() // textured background (shirt)
        val cells = Markers.cells(id)
        val a = angleDeg * kotlin.math.PI.toFloat() / 180f
        val ca = cos(a); val sa = sin(a)
        val margin = side * (8f / 6f) // marker + one-cell white margin each side
        for (y in 0 until h) for (x in 0 until w) {
            // inverse transform: image → marker coordinates in [−margin/2, margin/2]
            val dx = x - cx; val dy = y - cy
            var u = (dx * ca + dy * sa)
            val v = (-dx * sa + dy * ca)
            u += v * skew
            val half = margin / 2
            if (u < -half || u > half || v < -half || v > half) continue
            val mu = (u + side / 2) / side * 6f
            val mv = (v + side / 2) / side * 6f
            val black = mu >= 0 && mu < 6 && mv >= 0 && mv < 6 && cells[mv.toInt()][mu.toInt()]
            val base = if (black) 25 else 225
            img[y * w + x] = (base + rnd.nextInt(-12, 13)).coerceIn(0, 255).toByte()
        }
        return img
    }

    @Test
    fun dictionaryIsWellSeparated() {
        for (i in 0 until Markers.COUNT) {
            for (j in i + 1 until Markers.COUNT) {
                var c = Markers.CODES[j]
                repeat(4) {
                    assertTrue((Markers.CODES[i] xor c).countOneBits() >= 4, "codes $i and $j too similar")
                    c = Markers.rotate(c)
                }
            }
        }
    }

    @Test
    fun readsEveryMarkerUnderRotationScaleAndNoise() {
        val rnd = Random(7)
        val det = MarkerDetector()
        var ok = 0
        for (id in 0 until Markers.COUNT) {
            val w = 220; val h = 300
            val side = 60f + rnd.nextFloat() * 50f
            val angle = rnd.nextFloat() * 50f - 25f
            val skew = rnd.nextFloat() * 0.2f - 0.1f
            val img = renderMarker(id, w, h, 110f + rnd.nextFloat() * 20f, 140f + rnd.nextFloat() * 30f, side, angle, skew, rnd)
            val found = det.detect(img, w, h)
            if (found == listOf(id)) ok++
        }
        assertTrue(ok >= 48, "read $ok / 50 markers")
    }

    @Test
    fun smallMarkersStillRead() {
        val rnd = Random(3)
        val det = MarkerDetector()
        var ok = 0
        for (id in 0 until 20) {
            val img = renderMarker(id, 120, 160, 60f, 80f, 30f, rnd.nextFloat() * 20f - 10f, 0f, rnd)
            if (det.detect(img, 120, 160) == listOf(id)) ok++
        }
        assertTrue(ok >= 17, "read $ok / 20 small markers (5 px cells)")
    }

    @Test
    fun noFalseMarkersInNoise() {
        val rnd = Random(11)
        val det = MarkerDetector()
        var falsePositives = 0
        repeat(30) {
            val img = ByteArray(200 * 200) { (rnd.nextInt(256)).toByte() }
            // add a few dark blocks
            repeat(6) {
                val bx = rnd.nextInt(150); val by = rnd.nextInt(150); val s = 10 + rnd.nextInt(40)
                for (y in by until minOf(200, by + s)) for (x in bx until minOf(200, bx + s)) img[y * 200 + x] = 20
            }
            falsePositives += det.detect(img, 200, 200).size
        }
        assertTrue(falsePositives <= 1, "false positives: $falsePositives")
    }

    // ------------------------------------------------------------------ analyzer

    private class Scene(val w: Int = 480, val h: Int = 270, seed: Int = 1) {
        val rnd = Random(seed)
        val bgY = ByteArray(w * h) { (90 + rnd.nextInt(40)).toByte() }
        val bgU = ByteArray(w * h) { (125 + rnd.nextInt(6)).toByte() }
        val bgV = ByteArray(w * h) { (125 + rnd.nextInt(6)).toByte() }

        data class Car(val x: Float, val y: Float, val cw: Int, val ch: Int, val yv: Int, val u: Int, val v: Int)

        fun frame(ts: Long, cars: List<Car>): AnalysisFrame {
            val y = bgY.copyOf(); val u = bgU.copyOf(); val v = bgV.copyOf()
            for (i in y.indices) y[i] = ((y[i].toInt() and 0xFF) + rnd.nextInt(-4, 5)).coerceIn(0, 255).toByte()
            for (c in cars) {
                val x0 = (c.x - c.cw / 2).toInt(); val y0 = (c.y - c.ch / 2).toInt()
                for (yy in y0 until y0 + c.ch) for (xx in x0 until x0 + c.cw) {
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val i = yy * w + xx
                    y[i] = c.yv.toByte(); u[i] = c.u.toByte(); v[i] = c.v.toByte()
                }
            }
            return AnalysisFrame(ts, w, h, y, u, v, w, h) { _, _, _, _ -> null }
        }
    }

    private fun runPass(
        analyzer: FrameAnalyzer,
        scene: Scene,
        startNs: Long,
        fps: Int,
        pxPerSec: Float,
        car: (Float) -> Scene.Car,
        frames: Int,
    ): Long {
        var ts = startNs
        val dt = 1_000_000_000L / fps
        for (f in 0 until frames) {
            val x = -40f + pxPerSec * (f.toFloat() / fps)
            analyzer.process(scene.frame(ts, listOf(car(x))))
            ts += dt
        }
        return ts
    }

    @Test
    fun crossingTimeIsInterpolatedBetweenFrames() {
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        a.config = AnalyzerConfig(line = LineSpec(0.5f, 0.05f, 0.5f, 0.95f), sport = Sport.RC, metersPerProcPx = 0.01f)
        val scene = Scene()
        var ts = 1_000_000_000L
        // warm up on an empty track
        repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        val start = ts
        val speed = 600f // px/s → line at x=240 reached after (240+40)/600 s
        ts = runPass(a, scene, start, 60, speed, { x -> Scene.Car(x, 135f, 30, 16, 200, 90, 200) }, 120)
        repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        a.flush(ts)
        assertEquals(1, events.size, "exactly one crossing")
        val expected = start + ((240f + 40f) / speed * 1e9).toLong()
        val err = abs(events[0].timestampNs - expected) / 1e6
        assertTrue(err < 6.0, "timing error $err ms")
        // speed: 600 px/s * 0.01 m/px = 6 m/s = 21.6 km/h
        val kmh = assertNotNull(events[0].speedKmh)
        assertTrue(abs(kmh - 21.6f) < 2f, "speed $kmh km/h")
        assertTrue(events[0].direction == 1 || events[0].direction == -1)
    }

    @Test
    fun calibratedColoursAreToldApart() {
        val scene = Scene(seed = 5)
        val red = { x: Float -> Scene.Car(x, 135f, 34, 18, 90, 100, 220) }
        val blue = { x: Float -> Scene.Car(x, 135f, 34, 18, 80, 210, 110) }

        // calibration: one pass each with a single racer in the config
        fun learn(car: (Float) -> Scene.Car, seedTs: Long): FloatArray {
            val events = ArrayList<CrossingEvent>()
            val a = FrameAnalyzer(onCrossing = { events += it })
            a.config = AnalyzerConfig(line = LineSpec(), racers = listOf(RacerRef("x", null, null)))
            var ts = seedTs
            repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
            ts = runPass(a, scene, ts, 60, 500f, car, 120)
            a.flush(ts)
            return assertNotNull(events.single().fingerprint)
        }
        val fpRed = learn(red, 1_000_000_000L)
        val fpBlue = learn(blue, 50_000_000_000L)

        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        a.config = AnalyzerConfig(line = LineSpec(), racers = listOf(RacerRef("red", fpRed, null), RacerRef("blue", fpBlue, null)))
        var ts = 100_000_000_000L
        repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        ts = runPass(a, scene, ts, 60, 550f, blue, 120)
        repeat(20) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        ts = runPass(a, scene, ts, 60, 450f, red, 140)
        a.flush(ts)
        assertEquals(listOf("blue", "red"), events.map { it.racerId })
        assertTrue(events.all { it.source == IdSource.APPEARANCE })
    }

    @Test
    fun passingBesideTheLineIsIgnored() {
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        // short line in the top part of the frame only
        a.config = AnalyzerConfig(line = LineSpec(0.5f, 0.05f, 0.5f, 0.35f))
        val scene = Scene(seed = 9)
        var ts = 1_000_000_000L
        repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        ts = runPass(a, scene, ts, 60, 600f, { x -> Scene.Car(x, 230f, 30, 16, 200, 90, 200) }, 120)
        a.flush(ts)
        assertEquals(0, events.size)
    }

    @Test
    fun hugeBlobsAreNotCrossings() {
        // Half the frame changing (a light switched on, someone walking up to the phone) is not a car.
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        a.config = AnalyzerConfig(line = LineSpec(), sport = Sport.RC)
        val scene = Scene(seed = 11)
        var ts = 1_000_000_000L
        repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        ts = runPass(a, scene, ts, 60, 400f, { x -> Scene.Car(x, 135f, 260, 180, 200, 90, 200) }, 120)
        a.flush(ts)
        assertEquals(0, events.size)
    }

    @Test
    fun flickeringOnTheLineIsNotACrossing() {
        // Something static that pops in and out of the background right on the line never travels anywhere.
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        a.config = AnalyzerConfig(line = LineSpec(), sport = Sport.RC)
        val scene = Scene(seed = 12)
        var ts = 1_000_000_000L
        repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        repeat(120) { f ->
            val x = 240f + if (f % 2 == 0) -3f else 3f
            a.process(scene.frame(ts, listOf(Scene.Car(x, 135f, 30, 16, 200, 90, 200)))); ts += 16_666_667L
        }
        a.flush(ts)
        assertEquals(0, events.size)
    }

    @Test
    fun appearanceGateTurnsDownThingsThatDontLookLikeTheRacer() {
        val scene = Scene(seed = 13)
        val red = { x: Float -> Scene.Car(x, 135f, 34, 18, 90, 100, 220) }
        val grey = { x: Float -> Scene.Car(x, 135f, 34, 18, 170, 128, 128) }

        // Learn red from one ungated pass.
        val learned = ArrayList<CrossingEvent>()
        val la = FrameAnalyzer(onCrossing = { learned += it })
        la.config = AnalyzerConfig(line = LineSpec(), racers = listOf(RacerRef("r", null, null)))
        var ts = 1_000_000_000L
        repeat(30) { la.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        ts = runPass(la, scene, ts, 60, 500f, red, 120)
        la.flush(ts)
        val fpRed = assertNotNull(learned.single().fingerprint)

        // Gated: the grey thing is reported but not accepted; the red car is.
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        a.config = AnalyzerConfig(
            line = LineSpec(),
            racers = listOf(RacerRef("r", fpRed, null, listOf(fpRed))),
            minMatch = FrameAnalyzer.MIN_MATCH,
        )
        ts = 50_000_000_000L
        repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        ts = runPass(a, scene, ts, 60, 500f, grey, 120)
        repeat(20) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
        ts = runPass(a, scene, ts, 60, 500f, red, 120)
        a.flush(ts)
        assertEquals(listOf(false, true), events.map { it.accepted }, "matches: ${events.map { it.match }}")
        assertTrue(events[0].match < FrameAnalyzer.MIN_MATCH)
        assertTrue(events[1].match >= FrameAnalyzer.MIN_MATCH)
        assertEquals("r", events[1].racerId)
    }

    @Test
    fun snapshotOfTheCrossingOnlyWhenAsked() {
        val scene = Scene(seed = 11)
        val car = { x: Float -> Scene.Car(x, 135f, 34, 18, 90, 100, 220) }
        for (want in listOf(false, true)) {
            val events = ArrayList<CrossingEvent>()
            val a = FrameAnalyzer(onCrossing = { events += it })
            a.config = AnalyzerConfig(line = LineSpec(), racers = listOf(RacerRef("x", null, null)), captureSnapshots = want)
            var ts = 1_000_000_000L
            repeat(30) { a.process(scene.frame(ts, emptyList())); ts += 16_666_667L }
            ts = runPass(a, scene, ts, 60, 500f, car, 120)
            a.flush(ts)
            val snap = events.single().snapshot
            if (!want) {
                assertEquals(null, snap)
            } else {
                assertNotNull(snap)
                // At least the car (34 x 18 analysis px) plus some padding.
                assertTrue(snap.width >= 34 && snap.height >= 18, "snapshot ${snap.width}x${snap.height}")
                assertEquals(snap.width * snap.height, snap.argb.size)
                assertTrue(snap.argb.all { (it ushr 24) == 0xFF }, "opaque pixels")
            }
        }
    }
}
