package app.anylap.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AnyLap bib markers: a 6x6 grid of squares – a black border ring around 4x4 data cells
 * (white = 1) – printed with a white margin. 50 ids, any two differ in at least 4 cells
 * under every rotation, so one misread cell is still identified correctly.
 *
 * NEVER change [CODES]: printed bibs depend on them.
 */
object Markers {
    val CODES = intArrayOf(
        0xDC16, 0x0427, 0x651C, 0x1F1C, 0xAD62, 0x1DCF, 0x5AAD, 0xE533, 0xACC4, 0x1EB2,
        0x5F36, 0x1369, 0x7099, 0xFDE8, 0xAF2E, 0x7301, 0x3A20, 0xF2B3, 0xAEB9, 0x394A,
        0x10EA, 0xCB3F, 0x893C, 0x29DF, 0xFF70, 0x0657, 0x622E, 0x4B71, 0x0963, 0x6DC5,
        0x8151, 0x6E41, 0xAF10, 0xE063, 0x368B, 0x5D2C, 0xA0AB, 0xF38B, 0xF86A, 0xD2E2,
        0xCB87, 0x1219, 0xCE5E, 0xF116, 0x9466, 0x6A82, 0xEB0C, 0x15F6, 0xDE0A, 0xE084,
    )
    const val COUNT = 50

    /** 6x6 cells, true = black. Row-major. */
    fun cells(id: Int): Array<BooleanArray> {
        val code = CODES[id]
        return Array(6) { r ->
            BooleanArray(6) { c ->
                if (r == 0 || c == 0 || r == 5 || c == 5) true
                else {
                    val bit = (code shr ((r - 1) * 4 + (c - 1))) and 1
                    bit == 0 // white = 1
                }
            }
        }
    }

    /** Rotate a 4x4 code 90° clockwise (same convention as the generator). */
    internal fun rotate(c: Int): Int {
        var out = 0
        for (r in 0 until 4) for (col in 0 until 4) {
            if ((c shr ((3 - col) * 4 + r)) and 1 != 0) out = out or (1 shl (r * 4 + col))
        }
        return out
    }

    /** Best matching id for a read code, or null. */
    fun identify(code: Int, maxErrors: Int = 1): Int? {
        var bestId = -1
        var bestD = 99
        var c = code
        repeat(4) {
            for (i in CODES.indices) {
                val d = (CODES[i] xor c).countOneBits()
                if (d < bestD) { bestD = d; bestId = i }
            }
            c = rotate(c)
        }
        return if (bestD <= maxErrors) bestId else null
    }
}

/**
 * Finds AnyLap markers in a greyscale image (typically a crop around a runner).
 * Adaptive threshold → dark connected regions → four corners → perspective sampling of the 6x6 grid.
 */
class MarkerDetector {

    fun detect(img: ByteArray, w: Int, h: Int): List<Int> {
        if (w < 16 || h < 16) return emptyList()
        val dark = threshold(img, w, h)
        val found = LinkedHashSet<Int>()
        val labels = IntArray(w * h)
        val stack = IntArray(w * h)
        var next = 1
        val minSide = 10
        val maxSide = (min(w, h) * 0.95f).toInt()
        for (start in 0 until w * h) {
            if (dark[start].toInt() == 0 || labels[start] != 0) continue
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var count = 0
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            // extreme points for corner estimates
            var sMin = Int.MAX_VALUE; var sMinP = start
            var sMax = Int.MIN_VALUE; var sMaxP = start
            var dMin = Int.MAX_VALUE; var dMinP = start
            var dMax = Int.MIN_VALUE; var dMaxP = start
            var xMinP = start; var xMaxP = start; var yMinP = start; var yMaxP = start
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w
                val py = p / w
                count++
                if (px < minX) { minX = px; xMinP = p }
                if (px > maxX) { maxX = px; xMaxP = p }
                if (py < minY) { minY = py; yMinP = p }
                if (py > maxY) { maxY = py; yMaxP = p }
                val s = px + py
                val d = px - py
                if (s < sMin) { sMin = s; sMinP = p }
                if (s > sMax) { sMax = s; sMaxP = p }
                if (d < dMin) { dMin = d; dMinP = p }
                if (d > dMax) { dMax = d; dMaxP = p }
                // 4-connected is enough for a solid border ring
                if (px > 0) push(dark, labels, stack, p - 1, next).let { if (it) stack[sp++] = p - 1 }
                if (px < w - 1) push(dark, labels, stack, p + 1, next).let { if (it) stack[sp++] = p + 1 }
                if (py > 0) push(dark, labels, stack, p - w, next).let { if (it) stack[sp++] = p - w }
                if (py < h - 1) push(dark, labels, stack, p + w, next).let { if (it) stack[sp++] = p + w }
            }
            next++
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) continue
            val ratio = bw.toFloat() / bh
            if (ratio < 0.4f || ratio > 2.5f) continue
            val fill = count.toFloat() / (bw * bh)
            if (fill < 0.25f || fill > 0.95f) continue

