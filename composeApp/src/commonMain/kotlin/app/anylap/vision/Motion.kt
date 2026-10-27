package app.anylap.vision

import kotlin.math.abs

/**
 * Per-pixel running mean/variance background model (a single-Gaussian cousin of MOG2).
 * A pixel is "moving" when its brightness differs from the learned mean by more than [k] standard
 * deviations (and at least [minDiff] grey levels), or when its colour clearly changed. The background keeps adapting slowly so lighting drift,
 * shadows of clouds etc. fade in, while racers passing by are not absorbed.
 */
class BackgroundModel(val width: Int, val height: Int) {
    private val n = width * height
    private val mean = FloatArray(n)
    private val variance = FloatArray(n)
    private val meanU = FloatArray(n)
    private val meanV = FloatArray(n)
    private var frames = 0

    var k = 3.2f
    var minDiff = 18f

    /** Colour change (|ΔU|+|ΔV|) that counts as motion even when brightness matches the background. */
    var chromaDiff = 22f

    /**
     * Shadow suppression (the MOG2 idea): a pixel that is only *darker* than its background – same
     * colour, brightness between [shadowMin] and [shadowMax] of the learned mean – is a shadow cast by
     * something moving, not the thing itself. Without this a car under a low sun drags a second blob
     * across the line (0.5–0.8 false crossings per pass in the synthetic bench). Known trade-off:
     * a mid-grey car on grey asphalt loses part of its body to this test; its wheels, windows and
     * any colour still register, and the 3×3 morphology joins the rest.
     */
    var shadowMin = 0.5f
    var shadowMax = 0.93f
    /** Shadows may shift colour a little (sky light): allowed |ΔU|+|ΔV| from the scaled background colour. */
    var shadowChroma = 10f
    /** Pixels marked as shadow in the last [apply], as a fraction of the frame. */
    var shadowFrac = 0f
        private set
    /** How far each pixel was from its background in the last [apply]: |Δbrightness| + |ΔU| + |ΔV|. */
    val strength = FloatArray(n)
    /** Learned background brightness of pixel [i]. */
    fun meanAt(i: Int): Float = mean[i]

    val warmedUp: Boolean get() = frames >= WARMUP

    fun reset() {
        frames = 0
    }

    /** Writes 1/0 into [mask]; returns the fraction of pixels marked as moving. */
    fun apply(gray: ByteArray, u: ByteArray, v: ByteArray, mask: ByteArray): Float {
        if (frames == 0) {
            for (i in 0 until n) {
                mean[i] = (gray[i].toInt() and 0xFF).toFloat()
                meanU[i] = (u[i].toInt() and 0xFF).toFloat()
                meanV[i] = (v[i].toInt() and 0xFF).toFloat()
                variance[i] = 225f
                mask[i] = 0
            }
            frames = 1
            return 0f
        }
        val warm = frames < WARMUP
        val learnBg = if (warm) 1f / (frames + 1) else 0.025f
        val learnFg = if (warm) learnBg else 0.0015f
        val k2 = k * k
        var count = 0
        var shadows = 0
        for (i in 0 until n) {
            val px = (gray[i].toInt() and 0xFF).toFloat()
            val pu = (u[i].toInt() and 0xFF).toFloat()
            val pv = (v[i].toInt() and 0xFF).toFloat()
            val d = px - mean[i]
            val du = pu - meanU[i]
            val dv = pv - meanV[i]
            val d2 = d * d
            val chroma = abs(du) + abs(dv)
            strength[i] = abs(d) + chroma
            var fg = !warm && ((d2 > k2 * variance[i] && abs(d) > minDiff) || chroma > chromaDiff)
            var shadow = false
            if (fg && d < 0f) {
                // Light blocked scales R, G and B alike: brightness by `ratio`, and the colour offset
                // from neutral by the same ratio (a shadow on a cyan line or a red kerb is a darker,
                // *less* saturated cyan or red – not a colour change).
                val ratio = px / (mean[i] + 1f)
                if (ratio >= shadowMin && ratio <= shadowMax) {
                    val eu = pu - (128f + ratio * (meanU[i] - 128f))
                    val ev = pv - (128f + ratio * (meanV[i] - 128f))
                    if (abs(eu) + abs(ev) < shadowChroma) { fg = false; shadow = true; shadows++ }
                }
            }
            if (fg) {
                mask[i] = 1
                count++
            } else {
                mask[i] = 0
            }
            // A shadow is transient like the car that casts it: don't let it pull the background down.
            val a = if (fg || shadow) learnFg else learnBg
            mean[i] += a * d
            meanU[i] += a * du
            meanV[i] += a * dv
            var vr = variance[i] + a * (d2 - variance[i])
            if (vr < 36f) vr = 36f else if (vr > 3600f) vr = 3600f
            variance[i] = vr
        }
        frames++
        shadowFrac = shadows.toFloat() / n
        return count.toFloat() / n
    }

