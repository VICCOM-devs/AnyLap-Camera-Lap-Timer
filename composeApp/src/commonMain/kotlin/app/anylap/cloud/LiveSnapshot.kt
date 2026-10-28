package app.anylap.cloud

import app.anylap.data.SessionConfig
import app.anylap.engine.RaceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What viewers see: the timing tower (names, numbers, colours, laps, lap times, gaps). A Pro camera
 * also sends each car's expanded stats ("stats"); the server passes them on only to Pro viewers, and
 * only for cars whose owner is Pro. "owner" (a claimed car's account) is read by the server and never
 * passed on. No photos, no video, no fingerprints.
 */
object LiveSnapshot {
    fun build(title: String, config: SessionConfig, race: RaceState, speedKmh: Boolean, pro: Boolean = false): JsonObject = buildJsonObject {
        put("v", 2)
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
                    put("gap", s.gap)
                    put("finished", s.finished)
                    put("fastest", s.hasFastestLap)
                    s.racer.ownerUserId?.let { put("owner", it) }
                    val mine = race.laps.filter { it.racerId == s.racer.id }.sortedBy { it.number }
                    putJsonArray("lapTimes") { mine.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.timeMs)) } }
                    if (pro) putJsonObject("stats") {
                        val t = mine.map { it.timeMs.toDouble() }
                        if (t.isNotEmpty()) {
                            val avg = t.average()
                            put("avgMs", avg.toLong())
                            put("sdMs", kotlin.math.sqrt(t.sumOf { (it - avg) * (it - avg) } / t.size).toLong())
                        }
                        val sp = mine.mapNotNull { it.speedKmh }.map { if (speedKmh) it else it / 1.609344f }
                        sp.maxOrNull()?.let { put("topSpeed", it) }
                        putJsonArray("speeds") { sp.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    }
                }
            }
        }
    }

    /** The part that changes when something meaningful happens (not every clock tick). */
    fun key(race: RaceState): String =
        race.phase.name + "|" + race.standings.joinToString(";") { "${it.racer.id}:${it.position}:${it.laps}:${it.lastLapMs}:${it.finished}" }
}

