package clip

import app.anylap.data.LineSpec
import app.anylap.engine.LapSolver
import app.anylap.data.FinishEdge
import app.anylap.vision.AnalyzerConfig
import app.anylap.vision.CardFit
import app.anylap.vision.LabelLayout
import app.anylap.vision.CrossingEvent
import app.anylap.vision.Fingerprint
import app.anylap.vision.FrameAnalyzer
import app.anylap.vision.RacerRef
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 10 minis × 10 laps from lights-out, run through the engine + LapIdentity and scored against the
 * truth (nose crossing times). Diagonal crossings (25° and 38°), a bunched standing start, a forced
 * photo finish (1 frame apart, abreast), a three-wide arrival, spins. Writes out/race10_<seed>.json
 * and frames for the Check cards. Usage: Race10 <seed> [laps] [gate]
 */
private const val OUT = "/home/claude/blend/out"

/** Check-card canvas: [CARD_S] px square, the picture centred with at least a [CARD_M] px free band round it for the tags (the page draws the same). */
const val CARD_S = 420f
const val CARD_M = 40f
const val CARD_CHAR = 8.4f
const val CARD_TAG_H = 24f

fun main(args: Array<String>) {
    val seed = args.getOrNull(0)?.toInt() ?: 1
    val laps = args.getOrNull(1)?.toInt() ?: 10
    val useFit = System.getenv("NOFIT") == null
    if (System.getenv("FITALL") != null) FrameAnalyzer.FIT_ALL = true
    val metaTxt = File("/home/claude/blend/form/minis10/meta.json").readText()
    val nums = Regex("[-0-9.eE]+").findAll(metaTxt.substringAfter("\"line\"").substringBefore("]]")).map { it.value.toFloat() }.toList()
    LINE = LineSpec(nums[0], nums[1], nums[2], nums[3])
    MPP = Clip.flatJson(metaTxt)["metersPerProcPx"]!!.toFloat()
    val alongMap = Regex("\"(-?[0-9.]+)\":\\s*(-?[0-9.eE]+)").findAll(metaTxt.substringAfter("\"along\"")).associate { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
    fun along(lane: Double) = alongMap.minByOrNull { abs(it.key - lane) }!!.value
    val empty = Clip.load("minis_empty")
    val all = File(DIR).list()!!.filter { it.startsWith("minis_") && it.endsWith(".yuv") }.map { it.removeSuffix(".yuv") }
    val cars = all.filter { it.startsWith("minis_cal_") && it.contains("_3.5_") }.map { it.removePrefix("minis_cal_").substringBefore("_+0.00") }.sorted()
    println("cars: $cars · line $LINE · ${"%.2f".format(MPP * 1000)} mm/px · picture fit ${if (useFit) "on" else "off"}")
    HINTS.clear()
    val calErr = ArrayList<Double>()
    val racers = cars.map { c -> calibrateMini(c, empty, calErr) }.map { if (useFit) it else it.copy(template = null) }
    for (r in racers) r.template?.let { println("  picture ${r.id}: ${it.w}x${it.h} px, true size %.1f x %.1f px".format(it.lengthPx, it.widthPx)) }
    println("calibration-pass timing error (nose): mean %.1f ms, signed mean %.1f ms".format(calErr.map { abs(it) }.average(), calErr.average()))
    val sim = HashMap<Pair<String, String>, Float>()
    for (a in racers) for (b in racers) if (a.fingerprint != null && b.fingerprint != null) sim[a.id to b.id] = Fingerprint.similarity(a.fingerprint!!, b.fingerprint!!)
    if (args.getOrNull(2) == "cal") return
    if (args.getOrNull(2) == "gate") { gateTest(empty, racers); return }

    val passes = cars.associateWith { c -> all.filter { it.startsWith("minis_race_${c}_") || it.startsWith("minis_diag_${c}_") }.map { Clip.load(it) } }

    // ---------------------------------------------------------------- schedule
    val rnd = Random(seed)
    class Ev(var t: Double, val car: String, var lane: Double, var pass: Clip?, val lap: Int, var tag: String = "")
    val evs = ArrayList<Ev>()
    val pace = cars.associateWith { 17.6 + rnd.nextDouble() * 1.0 }
    val grid = cars.shuffled(rnd)
    val incidents = ArrayList<String>()
    for (c in cars) {
        var t = pace[c]!! + 1.2 + grid.indexOf(c) * 0.22          // standing start: lap 1 includes the getaway, bunched
        for (l in 1..laps) {
            evs += Ev(t, c, 0.0, null, l)
            var lapT = pace[c]!! + rnd.nextDouble() * 0.6 - 0.3
            if (l < laps && rnd.nextDouble() < 0.04) { val d = 2.0 + rnd.nextDouble() * 4; lapT += d; incidents += "%s lap %d: +%.1f s (spin)".format(c, l + 1, d) }
            t += lapT
        }
    }
    // forced: photo finish on lap 4 (2nd car 1 frame behind, abreast) and a three-wide arrival on lap 7
    fun shiftCar(c: String, fromLap: Int, dt: Double) { for (e in evs) if (e.car == c && e.lap >= fromLap) e.t += dt }
    // pick the cars that are already closest there, so the forced shift is small (a plausible lap, not a teleport)
    fun at(c: String, l: Int) = evs.first { it.car == c && it.lap == l }.t
    val pf = (3..5).flatMap { l -> cars.flatMap { x -> cars.filter { it > x }.map { y -> Triple(l, x, y) } } }.minByOrNull { (l, x, y) -> abs(at(x, l) - at(y, l)) }!!
    run { val (l, x, y) = pf; val d = at(x, l) + 1 / 60.0 - at(y, l); shiftCar(y, l, d); evs.first { it.car == x && it.lap == l }.tag = "photo"; evs.first { it.car == y && it.lap == l }.tag = "photo"; println("photo finish: lap $l $x / $y (shifted %.2f s)".format(d)) }
    val rest = cars.filter { it != pf.second && it != pf.third }
    val tw = (6..8).flatMap { l -> rest.flatMap { x -> rest.filter { it > x }.flatMap { y -> rest.filter { it > y }.map { z -> listOf(l.toString(), x, y, z) } } } }
        .minByOrNull { q -> val ts = q.drop(1).map { at(it, q[0].toInt()) }; ts.max() - ts.min() }!!
    run {
        val l = tw[0].toInt(); val tri = tw.drop(1).sortedBy { at(it, l) }
        for ((k, c) in tri.withIndex()) if (k > 0) shiftCar(c, l, at(tri[0], l) + 0.03 * k - at(c, l))
        for (c in tri) evs.first { it.car == c && it.lap == l }.tag = "three"
        println("three wide: lap $l $tri")
    }
    evs.sortBy { it.t }

    val lanesAll = listOf(-0.2, -0.1, 0.1, 0.2)
    var abreast = 0; var diag = 0
    for (i in evs.indices) {
        val e = evs[i]
        val near = evs.filter { it !== e && abs(it.t - e.t) < 0.5 && it.pass != null }
        val hard = near.filter { abs(it.t - e.t) < 0.08 }.map { it.lane }.toSet()
        val lone = evs.none { it !== e && abs(it.t - e.t) < 0.45 }
        val ps = passes[e.car]!!
        if (lone && e.tag.isEmpty() && rnd.nextDouble() < 0.30) {           // diagonal: lane change across the line
            val d = ps.filter { it.meta["kind"] == "diag" }.random(rnd)
            e.pass = d; e.lane = d.meta["lane"]!!.toDouble(); e.tag = "diag " + d.meta["angle"]!!.toDouble().roundToInt() + "°"; diag++; continue
        }
        if (lone && e.tag.isEmpty() && rnd.nextDouble() < 0.15) {
            ps.firstOrNull { it.meta["speed"] == "7.5" }?.let { e.pass = it; e.lane = 0.0; e.tag = "fast"; continue }
        }
        val free = lanesAll.filter { it !in hard }
        val closest = near.minByOrNull { abs(it.t - e.t) }
        e.lane = when {
            free.isEmpty() -> { e.t += 0.1; lanesAll.random(rnd) }
            closest != null && abs(closest.t - e.t) < 0.15 && closest.lane in lanesAll -> {
                val nb = listOf(closest.lane - 0.1, closest.lane + 0.1).map { (it * 10).roundToInt() / 10.0 }.filter { it in free && it != 0.0 && abs(it) <= 0.2 }
                if (nb.isNotEmpty()) { abreast++; nb.random(rnd) } else free.random(rnd)
            }
            else -> free.random(rnd)
        }
        e.pass = ps.first { it.meta["kind"] == "race" && it.meta["speed"] == "5.5" && abs(it.meta["lane"]!!.toDouble() - e.lane) < 0.01 }
    }
    println("abreast arrivals: $abreast · diagonal crossings: $diag")
    val tl = Timeline(empty, seed = seed * 7)
    class Tr(val t: Double, val centre: Double, val car: String, val lap: Int, val speed: Double, val along: Double, val tag: String, val angle: Double)
    val truth = ArrayList<Tr>()
    for (e in evs) {
        val p = e.pass!!
        val crossIdx = p.meta["crossIdx"]!!.toDouble()
        val off = (e.t * FPS - crossIdx).roundToInt()
        tl.placed += Placed(p, off, e.lane.toFloat())
        truth += Tr((off + crossIdx) / FPS, (off + p.meta["centreIdx"]!!.toDouble()) / FPS, e.car, e.lap, p.meta["speed"]!!.toDouble(), along(e.lane), e.tag, p.meta["angle"]!!.toDouble())
    }
    val frames = ((truth.maxOf { it.t } + 2.0) * FPS).toInt()
    println("schedule: ${truth.size} crossings over ${"%.0f".format(frames / FPS)} s, ${incidents.size} spins")

    // ---------------------------------------------------------------- engine
    val events = ArrayList<CrossingEvent>()
    val a = FrameAnalyzer(onCrossing = { events += it })
    a.config = AnalyzerConfig(line = LINE, racers = racers, minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP, sizeHints = HINTS.toList(), finishEdge = FinishEdge.FRONT, captureSnapshots = true)
    val t0 = System.currentTimeMillis()
    for (i in 0 until frames) a.process(tl.frame(i))
    a.flush((frames * 1e9 / FPS).toLong())
    println("engine: ${(System.currentTimeMillis() - t0) / 1000} s, ${events.size} events")

    // ---------------------------------------------------------------- race level
    val ins = events.map { e ->
        LapSolver.In(e.timestampNs, if (e.accepted) e.racerId else null, e.candidates, e.confidence, e.split, e.rescue, !e.accepted && !e.rescue, e.sizeRatio, fitted = e.fit != null && e.racerId != null)
    }
    val outs = LapSolver.solve(ins, cars, 0L, 2_500_000_000L, (frames * 1e9 / FPS).toLong())
    val counted = outs.filter { it.racer != null }
    // engine-only view (what the analyzer alone would have counted) for comparison
    val engineOnly = events.filter { it.accepted && it.racerId != null }

    // ---------------------------------------------------------------- score
    // right = counted for the right car, within 0.15 s AND at the right place across the track (a swapped pair abreast is wrong)
    var swapsFound = 0
    fun scoreOf(list: List<Triple<String, Double, Float>>): Triple<Int, Int, Int> {   // right, missed, extra
        val used = HashSet<Int>(); var right = 0; var swaps = 0
        for (p in truth) {
            val k = list.indices.filter { it !in used && list[it].first == p.car && abs(list[it].second - p.t) < 0.15 }.minByOrNull { abs(list[it].second - p.t) }
            if (k != null) { used += k; if (abs(list[k].third - p.along) < 0.07) right++ else swaps++ }
        }
        swapsFound = swaps
        return Triple(right, truth.size - right - swaps, list.size - used.size + swaps)
    }
    fun alongOfEvent(i: Int) = events.getOrNull(i)?.let { e -> e.quad?.let { q -> val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4; ((cx - LINE.x1) * (LINE.x2 - LINE.x1) + (cy - LINE.y1) * (LINE.y2 - LINE.y1)) / ((LINE.x2 - LINE.x1).let { it * it } + (LINE.y2 - LINE.y1).let { it * it }) } ?: e.along } ?: 0f
    val (rightE, missedE, extraE) = scoreOf(engineOnly.map { e -> Triple(e.racerId!!, e.timestampNs / 1e9, alongOfEvent(events.indexOf(e))) })
    val swapsE = swapsFound
    val (right, missed, extraN) = scoreOf(counted.map { Triple(it.racer!!, it.ns / 1e9, alongOfEvent(it.index)) })
    val swapsL = swapsFound
    // timing error per matched crossing (nose)
    val errs = ArrayList<Double>(); val errsC = ArrayList<Double>()
    for (p in truth) {
        val o = counted.filter { it.racer == p.car && abs(it.ns / 1e9 - p.t) < 0.15 }.minByOrNull { abs(it.ns / 1e9 - p.t) } ?: continue
        errs += (o.ns / 1e9 - p.t) * 1000
        events.getOrNull(o.index)?.let { errsC += (it.centreNs / 1e9 - p.centre) * 1000 }
    }
    println("ENGINE ALONE: right $rightE / ${truth.size}, missed $missedE, extra $extraE (wrong place $swapsE)")
    println("WITH LAP SOLVER: right $right / ${truth.size}, missed $missed, extra $extraN, wrong place $swapsL · nose timing |err| mean %.1f ms, max %.1f, bias %.1f · centre |err| %.1f".format(errs.map { abs(it) }.average(), errs.maxOf { abs(it) }, errs.average(), errsC.map { abs(it) }.average()))
    println("decisions: " + outs.groupingBy { it.why }.eachCount())

    // ---------------------------------------------------------------- Check-card outlines (same code as the app's tray)
    File("$OUT/r10_$seed").mkdirs()
    fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\"", "'").replace("\\", "/") + "\""
    val pool = racers.mapNotNull { r -> r.template?.let { r.id to it } }
    for ((id, t) in pool) File("$OUT/tpl_$id.pgm").outputStream().buffered().use { o -> o.write("P5\n${t.w} ${t.h * 2}\n255\n".toByteArray()); o.write(ByteArray(t.w * t.h * 2) { i -> if (i < t.w * t.h) t.y[i].toInt().coerceIn(0, 255).toByte() else (t.mask[i - t.w * t.h] * 255).toInt().toByte() }) }
    println("templates: " + pool.joinToString(" ") { "${it.first} %.1fx%.1f".format(it.second.lengthPx, it.second.widthPx) })
    val ownerOf = HashMap<Int, String>()
    for (o in outs) if (o.racer != null) ownerOf[o.index] = o.racer!!
    fun nice(c: String) = c.replace('_', ' ')
    val cardJson = HashMap<Int, String>()
    val uncovered = HashMap<Int, Float>()
    val tc = System.currentTimeMillis()
    for ((k, e) in events.withIndex()) {
        val snap = e.snapshot ?: continue
        val owners = { tid: Int ->
            val mine = events.indices.filter { events[it].trackId == tid && ownerOf[it] != null }
            val near = mine.filter { abs(events[it].timestampNs - snap.timestampNs) < 600_000_000L }
            (near.ifEmpty { mine.sortedBy { abs(events[it].timestampNs - snap.timestampNs) }.take(1) }).map { ownerOf[it]!! }
        }
        val seen = events.indices.filter { ownerOf[it] != null && events[it].box != null && abs(events[it].timestampNs - snap.timestampNs) < 400_000_000L }
            .map { CardFit.Seen(ownerOf[it]!!, events[it].box!!, events[it].vx, events[it].vy, events[it].timestampNs) }
        val fits = CardFit.card(snap, owners, pool, 1f / 120f, seen)
        // check: biggest patch of moving pixels no outline covers (in car areas)
        run {
            val fg = snap.fg!!; val W = snap.width; val H = snap.height
            val cov = BooleanArray(W * H)
            for (f in fits) for (yy in 0 until H) for (xx in 0 until W) if (inQuad(f.quad, (xx + 0.5f) / W, (yy + 0.5f) / H, W, H)) cov[yy * W + xx] = true
            val seen = BooleanArray(W * H); var big = 0
            for (q0 in 0 until W * H) { if (seen[q0] || fg[q0].toInt() == 0 || cov[q0]) continue
                val st = ArrayList<Int>(); st += q0; seen[q0] = true; var n = 0
                while (st.isNotEmpty()) { val q = st.removeAt(st.size - 1); n++; val x = q % W; val y = q / W
                    for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) { if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue; val m = ny * W + nx; if (!seen[m] && fg[m].toInt() != 0 && !cov[m]) { seen[m] = true; st += m } } }
                big = maxOf(big, n) }
            val car = pool.minOf { it.second.mask.count { m -> m > 0.5f } }
            uncovered[k] = big.toFloat() / car
        }
        // square canvas of CARD_S px: the picture as big as fits inside a CARD_M band, centred
        val K = (CARD_S - 2 * CARD_M) / maxOf(snap.width, snap.height)
        val ox = (CARD_S - snap.width * K) / 2; val oy = (CARD_S - snap.height * K) / 2
        val polys = fits.map { f -> FloatArray(8) { j -> (if (j % 2 == 0) ox else oy) + f.quad[j] * (if (j % 2 == 0) snap.width else snap.height) * K } }
        val sizes = fits.map { f -> (nice(f.racerId ?: "?").length * CARD_CHAR + 16f) to CARD_TAG_H }
        val tags = LabelLayout.place(polys, sizes, 0f, 0f, CARD_S, CARD_S)
        cardJson[k] = "{\"tids\":[${snap.boxes.joinToString(",") { "[${it.trackId},\"${owners(it.trackId).joinToString("+")}\"]" }}],\"w\":${snap.width},\"h\":${snap.height},\"K\":$K,\"S\":$CARD_S,\"ox\":$ox,\"oy\":$oy,\"line\":" + (snap.line?.joinToString(",", "[", "]") { "%.4f".format(it) } ?: "null") +
            ",\"cars\":[" + fits.indices.joinToString(",") { j -> val f = fits[j]; val t = tags[j]
                "{\"id\":${q(f.racerId)},\"score\":%.3f,\"poly\":[".format(f.score) + polys[j].joinToString(",") { "%.1f".format(it) } + "],\"tag\":[%.1f,%.1f,%.1f,%.1f,%.1f,%.1f]}".format(t.x, t.y, t.w, t.h, t.ax, t.ay) } + "]}"
        val rgb = ByteArray(snap.width * snap.height * 3)
        for (p in 0 until snap.width * snap.height) { val c = snap.argb[p]; rgb[3 * p] = (c shr 16).toByte(); rgb[3 * p + 1] = (c shr 8).toByte(); rgb[3 * p + 2] = c.toByte() }
        File("$OUT/r10_$seed/g$k.pgm").outputStream().buffered().use { o -> o.write("P5\n${snap.width} ${snap.height}\n255\n".toByteArray()); o.write(ByteArray(snap.width * snap.height) { if (snap.fg!![it].toInt() != 0) -1 else 0 }) }
        File("$OUT/r10_$seed/s$k.ppm").outputStream().buffered().use { o -> o.write("P6\n${snap.width} ${snap.height}\n255\n".toByteArray()); o.write(rgb) }
    }
    println("card outlines: ${cardJson.size} cards in ${System.currentTimeMillis() - tc} ms · uncovered moving patch ≥ 0.3 car: " + uncovered.filter { it.value >= 0.3f }.entries.sortedBy { it.key }.joinToString(" ") { "#${it.key}=%.2f".format(it.value) })

    val frameWanted = HashSet<Int>()
    val sb = StringBuilder("{")
    sb.append("\"cars\":[${cars.joinToString(",") { q(it) }}],\"laps\":$laps,\"seed\":$seed,")
    sb.append("\"pace\":{${cars.joinToString(",") { "${q(it)}:%.3f".format(pace[it]) }}},\"grid\":[${grid.joinToString(",") { q(it) }}],")
    sb.append("\"incidents\":[${incidents.joinToString(",") { q(it) }}],")
    sb.append("\"line\":[${LINE.x1},${LINE.y1},${LINE.x2},${LINE.y2}],\"mpp\":$MPP,\"fps\":$FPS,\"w\":${empty.w},\"h\":${empty.h},")
    sb.append("\"sim\":{" + sim.entries.joinToString(",") { "${q(it.key.first + "|" + it.key.second)}:%.3f".format(it.value) } + "},")
    sb.append("\"summary\":{\"crossings\":${truth.size},\"right\":$right,\"swaps\":$swapsL,\"engineSwaps\":$swapsE,\"missed\":$missed,\"extra\":$extraN,\"engineRight\":$rightE,\"engineMissed\":$missedE,\"engineExtra\":$extraE,")
    sb.append("\"timingMeanMs\":%.2f,\"timingMaxMs\":%.1f,\"timingBiasMs\":%.2f,\"centreMeanMs\":%.2f,\"abreast\":$abreast,\"diag\":$diag,\"durationS\":%.1f},".format(errs.map { abs(it) }.average(), errs.maxOf { abs(it) }, errs.average(), errsC.map { abs(it) }.average(), frames / FPS))
    sb.append("\"truth\":[" + truth.sortedBy { it.t }.joinToString(",") { "{\"t\":%.4f,\"car\":${q(it.car)},\"lap\":${it.lap},\"speed\":%.1f,\"along\":%.3f,\"tag\":${q(it.tag)},\"angle\":%.0f}".format(it.t, it.speed, it.along, it.angle) } + "],")
    sb.append("\"events\":[" + events.withIndex().joinToString(",") { (k, e) ->
        val b = e.box
        "{\"i\":$k,\"tid\":${e.trackId},\"t\":%.4f,\"centre\":%.4f,\"named\":${q(e.racerId)},\"accepted\":${e.accepted},\"rescue\":${e.rescue},\"split\":${e.split},\"conf\":%.3f,\"match\":%.3f,\"size\":%.2f,\"along\":%.3f,\"speed\":%s,\"dir\":${e.direction},".format(e.timestampNs / 1e9, e.centreNs / 1e9, e.confidence, e.match, e.sizeRatio, e.along, e.speedKmh?.let { "%.1f".format(it) } ?: "null") +
            "\"box\":" + (if (b == null) "null" else "[%.4f,%.4f,%.4f,%.4f]".format(b.l, b.t, b.r, b.b)) + ",\"v\":[%.4f,%.4f],".format(e.vx, e.vy) +
            "\"quad\":" + (e.quad?.joinToString(",", "[", "]") { "%.4f".format(it) } ?: "null") + ",\"fit\":" + (e.fit?.let { f -> "{\"cars\":${f.cars},\"renamed\":${f.renamed},\"scores\":[" + f.scores.take(3).joinToString(",") { "[${q(it.first)},%.3f]".format(it.second) } + "]}" } ?: "null") + "," +
            "\"card\":" + (cardJson[k] ?: "null") + "," +
            "\"cands\":[" + e.candidates.take(4).joinToString(",") { "[${q(it.first)},%.3f]".format(it.second) } + "]}"
    } + "],")
    sb.append("\"outs\":[" + outs.joinToString(",") { "{\"i\":${it.index},\"t\":%.4f,\"car\":${q(it.racer)},\"why\":${q(it.why.name)},\"note\":${q(it.note.replace("%", "%%"))}}".format(it.ns / 1e9) } + "]")
    sb.append("}")
    File("$OUT/race10_$seed.json").writeText(sb.toString())
    // frames for cards: around every event that isn't a plain, confident engine crossing, plus the forced scenarios
    for (e in events) { val fi = (e.timestampNs / 1e9 * FPS); frameWanted += fi.toInt(); frameWanted += fi.toInt() + 1; frameWanted += kotlin.math.ceil(e.centreNs / 1e9 * FPS).toInt() }
    for (p in truth) { frameWanted += (p.t * FPS).toInt(); frameWanted += (p.t * FPS).toInt() + 1 }
    for (fi in frameWanted.filter { it in 0 until frames }) {
        val f = tl.frame(fi); val rgb = rgbOf(f)
        File("$OUT/r10_$seed/f$fi.ppm").outputStream().buffered().use { o -> o.write("P6\n${f.width} ${f.height}\n255\n".toByteArray()); o.write(rgb) }
    }
    println("wrote $OUT/race10_$seed.json + ${frameWanted.size} frames")
}

