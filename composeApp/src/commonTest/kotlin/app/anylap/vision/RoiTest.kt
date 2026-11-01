package app.anylap.vision

import app.anylap.data.LineSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Zoom to the line ([Roi]): the analyzer looks at the line's surroundings at camera resolution and
 * still reports everything in whole-frame units. A far camera (small cars, like a whole-track view)
 * is the case it exists for – see tools/bench/real (Kyosho Mini-Z Cup clip: 85 % → 100 % found).
 */
class RoiTest {
    private val fw = 1280
    private val fh = 720
    private val line = LineSpec(0.5f, 0.42f, 0.5f, 0.58f)   // a short line in the middle of a wide view

    @Test
    fun regionAroundAShortLine() {
        val r = assertNotNull(Roi.forLine(line, fw, fh))
        assertTrue(r[0] <= 0.5f && r[2] >= 0.5f && r[1] <= 0.42f && r[3] >= 0.58f, "line inside the region")
        val wPx = (r[2] - r[0]) * fw; val hPx = (r[3] - r[1]) * fh
        assertEquals(fw.toFloat() / fh, wPx / hPx, 0.01f, "region keeps the frame's aspect")
        assertTrue(wPx >= FrameAnalyzer.PROC_W - 0.5f, "never finer than the camera's own pixels")
        assertTrue(r.all { it in 0f..1f }, "inside the frame")
        // a line across most of the frame, or a stream no bigger than the analysis: the whole frame as before
        assertNull(Roi.forLine(LineSpec(0.1f, 0.5f, 0.9f, 0.5f), fw, fh))
        assertNull(Roi.forLine(line, 480, 270))
        // near an edge: shifted inside, not cut
        val e = assertNotNull(Roi.forLine(LineSpec(0.97f, 0.85f, 0.97f, 0.95f), fw, fh))
        assertTrue(e[2] <= 1f + 1e-5f && e[3] <= 1f + 1e-5f && e[0] >= 0f && e[1] >= 0f)
    }

    @Test
    fun templateKeepsItsSizeAcrossZoom() {
        val n = 10 * 6
        val t = CarTemplate(10, 6, FloatArray(n) { 120f }, FloatArray(n) { 128f }, FloatArray(n) { 128f }, FloatArray(n) { 1f }, 10f, 6f, 100f, 1f)
        val z = t.atZoom(2.5f)
        assertEquals(25f, z.lengthPx, 0.01f); assertEquals(2.5f, z.zoom)
        assertEquals(25, z.w); assertEquals(15, z.h)
        val back = CarTemplate.decode(z.encode())!!
        assertEquals(2.5f, back.zoom, "zoom survives encode/decode")
        // pictures saved before zoom existed decode as zoom 1
        val old = floatArrayOf(10f, 6f, 10f, 6f, 100f) + FloatArray(4 * n) { 1f }
        assertEquals(1f, CarTemplate.decode(old)!!.zoom)
        assertTrue(t.atZoom(1.03f) === t, "no resampling for a 3 % difference")
    }

    /** A bright car [len] × [wid] camera px driving left → right through the line, frames like Camera.android. */
    private fun drive(zoom: Boolean, len: Int, wid: Int, speedPxPerFrame: Int): List<CrossingEvent> {
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        a.config = AnalyzerConfig(line = line, zoomToLine = zoom, metersPerProcPx = 0.01f)
        val pw = FrameAnalyzer.PROC_W; val ph = pw * fh / fw
        val carY = (0.5f * fh).toInt()
        for (i in 0 until 160) {
            val carX = if (i < 60) -1000 else 200 + (i - 60) * speedPxPerFrame   // 60 frames of empty track to learn the background
            fun lumaAt(x: Int, y: Int): Int {
                val onCar = x in carX - len / 2 until carX + len / 2 && y in carY - wid / 2 until carY + wid / 2
                return if (onCar) 230 else 60 + ((x * 7 + y * 13) % 11)   // textured dark floor
            }
            val region = a.regionFor(fw, fh)
            val rx = ((region?.get(0) ?: 0f) * fw).toInt(); val ry = ((region?.get(1) ?: 0f) * fh).toInt()
            val rw = (((region?.get(2) ?: 1f) - (region?.get(0) ?: 0f)) * fw).toInt(); val rh = (((region?.get(3) ?: 1f) - (region?.get(1) ?: 0f)) * fh).toInt()
            val y = ByteArray(pw * ph); val u = ByteArray(pw * ph) { 128.toByte() }; val v = ByteArray(pw * ph) { 128.toByte() }
            var k = 0
            for (py in 0 until ph) for (px in 0 until pw) { y[k++] = lumaAt(rx + px * rw / pw, ry + py * rh / ph).toByte() }
            val ts = i * 1_000_000_000L / 60
            a.process(AnalysisFrame(ts, pw, ph, y, u, v, fw, fh, region) { x0, y0, cw, ch ->
                ByteArray(cw * ch) { j -> lumaAt(x0 + j % cw, y0 + j / cw).toByte() }
            })
        }
        a.flush(160 * 1_000_000_000L / 60)
        return events.filter { it.accepted && !it.rescue }
    }

    @Test
    fun zoomedCrossingIsReportedInWholeFrameUnits() {
        // a car big enough for both: same time, same place, same size, same speed either way
        val whole = drive(false, 60, 28, 12).single()
        val zoomed = drive(true, 60, 28, 12).single()
        // true crossing: centre reaches x = 640 at frame 60 + (640 − 200) / 12
        val tTrue = (60 + 440f / 12) / 60f
        assertEquals(tTrue, zoomed.timestampNs / 1e9f, 0.004f, "time")
        assertEquals(tTrue, whole.timestampNs / 1e9f, 0.006f, "time (whole frame)")
        val zb = assertNotNull(zoomed.box)
        assertEquals(0.5f, (zb.l + zb.r) / 2, 0.02f, "box centre x in frame coordinates")
        assertEquals(0.5f, (zb.t + zb.b) / 2, 0.02f, "box centre y in frame coordinates")
        assertEquals(whole.vx, zoomed.vx, 0.05f * abs(whole.vx), "speed in frame widths/s")
        assertEquals(whole.speedKmh!!, zoomed.speedKmh!!, 0.05f * whole.speedKmh!!, "km/h from the whole-frame metres per pixel")
        assertEquals(whole.along, zoomed.along, 0.03f)
        // area in whole-frame analysis px (what calibration stores), within the mask clean-up's slack
        assertTrue(abs(zoomed.area - whole.area) <= 0.35f * whole.area, "area ${zoomed.area} vs ${whole.area}")
    }

    @Test
    fun smallFarCarIsFoundOnlyWhenZoomed() {
        // a Mini-Z seen from far: 24 × 11 camera px = 9 × 4 px at 480 – below the RC minimum blob
        assertEquals(0, drive(false, 24, 11, 12).size, "whole frame: too small to count")
        assertEquals(1, drive(true, 24, 11, 12).size, "zoomed to the line: counted once")
    }
}
