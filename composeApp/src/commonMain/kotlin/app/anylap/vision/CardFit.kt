package app.anylap.vision

import kotlin.math.PI as PI_D
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Outlines for a Check card ("correction stage"): every car in a crossing's picture gets its own
 * calibration picture fitted onto it – the right size, turned to its heading, placed where its
 * picture matches the camera's foreground best – so the outline covers that car and nothing else.
 *
 * Runs after the race, when it is known who each box is (the cars the race counted at that moment),
 * so each box is fitted with *its* car's picture, near the box the tracker saw. Cars are placed one
 * at a time, best fit first, each on what the others have not explained; then every car is fitted
 * once more with the others in place (two passes settle touching pairs).
 */
private const val PI = PI_D.toFloat()

object CardFit {
    /** One car in the picture: who it is (null = unknown), its picture, and the tracker's box (0..1 of the picture). */
    class Spec(
        val racerId: String?, val template: CarTemplate?, val l: Float, val t: Float, val r: Float, val b: Float, val vx: Float, val vy: Float,
        /** Placed from the race's record (no tracker box in this frame): only where its picture clearly matches, or nothing else is. */
        val fromRecord: Boolean = false,
    )

    /**
     * Fitted outline: 4 corners x,y (0..1 of the picture), nose edge first; [score] = how well its picture
     * matched (0 = placed from the race's own record, the camera barely saw it); [vx], [vy] its speed
     * in picture widths / heights per second.
     */
    class Fit(val racerId: String?, val quad: FloatArray, val score: Float, val vx: Float = 0f, val vy: Float = 0f) {
        fun contains(x: Float, y: Float): Boolean {
            var sign = 0
            for (k in 0 until 4) {
                val ax = quad[2 * k]; val ay = quad[2 * k + 1]; val bx = quad[(2 * k + 2) % 8]; val by = quad[(2 * k + 3) % 8]
                val c = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
                val sg = if (c > 0) 1 else if (c < 0) -1 else 0
                if (sg != 0) { if (sign == 0) sign = sg else if (sg != sign) return false }
            }
            return true
        }
    }

    const val MIN_SCORE = 0.2f
    /** An extra car (no box of its own) needs a clearly matching picture. */
    const val FILL_MIN = 0.45f
    /** A car placed from the race's record (no tracker box) needs a fair match on what nobody else explains. */
    const val RECORD_MIN = 0.3f
    /** Score lost for each picture pixel lying on a car already placed. */
    const val OVERLAP_COST = 0.6f
    /** Score lost per radian turned away from the expected heading. */
    const val TURN_COST = 0.4f
    private const val SIGMA = 28f
    private val ANGLES = floatArrayOf(-0.26f, -0.17f, -0.09f, 0f, 0.09f, 0.17f, 0.26f)

    private class Rot(val dx: FloatArray, val dy: FloatArray, val y: FloatArray, val u: FloatArray, val v: FloatArray)
    private class Place(val score: Float, val x: Float, val y: Float, val a: Float, val t: CarTemplate, val id: String?)

