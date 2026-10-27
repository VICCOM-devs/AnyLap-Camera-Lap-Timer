package app.anylap.engine

import app.anylap.data.CrossRec
import app.anylap.data.Lap
import app.anylap.data.LapSource
import app.anylap.data.RaceMode
import app.anylap.data.ReviewItem
import app.anylap.data.Session
import app.anylap.data.SessionConfig
import kotlin.math.abs

/** Laps and finishing order from counted crossings – the same rules [RaceEngine] applies live. */
object LapMath {
    fun isRace(config: SessionConfig) = config.mode != RaceMode.PRACTICE

    /** The crossings that count for a racer under the session's format. */
    fun used(config: SessionConfig, list: List<CrossRec>): List<CrossRec> = when (config.mode) {
        RaceMode.PRACTICE -> list
        RaceMode.LAPS -> list.take(config.targetLaps)
        RaceMode.TIME -> {
            val limitMs = config.timeLimitSec * 1000L
            val firstAfter = list.indexOfFirst { it.atMs >= limitMs }
            if (firstAfter >= 0) list.take(firstAfter + 1) else list
        }
    }

    fun laps(config: SessionConfig, racerId: String, list: List<CrossRec>): List<Lap> {
        val out = ArrayList<Lap>()
        var prev: Long? = if (isRace(config)) 0L else null
        for (c in used(config, list.sortedBy { it.atMs })) {
            val p = prev
            if (p != null) out += Lap(racerId, out.size + 1, c.atMs - p, c.atMs, c.speedKmh, c.source, c.confidence)
            prev = c.atMs
        }
        return out
    }

    /** Racer ids in standings order (races: laps then last crossing; practice: best lap then laps). */
    fun order(config: SessionConfig, racerIds: List<String>, crossings: Map<String, List<CrossRec>>): List<String> {
        data class Row(val id: String, val laps: List<Lap>, val lastMs: Long?)
        val rows = racerIds.map { id ->
            val l = laps(config, id, crossings[id] ?: emptyList())
            Row(id, l, used(config, (crossings[id] ?: emptyList()).sortedBy { it.atMs }).lastOrNull()?.atMs)
        }
        val ordered = if (isRace(config)) {
            rows.sortedWith(compareByDescending<Row> { it.laps.size }.thenBy { it.lastMs ?: Long.MAX_VALUE })
        } else {
            rows.sortedWith(compareBy<Row> { it.laps.minOfOrNull { l -> l.timeMs } ?: Long.MAX_VALUE }.thenByDescending { it.laps.size })
        }
        return ordered.filter { it.laps.isNotEmpty() || isRace(config) }.map { it.id }
    }

    /** A session with its laps and order rebuilt from [Session.crossings]. */
    fun rebuilt(s: Session): Session {
        val ids = s.racers.map { it.id }
        val laps = ids.flatMap { laps(s.config, it, s.crossings[it] ?: emptyList()) }
        return s.copy(
            laps = laps,
            order = order(s.config, ids, s.crossings),
            unassignedCrossings = s.reviews.count { (it.kind == app.anylap.data.ReviewKind.UNKNOWN || it.kind == app.anylap.data.ReviewKind.NOT_COUNTED) && it.open },
        )
    }

    /** Who the crossing at [atMs] is counted for now (null = nobody). */
    fun ownerAt(s: Session, atMs: Long): String? =
        s.crossings.entries.firstOrNull { (_, l) -> l.any { abs(it.atMs - atMs) <= 2 } }?.key

    /**
     * Re-names the crossing at [atMs] (one car in a review picture): taken from whoever it is
     * counted for, given to [to] (a racer id, or "" = not a car), laps and order rebuilt, and the
     * card marked as checked.
     */
    fun reassign(s: Session, itemId: String, atMs: Long, to: String): Session {
        val minLapMs = (s.config.minLapSec * 1000).toLong()
        val cr = s.crossings.mapValues { it.value.toMutableList() }.toMutableMap()
        val old = cr.values.flatten().firstOrNull { abs(it.atMs - atMs) <= 2 }
        for (l in cr.values) l.removeAll { abs(it.atMs - atMs) <= 2 }
        if (to.isNotEmpty() && s.racers.any { it.id == to }) {
            val list = cr.getOrPut(to) { ArrayList() }
            if (list.none { abs(it.atMs - atMs) < minLapMs }) {
                list += CrossRec(atMs, old?.speedKmh, LapSource.ASSIGNED, 1f)
                list.sortBy { it.atMs }
            }
        }
        val reviews = s.reviews.map { if (it.id == itemId) it.copy(checked = true, resolvedTo = if (abs(it.atMs - atMs) <= 2) to else it.resolvedTo) else it }
        return rebuilt(s.copy(crossings = cr, reviews = reviews))
    }

    /** "Looks right": the card is done, nothing changes. */
    fun check(s: Session, itemId: String): Session =
        s.copy(reviews = s.reviews.map { if (it.id == itemId) it.copy(checked = true) else it })

    /**
     * Applies a human verdict on a review item: [to] = a racer id, or "" for "not a car". Removes the
     * crossing from whoever the app had counted it for, adds it to the chosen racer (unless that racer
     * already has one within the minimum lap time), and rebuilds laps and order.
     */
    fun resolve(s: Session, itemId: String, to: String): Session {
        val item = s.reviews.firstOrNull { it.id == itemId } ?: return s
        val minLapMs = (s.config.minLapSec * 1000).toLong()
        val cr = s.crossings.mapValues { it.value.toMutableList() }.toMutableMap()
        val current = item.resolvedTo?.takeIf { it.isNotEmpty() } ?: item.decided?.takeIf { item.resolvedTo == null }
        if (current != null) cr[current]?.removeAll { abs(it.atMs - item.atMs) < 50 }
        if (to.isNotEmpty() && s.racers.any { it.id == to }) {
            val list = cr.getOrPut(to) { ArrayList() }
            if (list.none { abs(it.atMs - item.atMs) < minLapMs }) {
                list += CrossRec(item.atMs, item.speedKmh, LapSource.ASSIGNED, 1f)
                list.sortBy { it.atMs }
            }
        }
        val reviews = s.reviews.map { if (it.id == itemId) it.copy(resolvedTo = to) else it }
        return rebuilt(s.copy(crossings = cr, reviews = reviews))
    }
}
