package app.anylap.vision

import app.anylap.data.Direction
import app.anylap.data.FinishEdge
import app.anylap.data.LineSpec
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds moving things, follows them from frame to frame and reports every time one crosses the
 * finish line – with a sub-frame timestamp, speed and best guess of who it was.
 * Pure Kotlin: identical behaviour on Android and iOS. Not thread-safe; call [process] from one thread.
 */
class FrameAnalyzer(
    private val onCrossing: (CrossingEvent) -> Unit,
    private val onOverlay: (List<OverlayBox>) -> Unit = {},
) {
    @kotlin.concurrent.Volatile
    var config = AnalyzerConfig()

    private var w = 0
    private var h = 0
    private var model: BackgroundModel? = null
    private var blobFinder: BlobFinder? = null
    private var mask = ByteArray(0)
    /** Owner tag per pixel for blobs that were split this frame (0 = not split). */
    private var owner = IntArray(0)
    private var eroded = ByteArray(0)
    private var coreLabels = IntArray(0)
    /** Learned (along, area) of single racers this session. */
    private val sizeSamples = ArrayList<FloatArray>()
    private var tmpA = ByteArray(0)
    private var tmpB = ByteArray(0)
    private val markerDetector = MarkerDetector()
    private var exposure = Fingerprint.NORMAL
    private val lumaHist = IntArray(256)
    private var rawFg = ByteArray(0)
    private var lastMedian = Fingerprint.NORMAL_MEDIAN
    /** Recently emitted crossings (racer, time, centre x, y) – a fitted second car must not repeat one. */
    private val recentFits = ArrayList<FloatArray>()
    private val recentIds = ArrayList<String>()

    private val tracks = ArrayList<Track>()
    private var nextTrackId = 1
    private val pending = ArrayList<Pending>()
    private var frameCount = 0L
    @kotlin.concurrent.Volatile
    private var resetRequested = false
    @kotlin.concurrent.Volatile
    private var regionRequest: Pair<FloatArray, (FloatArray) -> Unit>? = null

    /** Forget the background, e.g. after the camera moved. Safe to call from any thread. */
    fun resetBackground() {
        resetRequested = true
    }

    /**
     * On the next frame, report the colour fingerprint of everything inside l,t,r,b (0..1), with
     * the colours of the ring around the box (ground) subtracted – for calibration photos.
     */
    fun requestRegionFingerprint(normRect: FloatArray, callback: (FloatArray) -> Unit) {
        regionRequest = normRect to callback
    }

    fun process(f: AnalysisFrame) {
        val cfg = config
        ensureSize(f.width, f.height)
        val m = model ?: return
        if (resetRequested) {
            resetRequested = false
            m.reset(); tracks.clear(); pending.clear(); claims.clear()
        }
        regionRequest?.let { (rect, cb) ->
            regionRequest = null
            cb(regionFingerprint(f, rect))
        }

        val frac = m.apply(f.y, f.u, f.v, mask)
        if (rawFg.size != mask.size) rawFg = ByteArray(mask.size)
        mask.copyInto(rawFg)   // foreground before the clean-up: touching racers are not glued together here
        frameCount++
        if (frameCount % EXPOSURE_EVERY == 1L) { lastMedian = medianLuma(f); exposure = Fingerprint.Exposure(lastMedian) }
        if (!m.warmedUp) return
        if (frac > 0.45f) {
            // Lighting jumped or the camera moved: start over rather than invent crossings.
            m.reset(); tracks.clear(); pending.clear()
            return
        }
        // Clean up: remove speckles, then join the parts of a racer (legs, wheels…) into one blob.
        Morph.erode(mask, tmpA, tmpB, w, h)
        tmpA.copyInto(eroded)   // racer cores before the dilations that join parts – and neighbours
        Morph.dilate(tmpA, mask, tmpB, w, h)
        Morph.dilate(mask, tmpA, tmpB, w, h)
        Morph.dilate(tmpA, mask, tmpB, w, h)
        Morph.dilate(mask, tmpA, tmpB, w, h)
        tmpA.copyInto(mask)

        val minArea = max(12, (cfg.sport.minBlobAreaFrac * w * h).toInt())
        val line = lineGeom(cfg.line)
        val blobs = splitMerged(blobFinder!!.find(mask, minArea), f, line, cfg)
        updateTracks(blobs, f.timestampNs)

        // A lot of the frame "moving" at once is a lighting transient (auto-exposure, a cloud, a
        // door opening): keep following things, but don't count crossings until it settles.
        val unstable = frac > UNSTABLE_FRAC
        for (t in tracks) {
            if (t.missed > 0) continue
            accumulateFingerprint(t, f)
            checkCrossing(t, line, cfg, f, unstable)
            checkRescue(t, line, cfg, f, unstable)
            if (cfg.useMarkers && abs(t.side) < 0.45f * w && frameCount % 2 == 0L) readMarker(t, f)
        }
        finalizePending(f.timestampNs, cfg, force = false)

        if (frameCount % 3 == 0L) {
            onOverlay(tracks.filter { it.missed == 0 }.map { t ->
                OverlayBox(
                    t.bx.toFloat() / w, t.by.toFloat() / h,
                    (t.bx + t.bw).toFloat() / w, (t.by + t.bh).toFloat() / h,
                    abs(t.side) < 0.1f * w,
                )
            })
        }
    }

    /** Median luma of the frame (every 4th pixel), the fingerprint's exposure reference. */
    private fun medianLuma(f: AnalysisFrame): Float {
        lumaHist.fill(0)
        var n = 0
        var i = 0
        val size = f.width * f.height
        while (i < size) { lumaHist[f.y[i].toInt() and 0xFF]++; n++; i += 4 }
        var acc = 0
        for (v in 0 until 256) { acc += lumaHist[v]; if (acc * 2 >= n) return v.toFloat() }
        return Fingerprint.NORMAL_MEDIAN
    }

    /** Emits crossings that are still waiting for more evidence (call when a session ends). */
    fun flush(nowNs: Long) = finalizePending(nowNs, config, force = true)

    private fun ensureSize(fw: Int, fh: Int) {
        if (fw == w && fh == h && model != null) return
        w = fw; h = fh
        model = BackgroundModel(w, h)
        blobFinder = BlobFinder(w, h)
        mask = ByteArray(w * h)
        owner = IntArray(w * h)
        eroded = ByteArray(w * h)
        coreLabels = IntArray(w * h)
        tmpA = ByteArray(w * h)
        tmpB = ByteArray(w * h)
        tracks.clear(); pending.clear()
    }

    private fun regionFingerprint(f: AnalysisFrame, r: FloatArray): FloatArray {
        val x0 = (r[0] * f.width).toInt().coerceIn(0, f.width - 1)
        val x1 = (r[2] * f.width).toInt().coerceIn(x0 + 1, f.width)
        val y0 = (r[1] * f.height).toInt().coerceIn(0, f.height - 1)
        val y1 = (r[3] * f.height).toInt().coerceIn(y0 + 1, f.height)
        // Ring: the box grown by 30 % on each side, minus the box itself.
        val padX = ((x1 - x0) * 0.3f).toInt()
        val padY = ((y1 - y0) * 0.3f).toInt()
        val rx0 = (x0 - padX).coerceAtLeast(0)
        val rx1 = (x1 + padX).coerceAtMost(f.width)
        val ry0 = (y0 - padY).coerceAtLeast(0)
        val ry1 = (y1 + padY).coerceAtMost(f.height)
        val box = Fingerprint.accumulator()
        val ring = Fingerprint.accumulator()
        var ringArea = 0
        for (y in ry0 until ry1) for (x in rx0 until rx1) {
            val i = y * f.width + x
            val inside = x >= x0 && x < x1 && y >= y0 && y < y1
            val acc = if (inside) box else ring.also { ringArea++ }
            Fingerprint.add(acc, f.y[i].toInt() and 0xFF, f.u[i].toInt() and 0xFF, f.v[i].toInt() and 0xFF, exposure)
        }
        return Fingerprint.subtractRing(box, (x1 - x0) * (y1 - y0), ring, ringArea)
    }

    // ------------------------------------------------------------------ tracks

    /** Position (centre) and box size of a track in one frame. */
    private class TrackPoint(val ts: Long, val x: Float, val y: Float, val bw: Int = 0, val bh: Int = 0)

    private inner class Track(val id: Int) {
        val pts = ArrayList<TrackPoint>()
        var bx = 0; var by = 0; var bw = 0; var bh = 0
        var missed = 0
        var lastTs = 0L
        var side = Float.NaN
        var lastCrossTs = Long.MIN_VALUE / 2
        val hist = Fingerprint.accumulator()
        var histFrames = 0
        val markerVotes = HashMap<Int, Int>()
        /** Split tag of the blob this track got this frame (0 = a whole blob). */
        var tag = 0
        /** Side of the check lines (finish line shifted ±CHECK_OFFSET) last frame. */
        var checkSide = Float.NaN
        var area = 0

        fun predict(ts: Long): Pair<Float, Float> {
            val n = pts.size
            if (n < 2) return pts.last().x to pts.last().y
            val a = pts[n - 2]
            val b = pts[n - 1]
            val dt = max(1L, b.ts - a.ts)
            val k = (ts - b.ts).toFloat() / dt
            return (b.x + (b.x - a.x) * k) to (b.y + (b.y - a.y) * k)
        }

        fun add(b: Blob, ts: Long) {
            pts += TrackPoint(ts, b.cx, b.cy, b.w, b.h)
            if (pts.size > 60) pts.removeAt(0)
            bx = b.x; by = b.y; bw = b.w; bh = b.h
            tag = b.tag; area = b.area
            missed = 0
            lastTs = ts
        }
    }

    private class Pairing(val track: Any, val blob: Blob, val d: Float)

    private fun updateTracks(blobs: List<Blob>, ts: Long) {
        val pairs = ArrayList<Pairing>()
        for (t in tracks) {
            // Search radius per track: 6 frame-widths/s of travel since it was last seen, but never more
            // than 0.3 w. A single radius grown by the *longest* missed track let a car entering on the
            // left adopt the track (and fingerprint) of the car that just left on the right.
            val dtSec = ((ts - t.lastTs) / 1e9).coerceIn(0.0, 0.5)
            val maxJump = (6.0 * w * dtSec).coerceIn(0.12 * w, 0.3 * w).toFloat()
            val (px, py) = t.predict(ts)
            for (b in blobs) {
                val d = hypot(px - b.cx, py - b.cy)
                if (d < maxJump) pairs += Pairing(t, b, d)
            }
        }
        pairs.sortBy { it.d }
        val usedT = HashSet<Int>()
        val usedB = HashSet<Blob>()
        for (p in pairs) {
            val t = p.track as Track
            if (t.id in usedT || p.blob in usedB) continue
            usedT += t.id
            usedB += p.blob
            t.add(p.blob, ts)
        }
        for (t in tracks) if (t.id !in usedT) t.missed++
        for (b in blobs) if (b !in usedB) {
            val t = Track(nextTrackId++)
            // A racer split out of a blob it shared with another track keeps that track's motion
            // history (shifted to its own position): otherwise a car that was hidden in a merged blob
            // until near the line has no "really moved" history and its crossing would be lost.
            val src = if (b.source >= 0) tracks.firstOrNull { it.id == b.source } else null
            if (src != null && src.pts.size >= 2) {
                val last = src.pts.last()
                val dx = b.cx - last.x; val dy = b.cy - last.y
                for (p in src.pts.dropLast(1)) t.pts += TrackPoint(p.ts, p.x + dx, p.y + dy)
            }
            t.add(b, ts)
            tracks += t
        }
        tracks.removeAll { it.missed > 0 && ts - it.lastTs > 300_000_000L }
    }

    /**
     * Adds the colours under the blob to the track's fingerprint. The mask was dilated to join the
     * parts of a racer, so its rim is mostly ground: only pixels with mask [CORE] px around them
     * count (a thin blob falls back to the whole mask), which keeps the fingerprint about the racer.
     */
    private fun accumulateFingerprint(t: Track, f: AnalysisFrame) {
        val x0 = t.bx.coerceIn(0, w - 1)
        val y0 = t.by.coerceIn(0, h - 1)
        val x1 = (t.bx + t.bw).coerceIn(x0 + 1, w)
        val y1 = (t.by + t.bh).coerceIn(y0 + 1, h)
        // Motion blur: each end of a moving blob is racer and ground mixed over one blur length
        // (speed × exposure). At 40 km/h and 1/120 s that is 9 cm of a 16 cm car – most of it – and the
        // mix drags every car's colours towards the asphalt. Read only the band along the direction of
        // travel that the blur has not reached (at least the middle 30 %).
        bandUx = 0f; bandUy = 0f; bandKeep = 0f
        val n = t.pts.size
        if (n >= 2) {
            val a = t.pts[n - 2]; val b = t.pts[n - 1]
            val dt = (b.ts - a.ts) / 1e9f
            if (dt > 0f) {
                val vx = (b.x - a.x) / dt; val vy = (b.y - a.y) / dt
                val sp = hypot(vx, vy)
                val blur = sp * config.exposureS
                if (blur > 1f) {
                    bandUx = vx / sp; bandUy = vy / sp
                    val half = (abs(bandUx) * t.bw + abs(bandUy) * t.bh) / 2f
                    bandKeep = max(0.3f * half, half - blur)
                    bandCx = t.bx + t.bw / 2f; bandCy = t.by + t.bh / 2f
                }
            }
        }
        val added = sampleFingerprint(t, f, x0, y0, x1, y1, core = true)
        if (added == 0) sampleFingerprint(t, f, x0, y0, x1, y1, core = false)
        t.histFrames++
    }



    private var bandUx = 0f
    private var bandUy = 0f
    private var bandKeep = 0f
    private var bandCx = 0f
    private var bandCy = 0f

    private fun sampleFingerprint(t: Track, f: AnalysisFrame, x0: Int, y0: Int, x1: Int, y1: Int, core: Boolean): Int {
        var added = 0
        val cw = CORE * w
        // Small blobs (an RC car) are read pixel by pixel so a 1 px stripe isn't skipped; big ones every other pixel.
        val step = if (t.bw * t.bh < 6000) 1 else 2
        var y = y0
        while (y < y1) {
            var x = x0
            val row = y * w
            while (x < x1) {
                val i = row + x
                if (mask[i].toInt() != 0 && (t.tag == 0 || owner[i] == t.tag) &&
                    (bandKeep <= 0f || abs((x - bandCx) * bandUx + (y - bandCy) * bandUy) <= bandKeep)
                ) {
                    val ok = !core || (
                        x >= CORE && x + CORE < w && y >= CORE && y + CORE < h &&
                            mask[i - CORE].toInt() != 0 && mask[i + CORE].toInt() != 0 &&
                            mask[i - cw].toInt() != 0 && mask[i + cw].toInt() != 0 &&
                            (t.tag == 0 || (owner[i - CORE] == t.tag && owner[i + CORE] == t.tag && owner[i - cw] == t.tag && owner[i + cw] == t.tag))
                        )
                    if (ok) {
                        Fingerprint.add(t.hist, f.y[i].toInt() and 0xFF, f.u[i].toInt() and 0xFF, f.v[i].toInt() and 0xFF, exposure)
                        added++
                    }
                }
                x += step
            }
            y += step
        }
        return added
    }

    // ------------------------------------------------------------------ merged racers

    /**
     * Expected blob area of one racer at [along] on the line: sqrt(area) fitted linearly over the
     * calibration hints and this session's samples (perspective makes size roughly linear in
     * position across the track). Null until there is something to go on.
     */
    private fun expectedArea(along: Float, cfg: AnalyzerConfig): Float? {
        // This session's own sizes win once there are a few: hints from an older calibration may be
        // from a camera that stood closer or further away.
        val pts = if (sizeSamples.size >= 3) sizeSamples else cfg.sizeHints + sizeSamples
        if (pts.size < 2) return null
        val n = pts.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (p in pts) { val x = p[0].toDouble(); val y = sqrt(p[1].toDouble()); sx += x; sy += y; sxx += x * x; sxy += x * y }
        val den = n * sxx - sx * sx
        val size = if (den / (n.toDouble() * n) < 0.01) {
            // all samples from one place: use their median
            pts.map { sqrt(it[1]) }.sorted()[n / 2].toDouble()
        } else {
            val b = (n * sxy - sx * sy) / den
            val a = (sy - b * sx) / n
            a + b * along.coerceIn(0f, 1f)
        }
        return if (size > 1) (size * size).toFloat() else null
    }

    /**
     * Splits blobs that hold more than one racer. Two cues: (1) two or more live tracks are predicted
     * inside one blob – they ran into each other (overtake, side by side); (2) the blob is much bigger
     * than one racer at that point of the line – they arrived merged (nose to tail). Pixels are shared
     * out by k-means on position, seeded with the predictions (cue 1) or along the blob's long axis
     * (cue 2). Each part's pixels are tagged in [owner] so its fingerprint only reads its own racer.
     */
    private fun splitMerged(blobs0: List<Blob>, f: AnalysisFrame, line: LineGeom, cfg: AnalyzerConfig): List<Blob> {
        val ts = f.timestampNs
        owner.fill(0)
        val blobs = joinFragments(blobs0, line, cfg)
        var nextTag = 1
        val out = ArrayList<Blob>(blobs.size + 2)
        // Only established, racer-sized tracks can claim a share of a blob: a shadow fragment or a
        // speck that briefly had its own track must not split the car it rejoins.
        val live = tracks.filter { it.missed <= 1 && it.pts.size >= 5 }
        for (b in blobs) {
            val pad = 2f
            val exp = expectedArea(line.along(b.cx, b.cy), cfg)
            val minShare = if (exp != null) 0.45f * exp else 0.3f * b.area
            val inside = live.filter { t ->
                val (px, py) = t.predict(ts)
                t.area >= minShare &&
                    px >= b.x - pad && px <= b.x + b.w + pad && py >= b.y - pad && py <= b.y + b.h + pad
            }.let { cand ->
                // two tracks predicted on top of each other are one racer seen twice, not two racers
                val minSep = if (exp != null) 0.35f * sqrt(exp) else 4f
                val keep = ArrayList<Track>()
                for (t in cand.sortedByDescending { it.area }) {
                    val (ax, ay) = t.predict(ts)
                    if (keep.none { k -> val (bx, by) = k.predict(ts); hypot(ax - bx, ay - by) < minSep }) keep += t
                }
                keep
            }
            var seeds: List<Pair<Float, Float>>? = null
            var source = -1
            splitColour = 0f
            // Cue 0 – gaps: the dilations that join a racer's parts also join racers a centimetre
            // apart. If the un-dilated mask has two or more racer-sized cores inside this blob, those
            // are the racers; no size guess needed. (Top-down and steep views; overlapping racers
            // from a low camera have no gap and fall through to the cues below.)
            val cores = if (exp != null && b.area > 1.2f * exp) (coreSeedsFull(b, exp, f) ?: coreSeeds(b, exp)) else null
            if (inside.size >= 2) {
                seeds = inside.take(6).map { it.predict(ts) }
            } else {
                if (DEBUG_SPLIT && exp != null && b.area > 1.2f * exp) println("big blob area ${b.area} exp ${exp.toInt()} along ${line.along(b.cx, b.cy)} ts ${ts / 1e9}")
                if (exp != null && b.area > SPLIT_FACTOR * exp) {
                    val k0 = (b.area / exp).roundToInt().coerceIn(2, 6)
                    source = inside.singleOrNull()?.id ?: -1
                    if (cfg.racers.any { it.fingerprint != null }) {
                        // Which way to cut, and into how many: a cut is valid when every part looks
                        // like a known racer and all of them like *different* racers. Tried in order of
                        // preference – k0 parts by position; k0 parts by position + colour; one more
                        // part (a racer half-hidden in shadow makes the blob look smaller than it is).
                        // The first rung with a valid cut wins (best score within the rung); no valid
                        // cut = one big-looking racer, leave it whole.
                        // Among valid cuts the one with the most parts wins (then the best score):
                        // every part must also be the size of one racer (0.55–1.5×), which is what
                        // stops a low camera's overlapping pair from being cut into three.
                        var bestK = 0; var bestScore = -1f
                        // candidate seedings: k0 and k0+1 equal parts along either axis, and the
                        // un-dilated cores (racers a gap apart, seen from above)
                        val candidates = ArrayList<Pair<List<Pair<Float, Float>>, Float>>()
                        for ((k, cw) in listOf(k0 to 0f, k0 to COLOUR_WEIGHT, k0 + 1 to 0f, k0 + 1 to COLOUR_WEIGHT)) {
                            if (k > 6 || (cw > 0f && COLOUR_WEIGHT <= 0f)) continue
                            for (perp in listOf(false, true)) candidates += axisSeeds(b, k, perpendicular = perp) to cw
                        }
                        if (cores != null && cores.size >= 2) candidates += cores to 0f
                        for ((sd, cw) in candidates) {
                            val k = sd.size
                            run {
                                val parts = kmeans(b, sd, f, cw) ?: return@run
                                // the extra part is only believed when every part is the size of one racer
                                if (k > k0 && parts.any { it.area < PART_MIN * exp || it.area > PART_MAX * exp }) return@run
                                val sc = partsScore(b, f, cfg)
                                if (sc.second.any { it == null } || sc.second.distinct().size < sc.second.size) return@run
                                if (k > bestK || (k == bestK && sc.first > bestScore)) { bestK = k; bestScore = sc.first; seeds = sd; splitColour = cw }
                            }
                        }
                        if (bestK == 0) seeds = null
                    } else {
                        seeds = axisSeeds(b, k0, perpendicular = false)
                    }
                }
            }
            if (seeds == null) { out += b; continue }
            val parts = kmeans(b, seeds, f, splitColour)
            if (parts == null) { out += b; continue }
            for ((tag0, part) in parts.withIndex()) {
                val tag = nextTag + tag0
                part.tag = tag
                part.source = source
            }
            // write the owner map
            val bf = blobFinder!!
            val ids = assignment
            var k = 0
            for (yy in b.y until b.y + b.h) for (xx in b.x until b.x + b.w) {
                val i = yy * w + xx
                if (bf.labelAt(i) == b.label && mask[i].toInt() != 0) { owner[i] = nextTag + ids[k]; k++ }
            }
            nextTag += parts.size
            out += parts
        }
        return out
    }

    /**
     * The reverse of a split: a racer broken into pieces (dark windows the colour of the asphalt, a
     * stripe) gives two blobs close together that add up to about one racer. Joined, they are timed
     * once. Needs the size model, so it only acts once one racer's size is known.
     */
    private fun joinFragments(blobs: List<Blob>, line: LineGeom, cfg: AnalyzerConfig): List<Blob> {
        if (blobs.size < 2) return blobs
        val list = blobs.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                val exp = expectedArea(line.along((a.cx * a.area + b.cx * b.area) / (a.area + b.area), (a.cy * a.area + b.cy * b.area) / (a.area + b.area)), cfg) ?: continue
                val gapX = max(0, max(a.x, b.x) - min(a.x + a.w, b.x + b.w))
                val gapY = max(0, max(a.y, b.y) - min(a.y + a.h, b.y + b.h))
                val small = min(a.area, b.area); val big = max(a.area, b.area)
                // two halves of one racer (dark windows the colour of the ground) …
                val halves = big <= 0.85f * exp && a.area + b.area <= 1.3f * exp && max(gapX, gapY) <= 0.5f * sqrt(exp)
                // … or a body plus a thin piece of itself the mask lost the link to (an open-wheeler's front
                // wing ahead of a nose cone too narrow to show): the piece alone is far too small to be a racer
                val appendix = small < APPENDIX_MAX * exp && big <= 1.15f * exp && a.area + b.area <= 1.45f * exp && max(gapX, gapY) <= 0.35f * sqrt(exp)
                if (!halves && !appendix) continue
                val x0 = min(a.x, b.x); val y0 = min(a.y, b.y)
                val x1 = max(a.x + a.w, b.x + b.w); val y1 = max(a.y + a.h, b.y + b.h)
                val n = a.area + b.area
                // label −1: a joined blob is never split again (its pixels span two labels)
                list[i] = Blob(x0, y0, x1 - x0, y1 - y0, n, (a.cx * a.area + b.cx * b.area) / n, (a.cy * a.area + b.cy * b.area) / n, -1)
                list.removeAt(j)
                changed = true
                break@loop
            }
        }
        return list
    }

    /**
     * Centroids of the racer-sized connected cores (un-dilated mask) inside blob [b], largest first,
     * at most 6; null if fewer than two.
     */
    private var fullStrength = FloatArray(0)
    private var fullLabels = IntArray(0)
    private var fullStack = IntArray(0)

    /**
     * [coreSeeds] on the camera's full-resolution brightness (the ≤1280-wide stream the bib reader
     * also uses): a 1 cm gap between racers is 2 px in the 480 px analysis image – one anti-aliased
     * blend of car and ground that still reads as "changed" – but 5 px at 1280, where the valley
     * test finds it. Strength = |brightness − background mean| (the background model's mean at the
     * analysis pixel underneath). Null when the camera gives no full-resolution frame.
     */
    private fun coreSeedsFull(b: Blob, exp: Float, f: AnalysisFrame): List<Pair<Float, Float>>? {
        if (f.fullWidth <= w) return null
        val m = model ?: return null
        val bf = blobFinder!!
        val sx = f.fullWidth.toFloat() / w; val sy = f.fullHeight.toFloat() / h
        val X0 = (b.x * sx).toInt().coerceIn(0, f.fullWidth - 2); val Y0 = (b.y * sy).toInt().coerceIn(0, f.fullHeight - 2)
        val X1 = ((b.x + b.w) * sx).toInt().coerceIn(X0 + 2, f.fullWidth); val Y1 = ((b.y + b.h) * sy).toInt().coerceIn(Y0 + 2, f.fullHeight)
        val cw = X1 - X0; val ch = Y1 - Y0
        val luma = f.lumaCrop(X0, Y0, cw, ch) ?: return null
        val n = cw * ch
        if (fullStrength.size < n) { fullStrength = FloatArray(n); fullLabels = IntArray(n); fullStack = IntArray(n) }
        // strength vs the background underneath; only inside this blob's (dilated) mask
        for (yy in 0 until ch) for (xx in 0 until cw) {
            val ax = ((X0 + xx) / sx).toInt().coerceIn(0, w - 1); val ay = ((Y0 + yy) / sy).toInt().coerceIn(0, h - 1)
            val ai = ay * w + ax
            val j = yy * cw + xx
            fullLabels[j] = 0
            fullStrength[j] = if (mask[ai].toInt() != 0 && bf.labelAt(ai) == b.label) abs((luma[j].toInt() and 0xFF) - m.meanAt(ai)) else 0f
        }
        val d = (2 * sx).roundToInt().coerceAtLeast(2)   // gap test distance ≈ 2 analysis px
        val minS = (m.minDiff * 0.8f)
        // The gap between two racers seen from above is a dark slot (you look down between two
        // bodies), darker than the ground and than the racers on either side – a dark line in the
        // brightness, two pixels wide at 480 px, four or five at 1280.
        fun lumaAt(j: Int) = (luma[j].toInt() and 0xFF).toFloat()
        fun core(j: Int): Boolean {
            if (fullStrength[j] < minS) return false
            val x = j % cw; val y = j / cw
            val l = lumaAt(j)
            val ax = ((X0 + x) / sx).toInt().coerceIn(0, w - 1); val ay = ((Y0 + y) / sy).toInt().coerceIn(0, h - 1)
            if (l >= m.meanAt(ay * w + ax) - GAP_BELOW_GROUND) return true   // not darker than the ground: not a slot
            val v = l * GAP_RATIO
            if (x >= d && x + d < cw && v < lumaAt(j - d) && v < lumaAt(j + d)) return false
            if (y >= d && y + d < ch && v < lumaAt(j - d * cw) && v < lumaAt(j + d * cw)) return false
            return true
        }
        val found = ArrayList<Triple<Int, Float, Float>>()
        var next = 1
        val scale2 = sx * sy
        for (start in 0 until n) {
            if (fullLabels[start] != 0 || !core(start)) continue
            var sp = 0; fullStack[sp++] = start; fullLabels[start] = next
            var cnt = 0; var ax = 0L; var ay = 0L
            while (sp > 0) {
                val p = fullStack[--sp]
                val x = p % cw; val y = p / cw
                cnt++; ax += x; ay += y
                for (dy in -1..1) { val ny = y + dy; if (ny < 0 || ny >= ch) continue
                    for (dx in -1..1) { val nx = x + dx; if (nx < 0 || nx >= cw) continue
                        val q = ny * cw + nx
                        if (fullLabels[q] == 0 && core(q)) { fullLabels[q] = next; fullStack[sp++] = q }
                    }
                }
            }
            next++
            if (cnt >= CORE_MIN_FRAC * exp * scale2) found += Triple(cnt, (X0 + ax.toFloat() / cnt) / sx, (Y0 + ay.toFloat() / cnt) / sy)
        }
        if (found.size < 2) return null
        return found.sortedByDescending { it.first }.take(6).map { it.second to it.third }
    }

    private val strengthHist = IntArray(256)

    private fun coreSeeds(b: Blob, exp: Float): List<Pair<Float, Float>>? {
        val bf = blobFinder!!
        val m = model ?: return null
        val x0 = b.x; val y0 = b.y; val x1 = b.x + b.w; val y1 = b.y + b.h
        // Strength threshold for this blob: pixels of a racer differ from the background by much
        // more than the blended pixels of a gap a centimetre wide. Core = above half the blob's
        // 80th-percentile strength (and in the un-dilated mask).
        strengthHist.fill(0)
        var cnt = 0
        for (yy in y0 until y1) for (xx in x0 until x1) {
            val i = yy * w + xx
            coreLabels[i] = 0
            if (eroded[i].toInt() != 0 && bf.labelAt(i) == b.label) { strengthHist[min(255, m.strength[i].toInt())]++; cnt++ }
        }
        if (cnt < 8) return null
        // A gap pixel (a blend of racer and ground) is weaker than the racer pixels on both sides of
        // it: a valley in the strength map along x or y, two pixels out – independent of how bright
        // each racer is.
        fun valley(i: Int): Boolean {
            val st = m.strength
            val x = i % w; val y = i / w
            val v = st[i] * VALLEY_RATIO
            if (x >= 2 && x + 2 < w && v < st[i - 2] && v < st[i + 2]) return true
            if (y >= 2 && y + 2 < h && v < st[i - 2 * w] && v < st[i + 2 * w]) return true
            return false
        }
        val thr = 0f
        val found = ArrayList<Triple<Int, Float, Float>>() // area, cx, cy
        var next = 1
        if (px.size < b.w * b.h) { px = IntArray(b.w * b.h); py = IntArray(b.w * b.h); assignment = IntArray(b.w * b.h); pcode = IntArray(b.w * b.h) }
        val stack = px
        for (sy in y0 until y1) for (sx in x0 until x1) {
            val start = sy * w + sx
            if (eroded[start].toInt() == 0 || coreLabels[start] != 0 || bf.labelAt(start) != b.label || valley(start)) continue
            var sp = 0; stack[sp++] = start; coreLabels[start] = next
            var n = 0; var ax = 0L; var ay = 0L
            while (sp > 0) {
                val p = stack[--sp]
                val cx = p % w; val cy = p / w
                n++; ax += cx; ay += cy
                for (dy in -1..1) { val ny = cy + dy; if (ny < y0 || ny >= y1) continue
                    for (dx in -1..1) { val nx = cx + dx; if (nx < x0 || nx >= x1) continue
                        val q = ny * w + nx
                        if (eroded[q].toInt() != 0 && coreLabels[q] == 0 && bf.labelAt(q) == b.label && !valley(q) && sp < stack.size) { coreLabels[q] = next; stack[sp++] = q }
                    }
                }
            }
            next++
            // a core is the racer minus its rim: ≥ 0.25 of a racer's dilated area is a racer, not a wheel
            if (n >= CORE_MIN_FRAC * exp) found += Triple(n, ax.toFloat() / n, ay.toFloat() / n)
        }
        if (found.size < 2) return null
        return found.sortedByDescending { it.first }.take(6).map { it.second to it.third }
    }

    private var assignment = IntArray(0)
    private var px = IntArray(0)
    private var py = IntArray(0)

    /** Seeds spread along the blob's principal axis (k equal racers in a row). */
    private fun axisSeeds(b: Blob, k: Int, perpendicular: Boolean): List<Pair<Float, Float>> {
        val bf = blobFinder!!
        var n = 0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (yy in b.y until b.y + b.h) for (xx in b.x until b.x + b.w) {
            val i = yy * w + xx
            if (bf.labelAt(i) != b.label || mask[i].toInt() == 0) continue
            val dx = xx - b.cx; val dy = yy - b.cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy; n++
        }
        if (n == 0) return listOf(b.cx to b.cy)
        sxx /= n; syy /= n; sxy /= n
        // largest eigenvector of [[sxx, sxy], [sxy, syy]]
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        val l1 = tr / 2 + sqrt(max(0.0, tr * tr / 4 - det))
        var ex = sxy; var ey = l1 - sxx
        if (abs(ex) + abs(ey) < 1e-9) { ex = if (sxx >= syy) 1.0 else 0.0; ey = if (sxx >= syy) 0.0 else 1.0 }
        val len = sqrt(ex * ex + ey * ey); ex /= len; ey /= len
        var sd = sqrt(l1)
        if (perpendicular) { val t = ex; ex = -ey; ey = t; sd = sqrt(max(1e-6, tr - l1)) }
        // centres of k equal racers in a row, in standard deviations of the whole row (sd = k·L/√12)
        val offs = (0 until k).map { (it - (k - 1) / 2.0) * sqrt(12.0) / k }
        return offs.map { o -> (b.cx + (ex * sd * o).toFloat()) to (b.cy + (ey * sd * o).toFloat()) }
    }

    private var pcode = IntArray(0)

    /**
     * Colour code of a pixel for splitting: 12 hue bins, bright grey, mid grey, dark – coarse
     * enough that one car is mostly one or two codes, so a cut between two touching cars of
     * different colours follows the colour edge instead of a straight line.
     */
    private fun colourCode(y: Int, u: Int, v: Int): Int {
        val du = u - 128; val dv = v - 128
        if (du * du + dv * dv >= 49) {
            val a = kotlin.math.atan2(dv.toFloat(), du.toFloat())
            return ((a + kotlin.math.PI.toFloat()) / (2 * kotlin.math.PI.toFloat()) * 12).toInt().coerceIn(0, 11)
        }
        val dark = exposure.darkFrom
        return when { y < dark * 0.5f -> 14; y < dark -> 13; else -> 12 }
    }

    /**
     * k-means over the blob's pixels on position *and* colour ([COLOUR_WEIGHT]): a pixel joins the
     * part it is close to whose colour make-up it fits. Seeds give the starting centres. Null if a
     * part ends up too small to be a racer.
     */
    private var splitColour = 0f

    private fun kmeans(b: Blob, seeds: List<Pair<Float, Float>>, f: AnalysisFrame, colourWeight: Float = 0f): List<Blob>? {
        val bf = blobFinder!!
        if (px.size < b.area) { px = IntArray(b.area); py = IntArray(b.area); assignment = IntArray(b.area); pcode = IntArray(b.area) }
        var n = 0
        for (yy in b.y until b.y + b.h) for (xx in b.x until b.x + b.w) {
            val i = yy * w + xx
            if (bf.labelAt(i) == b.label && mask[i].toInt() != 0 && n < px.size) {
                px[n] = xx; py[n] = yy
                pcode[n] = colourCode(f.y[i].toInt() and 0xFF, f.u[i].toInt() and 0xFF, f.v[i].toInt() and 0xFF)
                n++
            }
        }
        val k = seeds.size
        val cx = FloatArray(k) { seeds[it].first }
        val cy = FloatArray(k) { seeds[it].second }
        val cnt = IntArray(k)
        val hist = Array(k) { FloatArray(CODES) }
        val sigma2 = max(16f, (b.w.toFloat() * b.h / k) / 3f)
        repeat(5) { iter ->
            for (j in 0 until n) {
                var best = 0; var bd = Float.MAX_VALUE
                for (c in 0 until k) {
                    val dx = px[j] - cx[c]; val dy = py[j] - cy[c]
                    var d = (dx * dx + dy * dy) / sigma2
                    if (iter > 0 && colourWeight > 0f) d -= colourWeight * kotlin.math.ln(hist[c][pcode[j]])
                    if (d < bd) { bd = d; best = c }
                }
                assignment[j] = best
            }
            if (iter < 4) {
                val sx = FloatArray(k); val sy = FloatArray(k); cnt.fill(0)
                for (c in 0 until k) hist[c].fill(1f)
                for (j in 0 until n) { val c = assignment[j]; sx[c] += px[j]; sy[c] += py[j]; cnt[c]++; hist[c][pcode[j]] += 1f }
                for (c in 0 until k) {
                    if (cnt[c] > 0) { cx[c] = sx[c] / cnt[c]; cy[c] = sy[c] / cnt[c] }
                    val tot = cnt[c] + CODES
                    for (q in 0 until CODES) hist[c][q] /= tot
                }
            }
        }
        val minX = IntArray(k) { w }; val minY = IntArray(k) { h }; val maxX = IntArray(k); val maxY = IntArray(k)
        val sx = FloatArray(k); val sy = FloatArray(k); cnt.fill(0)
        for (j in 0 until n) {
            val c = assignment[j]; val x = px[j]; val y = py[j]
            cnt[c]++; sx[c] += x; sy[c] += y
            if (x < minX[c]) minX[c] = x; if (x > maxX[c]) maxX[c] = x
            if (y < minY[c]) minY[c] = y; if (y > maxY[c]) maxY[c] = y
        }
        if ((0 until k).any { cnt[it] < max(12, (0.2f * n / k).toInt()) }) return null
        return (0 until k).map { c ->
            Blob(minX[c], minY[c], maxX[c] - minX[c] + 1, maxY[c] - minY[c] + 1, cnt[c], sx[c] / cnt[c], sy[c] / cnt[c], 0)
        }
    }

    /**
     * How racer-like the parts of the last [kmeans] are: each part's colour fingerprint against its
     * best-matching racer, summed. Used to pick the direction of a size-based split.
     */
    private fun partsScore(b: Blob, f: AnalysisFrame, cfg: AnalyzerConfig): Pair<Float, List<String?>> {
        val bf = blobFinder!!
        val k = assignment.let { a -> var m = 0; for (j in 0 until min(a.size, b.area)) if (a[j] > m) m = a[j]; m + 1 }
        val acc = Array(k) { Fingerprint.accumulator() }
        var j = 0
        for (yy in b.y until b.y + b.h) for (xx in b.x until b.x + b.w) {
            val i = yy * w + xx
            if (bf.labelAt(i) != b.label || mask[i].toInt() == 0 || j >= assignment.size) continue
            Fingerprint.add(acc[assignment[j]], f.y[i].toInt() and 0xFF, f.u[i].toInt() and 0xFF, f.v[i].toInt() and 0xFF, exposure)
            j++
        }
        var total = 0f
        val best = ArrayList<String?>()
        for (a in acc) {
            val fp = Fingerprint.normalize(a)
            val top = cfg.racers.mapNotNull { r -> score(fp, r)?.let { r.id to it } }.maxByOrNull { it.second }
            total += top?.second ?: 0f
            // a part that doesn't clearly look like any racer (shadow, legs) makes the cut invalid
            best += top?.takeIf { it.second >= SPLIT_PART_MATCH }?.first
        }
        return total to best
    }

    // ------------------------------------------------------------------ crossings

    private class LineGeom(val ax: Float, val ay: Float, val bx: Float, val by: Float) {
        val dx = bx - ax
        val dy = by - ay
        val len = max(1f, sqrt(dx * dx + dy * dy))
        fun side(x: Float, y: Float) = (dx * (y - ay) - dy * (x - ax)) / len
        fun along(x: Float, y: Float) = ((x - ax) * dx + (y - ay) * dy) / (len * len)
    }

    private fun lineGeom(l: LineSpec) = LineGeom(l.x1 * w, l.y1 * h, l.x2 * w, l.y2 * h)

    private inner class Pending(val track: Track, var crossNs: Long, val direction: Int, val snapshot: Snapshot?, val along: Float, val area: Int, val split: Boolean, val rescue: Boolean = false, val centreNs: Long = crossNs) {
        val box = OverlayBox(track.bx.toFloat() / w, track.by.toFloat() / h, (track.bx + track.bw).toFloat() / w, (track.by + track.bh).toFloat() / h, true)
        val boxTs = track.lastTs
        val vel: Pair<Float, Float> = track.pts.let { ps ->
            if (ps.size < 2) 0f to 0f else {
                val a = ps[maxOf(0, ps.size - 3)]; val b = ps.last(); val dt = ((b.ts - a.ts) / 1e9f).coerceAtLeast(1e-3f)
                ((b.x - a.x) / w / dt) to ((b.y - a.y) / h / dt)
            }
        }
        /** The frame around the racer at the crossing, kept for the calibration-picture fit (null = none). */
        var crop: FitCrop? = null
        fun capture(f: AnalysisFrame, line: LineGeom, prevSide: Float, curSide: Float, a: TrackPoint, b: TrackPoint, cfg: AnalyzerConfig) {
            if (cfg.racers.none { it.template != null } && !cfg.captureTemplates) return
            crop = cropFor(track, f, line, prevSide, curSide, a, b)
        }
    }

    private fun checkCrossing(t: Track, line: LineGeom, cfg: AnalyzerConfig, frame: AnalysisFrame, unstable: Boolean) {
        val n = t.pts.size
        val cur = t.pts[n - 1]
        val s = line.side(cur.x, cur.y)
        val prevSide = t.side
        t.side = s
        if (n < 2 || prevSide.isNaN()) return
        if ((prevSide < 0f) == (s < 0f)) return
        if (unstable) return

        val prev = t.pts[n - 2]
        val f = abs(prevSide) / max(1e-3f, abs(prevSide) + abs(s))
        val ix = prev.x + (cur.x - prev.x) * f
        val iy = prev.y + (cur.y - prev.y) * f
        val along = line.along(ix, iy)
        if (along < -ALONG_MARGIN || along > 1f + ALONG_MARGIN) return // passed beside the line, not over it
        if (!plausibleSize(t, cfg)) return
        if (!reallyMoved(t, line, cur, s)) return

        val centreNs = prev.ts + ((cur.ts - prev.ts) * f).toLong()
        if (centreNs - t.lastCrossTs < 400_000_000L) return // wobbling on the line
        t.lastCrossTs = centreNs
        val crossNs = centreNs + edgeOffsetNs(t, line, cfg, abs(s - prevSide) / max(1L, cur.ts - prev.ts))

        val dir = if (prevSide < 0f) 1 else -1
        val wanted = when (cfg.direction) {
            Direction.BOTH -> true
            Direction.FORWARD -> dir == 1
            Direction.REVERSE -> dir == -1
        }
        if (wanted) pending += Pending(t, crossNs, dir, if (cfg.captureSnapshots) snapshot(t, frame) else null, along, t.area, t.tag != 0, centreNs = centreNs).also { it.capture(frame, line, prevSide, s, prev, cur, cfg) }
    }

    /**
     * Time from the racer's centre crossing the line to its front (negative) or rear (positive)
     * edge crossing it: half its length across the line over its speed across the line. Length =
     * median box extent across the line over the last frames, less the part of the motion blur the
     * mask sees ([BLUR_SEEN] of speed × exposure – the smear fades out at its ends) and the mask's
     * growth from the clean-up dilations. 0 for [FinishEdge.CENTRE].
     */
    private fun edgeOffsetNs(t: Track, line: LineGeom, cfg: AnalyzerConfig, vAcross: Float): Long {
        if (cfg.finishEdge == FinishEdge.CENTRE || vAcross <= 0f) return 0L
        val nx = abs(line.dy) / line.len
        val ny = abs(line.dx) / line.len
        val ext = t.pts.takeLast(EDGE_FRAMES).filter { it.bw > 0 }.map { it.bw * nx + it.bh * ny }.sorted()
        if (ext.isEmpty()) return 0L
        val blurPx = BLUR_SEEN * vAcross * cfg.exposureS * 1e9f
        val half = ((ext[ext.size / 2] - blurPx - 2 * MASK_GROW_PX) / 2f).coerceAtLeast(0f)
        val dt = (half / vAcross).toLong().coerceAtMost(MAX_EDGE_NS)
        return if (cfg.finishEdge == FinishEdge.FRONT) -dt else dt
    }

    /**
     * Second-line safety net against missed crossings: a check line parallel to the finish line,
     * [CHECK_OFFSET] of the frame beyond it on each side. A track that passes a check line with no
     * finish-line crossing in the last [RESCUE_WINDOW_NS] – it was merged with another racer there,
     * or gated out – is reported as a *rescue*: not timing, but a picture and an estimated time for
     * the review tray, so a missed car can be put back by hand.
     */
    private fun checkRescue(t: Track, line: LineGeom, cfg: AnalyzerConfig, frame: AnalysisFrame, unstable: Boolean) {
        val n = t.pts.size
        val cur = t.pts[n - 1]
        val d = CHECK_OFFSET * w
        val s = line.side(cur.x, cur.y)
        // signed distance beyond the nearer check line: positive outside, negative inside
        val beyond = abs(s) - d
        val prev = t.checkSide
        t.checkSide = beyond
        if (n < 2 || prev.isNaN() || unstable) return
        if (!(prev < 0f && beyond >= 0f)) return             // must go from inside to outside
        if (t.lastCrossTs > cur.ts - RESCUE_WINDOW_NS) return  // counted at the finish: fine
        if (!plausibleSize(t, cfg)) return
        val p = t.pts[n - 2]
        val along = line.along(cur.x, cur.y)
        if (along < -ALONG_MARGIN || along > 1f + ALONG_MARGIN) return
        // did it come from the other side of the finish line within the window? (otherwise it
        // appeared out here – a car entering the frame, not a missed crossing)
        val from = t.pts.firstOrNull { cur.ts - it.ts <= RESCUE_WINDOW_NS } ?: return
        val sFrom = line.side(from.x, from.y)
        if ((sFrom < 0f) == (s < 0f) && abs(sFrom) > 0.3f * d) return
        // estimated finish time from the speed across the line
        val dt = (cur.ts - p.ts).coerceAtLeast(1L)
        val vAcross = abs(s - line.side(p.x, p.y)) / dt   // px per ns
        if (vAcross <= 0f) return
        val finishNs = cur.ts - (abs(s) / vAcross).toLong()
        t.lastCrossTs = finishNs // one rescue per pass
        pending += Pending(t, finishNs, if (s > 0f) 1 else -1, if (cfg.captureSnapshots) snapshot(t, frame) else null, along, t.area, t.tag != 0, rescue = true)
    }

    /**
     * One racer is never most of the frame. Huge blobs are a lighting change, the camera moving or
     * someone walking up to it – their centroid wobbling over the line is not a crossing.
     */
    private fun plausibleSize(t: Track, cfg: AnalyzerConfig): Boolean {
        val area = t.bw.toLong() * t.bh
        if (area > cfg.sport.maxBlobAreaFrac * w * h) return false
        return t.bw <= 0.7f * w && t.bh <= 0.95f * h
    }

    /**
     * The track must have travelled a real distance in the last [MOVE_WINDOW_NS], mostly across
     * the line. A blob that was just born on the line, or that only flickers in place (a shadow,
     * a reflection, something static popping in and out of the background), doesn't count.
     */
    private fun reallyMoved(t: Track, line: LineGeom, cur: TrackPoint, curSide: Float): Boolean {
        val n = t.pts.size
        if (n < 3) return false
        val oldest = t.pts.first { cur.ts - it.ts <= MOVE_WINDOW_NS }
        val travel = hypot(cur.x - oldest.x, cur.y - oldest.y)
        if (travel < max(MIN_TRAVEL_PX.toFloat(), MIN_TRAVEL_FRAC * w)) return false
        val across = abs(curSide - line.side(oldest.x, oldest.y))
        // Within 45° of straight across. Racers cross a finish line close to square; a shadow edge or
        // a sliding reflection that drifts *along* the line (≥ 60° in Blender tests) does not count.
        return across >= ACROSS_FRAC * travel
    }

    private fun finalizePending(nowNs: Long, cfg: AnalyzerConfig, force: Boolean) {
        if (pending.isEmpty()) return
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            val gone = tracks.none { it === p.track }
            // Wait a moment after the crossing so we can see more of the racer (colour, bib).
            if (!force && !gone && nowNs - p.crossNs < 280_000_000L) continue
            iter.remove()
            emit(p, cfg)
        }
    }

    /** Racer → time of the last crossing it was named at (for "can't cross twice within a lap"). */
    private val claims = HashMap<String, Long>()

    /**
     * Names a crossing by appearance, deciding it together with the crossings still waiting to be
     * emitted, and leaving out racers named within the sport's minimum lap time (a car can't cross
     * twice in 2.5 s). Cars that cross at nearly the same moment are often ambiguous one by one but
     * not as a set: grey-vs-black for one and black-vs-grey for the other is one clear answer.
     * Returns (racer or null, margin): margin = best joint total − best joint total with this
     * crossing given any other answer (another racer, or none); the old one-by-one rule is the
     * special case of a single crossing.
     */
    private fun jointAppearance(p: Pending, fp: FloatArray, cfg: AnalyzerConfig): Pair<String?, Float> {
        val minLapNs = (cfg.sport.defaultMinLapSec * 1e9).toLong()
        val free = cfg.racers.filter { r -> claims[r.id]?.let { abs(p.crossNs - it) >= minLapNs } ?: true }
        if (free.isEmpty()) return null to 0f
        val others = pending.filter { it !== p && abs(it.crossNs - p.crossNs) < minLapNs && it.track.histFrames > 0 }
            .sortedBy { abs(it.crossNs - p.crossNs) }.take(5)
        val rows = ArrayList<FloatArray>(1 + others.size)
        rows += fp
        for (o in others) rows += Fingerprint.normalize(o.track.hist)
        val n = rows.size
        // columns: free racers, then one "none" column per row (score 0)
        val m = free.size + n
        val sc = Array(n) { r -> FloatArray(m) { c -> if (c < free.size) (score(rows[r], free[c]) ?: 0f) else 0f } }
        val (total, assign) = maxAssignment(sc)
        val col = assign[0]
        if (col >= free.size) return null to 0f
        val own = sc[0][col]
        val saved = sc[0][col]
        sc[0][col] = -1e3f
        val (alt, _) = maxAssignment(sc)
        sc[0][col] = saved
        val margin = total - alt
        // A piece cut out of a merged blob is only named when it clearly looks like the racer it is
        // given: a stray piece (a white body cut from its coloured roof) resembles the look-alike
        // racer only weakly, and a lap for the wrong car is worse than a crossing left for review.
        val needed = if (p.split) SPLIT_WEAK else MIN_SIMILARITY
        return (if (own >= needed && margin >= MIN_MARGIN) free[col].id else null) to margin
    }

    /** Maximum-weight assignment of every row to a distinct column (rows ≤ columns): Hungarian, O(n²m). */
    private fun maxAssignment(s: Array<FloatArray>): Pair<Float, IntArray> {
        val n = s.size; val m = s[0].size
        val inf = 1e9
        val u = DoubleArray(n + 1); val v = DoubleArray(m + 1)
        val pRow = IntArray(m + 1); val way = IntArray(m + 1)
        for (i in 1..n) {
            pRow[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { inf }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = pRow[j0]
                var delta = inf; var j1 = 0
                for (j in 1..m) if (!used[j]) {
                    val cur = -s[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..m) if (used[j]) { u[pRow[j]] += delta; v[j] -= delta } else minv[j] -= delta
                j0 = j1
            } while (pRow[j0] != 0)
            do { val j1 = way[j0]; pRow[j0] = pRow[j1]; j0 = j1 } while (j0 != 0)
        }
        val assign = IntArray(n)
        var total = 0f
        for (j in 1..m) if (pRow[j] != 0) { assign[pRow[j] - 1] = j - 1; total += s[pRow[j] - 1][j - 1] }
        return total to assign
    }

    /** The frame around [t] (its box + a car length), raw foreground included, for the calibration-picture fit. */
    private fun cropFor(t: Track, f: AnalysisFrame, line: LineGeom, prevSide: Float, curSide: Float, a: TrackPoint, b: TrackPoint): FitCrop {
        val pad = max(t.bw, t.bh) + 4
        val x0 = (t.bx - pad).coerceIn(0, w - 1); val y0 = (t.by - pad).coerceIn(0, h - 1)
        val x1 = (t.bx + t.bw + pad).coerceIn(x0 + 1, w); val y1 = (t.by + t.bh + pad).coerceIn(y0 + 1, h)
        val cw = x1 - x0; val ch = y1 - y0
        val cy = ByteArray(cw * ch); val cu = ByteArray(cw * ch); val cv = ByteArray(cw * ch); val fg = ByteArray(cw * ch)
        for (j in 0 until ch) for (i in 0 until cw) {
            val q = (y0 + j) * w + x0 + i; val o = j * cw + i
            cy[o] = f.y[q]; cu[o] = f.u[q]; cv[o] = f.v[q]; fg[o] = rawFg[q]
        }
        val n = t.pts.size
        val o = t.pts[max(0, n - 4)]; val c = t.pts[n - 1]
        val dt = max(1L, c.ts - o.ts) / 1e9f
        val vx = (c.x - o.x) / dt; val vy = (c.y - o.y) / dt
        val vAcross = (curSide - prevSide) / max(1L, b.ts - a.ts).toFloat()
        return FitCrop(x0, y0, cw, ch, cy, cu, cv, fg, f.timestampNs, TemplateFit.heading(vx, vy), sqrt(vx * vx + vy * vy),
            b.x, b.y, line.ax, line.ay, line.dx, line.dy, vAcross, lastMedian)
    }

    /** Time the chosen edge of a fitted outline crosses the line (from the crop frame and the speed across). */
    private fun fitTime(c: FitCrop, pl: Placement, cfg: AnalyzerConfig): Long? {
        if (abs(c.vAcross) < 1e-12f) return null
        val sgn = if (c.vAcross > 0f) 1f else -1f
        val q = pl.corners()
        val sides = (0 until 4).map { c.side(q[2 * it], q[2 * it + 1]) * sgn }
        val lead = when (cfg.finishEdge) {
            app.anylap.data.FinishEdge.FRONT -> sides.max()
            app.anylap.data.FinishEdge.REAR -> sides.min()
            else -> c.side(pl.cx, pl.cy) * sgn
        }
        return c.ts - (lead / abs(c.vAcross)).toLong()
    }

    private fun quadOf(pl: Placement): FloatArray { val q = pl.corners(); return FloatArray(8) { if (it % 2 == 0) q[it] / w else q[it] / h } }

    private fun inside(pl: Placement, x: Float, y: Float, slack: Float): Boolean {
        val dx = x - pl.cx; val dy = y - pl.cy; val c = kotlin.math.cos(pl.angle); val s = kotlin.math.sin(pl.angle)
        return abs(dx * c + dy * s) <= pl.lengthPx / 2 + slack && abs(-dx * s + dy * c) <= pl.widthPx / 2 + slack
    }

    /** Where a remembered crossing's racer is at time [tS] (moved on by its speed, px/s). */
    private fun recentAt(k: Int, tS: Float): Pair<Float, Float> { val r = recentFits[k]; val d = tS - r[1]; return (r[2] + r[4] * d) to (r[3] + r[5] * d) }

    private fun remember(id: String, ns: Long, x: Float, y: Float, vx: Float = 0f, vy: Float = 0f) {
        recentIds += id; recentFits += floatArrayOf(0f, ns / 1e9f, x, y, vx, vy)
        while (recentIds.size > 40) { recentIds.removeAt(0); recentFits.removeAt(0) }
    }

    /** A crossing worth a second look: touching or split racers, a piece of one, a close name, or another racer at the line at the same moment. */
    private fun doubtful(p: Pending, cfg: AnalyzerConfig, sizeRatio: Float): Boolean =
        p.split || sizeRatio >= 1.3f || (sizeRatio in 0.01f..0.7f) ||
            pending.any { it !== p && abs(it.crossNs - p.crossNs) < 150_000_000L } ||
            recentFits.any { abs(it[1] - p.crossNs / 1e9f) < 0.15f }

    private fun emit(p: Pending, cfg: AnalyzerConfig) {
        val t = p.track
        val fp = if (t.histFrames > 0) Fingerprint.normalize(t.hist) else null
        val candidates = if (fp != null) {
            cfg.racers.mapNotNull { r -> score(fp, r)?.let { r.id to it } }.sortedByDescending { it.second }
        } else emptyList()

        val markerEntry = t.markerVotes.maxByOrNull { it.value }
        val markerVotes = markerEntry?.value ?: 0
        val markerRacer = markerEntry?.let { e -> cfg.racers.firstOrNull { it.markerId == e.key } }

        val best = candidates.firstOrNull()?.second ?: 0f
        // Appearance gate: only meaningful once every racer has something to compare with.
        val gated = cfg.minMatch > 0f && cfg.racers.isNotEmpty() &&
            cfg.racers.all { it.fingerprint != null || it.views.isNotEmpty() }
        val accepted = !p.rescue && (!gated || markerRacer != null || best >= cfg.minMatch)

        var racerId: String? = null
        var source = IdSource.NONE
        var confidence = 0f
        when {
            markerRacer != null -> {
                racerId = markerRacer.id
                source = IdSource.MARKER
                confidence = min(1f, 0.6f + 0.2f * markerVotes)
            }
            cfg.racers.size == 1 -> {
                racerId = cfg.racers[0].id
                source = IdSource.ONLY_RACER
                confidence = 1f
            }
            // a piece far too small to be a racer (a wing tip left over from a split) is never named
            p.area > 0 && (expectedArea(p.along, cfg)?.let { p.area < FRAGMENT_MAX * it } ?: false) -> {}
            candidates.isNotEmpty() -> {
                val (id, margin) = jointAppearance(p, fp!!, cfg)
                confidence = margin
                if (id != null) {
                    racerId = id
                    source = IdSource.APPEARANCE
                }
            }
        }
        // ---- second look: fit the racers' calibration pictures onto the crossing
        val sizeRatio = if (p.area > 0) expectedArea(p.along, cfg)?.let { p.area / it } ?: 0f else 0f
        var quad: FloatArray? = null
        var fitInfo: FitInfo? = null
        var acceptedNow = accepted
        val crop = p.crop
        val extras = ArrayList<CrossingEvent>()
        if (crop != null && cfg.captureTemplates) {
            // calibration: keep this pass's picture
        } else if (crop != null && !p.rescue && markerRacer == null && cfg.racers.count { it.template != null } >= 2 && (FIT_ALL || doubtful(p, cfg, sizeRatio))) {
            // try the likely racers by colour, plus the racers that crossed at this very moment (they are the ones in the blob)
            val nearIds = recentIds.indices.filter { abs(recentFits[it][1] - p.crossNs / 1e9f) < 0.4f }.map { recentIds[it] }
            val pool = (candidates.take(FIT_CANDIDATES).map { it.first } + listOfNotNull(racerId) + nearIds).distinct()
                .mapNotNull { id -> cfg.racers.firstOrNull { it.id == id }?.template?.let { id to it } }
            val (pls, firstScores) = TemplateFit.fit(crop, pool, if (sizeRatio >= 1.3f) 3 else 2)
            // the outline this crossing's own blob sits in (its centre inside the outline, 2 px slack)
            val mine = pls.filter { inside(it, crop.cx, crop.cy, 2f) }.minByOrNull { hypot(it.cx - crop.cx, it.cy - crop.cy) }
            if (mine != null && mine.score >= FIT_NAME_MIN) {
                val before = racerId
                // a piece of a racer that was already counted at this very moment (an over-split, a wing tip): not a racer of its own
                val tNow = crop.ts / 1e9f
                val dup = recentIds.indices.any { k -> recentIds[k] == mine.racerId && abs(recentFits[k][1] - p.crossNs / 1e9f) < 0.3f && recentAt(k, tNow).let { (x, y) -> hypot(x - mine.cx, y - mine.cy) } < 0.5f * mine.lengthPx }
                if (dup) { racerId = null; source = IdSource.NONE; acceptedNow = false }
                else {
                    racerId = mine.racerId; source = IdSource.APPEARANCE
                    val second = firstScores.firstOrNull { it.first != mine.racerId }?.second ?: 0f
                    confidence = if (pls.size == 1) (mine.score - second).coerceAtLeast(0f) else max(confidence, 0.12f)
                    fitTime(crop, mine, cfg)?.let { if (abs(it - p.crossNs) < MAX_FIT_SHIFT_NS) p.crossNs = it }
                }
                quad = quadOf(mine)
                fitInfo = FitInfo(firstScores, pls.size, before != racerId)
                // a second racer in the same blob that has no crossing of its own: report it too
                for (o in pls) {
                    if (o === mine || o.racerId == racerId) continue
                    val tt = fitTime(crop, o, cfg) ?: continue
                    if (abs(tt - p.crossNs) > 150_000_000L) continue
                    if (o.score < (if (sizeRatio >= 1.3f) EXTRA_MIN_BIG else EXTRA_MIN)) continue
                    // any other crossing (of any racer) already standing on that spot means the racer there is counted
                    val seen = pending.any { it !== p && it.track.pts.isNotEmpty() && hypot(it.track.pts.last().x - o.cx, it.track.pts.last().y - o.cy) < 0.6f * o.lengthPx && abs(it.crossNs - tt) < 300_000_000L } ||
                        recentIds.indices.any { k -> abs(recentFits[k][1] - tt / 1e9f) < 0.3f && recentAt(k, crop.ts / 1e9f).let { (x, y) -> hypot(x - o.cx, y - o.cy) } < 0.6f * o.lengthPx } ||
                        recentIds.indices.any { k -> recentIds[k] == o.racerId && abs(recentFits[k][1] - tt / 1e9f) < 1.5f } ||
                        (claims[o.racerId]?.let { abs(tt - it) < (cfg.sport.defaultMinLapSec * 1e9).toLong() } ?: false)
                    if (seen) continue
                    claims[o.racerId] = tt
                    remember(o.racerId, tt, o.cx, o.cy, crop.speedPx * kotlin.math.cos(crop.heading), crop.speedPx * kotlin.math.sin(crop.heading))
                    recentFits.last()[1] = crop.ts / 1e9f
                    extras += CrossingEvent(timestampNs = tt, direction = p.direction, racerId = o.racerId, source = IdSource.APPEARANCE, confidence = 0.12f,
                        speedKmh = speedAt(t, p.crossNs, cfg), fingerprint = null, candidates = listOf(o.racerId to o.score) + firstScores.filter { it.first != o.racerId }, markerSeen = null, snapshot = p.snapshot,
                        match = o.score, accepted = true, area = 0, along = p.along, split = true, trackId = t.id, centreNs = tt, sizeRatio = sizeRatio,
                        box = p.box, vx = p.vel.first, vy = p.vel.second, quad = quadOf(o), fit = FitInfo(firstScores, pls.size, true))
                }
            }
        }
        if (racerId != null && !p.rescue) { claims[racerId] = p.crossNs; (quad?.let { q -> ((q[0] + q[2] + q[4] + q[6]) / 4 * w) to ((q[1] + q[3] + q[5] + q[7]) / 4 * h) } ?: crop?.let { it.cx to it.cy })?.let { remember(racerId, crop?.ts ?: p.crossNs, it.first, it.second, crop?.let { c -> c.speedPx * kotlin.math.cos(c.heading) } ?: 0f, crop?.let { c -> c.speedPx * kotlin.math.sin(c.heading) } ?: 0f); recentFits.last()[0] = p.crossNs / 1e9f } }
        onCrossing(
            CrossingEvent(
                timestampNs = p.crossNs,
                direction = p.direction,
                racerId = racerId,
                source = source,
                confidence = confidence,
                speedKmh = speedAt(t, p.crossNs, cfg),
                fingerprint = fp,
                candidates = candidates,
                markerSeen = markerEntry?.key,
                snapshot = p.snapshot,
                match = best,
                accepted = acceptedNow,
                area = p.area,
                along = p.along,
                split = p.split,
                rescue = p.rescue,
                trackId = t.id,
                centreNs = p.centreNs,
                sizeRatio = sizeRatio,
                box = p.box.let { b -> val k = (p.crossNs - p.boxTs) / 1e9f; OverlayBox(b.l + p.vel.first * k, b.t + p.vel.second * k, b.r + p.vel.first * k, b.b + p.vel.second * k, true) },
                vx = p.vel.first, vy = p.vel.second,
                quad = quad, fit = fitInfo,
                template = if (cfg.captureTemplates && crop != null) TemplateFit.capture(crop, cfg.exposureS, BLUR_SEEN) else null,
            ),
        )
        for (x in extras) onCrossing(x)
        // Learn how big one racer is at this point of the line (whole, confidently named blobs only).
        if (accepted && !p.split && !p.rescue && p.area > 0 && (source == IdSource.ONLY_RACER || source == IdSource.MARKER || (source == IdSource.APPEARANCE && confidence >= 2 * MIN_MARGIN))) {
            sizeSamples += floatArrayOf(p.along, p.area.toFloat())
            if (sizeSamples.size > 60) sizeSamples.removeAt(0)
        }
    }

    /**
     * Colour match against a racer: the combined fingerprint, blended with the best single view
     * (left side, right side, a pass…) so a car that looks different from each side still matches.
     */
    private fun score(fp: FloatArray, r: RacerRef): Float? {
        val avg = r.fingerprint?.let { Fingerprint.similarity(fp, it) }
        val best = r.views.maxOfOrNull { Fingerprint.similarity(fp, it) }
        return when {
            avg != null && best != null -> 0.5f * avg + 0.5f * max(avg, best)
            else -> avg ?: best
        }
    }

    /**
     * RGB crop around the track (a little padding), at analysis resolution, with the boxes of every
     * racer in it, the finish line and how they were moving – for the review tray's picture.
     */
    private fun snapshot(t: Track, f: AnalysisFrame): Snapshot? {
        if (t.bw <= 0 || t.bh <= 0) return null
        val pad = (max(t.bw, t.bh) * 0.6f).roundToInt() + 6
        val x0 = (t.bx - pad).coerceIn(0, w - 1)
        val y0 = (t.by - pad).coerceIn(0, h - 1)
        val x1 = (t.bx + t.bw + pad).coerceIn(x0 + 1, w)
        val y1 = (t.by + t.bh + pad).coerceIn(y0 + 1, h)
        val sw = x1 - x0
        val sh = y1 - y0
        val out = IntArray(sw * sh)
        for (yy in 0 until sh) {
            val row = (y0 + yy) * w
            for (xx in 0 until sw) {
                val i = row + x0 + xx
                out[yy * sw + xx] = yuvToArgb(f.y[i].toInt() and 0xFF, f.u[i].toInt() and 0xFF, f.v[i].toInt() and 0xFF)
            }
        }
        val boxes = tracks.filter { it.missed == 0 && it.bw > 0 && it.bx < x1 && it.bx + it.bw > x0 && it.by < y1 && it.by + it.bh > y0 }.map { o ->
            val n = o.pts.size
            var vx = 0f; var vy = 0f
            if (n >= 2) {
                val a = o.pts[n - 2]; val b = o.pts[n - 1]
                val dt = max(1L, b.ts - a.ts) / 1e9f
                vx = (b.x - a.x) / dt / sw; vy = (b.y - a.y) / dt / sh
            }
            SnapBox(
                o.id,
                (o.bx - x0).toFloat() / sw, (o.by - y0).toFloat() / sh,
                (o.bx + o.bw - x0).toFloat() / sw, (o.by + o.bh - y0).toFloat() / sh,
                vx, vy,
            )
        }
        val l = config.line
        val line = floatArrayOf((l.x1 * w - x0) / sw, (l.y1 * h - y0) / sh, (l.x2 * w - x0) / sw, (l.y2 * h - y0) / sh)
        val n = t.pts.size
        val frameNs = if (n >= 2) t.pts[n - 1].ts - t.pts[n - 2].ts else 0L
        return Snapshot(sw, sh, out, boxes, line, f.timestampNs, frameNs)
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val d = u - 128
        val e = v - 128
        val r = (y + 1.402f * e).roundToInt().coerceIn(0, 255)
        val g = (y - 0.344f * d - 0.714f * e).roundToInt().coerceIn(0, 255)
        val b = (y + 1.772f * d).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun speedAt(t: Track, ts: Long, cfg: AnalyzerConfig): Float? {
        val mpp = cfg.metersPerProcPx ?: return null
        val win = t.pts.filter { abs(it.ts - ts) <= 150_000_000L }.ifEmpty { t.pts.takeLast(3) }
        if (win.size < 2) return null
        val t0 = win[0].ts
        val n = win.size
        var st = 0.0; var sx = 0.0; var sy = 0.0; var stt = 0.0; var stx = 0.0; var sty = 0.0
        for (pt in win) {
            val tt = (pt.ts - t0) / 1e9
            st += tt; sx += pt.x; sy += pt.y; stt += tt * tt; stx += tt * pt.x; sty += tt * pt.y
        }
        val den = n * stt - st * st
        if (den <= 1e-12) return null
        val vx = (n * stx - st * sx) / den
        val vy = (n * sty - st * sy) / den
        val kmh = (hypot(vx, vy) * mpp * 3.6).toFloat()
        return if (kmh.isFinite() && kmh < 400f) kmh else null
    }

    // ------------------------------------------------------------------ markers

    private fun readMarker(t: Track, f: AnalysisFrame) {
        val sx = f.fullWidth.toFloat() / w
        val sy = f.fullHeight.toFloat() / h
        val padX = t.bw * 0.15f
        val padY = t.bh * 0.15f
        val x0 = ((t.bx - padX) * sx).roundToInt().coerceIn(0, f.fullWidth - 2)
        val y0 = ((t.by - padY) * sy).roundToInt().coerceIn(0, f.fullHeight - 2)
        val x1 = ((t.bx + t.bw + padX) * sx).roundToInt().coerceIn(x0 + 2, f.fullWidth)
        val y1 = ((t.by + t.bh + padY) * sy).roundToInt().coerceIn(y0 + 2, f.fullHeight)
        var cw = x1 - x0
        var ch = y1 - y0
        if (cw < 24 || ch < 24) return
        var crop = f.lumaCrop(x0, y0, cw, ch) ?: return
        // Keep marker reading cheap: halve very large crops.
        if (cw * ch > 500_000) {
            val hw = cw / 2; val hh = ch / 2
            val half = ByteArray(hw * hh)
            for (y in 0 until hh) for (x in 0 until hw) half[y * hw + x] = crop[(y * 2) * cw + x * 2]
            crop = half; cw = hw; ch = hh
        }
        for (id in markerDetector.detect(crop, cw, ch)) {
            t.markerVotes[id] = (t.markerVotes[id] ?: 0) + 1
        }
    }

    companion object {
        /** Width of the downscaled frame used for motion analysis. */
        const val PROC_W = 480
        const val MIN_SIMILARITY = 0.30f
        const val MIN_MARGIN = 0.06f

        /**
         * Default [AnalyzerConfig.minMatch]. Two things of the same brightness already share the
         * whole Y part of the histogram (0.35), so anything below that is "same brightness, other
         * colours"; a car against its own photos and passes should land well above this.
         */
        const val MIN_MATCH = 0.45f

        /** Fraction of the frame moving at once above which crossings are not counted. */
        const val UNSTABLE_FRAC = 0.30f
        /**
         * A blob this many times one racer's size (at that point of the line) is split. Lower values
         * split lone fast racers (blur makes them long); racers that hide each other and only reach
         * ~1.5× stay merged – a higher camera is the fix for those, not a lower threshold.
         */
        const val SPLIT_FACTOR = 1.7f
        /** Frames of box size used for a racer's length (leading-edge timing). */
        const val EDGE_FRAMES = 6
        /** A piece smaller than this share of one racer next to a racer-sized blob is part of it (front wing). */
        const val APPENDIX_MAX = 0.45f
        /** Below this share of one racer's size a crossing blob is a fragment, never named. */
        const val FRAGMENT_MAX = 0.4f
        /** Fit the calibration pictures onto every crossing, not only doubtful ones (bench / photo finishes). */
        var FIT_ALL = false
        /** Candidates (by colour) whose pictures are tried. */
        const val FIT_CANDIDATES = 5
        /** A fitted outline names a racer only above this score. */
        const val FIT_NAME_MIN = 0.45f
        /** A second racer found in a blob is reported only when its picture fits this well. */
        const val EXTRA_MIN = 0.6f
        /** … or this well when the blob is clearly bigger than one racer. */
        const val EXTRA_MIN_BIG = 0.5f
        /** The fit may move a crossing time by at most this much. */
        const val MAX_FIT_SHIFT_NS = 120_000_000L
        /** How far the clean-up (1 erosion, 4 dilations of 3×3) grows a blob on each side, px. */
        const val MASK_GROW_PX = 3f
        /** Share of the motion-blur smear that is solid enough to be in the mask (Blender bench: ~half). */
        const val BLUR_SEEN = 0.5f
        /** A racer's half-length never takes longer than this to cross (a parked car at the line). */
        const val MAX_EDGE_NS = 200_000_000L
        /** Each part of a size-based split must look at least this much like some racer. */
        const val SPLIT_PART_MATCH = 0.5f
        /** A split part is named only when it matches its racer at least this well (else left for review). */
        var SPLIT_WEAK = 0.75f
        /** A pixel is a gap (valley) when it is weaker than both neighbours two pixels out by this factor. */
        const val VALLEY_RATIO = 1.6f
        /** A slot between racers is darker than the ground by at least this (grey levels)… */
        const val GAP_BELOW_GROUND = 10f
        /** …and darker than both racers beside it by this factor. */
        const val GAP_RATIO = 1.3f
        /** An un-dilated core counts as a racer at this fraction of one racer's (dilated) size. */
        const val CORE_MIN_FRAC = 0.25f
        /** A part of a size-based split must be this fraction of one racer's size, at least / at most. */
        const val PART_MIN = 0.55f
        const val PART_MAX = 1.5f
        /** Colour codes for splitting: 12 hues + bright grey + mid grey + dark. */
        const val CODES = 15
        /** Weight of a pixel's colour fit against its distance in the split k-means (0 = position only). */
        var COLOUR_WEIGHT = 0f
        /** Check lines sit this fraction of the frame width beyond the finish line, on both sides. */
        const val CHECK_OFFSET = 0.15f
        /** A track reaching a check line with no finish crossing within this long is a possible miss. */
        const val RESCUE_WINDOW_NS = 700_000_000L
        /** Share of a crossing's recent travel that must be across the line (0.7 ≈ within 45°). */
        const val ACROSS_FRAC = 0.7f
        var DEBUG_SPLIT = false
        /** Frames between exposure (median luma) updates for the fingerprint. */
        const val EXPOSURE_EVERY = 15L
        /** How far past the line's ends (as a fraction of its length) a crossing still counts. */
        const val ALONG_MARGIN = 0.05f
        /** Rim of the (dilated) mask that is left out of fingerprints, in analysis pixels. */
        const val CORE = 4
        const val MOVE_WINDOW_NS = 300_000_000L
        const val MIN_TRAVEL_PX = 6
        const val MIN_TRAVEL_FRAC = 0.02f
    }
}