    /**
     * [y], [u], [v], [fg] = the picture's planes ([w] × [h]); [lumaRef] its median luma; [pool] = pictures
     * to try for boxes of unknown cars; [exposureS] the shutter (the outline is stretched by the blur
     * streak the camera sees, so it covers the car as it looks in the picture).
     */
    fun fit(
        w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray, fg: ByteArray, lumaRef: Float,
        specs: List<Spec>, pool: List<Pair<String, CarTemplate>>, exposureS: Float, fillRest: Boolean = true,
        line: FloatArray? = null,
    ): List<Fit?> {
        val explained = IntArray(w * h)   // index+1 of the car that explains a pixel, 0 = none
        val inv = 1f / (2 * SIGMA * SIGMA)
        // pictures already placed (a named car's picture is not tried on an unknown box once it is placed)
        val used = HashSet<String>()

        fun rotateNow(t: CarTemplate, ang: Float): Rot {
            val cs = cos(ang); val sn = sin(ang); val gain = lumaRef / max(1f, t.lumaRef)
            // the pixels that were the car when its picture was taken
            val idx = (0 until t.w * t.h).filter { t.mask[it] > 0.5f }
            val r = Rot(FloatArray(idx.size), FloatArray(idx.size), FloatArray(idx.size), FloatArray(idx.size), FloatArray(idx.size))
            for ((k, q) in idx.withIndex()) {
                val uu = q % t.w - t.w / 2f + 0.5f; val vv = q / t.w - t.h / 2f + 0.5f
                r.dx[k] = uu * cs - vv * sn; r.dy[k] = uu * sn + vv * cs
                r.y[k] = t.y[q] * gain; r.u[k] = t.u[q]; r.v[k] = t.v[q]
            }
            return r
        }
        val rotCache = HashMap<Pair<CarTemplate, Int>, Rot>()
        fun rotate(t: CarTemplate, ang: Float): Rot = rotCache.getOrPut(t to (ang * 1000).roundToInt()) { rotateNow(t, ang) }
        fun score(r: Rot, px: Float, py: Float, me: Int, stride: Int = 1): Float {
            var s = 0f; var inPic = 0
            for (k in r.dx.indices step stride) {
                val x = (px + r.dx[k]).roundToInt(); val yy = (py + r.dy[k]).roundToInt()
                if (x < 0 || yy < 0 || x >= w || yy >= h) continue
                inPic++
                val q = yy * w + x
                val ex = explained[q]
                if (ex > 0 && ex != me) { s -= OVERLAP_COST; continue }   // two cars can't be in one place
                if (fg[q].toInt() == 0 || ex != 0 && ex != me) continue
                val dY = (y[q].toInt() and 0xFF) - r.y[k]; val dU = (u[q].toInt() and 0xFF) - r.u[k]; val dV = (v[q].toInt() and 0xFF) - r.v[k]
                s += 1f / (1f + (0.5f * dY * dY + dU * dU + dV * dV) * inv)
            }
            // a car partly out of the picture is judged on the part that is in it (at least 30 % of it counts)
            return s / max(1f, max(inPic.toFloat(), 0.3f * r.dx.size / stride))
        }
        // cars at the line all run roughly the same way: a box whose own velocity disagrees by more
        // than 40° with the others' (a box that just split or merged jumps) takes the common heading
        val common: Float? = run {
            var sx = 0f; var sy = 0f
            for (o in specs) { val vx = o.vx * w; val vy = o.vy * h; val n = hypot(vx, vy); if (n > 5f) { sx += vx / n; sy += vy / n } }
            if (hypot(sx, sy) > 0.5f) atan2(sy, sx) else null
        }
        // across the line, either way: a car at the line is rarely more than ~40° off it, so this is
        // tried too whenever the box's velocity says something else (a box that jumped as it split)
        val normal: Float? = line?.let { l -> atan2((l[2] - l[0]) * w, -(l[3] - l[1]) * h) }
        fun near(a: Float, b: Float): Boolean { var d = a - b; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; return abs(d) < 0.12f }
        fun heading(s: Spec): Float {
            val vx = s.vx * w; val vy = s.vy * h
            val own = if (hypot(vx, vy) > 5f) atan2(vy, vx) else null
            if (own != null && common != null) {
                var d = own - common
                while (d > kotlin.math.PI) d -= 2 * kotlin.math.PI.toFloat()
                while (d < -kotlin.math.PI) d += 2 * kotlin.math.PI.toFloat()
                return if (abs(d) <= 0.7f) own else common
            }
            return own ?: common ?: 0f
        }
        // (angle, penalty): a small cost for turning away from the expected heading, so a car that
        // matches its picture weakly (dark, low contrast) is not tilted just to dodge a neighbour
        fun anglesFor(s: Spec): List<Pair<Float, Float>> {
            val hd = heading(s)
            val bases = arrayListOf(hd)
            if (normal != null) {
                // the normal pointing the way the cars go (or the way this box goes, if nobody moves)
                val ref = common ?: hd
                val n = if (cos(normal - ref) >= 0f) normal else normal + PI
                if (bases.none { near(it, n) }) bases += n
            }
            return bases.withIndex().flatMap { (k, b) -> ANGLES.map { (b + it) to (TURN_COST * abs(it) + if (k > 0) 0.01f else 0f) } }
        }
        fun unknownOutline(reg: IntArray, mv: Pair<Float, Float>): Fit {
            val a = common ?: normal ?: 0f
            val c = cos(a); val sn = sin(a)
            val cx = reg.sumOf { it % w }.toFloat() / reg.size; val cy = reg.sumOf { it / w }.toFloat() / reg.size
            val us = FloatArray(reg.size) { k -> (reg[k] % w - cx) * c + (reg[k] / w - cy) * sn }.also { it.sort() }
            val vs = FloatArray(reg.size) { k -> -(reg[k] % w - cx) * sn + (reg[k] / w - cy) * c }.also { it.sort() }
            val u0 = us[(reg.size * 0.03f).toInt()]; val u1 = us[(reg.size * 0.97f).toInt().coerceAtMost(reg.size - 1)]
            val v0 = vs[(reg.size * 0.03f).toInt()]; val v1 = vs[(reg.size * 0.97f).toInt().coerceAtMost(reg.size - 1)]
            val mx = cx + (u0 + u1) / 2 * c - (v0 + v1) / 2 * sn; val my = cy + (u0 + u1) / 2 * sn + (v0 + v1) / 2 * c
            val l = (u1 - u0) / 2 + 1f; val wd = (v1 - v0) / 2 + 1f
            val pts = listOf(l to -wd, l to wd, -l to wd, -l to -wd)
            val q = FloatArray(8)
            for ((k, pt) in pts.withIndex()) { q[2 * k] = (mx + pt.first * c - pt.second * sn) / w; q[2 * k + 1] = (my + pt.first * sn + pt.second * c) / h }
            return Fit(null, q, 0f, mv.first, mv.second)
        }
        fun centreOf(s: Spec) = ((s.l + s.r) / 2 * w).coerceIn(0f, w - 1f) to ((s.t + s.b) / 2 * h).coerceIn(0f, h - 1f)
        fun bestFor(s: Spec, cands: List<Pair<String, CarTemplate>>, me: Int, hints: List<Pair<Float, Float>> = listOf(centreOf(s))): Place? {
            // the box only says roughly where: a car the tracker merged into a neighbour's box (or split off
            // into a box of its own) is next to it, so a named car is looked for up to 1.5 lengths away
            var top: Place? = null
            for ((cx0, cy0) in hints) for ((id, t) in cands) {
                val reach = max((if (s.racerId != null) 1.5f else 0.6f) * t.lengthPx, max((s.r - s.l) * w, (s.b - s.t) * h) / 2 + 3f)
                for ((ang, pen) in anglesFor(s)) {
                    val r = rotate(t, ang)
                    var bx = 0f; var by = 0f; var bs = -1f
                    var yy = cy0 - reach
                    while (yy <= cy0 + reach) {
                        var xx = cx0 - reach
                        while (xx <= cx0 + reach) {
                            val sc = score(r, xx, yy, me, 3)   // coarse: every 3rd pixel of the picture
                            if (sc > bs) { bs = sc; bx = xx; by = yy }
                            xx += 2f
                        }
                        yy += 2f
                    }
                    bs = score(r, bx, by, me)
                    val gx = bx; val gy = by
                    for (oy in -1..1) for (ox in -1..1) { val sc = score(r, gx + ox, gy + oy, me); if (sc > bs) { bs = sc; bx = gx + ox; by = gy + oy } }
                    bs -= pen
                    if (top == null || bs > top.score) top = Place(bs, bx, by, ang, t, id)
                }
            }
            return top
        }
        fun overlapOf(p: Place): Float {
            val c = cos(p.a); val s = sin(p.a); val l = p.t.lengthPx / 2; val wd = p.t.widthPx / 2
            val ext = (l + wd).toInt() + 2
            var n = 0; var hit = 0
            for (yy in (p.y - ext).toInt()..(p.y + ext).toInt()) for (xx in (p.x - ext).toInt()..(p.x + ext).toInt()) {
                val dx = xx - p.x; val dy = yy - p.y
                if (abs(dx * c + dy * s) > l || abs(-dx * s + dy * c) > wd) continue
                n++
                if (xx in 0 until w && yy in 0 until h && explained[yy * w + xx] > 0) hit++
            }
            return if (n == 0) 0f else hit.toFloat() / n
        }
        fun best(si: Int, cands: List<Pair<String, CarTemplate>>): Place? {
            val s = specs[si]
            val own = centreOf(s)
            if (s.racerId == null || s.fromRecord) return bestFor(s, cands, si + 1)
            // a counted car may sit in a box the tracker had no name for (it split off a neighbour's box)
            val len = cands.maxOf { it.second.lengthPx }
            val near = specs.filter { it.racerId == null && !it.fromRecord }.map { centreOf(it) }
                .filter { (x, y) -> hypot(x - own.first, y - own.second) < 2.5f * len }
            return bestFor(s, cands, si + 1, listOf(own) + near)
        }
        fun outline(p: Place, speedPx: Float): FloatArray {
            val len = p.t.lengthPx + FrameAnalyzer.BLUR_SEEN * speedPx * exposureS + 1f
            val wid = p.t.widthPx + 1f
            val c = cos(p.a); val s = sin(p.a); val l = len / 2; val wd = wid / 2
            val pts = listOf(l to -wd, l to wd, -l to wd, -l to -wd)
            val out = FloatArray(8)
            for ((k, q) in pts.withIndex()) { out[2 * k] = (p.x + q.first * c - q.second * s) / w; out[2 * k + 1] = (p.y + q.first * s + q.second * c) / h }
            return out
        }
        fun mark(p: Place, me: Int, on: Boolean) {
            val c = cos(p.a); val s = sin(p.a); val l = p.t.lengthPx / 2 - 0.5f; val wd = p.t.widthPx / 2 - 0.5f   // the car itself (pixel centres inside it)
            val ext = (l + wd).toInt() + 2
            for (yy in max(0, (p.y - ext).toInt())..min(h - 1, (p.y + ext).toInt())) for (xx in max(0, (p.x - ext).toInt())..min(w - 1, (p.x + ext).toInt())) {
                val dx = xx - p.x; val dy = yy - p.y
                if (abs(dx * c + dy * s) <= l && abs(-dx * s + dy * c) <= wd) {
                    val q = yy * w + xx
                    if (on && explained[q] == 0) explained[q] = me else if (!on && explained[q] == me) explained[q] = 0
                }
            }
        }
        fun candsOf(i: Int): List<Pair<String, CarTemplate>> {
            val s = specs[i]
            if (s.racerId != null) (s.template ?: pool.firstOrNull { it.first == s.racerId }?.second)?.let { return listOf(s.racerId to it) }
            val boxed = specs.filter { it.racerId != null && !it.fromRecord }.mapNotNull { it.racerId }
            return pool.filter { it.first !in used && it.first !in boxed }
        }

        val placed = arrayOfNulls<Place>(specs.size)
        val left = specs.indices.filter { candsOf(it).isNotEmpty() }.toMutableList()
        // pass 1: best fit first, each on what the others have not explained
        while (left.isNotEmpty()) {
            // named cars first (the race says they are here), then boxes nobody was counted for
            // order: named cars in a tracker box, then named cars from the record, then unknown boxes
            val round = left.filter { specs[it].racerId != null && !specs[it].fromRecord }
                .ifEmpty { left.filter { specs[it].racerId != null } }.ifEmpty { left }
            val (i, p) = round.map { it to best(it, candsOf(it)) }.maxByOrNull { it.second?.score ?: -1f } ?: break
            left.remove(i)
            val need = when { specs[i].racerId == null -> FILL_MIN; specs[i].fromRecord -> RECORD_MIN; else -> MIN_SCORE }
            if (p == null || p.score < need) continue
            placed[i] = p; mark(p, i + 1, true)
            p.id?.let { used += it }
        }
        // pass 2: each car again with the others in place
        for (i in specs.indices) {
            val p = placed[i] ?: continue
            mark(p, i + 1, false)
            val q = best(i, if (specs[i].racerId != null) candsOf(i) else listOf(p.id!! to p.t)) ?: p
            val keep = if (q.score >= p.score) q else p
            placed[i] = keep; mark(keep, i + 1, true)
        }
        val out: MutableList<Fit?> = specs.indices.map { i ->
            val s = specs[i]; val p = placed[i] ?: return@map null
            Fit(s.racerId ?: p.id, outline(p, hypot(s.vx * w, s.vy * h)), p.score, s.vx, s.vy)
        }.toMutableList()
        val outPlace = ArrayList<Place?>(placed.toList())
        val outMark = ArrayList<Int>(specs.indices.map { it + 1 })
        val movedOnce = HashSet<Int>()
        // pass 3: moving pixels no outline explains, big enough for a car (a car the tracker merged into
        // a neighbour, or one just entering the picture) – fitted with the pictures nobody has used,
        // the cars the race says are around first
        if (fillRest && pool.isNotEmpty()) {
            val minArea = pool.minOf { (_, t) -> t.mask.count { it > 0.5f } } * 0.25f
            val expected = specs.filter { it.fromRecord }.mapNotNull { it.racerId }.toSet()
            for (round in 0 until 5) {
                val seen = BooleanArray(w * h)
                var bestRegion: IntArray? = null
                for (q0 in 0 until w * h) {
                    if (seen[q0] || fg[q0].toInt() == 0 || explained[q0] != 0) continue
                    val stack = ArrayList<Int>(); stack += q0; seen[q0] = true
                    val region = ArrayList<Int>()
                    while (stack.isNotEmpty()) {
                        val q = stack.removeAt(stack.size - 1); region += q
                        val x = q % w; val yy = q / w
                        for ((nx, ny) in arrayOf(x - 1 to yy, x + 1 to yy, x to yy - 1, x to yy + 1)) {
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                            val n = ny * w + nx
                            if (!seen[n] && fg[n].toInt() != 0 && explained[n] == 0) { seen[n] = true; stack += n }
                        }
                    }
                    if (region.size >= minArea && region.size > (bestRegion?.size ?: 0)) bestRegion = region.toIntArray()
                }
                val reg = bestRegion ?: break
                val cx = reg.sumOf { it % w }.toFloat() / reg.size; val cy = reg.sumOf { it / w }.toFloat() / reg.size
                val mv = specs.map { it.vx to it.vy }.maxByOrNull { hypot(it.first * w, it.second * h) } ?: (0f to 0f)
                val tmp = Spec(null, null, (cx - 1) / w, (cy - 1) / h, (cx + 1) / w, (cy + 1) / h, mv.first, mv.second)
                val free = pool.filter { it.first !in used }
                val me = specs.size + out.size + 1
                // a car outlined already may fit this spot clearly better (its picture went on a neighbour
                // that looks alike): move it here; the spot it leaves is filled in the next round
                if (reg.size >= minArea * 2) {
                    val moved = out.indices.filter { j -> out[j] != null && outPlace[j] != null && j !in movedOnce }.mapNotNull { j ->
                        val old = outPlace[j]!!
                        val here = bestFor(tmp, listOf(old.id!! to old.t), me) ?: return@mapNotNull null
                        if (here.score >= FILL_MIN && here.score > old.score + 0.1f) return@mapNotNull j to here
                        // or: it fits here fairly, and the spot it holds is clearly another free car's
                        if (here.score < RECORD_MIN || free.isEmpty()) return@mapNotNull null
                        mark(old, outMark[j], false)
                        val spot = Spec(null, null, (old.x - 1) / w, (old.y - 1) / h, (old.x + 1) / w, (old.y + 1) / h, mv.first, mv.second)
                        val other = bestFor(spot, free, me + 1)
                        mark(old, outMark[j], true)
                        if (other != null && other.score >= FILL_MIN && here.score + other.score > old.score + 0.3f) j to here else null
                    }.maxByOrNull { it.second.score }
                    if (moved != null) {
                        val (j, np) = moved
                        movedOnce += j
                        mark(outPlace[j]!!, outMark[j], false)
                        mark(np, outMark[j], true)
                        val f = out[j]!!
                        out[j] = Fit(f.racerId, outline(np, hypot(f.vx * w, f.vy * h)), np.score, f.vx, f.vy)
                        outPlace[j] = np
                        continue
                    }
                }
                if (free.isEmpty()) break
                val p = free.filter { it.first in expected }.takeIf { it.isNotEmpty() }?.let { bestFor(tmp, it, me) }?.takeIf { it.score >= RECORD_MIN }
                    ?: bestFor(tmp, free, me)
                if (p == null || p.score < (if (p.id in expected) RECORD_MIN else FILL_MIN)) {
                    for (q in reg) explained[q] = -1
                    // a car-sized moving patch nobody's picture matches: outlined as an unknown car all the same
                    if (reg.size >= minArea * 2.4f) { out += unknownOutline(reg, mv); outPlace += null; outMark += 0 }
                    continue
                }
                mark(p, me, true)
                p.id?.let { used += it }
                out += Fit(p.id, outline(p, hypot(mv.first * w, mv.second * h)), p.score, mv.first, mv.second)
                outPlace += p; outMark += me
            }
        }
        // last: a car the race knows is here but the camera barely saw (a dark car on dark tarmac) – its
        // own picture's size and heading where the tracker / its last crossing put it, unless it is
        // outlined already or that spot is taken by another car (then its record was off: it isn't here)
        for (i in specs.indices) {
            if (out[i] != null) continue
            val s = specs[i]
            val id = s.racerId ?: continue
            if (id in used) continue
            val t = s.template ?: pool.firstOrNull { it.first == id }?.second ?: continue
            val guess = Place(0f, (s.l + s.r) / 2 * w, (s.t + s.b) / 2 * h, heading(s), t, id)
            if (overlapOf(guess) > 0.15f) continue
            mark(guess, i + 1, true); used += id
            out[i] = Fit(id, outline(guess, hypot(s.vx * w, s.vy * h)), 0f, s.vx, s.vy)
        }
        return out
    }

