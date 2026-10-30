package app.anylap.engine

import app.anylap.data.CalibrationSample
import app.anylap.data.Racer
import app.anylap.data.SampleKind
import app.anylap.data.withCalibration
import app.anylap.vision.CrossingEvent
import app.anylap.vision.Fingerprint
import app.anylap.vision.IdSource
import kotlin.math.abs

/**
 * Self-learning (fail-safe 3): a crossing the camera, the lap timing and – where there was one – the human
 * all agreed on becomes one more view of the racer, kept with its calibration as a [SampleKind.LEARNED]
 * sample. Views are colour only; size and the top-down picture stay with the calibration passes, because
 * the camera may stand somewhere else next time. Everything here is pure (no files, no engine) so the
 * rules can be tested; [RaceEngine.learned] gathers the candidates, the app applies [merge].
 */
object Learning {
    /** Learned views kept per racer; when full, the one most like the newcomer makes room (variety wins). */
    const val MAX_LEARNED = 24
    /** At most this many views are learned from one session per racer (the most novel ones). */
    const val PER_SESSION = 8
    /** A view this alike an existing learned view teaches nothing. */
    const val NEAR_DUPLICATE = 0.97f
    /** A crossing that looks this unlike the racer's calibration is not learned on the camera's word alone (a verdict still counts). */
    const val MIN_CAL_SIMILARITY = 0.45f
    /** Appearance: at least this like the racer, and either a clear margin or a clear match on its own. */
    const val MIN_MATCH = 0.70f
    /** A fit that re-named the crossing is trusted when its own score is at least this. */
    const val MIN_FIT = 0.70f
    /** The lap ending at the crossing must be within this band of the racer's median lap. */
    const val LAP_LOW = 0.75f
    const val LAP_HIGH = 1.30f
    /** Blob size ÷ one racer: outside this band there may be two cars (or half of one) in it. */
    const val SIZE_LOW = 0.7f
    const val SIZE_HIGH = 1.3f
    /** Laps the racer must have before its rhythm is trusted. */
    const val MIN_LAPS = 3

    /** How a crossing was confirmed: the camera + lap timing ("auto"), the fit re-naming it + lap timing ("fit"), a human ("verdict"). */
    enum class By(val key: String) { AUTO("auto"), FIT("fit"), VERDICT("verdict") }

    /** A confirmed crossing with everything a learned sample and an uploaded crop need. */
    class Confirmed(val racerId: String, val event: CrossingEvent, val by: By, val atMs: Long)

    /**
     * Whether a counted crossing is certain enough to learn from. [lapsMs] = the racer's lap times so far
     * (in session order), [lapMs] = the lap this crossing ended (null = none: practice start, race lap 1),
     * [onCard] = the crossing is on a Check card (then only a verdict can confirm it), [locked] = a human
     * assigned it live.
     */
    fun certainty(racerId: String, e: CrossingEvent, lapsMs: List<Long>, lapMs: Long?, onCard: Boolean, locked: Boolean): By? {
        if (onCard) return null
        if (!e.accepted || e.rescue || e.split || e.area <= 0) return null
        val fp = e.fingerprint ?: return null
        if (fp.size != Fingerprint.SIZE) return null
        if (e.sizeRatio > 0f && (e.sizeRatio < SIZE_LOW || e.sizeRatio > SIZE_HIGH)) return null
        if (!lapOk(lapsMs, lapMs)) return null
        if (locked) return By.VERDICT
        if (e.source == IdSource.MARKER) return By.AUTO
        if (e.source != IdSource.APPEARANCE || e.racerId != racerId) return null
        val fit = e.fit
        if (fit != null && fit.renamed) {
            val score = fit.scores.firstOrNull { it.first == racerId }?.second ?: 0f
            return if (score >= MIN_FIT) By.FIT else null
        }
        val colour = e.candidates.firstOrNull { it.first == racerId }?.second ?: e.match
        if (colour < MIN_MATCH) return null
        if (e.confidence < RaceEngine.LOW_MARGIN && colour < RaceEngine.CLEAR_MATCH) return null
        return By.AUTO
    }

    /** The lap ending here fits the racer's own rhythm (needs [MIN_LAPS] laps; the median is robust to a spin). */
    fun lapOk(lapsMs: List<Long>, lapMs: Long?): Boolean {
        if (lapMs == null || lapMs <= 0) return false
        if (lapsMs.size < MIN_LAPS) return false
        val sorted = lapsMs.sorted()
        val median = sorted[sorted.size / 2].toFloat()
        if (median <= 0f) return false
        val r = lapMs / median
        return r in LAP_LOW..LAP_HIGH
    }

    /**
     * Adds learned views to a racer: near-duplicates are skipped, at most [PER_SESSION] per call (the most
     * novel first), at most [MAX_LEARNED] in all (the one most like the newcomer makes room). [samples] must
     * be [SampleKind.LEARNED]. Views that look too unlike the calibration are only taken on a verdict.
     * @return the racer with its fingerprint recomputed, and the samples that were dropped (their files can go).
     */
    fun merge(racer: Racer, samples: List<CalibrationSample>): Pair<Racer, List<CalibrationSample>> {
        val cal = racer.fingerprint.takeIf { it.size == Fingerprint.SIZE }?.toFloatArray()
        val existing = racer.calibration.filter { it.kind == SampleKind.LEARNED }.toMutableList()
        val fixed = racer.calibration.filter { it.kind != SampleKind.LEARNED }
        val dropped = ArrayList<CalibrationSample>()
        val wanted = samples.filter { s ->
            s.kind == SampleKind.LEARNED && s.fingerprint.size == Fingerprint.SIZE &&
                (s.learnedBy == By.VERDICT.key || cal == null || Fingerprint.similarity(cal, s.fingerprint.toFloatArray()) >= MIN_CAL_SIMILARITY)
        }
        // most novel first: least alike anything the racer already has
        val known = (fixed + existing).map { it.fingerprint.toFloatArray() }
        val ranked = wanted.sortedBy { s -> known.maxOfOrNull { Fingerprint.similarity(it, s.fingerprint.toFloatArray()) } ?: 0f }
        var taken = 0
        for (s in ranked) {
            if (taken >= PER_SESSION) { dropped += s; continue }
            val f = s.fingerprint.toFloatArray()
            if (existing.any { Fingerprint.similarity(it.fingerprint.toFloatArray(), f) >= NEAR_DUPLICATE }) { dropped += s; continue }
            if (existing.size >= MAX_LEARNED) {
                val victim = existing.maxByOrNull { Fingerprint.similarity(it.fingerprint.toFloatArray(), f) } ?: break
                existing.remove(victim); dropped += victim
            }
            existing += s
            taken++
        }
        if (taken == 0) return racer to dropped
        return racer.withCalibration(fixed + existing) to dropped
    }

    /** The lap this crossing ended, from the racer's counted crossings (ms, session time); null for the first of a practice. */
    fun lapEndingAt(crossingsMs: List<Long>, atMs: Long, raceStart: Boolean): Long? {
        val i = crossingsMs.indexOfFirst { abs(it - atMs) <= 1 }
        if (i < 0) return null
        if (i == 0) return if (raceStart) atMs else null
        return atMs - crossingsMs[i - 1]
    }
}
