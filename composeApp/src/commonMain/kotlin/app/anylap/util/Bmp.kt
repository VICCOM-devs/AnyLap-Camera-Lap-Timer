package app.anylap.util

/** Minimal 24-bit BMP writer: tiny calibration thumbnails without a platform image encoder. */
object Bmp {
    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width > 0 && height > 0 && argb.size >= width * height)
        val rowSize = (width * 3 + 3) and 3.inv()
        val dataSize = rowSize * height
        val out = ByteArray(54 + dataSize)
        fun i32(at: Int, v: Int) {
            out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte(); out[at + 2] = (v shr 16).toByte(); out[at + 3] = (v shr 24).toByte()
        }
        fun i16(at: Int, v: Int) {
            out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte()
        }
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        i32(2, out.size)
        i32(10, 54)
        i32(14, 40)
        i32(18, width)
        i32(22, height) // positive = rows stored bottom-up
        i16(26, 1)
        i16(28, 24)
        i32(34, dataSize)
        i32(38, 2835); i32(42, 2835) // 72 dpi
        for (y in 0 until height) {
            val src = (height - 1 - y) * width
            var o = 54 + y * rowSize
            for (x in 0 until width) {
                val p = argb[src + x]
                out[o++] = p.toByte()          // B
                out[o++] = (p shr 8).toByte()  // G
                out[o++] = (p shr 16).toByte() // R
            }
        }
        return out
    }

    /** Reads a 24-bit BMP written by [encode] (bottom-up rows): (width, height, ARGB pixels), or null. */
    fun decode(bytes: ByteArray): Triple<Int, Int, IntArray>? {
        if (bytes.size < 54 || bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) return null
        fun i32(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        fun i16(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        val offset = i32(10)
        val width = i32(18)
        val heightRaw = i32(22)
        val height = kotlin.math.abs(heightRaw)
        if (i16(28) != 24 || width <= 0 || height <= 0) return null
        val rowSize = (width * 3 + 3) and 3.inv()
        if (bytes.size < offset + rowSize * height) return null
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val dst = (if (heightRaw > 0) height - 1 - y else y) * width
            var o = offset + y * rowSize
            for (x in 0 until width) {
                val b = bytes[o].toInt() and 0xFF; val g = bytes[o + 1].toInt() and 0xFF; val r = bytes[o + 2].toInt() and 0xFF
                out[dst + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                o += 3
            }
        }
        return Triple(width, height, out)
    }

    /** A rectangle (0..1 of the picture, grown by [pad] of its size on every side) cut out of ARGB pixels: (w, h, pixels). */
    fun crop(width: Int, height: Int, argb: IntArray, l: Float, t: Float, r: Float, b: Float, pad: Float = 0.3f): Triple<Int, Int, IntArray>? {
        val pw = (r - l) * pad; val ph = (b - t) * pad
        val x0 = ((l - pw) * width).toInt().coerceIn(0, width - 1)
        val y0 = ((t - ph) * height).toInt().coerceIn(0, height - 1)
        val x1 = ((r + pw) * width).toInt().coerceIn(x0 + 1, width)
        val y1 = ((b + ph) * height).toInt().coerceIn(y0 + 1, height)
        val w = x1 - x0; val h = y1 - y0
        if (w < 2 || h < 2) return null
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = argb[(y0 + y) * width + x0 + x]
        return Triple(w, h, out)
    }
}
