package app.anylap.cloud

import app.anylap.data.SessionConfig
import app.anylap.engine.RaceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What spectators see: the timing tower only (names, numbers, colours, laps, times).
 * No photos, no video, no fingerprints, no account ids.
 */
object LiveSnapshot {
    fun build(title: String, config: SessionConfig, race: RaceState, speedKmh: Boolean): JsonObject = buildJsonObject {
        put("v", 1)
        put("title", title)
        put("sport", config.sport.name)
        put("mode", config.mode.name)
        put("targetLaps", config.targetLaps)
        put("timeLimitSec", config.timeLimitSec)
        put("phase", race.phase.name)
        put("elapsedMs", race.elapsedMs)
        put("leaderLaps", race.leaderLaps)
        put("speedUnit", if (speedKmh) "km/h" else "mph")
        putJsonArray("rows") {
            race.standings.forEach { s ->
                addJsonObject {
                    put("name", s.racer.name)
                    put("number", s.racer.number)
                    put("color", "#" + (s.racer.color and 0xFFFFFF).toString(16).padStart(6, '0'))
                    put("pos", s.position)
                    put("laps", s.laps)
                    s.lastLapMs?.let { put("lastMs", it) }
                    s.bestLapMs?.let { put("bestMs", it) }
                    s.lastSpeedKmh?.let { put("speed", if (speedKmh) it else it / 1.609344f) }
                    put("gap", s.gap)
                    put("finished", s.finished)
                    put("fastest", s.hasFastestLap)
                }
            }
        }
    }

    /** The part that changes when something meaningful happens (not every clock tick). */
    fun key(race: RaceState): String =
        race.phase.name + "|" + race.standings.joinToString(";") { "${it.racer.id}:${it.position}:${it.laps}:${it.lastLapMs}:${it.finished}" }
}

