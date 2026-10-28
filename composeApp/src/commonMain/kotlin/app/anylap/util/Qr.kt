package app.anylap.util

/**
 * Minimal QR Code encoder (byte mode, error correction M, versions 1–10, automatic mask) – enough for
 * a join link. After Nayuki's reference implementation (MIT). [encode] returns the modules, true = dark.
 */
object Qr {
    class Code(val size: Int, private val m: Array<BooleanArray>) {
        operator fun get(x: Int, y: Int) = m[y][x]
    }

    // ECC level M: codewords per block and number of blocks, versions 1..10
    private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    private fun rawModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val na = ver / 7 + 2
            r -= (25 * na - 10) * na - 55
            if (ver >= 7) r -= 36
        }
        return r
    }
    private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver - 1] * BLOCKS[ver - 1]

    fun encode(text: String): Code {
        val data = text.encodeToByteArray()
        val ver = (1..10).firstOrNull { v -> 4 + (if (v < 10) 8 else 16) + data.size * 8 <= dataCodewords(v) * 8 }
            ?: error("text too long for a QR code here")
        // bit stream
        val bits = ArrayList<Boolean>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits += (v ushr i) and 1 != 0 }
        put(0x4, 4); put(data.size, if (ver < 10) 8 else 16)
        for (b in data) put(b.toInt() and 0xFF, 8)
        val cap = dataCodewords(ver) * 8
        put(0, minOf(4, cap - bits.size))
        put(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < cap) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
        val dataBytes = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { a, k -> (a shl 1) or (if (bits[i * 8 + k]) 1 else 0) } }
        val all = interleave(ver, dataBytes)
        // modules
        val size = ver * 4 + 17
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }
        fun set(x: Int, y: Int, d: Boolean) { m[y][x] = d; fn[y][x] = true }
        for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(x, y, d != 2 && d != 4)
            }
        }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
        val align = alignPositions(ver)
        for (i in align.indices) for (j in align.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
        drawFormat(m, fn, size, 0)   // reserve
        if (ver >= 7) drawVersion(m, fn, size, ver)
        // data
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val up = ((right + 1) and 2) == 0
                val y = if (up) size - 1 - vert else vert
                if (!fn[y][x] && i < all.size * 8) {
                    m[y][x] = (all[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                    i++
                }
            }
            right -= 2
        }
        // best mask
        var best = 0; var bestPen = Int.MAX_VALUE
        for (mask in 0 until 8) {
            applyMask(m, fn, size, mask); drawFormat(m, fn, size, mask)
            val p = penalty(m, size)
            if (p < bestPen) { bestPen = p; best = mask }
            applyMask(m, fn, size, mask)
        }
        applyMask(m, fn, size, best); drawFormat(m, fn, size, best)
        return Code(size, m)
    }

    private fun alignPositions(ver: Int): IntArray {
        if (ver == 1) return IntArray(0)
        val n = ver / 7 + 2
        val step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2
        val r = IntArray(n); r[0] = 6
        var pos = ver * 4 + 10
        for (k in n - 1 downTo 1) { r[k] = pos; pos -= step }
        return r
    }

    private fun interleave(ver: Int, data: IntArray): IntArray {
        val nb = BLOCKS[ver - 1]; val ecl = ECC_PER_BLOCK[ver - 1]
        val raw = rawModules(ver) / 8
        val shortBlocks = nb - raw % nb
        val shortLen = raw / nb
        val gen = rsGenerator(ecl)
        val blocks = ArrayList<IntArray>()
        var k = 0
        for (b in 0 until nb) {
            val dl = shortLen - ecl + (if (b < shortBlocks) 0 else 1)
            val d = data.copyOfRange(k, k + dl); k += dl
            val ecc = rsRemainder(d, gen)
            blocks += (if (b < shortBlocks) d + intArrayOf(0) + ecc else d + ecc)
        }
        val out = ArrayList<Int>()
        for (i in 0 until blocks[0].size) for ((b, blk) in blocks.withIndex()) {
            if (i != shortLen - ecl || b >= shortBlocks) out += blk[i]
        }
        return out.toIntArray()
    }

    private fun mul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); if ((y ushr i) and 1 != 0) z = z xor x }
        return z
    }
    private fun rsGenerator(deg: Int): IntArray {
        val r = IntArray(deg); r[deg - 1] = 1
        var root = 1
        for (i in 0 until deg) {
            for (j in 0 until deg) { r[j] = mul(r[j], root); if (j + 1 < deg) r[j] = r[j] xor r[j + 1] }
            root = mul(root, 2)
        }
        return r
    }
    private fun rsRemainder(data: IntArray, gen: IntArray): IntArray {
        val r = IntArray(gen.size)
        for (b in data) {
            val f = b xor r[0]
            for (i in 0 until r.size - 1) r[i] = r[i + 1]
            r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor mul(gen[i], f)
        }
        return r
    }

    private fun drawFormat(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int, mask: Int) {
        val data = (0 shl 3) or mask   // ECC M = 0b00
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 != 0
        fun set(x: Int, y: Int, d: Boolean) { m[y][x] = d; fn[y][x] = true }
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }

    private fun drawVersion(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int, ver: Int) {
        var rem = ver
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (ver shl 12) or rem
        for (i in 0 until 18) {
            val d = (bits ushr i) and 1 != 0
            val a = size - 11 + i % 3; val b = i / 3
            m[b][a] = d; fn[b][a] = true; m[a][b] = d; fn[a][b] = true
        }
    }

    private fun applyMask(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int, mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            if (fn[y][x]) continue
            val inv = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (inv) m[y][x] = !m[y][x]
        }
    }

    private fun penalty(m: Array<BooleanArray>, size: Int): Int {
        var p = 0
        fun line(get: (Int) -> Boolean) {
            var run = 1
            for (i in 1 until size) {
                if (get(i) == get(i - 1)) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1
            }
            for (i in 0..size - 7) {
                if (get(i) && !get(i + 1) && get(i + 2) && get(i + 3) && get(i + 4) && !get(i + 5) && get(i + 6)) {
                    val before = (i - 4 until i).all { it < 0 || !get(it) }
                    val after = (i + 7 until i + 11).all { it >= size || !get(it) }
                    if (before || after) p += 40
                }
            }
        }
        for (y in 0 until size) line { m[y][it] }
        for (x in 0 until size) line { m[it][x] }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
        }
        val dark = m.sumOf { r -> r.count { it } }
        val total = size * size
        p += (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total * 10 - 10
        return p
    }
}
