package app.anylap.platform

import app.anylap.vision.SignatureOut

/**
 * Runs the exported recognition model (`carnet.tflite`) on one square RGB crop. Android = LiteRT;
 * iOS = not available yet (Android-first rule), [load] returns false and the app behaves as before.
 */
expect class SignatureRunner() {
    /** Loads the model file; false when the platform has no runtime or the file is not a valid model. */
    fun load(path: String): Boolean

    /** Side of the square input in pixels (64 or 96), valid after [load]. */
    val inputSize: Int

    /** [rgb] = inputSize × inputSize × 3 floats in 0..1, row-major, channels last. Null when not loaded or the run failed. */
    fun run(rgb: FloatArray): SignatureOut?

    fun close()
}

