package app.anylap.vision

import app.anylap.data.Direction
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
    /** Learned (along, area) of single racers this session. */
    private val sizeSamples = ArrayList<FloatArray>()
    private var tmpA = ByteArray(0)
    private var tmpB = ByteArray(0)
    private val markerDetector = MarkerDetector()
    private var exposure = Fingerprint.NORMAL
    private val lumaHist = IntArray(256)

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
        frameCount++
        if (frameCount % EXPOSURE_EVERY == 1L) exposure = Fingerprint.Exposure(medianLuma(f))
        if (!m.warmedUp) return
        if (frac > 0.45f) {
            // Lighting jumped or the camera moved: start over rather than invent crossings.
            m.reset(); tracks.clear(); pending.clear()
            return
        }
        // Clean up: remove speckles, then join the parts of a racer (legs, wheels…) into one blob.
        Morph.erode(mask, tmpA, tmpB, w, h)
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

    private class TrackPoint(val ts: Long, val x: Float, val y: Float)

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
            pts += TrackPoint(ts, b.cx, b.cy)
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
            if (inside.size >= 2) {
                seeds = inside.take(4).map { it.predict(ts) }
            } else {
                if (DEBUG_SPLIT && exp != null && b.area > 1.2f * exp) println("big blob area ${b.area} exp ${exp.toInt()} along ${line.along(b.cx, b.cy)} ts ${ts / 1e9}")
                if (exp != null && b.area > SPLIT_FACTOR * exp) {
                    val k = (b.area / exp).roundToInt().coerceIn(2, 4)
                    seeds = axisSeeds(b, k, perpendicular = false)
                    source = inside.singleOrNull()?.id ?: -1
                    // Racers side by side make a squarish blob whose long axis may be the wrong one:
                    // also try across, keep the cut whose parts each look most like a known racer.
                    if (cfg.racers.any { it.fingerprint != null }) {
                        val alt = axisSeeds(b, k, perpendicular = true)
                        val a1 = kmeans(b, seeds)?.let { partsScore(b, f, cfg) }
                        val a2 = kmeans(b, alt)?.let { partsScore(b, f, cfg) }
                        val pick = if (a2 != null && (a1 == null || a2.first > a1.first)) { seeds = alt; a2 } else a1
                        // Parts that all look like the same racer are one big-looking racer (camera
                        // closer than at calibration, long blur), not several: leave it whole.
                        if (pick == null || pick.second.any { it == null } || pick.second.distinct().size < pick.second.size) seeds = null
                    }
                }
            }
            if (seeds == null) { out += b; continue }
            val parts = kmeans(b, seeds)
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
                if (a.area > 0.85f * exp || b.area > 0.85f * exp || a.area + b.area > 1.3f * exp) continue
                val gapX = max(0, max(a.x, b.x) - min(a.x + a.w, b.x + b.w))
                val gapY = max(0, max(a.y, b.y) - min(a.y + a.h, b.y + b.h))
                if (max(gapX, gapY) > 0.5f * sqrt(exp)) continue
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

    /** k-means on the blob's pixel positions; null if a part ends up too small to be a racer. */
    private fun kmeans(b: Blob, seeds: List<Pair<Float, Float>>): List<Blob>? {
        val bf = blobFinder!!
        if (px.size < b.area) { px = IntArray(b.area); py = IntArray(b.area); assignment = IntArray(b.area) }
        var n = 0
        for (yy in b.y until b.y + b.h) for (xx in b.x until b.x + b.w) {
            val i = yy * w + xx
            if (bf.labelAt(i) == b.label && mask[i].toInt() != 0 && n < px.size) { px[n] = xx; py[n] = yy; n++ }
        }
        val k = seeds.size
        val cx = FloatArray(k) { seeds[it].first }
        val cy = FloatArray(k) { seeds[it].second }
        val cnt = IntArray(k)
        repeat(3) { iter ->
            for (j in 0 until n) {
                var best = 0; var bd = Float.MAX_VALUE
                for (c in 0 until k) { val dx = px[j] - cx[c]; val dy = py[j] - cy[c]; val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = c } }
                assignment[j] = best
            }
            if (iter < 2) {
                val sx = FloatArray(k); val sy = FloatArray(k); cnt.fill(0)
                for (j in 0 until n) { val c = assignment[j]; sx[c] += px[j]; sy[c] += py[j]; cnt[c]++ }
                for (c in 0 until k) if (cnt[c] > 0) { cx[c] = sx[c] / cnt[c]; cy[c] = sy[c] / cnt[c] }
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

    private inner class Pending(val track: Track, val crossNs: Long, val direction: Int, val snapshot: Snapshot?, val along: Float, val area: Int, val split: Boolean, val rescue: Boolean = false)

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

        val crossNs = prev.ts + ((cur.ts - prev.ts) * f).toLong()
        if (crossNs - t.lastCrossTs < 400_000_000L) return // wobbling on the line
        t.lastCrossTs = crossNs

        val dir = if (prevSide < 0f) 1 else -1
        val wanted = when (cfg.direction) {
            Direction.BOTH -> true
            Direction.FORWARD -> dir == 1
            Direction.REVERSE -> dir == -1
        }
        if (wanted) pending += Pending(t, crossNs, dir, if (cfg.captureSnapshots) snapshot(t, frame) else null, along, t.area, t.tag != 0)
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
        return (if (own >= MIN_SIMILARITY && margin >= MIN_MARGIN) free[col].id else null) to margin
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
            candidates.isNotEmpty() -> {
                val (id, margin) = jointAppearance(p, fp!!, cfg)
                confidence = margin
                if (id != null) {
                    racerId = id
                    source = IdSource.APPEARANCE
                }
            }
        }
        if (racerId != null && !p.rescue) claims[racerId] = p.crossNs
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
                accepted = accepted,
                area = p.area,
                along = p.along,
                split = p.split,
                rescue = p.rescue,
            ),
        )
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

    /** RGB crop around the track (a little padding), at analysis resolution. */
    private fun snapshot(t: Track, f: AnalysisFrame): Snapshot? {
        if (t.bw <= 0 || t.bh <= 0) return null
        val pad = (max(t.bw, t.bh) * 0.25f).roundToInt() + 4
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
        return Snapshot(sw, sh, out)
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
        /** Each part of a size-based split must look at least this much like some racer. */
        const val SPLIT_PART_MATCH = 0.5f
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
