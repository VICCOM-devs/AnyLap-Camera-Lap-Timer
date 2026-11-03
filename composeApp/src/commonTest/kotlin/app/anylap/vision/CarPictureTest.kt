package app.anylap.vision

import app.anylap.data.LineSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The recognition model's car picture ([CarPicture]): the crossing car at the camera's full resolution, cut
 * as the model was trained (square, 1.6 × the car's longer side, centred), from the camera's colour crop when
 * it has more detail than the analysis image, else from the analysis image – so crossings and calibration
 * pictures always share one framing.
 */
class CarPictureTest {
    private val fw = 1280
    private val fh = 720
    private val line = LineSpec(0.5f, 0.3f, 0.5f, 0.7f)
    private val red = Triple(230, 30, 40)

    private fun ycc(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val y = (0.299 * r + 0.587 * g + 0.114 * b)
        val u = (b - y) / 1.772 + 128; val v = (r - y) / 1.402 + 128
        return Triple(y.toInt().coerceIn(0, 255), u.toInt().coerceIn(0, 255), v.toInt().coerceIn(0, 255))
    }

    /** One red car 90 × 40 camera px driving left → right through the line; returns the accepted crossings. */
    private fun drive(zoom: Boolean, withColour: Boolean): List<CrossingEvent> {
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        a.config = AnalyzerConfig(line = line, zoomToLine = zoom, captureSnapshots = true)
        val pw = FrameAnalyzer.PROC_W; val ph = pw * fh / fw
        val carY = fh / 2; val len = 90; val wid = 40
        for (i in 0 until 150) {
            val carX = if (i < 60) -1000 else 300 + (i - 60) * 10
            fun rgb(x: Int, y: Int): Int {
                val on = x in carX - len / 2 until carX + len / 2 && y in carY - wid / 2 until carY + wid / 2
                if (on) return (0xFF shl 24) or (red.first shl 16) or (red.second shl 8) or red.third
                val g = 55 + ((x * 7 + y * 13) % 9)
                return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            val region = a.regionFor(fw, fh)
            val rx = ((region?.get(0) ?: 0f) * fw).toInt(); val ry = ((region?.get(1) ?: 0f) * fh).toInt()
            val rw = (((region?.get(2) ?: 1f) - (region?.get(0) ?: 0f)) * fw).toInt(); val rh = (((region?.get(3) ?: 1f) - (region?.get(1) ?: 0f)) * fh).toInt()
            val y = ByteArray(pw * ph); val u = ByteArray(pw * ph); val v = ByteArray(pw * ph)
            var k = 0
            for (py in 0 until ph) for (px in 0 until pw) {
                val c = rgb(rx + px * rw / pw, ry + py * rh / ph)
                val (yy, uu, vv) = ycc((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                y[k] = yy.toByte(); u[k] = uu.toByte(); v[k] = vv.toByte(); k++
            }
            val colour: ((Int, Int, Int, Int) -> IntArray?)? = if (withColour) { x0, y0, cw, ch -> IntArray(cw * ch) { j -> rgb(x0 + j % cw, y0 + j / cw) } } else null
            a.process(AnalysisFrame(i * 1_000_000_000L / 60, pw, ph, y, u, v, fw, fh, region, colour) { x0, y0, cw, ch ->
                ByteArray(cw * ch) { j -> ycc(rgb(x0 + j % cw, y0 + j / cw).let { (it shr 16) and 0xFF }, 0, 0).first.toByte() }
            })
        }
        a.flush(150 * 1_000_000_000L / 60)
        return events.filter { it.accepted && !it.rescue }
    }

    private fun centreIsRed(c: CarPicture): Boolean {
        val p = c.argb[(c.height / 2) * c.width + c.width / 2]
        return ((p shr 16) and 0xFF) > 150 && ((p shr 8) and 0xFF) < 90
    }

    @Test
    fun fromTheCameraWhenItHasMoreDetail() {
        // whole frame at 480 px: the camera has 2.7× the pixels → the picture comes from its colour crop
        val e = drive(zoom = false, withColour = true).single()
        val car = assertNotNull(e.snapshot?.car, "car picture")
        assertEquals(e.trackId, car.trackId)
        assertEquals(car.width, car.height, "square")
        // ≈ 1.6 × the car's ~100 px tracker box (mask growth included) in camera px, capped at MAX_SIDE
        assertEquals(CarPicture.MAX_SIDE, car.width)
        assertTrue(centreIsRed(car), "car in the middle")
        // the car fills the middle 1 / 1.6: the model's box edges are on the car, the corners on the floor
        val corner = car.argb[2 * car.width + 2]
        assertTrue(((corner shr 16) and 0xFF) < 100, "corner is floor")
        // read as the model reads it: centre of the 64 px crop is red
        val crop = cropForSignature(car.argb, car.width, car.height, CarPicture.MODEL_BOX[0], CarPicture.MODEL_BOX[1], CarPicture.MODEL_BOX[2], CarPicture.MODEL_BOX[3], 64)
        val o = (32 * 64 + 32) * 3
        assertTrue(crop[o] > 0.6f && crop[o + 1] < 0.35f, "model crop centre red: ${crop[o]}, ${crop[o + 1]}")
    }

    @Test
    fun fromTheAnalysisImageWithoutColourCrop() {
        // no colour crop (iOS): the same square cut from the analysis image (about a third of the camera's size here)
        val e = drive(zoom = false, withColour = false).single()
        val car = assertNotNull(e.snapshot?.car, "car picture")
        assertEquals(car.width, car.height)
        assertTrue(car.width in 40..90, "analysis-size square, got ${car.width}")
        assertTrue(centreIsRed(car), "car in the middle")
    }

    @Test
    fun zoomedToTheLineTheAnalysisImageIsUsed() {
        // zoomed 1:1 to the line: the analysis image already has every camera pixel – same framing, no extra copy
        val e = drive(zoom = true, withColour = true).single()
        val car = assertNotNull(e.snapshot?.car)
        assertTrue(abs(car.width - car.height) == 0 && centreIsRed(car))
    }
}
