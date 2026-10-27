package app.anylap.engine

import kotlin.math.abs

/**
 * Lap timing as a second opinion on *who* crossed.
 *
 * The camera names each crossing by appearance. A name can still be wrong – a piece of a two-tone
 * car cut out of a pair of touching cars looks like another car, a grey car can pass for a white
 * one – and once given it used to stand: the wrongly named car got a 5-second lap, the real one
 * lost a lap. Here every racer has a pace, and two crossings of the same racer closer together
 * than it can possibly lap ([window]) are a conflict:
 *
 * - the crossing that looks more like the racer *and* fits its pace keeps the name (a hand-assigned
 *   one always does);
 * - the other one is re-named to its best runner-up if that car looks almost as convincing
 *   ([RELOCATE_MIN], or [RELOCATE_PACE_MIN] when it arrives right on its own pace), is *due* (about
 *   one of its laps since it was last seen) and has no crossing of its own nearby;
 * - otherwise it is parked: not counted for anybody, and put in front of a human.
 *
 * Pure logic on plain lists, so the bench runs exactly what the app runs.
 */
class LapIdentity(private val minLapNs: Long) {

    /** One crossing. [locked] = a human or a bib decided it; never moved by timing. */
    class Entry(
        val ns: Long,
        val candidates: List<Pair<String, Float>>,
        val locked: Boolean = false,
        val payload: Any? = null,
    ) {
        fun score(racerId: String): Float = candidates.firstOrNull { it.first == racerId }?.second ?: 0f
    }

    /** What happened to a crossing: counted for [to], or parked when [to] is null. [from] = previous owner. */
    data class Change(val entry: Entry, val from: String?, val to: String?, val reason: Reason)

    enum class Reason {
        /** Counted as named. */
        NAMED,
        /** Lost a conflict and was given to the runner-up that was due. */
        RENAMED,
        /** Lost a conflict and no other car fits: not counted. */
        PARKED,
        /** Displaced an earlier, weaker crossing of the same name. */
        REPLACED,
    }

    private val lists = LinkedHashMap<String, MutableList<Entry>>()

    fun crossings(racerId: String): List<Entry> = lists[racerId] ?: emptyList()

    fun racers(): Set<String> = lists.keys

    fun register(racerId: String) { lists.getOrPut(racerId) { ArrayList() } }

    fun remove(racerId: String, e: Entry) { lists[racerId]?.remove(e) }

    /** Adds [e] without any conflict check (restored or manual data). */
    fun force(racerId: String, e: Entry) {
        val l = lists.getOrPut(racerId) { ArrayList() }
        l += e; l.sortBy { it.ns }
    }

    /**
     * A crossing named [racerId] by the camera. Returns every change it caused (the new crossing,
     * and possibly an older one that was re-named or parked), in order.
     */
    fun place(racerId: String, e: Entry): List<Change> {
        val list = lists.getOrPut(racerId) { ArrayList() }
        val w = window(racerId)
        val clash = list.filter { abs(it.ns - e.ns) < w }.minByOrNull { abs(it.ns - e.ns) }
        if (clash == null) {
            list += e; list.sortBy { it.ns }
            return listOf(Change(e, null, racerId, Reason.NAMED))
        }
        // Which of the two is the racer? How much it looks like it, less how badly it fits the racer's
        // pace (time since its crossing before both, against its median lap).
        val med = medianLap(racerId)
        val prev = list.filter { it !== clash && it.ns < minOf(e.ns, clash.ns) }.maxByOrNull { it.ns }
        fun fit(x: Entry): Float =
            if (med == null || prev == null) 0f else (abs((x.ns - prev.ns) - med).toDouble() / med).toFloat().coerceAtMost(1f)
        val newWins = !clash.locked && (e.locked || e.score(racerId) - FIT_WEIGHT * fit(e) > clash.score(racerId) - FIT_WEIGHT * fit(clash))
        val out = ArrayList<Change>()
        val loser: Entry
        if (newWins) {
            list.remove(clash)
            list += e; list.sortBy { it.ns }
            out += Change(e, null, racerId, Reason.REPLACED)
            loser = clash
        } else {
            loser = e
        }
        out += relocate(loser, from = if (newWins) racerId else null, exclude = racerId)
        return out
    }

