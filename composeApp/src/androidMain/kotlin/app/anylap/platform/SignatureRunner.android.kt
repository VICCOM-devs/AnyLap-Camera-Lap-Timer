package app.anylap.platform

import app.anylap.vision.SignatureOut

import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** LiteRT (TensorFlow Lite) runner for `carnet.tflite`: input 1×3×S×S or 1×S×S×3 float 0..1, outputs signature / count / nose. */
actual class SignatureRunner actual constructor() {
    private var interpreter: Interpreter? = null
    private var channelsFirst = true
    private var size = 64
    private var sigIndex = 0
    private var countIndex = 1
    private var noseIndex = 2
    private var inputBuffer: ByteBuffer? = null

    actual val inputSize: Int get() = size

    actual fun load(path: String): Boolean = try {
        close()
        val f = File(path)
        val mapped = f.inputStream().channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) }
        val opts = Interpreter.Options().apply { numThreads = 2 }
        val it = Interpreter(mapped, opts)
        val shape = it.getInputTensor(0).shape() // [1,3,S,S] (torch export) or [1,S,S,3]
        channelsFirst = shape.size == 4 && shape[1] == 3 && shape[3] != 3
        size = if (channelsFirst) shape[2] else shape[1]
        // find the outputs by size: 64 = signature, 3 = count, 2 = nose (export names them, order may vary)
        for (i in 0 until it.outputTensorCount) {
            val n = it.getOutputTensor(i).shape().fold(1) { a, b -> a * b }
            when (n) { 3 -> countIndex = i; 2 -> noseIndex = i; else -> sigIndex = i }
        }
        inputBuffer = ByteBuffer.allocateDirect(4 * 3 * size * size).order(ByteOrder.nativeOrder())
        interpreter = it
        true
    } catch (t: Throwable) {
        println("AnyLap model: load failed: $t")
        close()
        false
    }

    actual fun run(rgb: FloatArray): SignatureOut? {
        val it = interpreter ?: return null
        val buf = inputBuffer ?: return null
        if (rgb.size != size * size * 3) return null
        buf.rewind()
        val fb = buf.asFloatBuffer()
        if (channelsFirst) {
            val plane = size * size
            for (c in 0 until 3) for (p in 0 until plane) fb.put(rgb[p * 3 + c])
        } else {
            fb.put(rgb)
        }
        val sigLen = it.getOutputTensor(sigIndex).shape().fold(1) { a, b -> a * b }
        val sig = Array(1) { FloatArray(sigLen) }
        val count = Array(1) { FloatArray(3) }
        val nose = Array(1) { FloatArray(2) }
        val outputs = HashMap<Int, Any>()
        outputs[sigIndex] = sig; outputs[countIndex] = count; outputs[noseIndex] = nose
        return try {
            buf.rewind()
            it.runForMultipleInputsOutputs(arrayOf<Any>(buf), outputs)
            // the exported net returns logits for count; the signature is already L2-normalised
            val c = count[0]
            val m = c.max()
            var z = 0f
            val soft = FloatArray(3) { i -> kotlin.math.exp((c[i] - m).toDouble()).toFloat().also { z += it } }
            for (i in 0 until 3) soft[i] /= z
            SignatureOut(sig[0], soft, nose[0])
        } catch (t: Throwable) {
            println("AnyLap model: run failed: $t")
            null
        }
    }

    actual fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        interpreter = null
        inputBuffer = null
    }
}
