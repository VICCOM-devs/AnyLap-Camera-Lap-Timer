package app.anylap.engine

import app.anylap.i18n.tr
import app.anylap.i18n.trn
import app.anylap.platform.Platform
import app.anylap.data.CrossRec
import app.anylap.data.Lap
import app.anylap.data.LapSource
import app.anylap.data.ReviewBox
import app.anylap.data.ReviewCandidate
import app.anylap.data.ReviewItem
import app.anylap.data.ReviewKind
import app.anylap.platform.joinPath
import app.anylap.util.Bmp
import app.anylap.data.RaceMode
import app.anylap.data.Racer
import app.anylap.data.Session
import app.anylap.data.SessionConfig
import app.anylap.vision.CardFit
import app.anylap.vision.CrossingEvent
import app.anylap.vision.Snapshot
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
    /** Start lights lit (1..[RaceEngine.START_LIGHTS]) while [phase] is COUNTDOWN. */
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
    /** Start lights: light [n] of [START_LIGHTS] just came on (for a beep). */
    private val onLight: (n: Int) -> Unit = {},
    /** Start lights went out: the race clock is running. */
    private val onLightsOut: () -> Unit = {},
    /** Hold with all lights on before they go out, ms (random 200–3000 by default). */
    private val holdMs: () -> Long = { kotlin.random.Random.nextLong(200, 3001) },
) {
    private class Cross(val ns: Long, val speed: Float?, val source: LapSource, val confidence: Float, val event: CrossingEvent? = null)

    private val _state = MutableStateFlow(RaceState())
    val state: StateFlow<RaceState> = _state

    /** Counted crossings per racer, with lap timing as a second opinion on the names. */
    private val identity: LapIdentity
    private val unassigned = ArrayList<Unassigned>()
    private var nextUnassignedId = 1L
    private var startNs = 0L
    private var startWallMs = 0L
    private var endNs = 0L
    private var ticker: Job? = null
    private val minLapNs = (config.minLapSec * 1e9).toLong()
    private val isRace = config.mode != RaceMode.PRACTICE

    private fun crossesOf(racerId: String): List<Cross> = identity.crossings(racerId).map { it.payload as Cross }

    val sessionStartNs: Long get() = startNs

    init {
        identity = LapIdentity(minLapNs)
        racers.forEach { identity.register(it.id) }
        publish(null)
    }

    // ------------------------------------------------------------------ control

    fun start() {
        if (_state.value.phase != Phase.SETUP) return
        if (!isRace) {
            begin()
            return
        }
        // F1-style start: five red lights come on one per second, stay on for a random 0.2–3 s,
        // and the race starts the moment they go out (nobody can anticipate it).
        scope.launch {
            for (n in 1..START_LIGHTS) {
                _state.value = _state.value.copy(phase = Phase.COUNTDOWN, countdown = n)
                onLight(n)
                delay(1000)
            }
            delay(holdMs())
            begin()
            onLightsOut()
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
        trackCross[e.trackId] = e.timestampNs
        val id = e.racerId
        if (id == null || racers.none { it.id == id }) {
            if (racers.isEmpty()) return
            park(e, ReviewKind.UNKNOWN)
            publish(null)
            return
        }
        val source = when (e.source) {
            IdSource.MARKER -> LapSource.MARKER
            else -> LapSource.AUTO
        }
        // Things a human should glance at afterwards: a split pair, or a name that only just won
        // *and* isn't a convincing match on its own (a look-alike pair is always close, so a fixed
        // margin alone would flag every lap of a clearly seen car).
        val best = e.candidates.firstOrNull()?.second ?: 0f
        when {
            e.split -> review(e, ReviewKind.SPLIT, decided = id)
            e.source == IdSource.APPEARANCE && e.confidence < LOW_MARGIN && best < CLEAR_MATCH -> review(e, ReviewKind.LOW_MARGIN, decided = id)
        }
        add(id, Cross(e.timestampNs, e.speedKmh, source, e.confidence, e), e.candidates, locked = source == LapSource.MARKER)
    }

    /** Not counted for anyone: offered for a live "assign" and filed in the tray. */
    private fun park(e: CrossingEvent, kind: ReviewKind) {
        val suggestions = e.candidates.map { it.first }.filter { s -> racers.any { it.id == s } }
        val ordered = identity.dueFirst(suggestions, e.timestampNs) + racers.map { it.id }.filterNot { it in suggestions }
        val uid = nextUnassignedId++
        unassigned += Unassigned(uid, e.timestampNs, (e.timestampNs - startNs) / 1_000_000, e.speedKmh, ordered)
        review(e, kind, decided = null, unassignedId = uid)
    }

    /** A crossing the appearance gate turned down: kept with its picture so a missed car can be rescued. */
    fun onRejected(e: CrossingEvent) {
        if (_state.value.phase != Phase.RUNNING) return
        if (e.timestampNs < startNs || e.snapshot == null) return
        review(e, if (e.rescue) ReviewKind.MISSED else ReviewKind.REJECTED, decided = null)
    }

    private val reviews = ArrayList<ReviewItem>()
    private val reviewUnassigned = HashMap<Long, String>()
    /** Track → its finish-line crossing time, and review item → the tracks of its boxes (in order). */
    private val trackCross = HashMap<Int, Long>()
    private val reviewTracks = HashMap<String, List<Int>>()

    @OptIn(ExperimentalUuidApi::class)
    private fun review(e: CrossingEvent, kind: ReviewKind, decided: String?, unassignedId: Long? = null) {
        if (reviews.size >= MAX_REVIEWS) return
        val atMs = (e.timestampNs - startNs) / 1_000_000
        // Two touching cars are one card showing both, not two cards with the same picture.
        if (kind == ReviewKind.SPLIT) {
            val twin = reviews.firstOrNull { it.kind == ReviewKind.SPLIT && kotlin.math.abs(it.atMs - atMs) < 400 && e.trackId in (reviewTracks[it.id] ?: emptyList()) }
            if (twin != null) return
        }
        val snap = e.snapshot
        val path = snap?.let {
            try {
                val p = joinPath(Platform.dataDir, "review_${sessionId}_$atMs.bmp")
                Platform.writeBytes(p, Bmp.encode(it.width, it.height, it.argb))
                p
            } catch (t: Throwable) { null }
        }
        val id = Uuid.random().toString()
        val boxes = snap?.boxes ?: emptyList()
        val item = ReviewItem(
            id = id,
            atMs = atMs,
            kind = kind,
            imagePath = path,
            candidates = e.candidates.filter { c -> racers.any { it.id == c.first } }.take(4).map { ReviewCandidate(it.first, it.second) },
            decided = decided,
            speedKmh = e.speedKmh,
            direction = e.direction,
            boxes = boxes.map { b -> ReviewBox(b.l, b.t, b.r, b.b, b.vx, b.vy, atMs = if (b.trackId == e.trackId) atMs else null, subject = b.trackId == e.trackId) },
            line = snap?.line?.toList() ?: emptyList(),
            shotMs = snap?.let { (it.timestampNs - startNs) / 1_000_000 },
            frameMs = (snap?.frameNs ?: 0L) / 1e6f,
        )
        reviews += item
        reviewTracks[id] = boxes.map { it.trackId }
        if (snap?.yuv != null) reviewSnaps[id] = snap
        if (unassignedId != null) reviewUnassigned[unassignedId] = item.id
    }

    /** Review boxes get the crossing time of their track (known once every crossing is in). */
    private fun reviewsWithTimes(): List<ReviewItem> {
        val outlined = outlineCards()
        return reviews.map { r ->
            outlined[r.id]?.let { return@map r.copy(boxes = it, note = pieceNote(r, it)) }
            val ids = reviewTracks[r.id] ?: return@map r
            r.copy(boxes = r.boxes.mapIndexed { i, b ->
                if (b.atMs != null) b else {
                    val ns = ids.getOrNull(i)?.let { trackCross[it] }
                    val ms = ns?.let { (it - startNs) / 1_000_000 }
                    if (ms != null && kotlin.math.abs(ms - r.atMs) < 1000) b.copy(atMs = ms) else b
                }
            })
        }
    }

    /**
     * A crossing that wasn't counted but sits inside the outline of a car that *was* counted at the same
     * moment is a second piece of that car (cut out of a blob of touching cars): say so in plain words.
     */
    private fun pieceNote(r: ReviewItem, boxes: List<ReviewBox>): String? {
        if (r.decided != null) return null
        val subject = boxes.firstOrNull { it.subject } ?: return null
        val id = subject.racerId ?: return null
        val counted = crossesOf(id).map { (it.ns - startNs) / 1_000_000 }.minByOrNull { kotlin.math.abs(it - r.atMs) } ?: return null
        if (kotlin.math.abs(counted - r.atMs) > 150) return null
        val name = racers.firstOrNull { it.id == id }?.name ?: return null
        return tr("A second piece of {0}, cut out of the touching cars – {1} is already counted at {2}. Nothing to fix.", name, name, Format.lap(counted))
    }

    /** Pictures of the Check cards, in memory only (colour planes + foreground for [CardFit]). */
    private val reviewSnaps = HashMap<String, Snapshot>()
    /** Longest the card outlines may take at the end of a race (they run in the background). */
    private val CARD_FIT_BUDGET_NS = 25_000_000_000L
    /** Shutter time, for how long the blur streak the outline must cover is. */
    var cardExposureS = 1f / 120f

    /**
     * Every car on every Check card outlined with its own calibration picture, named as the race finally
     * counted it (once the race is over, so the names are the final ones). Cards without a picture, or
     * racers without calibration pictures, keep the tracker's boxes.
     */
    private fun outlineCards(): Map<String, List<ReviewBox>> {
        if (reviewSnaps.isEmpty()) return emptyMap()
        val pool = racers.mapNotNull { r -> r.topTemplate()?.let { r.id to it } }
        if (pool.isEmpty()) return emptyMap()
        val counted = racers.flatMap { r -> crossesOf(r.id).mapNotNull { c -> c.event?.let { r.id to it } } }
        val out = HashMap<String, List<ReviewBox>>()
        val t0 = Platform.monotonicNanos()
        // open cards first (they are the ones that need a look); stop after a time budget – the rest keep the tracker's boxes
        for (r in reviews.sortedByDescending { it.decided == null }) {
            if (Platform.monotonicNanos() - t0 > CARD_FIT_BUDGET_NS) break
            val snap = reviewSnaps[r.id] ?: continue
            val near = counted.filter { kotlin.math.abs(it.second.timestampNs - snap.timestampNs) < 600_000_000L }
            val owners = { tid: Int ->
                val mine = counted.filter { it.second.trackId == tid }
                (mine.filter { it in near }.ifEmpty { mine.sortedBy { kotlin.math.abs(it.second.timestampNs - snap.timestampNs) }.take(1) }).map { it.first }
            }
            val seen = near.mapNotNull { (id, e) -> e.box?.let { CardFit.Seen(id, it, e.vx, e.vy, e.timestampNs) } }
            val fits = try { CardFit.card(snap, owners, pool, cardExposureS, seen) } catch (t: Throwable) { continue }
            if (fits.isEmpty()) continue
            val subjectBox = r.boxes.firstOrNull { it.subject }
            val sx = subjectBox?.let { (it.l + it.r) / 2 }; val sy = subjectBox?.let { (it.t + it.b) / 2 }
            val subjectFit = fits.firstOrNull { r.decided != null && it.racerId == r.decided }
                ?: if (sx != null && sy != null) fits.firstOrNull { it.contains(sx, sy) } else null
            out[r.id] = fits.map { f ->
                val xs = (0 until 4).map { f.quad[2 * it] }; val ys = (0 until 4).map { f.quad[2 * it + 1] }
                val atNs = near.filter { it.first == f.racerId }.minByOrNull { kotlin.math.abs(it.second.timestampNs - snap.timestampNs) }?.second?.timestampNs
                ReviewBox(
                    xs.min().coerceIn(0f, 1f), ys.min().coerceIn(0f, 1f), xs.max().coerceIn(0f, 1f), ys.max().coerceIn(0f, 1f),
                    f.vx, f.vy,
                    atMs = if (f === subjectFit) r.atMs else atNs?.let { (it - startNs) / 1_000_000 },
                    subject = f === subjectFit,
                    quad = f.quad.toList(),
                    racerId = f.racerId,
                )
            }
        }
        return out
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
        if (!add(racerId, Cross(u.elapsedNs, u.speedKmh, LapSource.ASSIGNED, 1f), emptyList(), locked = true)) publish(null)
    }

    fun dismiss(unassignedId: Long) {
        unassigned.removeAll { it.id == unassignedId }
        resolveReview(unassignedId, "")
        publish(null)
    }

    /** Manual lap at this moment (e.g. the camera missed one). */
    fun addManualLap(racerId: String) {
        if (_state.value.phase != Phase.RUNNING) return
        add(racerId, Cross(Platform.monotonicNanos(), null, LapSource.MANUAL, 1f), emptyList(), locked = true)
    }

    fun removeLastLap(racerId: String) {
        val last = identity.crossings(racerId).lastOrNull() ?: return
        identity.remove(racerId, last)
        publish(null)
    }

    /**
     * Counts a crossing for [racerId] through [LapIdentity]: if that racer already crossed within
     * what it can lap, the weaker of the two is re-named to a runner-up that is due, or parked.
     * @return true if this crossing was counted (for anyone).
     */
    private fun add(racerId: String, c: Cross, candidates: List<Pair<String, Float>>, locked: Boolean): Boolean {
        if (racers.none { it.id == racerId }) return false
        val list = crossesOf(racerId)
        if (isFinished(racerId) && (list.lastOrNull()?.ns ?: 0) < c.ns) return false
        if (isRace && c.ns - startNs < minLapNs / 2) return false // still on the grid
        // a hand-made lap right on top of one already counted is a double tap, not a conflict
        if (locked && list.any { kotlin.math.abs(it.ns - c.ns) < minLapNs }) return false

        val changes = identity.place(racerId, LapIdentity.Entry(c.ns, candidates, locked, c))
        var counted = false
        var flashRacer: String? = null
        for (ch in changes) {
            val cr = ch.entry.payload as Cross
            if (cr === c) {
                counted = ch.to != null
                if (ch.to != null) flashRacer = ch.to
            }
            when (ch.reason) {
                LapIdentity.Reason.RENAMED -> {
                    relabel(cr, ch.to)
                    cr.event?.let { review(it, ReviewKind.RENAMED, decided = ch.to) }
                }
                LapIdentity.Reason.PARKED -> {
                    relabel(cr, null)
                    cr.event?.let { park(it, ReviewKind.NOT_COUNTED) }
                }
                else -> {}
            }
        }

        val fr = flashRacer
        val flash = if (fr != null) {
            val laps = lapsFor(fr)
            val newLap = laps.lastOrNull { it.atMs == (c.ns - startNs) / 1_000_000 }
            if (newLap != null) {
                val racer = racers.first { it.id == fr }
                val best = laps.minOf { it.timeMs }
                val overall = allLaps().minOfOrNull { it.timeMs }
                LapFlash(racer, newLap, 0, personalBest = newLap.timeMs == best && laps.size > 1, fastest = newLap.timeMs == overall)
            } else null
        } else null
        publish(flash)

        if (isRace && racers.all { isFinished(it.id) }) finish()
        return counted
    }

    /** A crossing moved by lap timing: tray items that said who it was now say the new owner. */
    private fun relabel(c: Cross, to: String?) {
        val atMs = (c.ns - startNs) / 1_000_000
        for (i in reviews.indices) if (kotlin.math.abs(reviews[i].atMs - atMs) <= 1 && reviews[i].decided != null) reviews[i] = reviews[i].copy(decided = to)
    }

    // ------------------------------------------------------------------ derived

    private fun crossingLimit(): Int = if (config.mode == RaceMode.LAPS) config.targetLaps else Int.MAX_VALUE

    private fun usedCrosses(racerId: String): List<Cross> {
        val list = crossesOf(racerId)
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
                i == 0 || leader == null -> if (isRace) tr("Leader") else (if (best != null) tr("Fastest") else "—")
                !isRace -> {
                    val lb = leader.laps.minOfOrNull { it.timeMs }
                    if (best != null && lb != null) "+" + Format.lap(best - lb) else "—"
                }
                row.laps.size < leader.laps.size -> {
                    val d = leader.laps.size - row.laps.size
                    trn(d, "+{0} lap", "+{0} laps")
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
            crossings = racers.associate { r -> r.id to crossesOf(r.id).map { CrossRec((it.ns - startNs) / 1_000_000, it.speed, it.source, it.confidence) } },
            reviews = reviewsWithTimes(),
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private val sessionId = Uuid.random().toString()

    companion object {
        /** Naming margin below which a crossing goes to the "Check these" tray (2× the engine's minimum). */
        const val LOW_MARGIN = 0.12f
        /** A match this good is clear on its own, however close its look-alike scored. */
        const val CLEAR_MATCH = 0.85f
        /** Pictures kept per session. */
        const val MAX_REVIEWS = 60
        /** Red lights before the start (races). */
        const val START_LIGHTS = 5
    }
}
