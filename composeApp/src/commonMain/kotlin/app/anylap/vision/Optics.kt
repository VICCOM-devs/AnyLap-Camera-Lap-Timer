package app.anylap.vision

import kotlin.math.tan

object Optics {
    /** Metres covered by one pixel at [distanceM], for an image [imageWidthPx] wide. */
    fun metersPerPixel(distanceM: Float, hfovRad: Float, imageWidthPx: Int): Float =
        (2.0 * distanceM * tan(hfovRad / 2.0) / imageWidthPx).toFloat()

    /** Width of the scene at the finish line, in metres. */
    fun sceneWidthM(distanceM: Float, hfovRad: Float): Float = (2.0 * distanceM * tan(hfovRad / 2.0)).toFloat()
}
