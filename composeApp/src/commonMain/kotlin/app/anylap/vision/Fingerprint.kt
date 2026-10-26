package app.anylap.vision

import kotlin.math.min

/**
 * Appearance fingerprint: a colour histogram built straight from YUV pixels.
 * 16x16 chroma (U,V) bins capture paint/clothing colours fairly independently of brightness,
 * plus 8 brightness (Y) bins so black, grey and white can still be told apart.
 */
object Fingerprint {
    private const val UV_BINS = 16
    private const val Y_BINS = 8
    private const val UV_SIZE = UV_BINS * UV_BINS
    const val SIZE = UV_SIZE + Y_BINS
    private const val Y_WEIGHT = 0.35f

    fun accumulator() = FloatArray(SIZE)

    /** y, u, v are 0..255. */
    fun add(acc: FloatArray, y: Int, u: Int, v: Int) {
        acc[(u shr 4) * UV_BINS + (v shr 4)] += 1f
        acc[UV_SIZE + (y shr 5)] += 1f
    }

    fun normalize(acc: FloatArray): FloatArray {
        val out = FloatArray(SIZE)
        var uvSum = 0f
        for (i in 0 until UV_SIZE) uvSum += acc[i]
        var ySum = 0f
        for (i in UV_SIZE until SIZE) ySum += acc[i]
        if (uvSum > 0f) for (i in 0 until UV_SIZE) out[i] = acc[i] / uvSum * (1f - Y_WEIGHT)
        if (ySum > 0f) for (i in UV_SIZE until SIZE) out[i] = acc[i] / ySum * Y_WEIGHT
        return out
    }

    /** Histogram intersection: 1.0 = identical colour make-up, 0 = nothing in common. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != SIZE || b.size != SIZE) return 0f
        var s = 0f
        for (i in 0 until SIZE) s += min(a[i], b[i])
        return s
    }

    /** Running average of an existing fingerprint (n samples) with a new one. */
    fun blend(old: List<Float>, n: Int, new: FloatArray): List<Float> {
        if (old.size != SIZE || n <= 0) return new.toList()
        val w = 1f / (n + 1)
        return List(SIZE) { i -> old[i] * (1f - w) + new[i] * w }
    }

    /** Weighted mean of several fingerprints (weights need not sum to 1). Empty input → empty list. */
    fun average(items: List<Pair<List<Float>, Float>>): List<Float> {
        val valid = items.filter { it.first.size == SIZE && it.second > 0f }
        if (valid.isEmpty()) return emptyList()
        val out = FloatArray(SIZE)
        var wSum = 0f
        for ((fp, w) in valid) {
            for (i in 0 until SIZE) out[i] += fp[i] * w
            wSum += w
        }
        for (i in 0 until SIZE) out[i] /= wSum
        return out.toList()
    }
}