/** Wizard calibration from the minis' slow (3.5 m/s) and fast (7 m/s) passes; also measures nose-timing error on them. */
fun inQuad(q: FloatArray, x: Float, y: Float, w: Int, h: Int): Boolean {
    var sign = 0
    for (k in 0 until 4) {
        val ax = q[2 * k] * w; val ay = q[2 * k + 1] * h; val bx = q[(2 * k + 2) % 8] * w; val by = q[(2 * k + 3) % 8] * h
        val c = (bx - ax) * (y * h - ay) - (by - ay) * (x * w - ax)
        val sg = if (c > 0) 1 else if (c < 0) -1 else 0
        if (sg != 0) { if (sign == 0) sign = sg else if (sg != sign) return false }
    }
    return true
}

fun calibrateMini(car: String, empty: Clip, errs: MutableList<Double>): RacerRef {
    val passes = listOf(Clip.load("minis_cal_${car}_+0.00_3.5_+0"), Clip.load("minis_cal_${car}_+0.00_7.0_+0"))
    val tl = Timeline(empty, seed = car.hashCode())
    var at = 120
    val truthT = ArrayList<Double>()
    for (c in passes) { tl.placed += Placed(c, at, 0f); truthT += (at + c.meta["crossIdx"]!!.toDouble()) / FPS; at += c.n + 40 }
    val views = ArrayList<FloatArray>()
    lateinit var a: FrameAnalyzer
    val pics = ArrayList<app.anylap.vision.CarTemplate>()
    fun cfg() = AnalyzerConfig(line = LINE, racers = listOf(RacerRef("x", if (views.isEmpty()) null else Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray(), null, views.toList())), minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP, finishEdge = FinishEdge.FRONT, captureTemplates = true)
    a = FrameAnalyzer(onCrossing = { e ->
        truthT.minByOrNull { abs(it - e.timestampNs / 1e9) }?.let { if (abs(it - e.timestampNs / 1e9) < 0.15) errs += (e.timestampNs / 1e9 - it) * 1000 }
        if (e.accepted && e.fingerprint != null) { views += e.fingerprint!!; e.template?.let { pics += it }; a.config = cfg(); if (e.area > 0) HINTS += floatArrayOf(e.along, e.area.toFloat()) }
    })
    a.config = cfg()
    for (i in 0 until at) a.process(tl.frame(i))
    a.flush((at * 1e9 / FPS).toLong())
    println("  calibrated $car: ${views.size}/2 passes")
    val fp = if (views.isEmpty()) null else Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray()
    // the slow pass's picture (least blur); true size averaged over both passes
    val pic = pics.firstOrNull()?.let { p0 -> if (pics.size > 1) app.anylap.vision.CarTemplate(p0.w, p0.h, p0.y, p0.u, p0.v, p0.mask, pics.map { it.lengthPx }.average().toFloat(), pics.map { it.widthPx }.average().toFloat(), p0.lumaRef) else p0 }
    return RacerRef(car, fp, null, views, pic)
}

