package app.anylap.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateFitTest {
    /** A flat-coloured car picture, nose +x. */
    private fun car(y: Int, u: Int, v: Int, l: Int = 30, w: Int = 18): CarTemplate {
        val n = l * w
        return CarTemplate(l, w, FloatArray(n) { y.toFloat() }, FloatArray(n) { u.toFloat() }, FloatArray(n) { v.toFloat() }, FloatArray(n) { 1f }, l.toFloat(), w.toFloat(), 100f)
    }

    /** Two cars side by side touching (no gap), driving +x: the fit must find both, each at its own place, with its own name. */
    @Test fun touchingCarsComeOutAsTwoOutlines() {
        val cw = 80; val ch = 60
        val y = ByteArray(cw * ch) { 90 }; val u = ByteArray(cw * ch) { 128.toByte() }; val v = ByteArray(cw * ch) { 128.toByte() }; val fg = ByteArray(cw * ch)
        fun paint(x0: Int, y0: Int, yy: Int, uu: Int, vv: Int) { for (j in y0 until y0 + 18) for (i in x0 until x0 + 30) { val q = j * cw + i; y[q] = yy.toByte(); u[q] = uu.toByte(); v[q] = vv.toByte(); fg[q] = 1 } }
        paint(20, 10, 230, 128, 128)    // white car, upper
        paint(24, 28, 40, 128, 128)     // black car right below it, touching, 4 px further ahead
        val crop = FitCrop(0, 0, cw, ch, y, u, v, fg, 0L, 0f, 300f, 37f, 37f, 40f, 0f, 0f, 1f, 1e-7f, 100f)
        val (pls, _) = TemplateFit.fit(crop, listOf("white" to car(230, 128, 128), "black" to car(40, 128, 128), "red" to car(80, 100, 220)), 3)
        assertEquals(2, pls.size, "two racers")
        val white = pls.first { it.racerId == "white" }; val black = pls.first { it.racerId == "black" }
        assertTrue(kotlin.math.abs(white.cy - 19f) < 2f && kotlin.math.abs(white.cx - 35f) < 2f, "white at its place: ${white.cx},${white.cy}")
        assertTrue(kotlin.math.abs(black.cy - 37f) < 2f && kotlin.math.abs(black.cx - 39f) < 2f, "black at its place: ${black.cx},${black.cy}")
    }
}