    /**
     * Outlines for every car in a Check card's picture. [ownersOf] gives, for a box's track, the cars the
     * race finally counted for it (two when the tracker had two touching cars in one box; empty = not
     * known – then the box is fitted with the pictures nobody else in the card has). [pool] = every
     * racer's calibration picture.
     */
    fun card(
        snap: Snapshot, ownersOf: (trackId: Int) -> List<String>, pool: List<Pair<String, CarTemplate>>, exposureS: Float,
        others: List<Seen> = emptyList(),
    ): List<Fit> {
        val byId = pool.toMap()
        val specs = ArrayList<Spec>()
        for (b in snap.boxes) {
            val owners = ownersOf(b.trackId).distinct().filter { o -> specs.none { it.racerId == o } }
            if (owners.isEmpty()) specs += Spec(null, null, b.l, b.t, b.r, b.b, b.vx, b.vy)
            else for (o in owners.take(3)) {
                // the crossing's own velocity (measured over the whole approach) beats a box's last step
                val sn = others.filter { it.racerId == o }.minByOrNull { abs(it.atNs - snap.timestampNs) }
                val vx = if (sn != null && snap.frameW > 0) sn.vx * snap.frameW / snap.width else b.vx
                val vy = if (sn != null && snap.frameW > 0) sn.vy * snap.frameH / snap.height else b.vy
                specs += Spec(o, byId[o], b.l, b.t, b.r, b.b, vx, vy)
            }
        }
        // cars the race counted just before / after that the tracker had no box for in this frame
        // (lost for a frame, or too dark to stand out): moved to this moment by their own speed
        if (snap.frameW > 0) for (o in others) {
            if (specs.any { it.racerId == o.racerId }) continue
            val dt = (snap.timestampNs - o.atNs) / 1e9f
            val l = ((o.box.l + o.vx * dt) * snap.frameW - snap.x0) / snap.width
            val r = ((o.box.r + o.vx * dt) * snap.frameW - snap.x0) / snap.width
            val t = ((o.box.t + o.vy * dt) * snap.frameH - snap.y0) / snap.height
            val b = ((o.box.b + o.vy * dt) * snap.frameH - snap.y0) / snap.height
            val cx = (l + r) / 2; val cy = (t + b) / 2
            if (cx < 0f || cx > 1f || cy < 0f || cy > 1f) continue
            specs += Spec(o.racerId, byId[o.racerId], l, t, r, b, o.vx * snap.frameW / snap.width, o.vy * snap.frameH / snap.height, fromRecord = true)
        }
        return fit(snap, specs, pool, exposureS).filterNotNull()
    }

