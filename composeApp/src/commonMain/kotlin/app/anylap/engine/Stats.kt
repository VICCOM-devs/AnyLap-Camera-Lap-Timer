package app.anylap.engine

import app.anylap.data.Lap
import app.anylap.data.RaceMode
import app.anylap.data.Racer
import app.anylap.data.Session
import app.anylap.data.Sport
import app.anylap.util.fixed
import app.anylap.util.pad
import kotlin.math.abs
import kotlin.math.sqrt

object Format {
    /** 12.483  /  1:02.483 */
    fun lap(ms: Long?): String {
        if (ms == null) return "—"
        val a = abs(ms)
        val min = a / 60_000
        val sec = (a % 60_000) / 1000
        val milli = a % 1000
        val s = if (min > 0) "$min:${sec.pad(2)}.${milli.pad(3)}" else "$sec.${milli.pad(3)}"
        return if (ms < 0) "-$s" else s
    }

    /** Race clock: 02:15.4 */
    fun clock(ms: Long): String {
        val a = ms.coerceAtLeast(0)
        return "${(a / 60_000).pad(2)}:${((a % 60_000) / 1000).pad(2)}.${(a % 1000) / 100}"
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "${s / 3600}:${((s % 3600) / 60).pad(2)}:${(s % 60).pad(2)}"
        else "${s / 60}:${(s % 60).pad(2)}"
    }

    fun speed(kmh: Float?, useKmh: Boolean): String {
        if (kmh == null) return "—"
        return if (useKmh) "${kmh.fixed(1)} km/h" else "${(kmh / 1.609344f).fixed(1)} mph"
    }

    /** 4:35 /km */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || !secPerKm.isFinite()) return "—"
        val s = secPerKm.toLong()
        return "${s / 60}:${(s % 60).pad(2)} /km"
    }

    fun percent(v: Double?): String = if (v == null) "—" else "${v.fixed(0)}%"
}

data class RacerSessionStats(
    val racerId: String,
    val laps: Int,
    val bestMs: Long?,
    val worstMs: Long?,
    val avgMs: Long?,
    val medianMs: Long?,
    val stdDevMs: Long?,
    /** 100 % = every lap identical. */
    val consistencyPct: Double?,
    val topSpeedKmh: Float?,
    val avgSpeedKmh: Float?,
    val bestPaceSecPerKm: Double?,
    val avgPaceSecPerKm: Double?,
    val distanceM: Double?,
    /** Best lap minus average: how much is left on the table. */
    val potentialGainMs: Long?,
)

data class AllTimeStats(
    val racer: Racer,
    val sessions: Int,
    val laps: Int,
    val bestLapMs: Long?,
    val bestLapAt: Long?,
    val topSpeedKmh: Float?,
    val wins: Int,
    val podiums: Int,
    val distanceM: Double,
    /** (session start epoch ms, best lap that session) oldest first. */
    val bestLapTrend: List<Pair<Long, Long>>,
    /** (session start epoch ms, consistency %) oldest first. */
    val consistencyTrend: List<Pair<Long, Double>>,
)

object Stats {

    fun forRacer(session: Session, racerId: String): RacerSessionStats {
        val laps = session.lapsOf(racerId)
        val times = laps.map { it.timeMs }
        if (times.isEmpty()) {
            return RacerSessionStats(racerId, 0, null, null, null, null, null, null, null, null, null, null, null, null)
        }
        val avg = times.average()
        val sorted = times.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        val sd = if (times.size > 1) sqrt(times.sumOf { (it - avg) * (it - avg) } / (times.size - 1)) else 0.0
        val consistency = if (times.size > 1 && avg > 0) (100.0 * (1.0 - sd / avg)).coerceIn(0.0, 100.0) else null
        val speeds = laps.mapNotNull { it.speedKmh }
        val lapLen = session.config.lapLengthM
        val best = sorted.first()
        return RacerSessionStats(
            racerId = racerId,
            laps = times.size,
            bestMs = best,
            worstMs = sorted.last(),
            avgMs = avg.toLong(),
            medianMs = median,
            stdDevMs = sd.toLong(),
            consistencyPct = consistency,
            topSpeedKmh = speeds.maxOrNull(),
            avgSpeedKmh = if (speeds.isNotEmpty()) speeds.average().toFloat() else null,
            bestPaceSecPerKm = lapLen?.takeIf { it > 0 }?.let { best / 1000.0 / (it / 1000.0) },
            avgPaceSecPerKm = lapLen?.takeIf { it > 0 }?.let { avg / 1000.0 / (it / 1000.0) },
            distanceM = lapLen?.let { it.toDouble() * times.size },
            potentialGainMs = (avg - best).toLong(),
        )
    }

    fun fastestLap(session: Session): Lap? = session.laps.minByOrNull { it.timeMs }

    /** Race position of each racer after each of their laps. */
    fun positionsByLap(session: Session): Map<String, List<Int>> {
        val byRacer = session.racers.associate { r -> r.id to session.lapsOf(r.id) }
        val out = HashMap<String, List<Int>>()
        for ((id, laps) in byRacer) {
            out[id] = laps.mapIndexed { idx, lap ->
                val k = idx + 1
                // Everyone who had completed k laps before this racer did, plus everyone who was further ahead.
                val ahead = byRacer.count { (other, ol) ->
                    other != id && (ol.size >= k && ol[k - 1].atMs < lap.atMs)
                }
                ahead + 1
            }
        }
        return out
    }

    fun allTime(sessions: List<Session>, racer: Racer): AllTimeStats {
        val mine = sessions.filter { s -> s.racers.any { it.id == racer.id } }.sortedBy { it.startedAt }
        var laps = 0
        var best: Long? = null
        var bestAt: Long? = null
        var top: Float? = null
        var wins = 0
        var podiums = 0
        var dist = 0.0
        val trend = ArrayList<Pair<Long, Long>>()
        val cons = ArrayList<Pair<Long, Double>>()
        for (s in mine) {
            val st = forRacer(s, racer.id)
            laps += st.laps
            st.bestMs?.let {
                trend += s.startedAt to it
                if (best == null || it < best!!) { best = it; bestAt = s.startedAt }
            }
            st.consistencyPct?.let { cons += s.startedAt to it }
            st.topSpeedKmh?.let { if (top == null || it > top!!) top = it }
            st.distanceM?.let { dist += it }
            if (s.config.mode != RaceMode.PRACTICE && s.racers.size > 1) {
                val pos = s.order.indexOf(racer.id)
                if (pos == 0) wins++
                if (pos in 0..2) podiums++
            }
        }
        return AllTimeStats(racer, mine.size, laps, best, bestAt, top, wins, podiums, dist, trend, cons)
    }

    fun totals(sessions: List<Session>, sport: Sport?): Triple<Int, Int, Long?> {
        val list = if (sport == null) sessions else sessions.filter { it.config.sport == sport }
        val laps = list.sumOf { it.laps.size }
        val best = list.flatMap { it.laps }.minOfOrNull { it.timeMs }
        return Triple(list.size, laps, best)
    }
}
