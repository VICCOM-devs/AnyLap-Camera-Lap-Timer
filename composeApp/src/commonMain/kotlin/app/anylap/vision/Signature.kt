package app.anylap.vision

/** What the recognition network says about one crop (see `tools/ml`): a 64-d L2-normalised signature, how many cars are at the centre, where the nose is. */
class SignatureOut(
    val signature: FloatArray,
    /** Softmax over 0 / 1 / 2+ cars at the crop centre. */
    val count: FloatArray,
    /** Nose offset from the crop centre in crop widths (x, y). */
    val nose: FloatArray,
) {
    val carProbability: Float get() = 1f - (count.getOrNull(0) ?: 0f)
}

/** Cosine similarity of two L2-normalised signatures (0..1 for the model's outputs in practice). */
fun signatureSimilarity(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) s += a[i] * b[i]
    return s
}

/**
 * Cuts the crop the model was trained on from a picture: a square of 1.6 × the box's longer side around
 * the box centre, resampled bilinearly to [size] px, as 0..1 RGB channels-last floats (the same recipe as
 * `tools/ml/train/real_crops.py`). Outside the picture = the nearest edge pixel.
 */
fun cropForSignature(argb: IntArray, width: Int, height: Int, l: Float, t: Float, r: Float, b: Float, size: Int): FloatArray {
    val cx = (l + r) / 2f * width
    val cy = (t + b) / 2f * height
    val side = maxOf((r - l) * width, (b - t) * height, 4f) * 1.6f
    val out = FloatArray(size * size * 3)
    val x0 = cx - side / 2f
    val y0 = cy - side / 2f
    val step = side / size
    for (yy in 0 until size) {
        val sy = (y0 + (yy + 0.5f) * step - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val iy = sy.toInt().coerceIn(0, height - 1)
        val iy1 = minOf(iy + 1, height - 1)
        val fy = sy - iy
        for (xx in 0 until size) {
            val sx = (x0 + (xx + 0.5f) * step - 0.5f).coerceIn(0f, (width - 1).toFloat())
            val ix = sx.toInt().coerceIn(0, width - 1)
            val ix1 = minOf(ix + 1, width - 1)
            val fx = sx - ix
            val p00 = argb[iy * width + ix]; val p01 = argb[iy * width + ix1]
            val p10 = argb[iy1 * width + ix]; val p11 = argb[iy1 * width + ix1]
            val o = (yy * size + xx) * 3
            for (c in 0 until 3) {
                val sh = 16 - 8 * c
                val v0 = ((p00 shr sh) and 0xFF) * (1 - fx) + ((p01 shr sh) and 0xFF) * fx
                val v1 = ((p10 shr sh) and 0xFF) * (1 - fx) + ((p11 shr sh) and 0xFF) * fx
                out[o + c] = (v0 * (1 - fy) + v1 * fy) / 255f
            }
        }
    }
    return out
}