            // Two corner hypotheses: diagonal extremes (upright square) and axis extremes (≈45°).
            val quadA = floatArrayOf(
                (sMinP % w).toFloat(), (sMinP / w).toFloat(), // TL
                (dMaxP % w).toFloat(), (dMaxP / w).toFloat(), // TR
                (sMaxP % w).toFloat(), (sMaxP / w).toFloat(), // BR
                (dMinP % w).toFloat(), (dMinP / w).toFloat(), // BL
            )
            val quadB = floatArrayOf(
                (yMinP % w).toFloat(), (yMinP / w).toFloat(),
                (xMaxP % w).toFloat(), (xMaxP / w).toFloat(),
                (yMaxP % w).toFloat(), (yMaxP / w).toFloat(),
                (xMinP % w).toFloat(), (xMinP / w).toFloat(),
            )
            val quad = if (area(quadA) >= area(quadB)) quadA else quadB
            if (area(quad) < 0.45f * bw * bh) continue
            readCode(img, w, h, quad)?.let { id -> found += id }
        }
        return found.toList()
    }

    private fun push(dark: ByteArray, labels: IntArray, @Suppress("UNUSED_PARAMETER") stack: IntArray, q: Int, label: Int): Boolean {
        if (dark[q].toInt() != 0 && labels[q] == 0) {
            labels[q] = label
            return true
        }
        return false
    }

    /** Pixels clearly darker than their neighbourhood (integral-image local mean). */
    private fun threshold(img: ByteArray, w: Int, h: Int): ByteArray {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += img[y * w + x].toInt() and 0xFF
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum
            }
        }
        val r = max(4, min(w, h) / 10)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = sum.toFloat() / ((x1 - x0) * (y1 - y0))
                val v = img[y * w + x].toInt() and 0xFF
                out[y * w + x] = if (v < mean - 10f) 1 else 0
            }
        }
        return out
    }

    private fun area(q: FloatArray): Float {
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return abs(a) / 2f
    }

    /** Samples the 6x6 grid inside [quad] (TL, TR, BR, BL) and decodes it. */
    private fun readCode(img: ByteArray, w: Int, h: Int, quad: FloatArray): Int? {
        val hm = Homography.squareToQuad(quad) ?: return null
        val cellMeans = FloatArray(36)
        for (r in 0 until 6) for (c in 0 until 6) {
            var sum = 0f
            var n = 0
            for (sy in -1..1) for (sx in -1..1) {
                val u = (c + 0.5f + sx * 0.22f) / 6f
                val v = (r + 0.5f + sy * 0.22f) / 6f
                val p = hm.map(u, v)
                val lum = bilinear(img, w, h, p.first, p.second) ?: return null
                sum += lum; n++
            }
            cellMeans[r * 6 + c] = sum / n
        }
        val lo = cellMeans.min()
        val hi = cellMeans.max()
        if (hi - lo < 30f) return null
        val t = (lo + hi) / 2f
        var borderDark = 0
        for (r in 0 until 6) for (c in 0 until 6) {
            if (r == 0 || c == 0 || r == 5 || c == 5) if (cellMeans[r * 6 + c] < t) borderDark++
        }
        if (borderDark < 18) return null
        var code = 0
        for (r in 1..4) for (c in 1..4) {
            if (cellMeans[r * 6 + c] >= t) code = code or (1 shl ((r - 1) * 4 + (c - 1)))
        }
        return Markers.identify(code)
    }

    private fun bilinear(img: ByteArray, w: Int, h: Int, x: Float, y: Float): Float? {
        if (x < 0f || y < 0f || x > w - 1.001f || y > h - 1.001f) return null
        val x0 = x.toInt(); val y0 = y.toInt()
        val fx = x - x0; val fy = y - y0
        fun at(xx: Int, yy: Int) = (img[yy * w + xx].toInt() and 0xFF).toFloat()
        val a = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val b = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return a * (1 - fy) + b * fy
    }
}

/** Projective map from the unit square to a quadrilateral. */
class Homography(private val m: DoubleArray) {
    fun map(u: Float, v: Float): Pair<Float, Float> {
        val x = m[0] * u + m[1] * v + m[2]
        val y = m[3] * u + m[4] * v + m[5]
        val z = m[6] * u + m[7] * v + 1.0
        return (x / z).toFloat() to (y / z).toFloat()
    }

    companion object {
        /** quad = TL, TR, BR, BL as x,y pairs. */
        fun squareToQuad(q: FloatArray): Homography? {
            val src = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val u = src[i * 2]; val v = src[i * 2 + 1]
                val x = q[i * 2].toDouble(); val y = q[i * 2 + 1].toDouble()
                a[i * 2] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
                a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
            }
            // Gaussian elimination with partial pivoting
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-9) return null
                val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (k in col until 9) a[r][k] -= f * a[col][k]
                }
            }
            return Homography(DoubleArray(8) { a[it][8] / a[it][it] })
        }

        fun distance(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    }
}
