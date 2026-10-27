package clip

import app.anylap.data.LineSpec
import app.anylap.vision.AnalysisFrame
import app.anylap.vision.AnalyzerConfig
import app.anylap.vision.CrossingEvent
import app.anylap.vision.Fingerprint
import app.anylap.vision.FrameAnalyzer
import app.anylap.vision.RacerRef
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

const val DIR = "/home/claude/blend/clips"
const val FPS = 60.0

class Clip(val w: Int, val h: Int, val y: List<ByteArray>, val u: List<ByteArray>, val v: List<ByteArray>, val meta: Map<String, String>,
           val fw: Int = 0, val fh: Int = 0, val full: List<ByteArray> = emptyList()) {
    val n get() = y.size
    companion object {
        fun load(name: String): Clip {
            val f = File("$DIR/$name.yuv")
            val bytes = f.readBytes()
            val j = File("$DIR/$name.json").takeIf { it.exists() }?.readText() ?: "{}"
            val meta = flatJson(j)
            val hasFull = meta["full"] == "true"
            val bb = ByteBuffer.wrap(bytes, 0, 20).order(ByteOrder.LITTLE_ENDIAN)
            val w = bb.int; val h = bb.int; val n = bb.int
            var off = 12
            var fw = 0; var fh = 0
            if (hasFull) { fw = bb.int; fh = bb.int; off = 20 }
            val ys = ArrayList<ByteArray>(); val us = ArrayList<ByteArray>(); val vs = ArrayList<ByteArray>(); val fs = ArrayList<ByteArray>()
            val p = w * h
            repeat(n) {
                ys += bytes.copyOfRange(off, off + p); off += p
                us += bytes.copyOfRange(off, off + p); off += p
                vs += bytes.copyOfRange(off, off + p); off += p
                if (hasFull) { fs += bytes.copyOfRange(off, off + fw * fh); off += fw * fh }
            }
            return Clip(w, h, ys, us, vs, meta, fw, fh, fs)
        }
        /** Tiny flat JSON reader (strings and numbers only; arrays kept as raw text). */
        fun flatJson(s: String): Map<String, String> {
            val out = HashMap<String, String>()
            Regex("\"(\\w+)\"\\s*:\\s*(\"[^\"]*\"|\\[[^\\]]*\\]|true|false|[-0-9.eE]+)").findAll(s).forEach { m ->
                out[m.groupValues[1]] = m.groupValues[2].trim('"')
            }
            return out
        }
    }
}

/** A pass laid onto the timeline: clip frame k shows at timeline index offset + k. */
class Placed(val clip: Clip, val offset: Int, val lane: Float)

/** Builds timeline frames: the empty track + any passes on screen + fresh sensor noise per frame. */
class Timeline(val empty: Clip, seed: Int = 3) {
    private val rnd = Random(seed)
    private val w = empty.w; private val h = empty.h
    private val gauss = FloatArray(4096) { // cheap gaussian table
        var s = 0f; repeat(6) { s += rnd.nextFloat() }; (s - 3f) * 1.41f
    }
    val placed = ArrayList<Placed>()
    /** Plain clip played from [offset], frames replacing the background entirely (e.g. Victor's full render). */
    var base: Pair<Clip, Int>? = null
    /** Frames before the base clip starts (default: the base clip's own first frame). */
    var lead: Clip? = null

