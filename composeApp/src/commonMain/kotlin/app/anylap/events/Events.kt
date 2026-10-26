package app.anylap.events

import app.anylap.data.RaceMode
import app.anylap.data.Session
import app.anylap.data.Sport
import kotlinx.serialization.Serializable

@Serializable
enum class RoundKind(val title: String) { HEAT("Heat"), FINAL("Final") }

/** One race inside an event. [sessionId] is set once it has been run. */
@Serializable
data class Round(
    val id: String,
    val name: String,
    val kind: RoundKind,
    val racerIds: List<String>,
    val sessionId: String? = null,
)

/**
 * A race day: heats that score points, then optionally a final seeded from the points table.
 * Every round uses the same format ([mode], [targetLaps] / [timeLimitSec]).
 */
@Serializable
data class Event(
    val id: String,
    val name: String,
    val sport: Sport,
    val createdAt: Long,
    val racerIds: List<String>,
    val mode: RaceMode = RaceMode.LAPS,
    val targetLaps: Int = 10,
    val timeLimitSec: Int = 300,
    /** Points for P1, P2, … Positions beyond the list score 0. */
    val points: List<Int> = DEFAULT_POINTS,
    /** Finals count double – the classic "finals decide it" rule. */
    val finalMultiplier: Int = 2,
    val rounds: List<Round> = emptyList(),
) {
    val finished: Boolean get() = rounds.isNotEmpty() && rounds.all { it.sessionId != null }
    val hasFinal: Boolean get() = rounds.any { it.kind == RoundKind.FINAL }

    companion object {
        val DEFAULT_POINTS = listOf(10, 8, 6, 5, 4, 3, 2, 1)
    }
}

data class EventStanding(
    val racerId: String,
    val position: Int,
    val points: Int,
    val wins: Int,
    val roundsRun: Int,
    val bestLapMs: Long?,
)

/** Pure event rules, unit-tested in commonTest. */
object EventLogic {

    /**
     * Splits racers into the fewest heats of at most [maxPerHeat], as even as possible,
     * dealing them out like cards so heats mix (1,3,5… / 2,4,6…).
     * Each racer gets [rounds] heats; later rounds rotate who races whom.
     */
    fun heats(racerIds: List<String>, maxPerHeat: Int, rounds: Int = 1): List<List<String>> {
        require(maxPerHeat >= 1)
        if (racerIds.isEmpty()) return emptyList()
        val groups = (racerIds.size + maxPerHeat - 1) / maxPerHeat
        val out = ArrayList<List<String>>()
        for (r in 0 until rounds) {
            val rotated = racerIds.drop(r % racerIds.size) + racerIds.take(r % racerIds.size)
            val buckets = List(groups) { ArrayList<String>() }
            rotated.forEachIndexed { i, id -> buckets[i % groups].add(id) }
            out += buckets
        }
        return out
    }

    fun pointsFor(position: Int, event: Event, kind: RoundKind): Int {
        val base = event.points.getOrElse(position - 1) { 0 }
        return if (kind == RoundKind.FINAL) base * event.finalMultiplier else base
    }

    /** Points table. Ties broken by wins, then best lap, then name order of [Event.racerIds]. */
    fun standings(event: Event, sessions: List<Session>): List<EventStanding> {
        val byId = sessions.associateBy { it.id }
        val points = HashMap<String, Int>()
        val wins = HashMap<String, Int>()
        val run = HashMap<String, Int>()
        val best = HashMap<String, Long>()
        for (round in event.rounds) {
            val s = round.sessionId?.let { byId[it] } ?: continue
            s.order.forEachIndexed { i, id ->
                if (id !in event.racerIds) return@forEachIndexed
                points[id] = (points[id] ?: 0) + pointsFor(i + 1, event, round.kind)
                run[id] = (run[id] ?: 0) + 1
                if (i == 0) wins[id] = (wins[id] ?: 0) + 1
            }
            s.laps.forEach { l -> if (l.racerId in event.racerIds) best[l.racerId] = minOf(best[l.racerId] ?: Long.MAX_VALUE, l.timeMs) }
        }
        val sorted = event.racerIds.sortedWith(
            compareByDescending<String> { points[it] ?: 0 }
                .thenByDescending { wins[it] ?: 0 }
                .thenBy { best[it] ?: Long.MAX_VALUE }
                .thenBy { event.racerIds.indexOf(it) },
        )
        return sorted.mapIndexed { i, id ->
            EventStanding(id, i + 1, points[id] ?: 0, wins[id] ?: 0, run[id] ?: 0, best[id])
        }
    }

    /** Final grid: top [size] of the points table after the heats, best qualifier first. */
    fun finalGrid(event: Event, sessions: List<Session>, size: Int): List<String> =
        standings(event.copy(rounds = event.rounds.filter { it.kind == RoundKind.HEAT }), sessions)
            .take(size).map { it.racerId }

    /** The next round that hasn't been run, in order. */
    fun nextRound(event: Event): Round? = event.rounds.firstOrNull { it.sessionId == null }
}