/** Every diagonal / gate pass of every car alone, as a race car: nose timing error, accepted or not, name. */
fun gateTest(empty: Clip, racers: List<RacerRef>) {
    val names = File(DIR).list()!!.filter { (it.startsWith("minis_diag_") || it.startsWith("minis_gate_") || it.startsWith("minis_race_")) && it.endsWith(".yuv") }.map { it.removeSuffix(".yuv") }.sorted()
    for (n in names) {
        val c = Clip.load(n)
        val tl = Timeline(empty, seed = n.hashCode()); tl.placed += Placed(c, 120, 0f)
        val truthT = (120 + c.meta["crossIdx"]!!.toDouble()) / FPS
        val ev = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { ev += it })
        a.config = AnalyzerConfig(line = LINE, racers = racers, minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP, sizeHints = HINTS.toList(), finishEdge = FinishEdge.FRONT)
        for (i in 0 until 120 + c.n + 30) a.process(tl.frame(i))
        a.flush(((150 + c.n) * 1e9 / FPS).toLong())
        val e = ev.minByOrNull { abs(it.timestampNs / 1e9 - truthT) }
        println("%-40s %s".format(n, if (e == null) "NOTHING" else "%s acc=%s rescue=%s named=%s err=%.1f ms (centre %.1f) size=%.2f".format(if (abs(e.timestampNs / 1e9 - truthT) < 0.15) "hit" else "far", e.accepted, e.rescue, e.racerId, (e.timestampNs / 1e9 - truthT) * 1000, (e.centreNs / 1e9 - (120 + c.meta["centreIdx"]!!.toDouble()) / FPS) * 1000, e.sizeRatio)))
    }
}

fun rgbOf(f: app.anylap.vision.AnalysisFrame): ByteArray {
    val out = ByteArray(f.width * f.height * 3)
    for (j in 0 until f.width * f.height) {
        val y = 1.164 * ((f.y[j].toInt() and 0xFF) - 16); val u = (f.u[j].toInt() and 0xFF) - 128.0; val v = (f.v[j].toInt() and 0xFF) - 128.0
        out[j * 3] = (y + 1.596 * v).roundToInt().coerceIn(0, 255).toByte()
        out[j * 3 + 1] = (y - 0.392 * u - 0.813 * v).roundToInt().coerceIn(0, 255).toByte()
        out[j * 3 + 2] = (y + 2.017 * u).roundToInt().coerceIn(0, 255).toByte()
    }
    return out
}