    /** A counted crossing near the card's moment: who, its box (0..1 of the frame) and speed, and when. */
    class Seen(val racerId: String, val box: OverlayBox, val vx: Float, val vy: Float, val atNs: Long)

    /** [fit] on a crossing's [Snapshot] (needs its colour planes and foreground). */
    fun fit(snap: Snapshot, specs: List<Spec>, pool: List<Pair<String, CarTemplate>>, exposureS: Float): List<Fit?> {
        val yuv = snap.yuv ?: return specs.map { null }
        val fg = snap.fg ?: return specs.map { null }
        val n = snap.width * snap.height
        return fit(snap.width, snap.height, yuv.copyOfRange(0, n), yuv.copyOfRange(n, 2 * n), yuv.copyOfRange(2 * n, 3 * n), fg, snap.lumaRef, specs, pool, exposureS, line = snap.line)
    }
}

/**
 * Where to put the name tags on a card so that no tag covers a car (nor another tag): each tag is
 * tried at many places around its car – 16 directions, growing distances – and takes the cheapest
 * (covering a car or a tag, or leaving the canvas, is ruled out; a long leader line or one crossing
 * another car costs). Crowded cars are placed first. Coordinates are whatever the caller uses
 * (canvas px); the canvas should have a free margin round the picture so a tag always fits.
 */
