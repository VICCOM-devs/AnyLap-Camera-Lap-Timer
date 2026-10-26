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
}
