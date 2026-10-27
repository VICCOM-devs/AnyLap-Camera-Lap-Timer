package app.anylap.engine

import kotlin.math.abs

/**
 * Race-level identity: each car's own lap rhythm as a second cue next to its appearance.
 *
 * The camera names a crossing by how it looks; that name is never revisited by the analyzer. Here
 * every crossing of the race is looked at again with timing in mind – a car can't complete a lap in
 * half its usual time, and a car whose lap is due and hasn't shown up is the likely owner of an
 * unnamed or doubtful crossing near that moment. Pure and deterministic: [solve] is re-run on the
 * whole list after every crossing (live) and once more at the end, so the live view and the saved
 * result follow the same rules. Human verdicts ([In.locked]) always win.
 *
 * Rules, in order:
 * 1. A name that fits the car's rhythm is kept.
 * 2. **Conflict** – a car named again far too soon (< [EARLY] of its pace): keep whichever of the two
 *    crossings fits better (appearance), and give the other to its best runner-up that is *due*, or
 *    leave it uncounted for the Check tray.
 * 3. **Unnamed crossing** – given to the only candidate that looks like it (≥ [NAME_MIN]) *and* is due.
 * 4. **Check-line rescue** – a car seen leaving the line area uncounted is counted if it is due and
 *    looks like itself (≥ [RESCUE_MIN]).
 * 5. **Missing lap** – a car whose lap is overdue by > [MISSING] of its pace takes the doubtful crossing
 *    (unnamed, uncounted, gated out, or a blob of two cars) nearest its expected time, if it is one
 *    of that crossing's candidates.
 */
object LapIdentity {
    class In(
        val ns: Long,
        /** The analyzer's name, null when it couldn't name it. */
        val named: String?,
        /** Racers by appearance similarity, best first. */
        val cands: List<Pair<String, Float>>,
        val margin: Float = 1f,
        val split: Boolean = false,
        /** Reconstructed from the check line (not seen on the finish line). */
        val rescue: Boolean = false,
        /** Turned down by the appearance gate. */
        val gated: Boolean = false,
        /** Blob size ÷ one racer's size at the crossing (0 = unknown). */
        val sizeRatio: Float = 0f,
        /** Human verdict: a racer id, or "" = not a car. */
        val locked: String? = null,
    )

    enum class Why { ENGINE, RENAMED_BY_TIMING, NAMED_BY_TIMING, CHECK_LINE, SECOND_IN_BLOB, MISSING_LAP, NOT_COUNTED, UNKNOWN, HUMAN, IGNORED }

    /** One decision per input ([index]); extra ones (a second car in one blob) repeat the index. */
    class Out(val index: Int, val ns: Long, val racer: String?, val why: Why, val note: String)

    const val EARLY = 0.6
    const val NAME_MIN = 0.6f
    const val ALT_MIN = 0.55f
    const val RESCUE_MIN = 0.6f
    const val TRAY_MIN = 0.45f
    const val MISSING = 1.55
    const val BLOB_TWO = 1.4f

