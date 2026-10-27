package clip

import app.anylap.data.LineSpec
import app.anylap.vision.AnalysisFrame
import app.anylap.vision.AnalyzerConfig
import app.anylap.vision.CrossingEvent
import app.anylap.vision.FrameAnalyzer
import app.anylap.vision.OverlayBox
import app.anylap.vision.RacerRef
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A 50-lap, 10-car race composited from the overhead passes of the cars10 grid, run through the
 * engine, scored against the truth and exported for the results page:
 * out/race50.json (standings, laps, truth, review items) + out/crops/rN.ppm.
 */
fun main(args: Array<String>) {
    val seed = args.getOrNull(0)?.toInt() ?: 1
    System.getenv("WEAK")?.let { FrameAnalyzer.SPLIT_WEAK = it.toFloat() }
    val laps = args.getOrNull(1)?.toInt() ?: 50
    val metaTxt = File("/home/claude/blend/form/cars10/meta.json").readText()
    val nums = Regex("[-0-9.eE]+").findAll(metaTxt.substringAfter("\"line\"").substringBefore("]]")).map { it.value.toFloat() }.toList()
    LINE = LineSpec(nums[0], nums[1], nums[2], nums[3])
    MPP = Clip.flatJson(metaTxt)["metersPerProcPx"]!!.toFloat()
    val alongMap = Regex("\"(-?[0-9.]+)\":\\s*(-?[0-9.eE]+)").findAll(metaTxt.substringAfter("\"along\"")).associate { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
    fun along(lane: Double) = alongMap.minByOrNull { abs(it.key - lane) }!!.value
    val empty = Clip.load("cars10_empty")
    val cars = File(DIR).list()!!.filter { it.startsWith("cars10_cal_") && it.endsWith("_3.5.yuv") }.map { it.removePrefix("cars10_cal_").removeSuffix("_3.5.yuv") }.sorted()
    println("cars: $cars · line $LINE · ${"%.2f".format(MPP * 1000)} mm/px")
    HINTS.clear()
    val racers = cars.map { c -> calibratePair(c, empty, "cars10_cal_${c}_3.5", "cars10_cal_${c}_7.0") }
    // look-alike matrix: how similar each car's calibrated fingerprint is to the others'
    println("similarity matrix (row vs column, %):")
    println("            " + cars.joinToString(" ") { it.take(6).padStart(6) })
    for (r in racers) println(r.id.take(11).padEnd(11) + " " + racers.joinToString(" ") { o -> if (r.fingerprint == null || o.fingerprint == null) "     -" else "%6.0f".format(app.anylap.vision.Fingerprint.similarity(r.fingerprint!!, o.fingerprint!!) * 100) })
    if (args.getOrNull(2) == "cal") return
    val passes = cars.associateWith { c -> File(DIR).list()!!.filter { it.startsWith("cars10_pass_${c}_") && it.endsWith(".yuv") }.map { Clip.load(it.removeSuffix(".yuv")) } }

    // ---------------------------------------------------------------- schedule
    val rnd = Random(seed)
    data class Ev(var t: Double, val car: String, var lane: Double, var pass: Clip?, val lap: Int)
    val evs = ArrayList<Ev>()
    val pace = cars.associateWith { 18.0 + (rnd.nextDouble() - 0.5) * 1.2 }     // 17.4–18.6 s laps
    val startGap = cars.shuffled(rnd).withIndex().associate { (i, c) -> c to 3.0 + i * 0.45 } // rolling start, 0.45 s apart
    val incidents = ArrayList<String>()
    for (c in cars) {
        var t = startGap[c]!!
        for (l in 1..laps) {
            evs += Ev(t, c, 0.0, null, l)
            var lapT = pace[c]!! + rnd.nextDouble() * 0.6 - 0.3
            if (rnd.nextDouble() < 0.04) { val d = 2.0 + rnd.nextDouble() * 5; lapT += d; incidents += "%s lap %d: +%.1f s (spin)".format(c, l + 1, d) }
            t += lapT
        }
    }
    evs.sortBy { it.t }
    // lanes: a car may not share a lane with one within 0.08 s (they would be painted on top of each other);
    // within 0.12 s of another car it takes the neighbouring lane (10 cm centre to centre → ~1 cm gap, abreast)
    val lanesAll = listOf(-0.2, -0.1, 0.1, 0.2)
    var abreast = 0
    for (i in evs.indices) {
        val near = (maxOf(0, i - 8) until i).map { evs[it] }.filter { abs(it.t - evs[i].t) < 0.5 }
        val hard = near.filter { abs(it.t - evs[i].t) < 0.08 }.map { it.lane }.toSet()
        val soft = near.map { it.lane }.toSet()
        val free = lanesAll.filter { it !in hard }
        val closest = near.minByOrNull { abs(it.t - evs[i].t) }
        evs[i].lane = when {
            free.isEmpty() -> { evs[i].t += 0.1; lanesAll.random(rnd) }
            closest != null && abs(closest.t - evs[i].t) < 0.12 -> {
                val nb = when (closest.lane) { -0.2 -> -0.1; -0.1 -> -0.2; 0.1 -> 0.2; else -> 0.1 }
                if (nb in free) { abreast++; nb } else free.random(rnd)
            }
            else -> (free.filter { it !in soft }.ifEmpty { free }).random(rnd)
        }
        evs[i].pass = passes[evs[i].car]!!.first { abs(it.meta["lane"]!!.toDouble() - evs[i].lane) < 0.01 }
    }
    println("abreast arrivals (<0.12 s, neighbouring lanes): $abreast")
    // fast passes (7.5 m/s, lane 0) for ~15 % of lone crossings
    for (e in evs) if (rnd.nextDouble() < 0.15 && evs.none { o -> o !== e && abs(o.t - e.t) < 0.5 }) {
        passes[e.car]!!.firstOrNull { it.meta["speed"] == "7.5" }?.let { e.pass = it; e.lane = 0.0 }
    }
    val tl = Timeline(empty, seed = seed * 7)
    val truth = ArrayList<Truth>()
    val truthLap = HashMap<Truth, Int>()
    for (e in evs) {
        val p = e.pass!!
        val crossIdx = p.meta["crossIdx"]!!.toDouble()
        val off = (e.t * FPS - crossIdx).toInt()
        val exact = (off + crossIdx) / FPS
        tl.placed += Placed(p, off, e.lane.toFloat())
        val tr = Truth(exact, e.car, p.meta["speed"]!!.toDouble(), along(e.lane))
        truth += tr; truthLap[tr] = e.lap
    }
    val frames = ((truth.maxOf { it.t } + 2.0) * FPS).toInt()
    println("schedule: ${truth.size} crossings over ${"%.0f".format(frames / FPS)} s, ${incidents.size} spins")

    if (args.getOrNull(2) == "dump") {
        for (p in truth.sortedBy { it.t }.filter { it.t in 21.5..21.7 || it.t in 146.2..146.4 }) {
            val fi = (p.t * FPS).roundToInt()
            println("truth %.3f %s along %.2f: frame %d shows %s".format(p.t, p.car, p.along, fi, tl.placed.filter { fi - it.offset in 0 until it.clip.n }.map { it.clip.meta["car"] + "@" + (fi - it.offset) + "/" + it.clip.meta["crossIdx"] }))
            val f = tl.frame(fi); val rgb = toRgb(f)
            File("/home/claude/blend/out/dump_$fi.ppm").outputStream().buffered().use { o -> o.write("P6\n${f.width} ${f.height}\n255\n".toByteArray()); o.write(rgb) }
        }
        return
    }
    // ---------------------------------------------------------------- run
    val events = ArrayList<CrossingEvent>()
    val boxes = HashMap<Int, List<OverlayBox>>()
    var cur = 0
    val a = FrameAnalyzer(onCrossing = { events += it }, onOverlay = { b -> boxes[cur] = b })
    a.config = AnalyzerConfig(line = LINE, racers = racers, minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP, sizeHints = HINTS.toList())
    val t0 = System.currentTimeMillis()
    for (i in 0 until frames) { cur = i; a.process(tl.frame(i)); if (i % 3000 == 0) println("  frame $i / $frames") }
    a.flush((frames * 1e9 / FPS).toLong())
    println("engine: ${(System.currentTimeMillis() - t0) / 1000} s")

    // ---------------------------------------------------------------- score
    val acc = events.filter { it.accepted }
    fun cost(e: CrossingEvent, p: Truth): Double { val dt = abs(e.timestampNs / 1e9 - p.t); if (dt >= 0.15) return Double.MAX_VALUE; return dt / 0.05 + abs(e.along - p.along) / 0.1 }
    val pairs = ArrayList<Triple<Double, CrossingEvent, Truth>>()
    for (p in truth) for (e in acc) { val c = cost(e, p); if (c < Double.MAX_VALUE) pairs += Triple(c, e, p) }
    pairs.sortBy { it.first }
    val used = HashSet<CrossingEvent>(); val match = HashMap<Truth, CrossingEvent>()
    for ((_, e, p) in pairs) if (e !in used && p !in match) { used += e; match[p] = e }
    val extras = acc.filter { it !in used }

    // app-side lap counting from the engine's named crossings (min lap 2.5 s), as RaceEngine does
    data class Cross(val t: Double, val car: String, val e: CrossingEvent?)
    val engCross = acc.filter { it.racerId != null }.map { Cross(it.timestampNs / 1e9, it.racerId!!, it) }.sortedBy { it.t }
    fun lapsOf(cross: List<Cross>, car: String): List<Cross> { val out = ArrayList<Cross>(); var last = -99.0; for (c in cross) if (c.car == car && c.t - last >= 2.5) { out += c; last = c.t }; return out }
    val engLaps = cars.associateWith { lapsOf(engCross, it) }
    val trueLaps = cars.associateWith { c -> truth.filter { it.car == c }.sortedBy { it.t } }

    // ---------------------------------------------------------------- review items (troubled)
    data class Review(val kind: String, val t: Double, val car: String?, val truthCar: String?, val cands: String, val note: String, val along: Float)
    val reviews = ArrayList<Review>()
    for (p in truth.sortedBy { it.t }) {
        val e = match[p]
        if (e == null) {
            val rej = events.firstOrNull { !it.accepted && !it.rescue && abs(it.timestampNs / 1e9 - p.t) < 0.15 }
            val res = events.firstOrNull { it.rescue && abs(it.timestampNs / 1e9 - p.t) < 0.3 }
            reviews += Review(if (res != null) "MISSED?" else if (rej != null) "REJECTED" else "MISSED", p.t, null, p.car, (res ?: rej)?.candidates?.take(3)?.joinToString { "%s %.2f".format(it.first, it.second) } ?: "", if (res != null) "check line saw something uncounted" else if (rej != null) "gate turned it down (%.0f %%)".format(rej.match * 100) else "nothing seen at the line", p.along.toFloat())
            continue
        }
        when {
            e.racerId == null -> reviews += Review("UNKNOWN", p.t, null, p.car, e.candidates.take(3).joinToString { "%s %.2f".format(it.first, it.second) }, "no clear match", e.along)
            e.racerId != p.car -> reviews += Review("WRONG", p.t, e.racerId, p.car, e.candidates.take(3).joinToString { "%s %.2f".format(it.first, it.second) }, "named the wrong car", e.along)
            e.split -> reviews += Review("SPLIT", p.t, e.racerId, p.car, e.candidates.take(3).joinToString { "%s %.2f".format(it.first, it.second) }, "separated from a touching car", e.along)
            e.confidence < 0.12f -> reviews += Review("CLOSE CALL", p.t, e.racerId, p.car, e.candidates.take(3).joinToString { "%s %.2f".format(it.first, it.second) }, "won by %.2f".format(e.confidence), e.along)
        }
    }
    for (e in extras) reviews += Review("EXTRA", e.timestampNs / 1e9, e.racerId, null, e.candidates.take(3).joinToString { "%s %.2f".format(it.first, it.second) }, "counted, but no car crossed", e.along)
    for (e in events.filter { it.rescue && truth.none { p -> abs(p.t - it.timestampNs / 1e9) < 0.3 && match[p] == null } })
        reviews += Review("MISSED? (false)", e.timestampNs / 1e9, null, null, e.candidates.take(2).joinToString { "%s %.2f".format(it.first, it.second) }, "check line fired, nothing was missed", e.along)

    // crops: the composed frame at each review time, ±48 px around the line at the reported position
    File("/home/claude/blend/out/crops$seed").mkdirs()
    val cropOf = HashMap<Review, String>()
    for ((k, r) in reviews.withIndex()) {
        val fi = (r.t * FPS).roundToInt().coerceIn(0, frames - 1)
        val f = tl.frame(fi); val rgb = toRgb(f)
        val lx = ((LINE.x1 + (LINE.x2 - LINE.x1) * r.along) * f.width).toInt(); val ly = ((LINE.y1 + (LINE.y2 - LINE.y1) * r.along) * f.height).toInt()
        val cw = 96; val ch = 96
        val x0 = (lx - cw / 2).coerceIn(0, f.width - cw); val y0 = (ly - ch / 2).coerceIn(0, f.height - ch)
        val path = "/home/claude/blend/out/crops$seed/r${k}.ppm"
        File(path).outputStream().buffered().use { o ->
            o.write("P6\n$cw $ch\n255\n".toByteArray())
            for (yy in 0 until ch) o.write(rgb, ((y0 + yy) * f.width + x0) * 3, cw * 3)
        }
        cropOf[r] = path
    }

    // ---------------------------------------------------------------- export
    fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\"", "'") + "\""
    val sb = StringBuilder("{")
    sb.append("\"cars\":[${cars.joinToString(",") { q(it) }}],")
    sb.append("\"pace\":{${cars.joinToString(",") { "${q(it)}:%.3f".format(pace[it]) }}},")
    sb.append("\"incidents\":[${incidents.joinToString(",") { q(it) }}],")
    sb.append("\"summary\":{\"crossings\":${truth.size},\"found\":${match.size},\"missed\":${truth.size - match.size},\"extra\":${extras.size},")
    sb.append("\"right\":${truth.count { match[it]?.racerId == it.car }},\"wrong\":${truth.count { match[it] != null && match[it]!!.racerId != null && match[it]!!.racerId != it.car }},\"unknown\":${truth.count { match[it] != null && match[it]!!.racerId == null }},")
    val tErr = truth.mapNotNull { p -> match[p]?.let { abs(it.timestampNs / 1e9 - p.t) * 1000 } }
    sb.append("\"timingMeanMs\":%.2f,\"timingMaxMs\":%.1f,\"durationS\":%.1f},".format(tErr.average(), tErr.max(), frames / FPS))
    sb.append("\"engineLaps\":{" + cars.joinToString(",") { c -> q(c) + ":[" + engLaps[c]!!.joinToString(",") { "{\"t\":%.4f,\"speed\":%s,\"conf\":%.3f,\"split\":%s}".format(it.t, it.e?.speedKmh?.let { s -> "%.1f".format(s) } ?: "null", it.e?.confidence ?: 1f, it.e?.split ?: false) } + "]" } + "},")
    sb.append("\"trueLaps\":{" + cars.joinToString(",") { c -> q(c) + ":[" + trueLaps[c]!!.joinToString(",") { "{\"t\":%.4f,\"speed\":%.1f}".format(it.t, it.speed) } + "]" } + "},")
    sb.append("\"reviews\":[" + reviews.withIndex().joinToString(",") { (k, r) -> "{\"kind\":${q(r.kind)},\"t\":%.3f,\"car\":${q(r.car)},\"truth\":${q(r.truthCar)},\"cands\":${q(r.cands)},\"note\":${q(r.note)},\"crop\":${q(cropOf[r])}}".format(r.t) } + "]")
    sb.append("}")
    File("/home/claude/blend/out/race50_$seed.json").writeText(sb.toString())
    println("summary: ${truth.size} crossings, found ${match.size}, extra ${extras.size}, right ${truth.count { match[it]?.racerId == it.car }}, wrong ${truth.count { match[it] != null && match[it]!!.racerId != null && match[it]!!.racerId != it.car }}, unknown ${truth.count { match[it] != null && match[it]!!.racerId == null }}, timing %.1f ms; reviews ${reviews.size} (${reviews.groupingBy { it.kind }.eachCount()})".format(tErr.average()))
}