    fun frame(i: Int): AnalysisFrame {
        val p = w * h
        val y = ByteArray(p); val u = ByteArray(p); val v = ByteArray(p)
        val b = base
        val bgY: ByteArray; val bgU: ByteArray; val bgV: ByteArray
        if (b != null && i - b.second in 0 until b.first.n) {
            val k = (i - b.second); bgY = b.first.y[k]; bgU = b.first.u[k]; bgV = b.first.v[k]
        } else if (b != null && i - b.second >= b.first.n) {
            val k = b.first.n - 1; bgY = b.first.y[k]; bgU = b.first.u[k]; bgV = b.first.v[k]
        } else if (b != null) {
            // lead-in: the base clip's own first (empty) frame, so its shadows/props match
            val l = lead ?: b.first
            bgY = l.y[0]; bgU = l.u[0]; bgV = l.v[0]
        } else { bgY = empty.y[0]; bgU = empty.u[0]; bgV = empty.v[0] }
        bgY.copyInto(y); bgU.copyInto(u); bgV.copyInto(v)
        // far lanes first so nearer cars cover them (camera stands at +x)
        val eY = empty.y[0]; val eU = empty.u[0]; val eV = empty.v[0]
        for (pl in placed.filter { i - it.offset in 0 until it.clip.n }.sortedBy { it.lane }) {
            val k = i - pl.offset
            val cy = pl.clip.y[k]; val cu = pl.clip.u[k]; val cv = pl.clip.v[k]
            for (j in 0 until p) {
                val d = abs((cy[j].toInt() and 0xFF) - (eY[j].toInt() and 0xFF)) +
                    abs((cu[j].toInt() and 0xFF) - (eU[j].toInt() and 0xFF)) +
                    abs((cv[j].toInt() and 0xFF) - (eV[j].toInt() and 0xFF))
                if (d > 6) { y[j] = cy[j]; u[j] = cu[j]; v[j] = cv[j] }
            }
        }
        var g = rnd.nextInt(gauss.size)
        for (j in 0 until p) {
            y[j] = ((y[j].toInt() and 0xFF) + (gauss[g] * 3f).toInt()).coerceIn(0, 255).toByte(); g = (g + 7) and 4095
            u[j] = ((u[j].toInt() and 0xFF) + (gauss[g] * 1.5f).toInt()).coerceIn(0, 255).toByte(); g = (g + 13) and 4095
            v[j] = ((v[j].toInt() and 0xFF) + (gauss[g] * 1.5f).toInt()).coerceIn(0, 255).toByte(); g = (g + 3) and 4095
        }
        // full-resolution luma for the split step, when the base clip carries one
        val fullSrc: Pair<Clip, Int>? = b?.let { bb ->
            val k = i - bb.second
            when {
                bb.first.full.isEmpty() -> null
                k in 0 until bb.first.n -> bb.first to k
                k >= bb.first.n -> bb.first to bb.first.n - 1
                else -> (lead ?: bb.first).let { l -> if (l.full.isEmpty()) null else l to 0 }
            }
        }
        if (fullSrc == null || System.getenv("FULLOFF") != null) return AnalysisFrame((i * 1e9 / FPS).toLong(), w, h, y, u, v, w, h) { _, _, _, _ -> null }
        val (fc, fk) = fullSrc
        val plane = fc.full[fk]
        return AnalysisFrame((i * 1e9 / FPS).toLong(), w, h, y, u, v, fc.fw, fc.fh) { x0, y0, cw, ch ->
            if (x0 < 0 || y0 < 0 || x0 + cw > fc.fw || y0 + ch > fc.fh) null else {
                val out = ByteArray(cw * ch)
                for (yy in 0 until ch) System.arraycopy(plane, (y0 + yy) * fc.fw + x0, out, yy * cw, cw)
                out
            }
        }
    }
}

lateinit var LINE: LineSpec
/** Lane x (m) → position along the line in the image, projected through the scene camera (along.json). */
val ALONG: Map<Int, Double> by lazy {
    Regex("\"(-?[0-9.]+)\":\\s*(-?[0-9.eE]+)").findAll(File("/home/claude/blend/along.json").readText())
        .associate { (it.groupValues[1].toDouble() * 1000).toInt() to it.groupValues[2].toDouble() }
}
fun alongOf(x: Double) = ALONG[(x * 1000).toInt().coerceIn(-450, 450)] ?: Double.NaN
var MPP = 0f

/** Wizard-style calibration: the car alone, slow then fast pass; each accepted pass becomes a view. */
val HINTS = ArrayList<FloatArray>()

