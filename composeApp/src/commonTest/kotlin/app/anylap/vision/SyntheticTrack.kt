package app.anylap.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A synthetic track for the engine bench: textured floor with a light falloff and a seam, red/white
 * barriers, racer-sized cars with wheels and a windscreen, optional colour tags, motion blur by
 * sub-sampling the shutter, hard shadows from a low light, dim light and sensor noise. Everything
 * is scripted, so each run comes with exact ground truth. Numbers are chosen to look like the
 * 480 px analysis frames of a phone ~1.5 m from the line (Mini-Z ≈ 39×18 px).
 */
object SyntheticTrack {

    /** YUV colour (BT.601 studio range, like the camera gives). */
    data class Yuv(val y: Int, val u: Int, val v: Int)

    fun rgb(r: Int, g: Int, b: Int): Yuv {
        val y = (0.257 * r + 0.504 * g + 0.098 * b + 16).roundToInt().coerceIn(16, 235)
        val u = (-0.148 * r - 0.291 * g + 0.439 * b + 128).roundToInt().coerceIn(16, 240)
        val v = (0.439 * r - 0.368 * g - 0.071 * b + 128).roundToInt().coerceIn(16, 240)
        return Yuv(y, u, v)
    }

    data class Car(
        val id: String,
        val body: Yuv,
        /** Optional colour tag strip along the side; two colours = two halves. */
        val tag: List<Yuv> = emptyList(),
        val lengthCm: Float = 17f,
        val widthCm: Float = 8f,
    )

    /** One pass across the line: x(t) = lineX + v · (t − tCross). */
    data class Pass(val car: Car, val tCross: Double, val speedMs: Double, val laneY: Float)

    class Conditions(
        val name: String,
        /** Exposure as a fraction of the frame interval (Blender's "shutter"): 0.5 = 1/120 s at 60 fps. */
        val shutter: Float,
        /** 1 = bright, 0.5 = dim (luma halved). */
        val light: Float,
        /** Sensor noise, ± grey levels. */
        val noise: Int,
        val shadows: Boolean,
    )

    val GOOD = Conditions("good: 1/500 s, bright, no shadows", 0.12f, 1f, 3, false)
    val BAD = Conditions("bad: 1/250 s, dim, hard shadows", 0.25f, 0.5f, 8, true)
    val WORST = Conditions("worst: 1/120 s, dim, hard shadows", 0.5f, 0.5f, 8, true)

    fun lane(i: Int) = 40f + i * 38f

    class Renderer(val w: Int = 480, val h: Int = 270, val pxPerCm: Float = 2.3f, val fps: Int = 60, val cond: Conditions, seed: Int = 1, private val subSamples: Int = 8) {
        private val rnd = Random(seed)
        val lineX = w * 0.5f
        private val bgY = FloatArray(w * h)
        private val bgU = FloatArray(w * h)
        private val bgV = FloatArray(w * h)
        private val accY = FloatArray(w * h)
        private val accU = FloatArray(w * h)
        private val accV = FloatArray(w * h)
        private val cov = FloatArray(w * h)
        private val shade = FloatArray(w * h)
        private val layY = FloatArray(w * h)
        private val layU = FloatArray(w * h)
        private val layV = FloatArray(w * h)
        private val layOn = BooleanArray(w * h)
        private val touched = IntArray(w * h)
        private var nTouched = 0

        init {
            val floor = rgb(96, 96, 98)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val falloff = 1f - 0.25f * abs(x - w / 2f) / (w / 2f)
                var yy = floor.y * falloff + rnd.nextInt(-14, 15)
                if (y < 14 || y >= h - 14) { // red/white barrier blocks
                    val c = if ((x / 24) % 2 == 0) rgb(230, 230, 230) else rgb(200, 30, 30)
                    yy = c.y.toFloat(); bgU[i] = c.u.toFloat(); bgV[i] = c.v.toFloat()
                } else {
                    bgU[i] = floor.u + rnd.nextInt(-2, 3).toFloat(); bgV[i] = floor.v + rnd.nextInt(-2, 3).toFloat()
                    if (abs(x - w * 0.31f) < 1.5f) yy -= 20 // floor seam
                }
                bgY[i] = yy
            }
        }

