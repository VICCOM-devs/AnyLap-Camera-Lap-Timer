package clip

import app.anylap.data.LineSpec
import app.anylap.vision.AnalyzerConfig
import app.anylap.vision.CrossingEvent
import app.anylap.vision.Fingerprint
import app.anylap.vision.FrameAnalyzer
import app.anylap.vision.OverlayBox
import app.anylap.vision.RacerRef
import java.io.File
import kotlin.math.abs

/** Formation clips under one camera rig: calibrate per rig, run each clip, score, dump review data. */
fun main_form(args: Array<String>) {
    val cam = args[0]
    val metaTxt = File("/home/claude/blend/form/$cam/meta.json").readText()
    val nums = Regex("[-0-9.eE]+").findAll(metaTxt.substringAfter("\"line\"").substringBefore("]]")).map { it.value.toFloat() }.toList()
    LINE = LineSpec(nums[0], nums[1], nums[2], nums[3])
    MPP = Clip.flatJson(metaTxt)["metersPerProcPx"]!!.toFloat()
    val alongMap = Regex("\"(-?[0-9.]+)\":\\s*(-?[0-9.eE]+)").findAll(metaTxt.substringAfter("\"along\"")).associate { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
    fun along(lane: Double) = alongMap.minByOrNull { abs(it.key - lane) }!!.value
    val empty = Clip.load(if (cam == "45") "empty" else "form_${cam}_empty")
    val calPrefix = if (cam == "45") "cal_" else "form_${cam}_cal_"
    val cars = File(DIR).list()!!.filter { it.startsWith(calPrefix) && it.endsWith("_3.yuv") }.map { it.removePrefix(calPrefix).removeSuffix("_3.yuv") }.sorted()
    println("camera $cam · line $LINE · ${"%.2f".format(MPP * 1000)} mm/px · cars $cars")
    HINTS.clear()
    val racers = cars.map { calibrateFrom(it, empty, calPrefix) }
    File("/home/claude/blend/review").mkdirs()
    val clips = File(DIR).list()!!.filter { it.startsWith("form_${cam}_") && !it.contains("_cal_") && !it.endsWith("empty.yuv") && it.endsWith(".yuv") }.sorted()
    val summary = StringBuilder()
    for (cn in clips) {
        val name = cn.removePrefix("form_${cam}_").removeSuffix(".yuv")
        val clip = Clip.load(cn.removeSuffix(".yuv"))
        val truthTxt = File("$DIR/${cn.removeSuffix(".yuv")}.json").readText()
        val truth = Regex("\\{[^{}]*\"car\"[^{}]*\\}").findAll(truthTxt).map { m ->
            val j = Clip.flatJson(m.value)
            Truth((120 + j["crossIdx"]!!.toDouble()) / FPS, j["car"]!!, j["speed"]!!.toDouble(), along(j["lane"]!!.toDouble()))
        }.toList()
        val tl = Timeline(empty, seed = name.hashCode()); tl.base = clip to 120; tl.lead = empty
        val boxes = HashMap<Int, List<OverlayBox>>()
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it }, onOverlay = { b -> boxes[curFrame] = b })
        a.config = AnalyzerConfig(line = LINE, racers = racers, minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP, sizeHints = HINTS.toList())
        val frames = 120 + clip.n + 20
        for (i in 0 until frames) { curFrame = i; a.process(tl.frame(i)) }
        a.flush((frames * 1e9 / FPS).toLong())
        val s = score(truth, events)
        println("== $cam $name: $s")
        s.notes.forEach { println("   $it") }
        summary.append("$name\t${s.line}\n")
        // review dump: per truth crossing, the frame index, boxes near it, engine events
        val sb = StringBuilder()
        sb.append("{\"cam\":\"$cam\",\"clip\":\"$name\",\"summary\":\"${s.line}\",\"truth\":[")
        sb.append(truth.joinToString(",") { "{\"t\":%.4f,\"car\":\"%s\",\"along\":%.3f,\"speed\":%.1f}".format(it.t, it.car, it.along, it.speed) })
        sb.append("],\"events\":[")
        sb.append(events.joinToString(",") { e -> "{\"t\":%.4f,\"car\":%s,\"along\":%.3f,\"area\":%d,\"split\":%s,\"accepted\":%s,\"match\":%.2f,\"cands\":\"%s\"}".format(
            e.timestampNs / 1e9, e.racerId?.let { "\"$it\"" } ?: "null", e.along, e.area, e.split, e.accepted, e.match, e.candidates.take(3).joinToString(" ") { "%s %.2f".format(it.first, it.second) }) })
        sb.append("],\"boxes\":{")
        sb.append(boxes.entries.sortedBy { it.key }.joinToString(",") { (f, b) -> "\"$f\":[" + b.joinToString(",") { "[%.3f,%.3f,%.3f,%.3f]".format(it.l, it.t, it.r, it.b) } + "]" })
        sb.append("},\"line\":[%.4f,%.4f,%.4f,%.4f]}".format(LINE.x1, LINE.y1, LINE.x2, LINE.y2))
        File("/home/claude/blend/review/${cam}_$name.json").writeText(sb.toString())
    }
    File("/home/claude/blend/review/summary_$cam.tsv").writeText(summary.toString())
}