object LabelLayout {
    /** Tag rectangle (x, y = top left) and the point on the car its leader line ends at. */
    class Tag(val x: Float, val y: Float, val w: Float, val h: Float, val ax: Float, val ay: Float)

    fun place(cars: List<FloatArray>, sizes: List<Pair<Float, Float>>, x0: Float, y0: Float, x1: Float, y1: Float, gap: Float = 4f): List<Tag> {
        fun inside(poly: FloatArray, x: Float, y: Float, grow: Float): Boolean {
            // point in convex polygon grown by [grow]: distance to every edge's inner side
            val n = poly.size / 2
            var cx = 0f; var cy = 0f
            for (k in 0 until n) { cx += poly[2 * k]; cy += poly[2 * k + 1] }
            cx /= n; cy /= n
            for (k in 0 until n) {
                val ax = poly[2 * k]; val ay = poly[2 * k + 1]; val bx = poly[(2 * k + 2) % (2 * n)]; val by = poly[(2 * k + 3) % (2 * n)]
                var nx = by - ay; var ny = -(bx - ax)
                val len = max(1e-3f, hypot(nx, ny)); nx /= len; ny /= len
                if ((cx - ax) * nx + (cy - ay) * ny > 0) { nx = -nx; ny = -ny }   // outward normal
                if ((x - ax) * nx + (y - ay) * ny > grow) return false
            }
            return true
        }
        fun rectHitsPoly(rx: Float, ry: Float, rw: Float, rh: Float, poly: FloatArray): Boolean {
            for (j in 0..4) for (i in 0..6) if (inside(poly, rx + rw * i / 6f, ry + rh * j / 4f, gap)) return true
            for (k in 0 until poly.size / 2) { val px = poly[2 * k]; val py = poly[2 * k + 1]; if (px in rx - gap..rx + rw + gap && py in ry - gap..ry + rh + gap) return true }
            return false
        }
        fun segHitsPoly(ax: Float, ay: Float, bx: Float, by: Float, poly: FloatArray): Boolean {
            for (k in 1..9) { val t = k / 10f; if (inside(poly, ax + (bx - ax) * t, ay + (by - ay) * t, 0f)) return true }
            return false
        }
        fun centre(p: FloatArray): Pair<Float, Float> { var x = 0f; var y = 0f; val n = p.size / 2; for (k in 0 until n) { x += p[2 * k]; y += p[2 * k + 1] }; return x / n to y / n }
        fun radius(p: FloatArray): Float { val (cx, cy) = centre(p); return (0 until p.size / 2).maxOf { hypot(p[2 * it] - cx, p[2 * it + 1] - cy) } }

        val tags = arrayOfNulls<Tag>(cars.size)
        val placed = ArrayList<Tag>()
        // most crowded car first (most other cars close by)
        val order = cars.indices.sortedByDescending { i -> val (cx, cy) = centre(cars[i]); cars.indices.count { j -> j != i && centre(cars[j]).let { (x, y) -> hypot(x - cx, y - cy) } < 3 * radius(cars[i]) } }
        for (i in order) {
            val (tw, th) = sizes[i]
            val (cx, cy) = centre(cars[i]); val r = radius(cars[i])
            var bestCost = Float.MAX_VALUE; var best: Tag? = null
            for (step in 0 until 8) {
                val dist = r + gap + step * (th * 0.9f)
                for (k in 0 until 16) {
                    val a = (k / 16f) * 2 * kotlin.math.PI.toFloat() - kotlin.math.PI.toFloat() / 2   // start straight up
                    val ex = cx + cos(a) * dist; val ey = cy + sin(a) * dist
                    // tag sits beyond the point, on the far side from the car
                    val rx = ex - tw / 2 + cos(a) * tw / 2; val ry = ey - th / 2 + sin(a) * th / 2
                    if (rx < x0 || ry < y0 || rx + tw > x1 || ry + th > y1) continue
                    if (cars.any { rectHitsPoly(rx, ry, tw, th, it) }) continue
                    if (placed.any { o -> rx < o.x + o.w + gap && rx + tw + gap > o.x && ry < o.y + o.h + gap && ry + th + gap > o.y }) continue
                    // leader from the tag's nearest edge point to the car's outline edge
                    val lx = (cx).coerceIn(rx, rx + tw); val ly = (cy).coerceIn(ry, ry + th)
                    val crosses = cars.indices.count { j -> j != i && segHitsPoly(lx, ly, cx, cy, cars[j]) }
                    val cost = dist + crosses * 400f + (if (abs(sin(a)) > 0.5f) 0f else 6f)   // above/below reads better
                    if (cost < bestCost) {
                        bestCost = cost
                        // anchor on the car's outline toward the tag
                        val ux = lx - cx; val uy = ly - cy; val ul = max(1e-3f, hypot(ux, uy))
                        var t = 0f
                        while (t < ul && inside(cars[i], cx + ux / ul * t, cy + uy / ul * t, 0f)) t += 1f
                        best = Tag(rx, ry, tw, th, cx + ux / ul * t, cy + uy / ul * t)
                    }
                }
                if (best != null && step >= 1) break   // nearest free ring wins
            }
            val tag = best ?: Tag(x0 + 2f, y0 + 2f + placed.size * (th + gap), tw, th, cx, cy)   // canvas full: stack in the corner
            tags[i] = tag; placed += tag
        }
        return tags.map { it!! }
    }
}