    fun solve(xs: List<In>, racers: List<String>, startNs: Long?, minLapNs: Long, nowNs: Long): List<Out> {
        val order = xs.indices.sortedBy { xs[it].ns }
        val times = racers.associateWith { ArrayList<Pair<Long, Int>>() }.toMutableMap() // (ns, input index)
        val dec = HashMap<Int, Out>()
        val extra = ArrayList<Out>()
        fun sc(i: Int, c: String) = xs[i].cands.firstOrNull { it.first == c }?.second ?: 0f
        fun field(): Double? {
            val d = times.values.flatMap { l -> l.sortedBy { it.first }.zipWithNext { a, b -> (b.first - a.first).toDouble() } }
            return if (d.size >= 3) d.sorted()[d.size / 2] else null
        }
        fun pace(c: String): Double? {
            val l = times[c]!!.map { it.first }.sorted()
            val d = l.zipWithNext { a, b -> (b - a).toDouble() }.takeLast(5)
            return if (d.size >= 2) d.sorted()[d.size / 2] else field()
        }
        fun last(c: String, before: Long): Long? = times[c]!!.map { it.first }.filter { it < before }.maxOrNull() ?: startNs
        /** gap since the car's last crossing ÷ its pace (null = nothing to compare with). */
        fun ratio(c: String, t: Long): Double? {
            val l = last(c, t) ?: return null
            val p = pace(c) ?: return null
            return (t - l) / p
        }
        fun tooSoon(c: String, t: Long): Boolean {
            val l = times[c]!!.map { it.first }.filter { it < t }.maxOrNull()
            if (l != null && t - l < minLapNs) return true
            val r = ratio(c, t) ?: return false
            return r < EARLY && times[c]!!.isNotEmpty()
        }
        fun near(c: String, t: Long, win: Long) = times[c]!!.any { abs(it.first - t) < win }
        fun due(c: String, t: Long): Boolean {
            if (tooSoon(c, t)) return false
            val r = ratio(c, t) ?: return true
            return r in 0.8..1.9
        }
        fun put(i: Int, c: String?, why: Why, note: String) {
            dec[i] = Out(i, xs[i].ns, c, why, note)
            if (c != null) times[c]!!.add(xs[i].ns to i)
        }
        fun unput(i: Int) { val o = dec.remove(i) ?: return; if (o.racer != null) times[o.racer]!!.removeAll { it.second == i } }

        // ---------------------------------------------------------------- pass 1, in time order
        for (i in order) {
            val x = xs[i]
            if (x.locked != null) { put(i, x.locked.ifEmpty { null }, Why.HUMAN, "set by hand"); continue }
            if (x.gated) { put(i, null, Why.NOT_COUNTED, "gate turned it down"); continue }
            if (x.rescue) {
                val c = x.cands.firstOrNull()
                if (c != null && c.second >= RESCUE_MIN && c.first in times && due(c.first, x.ns) && !near(c.first, x.ns, minLapNs))
                    put(i, c.first, Why.CHECK_LINE, "seen at the check line, not counted at the finish; ${c.first} was due")
                else put(i, null, Why.IGNORED, "check line, nobody due")
                continue
            }
            val n = x.named
            if (n != null && n in times) {
                if (!tooSoon(n, x.ns)) { put(i, n, Why.ENGINE, ""); continue }
                // conflict: n already crossed too recently
                val prev = times[n]!!.filter { it.first < x.ns }.maxByOrNull { it.first }!!.second
                val altCur = x.cands.filter { it.first != n && it.second >= ALT_MIN && it.first in times && due(it.first, x.ns) && !near(it.first, x.ns, minLapNs) }.maxByOrNull { it.second }
                val prevLocked = xs[prev].locked != null
                unput(prev)
                val altPrev = if (prevLocked) null else xs[prev].cands.filter { it.first != n && it.second >= ALT_MIN && it.first in times && due(it.first, xs[prev].ns) && !near(it.first, xs[prev].ns, minLapNs) }.maxByOrNull { it.second }
                val keepPrev = sc(prev, n) + (altCur?.second ?: 0f)
                val keepCur = sc(i, n) + (altPrev?.second ?: 0f)
                if (prevLocked || keepPrev >= keepCur) {
                    put(prev, n, dec[prev]?.why ?: Why.ENGINE, "")
                    if (altCur != null) put(i, altCur.first, Why.RENAMED_BY_TIMING, "looked like $n (%.2f) but $n crossed %.1f s earlier; ${altCur.first} (%.2f) was due".format(sc(i, n), (x.ns - xs[prev].ns) / 1e9, altCur.second))
                    else put(i, null, Why.NOT_COUNTED, "looked like $n but $n crossed %.1f s earlier – no lap".format((x.ns - xs[prev].ns) / 1e9))
                } else {
                    if (altPrev != null) put(prev, altPrev.first, Why.RENAMED_BY_TIMING, "first named $n, but $n fits the later crossing better; ${altPrev.first} (%.2f) was due".format(altPrev.second))
                    else put(prev, null, Why.NOT_COUNTED, "named $n, but $n fits the crossing %.1f s later better".format((x.ns - xs[prev].ns) / 1e9))
                    put(i, n, Why.ENGINE, "")
                }
                continue
            }
            // unnamed: the only due candidate that looks like it
            val dueC = x.cands.filter { it.first in times && it.second >= TRAY_MIN && due(it.first, x.ns) && !near(it.first, x.ns, minLapNs) }
            val best = dueC.maxByOrNull { it.second }
            val second = dueC.filter { it !== best }.maxOfOrNull { it.second } ?: 0f
            if (best != null && best.second >= NAME_MIN && best.second - second >= 0.05f)
                put(i, best.first, Why.NAMED_BY_TIMING, "camera unsure; ${best.first} (%.2f) is the one due".format(best.second))
            else put(i, null, Why.UNKNOWN, "no due car looks like it")
        }

        // ---------------------------------------------------------------- pass 2: missing laps
        val doubtful = { i: Int -> val o = dec[i]; o == null || o.racer == null }
        repeat(3) {
            for (c in racers) {
                val p = pace(c) ?: continue
                val l = (listOfNotNull(startNs) + times[c]!!.map { it.first }).sorted()
                // gaps inside the race, and the open end (others still crossing after this car's last)
                val gaps = l.zipWithNext().filter { (a, b) -> b - a > MISSING * p }.map { it.first }.toMutableList()
                val lastAny = dec.values.filter { it.racer != null }.maxOfOrNull { it.ns } ?: 0L
                if (l.isNotEmpty() && minOf(nowNs, lastAny + (0.5 * p).toLong()) - l.last() > MISSING * p) gaps += l.last()
                for (g in gaps) {
                    val expect = g + p
                    val win = 0.35 * p
                    var bestJ = -1; var bestD = Double.MAX_VALUE; var how = Why.MISSING_LAP
                    for (j in xs.indices) {
                        val d = abs(xs[j].ns - expect)
                        if (d > win || near(c, xs[j].ns, (EARLY * p).toLong())) continue
                        if (sc(j, c) < TRAY_MIN) continue
                        val o = dec[j]
                        val ok = when {
                            o == null || o.racer == null -> !(o?.why == Why.IGNORED && sc(j, c) < RESCUE_MIN)
                            xs[j].sizeRatio >= BLOB_TWO && o.racer != c -> true
                            else -> false
                        }
                        if (ok && d < bestD) { bestD = d.toDouble(); bestJ = j; how = if (o?.racer != null) Why.SECOND_IN_BLOB else Why.MISSING_LAP }
                    }
                    if (bestJ < 0) continue
                    if (how == Why.SECOND_IN_BLOB) {
                        extra += Out(bestJ, xs[bestJ].ns, c, how, "$c's lap was overdue; this blob was %.1f× one car and $c (%.2f) its runner-up".format(xs[bestJ].sizeRatio, sc(bestJ, c)))
                        times[c]!!.add(xs[bestJ].ns to bestJ)
                    } else {
                        put(bestJ, c, how, "$c's lap was overdue (%.1f s); this crossing near its expected time looks like it (%.2f)".format((xs[bestJ].ns - g) / 1e9, sc(bestJ, c)))
                    }
                }
            }
        }
        return xs.indices.mapNotNull { dec[it] } + extra
    }

    /** Counted crossing times per racer from [solve]'s decisions. */
    fun counted(out: List<Out>): Map<String, List<Long>> =
        out.filter { it.racer != null }.groupBy { it.racer!! }.mapValues { (_, v) -> v.map { it.ns }.sorted() }
}