        /** Paint into the sub-sample layer; later paint replaces earlier paint. */
        private fun fill(x0: Float, y0: Float, x1: Float, y1: Float, c: Yuv) {
            val xa = max(0, x0.toInt()); val xb = minOf(w, x1.toInt() + 1)
            val ya = max(0, y0.toInt()); val yb = minOf(h, y1.toInt() + 1)
            for (y in ya until yb) for (x in xa until xb) {
                if (x + 0.5f < x0 || x + 0.5f > x1 || y + 0.5f < y0 || y + 0.5f > y1) continue
                val i = y * w + x
                if (!layOn[i]) { layOn[i] = true; touched[nTouched++] = i }
                layY[i] = c.y.toFloat(); layU[i] = c.u.toFloat(); layV[i] = c.v.toFloat()
            }
        }

        private fun flushLayer(wgt: Float) {
            for (k in 0 until nTouched) {
                val i = touched[k]
                accY[i] += layY[i] * wgt; accU[i] += layU[i] * wgt; accV[i] += layV[i] * wgt; cov[i] += wgt
                layOn[i] = false
            }
            nTouched = 0
        }

        private fun fillShade(x0: Float, y0: Float, x1: Float, y1: Float, wgt: Float) {
            val xa = max(0, x0.toInt()); val xb = minOf(w, x1.toInt() + 1)
            val ya = max(0, y0.toInt()); val yb = minOf(h, y1.toInt() + 1)
            for (y in ya until yb) for (x in xa until xb) {
                if (x + 0.5f < x0 || x + 0.5f > x1 || y + 0.5f < y0 || y + 0.5f > y1) continue
                shade[y * w + x] += wgt
            }
        }

        private fun drawCar(car: Car, cx: Float, cy: Float) {
            val len = car.lengthCm * pxPerCm; val wid = car.widthCm * pxPerCm
            val x0 = cx - len / 2; val x1 = cx + len / 2; val y0 = cy - wid / 2; val y1 = cy + wid / 2
            fill(x0, y0, x1, y1, car.body)
            val dark = Yuv(40, 128, 128) // wheels / chassis seen from a low angle
            fill(x0, y0, x1, y0 + wid * 0.14f, dark)
            fill(x0, y1 - wid * 0.14f, x1, y1, dark)
            fill(cx - len * 0.12f, y0 + wid * 0.2f, cx + len * 0.12f, y1 - wid * 0.2f, Yuv((car.body.y * 0.6f).toInt(), car.body.u, car.body.v))
            if (car.tag.isNotEmpty()) { // 6 × 2 cm strip along the side
                val tl = 6f * pxPerCm; val tw = 2f * pxPerCm
                val ty0 = y0 + wid * 0.2f; val ty1 = ty0 + tw
                if (car.tag.size == 1) fill(cx - tl / 2, ty0, cx + tl / 2, ty1, car.tag[0])
                else { fill(cx - tl / 2, ty0, cx, ty1, car.tag[0]); fill(cx, ty0, cx + tl / 2, ty1, car.tag[1]) }
            }
        }

        private fun drawShadow(car: Car, cx: Float, cy: Float, wgt: Float) {
            val len = car.lengthCm * pxPerCm; val wid = car.widthCm * pxPerCm
            // low light from the upper left: shadow stretched to the lower right, 1.3× the car
            val sx = cx + len * 0.45f; val sy = cy + wid * 0.9f
            fillShade(sx - len * 0.65f, sy - wid * 0.55f, sx + len * 0.65f, sy + wid * 0.55f, wgt)
        }

