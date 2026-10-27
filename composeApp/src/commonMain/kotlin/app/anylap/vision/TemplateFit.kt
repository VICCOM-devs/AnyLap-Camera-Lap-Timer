package app.anylap.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A racer's top-down picture from a calibration pass, in its own frame: nose pointing +x, one
 * template pixel = one analysis pixel. [mask] = 1 where the racer is. [lengthPx] / [widthPx] are its
 * real size (the motion-blur streak taken off the length).
 */
class CarTemplate(
    val w: Int,
    val h: Int,
    val y: FloatArray,
    val u: FloatArray,
    val v: FloatArray,
    val mask: FloatArray,
    val lengthPx: Float,
    val widthPx: Float,
    /** Median luma of the frame it was taken from (exposure reference). */
    val lumaRef: Float,
) {
    fun encode(): FloatArray = floatArrayOf(w.toFloat(), h.toFloat(), lengthPx, widthPx, lumaRef) + y + u + v + mask
    companion object {
        fun decode(a: FloatArray): CarTemplate? {
            if (a.size < 5) return null
            val w = a[0].toInt(); val h = a[1].toInt(); val n = w * h
            if (a.size != 5 + 4 * n) return null
            return CarTemplate(w, h, a.copyOfRange(5, 5 + n), a.copyOfRange(5 + n, 5 + 2 * n), a.copyOfRange(5 + 2 * n, 5 + 3 * n), a.copyOfRange(5 + 3 * n, 5 + 4 * n), a[2], a[3], a[4])
        }
    }
}

/**
 * The frame around a crossing racer, kept for the fit: colours and the raw foreground (before the
 * clean-up dilations, so touching racers are not glued together), its heading and how it moved
 * across the finish line.
 */
class FitCrop(
    val x0: Int, val y0: Int, val w: Int, val h: Int,
    val y: ByteArray, val u: ByteArray, val v: ByteArray, val fg: ByteArray,
    /** Frame time. */
    val ts: Long,
    /** Heading of the racer (radians, image coordinates) and its speed along it (px/s). */
    val heading: Float, val speedPx: Float,
    /** The racer's centroid in this frame (frame px). */
    val cx: Float, val cy: Float,
    /** Finish line a→b (frame px) and the signed speed across it (px/ns, + = towards the + side). */
    val ax: Float, val ay: Float, val dx: Float, val dy: Float, val vAcross: Float,
    val lumaRef: Float,
) {
    val len = max(1f, sqrt(dx * dx + dy * dy))
    fun side(x: Float, y: Float) = (dx * (y - ay) - dy * (x - ax)) / len
}

/** One racer placed on the picture: centre (frame px), angle, its true size and the 4 corners (nose edge first). */
class Placement(val racerId: String, val score: Float, val cx: Float, val cy: Float, val angle: Float, val lengthPx: Float, val widthPx: Float) {
    fun corners(): FloatArray {
        val c = cos(angle); val s = sin(angle); val l = lengthPx / 2; val wd = widthPx / 2
        val pts = listOf(l to -wd, l to wd, -l to wd, -l to -wd)
        val out = FloatArray(8)
        for ((k, p) in pts.withIndex()) { out[2 * k] = cx + p.first * c - p.second * s; out[2 * k + 1] = cy + p.first * s + p.second * c }
        return out
    }
}

/**
 * Fits racers' calibration pictures onto a crossing: each candidate's picture is turned to the
 * racer's heading (± a few degrees), slid over the crop, and scored by how much of it lands on
 * foreground of the same colour. The best placement is taken, its pixels are marked as explained,
 * and the next racer is fitted on what is left – so two touching racers come out as two outlines
 * of their own true size, a broken-off front wing falls inside its car, and the colour of each
 * racer is read only from inside its own outline.
 */
object TemplateFit {
    /** A placement must explain at least this share of its picture to count. */
    const val MIN_SCORE = 0.35f
    /** Colour difference scale (0..255 units) for a pixel to count as "the same". */
    const val SIGMA = 28f
    /** A second racer must land on at least this share of still unexplained foreground. */
    const val SECOND_MIN_HITS = 0.3f
    private val ANGLES = floatArrayOf(-0.14f, -0.07f, 0f, 0.07f, 0.14f)

