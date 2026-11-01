package app.anylap.vision

import app.anylap.data.LineSpec
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The part of the camera frame the analyzer looks at: the finish line and its surroundings, at up to
 * the camera's full resolution, instead of the whole frame squeezed to [FrameAnalyzer.PROC_W] px.
 *
 * Why: a phone that sees a whole track (or stands far back) makes a Mini-Z ~14 × 7 px at 480 px –
 * too small to be found reliably and too few pixels to tell look-alike colours apart. Only the line's
 * surroundings matter for timing, so the same 480 × 270 budget is spent there (Nov 1 2026 real-video
 * bench: 89 % → 100 % crossings found, 78 % → 99.4 % named right, same engine).
 *
 * The region is the line's bounding box grown by [ACROSS] line lengths on both sides across the line
 * (where racers come from and go to: the approach, the check lines, the motion history) and [ALONG]
 * line lengths beyond its ends, widened to the frame's aspect, never smaller than [FrameAnalyzer.PROC_W]
 * camera pixels (no upscaling) and kept inside the frame. When that is most of the frame anyway, the
 * whole frame is used (null) – exactly what the analyzer did before.
 */
object Roi {
    /** Room across the line on each side, in line lengths. */
    const val ACROSS = 1.5f
    /** Room beyond each end of the line, in line lengths. */
    const val ALONG = 0.3f
    /** A region at least this wide (share of the frame) is not worth it: use the whole frame. */
    const val WHOLE = 0.8f

    /**
     * Region l, t, r, b (0..1 of the frame) for [line] on a camera stream of [fullW] × [fullH] pixels
     * analysed at [procW] px wide; null = the whole frame.
     */
    fun forLine(line: LineSpec, fullW: Int, fullH: Int, procW: Int = FrameAnalyzer.PROC_W): FloatArray? {
        if (fullW <= procW || fullH <= 0) return null
        val ax = line.x1 * fullW; val ay = line.y1 * fullH
        val bx = line.x2 * fullW; val by = line.y2 * fullH
        val len = hypot(bx - ax, by - ay)
        if (len < 4f) return null
        val ux = (bx - ax) / len; val uy = (by - ay) / len          // along the line
        val nx = -uy; val ny = ux                                    // across
        val hl = len * (0.5f + ALONG); val ha = len * ACROSS
        val cx = (ax + bx) / 2; val cy = (ay + by) / 2
        // bounding box of the oriented rectangle
        val ex = abs(ux) * hl + abs(nx) * ha
        val ey = abs(uy) * hl + abs(ny) * ha
        val aspect = fullW.toFloat() / fullH
        var rw = max(2 * ex, 2 * ey * aspect)
        rw = max(rw, procW.toFloat())                                // never finer than the camera's own pixels
        if (rw >= WHOLE * fullW) return null
        val rh = rw / aspect
        val l = (cx - rw / 2).coerceIn(0f, fullW - rw)
        val t = (cy - rh / 2).coerceIn(0f, fullH - rh)
        return floatArrayOf(l / fullW, t / fullH, (l + rw) / fullW, (t + rh) / fullH)
    }

    /** Analysis pixels per whole-frame analysis pixel for a [region] (1 = whole frame). */
    fun zoom(region: FloatArray?): Float = if (region == null) 1f else 1f / max(1e-3f, region[2] - region[0])

    /** [line] in the coordinates of [region] (0..1 of the region). */
    fun lineIn(line: LineSpec, region: FloatArray?): LineSpec {
        if (region == null) return line
        val w = region[2] - region[0]; val h = region[3] - region[1]
        return LineSpec((line.x1 - region[0]) / w, (line.y1 - region[1]) / h, (line.x2 - region[0]) / w, (line.y2 - region[1]) / h)
    }

    /** A box in [region] coordinates → whole-frame coordinates. */
    fun boxOut(b: OverlayBox, region: FloatArray?): OverlayBox {
        if (region == null) return b
        val w = region[2] - region[0]; val h = region[3] - region[1]
        return OverlayBox(region[0] + b.l * w, region[1] + b.t * h, region[0] + b.r * w, region[1] + b.b * h, b.nearLine)
    }

    /** Points x, y, x, y… in [region] coordinates → whole-frame coordinates. */
    fun pointsOut(q: FloatArray?, region: FloatArray?): FloatArray? {
        if (q == null || region == null) return q
        val w = region[2] - region[0]; val h = region[3] - region[1]
        return FloatArray(q.size) { i -> if (i % 2 == 0) region[0] + q[i] * w else region[1] + q[i] * h }
    }

    /** Is the whole-frame rectangle l, t, r, b inside [region]? */
    fun contains(region: FloatArray?, r: FloatArray): Boolean =
        region == null || (r[0] >= region[0] && r[1] >= region[1] && r[2] <= region[2] && r[3] <= region[3])

    fun same(a: FloatArray?, b: FloatArray?): Boolean =
        if (a == null || b == null) a == null && b == null else (0 until 4).all { abs(a[it] - b[it]) < 1e-4f }
}