fun calibrate(car: String, empty: Clip): RacerRef {
    val passes = listOf(Clip.load("cal_${car}_3"), Clip.load("cal_${car}_7"))
    val tl = Timeline(empty, seed = car.hashCode())
    var at = 120
    for (c in passes) { tl.placed += Placed(c, at, 0f); at += c.n + 40 }
    val views = ArrayList<FloatArray>()
    val matches = ArrayList<Float>()
    lateinit var a: FrameAnalyzer
    fun cfg() = AnalyzerConfig(
        line = LINE, racers = listOf(RacerRef("x", if (views.isEmpty()) null else Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray(), null, views.toList())),
        minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP,
    )
    a = FrameAnalyzer(onCrossing = { e ->
        if (views.isNotEmpty()) matches += e.match
        if (e.accepted && e.fingerprint != null) {
            views += e.fingerprint!!; a.config = cfg()
            if (e.area > 0) HINTS += floatArrayOf(e.along, e.area.toFloat())
        }
    })
    a.config = cfg()
    for (i in 0 until at) a.process(tl.frame(i))
    a.flush((at * 1e9 / FPS).toLong())
    println("  calibrated $car: ${views.size}/2 passes" + if (matches.isNotEmpty()) ", 2nd pass looked ${(matches[0] * 100).toInt()} % like the 1st" else "")
    val fp = if (views.isEmpty()) null else Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray()
    return RacerRef(car, fp, null, views)
}

class Truth(val t: Double, val car: String, val speed: Double, val along: Double = Double.NaN)

class Tally(var n: Int = 0, var found: Int = 0, var extra: Int = 0, var right: Int = 0, var wrong: Int = 0, var unknown: Int = 0, var late10: Int = 0, var orderOk: Int = 0, var orderN: Int = 0, val tErr: ArrayList<Double> = ArrayList())
val TOTAL = Tally()

