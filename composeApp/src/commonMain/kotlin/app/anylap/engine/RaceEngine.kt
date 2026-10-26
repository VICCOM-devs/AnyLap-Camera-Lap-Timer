package app.anylap.engine

import app.anylap.platform.Platform
import app.anylap.data.Lap
import app.anylap.data.LapSource
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
    private class Cross(val ns: Long, val speed: Float?, val source: LapSource, val confidence: Float)

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
        val id = e.racerId
        if (id == null || crosses[id] == null) {
            if (racers.isEmpty()) return
            val suggestions = e.candidates.map { it.first }.filter { s -> racers.any { it.id == s } }
            val ordered = suggestions + racers.map { it.id }.filterNot { it in suggestions }
            unassigned += Unassigned(nextUnassignedId++, e.timestampNs, (e.timestampNs - startNs) / 1_000_000, e.speedKmh, ordered)
            publish(null)
            return
        }
        val source = when (e.source) {
            IdSource.MARKER -> LapSource.MARKER
            else -> LapSource.AUTO
        }
        add(id, Cross(e.timestampNs, e.speedKmh, source, e.confidence))
    }

    fun assign(unassignedId: Long, racerId: String) {
        val u = unassigned.firstOrNull { it.id == unassignedId } ?: return
        unassigned.remove(u)
        if (!add(racerId, Cross(u.elapsedNs, u.speedKmh, LapSource.ASSIGNED, 1f))) publish(null)
    }

    fun dismiss(unassignedId: Long) {
        unassigned.removeAll { it.id == unassignedId }
        publish(null)
    }

    /** Manual lap at this moment (e.g. the camera missed one). */
    fun addManualLap(racerId: String) {
        if (_state.value.phase != Phase.RUNNING) return
        add(racerId, Cross(Platform.monotonicNanos(), null, LapSource.MANUAL, 1f))
    }

    fun removeLastLap(racerId: String) {
        val list = crosses[racerId] ?: return
        if (list.isEmpty()) return
        list.removeAt(list.lastIndex)
        publish(null)
    }

    /** @return true if the crossing was used. */
    private fun add(racerId: String, c: Cross): Boolean {
        val list = crosses[racerId] ?: return false
        if (isFinished(racerId) && (list.lastOrNull()?.ns ?: 0) < c.ns) return false
        if (list.any { kotlin.math.abs(it.ns - c.ns) < minLapNs }) return false // double trigger
        if (isRace && c.ns - startNs < minLapNs / 2) return false // still on the grid
        list += c
        list.sortBy { it.ns }

        val laps = lapsFor(racerId)
        val newLap = laps.lastOrNull { it.atMs == (c.ns - startNs) / 1_000_000 }
        val flash = if (newLap != null) {
            val racer = racers.first { it.id == racerId }
            val best = laps.minOf { it.timeMs }
            val overall = allLaps().minOfOrNull { it.timeMs }
            LapFlash(racer, newLap, 0, personalBest = newLap.timeMs == best && laps.size > 1, fastest = newLap.timeMs == overall)
        } else null
        publish(flash)

        if (isRace && racers.all { isFinished(it.id) }) finish()
        return true
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
            id = Uuid.random().toString(),
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
        )
    }
}
