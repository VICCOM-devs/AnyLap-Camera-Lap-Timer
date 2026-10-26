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
            m.reset(); tracks.clear(); pending.clear()
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
        val blobs = blobFinder!!.find(mask, minArea)
        updateTracks(blobs, f.timestampNs)

        // A lot of the frame "moving" at once is a lighting transient (auto-exposure, a cloud, a
        // door opening): keep following things, but don't count crossings until it settles.
        val unstable = frac > UNSTABLE_FRAC
        val line = lineGeom(cfg.line)
        for (t in tracks) {
            if (t.missed > 0) continue
            accumulateFingerprint(t, f)
            checkCrossing(t, line, cfg, f, unstable)
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
        for (b in blobs) if (b !in usedB) tracks += Track(nextTrackId++).also { it.add(b, ts) }
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
        val added = sampleFingerprint(t, f, x0, y0, x1, y1, core = true)
        if (added == 0) sampleFingerprint(t, f, x0, y0, x1, y1, core = false)
        t.histFrames++
    }

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
                if (mask[i].toInt() != 0) {
                    val ok = !core || (
                        x >= CORE && x + CORE < w && y >= CORE && y + CORE < h &&
                            mask[i - CORE].toInt() != 0 && mask[i + CORE].toInt() != 0 &&
                            mask[i - cw].toInt() != 0 && mask[i + cw].toInt() != 0
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

    // ------------------------------------------------------------------ crossings

    private class LineGeom(val ax: Float, val ay: Float, val bx: Float, val by: Float) {
        val dx = bx - ax
        val dy = by - ay
        val len = max(1f, sqrt(dx * dx + dy * dy))
        fun side(x: Float, y: Float) = (dx * (y - ay) - dy * (x - ax)) / len
        fun along(x: Float, y: Float) = ((x - ax) * dx + (y - ay) * dy) / (len * len)
    }

    private fun lineGeom(l: LineSpec) = LineGeom(l.x1 * w, l.y1 * h, l.x2 * w, l.y2 * h)

    private inner class Pending(val track: Track, val crossNs: Long, val direction: Int, val snapshot: Snapshot?)

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
        if (wanted) pending += Pending(t, crossNs, dir, if (cfg.captureSnapshots) snapshot(t, frame) else null)
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
        return across >= 0.5f * travel
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
        val accepted = !gated || markerRacer != null || best >= cfg.minMatch

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
                val best = candidates[0]
                val second = candidates.getOrNull(1)?.second ?: 0f
                confidence = best.second - second
                if (best.second >= MIN_SIMILARITY && best.second - second >= MIN_MARGIN) {
                    racerId = best.first
                    source = IdSource.APPEARANCE
                }
            }
        }
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
            ),
        )
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