fun runRace(name: String, tl: Timeline, frames: Int, racers: List<RacerRef>, truth: List<Truth>, minLapSec: Double = 2.5, quiet: Boolean = false) {
    val events = ArrayList<CrossingEvent>()
    val a = FrameAnalyzer(onCrossing = { events += it })
    a.config = AnalyzerConfig(line = LINE, racers = racers, minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP, sizeHints = HINTS.toList())
    val t0 = System.currentTimeMillis()
    for (i in 0 until frames) a.process(tl.frame(i))
    a.flush((frames * 1e9 / FPS).toLong())
    val secs = (System.currentTimeMillis() - t0) / 1000

    val acc = events.filter { it.accepted }
    val used = HashSet<CrossingEvent>()
    var ok = 0; var wrong = 0; var unknown = 0
    val tErr = ArrayList<Double>(); val vErr = ArrayList<Double>()
    val notes = ArrayList<String>()
    // Pair engine crossings with true ones by time AND position along the line (lane): several cars
    // can cross within one frame in different lanes. Greedy on cost = dt/50 ms + dAlong/0.1.
    fun cost(e: CrossingEvent, p: Truth): Double {
        val dt = abs(e.timestampNs / 1e9 - p.t)
        if (dt >= 0.15) return Double.MAX_VALUE
        val da = if (p.along.isNaN()) 0.0 else abs(e.along - p.along)
        return dt / 0.05 + da / 0.1
    }
    val pairs = ArrayList<Triple<Double, CrossingEvent, Truth>>()
    for (p in truth) for (e in acc) { val c = cost(e, p); if (c < Double.MAX_VALUE) pairs += Triple(c, e, p) }
    pairs.sortBy { it.first }
    val match = HashMap<Truth, CrossingEvent>()
    for ((_, e, p) in pairs) if (e !in used && p !in match) { used += e; match[p] = e }
    used.clear()
    for (p in truth.sortedBy { it.t }) {
        val e = match[p]
        if (e == null) {
            val rej = events.firstOrNull { !it.accepted && abs(it.timestampNs / 1e9 - p.t) < 0.15 }
            notes += "MISSED   %6.2f s  %-10s%s".format(p.t, p.car, rej?.let { " (turned down, looked %.0f %% like %s)".format(it.match * 100, it.candidates.firstOrNull()?.first) } ?: "")
            continue
        }
        used += e
        tErr += abs(e.timestampNs / 1e9 - p.t) * 1000
        if (abs(e.timestampNs / 1e9 - p.t) > 0.010) notes += "TIMING   %6.2f s  %-10s off by %+.0f ms%s".format(p.t, p.car, (e.timestampNs / 1e9 - p.t) * 1000, if (e.split) " (split)" else "")
        e.speedKmh?.let { vErr += abs(it / 3.6 - p.speed) / p.speed * 100 }
        when (e.racerId) {
            p.car -> ok++
            null -> { unknown++; notes += "UNKNOWN  %6.2f s  %-10s best %s".format(p.t, p.car, e.candidates.take(2).joinToString { "%s %.2f".format(it.first, it.second) }) }
            else -> { wrong++; notes += "WRONG    %6.2f s  %-10s read as %s (%.2f vs own %.2f)".format(p.t, p.car, e.racerId, e.candidates[0].second, e.candidates.firstOrNull { it.first == p.car }?.second ?: 0f) }
        }
    }
    val extras = acc.filter { it !in used }
    val rescues = events.filter { it.rescue }
    for (r in rescues) {
        val hit = truth.firstOrNull { abs(it.t - r.timestampNs / 1e9) < 0.15 && match[it] == null }
        notes += "RESCUE   %6.2f s  %s (est. finish) – %s".format(r.timestampNs / 1e9, r.candidates.firstOrNull()?.first ?: "?", if (hit != null) "WOULD RESCUE missed ${hit.car}" else "no missed crossing there (false alarm)")
    }
    for (e in extras) notes += "EXTRA    %6.2f s  read as %s%s area %d".format(e.timestampNs / 1e9, e.racerId ?: "unknown", if (e.split) " (split)" else "", e.area)

    TOTAL.n += truth.size; TOTAL.found += used.size; TOTAL.extra += extras.size; TOTAL.right += ok; TOTAL.wrong += wrong; TOTAL.unknown += unknown
    TOTAL.late10 += tErr.count { it > 10 }; TOTAL.tErr += tErr
    println("\n== $name  (${frames} frames, ${secs}s)")
    println("crossings ${truth.size}: found ${used.size}, missed ${truth.size - used.size}, extra ${extras.size}, turned down by gate ${events.count { !it.accepted }}")
    println("identity: right $ok, wrong $wrong, unknown $unknown")
    if (tErr.isNotEmpty()) println("timing error: mean %.1f ms, max %.1f ms".format(tErr.average(), tErr.max()))
    if (vErr.isNotEmpty()) println("speed error: mean %.1f %%, max %.1f %%  (one metres-per-pixel for all lanes)".format(vErr.average(), vErr.max()))

    // ---- laps and running order: engine view vs truth, sampled right after every true crossing
    val trueCross = truth.map { it.t to it.car }
    /** Same order, except swaps inside photo finishes (same laps, true crossings < 10 ms apart). */
    fun sameOrder(truthOrder: List<String>, eng: List<String>, until: Double): Boolean {
        if (truthOrder.size != eng.size) return false
        val last = HashMap<String, Double>()
        for ((t, c) in trueCross.sortedBy { it.first }) if (t <= until) last[c] = t
        var i = 0
        while (i < truthOrder.size) {
            var j = i + 1
            while (j < truthOrder.size && abs((last[truthOrder[j]] ?: 0.0) - (last[truthOrder[j - 1]] ?: 0.0)) < 0.010) j++
            if (truthOrder.subList(i, j).toSet() != eng.subList(i, j).toSet()) return false
            i = j
        }
        return true
    }
    fun standings(cross: List<Pair<Double, String>>, until: Double): List<String> {
        val laps = HashMap<String, Int>(); val last = HashMap<String, Double>()
        for ((t, c) in cross.sortedBy { it.first }) {
            if (t > until) break
            val prev = last[c]
            if (prev != null && t - prev < minLapSec) continue   // the app's minimum lap time
            laps[c] = (laps[c] ?: 0) + 1; last[c] = t
        }
        return laps.keys.sortedWith(compareByDescending<String> { laps[it] }.thenBy { last[it] })
    }
    val engCross = acc.filter { it.racerId != null }.map { it.timestampNs / 1e9 to it.racerId!! }
    var same = 0; var leaderSame = 0
    val tt = truth.map { it.t }.sorted()
    val checks = tt.indices.map { i -> if (i + 1 < tt.size) (tt[i] + tt[i + 1]) / 2 else tt[i] + 0.3 }
    for (t in checks) {
        val a1 = standings(trueCross, t); val b1 = standings(engCross, t)
        if (sameOrder(a1, b1, t)) same++ else if (System.getenv("ORDERDBG") != null) {
            println("  order @%.2f\n    truth  %s\n    engine %s".format(t, a1, b1))
            for (c in listOf("Gul_bil", "Rosa_bil", "Röd_bil", "Orange_bil")) println("    %s truth %s engine %s".format(c,
                trueCross.filter { it.second == c && it.first <= t }.map { "%.4f".format(it.first) }, engCross.filter { it.second == c && it.first <= t }.map { "%.4f".format(it.first) }))
        }
        if (a1.firstOrNull() == b1.firstOrNull()) leaderSame++
    }
    TOTAL.orderOk += same; TOTAL.orderN += checks.size
    println("running order identical after %d / %d crossings · leader right %d / %d".format(same, checks.size, leaderSame, checks.size))
    val endT = truth.maxOf { it.t } + 1
    val tf = standings(trueCross, endT); val ef = standings(engCross, endT)
    fun lapsOf(cross: List<Pair<Double, String>>, c: String): Int {
        var n = 0; var last = -99.0
        for ((t, x) in cross.sortedBy { it.first }) if (x == c && t - last >= minLapSec) { n++; last = t }
        return n
    }
    println("final order  truth: " + tf.joinToString(" ") { "$it(${lapsOf(trueCross, it)})" })
    println("final order engine: " + ef.joinToString(" ") { "$it(${lapsOf(engCross, it)})" })
    notes.forEach { println("  $it") }
}

