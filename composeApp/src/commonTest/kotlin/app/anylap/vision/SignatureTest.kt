package app.anylap.vision

import app.anylap.util.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignatureTest {
    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        // 1000 bytes (i % 251): crosses several 64-byte blocks and the padding boundary
        assertEquals("4e4c294b331f7a2099a379bec34b9f9fc03dc46ab465d998f4d683da53487e6d", Sha256.hex(ByteArray(1000) { (it % 251).toByte() }))
    }

    @Test
    fun cropIsSquareAroundTheBoxAndChannelsLast() {
        // 100 × 50 picture: left half pure red, right half pure blue
        val w = 100; val h = 50
        val argb = IntArray(w * h) { i -> if (i % w < 50) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        // a 20 × 10 box in the middle of the red half → crop side 32 px, all red
        val red = cropForSignature(argb, w, h, 0.10f, 0.40f, 0.30f, 0.60f, 8)
        assertEquals(8 * 8 * 3, red.size)
        for (i in 0 until 64) { assertEquals(1f, red[i * 3], 1e-5f); assertEquals(0f, red[i * 3 + 1], 1e-5f); assertEquals(0f, red[i * 3 + 2], 1e-5f) }
        // a box straddling the middle → left columns red, right columns blue
        val mid = cropForSignature(argb, w, h, 0.40f, 0.40f, 0.60f, 0.60f, 8)
        assertEquals(1f, mid[(0 * 8 + 0) * 3], 1e-5f)      // top-left pixel is red
        assertEquals(1f, mid[(0 * 8 + 7) * 3 + 2], 1e-5f)  // top-right pixel is blue
        // outside the picture clamps to the edge instead of crashing
        val edge = cropForSignature(argb, w, h, 0.95f, 0.9f, 1.2f, 1.3f, 4)
        assertTrue(edge.all { it in 0f..1f })
    }

    @Test
    fun similarityOfNormalisedSignatures() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(1f, signatureSimilarity(a, a), 1e-6f)
        assertEquals(0f, signatureSimilarity(a, b), 1e-6f)
    }
}