var curFrame = 0

fun calibrateFrom(car: String, empty: Clip, prefix: String): RacerRef {
    val passes = listOf(Clip.load("${prefix}${car}_3"), Clip.load("${prefix}${car}_7"))
    val tl = Timeline(empty, seed = car.hashCode())
    var at = 120
    for (c in passes) { tl.placed += Placed(c, at, 0f); at += c.n + 40 }
    val views = ArrayList<FloatArray>()
    lateinit var a: FrameAnalyzer
    fun cfg() = AnalyzerConfig(line = LINE, racers = listOf(RacerRef("x", if (views.isEmpty()) null else Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray(), null, views.toList())), minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = MPP)
    a = FrameAnalyzer(onCrossing = { e ->
        if (e.accepted && e.fingerprint != null) { views += e.fingerprint!!; a.config = cfg(); if (e.area > 0) HINTS += floatArrayOf(e.along, e.area.toFloat()) }
    })
    a.config = cfg()
    for (i in 0 until at) a.process(tl.frame(i))
    a.flush((at * 1e9 / FPS).toLong())
    val fp = if (views.isEmpty()) null else Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray()
    return RacerRef(car, fp, null, views)
}

class ScoreLine(val line: String, val notes: List<String>) { override fun toString() = line }

fun score(truth: List<Truth>, events: List<CrossingEvent>): ScoreLine {
    val acc = events.filter { it.accepted }
    fun cost(e: CrossingEvent, p: Truth): Double { val dt = abs(e.timestampNs / 1e9 - p.t); if (dt >= 0.15) return Double.MAX_VALUE; return dt / 0.05 + abs(e.along - p.along) / 0.1 }
    val pairs = ArrayList<Triple<Double, CrossingEvent, Truth>>()
    for (p in truth) for (e in acc) { val c = cost(e, p); if (c < Double.MAX_VALUE) pairs += Triple(c, e, p) }
    pairs.sortBy { it.first }
    val used = HashSet<CrossingEvent>(); val match = HashMap<Truth, CrossingEvent>()
    for ((_, e, p) in pairs) if (e !in used && p !in match) { used += e; match[p] = e }
    var ok = 0; var wrong = 0; var unknown = 0; val notes = ArrayList<String>(); val tErr = ArrayList<Double>()
    for (p in truth.sortedBy { it.t }) {
        val e = match[p]
        if (e == null) { notes += "MISSED  %s (lane along %.2f)".format(p.car, p.along); continue }
        tErr += abs(e.timestampNs / 1e9 - p.t) * 1000
        when (e.racerId) { p.car -> ok++; null -> { unknown++; notes += "UNKNOWN %s: %s".format(p.car, e.candidates.take(2).joinToString { "%s %.2f".format(it.first, it.second) }) }; else -> { wrong++; notes += "WRONG   %s read as %s".format(p.car, e.racerId) } }
    }
    val extra = acc.size - used.size
    for (e in acc) if (e !in used) notes += "EXTRA   t=%.2f as %s%s".format(e.timestampNs / 1e9, e.racerId ?: "unknown", if (e.split) " (split)" else "")
    for (r in events.filter { it.rescue }) {
        val hit = truth.firstOrNull { abs(it.t - r.timestampNs / 1e9) < 0.15 && match[it] == null }
        notes += "RESCUE  t=%.2f %s – %s".format(r.timestampNs / 1e9, r.candidates.firstOrNull()?.first ?: "?", if (hit != null) "would rescue missed ${hit.car}" else "false alarm")
    }
    val line = "%d crossings: found %d, missed %d, extra %d · named %d/%d/%d (right/wrong/unknown) · timing %.1f ms".format(
        truth.size, match.size, truth.size - match.size, extra, ok, wrong, unknown, if (tErr.isEmpty()) 0.0 else tErr.average())
    return ScoreLine(line, notes)
}