fun main_lap(args: Array<String>) {
    val empty = Clip.load("empty")
    val meta = Clip.flatJson(File("/home/claude/blend/meta.json").readText())
    val nums = Regex("[-0-9.eE]+").findAll(File("/home/claude/blend/meta.json").readText().substringAfter("\"line\"").substringBefore("]]")).map { it.value.toFloat() }.toList()
    LINE = LineSpec(nums[0], nums[1], nums[2], nums[3])
    MPP = meta["metersPerProcPx"]!!.toFloat()
    val cars = File(DIR).list()!!.filter { it.startsWith("cal_") && it.endsWith("_3.yuv") }.map { it.removePrefix("cal_").removeSuffix("_3.yuv") }.sorted()
    println("line $LINE, ${"%.2f".format(MPP * 1000)} mm/px, cars $cars")
    val racers = cars.map { calibrate(it, empty) }
    println("calibration look-alikes (similarity of each car's fingerprint to the others, ≥0.80 shown):")
    for (a in racers) for (b in racers) if (a.id < b.id) {
        val s = Fingerprint.similarity(a.fingerprint!!, b.fingerprint!!)
        if (s >= 0.80f) println("  %-10s ~ %-10s %.2f".format(a.id, b.id, s))
    }

    val mode = args.firstOrNull() ?: "lap1"
    if (mode == "lap1" || mode == "all") {
        // Victor's render: 2 s empty track, his 80 frames, then 0.5 s of the last frame
        val truth = File("/home/claude/blend/crossings.csv").readLines().drop(1).map { l ->
            val p = l.split(","); Truth((120 + p[0].toDouble() - 1) / FPS, p[1].replace(' ', '_'), p[3].toDouble(), alongOf(p[4].toDouble()))
        }
        for (src in listOf("race_victor", "race_mine")) {
            val tl = Timeline(empty, seed = 11); tl.base = Clip.load(src) to 120
            runRace("clip 1, all 9 cars, $src (shutter 1/120, legs, shadow)", tl, 120 + 80 + 30, racers, truth)
        }
        val truth2 = File("/home/claude/blend/crossings2.csv").readLines().drop(1).map { l ->
            val p = l.split(","); Truth((120 + p[0].toDouble() - 1) / FPS, p[1].replace(' ', '_'), p[3].toDouble(), alongOf(p[4].toDouble()))
        }
        for (src in listOf("race_victor3", "race3")) {
            val tl2 = Timeline(empty, seed = 12); tl2.base = Clip.load(src) to 120
            runRace("clip 3, $src: 3 cars abreast + 1 right behind, shutter 1/120", tl2, 120 + 72 + 30, racers, truth2)
        }
    }
    if (mode == "laps" || mode == "all") {
        val passes = File(DIR).list()!!.filter { it.startsWith("pass_") && it.endsWith(".yuv") }.sorted().map { Clip.load(it.removeSuffix(".yuv")) }
        val seeds = (args.getOrNull(1)?.toInt() ?: 1)..(args.getOrNull(2)?.toInt() ?: 1)
        TOTAL.n = 0; TOTAL.found = 0; TOTAL.extra = 0; TOTAL.right = 0; TOTAL.wrong = 0; TOTAL.unknown = 0; TOTAL.late10 = 0; TOTAL.orderOk = 0; TOTAL.orderN = 0; TOTAL.tErr.clear()
        for (seed in seeds) {
            val (tl, truth, frames) = scheduleLaps(empty, passes, cars, laps = 3, lapS = 18.0, seed = seed)
            runRace("race $seed: 3 laps × 9 cars, ~18 s laps, random lap times + forced close calls", tl, frames, racers, truth)
        }
        val t = TOTAL
        println("\n#### ${seeds.count()} races, ${t.n} crossings: found ${t.found} (missed ${t.n - t.found} = %.2f %%), extra ${t.extra}, named right ${t.right}, wrong ${t.wrong}, unknown ${t.unknown}".format(100.0 * (t.n - t.found) / t.n))
        println("#### timing: mean %.1f ms, >10 ms off: ${t.late10} · running order identical after ${t.orderOk} / ${t.orderN} crossings".format(t.tErr.average()))
    }
}

