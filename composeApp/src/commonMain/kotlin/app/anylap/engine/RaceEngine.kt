package app.anylap.engine

import app.anylap.platform.Platform
import app.anylap.data.CrossRec
import app.anylap.data.Lap
import app.anylap.data.LapSource
import app.anylap.data.ReviewCandidate
import app.anylap.data.ReviewItem
import app.anylap.data.ReviewKind
import app.anylap.platform.joinPath
import app.anylap.util.Bmp
import app.anylap.data.RaceMode
import app.anylap.data.Racer
import app.anylap.data.Session
import app.anylap.data.SessionConfig
import app.anylap.vision.CrossingEvent
import app.anylap.vision.IdSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class Phase { SETUP, COUNTDOWN, RUNNING, FINISHED }

data class Unassigned(
    val id: Long,
    val elapsedNs: Long,
    val atMs: Long,
    val speedKmh: Float?,
    /** Racer ids ordered by how likely they are. */
    val suggestions: List<String>,
    /** The camera crossing behind it (index into the engine's crossing list), −1 = none. */
    val autoIndex: Int = -1,
)

data class Standing(
    val racer: Racer,
    val position: Int,
    val laps: Int,
    val lastLapMs: Long?,
    val bestLapMs: Long?,
    val lastSpeedKmh: Float?,
    val gap: String,
    val finished: Boolean,
    val hasFastestLap: Boolean,
    /** Practice: true once the first crossing started the clock for this racer. */
    val timing: Boolean,
)

data class LapFlash(val racer: Racer, val lap: Lap, val position: Int, val personalBest: Boolean, val fastest: Boolean)

data class RaceState(
    val phase: Phase = Phase.SETUP,
    val countdown: Int = 0,
    val elapsedMs: Long = 0,
    val standings: List<Standing> = emptyList(),
    val laps: List<Lap> = emptyList(),
    val unassigned: List<Unassigned> = emptyList(),
    val flash: LapFlash? = null,
    val timeUp: Boolean = false,
    val leaderLaps: Int = 0,
)

/**
 * Turns finish-line crossings into laps, positions, gaps and a finished [Session].
 * Crossings are stored per racer and laps are always re-derived from them, so manual fixes
 * (assigning an unknown crossing, deleting a false one) can happen in any order.
 */