    /** Captures a racer's picture from a crossing of it alone (calibration). */
    fun capture(c: FitCrop, exposureS: Float, blurSeen: Float): CarTemplate? {
        // centroid and extents of the foreground in the racer's own axes
        val cs = cos(c.heading); val sn = sin(c.heading)
        var n = 0; var sx = 0f; var sy = 0f
        for (j in 0 until c.h) for (i in 0 until c.w) if (c.fg[j * c.w + i].toInt() != 0) { n++; sx += i; sy += j }
        if (n < 20) return null
        val mx = sx / n; val my = sy / n
        val us = FloatArray(n); val vs = FloatArray(n); var k = 0
        for (j in 0 until c.h) for (i in 0 until c.w) if (c.fg[j * c.w + i].toInt() != 0) {
            val dx = i - mx; val dy = j - my
            us[k] = dx * cs + dy * sn; vs[k] = -dx * sn + dy * cs; k++
        }
        us.sort(); vs.sort()
        val u0 = us[(n * 0.01f).toInt()]; val u1 = us[(n * 0.99f).toInt().coerceAtMost(n - 1)]
        val v0 = vs[(n * 0.01f).toInt()]; val v1 = vs[(n * 0.99f).toInt().coerceAtMost(n - 1)]
        val ucen = (u0 + u1) / 2; val vcen = (v0 + v1) / 2
        val tw = ceil(u1 - u0).toInt() + 3; val th = ceil(v1 - v0).toInt() + 3
        val ty = FloatArray(tw * th); val tu = FloatArray(tw * th); val tv = FloatArray(tw * th); val tm = FloatArray(tw * th)
        for (j in 0 until th) for (i in 0 until tw) {
            val uu = i - tw / 2f + 0.5f + ucen; val vv = j - th / 2f + 0.5f + vcen
            val x = (mx + uu * cs - vv * sn).roundToInt(); val y = (my + uu * sn + vv * cs).roundToInt()
            if (x < 0 || y < 0 || x >= c.w || y >= c.h) continue
            val q = y * c.w + x; val t = j * tw + i
            ty[t] = (c.y[q].toInt() and 0xFF).toFloat(); tu[t] = (c.u[q].toInt() and 0xFF).toFloat(); tv[t] = (c.v[q].toInt() and 0xFF).toFloat()
            tm[t] = if (c.fg[q].toInt() != 0) 1f else 0f
        }
        val blur = blurSeen * c.speedPx * exposureS
        return CarTemplate(tw, th, ty, tu, tv, tm, max(4f, (u1 - u0) - blur), max(3f, v1 - v0), c.lumaRef)
    }

    private class Rot(val dx: FloatArray, val dy: FloatArray, val y: FloatArray, val u: FloatArray, val v: FloatArray)

    private fun rotate(t: CarTemplate, angle: Float, gain: Float): Rot {
        val cs = cos(angle); val sn = sin(angle)
        val idx = (0 until t.w * t.h).filter { t.mask[it] > 0.5f }
        val dx = FloatArray(idx.size); val dy = FloatArray(idx.size)
        val y = FloatArray(idx.size); val u = FloatArray(idx.size); val v = FloatArray(idx.size)
        for ((k, q) in idx.withIndex()) {
            val uu = q % t.w - t.w / 2f + 0.5f; val vv = q / t.w - t.h / 2f + 0.5f
            dx[k] = uu * cs - vv * sn; dy[k] = uu * sn + vv * cs
            y[k] = t.y[q] * gain; u[k] = t.u[q]; v[k] = t.v[q]
        }
        return Rot(dx, dy, y, u, v)
    }