    companion object {
        const val WARMUP = 20
    }
}

/** Fast 3x3 binary morphology on 0/1 masks (separable max/min filters). */
object Morph {
    fun dilate(src: ByteArray, dst: ByteArray, tmp: ByteArray, w: Int, h: Int) {
        // horizontal
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = if (x > 0) src[row + x - 1].toInt() else 0
                val c = src[row + x].toInt()
                val r = if (x < w - 1) src[row + x + 1].toInt() else 0
                tmp[row + x] = if (l or c or r != 0) 1 else 0
            }
        }
        // vertical
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val u = if (y > 0) tmp[row - w + x].toInt() else 0
                val c = tmp[row + x].toInt()
                val d = if (y < h - 1) tmp[row + w + x].toInt() else 0
                dst[row + x] = if (u or c or d != 0) 1 else 0
            }
        }
    }

    fun erode(src: ByteArray, dst: ByteArray, tmp: ByteArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = if (x > 0) src[row + x - 1].toInt() else 0
                val c = src[row + x].toInt()
                val r = if (x < w - 1) src[row + x + 1].toInt() else 0
                tmp[row + x] = if (l and c and r != 0) 1 else 0
            }
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val u = if (y > 0) tmp[row - w + x].toInt() else 0
                val c = tmp[row + x].toInt()
                val d = if (y < h - 1) tmp[row + w + x].toInt() else 0
                dst[row + x] = if (u and c and d != 0) 1 else 0
            }
        }
    }
}

class Blob(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val area: Int,
    val cx: Float,
    val cy: Float,
    /** Component label in [BlobFinder]'s label image (0 = made by a split). */
    val label: Int = 0,
) {
    /** Non-zero when this blob is one part of a split: its pixels carry this tag in the owner map. */
    var tag: Int = 0
    /** Track this part grew out of (inherits its motion history if it becomes a new track); −1 = none. */
    var source: Int = -1
    /** Signed distance (px) of the blob's nearest/farthest pixel from the finish line (for edge timing). */
    var sMin: Float = 0f
    var sMax: Float = 0f
}

/** Connected components (8-connected) of a 0/1 mask. Reuses its buffers between frames. */
class BlobFinder(private val w: Int, private val h: Int) {
    private val labels = IntArray(w * h)
    private val stack = IntArray(w * h)

    /** Label of pixel [i] after the last [find] (0 = background). */
    fun labelAt(i: Int): Int = labels[i]

    fun find(mask: ByteArray, minArea: Int, maxBlobs: Int = 32): List<Blob> {
        labels.fill(0)
        val out = ArrayList<Blob>()
        var next = 1
        for (start in 0 until w * h) {
            if (mask[start].toInt() == 0 || labels[start] != 0) continue
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            var count = 0
            var sx = 0L; var sy = 0L
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w
                val py = p / w
                count++
                sx += px; sy += py
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = px + dx
                        if (nx < 0 || nx >= w) continue
                        val q = ny * w + nx
                        if (mask[q].toInt() != 0 && labels[q] == 0) {
                            labels[q] = next
                            stack[sp++] = q
                        }
                    }
                }
            }
            next++
            if (count >= minArea) {
                out += Blob(minX, minY, maxX - minX + 1, maxY - minY + 1, count, sx.toFloat() / count, sy.toFloat() / count, next - 1)
            }
        }
        return out.sortedByDescending { it.area }.take(maxBlobs)
    }
}