        fun frame(t: Double, passes: List<Pass>): AnalysisFrame {
            accY.fill(0f); accU.fill(0f); accV.fill(0f); cov.fill(0f); shade.fill(0f)
            val dt = 1.0 / fps
            val wgt = 1f / subSamples
            val visible = passes.filter { abs(t - it.tCross) < 3.0 }
            for (s in 0 until subSamples) {
                val tt = t + cond.shutter * dt * (s + 0.5) / subSamples
                for (p in visible) {
                    val x = lineX + (p.speedMs * 100 * pxPerCm * (tt - p.tCross)).toFloat()
                    if (x < -80 || x > w + 80) continue
                    if (cond.shadows) drawShadow(p.car, x, p.laneY, wgt)
                }
                for (p in visible) {
                    val x = lineX + (p.speedMs * 100 * pxPerCm * (tt - p.tCross)).toFloat()
                    if (x < -80 || x > w + 80) continue
                    drawCar(p.car, x, p.laneY)
                }
                flushLayer(wgt)
            }
            val yb = ByteArray(w * h); val ub = ByteArray(w * h); val vb = ByteArray(w * h)
            for (i in 0 until w * h) {
                val sh = 1f - 0.5f * minOf(1f, shade[i])
                val c = minOf(1f, cov[i])
                var yy = bgY[i] * sh * (1 - c) + accY[i]
                var uu = bgU[i] * (1 - c) + accU[i]
                var vv = bgV[i] * (1 - c) + accV[i]
                yy = 16 + (yy - 16) * cond.light
                uu = 128 + (uu - 128) * (0.6f + 0.4f * cond.light)
                vv = 128 + (vv - 128) * (0.6f + 0.4f * cond.light)
                yy += rnd.nextInt(-cond.noise, cond.noise + 1)
                uu += rnd.nextInt(-cond.noise / 2, cond.noise / 2 + 1)
                vv += rnd.nextInt(-cond.noise / 2, cond.noise / 2 + 1)
                yb[i] = yy.roundToInt().coerceIn(0, 255).toByte()
                ub[i] = uu.roundToInt().coerceIn(0, 255).toByte()
                vb[i] = vv.roundToInt().coerceIn(0, 255).toByte()
            }
            return AnalysisFrame((t * 1e9).toLong(), w, h, yb, ub, vb, w, h) { _, _, _, _ -> null }
        }
    }

    // ------------------------------------------------------------------ running the engine

    /** The assisted wizard: slow + fast passes alone on the track; fingerprint = average, views = each pass. */
    fun calibrate(car: Car, r: Renderer, lane: Float, seedT: Double, speeds: List<Double> = listOf(1.2, 1.5, 1.3, 4.0, 4.5, 5.0), margin: Double = 1.5): RacerRef {
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        val views = ArrayList<FloatArray>()
        var t = seedT
        val dt = 1.0 / r.fps
        fun run(until: Double, passes: List<Pass>) {
            while (t < until) { a.process(r.frame(t, passes)); t += dt }
        }
        run(t + 2.0, emptyList())
        for (sp in speeds) {
            val fp = if (views.isEmpty()) null else Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray()
            a.config = AnalyzerConfig(racers = listOf(RacerRef("x", fp, null, views.toList())), minMatch = FrameAnalyzer.MIN_MATCH)
            val tc = t + margin
            run(tc + margin, listOf(Pass(car, tc, sp, lane)))
            a.flush((t * 1e9).toLong())
            val e = events.lastOrNull { abs(it.timestampNs / 1e9 - tc) < 0.2 }
            if (e?.fingerprint != null && e.accepted) views += e.fingerprint!!
            events.clear()
        }
        val fp = Fingerprint.average(views.map { it.toList() to 1f }).toFloatArray()
        return RacerRef(car.id, fp, null, views)
    }

    class Score(
        val passes: Int,
        val detected: Int,
        val falseExtra: Int,
        val rejected: Int,
        val idCorrect: Int,
        val idWrong: Int,
        val idUnknown: Int,
        val timingMeanMs: Double,
        val timingMaxMs: Double,
        val speedMeanPct: Double,
        val notes: List<String>,
    ) {
        val missed get() = passes - detected
        override fun toString() =
            "passes $passes: detected $detected, missed $missed, false extra $falseExtra, rejected $rejected · " +
                "identity ok $idCorrect / wrong $idWrong / unknown $idUnknown · " +
                "timing ${timingMeanMs.fixed(1)} ms mean, ${timingMaxMs.fixed(1)} max · speed ${speedMeanPct.fixed(1)} %"
    }

    fun race(r: Renderer, racers: List<RacerRef>, passes: List<Pass>): Score {
        val events = ArrayList<CrossingEvent>()
        val a = FrameAnalyzer(onCrossing = { events += it })
        a.config = AnalyzerConfig(racers = racers, minMatch = FrameAnalyzer.MIN_MATCH, metersPerProcPx = 0.01f / r.pxPerCm)
        val dt = 1.0 / r.fps
        var t = 0.0
        val end = passes.maxOf { it.tCross } + 2.0
        while (t < end) { a.process(r.frame(t, passes)); t += dt }
        a.flush((t * 1e9).toLong())

        val acc = events.filter { it.accepted }
        val used = HashSet<CrossingEvent>()
        var detected = 0; var idOk = 0; var idWrong = 0; var idNone = 0
        val tErr = ArrayList<Double>(); val vErr = ArrayList<Double>()
        val notes = ArrayList<String>()
        for (p in passes.sortedBy { it.tCross }) {
            val e = acc.filter { it !in used && abs(it.timestampNs / 1e9 - p.tCross) < 0.15 }
                .minByOrNull { abs(it.timestampNs / 1e9 - p.tCross) }
            if (e == null) { notes += "missed t=${p.tCross.fixed(2)} ${p.car.id}"; continue }
            used += e; detected++
            tErr += abs(e.timestampNs / 1e9 - p.tCross) * 1000
            e.speedKmh?.let { vErr += abs(it / 3.6 - p.speedMs) / p.speedMs * 100 }
            when (e.racerId) {
                p.car.id -> idOk++
                null -> { idNone++; notes += "t=${p.tCross.fixed(2)} ${p.car.id}: unknown (best ${e.candidates.firstOrNull()?.let { "${it.first} ${it.second.toDouble().fixed(2)}" }})" }
                else -> { idWrong++; notes += "t=${p.tCross.fixed(2)} ${p.car.id}: read as ${e.racerId}" }
            }
        }
        return Score(
            passes = passes.size, detected = detected, falseExtra = acc.size - used.size,
            rejected = events.count { !it.accepted },
            idCorrect = idOk, idWrong = idWrong, idUnknown = idNone,
            timingMeanMs = if (tErr.isEmpty()) 0.0 else tErr.average(), timingMaxMs = tErr.maxOrNull() ?: 0.0,
            speedMeanPct = if (vErr.isEmpty()) 0.0 else vErr.average(), notes = notes,
        )
    }

    /** Laps at different lap times so the cars drift past each other, plus scripted close calls. */
    fun passesFor(cars: List<Car>, laps: Int, closeCalls: Boolean, pxPerCm: Float = 2.3f, fps: Int = 60, lapTimeS: Double = 12.0): List<Pass> {
        val out = ArrayList<Pass>()
        val lapTimes = listOf(0.0, 0.6, 1.1, -0.3, 0.3, 0.9, -0.1, 0.4, 1.3, 0.1).map { it + lapTimeS }
        cars.forEachIndexed { i, c ->
            var t = 8.0 + i * 0.7
            for (l in 0 until laps) { out += Pass(c, t, listOf(4.5, 5.2, 3.8, 5.5)[(i + l) % 4], lane(i % 5)); t += lapTimes[i % lapTimes.size] }
        }
        if (closeCalls && cars.size >= 4) {
            val base = 8.0 + laps * (lapTimeS + 2.0)
            // overtake: two cars cross 2 frames apart in neighbouring lanes
            out += Pass(cars[0], base, 5.5, lane(1)); out += Pass(cars[2], base + 2.0 / fps, 4.0, lane(2))
            // side by side, touching, same instant → one merged blob
            out += Pass(cars[1], base + 4, 4.8, lane(1)); out += Pass(cars[3], base + 4, 4.8, lane(1) + 8.5f * pxPerCm)
            // nose to tail in one lane, 0.15 s apart
            out += Pass(cars[2], base + 8, 5.0, lane(3)); out += Pass(cars[0], base + 8.15, 5.0, lane(3))
            // the two look-alikes 3 frames apart
            out += Pass(cars[0], base + 12, 4.5, lane(0)); out += Pass(cars[1], base + 12 + 3.0 / fps, 4.5, lane(2))
        }
        return out
    }

    fun Double.fixed(d: Int): String {
        var m = 1.0; repeat(d) { m *= 10 }
        val v = (this * m).roundToInt()
        val int = v / m.toInt(); val frac = abs(v % m.toInt())
        return if (d == 0) int.toString() else "$int." + frac.toString().padStart(d, '0')
    }
}