    /**
     * Places up to [maxCars] racers from [cands] on [c]. Returns the placements, best first, and every
     * candidate's best score in the first round (for the identity margin).
     */
    fun fit(c: FitCrop, cands: List<Pair<String, CarTemplate>>, maxCars: Int): Pair<List<Placement>, List<Pair<String, Float>>> {
        if (cands.isEmpty()) return emptyList<Placement>() to emptyList()
        val explained = BooleanArray(c.w * c.h)
        val inv = 1f / (2 * SIGMA * SIGMA)
        // foreground bounding box: centres are searched inside it
        var fx0 = c.w; var fy0 = c.h; var fx1 = -1; var fy1 = -1; var fgN = 0
        for (j in 0 until c.h) for (i in 0 until c.w) if (c.fg[j * c.w + i].toInt() != 0) { fgN++; fx0 = min(fx0, i); fy0 = min(fy0, j); fx1 = max(fx1, i); fy1 = max(fy1, j) }
        if (fgN < 10) return emptyList<Placement>() to emptyList()
        val rots = cands.map { (id, t) -> Triple(id, t, ANGLES.map { a -> c.heading + a to rotate(t, c.heading + a, c.lumaRef / max(1f, t.lumaRef)) }) }

        fun score(r: Rot, px: Float, py: Float): Float {
            var s = 0f
            val n = r.dx.size
            for (k in 0 until n) {
                val x = (px + r.dx[k]).roundToInt(); val y = (py + r.dy[k]).roundToInt()
                if (x < 0 || y < 0 || x >= c.w || y >= c.h) continue
                val q = y * c.w + x
                if (c.fg[q].toInt() == 0 || explained[q]) continue
                val dY = (c.y[q].toInt() and 0xFF) - r.y[k]; val dU = (c.u[q].toInt() and 0xFF) - r.u[k]; val dV = (c.v[q].toInt() and 0xFF) - r.v[k]
                s += exp(-(0.5f * dY * dY + dU * dU + dV * dV) * inv)
            }
            return s / max(1, n)
        }

        class Best(var s: Float = -1f, var x: Float = 0f, var y: Float = 0f, var a: Float = 0f, var rot: Rot? = null)
        fun best(r: List<Pair<Float, Rot>>): Best {
            val b = Best()
            for ((a, rot) in r) {
                var yy = fy0
                while (yy <= fy1) {
                    var xx = fx0
                    while (xx <= fx1) {
                        val s = score(rot, xx.toFloat(), yy.toFloat())
                        if (s > b.s) { b.s = s; b.x = xx.toFloat(); b.y = yy.toFloat(); b.a = a; b.rot = rot }
                        xx += 2
                    }
                    yy += 2
                }
            }
            // refine ±1 px around the coarse best
            val rot = b.rot ?: return b
            for (oy in -1..1) for (ox in -1..1) {
                val s = score(rot, b.x + ox, b.y + oy)
                if (s > b.s) { b.s = s; b.x += ox; b.y += oy }
            }
            return b
        }

        val out = ArrayList<Placement>()
        var firstScores: List<Pair<String, Float>> = emptyList()
        val left = rots.toMutableList()
        repeat(maxCars) { round ->
            if (left.isEmpty()) return@repeat
            val results = left.map { (id, t, r) -> Triple(id, t, best(r)) }
            if (round == 0) firstScores = results.map { it.first to it.third.s }.sortedByDescending { it.second }
            val win = results.maxByOrNull { it.third.s } ?: return@repeat
            if (win.third.s < MIN_SCORE) return@repeat
            // a later racer must have real unexplained foreground under it, not scraps of the first
            if (round > 0) {
                val rot = win.third.rot!!
                var hits = 0
                for (k in rot.dx.indices) {
                    val x = (win.third.x + rot.dx[k]).roundToInt(); val y = (win.third.y + rot.dy[k]).roundToInt()
                    if (x in 0 until c.w && y in 0 until c.h && c.fg[y * c.w + x].toInt() != 0 && !explained[y * c.w + x]) hits++
                }
                if (hits < SECOND_MIN_HITS * rot.dx.size) return@repeat
            }
            val p = Placement(win.first, win.third.s, c.x0 + win.third.x, c.y0 + win.third.y, win.third.a, win.second.lengthPx, win.second.widthPx)
            out += p
            // mark its outline (true size + 1 px) as explained
            val cs = cos(p.angle); val sn = sin(p.angle)
            val l = p.lengthPx / 2 + 0.5f; val wd = p.widthPx / 2 + 0.5f
            for (j in 0 until c.h) for (i in 0 until c.w) {
                val dx = i - win.third.x; val dy = j - win.third.y
                if (abs(dx * cs + dy * sn) <= l && abs(-dx * sn + dy * cs) <= wd) explained[j * c.w + i] = true
            }
            left.removeAll { it.first == win.first }
        }
        return out to firstScores
    }

    /** Heading (radians) from a velocity. */
    fun heading(vx: Float, vy: Float): Float = atan2(vy, vx)
}
