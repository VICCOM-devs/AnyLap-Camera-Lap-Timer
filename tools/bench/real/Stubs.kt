// Stand-ins for the app types the vision engine imports (the real ones pull in Compose/i18n).
// Values must match composeApp/src/commonMain/kotlin/app/anylap/data/Models.kt.
package app.anylap.data

enum class Sport(val title: String, val unitName: String, val maxRacers: Int, val defaultMinLapSec: Float, val minBlobAreaFrac: Float, val maxBlobAreaFrac: Float, val defaultDistanceM: Float) {
    RC("RC cars", "car", 5, 2.5f, 0.0012f, 0.12f, 2.5f),
    RUNNING("Running", "runner", 10, 15f, 0.004f, 0.6f, 5f),
}
enum class Direction { BOTH, FORWARD, REVERSE }
enum class FinishEdge(val title: String, val detail: String) { FRONT("", ""), REAR("", ""), CENTRE("", "") }
data class LineSpec(val x1: Float = 0.5f, val y1: Float = 0.1f, val x2: Float = 0.5f, val y2: Float = 0.9f)