class RaceEngine(
    val config: SessionConfig,
    val racers: List<Racer>,
    private val scope: CoroutineScope,
    private val onLap: (LapFlash) -> Unit = {},
    private val onFinished: () -> Unit = {},
) {
    private class Cross(val ns: Long, val speed: Float?, val source: LapSource, val confidence: Float, val auto: Int = -1)

    /** Every camera crossing of the session (named, unnamed, gated out, check-line rescues), re-decided by [LapIdentity] after each one. */
    private class Auto(var input: LapIdentity.In, val speed: Float?, val event: CrossingEvent)
    private val autos = ArrayList<Auto>()
    /** Laps added or assigned by hand, kept apart from the camera's. */
    private val handCrosses = HashMap<String, MutableList<Cross>>()
    private val timingReviewed = HashSet<Int>()

    private val _state = MutableStateFlow(RaceState())
    val state: StateFlow<RaceState> = _state

    private val crosses = HashMap<String, MutableList<Cross>>()
    private val unassigned = ArrayList<Unassigned>()
    private var nextUnassignedId = 1L
    private var startNs = 0L
    private var startWallMs = 0L
    private var endNs = 0L
    private var ticker: Job? = null
    private val minLapNs = (config.minLapSec * 1e9).toLong()
    private val isRace = config.mode != RaceMode.PRACTICE

    val sessionStartNs: Long get() = startNs

    init {
        racers.forEach { crosses[it.id] = ArrayList() }
        publish(null)
    }

    // ------------------------------------------------------------------ control

    fun start() {
        if (_state.value.phase != Phase.SETUP) return
        if (!isRace) {
            begin()
            return
        }
        scope.launch {
            for (n in 3 downTo 1) {
                _state.value = _state.value.copy(phase = Phase.COUNTDOWN, countdown = n)
                delay(1000)
            }
            begin()
        }
    }

    private fun begin() {
        startNs = Platform.monotonicNanos()
        startWallMs = Platform.nowMillis()
        _state.value = _state.value.copy(phase = Phase.RUNNING, countdown = 0)
        ticker = scope.launch {
            while (isActive && _state.value.phase == Phase.RUNNING) {
                val now = Platform.monotonicNanos()
                val elapsed = (now - startNs) / 1_000_000
                val timeUp = config.mode == RaceMode.TIME && elapsed >= config.timeLimitSec * 1000L
                _state.value = _state.value.copy(elapsedMs = elapsed, timeUp = timeUp)
                delay(100)
            }
        }
    }

    fun finish() {
        if (_state.value.phase == Phase.FINISHED) return
        endNs = Platform.monotonicNanos()
        ticker?.cancel()
        _state.value = _state.value.copy(phase = Phase.FINISHED, elapsedMs = (endNs - startNs) / 1_000_000)
        publish(null)
        onFinished()
    }

    // ------------------------------------------------------------------ input

    fun onCrossing(e: CrossingEvent) {
        if (_state.value.phase != Phase.RUNNING) return
        if (e.timestampNs < startNs) return
        val id = e.racerId?.takeIf { crosses[it] != null }
        val idx = autos.size
        autos += Auto(LapIdentity.In(e.timestampNs, id, e.candidates, e.confidence, e.split, rescue = false, gated = false, sizeRatio = e.sizeRatio), e.speedKmh, e)
        val outs = rebuild(e.timestampNs)
        val mine = outs.firstOrNull { it.index == idx && it.why != LapIdentity.Why.SECOND_IN_BLOB }
        if (mine?.racer == null) {
            if (racers.isEmpty()) return
            val suggestions = e.candidates.map { it.first }.filter { s -> racers.any { it.id == s } }
            val ordered = suggestions + racers.map { it.id }.filterNot { it in suggestions }
            val uid = nextUnassignedId++
            unassigned += Unassigned(uid, e.timestampNs, (e.timestampNs - startNs) / 1_000_000, e.speedKmh, ordered, idx)
            if (mine?.why != LapIdentity.Why.NOT_COUNTED) review(e, ReviewKind.UNKNOWN, decided = null, unassignedId = uid)
        } else if (mine.why == LapIdentity.Why.ENGINE) {
            // Things a human should glance at afterwards: a split pair, or a name that only just won.
            when {
                e.split -> review(e, ReviewKind.SPLIT, decided = mine.racer)
                e.source == IdSource.APPEARANCE && e.confidence < LOW_MARGIN -> review(e, ReviewKind.LOW_MARGIN, decided = mine.racer)
            }
        }
        afterRebuild(outs, flashFor = mine?.racer?.let { it to e.timestampNs })
    }

    /** A crossing the appearance gate turned down, or a check-line rescue: kept, and counted if lap timing says a due car owns it. */
    fun onRejected(e: CrossingEvent) {
        if (_state.value.phase != Phase.RUNNING) return
        if (e.timestampNs < startNs) return
        val idx = autos.size
        autos += Auto(LapIdentity.In(e.timestampNs, null, e.candidates, e.confidence, e.split, rescue = e.rescue, gated = !e.rescue, sizeRatio = e.sizeRatio), e.speedKmh, e)
        val outs = rebuild(e.timestampNs)
        val mine = outs.firstOrNull { it.index == idx }
        if (mine?.racer == null && e.snapshot != null) review(e, if (e.rescue) ReviewKind.MISSED else ReviewKind.REJECTED, decided = null)
        afterRebuild(outs, flashFor = mine?.racer?.let { it to e.timestampNs })
    }

    /** Re-decides every camera crossing with [LapIdentity] and rebuilds the per-racer crossing lists. */
    private fun rebuild(nowNs: Long): List<LapIdentity.Out> {
        val outs = LapIdentity.solve(autos.map { it.input }, racers.map { it.id }, if (isRace) startNs else null, minLapNs, nowNs)
        racers.forEach { r -> crosses[r.id] = ArrayList(handCrosses[r.id] ?: emptyList()) }
        for (o in outs.sortedBy { it.ns }) {
            val rid = o.racer ?: continue
            val list = crosses[rid] ?: continue
            if (list.any { kotlin.math.abs(it.ns - o.ns) < minLapNs }) continue      // double trigger
            if (isRace && o.ns - startNs < minLapNs / 2) continue                      // still on the grid
            val a = autos[o.index]
            list += Cross(o.ns, a.speed, if (a.event.source == IdSource.MARKER) LapSource.MARKER else LapSource.AUTO, if (o.why == LapIdentity.Why.ENGINE) a.event.confidence else 0f, o.index)
        }
        crosses.values.forEach { it.sortBy { c -> c.ns } }
        return outs
    }

    private fun afterRebuild(outs: List<LapIdentity.Out>, flashFor: Pair<String, Long>?) {
        // the tray: every crossing lap timing decided (once per crossing, with the picture taken at the line)
        for (o in outs) {
            if (o.why !in TIMING_KINDS || !timingReviewed.add(o.index)) continue
            review(autos[o.index].event, ReviewKind.TIMING, decided = o.racer)
        }
        // unknowns that lap timing has since given to a car leave the "assign" list
        val countedAutos = crosses.values.flatten().map { it.auto }.toSet()
        unassigned.removeAll { it.autoIndex >= 0 && it.autoIndex in countedAutos }
        var flash: LapFlash? = null
        if (flashFor != null) {
            val (racerId, ns) = flashFor
            val laps = lapsFor(racerId)
            val newLap = laps.lastOrNull { it.atMs == (ns - startNs) / 1_000_000 }
            if (newLap != null) {
                val racer = racers.first { it.id == racerId }
                val best = laps.minOf { it.timeMs }
                val overall = allLaps().minOfOrNull { it.timeMs }
                flash = LapFlash(racer, newLap, 0, personalBest = newLap.timeMs == best && laps.size > 1, fastest = newLap.timeMs == overall)
            }
        }
        publish(flash)
        if (isRace && racers.all { isFinished(it.id) }) finish()
    }

    private val reviews = ArrayList<ReviewItem>()
    private val reviewUnassigned = HashMap<Long, String>()

    @OptIn(ExperimentalUuidApi::class)
    private fun review(e: CrossingEvent, kind: ReviewKind, decided: String?, unassignedId: Long? = null) {
        if (reviews.size >= MAX_REVIEWS) return
        val atMs = (e.timestampNs - startNs) / 1_000_000
        val path = e.snapshot?.let { snap ->
            try {
                val p = joinPath(Platform.dataDir, "review_${sessionId}_$atMs.bmp")
                Platform.writeBytes(p, Bmp.encode(snap.width, snap.height, snap.argb))
                p
            } catch (t: Throwable) { null }
        }
        val item = ReviewItem(
            id = Uuid.random().toString(),
            atMs = atMs,
            kind = kind,
            imagePath = path,
            candidates = e.candidates.filter { c -> racers.any { it.id == c.first } }.take(4).map { ReviewCandidate(it.first, it.second) },
            decided = decided,
            speedKmh = e.speedKmh,
            direction = e.direction,
        )
        reviews += item
        if (unassignedId != null) reviewUnassigned[unassignedId] = item.id
    }

    private fun resolveReview(unassignedId: Long, to: String) {
        val rid = reviewUnassigned.remove(unassignedId) ?: return
        val i = reviews.indexOfFirst { it.id == rid }
        if (i >= 0) reviews[i] = reviews[i].copy(resolvedTo = to)
    }

    fun assign(unassignedId: Long, racerId: String) {
        val u = unassigned.firstOrNull { it.id == unassignedId } ?: return
        unassigned.remove(u)
        resolveReview(unassignedId, racerId)
        if (u.autoIndex >= 0) lock(u.autoIndex, racerId)
        else {
            handCrosses.getOrPut(racerId) { ArrayList() } += Cross(u.elapsedNs, u.speedKmh, LapSource.ASSIGNED, 1f)
            afterRebuild(rebuild(Platform.monotonicNanos()), null)
        }
    }

    fun dismiss(unassignedId: Long) {
        val u = unassigned.firstOrNull { it.id == unassignedId }
        unassigned.removeAll { it.id == unassignedId }
        resolveReview(unassignedId, "")
        if (u != null && u.autoIndex >= 0) lock(u.autoIndex, "") else publish(null)
    }

    /** A human verdict on a camera crossing: a racer id, or "" = not a car. Lap timing re-decides the rest around it. */
    private fun lock(autoIndex: Int, to: String) {
        val a = autos.getOrNull(autoIndex) ?: return
        val i = a.input
        a.input = LapIdentity.In(i.ns, i.named, i.cands, i.margin, i.split, i.rescue, i.gated, i.sizeRatio, locked = to)
        afterRebuild(rebuild(Platform.monotonicNanos()), null)
    }

    /** Manual lap at this moment (e.g. the camera missed one). */
    fun addManualLap(racerId: String) {
        if (_state.value.phase != Phase.RUNNING) return
        val c = Cross(Platform.monotonicNanos(), null, LapSource.MANUAL, 1f)
        handCrosses.getOrPut(racerId) { ArrayList() } += c
        afterRebuild(rebuild(c.ns), racerId to c.ns)
    }

    fun removeLastLap(racerId: String) {
        val last = crosses[racerId]?.lastOrNull() ?: return
        if (last.auto >= 0) lock(last.auto, "")
        else {
            handCrosses[racerId]?.removeAll { it.ns == last.ns }
            afterRebuild(rebuild(Platform.monotonicNanos()), null)
        }
    }

    // ------------------------------------------------------------------ derived

    private fun crossingLimit(): Int = if (config.mode == RaceMode.LAPS) config.targetLaps else Int.MAX_VALUE

    private fun usedCrosses(racerId: String): List<Cross> {
        val list = crosses[racerId] ?: return emptyList()
        return when (config.mode) {
            RaceMode.PRACTICE -> list
            RaceMode.LAPS -> list.take(crossingLimit())
            RaceMode.TIME -> {
                val limitNs = startNs + config.timeLimitSec * 1_000_000_000L
                val firstAfter = list.indexOfFirst { it.ns >= limitNs }
                if (firstAfter >= 0) list.take(firstAfter + 1) else list
            }
        }
    }

    private fun isFinished(racerId: String): Boolean {
        val used = usedCrosses(racerId)
        return when (config.mode) {
            RaceMode.PRACTICE -> false
            RaceMode.LAPS -> used.size >= config.targetLaps
            RaceMode.TIME -> used.lastOrNull()?.let { it.ns >= startNs + config.timeLimitSec * 1_000_000_000L } == true
        }
    }

    private fun lapsFor(racerId: String): List<Lap> {
        val used = usedCrosses(racerId)
        val out = ArrayList<Lap>()
        var prev: Long? = if (isRace) startNs else null
        for (c in used) {
            val p = prev
            if (p != null) {
                out += Lap(
                    racerId = racerId,
                    number = out.size + 1,
                    timeMs = (c.ns - p) / 1_000_000,
                    atMs = (c.ns - startNs) / 1_000_000,
                    speedKmh = c.speed,
                    source = c.source,
                    confidence = c.confidence,
                )
            }
            prev = c.ns
        }
        return out
    }

    private fun allLaps(): List<Lap> = racers.flatMap { lapsFor(it.id) }

    private fun publish(flash: LapFlash?) {
        val allLaps = allLaps()
        val fastest = allLaps.minByOrNull { it.timeMs }
        data class Row(val racer: Racer, val laps: List<Lap>, val lastNs: Long?, val finished: Boolean, val timing: Boolean)
        val rows = racers.map { r ->
            val used = usedCrosses(r.id)
            Row(r, lapsFor(r.id), used.lastOrNull()?.ns, isFinished(r.id), used.isNotEmpty())
        }
        val ordered = if (isRace) {
            rows.sortedWith(compareByDescending<Row> { it.laps.size }.thenBy { it.lastNs ?: Long.MAX_VALUE })
        } else {
            rows.sortedWith(compareBy<Row> { it.laps.minOfOrNull { l -> l.timeMs } ?: Long.MAX_VALUE }.thenByDescending { it.laps.size })
        }
        val leader = ordered.firstOrNull()
        val standings = ordered.mapIndexed { i, row ->
            val best = row.laps.minOfOrNull { it.timeMs }
            val gap = when {
                i == 0 || leader == null -> if (isRace) "Leader" else (if (best != null) "Fastest" else "—")
                !isRace -> {
                    val lb = leader.laps.minOfOrNull { it.timeMs }
                    if (best != null && lb != null) "+" + Format.lap(best - lb) else "—"
                }
                row.laps.size < leader.laps.size -> {
                    val d = leader.laps.size - row.laps.size
                    "+$d lap" + if (d > 1) "s" else ""
                }
                else -> {
                    val k = row.laps.size
                    val mine = row.laps.getOrNull(k - 1)?.atMs
                    val theirs = leader.laps.getOrNull(k - 1)?.atMs
                    if (mine != null && theirs != null) "+" + Format.lap(mine - theirs) else "—"
                }
            }
            Standing(
                racer = row.racer,
                position = i + 1,
                laps = row.laps.size,
                lastLapMs = row.laps.lastOrNull()?.timeMs,
                bestLapMs = best,
                lastSpeedKmh = row.laps.lastOrNull()?.speedKmh,
                gap = gap,
                finished = row.finished,
                hasFastestLap = fastest != null && fastest.racerId == row.racer.id,
                timing = row.timing,
            )
        }
        val f = flash?.let { fl -> fl.copy(position = standings.firstOrNull { it.racer.id == fl.racer.id }?.position ?: 0) }
        _state.value = _state.value.copy(
            standings = standings,
            laps = allLaps,
            unassigned = unassigned.toList(),
            flash = f ?: _state.value.flash,
            leaderLaps = standings.firstOrNull()?.laps ?: 0,
        )
        if (f != null) onLap(f)
    }

    // ------------------------------------------------------------------ result

    @OptIn(ExperimentalUuidApi::class)
    fun buildSession(videoPath: String?, recordingStartNs: Long?): Session {
        val st = _state.value
        val durationMs = if (endNs > 0) (endNs - startNs) / 1_000_000 else st.elapsedMs
        return Session(
            id = sessionId,
            startedAt = if (startWallMs > 0) startWallMs else Platform.nowMillis(),
            config = config,
            // Sessions keep a snapshot of who raced, not their calibration images.
            racers = racers.map { it.copy(calibration = emptyList()) },
            laps = st.laps,
            durationMs = durationMs,
            order = st.standings.filter { it.laps > 0 || isRace }.map { it.racer.id },
            videoPath = videoPath,
            videoOffsetMs = if (recordingStartNs != null && startNs > 0) (startNs - recordingStartNs) / 1_000_000 else 0,
            unassignedCrossings = st.unassigned.size,
            crossings = racers.associate { r -> r.id to (crosses[r.id] ?: emptyList()).map { CrossRec((it.ns - startNs) / 1_000_000, it.speed, it.source, it.confidence) } },
            reviews = reviews.toList(),
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private val sessionId = Uuid.random().toString()

    companion object {
        /** Naming margin below which a crossing goes to the "Check these" tray (2× the engine's minimum). */
        const val LOW_MARGIN = 0.12f
        /** Pictures kept per session. */
        const val MAX_REVIEWS = 60
        private val TIMING_KINDS = setOf(
            LapIdentity.Why.RENAMED_BY_TIMING, LapIdentity.Why.NAMED_BY_TIMING, LapIdentity.Why.CHECK_LINE,
            LapIdentity.Why.SECOND_IN_BLOB, LapIdentity.Why.MISSING_LAP, LapIdentity.Why.NOT_COUNTED,
        )
    }
}