    private fun relocate(e: Entry, from: String?, exclude: String): Change {
        for ((c, s) in e.candidates.sortedByDescending { it.second }) {
            if (c == exclude) continue
            if (s < RELOCATE_PACE_MIN) break
            val l = lists[c] ?: continue
            if (!isDue(c, e.ns)) continue
            // a slightly weaker match is enough when the car arrives right on its own pace
            if (s < RELOCATE_MIN && !onPace(c, e.ns)) continue
            if (l.any { abs(it.ns - e.ns) < window(c) }) continue
            l += e; l.sortBy { it.ns }
            return Change(e, from, c, Reason.RENAMED)
        }
        return Change(e, from, null, Reason.PARKED)
    }

    /**
     * Two crossings of [racerId] closer together than this are impossible: [CONFLICT_FRAC] of its
     * typical lap ([medianLap]), never less than the sport's minimum lap.
     */
    fun window(racerId: String): Long {
        val med = medianLap(racerId) ?: return minLapNs
        return maxOf(minLapNs, (CONFLICT_FRAC * med).toLong())
    }

    /** About one of its laps has passed since [racerId] was last seen before [ns] (or never seen). */
    fun isDue(racerId: String, ns: Long): Boolean {
        val last = lists[racerId]?.lastOrNull { it.ns < ns } ?: return true
        val med = medianLap(racerId) ?: return ns - last.ns >= minLapNs
        return ns - last.ns >= (DUE_FRAC * med).toLong()
    }

    /** [racerId]'s last crossing before [ns] was about one typical lap earlier (within [PACE_TOL]). */
    fun onPace(racerId: String, ns: Long): Boolean {
        val last = lists[racerId]?.lastOrNull { it.ns < ns } ?: return false
        val med = medianLap(racerId) ?: return false
        return abs((ns - last.ns) - med) <= PACE_TOL * med
    }

    /** [ids] with the racers that are due at [ns] first (keeping their order) – suggestions for an unknown car. */
    fun dueFirst(ids: List<String>, ns: Long): List<String> = ids.filter { isDue(it, ns) } + ids.filterNot { isDue(it, ns) }

    /**
     * A racer's typical lap, kept low on purpose: the lower quartile of its laps (a missed crossing
     * makes a double lap, a spin a long one – neither may widen the "impossible" window), and never
     * more than the field's median. Needs three laps of its own; until then the field's.
     */
    private fun medianLap(racerId: String): Long? {
        val l = lists[racerId] ?: return null
        val field = fieldMedianLap()
        if (l.size < 4) return field
        val laps = l.zipWithNext { a, b -> b.ns - a.ns }.sorted()
        val q1 = laps[(laps.size - 1) / 4]
        return if (field != null) minOf(q1, field) else q1
    }

    private fun fieldMedianLap(): Long? {
        val laps = lists.values.flatMap { l -> l.zipWithNext { a, b -> b.ns - a.ns } }.sorted()
        return if (laps.size < 3) null else laps[(laps.size - 1) / 2]
    }

    companion object {
        /** Crossings of one racer closer than this share of its median lap are a conflict. */
        const val CONFLICT_FRAC = 0.6
        /** A racer is due again after this share of its median lap. */
        const val DUE_FRAC = 0.8
        /** A conflict loser is only re-named to a runner-up that matches at least this well. */
        const val RELOCATE_MIN = 0.85f
        /** …or this well, when the runner-up arrives right on its own pace. */
        const val RELOCATE_PACE_MIN = 0.78f
        /** "On pace" = within this share of its typical lap. */
        const val PACE_TOL = 0.1
        /** Pace weight in a conflict: a crossing half a lap off the racer's pace loses 0.25 of match. */
        const val FIT_WEIGHT = 0.5f
    }
}