/** Rolling start, ~18 s laps with per-car pace and per-lap noise, plus forced close calls. */
fun scheduleLaps(empty: Clip, passes: List<Clip>, cars: List<String>, laps: Int, lapS: Double, seed: Int): Triple<Timeline, List<Truth>, Int> {
    val rnd = Random(seed)
    val byCar = passes.groupBy { it.meta["car"]!! }
    data class Ev(var t: Double, val car: String, val pass: Clip)
    val evs = ArrayList<Ev>()
    cars.forEachIndexed { i, c ->
        val pace = lapS + (rnd.nextDouble() - 0.5) * 2.0          // each car ±1 s from 18 s
        var t = 3.0 + i * 0.55                                   // rolling start, cars 0.55 s apart
        repeat(laps) {
            evs += Ev(t, c, byCar[c]!!.random(rnd))
            t += pace + (rnd.nextDouble() - 0.5) * 0.8          // lap-to-lap spread ±0.4 s
        }
    }
    fun lane(p: Clip) = p.meta["lane"]!!.toFloat()
    // forced close calls on lap 2 and 3: pairs of cars brought to the line together
    val l2 = evs.filter { it.t > 3 + lapS * 0.6 && it.t < 3 + lapS * 1.6 }.sortedBy { it.t }
    if (l2.size >= 6) {
        l2[1].t = l2[0].t + 2 / FPS                                // overtake, 2 frames apart
        l2[4].t = l2[3].t                                          // same instant
    }
    // keep same-lane passes from overlapping (cars can't drive through each other)
    evs.sortBy { it.t }
    for (i in evs.indices) for (j in 0 until i) {
        if (lane(evs[i].pass) == lane(evs[j].pass) && abs(evs[i].t - evs[j].t) < 0.12) evs[i].t = evs[j].t + 0.15
    }
    val tl = Timeline(empty, seed = seed * 31)
    val truth = ArrayList<Truth>()
    for (e in evs) {
        val crossIdx = e.pass.meta["crossIdx"]!!.toDouble()
        val off = (e.t * FPS - crossIdx).toInt()
        val exact = (off + crossIdx) / FPS
        tl.placed += Placed(e.pass, off, lane(e.pass))
        truth += Truth(exact, e.car, e.pass.meta["speed"]!!.toDouble(), alongOf(lane(e.pass).toDouble()))
    }
    val frames = ((truth.maxOf { it.t } + 1.5) * FPS).toInt()
    return Triple(tl, truth, frames)
}
