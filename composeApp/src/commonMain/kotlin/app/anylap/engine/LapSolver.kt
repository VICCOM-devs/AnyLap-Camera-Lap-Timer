package app.anylap.engine

import kotlin.math.abs

/**
 * Whole-race lap solver: each car's own lap rhythm as a second cue next to its appearance, re-deciding
 * every camera crossing of the race at once (the live [LapIdentity] decides one crossing at a time).
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
object LapSolver {
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
        /** The name came from fitting the racers' calibration pictures (outline + colour inside it): stronger than the colour histogram. */
        val fitted: Boolean = false,
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
    const val SWAP_GAP = 0.15f
    const val ALT_GAP = 0.2f
    const val NONE_VALUE = 0.55f
    /** Smaller than this share of one car: a piece of a car (a wing tip), not a car. */
    const val FRAGMENT = 0.4f

    fun solve(xs: List<In>, racers: List<String>, startNs: Long?, minLapNs: Long, nowNs: Long): List<Out> {
        val order = xs.indices.sortedBy { xs[it].ns }
        val times = racers.associateWith { ArrayList<Pair<Long, Int>>() }.toMutableMap() // (ns, input index)
        val dec = HashMap<Int, Out>()
        val extra = ArrayList<Out>()
        fun sc(i: Int, c: String) = xs[i].cands.firstOrNull { it.first == c }?.second ?: 0f
        /** Median lap from crossing gaps, each divided by how many laps it spans (a missed lap is a gap of 2). */
        fun robust(d: List<Double>): Double? {
            if (d.isEmpty()) return null
            val base = d.filter { it >= minLapNs }.minOrNull() ?: return null
            val n = d.map { g -> g / maxOf(1.0, kotlin.math.round(g / base)) }.sorted()
            return if (n.size % 2 == 1) n[n.size / 2] else (n[n.size / 2 - 1] + n[n.size / 2]) / 2
        }
        fun field(): Double? {
            val d = times.values.flatMap { l -> l.sortedBy { it.first }.zipWithNext { a, b -> (b.first - a.first).toDouble() } }
            return if (d.size >= 3) robust(d) else null
        }
        fun pace(c: String): Double? {
            val l = times[c]!!.map { it.first }.sorted()
            val d = l.zipWithNext { a, b -> (b - a).toDouble() }.takeLast(6)
            return if (d.size >= 2) robust(d) else field()
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
            if (x.named == null && x.sizeRatio > 0f && x.sizeRatio < FRAGMENT) { put(i, null, Why.IGNORED, "a fragment of a car (${fx(x.sizeRatio * 100, 0)} % of one)"); continue }
            if (x.rescue) {
                val c = x.cands.firstOrNull()
                if (c != null && c.second >= RESCUE_MIN && c.first in times && due(c.first, x.ns) && !near(c.first, x.ns, minLapNs))
                    put(i, c.first, Why.CHECK_LINE, "seen at the check line, not counted at the finish; ${c.first} was due")
                else put(i, null, Why.IGNORED, "check line, nobody due")
                continue
            }
            // the analyzer may have named a crossing after its runner-up because the best match was "used"
            // by a weak, earlier crossing (it can't name a car twice in 2.5 s): look at the best match here
            // and let the conflict rule decide which of the two crossings that car really owns
            val top = x.cands.firstOrNull()
            val n = if (!x.fitted && x.named != null && top != null && top.first != x.named && top.first in times && top.second - sc(i, x.named) >= SWAP_GAP) top.first else x.named
            if (n != null && n in times) {
                if (!tooSoon(n, x.ns)) { put(i, n, Why.ENGINE, ""); continue }
                // conflict: n already crossed too recently
                val prev = times[n]!!.filter { it.first < x.ns }.maxByOrNull { it.first }!!.second
                // a crossing can only be renamed to a due car that looks nearly as much like it as the name it had
                val altCur = x.cands.filter { it.first != n && it.second >= maxOf(ALT_MIN, sc(i, n) - ALT_GAP) && it.first in times && due(it.first, x.ns) && !near(it.first, x.ns, minLapNs) }.maxByOrNull { it.second }
                val prevLocked = xs[prev].locked != null
                unput(prev)
                val altPrev = if (prevLocked) null else xs[prev].cands.filter { it.first != n && it.second >= maxOf(ALT_MIN, sc(prev, n) - ALT_GAP) && it.first in times && due(it.first, xs[prev].ns) && !near(it.first, xs[prev].ns, minLapNs) }.maxByOrNull { it.second }
                // "no other car" is worth a neutral score, so a weak rename isn't preferred to leaving a crossing uncounted
                val keepPrev = sc(prev, n) + (altCur?.second ?: NONE_VALUE)
                val keepCur = sc(i, n) + (altPrev?.second ?: NONE_VALUE)
                if (prevLocked || keepPrev >= keepCur) {
                    put(prev, n, dec[prev]?.why ?: Why.ENGINE, "")
                    if (altCur != null) put(i, altCur.first, Why.RENAMED_BY_TIMING, "looked like $n (${fx(sc(i, n), 2)}) but $n crossed ${fx((x.ns - xs[prev].ns) / 1e9, 1)} s earlier; ${altCur.first} (${fx(altCur.second, 2)}) was due")
                    else put(i, null, Why.NOT_COUNTED, "looked like $n but $n crossed ${fx((x.ns - xs[prev].ns) / 1e9, 1)} s earlier – no lap")
                } else {
                    if (altPrev != null) put(prev, altPrev.first, Why.RENAMED_BY_TIMING, "first named $n, but $n fits the later crossing better; ${altPrev.first} (${fx(altPrev.second, 2)}) was due")
                    else put(prev, null, Why.NOT_COUNTED, "named $n, but $n fits the crossing ${fx((x.ns - xs[prev].ns) / 1e9, 1)} s later better")
                    put(i, n, Why.ENGINE, "")
                }
                continue
            }
            // unnamed: the only due candidate that looks like it
            val dueC = x.cands.filter { it.first in times && it.second >= TRAY_MIN && due(it.first, x.ns) && !near(it.first, x.ns, minLapNs) }
            val best = dueC.maxByOrNull { it.second }
            val second = dueC.filter { it !== best }.maxOfOrNull { it.second } ?: 0f
            if (best != null && best.second >= NAME_MIN && best.second - second >= 0.05f)
                put(i, best.first, Why.NAMED_BY_TIMING, "camera unsure; ${best.first} (${fx(best.second, 2)}) is the one due")
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
                        if (d > win || xs[j].locked != null || near(c, xs[j].ns, (EARLY * p).toLong())) continue
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
                        extra += Out(bestJ, xs[bestJ].ns, c, how, "$c's lap was overdue; this blob was ${fx(xs[bestJ].sizeRatio, 1)}× one car and $c (${fx(sc(bestJ, c), 2)}) its runner-up")
                        times[c]!!.add(xs[bestJ].ns to bestJ)
                    } else {
                        put(bestJ, c, how, "$c's lap was overdue (${fx((xs[bestJ].ns - g) / 1e9, 1)} s); this crossing near its expected time looks like it (${fx(sc(bestJ, c), 2)})")
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

/** Fixed decimals without String.format (common code: iOS has none). */
internal fun fx(v: Number, digits: Int): String {
    val x = v.toDouble()
    var m = 1L; repeat(digits) { m *= 10 }
    val r = kotlin.math.round(kotlin.math.abs(x) * m).toLong()
    val sign = if (x < 0 && r != 0L) "-" else ""
    val whole = r / m; val frac = r % m
    return if (digits == 0) "$sign$whole" else "$sign$whole." + frac.toString().padStart(digits, '0')
}
