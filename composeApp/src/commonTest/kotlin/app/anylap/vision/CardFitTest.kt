package app.anylap.vision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardFitTest {
    private val cw = 90
    private val ch = 70

    /** Flat-coloured car picture, nose +x, cut a little wider than the car (as captured); the mask is the car. */
    private fun car(y: Int, u: Int, v: Int, l: Int = 30, w: Int = 18): CarTemplate {
        val tw = l + 3; val th = w + 3; val n = tw * th
        val mask = FloatArray(n) { q -> if (q % tw in 1 until tw - 2 && q / tw in 1 until th - 2) 1f else 0f }
        return CarTemplate(tw, th, FloatArray(n) { y.toFloat() }, FloatArray(n) { u.toFloat() }, FloatArray(n) { v.toFloat() }, mask, l.toFloat(), w.toFloat(), 100f)
    }

    private class Pic(val w: Int, val h: Int) {
        val y = ByteArray(w * h) { 90 }; val u = ByteArray(w * h) { 128.toByte() }; val v = ByteArray(w * h) { 128.toByte() }; val fg = ByteArray(w * h)
        fun paint(x0: Int, y0: Int, yy: Int, uu: Int, vv: Int, moving: Boolean = true) {
            for (j in y0 until y0 + 18) for (i in x0 until x0 + 30) { val q = j * w + i; y[q] = yy.toByte(); u[q] = uu.toByte(); v[q] = vv.toByte(); if (moving) fg[q] = 1 }
        }
    }

    private fun centre(q: FloatArray, w: Int, h: Int) = (0 until 4).map { q[2 * it] * w }.average().toFloat() to (0 until 4).map { q[2 * it + 1] * h }.average().toFloat()

    /** Two touching cars in one tracker box: each gets its own outline, at its own place, with its own name. */
    @Test fun touchingCarsInOneBoxGetTheirOwnOutlines() {
        val p = Pic(cw, ch)
        p.paint(20, 12, 230, 128, 128)   // white, upper
        p.paint(26, 30, 60, 90, 200)     // red-ish, right below it, touching
        val box = CardFit.Spec(null, null, 20f / cw, 12f / ch, 56f / cw, 48f / ch, 3f, 0f)
        val pool = listOf("white" to car(230, 128, 128), "red" to car(60, 90, 200), "green" to car(120, 60, 60))
        val fits = CardFit.fit(cw, ch, p.y, p.u, p.v, p.fg, 100f,
            listOf(CardFit.Spec("white", pool[0].second, box.l, box.t, box.r, box.b, 3f, 0f), CardFit.Spec("red", pool[1].second, box.l, box.t, box.r, box.b, 3f, 0f)),
            pool, 0f).filterNotNull()
        assertEquals(2, fits.size)
        val (wx, wy) = centre(fits.first { it.racerId == "white" }.quad, cw, ch)
        val (rx, ry) = centre(fits.first { it.racerId == "red" }.quad, cw, ch)
        assertTrue(abs(wx - 35f) < 2f && abs(wy - 21f) < 2f, "white at $wx,$wy")
        assertTrue(abs(rx - 41f) < 2f && abs(ry - 39f) < 2f, "red at $rx,$ry")
    }

    /** A car that barely shows in the foreground (dark on dark) still gets its outline where the race put it. */
    @Test fun carTheCameraBarelySawIsOutlinedFromItsRecord() {
        val p = Pic(cw, ch)
        p.paint(30, 25, 70, 128, 128, moving = false)
        val pool = listOf("black" to car(70, 128, 128))
        val fits = CardFit.fit(cw, ch, p.y, p.u, p.v, p.fg, 100f, listOf(CardFit.Spec("black", pool[0].second, 30f / cw, 25f / ch, 60f / cw, 43f / ch, 3f, 0f)), pool, 0f)
        val f = fits.single()!!
        val (x, y) = centre(f.quad, cw, ch)
        assertTrue(abs(x - 45f) < 1.5f && abs(y - 34f) < 1.5f, "outline at $x,$y")
    }

    /** Tags never cover a car or another tag, even with three cars bunched together. */
    @Test fun tagsNeverCoverCars() {
        fun rect(x: Float, y: Float, w: Float, h: Float) = floatArrayOf(x + w, y, x + w, y + h, x, y + h, x, y)
        val cars = listOf(rect(100f, 100f, 90f, 50f), rect(150f, 152f, 90f, 50f), rect(60f, 154f, 85f, 48f))
        val sizes = List(3) { 80f to 22f }
        val tags = LabelLayout.place(cars, sizes, 0f, 0f, 340f, 340f)
        for ((i, t) in tags.withIndex()) {
            for (c in cars) {
                val xs = (0 until 4).map { c[2 * it] }; val ys = (0 until 4).map { c[2 * it + 1] }
                val overlap = t.x < xs.max() && t.x + t.w > xs.min() && t.y < ys.max() && t.y + t.h > ys.min()
                assertTrue(!overlap, "tag $i over a car")
            }
            for ((j, o) in tags.withIndex()) if (j != i) assertTrue(!(t.x < o.x + o.w && t.x + t.w > o.x && t.y < o.y + o.h && t.y + t.h > o.y), "tags $i and $j overlap")
            assertTrue(t.x >= 0f && t.y >= 0f && t.x + t.w <= 340f && t.y + t.h <= 340f, "tag $i on the canvas")
        }
    }
}
