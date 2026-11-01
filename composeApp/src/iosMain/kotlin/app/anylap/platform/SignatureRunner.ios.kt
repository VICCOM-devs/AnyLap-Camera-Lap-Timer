package app.anylap.platform

import app.anylap.vision.SignatureOut

/** No on-device runtime on iOS yet (Android-first rule): the app runs without the model, as before. */
actual class SignatureRunner actual constructor() {
    actual val inputSize: Int get() = 64
    actual fun load(path: String): Boolean = false
    actual fun run(rgb: FloatArray): SignatureOut? = null
    actual fun close() {}
}