fun calibratePair(car: String, empty: Clip, slow: String, fast: String): RacerRef {
    val passes = listOf(Clip.load(slow), Clip.load(fast))
    val tl = Timeline(empty, seed = car.hashCode())
    var at = 120
    for (c in passes) { tl.placed += Placed(c, at, 0f); at += c.n + 40 }
    val views = ArrayList<FloatArray>()
    lateinit var a: FrameAnalyzer
    fun cfg() = AnalyzerConfig(line = LINE, racers = listOf(RacerRef("x", if (views.isEmpty()) null else app.anylap.vision.Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray(), null, views.toList())), minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP)
    a = FrameAnalyzer(onCrossing = { e -> if (e.accepted && e.fingerprint != null) { views += e.fingerprint!!; a.config = cfg(); if (e.area > 0) HINTS += floatArrayOf(e.along, e.area.toFloat()) } })
    a.config = cfg()
    for (i in 0 until at) a.process(tl.frame(i))
    a.flush((at * 1e9 / FPS).toLong())
    println("  calibrated $car: ${views.size}/2 passes")
    val fp = if (views.isEmpty()) null else app.anylap.vision.Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray()
    return RacerRef(car, fp, null, views)
}

fun toRgb(f: AnalysisFrame): ByteArray {
    val out = ByteArray(f.width * f.height * 3)
    for (j in 0 until f.width * f.height) {
        val y = 1.164 * ((f.y[j].toInt() and 0xFF) - 16); val u = (f.u[j].toInt() and 0xFF) - 128.0; val v = (f.v[j].toInt() and 0xFF) - 128.0
        out[j * 3] = (y + 1.596 * v).roundToInt().coerceIn(0, 255).toByte()
        out[j * 3 + 1] = (y - 0.392 * u - 0.813 * v).roundToInt().coerceIn(0, 255).toByte()
        out[j * 3 + 2] = (y + 2.017 * u).roundToInt().coerceIn(0, 255).toByte()
    }
    return out
}
