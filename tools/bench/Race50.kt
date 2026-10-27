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
    val edge = System.getenv("FINISH")?.let { app.anylap.data.FinishEdge.valueOf(it) } ?: app.anylap.data.FinishEdge.CENTRE
    // car lengths (m) from the Blender grid, for the true front/rear crossing time
    val carLen = Regex("\\(\"([^\"]+)\",\\s*([0-9.]+)").findAll(File("/home/claude/blend/cars10.py").readText()).associate { it.groupValues[1].replace(' ', '_') to it.groupValues[2].toDouble() / 100 }
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
        val sp = p.meta["speed"]!!.toDouble()
        val halfT = (carLen[e.car] ?: 0.16) / 2 / sp
        val tEdge = when (edge) { app.anylap.data.FinishEdge.FRONT -> exact - halfT; app.anylap.data.FinishEdge.REAR -> exact + halfT; else -> exact }
        val tr = Truth(tEdge, e.car, sp, along(e.lane))
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
    a.config = AnalyzerConfig(line = LINE, racers = racers, minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP, sizeHints = HINTS.toList(), finishEdge = edge, captureSnapshots = true)
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

    // app-side lap counting from the engine's named crossings, as RaceEngine does: LapIdentity
    // (lap timing as a second opinion), or the old rule (min lap 2.5 s) with IDENTITY=0
    data class Cross(val t: Double, val car: String, val e: CrossingEvent?)
    val useIdentity = System.getenv("IDENTITY") != "0"
    val owner = HashMap<CrossingEvent, String?>()   // final owner of each named crossing (null = parked)
    val how = HashMap<CrossingEvent, app.anylap.engine.LapIdentity.Reason>()
    val named = acc.filter { it.racerId != null }.sortedBy { it.timestampNs }
    if (useIdentity) {
        val idn = app.anylap.engine.LapIdentity((2.5e9).toLong())
        cars.forEach { idn.register(it) }
        for (e in named) for (c in idn.place(e.racerId!!, app.anylap.engine.LapIdentity.Entry(e.timestampNs, e.candidates, payload = e)).also { chs ->
            if (System.getenv("DEBUG_ID") != null && chs.any { it.reason == app.anylap.engine.LapIdentity.Reason.PARKED } && e.timestampNs < 100e9)
                println("DBG place %s at %.2f → %s · list now %s · window %.1f s".format(e.racerId, e.timestampNs / 1e9, chs.joinToString { "%.2f %s→%s %s".format(it.entry.ns / 1e9, it.from, it.to, it.reason) }, idn.crossings(e.racerId!!).joinToString { "%.2f".format(it.ns / 1e9) }, idn.window(e.racerId!!) / 1e9))
        }) {
            val ev = c.entry.payload as CrossingEvent
            owner[ev] = c.to
            if (c.reason != app.anylap.engine.LapIdentity.Reason.NAMED && c.reason != app.anylap.engine.LapIdentity.Reason.REPLACED) how[ev] = c.reason
        }
    } else {
        val last = HashMap<String, Double>()
        for (e in named) { val t = e.timestampNs / 1e9; if (t - (last[e.racerId!!] ?: -99.0) >= 2.5) { owner[e] = e.racerId; last[e.racerId!!] = t } else owner[e] = null }
    }
    if (System.getenv("DEBUG_ID") != null) for ((ev, r) in how) {
        val t = ev.timestampNs / 1e9
        val same = named.filter { it.racerId == ev.racerId && it !== ev && abs(it.timestampNs / 1e9 - t) < 15 }.joinToString { "%.2f(%s)".format(it.timestampNs / 1e9, owner[it] ?: "-") }
        val tru = match.entries.firstOrNull { it.value === ev }?.key?.car
        println("  $r %.2f named %s (truth %s) cands %s · same-name nearby: %s".format(t, ev.racerId, tru, ev.candidates.take(2).joinToString { "%s %.2f".format(it.first, it.second) }, same))
    }
    val engLaps = cars.associateWith { c -> named.filter { owner[it] == c }.map { Cross(it.timestampNs / 1e9, c, it) } }
    val trueLaps = cars.associateWith { c -> truth.filter { it.car == c }.sortedBy { it.t } }
    // lap-level score: what got counted for whom
    var lapRight = 0; var lapWrong = 0; var lapTray = 0; var lapMissed = 0; var renamedOk = 0; var renamedBad = 0
    for (p in truth) {
        val e = match[p]
        when {
            e == null -> lapMissed++
            e.racerId == null || (e in owner && owner[e] == null) -> lapTray++
            owner[e] == p.car -> { lapRight++; if (how[e] == app.anylap.engine.LapIdentity.Reason.RENAMED) renamedOk++ }
            else -> { lapWrong++; if (how[e] == app.anylap.engine.LapIdentity.Reason.RENAMED) renamedBad++ }
        }
    }
    val falseLaps0 = extras.count { owner[it] != null }
    // lap table view: does every true crossing have a counted crossing for the same car at that time?
    run {
        val counted = named.filter { owner[it] != null }.toMutableList()
        var ok = 0
        for (p in truth.sortedBy { it.t }) {
            val c = counted.filter { owner[it] == p.car && abs(it.timestampNs / 1e9 - p.t) < 0.15 }.minByOrNull { abs(it.timestampNs / 1e9 - p.t) }
            if (c != null) { ok++; counted.remove(c) }
        }
        println("lap table: ${ok} of ${truth.size} true laps counted for the right car at the right time · ${counted.size} counted laps that no car drove (wrong car or phantom)")
        LAPTABLE[0] = ok; LAPTABLE[1] = counted.size
    }
    val falseLaps = falseLaps0
    println("laps (${if (useIdentity) "lap-timing identity" else "old rule"}): right $lapRight · wrong car $lapWrong · false (no car) $falseLaps · not counted / tray $lapTray · missed $lapMissed · re-named by timing: $renamedOk right, $renamedBad wrong · parked ${how.values.count { it == app.anylap.engine.LapIdentity.Reason.PARKED }}")

    // ---------------------------------------------------------------- review items (troubled)
    data class Review(val kind: String, val t: Double, val car: String?, val truthCar: String?, val cands: String, val note: String, val along: Float, val ev: CrossingEvent? = null)
    val reviews = ArrayList<Review>()
    fun cs(e: CrossingEvent) = e.candidates.take(3).joinToString { "%s %.2f".format(it.first, it.second) }
    for (p in truth.sortedBy { it.t }) {
        val e = match[p]
        if (e == null) {
            val rej = events.firstOrNull { !it.accepted && !it.rescue && abs(it.timestampNs / 1e9 - p.t) < 0.15 }
            val res = events.firstOrNull { it.rescue && abs(it.timestampNs / 1e9 - p.t) < 0.3 }
            reviews += Review(if (res != null) "MISSED?" else if (rej != null) "REJECTED" else "MISSED", p.t, null, p.car, (res ?: rej)?.candidates?.take(3)?.joinToString { "%s %.2f".format(it.first, it.second) } ?: "", if (res != null) "check line saw something uncounted" else if (rej != null) "gate turned it down (%.0f %%)".format(rej.match * 100) else "nothing seen at the line", p.along.toFloat(), res ?: rej)
            continue
        }
        val o = if (e in owner) owner[e] else e.racerId
        when {
            e.racerId == null -> reviews += Review("UNKNOWN", p.t, null, p.car, cs(e), "no clear match", e.along, e)
            how[e] == app.anylap.engine.LapIdentity.Reason.PARKED -> reviews += Review("NOT COUNTED", p.t, null, p.car, cs(e), "named ${e.racerId!!.replace('_', ' ')}, but that car had just crossed – lap timing parked it", e.along, e)
            how[e] == app.anylap.engine.LapIdentity.Reason.RENAMED -> reviews += Review("RE-NAMED", p.t, o, p.car, cs(e), "named ${e.racerId!!.replace('_', ' ')}, moved by lap timing", e.along, e)
            o != p.car -> reviews += Review("WRONG", p.t, o, p.car, cs(e), "named the wrong car", e.along, e)
            e.split -> reviews += Review("SPLIT", p.t, o, p.car, cs(e), "separated from a touching car", e.along, e)
            e.confidence < 0.12f && (e.candidates.firstOrNull()?.second ?: 0f) < 0.85f -> reviews += Review("CLOSE CALL", p.t, o, p.car, cs(e), "won by %.2f".format(e.confidence), e.along)
        }
    }
    for (e in extras) {
        val o = if (e in owner) owner[e] else e.racerId
        if (o == null && e.racerId != null) reviews += Review("NOT COUNTED", e.timestampNs / 1e9, null, null, cs(e), "named ${e.racerId!!.replace('_', ' ')}, but that car had just crossed – lap timing parked it", e.along, e)
        else reviews += Review("EXTRA", e.timestampNs / 1e9, o, null, cs(e), "counted, but no car crossed", e.along, e)
    }
    for (e in events.filter { it.rescue && truth.none { p -> abs(p.t - it.timestampNs / 1e9) < 0.3 && match[p] == null } })
        reviews += Review("MISSED? (false)", e.timestampNs / 1e9, null, null, e.candidates.take(2).joinToString { "%s %.2f".format(it.first, it.second) }, "check line fired, nothing was missed", e.along, e)
    reviews.sortBy { it.t }
    // one card per pair of touching cars, as the app does
    run {
        val keep = ArrayList<Review>()
        for (r in reviews) {
            val tid = r.ev?.trackId
            if (r.kind == "SPLIT" && tid != null && keep.any { k -> k.kind == "SPLIT" && abs(k.t - r.t) < 0.4 && k.ev?.snapshot?.boxes?.any { it.trackId == tid } == true }) continue
            keep += r
        }
        reviews.clear(); reviews += keep
    }

    fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\"", "'") + "\""
    // pictures: the engine's own snapshot (with every car's box, the line and their motion), as the
    // app stores it; a plain crop of the frame where there is none (a missed car)
    File("/home/claude/blend/out/crops$seed").mkdirs()
    val cropOf = HashMap<Review, String>()
    val boxesOf = HashMap<Review, String>()
    val evOfTrack = events.groupBy { it.trackId }
    for ((k, r) in reviews.withIndex()) {
        val path = "/home/claude/blend/out/crops$seed/r${k}.ppm"
        val snap = r.ev?.snapshot
        if (snap != null) {
            File(path).outputStream().buffered().use { o ->
                o.write("P6\n${snap.width} ${snap.height}\n255\n".toByteArray())
                val row = ByteArray(snap.width * 3)
                for (yy in 0 until snap.height) {
                    for (xx in 0 until snap.width) { val c = snap.argb[yy * snap.width + xx]; row[xx * 3] = (c shr 16).toByte(); row[xx * 3 + 1] = (c shr 8).toByte(); row[xx * 3 + 2] = c.toByte() }
                    o.write(row)
                }
            }
            val shot = snap.timestampNs / 1e9
            boxesOf[r] = "{\"line\":[" + (snap.line?.joinToString(",") { "%.4f".format(it) } ?: "") + "],\"shot\":%.4f,\"frameMs\":%.2f,\"boxes\":[".format(shot, snap.frameNs / 1e6) +
                snap.boxes.joinToString(",") { b ->
                    val ev = evOfTrack[b.trackId]?.filter { it.accepted && abs(it.timestampNs / 1e9 - shot) < 1.0 }?.minByOrNull { abs(it.timestampNs / 1e9 - shot) }
                    val own = ev?.let { if (it in owner) owner[it] else it.racerId }
                    val tru = ev?.let { x -> match.entries.firstOrNull { it.value === x }?.key?.car }
                    "{\"l\":%.4f,\"t\":%.4f,\"r\":%.4f,\"b\":%.4f,\"vx\":%.4f,\"vy\":%.4f,\"at\":%s,\"car\":%s,\"truth\":%s,\"subject\":%s,\"cands\":[%s]}".format(
                        b.l, b.t, b.r, b.b, b.vx, b.vy, ev?.let { "%.4f".format(it.timestampNs / 1e9) } ?: "null", q(own), q(tru), b.trackId == r.ev.trackId,
                        ev?.candidates?.take(4)?.joinToString(",") { "[${q(it.first)},%.3f]".format(it.second) } ?: "")
                } + "]}"
        } else {
            val fi = (r.t * FPS).roundToInt().coerceIn(0, frames - 1)
            val f = tl.frame(fi); val rgb = toRgb(f)
            val lx = ((LINE.x1 + (LINE.x2 - LINE.x1) * r.along) * f.width).toInt(); val ly = ((LINE.y1 + (LINE.y2 - LINE.y1) * r.along) * f.height).toInt()
            val cw = 96; val ch = 96
            val x0 = (lx - cw / 2).coerceIn(0, f.width - cw); val y0 = (ly - ch / 2).coerceIn(0, f.height - ch)
            File(path).outputStream().buffered().use { o ->
                o.write("P6\n$cw $ch\n255\n".toByteArray())
                for (yy in 0 until ch) o.write(rgb, ((y0 + yy) * f.width + x0) * 3, cw * 3)
            }
        }
        cropOf[r] = path
    }

    // ---------------------------------------------------------------- export
    val sb = StringBuilder("{")
    sb.append("\"cars\":[${cars.joinToString(",") { q(it) }}],")
    sb.append("\"pace\":{${cars.joinToString(",") { "${q(it)}:%.3f".format(pace[it]) }}},")
    sb.append("\"incidents\":[${incidents.joinToString(",") { q(it) }}],")
    sb.append("\"summary\":{\"tableOk\":${LAPTABLE[0]},\"tableFalse\":${LAPTABLE[1]},\"lapRight\":$lapRight,\"lapWrong\":$lapWrong,\"falseLaps\":$falseLaps,\"lapTray\":$lapTray,\"crossings\":${truth.size},\"found\":${match.size},\"missed\":${truth.size - match.size},\"extra\":${extras.size},")
    sb.append("\"right\":${truth.count { match[it]?.racerId == it.car }},\"wrong\":${truth.count { match[it] != null && match[it]!!.racerId != null && match[it]!!.racerId != it.car }},\"unknown\":${truth.count { match[it] != null && match[it]!!.racerId == null }},")
    val tErr = truth.mapNotNull { p -> match[p]?.let { abs(it.timestampNs / 1e9 - p.t) * 1000 } }
    sb.append("\"timingMeanMs\":%.2f,\"timingMaxMs\":%.1f,\"durationS\":%.1f},".format(tErr.average(), tErr.max(), frames / FPS))
    sb.append("\"engineLaps\":{" + cars.joinToString(",") { c -> q(c) + ":[" + engLaps[c]!!.joinToString(",") { "{\"t\":%.4f,\"speed\":%s,\"conf\":%.3f,\"split\":%s}".format(it.t, it.e?.speedKmh?.let { s -> "%.1f".format(s) } ?: "null", it.e?.confidence ?: 1f, it.e?.split ?: false) } + "]" } + "},")
    sb.append("\"trueLaps\":{" + cars.joinToString(",") { c -> q(c) + ":[" + trueLaps[c]!!.joinToString(",") { "{\"t\":%.4f,\"speed\":%.1f}".format(it.t, it.speed) } + "]" } + "},")
    sb.append("\"reviews\":[" + reviews.withIndex().joinToString(",") { (k, r) -> "{\"kind\":${q(r.kind)},\"t\":%.3f,\"car\":${q(r.car)},\"truth\":${q(r.truthCar)},\"cands\":${q(r.cands)},\"note\":${q(r.note)},\"crop\":${q(cropOf[r])},\"pic\":${boxesOf[r] ?: "null"}}".format(r.t) } + "]")
    sb.append("}")
    File("/home/claude/blend/out/race50_$seed.json").writeText(sb.toString())
    // photo-finish order: truth pairs within 60 ms of each other, both found – does the engine agree who was first?
    val ts = truth.filter { match[it] != null }.sortedBy { it.t }
    var pairN = 0; var pairOk = 0
    for (i in ts.indices) for (j in i + 1 until ts.size) {
        if (ts[j].t - ts[i].t > 0.06) break
        pairN++
        if ((match[ts[j]]!!.timestampNs - match[ts[i]]!!.timestampNs) >= 0) pairOk++
    }
    val signed = truth.mapNotNull { p -> match[p]?.let { (it.timestampNs / 1e9 - p.t) * 1000 } }
    val bySpeed = truth.filter { match[it] != null }.groupBy { it.speed }.mapValues { (_, l) -> l.map { (match[it]!!.timestampNs / 1e9 - it.t) * 1000 }.average() }
    println("close pairs (< 60 ms): $pairOk / $pairN in the right order · timing p95 %.1f ms · signed mean %+.2f ms · by speed %s".format(tErr.sorted()[(tErr.size * 0.95).toInt()], signed.average(), bySpeed.entries.joinToString { "%.1f m/s %+.1f".format(it.key, it.value) }))
    println("summary: ${truth.size} crossings, found ${match.size}, extra ${extras.size}, right ${truth.count { match[it]?.racerId == it.car }}, wrong ${truth.count { match[it] != null && match[it]!!.racerId != null && match[it]!!.racerId != it.car }}, unknown ${truth.count { match[it] != null && match[it]!!.racerId == null }}, timing %.1f ms; reviews ${reviews.size} (${reviews.groupingBy { it.kind }.eachCount()})".format(tErr.average()))
}

val LAPTABLE = IntArray(2)

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
