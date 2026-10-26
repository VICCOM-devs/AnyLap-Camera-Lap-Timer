package app.anylap.vision

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Appearance fingerprint built straight from YUV pixels.
 *
 * Colour is binned by **hue** (the direction of the chroma vector) and weighted by **saturation**,
 * so a green stripe on a white car is what the fingerprint is mostly made of, while the white
 * itself lands in one small achromatic bin. Hue survives motion blur and distance (which only dilute
 * saturation), so a stripe read at speed still lands in the same bin as in the calibration photo.
 * Black is treated the same way – a **dark** bin weighted by how dark the pixel is – so black stripes
 * on white count as strongly as coloured ones. Eight brightness (Y) bins back that up.
 *
 * Brightness is judged **relative to the exposure**: "dark" and the Y bins scale with the frame's
 * median luma ([Exposure]), so a white car filmed in a dim hall (half the luma of a sunny day) is
 * still white and not dark. With the fixed 180 threshold the dark bin swallowed the car's hue in
 * dim light and look-alikes could not be told apart (synthetic bench, Oct 2026).
 *
 * Version 2 (Oct 2026). Fingerprints have [SIZE] entries; anything else is from an older build
 * and is treated as "not calibrated".
 */
object Fingerprint {
    private const val HUE_BINS = 24
    private const val CHROMA_SIZE = HUE_BINS + 2 // + achromatic + dark
    private const val ACHROMATIC = HUE_BINS
    private const val DARK = HUE_BINS + 1
    private const val Y_BINS = 8
    const val SIZE = CHROMA_SIZE + Y_BINS
    private const val Y_WEIGHT = 0.35f

    /** Below this chroma radius (in U/V units) a pixel is grey/white/black. */
    private const val MIN_SAT = 7f
    /** Saturation at which a pixel gets full weight. */
    private const val FULL_SAT = 40f
    /** Weight of an achromatic pixel: small, so colour wins, but a plain white car still has a signature. */
    private const val ACHROMATIC_WEIGHT = 0.05f
    /** Grey pixels darker than this count towards the dark bin, fully black at Y = 0 (normal exposure). */
    const val DARK_FROM = 180f
    /** Median luma of a normally exposed frame; [DARK_FROM] and the Y bins are defined for it. */
    const val NORMAL_MEDIAN = 100f

    /**
     * How bright the frame is: [darkFrom] and [yBinScale] are [DARK_FROM] and 8 bins over 0..255
     * for a frame whose median luma is [NORMAL_MEDIAN], and scale with the median otherwise.
     */
    class Exposure(median: Float) {
        val darkFrom: Float = (DARK_FROM * median / NORMAL_MEDIAN).coerceIn(50f, 220f)
        val yBinScale: Float = (Y_BINS / 256f) * (NORMAL_MEDIAN / median.coerceIn(30f, 200f))
    }

    val NORMAL = Exposure(NORMAL_MEDIAN)
    /** Share of the surrounding ring assumed to also be inside a photo box (ground around the car). */
    private const val RING_SUBTRACT = 0.5f

    fun accumulator() = FloatArray(SIZE)

    /** y, u, v are 0..255. */
    fun add(acc: FloatArray, y: Int, u: Int, v: Int, exposure: Exposure = NORMAL) {
        val du = (u - 128).toFloat()
        val dv = (v - 128).toFloat()
        val sat = sqrt(du * du + dv * dv)
        if (sat < MIN_SAT) {
            acc[ACHROMATIC] += ACHROMATIC_WEIGHT
            val darkFrom = exposure.darkFrom
            if (y < darkFrom) acc[DARK] += (darkFrom - y) / darkFrom
        } else {
            val w = min(1f, sat / FULL_SAT)
            // Soft binning: split between the two nearest hue bins so a hue on a bin edge doesn't flicker.
            val pos = ((atan2(dv, du) + PI) / (2 * PI) * HUE_BINS).toFloat()
            val i = pos.toInt()
            val f = pos - i
            acc[i % HUE_BINS] += w * (1f - f)
            acc[(i + 1) % HUE_BINS] += w * f
        }
        acc[CHROMA_SIZE + (y * exposure.yBinScale).toInt().coerceIn(0, Y_BINS - 1)] += 1f
    }

    fun normalize(acc: FloatArray): FloatArray {
        val out = FloatArray(SIZE)
        var cSum = 0f
        for (i in 0 until CHROMA_SIZE) cSum += acc[i]
        var ySum = 0f
        for (i in CHROMA_SIZE until SIZE) ySum += acc[i]
        if (cSum > 0f) for (i in 0 until CHROMA_SIZE) out[i] = acc[i] / cSum * (1f - Y_WEIGHT)
        if (ySum > 0f) for (i in CHROMA_SIZE until SIZE) out[i] = acc[i] / ySum * Y_WEIGHT
        return out
    }

    /**
     * Fingerprint of a photo box with the ground around it taken out: whatever the ring around the
     * box contains is assumed to make up to [RING_SUBTRACT] of the box too, and is subtracted
     * (scaled by area) before normalising. Both inputs are raw accumulators.
     */
    fun subtractRing(box: FloatArray, boxArea: Int, ring: FloatArray, ringArea: Int): FloatArray {
        if (ringArea <= 0 || boxArea <= 0) return normalize(box)
        val scale = RING_SUBTRACT * boxArea.toFloat() / ringArea
        val cleaned = FloatArray(SIZE)
        for (i in 0 until SIZE) cleaned[i] = (box[i] - ring[i] * scale).coerceAtLeast(0f)
        return normalize(cleaned)
    }

    /**
     * Histogram intersection, 1.0 = identical, 0 = nothing in common – computed separately for the
     * colour part and the brightness part and mixed by how colourful the two are. Two coloured cars
     * are told apart by hue; two colourless ones (white, grey, black) only by brightness, which with
     * a fixed 65/35 mix could never move the score by more than 0.35 – white, grey and black scored
     * 0.81–0.88 alike in Blender tests. Colour weight = 0.35 + 0.5 × the share of hue in the two.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != SIZE || b.size != SIZE) return 0f
        var sc = 0f; var sy = 0f; var ha = 0f; var hb = 0f; var ca = 0f; var cb = 0f
        for (i in 0 until CHROMA_SIZE) {
            sc += min(a[i], b[i]); ca += a[i]; cb += b[i]
            if (i < HUE_BINS) { ha += a[i]; hb += b[i] }
        }
        var ya = 0f; var yb = 0f
        for (i in CHROMA_SIZE until SIZE) { sy += min(a[i], b[i]); ya += a[i]; yb += b[i] }
        val cn = max(1e-6f, min(ca, cb)); val yn = max(1e-6f, min(ya, yb))
        val hueShare = 0.5f * ((if (ca > 0f) ha / ca else 0f) + (if (cb > 0f) hb / cb else 0f))
        val wc = 0.35f + 0.5f * hueShare.coerceIn(0f, 1f)
        return wc * (sc / cn).coerceAtMost(1f) + (1f - wc) * (sy / yn).coerceAtMost(1f)
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
