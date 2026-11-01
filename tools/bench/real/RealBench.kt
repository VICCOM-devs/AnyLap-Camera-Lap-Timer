package real

import app.anylap.data.LineSpec
import app.anylap.engine.LapSolver
import app.anylap.vision.AnalysisFrame
import app.anylap.vision.AnalyzerConfig
import app.anylap.vision.CarTemplate
import app.anylap.vision.CrossingEvent
import app.anylap.vision.Fingerprint
import app.anylap.vision.FrameAnalyzer
import app.anylap.vision.RacerRef
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Real-video bench: the vision engine + the end-of-race audit on a real race clip, scored against a
 * crossing list checked by eye ([clips]/<name>.json). Frames are fed the way the Android camera feeds
 * them: YUV 4:2:0, the region the analyzer asks for ([FrameAnalyzer.regionFor]) sampled nearest-pixel
 * to 480 px wide.
 *
 *   1. calibration – every car's first lone passes (N per car, nobody else at the line) captured like the
 *      calibration wizard does (fingerprint, top-down picture, size hint); those passes are not scored;
 *   2. race – the same clip with the calibrated cars, appearance gate on;
 *   3. audit – [LapSolver] over every logged crossing, as RaceEngine.audit() does when a race is saved
 *      (from the clip's "start" = lights out, as in race mode; no start = practice).
 *
 * Exit code 1 when a result is below the clip's "expect" block. Usage (see README):
 *   java ... real.RealBenchKt <clip.json> <video file> [--whole-frame] [--out dir]
 */
class Clip(val file: File) {
    private val s = file.readText()
    private fun num(k: String) = Regex("\"$k\"\\s*:\\s*([-0-9.eE]+)").find(s)!!.groupValues[1].toDouble()
    private fun nums(k: String) = Regex("\"$k\"\\s*:\\s*\\[([^\\]]*)]").find(s)!!.groupValues[1].split(",").map { it.trim().toDouble() }
    val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(s)!!.groupValues[1]
    val size = nums("size").map { it.toInt() }
    /** Lights out (s), or null = practice: the audit then has no start to measure lap 1 from. */
    val start = Regex("\"start\"\\s*:\\s*([-0-9.eE]+)").find(s)?.groupValues?.get(1)?.toDouble()
    val fps = num("fps"); val from = num("from"); val to = num("to"); val scoreUntil = num("scoreUntil")
    /** Crossings before this (s) are not scored, e.g. the start wave when the grid stands just behind the line. */
    val scoreFrom = Regex("\"scoreFrom\"\\s*:\\s*([-0-9.eE]+)").find(s)?.groupValues?.get(1)?.toDouble() ?: from
    /**
     * Cars that are identical in the clip (same body, same colour – nobody could tell them apart from the
     * picture): a name inside the right group counts as right, and laps are compared per group. Which twin
     * is which can only come from lap rhythm – the truth's and the audit's – so it isn't scored.
     */
    val twinGroups: List<List<String>> = Regex("\"twins\"\\s*:\\s*\\[(.*?)]\\s*]", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)
        ?.let { body -> Regex("\\[([^\\]]*)").findAll(body + "]").map { g -> Regex("\"(\\w+)\"").findAll(g.groupValues[1]).map { it.groupValues[1] }.toList() }.filter { it.size >= 2 }.toList() } ?: emptyList()
    fun group(car: String?) = car?.let { c -> twinGroups.firstOrNull { c in it }?.joinToString("/") ?: c }
    val line = nums("line").let { LineSpec(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
    val calPerCar = num("passesPerCar").toInt(); val calAfter = num("after"); val calAlone = num("aloneWithin")
    val expFound = num("found"); val expExtra = num("extra").toInt(); val expAudit = num("auditRight")
    /** Most laps the audit may count for a crossing no car drove (default 0). */
    val expNobody = Regex("\"nobody\"\\s*:\\s*([0-9]+)").find(s)?.groupValues?.get(1)?.toInt() ?: 0
    val expLaps = Regex("\"lapsExact\"\\s*:\\s*true").containsMatchIn(s)
    class Cross(val t: Double, val car: String, val along: Double)
    val crossings = Regex("\\[([-0-9.]+),\\s*\"(\\w+)\",\\s*([-0-9.]+)]").findAll(s).map { Cross(it.groupValues[1].toDouble(), it.groupValues[2], it.groupValues[3].toDouble()) }.toList()
    val cars = crossings.map { it.car }.distinct()
}

/** Runs the clip through an analyzer; returns every event (accepted, gated, rescues). */
fun runClip(clip: Clip, video: File, cfg: AnalyzerConfig): List<CrossingEvent> {
    val fw = clip.size[0]; val fh = clip.size[1]
    val pw = FrameAnalyzer.PROC_W; val ph = (pw.toLong() * fh / fw).toInt()
    val events = ArrayList<CrossingEvent>()
    val a = FrameAnalyzer(onCrossing = { events += it })
    a.config = cfg
    val p = ProcessBuilder("ffmpeg", "-v", "error", "-ss", clip.from.toString(), "-to", clip.to.toString(), "-i", video.path,
        "-f", "rawvideo", "-pix_fmt", "yuv420p", "-").redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val inp = DataInputStream(p.inputStream.buffered(1 shl 22))
    val cw = fw / 2
    val buf = ByteArray(fw * fh * 3 / 2)
    var i = 0
    var region: FloatArray? = null
    while (true) {
        try { inp.readFully(buf) } catch (e: java.io.EOFException) { break }
        region = a.regionFor(fw, fh)
        // the Android camera's sampling (Camera.android.kt toFrame), inside the region
        val rx = ((region?.get(0) ?: 0f) * fw).toInt(); val ry = ((region?.get(1) ?: 0f) * fh).toInt()
        val rw = (((region?.get(2) ?: 1f) - (region?.get(0) ?: 0f)) * fw).toInt(); val rh = (((region?.get(3) ?: 1f) - (region?.get(1) ?: 0f)) * fh).toInt()
        val y = ByteArray(pw * ph); val u = ByteArray(pw * ph); val v = ByteArray(pw * ph)
        var k = 0
        for (py in 0 until ph) {
            val sy = ry + py * rh / ph
            for (px in 0 until pw) {
                val sx = rx + px * rw / pw
                y[k] = buf[sy * fw + sx]
                u[k] = buf[fw * fh + (sy / 2) * cw + sx / 2]
                v[k] = buf[fw * fh + fw * fh / 4 + (sy / 2) * cw + sx / 2]
                k++
            }
        }
        val ts = ((clip.from + i / clip.fps) * 1e9).toLong()
        a.process(AnalysisFrame(ts, pw, ph, y, u, v, fw, fh, region) { x0, y0, w, h ->
            if (x0 < 0 || y0 < 0 || x0 + w > fw || y0 + h > fh) null else ByteArray(w * h).also { o -> for (r in 0 until h) System.arraycopy(buf, (y0 + r) * fw + x0, o, r * w, w) }
        })
        i++
    }
    p.waitFor()
    a.flush(((clip.from + i / clip.fps) * 1e9).toLong())
    System.err.println("  $i frames, region ${region?.joinToString { "%.3f".format(it) } ?: "whole frame"}")
    return events
}

/** Greedy pairing of engine times with true crossings (≤ 0.2 s, cost = time + place along the line). */
fun <E> pair(truth: List<Clip.Cross>, engine: List<E>, t: (E) -> Double, along: (E) -> Double?): Map<Int, Int> {
    val c = ArrayList<Triple<Double, Int, Int>>()
    for ((i, x) in truth.withIndex()) for ((j, e) in engine.withIndex()) {
        val dt = abs(t(e) - x.t); if (dt >= 0.2) continue
        c += Triple(dt / 0.05 + (along(e)?.let { abs(it - x.along) / 0.1 } ?: 0.0), i, j)
    }
    c.sortBy { it.first }
    val mt = HashMap<Int, Int>(); val used = HashSet<Int>()
    for ((_, i, j) in c) if (i !in mt && j !in used) { mt[i] = j; used += j }
    return mt
}

fun main(args: Array<String>) {
    val clip = Clip(File(args[0])); val video = File(args[1])
    val whole = "--whole-frame" in args
    val out = args.indexOf("--out").takeIf { it >= 0 }?.let { File(args[it + 1]) }?.also { it.mkdirs() }
    val truth = clip.crossings.filter { it.t < clip.scoreUntil && it.t >= clip.scoreFrom }
    println("== ${clip.name}: ${truth.size} true crossings, ${clip.cars.size} cars, ${clip.fps.toInt()} fps, " + if (whole) "WHOLE FRAME (zoomToLine off)" else "zoomed to the line")

    // ---- 1. calibration
    System.err.println("calibration pass")
    val calEvents = runClip(clip, video, AnalyzerConfig(line = clip.line, captureTemplates = true, zoomToLine = !whole)).filter { it.accepted && !it.rescue }
    val calPair = pair(truth, calEvents, { it.timestampNs / 1e9 }, { it.along.toDouble() })
    val calUsed = HashSet<Int>()
    val racers = ArrayList<RacerRef>(); val hints = ArrayList<FloatArray>()
    for (car in clip.cars) {
        val picks = truth.indices.filter { i ->
            val x = truth[i]
            x.car == car && x.t >= clip.calAfter && i in calPair && truth.none { o -> o !== x && abs(o.t - x.t) < clip.calAlone } &&
                calEvents[calPair[i]!!].fingerprint != null
        }.take(clip.calPerCar)
        calUsed += picks
        val evs = picks.map { calEvents[calPair[it]!!] }
        val views = evs.map { it.fingerprint!! }
        val tpl: CarTemplate? = evs.firstNotNullOfOrNull { it.template }
        evs.forEach { if (it.area > 0) hints += floatArrayOf(it.along, it.area.toFloat()) }
        racers += RacerRef(car, if (views.isEmpty()) null else Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray(), null, views, tpl)
        println("  calibrated %-14s %d passes%s".format(car, views.size, if (tpl != null) ", top-down picture (zoom %.2f)".format(tpl.zoom) else ""))
    }

    // ---- 2. race
    System.err.println("race pass")
    val ev = runClip(clip, video, AnalyzerConfig(line = clip.line, racers = racers, sizeHints = hints, minMatch = FrameAnalyzer.MIN_MATCH, zoomToLine = !whole))
        .filter { it.timestampNs / 1e9 < clip.scoreUntil }
    val evScored = ev.filter { it.timestampNs / 1e9 >= clip.scoreFrom - 0.2 }
    val scored = truth.indices.filter { it !in calUsed }
    val acc = evScored.filter { it.accepted && !it.rescue }.filter { it.timestampNs / 1e9 >= clip.scoreFrom || truth.any { x -> abs(x.t - it.timestampNs / 1e9) < 0.2 } }
    val mp = pair(truth, acc, { it.timestampNs / 1e9 }, { it.along.toDouble() })
    val found = mp.size; val extra = acc.size - mp.size
    var right = 0; var wrong = 0; var unknown = 0; var missed = 0
    val notes = ArrayList<String>()
    for (i in scored) {
        val x = truth[i]; val e = mp[i]?.let { acc[it] }
        when {
            e == null -> { missed++; notes += "MISSED   %7.2f s %s".format(x.t, x.car) }
            clip.group(e.racerId) == clip.group(x.car) -> right++
            e.racerId == null -> { unknown++; notes += "UNKNOWN  %7.2f s %-14s best %s".format(x.t, x.car, e.candidates.take(2).joinToString { "%s %.2f".format(it.first, it.second) }) }
            else -> { wrong++; notes += "WRONG    %7.2f s %-14s named %s".format(x.t, x.car, e.racerId) }
        }
    }
    val tErr = mp.map { (i, j) -> (acc[j].timestampNs / 1e9 - truth[i].t) * 1000 }
    // ---- 3. end-of-race audit (RaceEngine.audit: LapSolver over every logged crossing, twins by look)
    val ids = racers.map { it.id }
    val ins = ev.map { e ->
        LapSolver.In(e.timestampNs, if (e.accepted) e.racerId?.takeIf { it in ids } else null, e.candidates.filter { it.first in ids }, e.confidence, e.split, e.rescue,
            gated = !e.accepted && !e.rescue, sizeRatio = e.sizeRatio, fitted = e.quad != null && e.racerId != null)
    }
    val fps = racers.filter { it.fingerprint != null }
    val twins = ArrayList<List<String>>(); val seen = HashSet<String>()
    for (a in fps) { if (a.id in seen) continue
        val g = fps.filter { b -> b.id !in seen && (b.id == a.id || Fingerprint.similarity(a.fingerprint!!, b.fingerprint!!) >= 0.88f) }.map { it.id }
        if (g.size >= 2) { twins += g.take(3); seen += g.take(3) } }
    val outs = LapSolver.solve(ins, ids, clip.start?.let { (it * 1e9).toLong() }, 2_500_000_000L, (clip.scoreUntil * 1e9).toLong(), twins.filter { g -> ins.count { it.named in g } <= 40 })
    val counted = outs.filter { it.racer != null && it.ns / 1e9 >= clip.scoreFrom - 0.2 }.filter { it.ns / 1e9 >= clip.scoreFrom || truth.any { x -> abs(x.t - it.ns / 1e9) < 0.2 } }
    val ma = pair(truth, counted, { it.ns / 1e9 }, { null })
    var aRight = 0; var aWrong = 0; var aNot = 0
    for (i in scored) { val o = ma[i]?.let { counted[it] }; when { o == null -> aNot++; clip.group(o.racer) == clip.group(truth[i].car) -> aRight++; else -> { aWrong++; notes += "AUDIT    %7.2f s %-14s counted for %s".format(truth[i].t, truth[i].car, o.racer) } } }
    val aExtra = counted.size - ma.size
    val lapsTrue = truth.groupingBy { clip.group(it.car) }.eachCount(); val lapsEng = counted.groupingBy { clip.group(it.racer)!! }.eachCount()
    val groups = clip.cars.map { clip.group(it)!! }.distinct()

    val n = scored.size
    fun pc(k: Int, of: Int = n) = "%5.1f %%".format(100.0 * k / of)
    println("crossings   found ${found}/${truth.size} (${pc(found, truth.size)}), extra $extra, check-line rescues ${ev.count { it.rescue }}")
    println("timing      vs truth: mean |err| %.1f ms, max %.1f ms (centroid truth at the clip frame rate, not an absolute check)".format(tErr.map { abs(it) }.average(), tErr.maxOf { abs(it) }))
    println("named live  right $right (${pc(right)}), wrong $wrong (${pc(wrong)}), unknown $unknown, missed $missed   [$n scored, calibration passes left out]")
    println("after audit right $aRight (${pc(aRight)}), wrong $aWrong, not counted $aNot, laps for nobody $aExtra")
    println("laps        " + groups.joinToString("  ") { "$it ${lapsEng[it] ?: 0}/${lapsTrue[it] ?: 0}" })
    if (clip.twinGroups.isNotEmpty()) println("            (identical cars scored as a group: ${clip.twinGroups.joinToString { it.joinToString("/") }})")
    notes.sorted().take(40).forEach { println("  $it") }
    out?.let { d ->
        File(d, "${clip.name}${if (whole) "-whole" else ""}.csv").printWriter().use { w ->
            w.println("t,accepted,rescue,racer,match,area,along,split,cands")
            for (e in ev) w.println("%.4f,%s,%s,%s,%.3f,%d,%.3f,%s,%s".format(e.timestampNs / 1e9, e.accepted, e.rescue, e.racerId ?: "", e.match, e.area, e.along, e.split, e.candidates.joinToString(" ") { "${it.first}:%.2f".format(it.second) }))
        }
    }
    val fails = ArrayList<String>()
    if (found < clip.expFound * truth.size - 1e-9) fails += "found ${found}/${truth.size}"
    if (extra > clip.expExtra) fails += "extra $extra"
    if (aRight < clip.expAudit * n - 1e-9) fails += "audit right $aRight/$n"
    if (clip.expLaps && groups.any { lapsEng[it] != lapsTrue[it] }) fails += "lap counts differ"
    if (aExtra > clip.expNobody) fails += "laps for nobody $aExtra"
    if (fails.isEmpty()) println("PASS") else { println("FAIL: " + fails.joinToString("; ")); exitProcess(1) }
}
