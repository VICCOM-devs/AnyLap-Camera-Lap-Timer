package app.anylap.learn

import app.anylap.cloud.Cloud
import app.anylap.data.CalibrationSample
import app.anylap.data.Repository
import app.anylap.data.ReviewItem
import app.anylap.data.SampleKind
import app.anylap.data.Session
import app.anylap.engine.Learning
import app.anylap.engine.RaceEngine
import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import app.anylap.util.Bmp
import app.anylap.vision.CrossingEvent
import app.anylap.vision.Snapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What happens with a race's confirmed crossings once it is saved, and with every verdict in the Check tray:
 * 1. **Local self-learning** (Settings → "Keep learning my cars"): the car gets the crossing as a learned view
 *    ([Learning.merge]), so next time it is matched under this light too.
 * 2. **Shared crops** (Settings → "Help improve car recognition"): the picture + labels are queued for upload
 *    ([Crops]) – the training data for the recognition model of future versions.
 */
object Learn {
    /** Crops shared per session at most (Check cards first – they are the hard cases – then confirmed ones). */
    const val MAX_CROPS_PER_SESSION = 120

    class Camera(val exposureS: Float, val fps: Double, val metersPerPx: Float?, val frameW: Int, val frameH: Int)

    /** After [RaceEngine.buildSession]: learn the confirmed crossings and queue the crops. Runs off the main thread. */
    @OptIn(ExperimentalUuidApi::class)
    fun afterRace(repo: Repository, cloud: Cloud, engine: RaceEngine, session: Session, camera: Camera) {
        val settings = repo.settings.value
        val confirmed = try { engine.learned() } catch (t: Throwable) { emptyList() }

        if (settings.learnCars) {
            for ((racerId, list) in confirmed.groupBy { it.racerId }) {
                val racer = repo.racer(racerId) ?: continue
                if (!racer.isCalibrated) continue
                val samples = list.mapNotNull { c -> sampleOf(c.event, c.by, session.id, c.event.snapshot) }
                applyMerge(repo, racerId, samples)
            }
        }

        if (settings.shareCrops) {
            val now = Platform.nowMillis()
            val items = ArrayList<CropItem>()
            val pictured = try { engine.pictured() } catch (t: Throwable) { emptyList() }
            val onCard = session.reviews.associateBy { it.atMs }
            val learnedAt = confirmed.associateBy { it.atMs }
            // the hard cases first
            val cards = pictured.filter { (at, _, _) -> onCard.keys.any { kotlin.math.abs(it - at) <= 1 } }
            val plain = pictured.filter { (at, _, _) -> at in learnedAt && cards.none { it.first == at } }
            val chosen = cards.take(MAX_CROPS_PER_SESSION) + spread(plain, MAX_CROPS_PER_SESSION - minOf(cards.size, MAX_CROPS_PER_SESSION))
            val seen = HashSet<Long>()
            for ((at, owner, e) in chosen) {
                if (!seen.add(at)) continue
                val snap = e.snapshot ?: continue
                val card = onCard.entries.firstOrNull { kotlin.math.abs(it.key - at) <= 1 }?.value
                val kind = card?.let { "card:${it.kind.name}" } ?: learnedAt[at]?.by?.key ?: "auto"
                val stored = Crops.store(snap.width, snap.height, snap.argb) ?: continue
                items += CropItem(Uuid.random().toString(), session.id, at, owner, kind, stored.first, stored.second, meta(e, snap, card, session, camera), now)
            }
            Crops.add(items, cloud.takeIf { settings.cropsNoticeSeen })
        }
    }

    /** A human answered a Check card (or agreed with it): teach the car and share the picture with the answer. */
    @OptIn(ExperimentalUuidApi::class)
    fun verdict(repo: Repository, cloud: Cloud, session: Session, item: ReviewItem, racerId: String, atMs: Long = item.atMs) {
        val settings = repo.settings.value
        val subjectBox = item.boxes.firstOrNull { it.subject }
        val img = item.imagePath?.let { Platform.readBytes(it) }?.let { Bmp.decode(it) }
        val fp = item.fingerprint
        if (settings.learnCars && racerId.isNotEmpty() && fp != null && kotlin.math.abs(atMs - item.atMs) <= 2 && repo.racer(racerId)?.isCalibrated == true) {
            val thumb = img?.let { (w, h, px) -> subjectBox?.let { b -> Bmp.crop(w, h, px, b.l, b.t, b.r, b.b) } }
            val path = thumb?.let { (w, h, px) ->
                try { joinPath(Platform.dataDir, "learn_${racerId}_${Platform.nowMillis()}.bmp").also { Platform.writeBytes(it, Bmp.encode(w, h, px)) } } catch (t: Throwable) { null }
            }
            val sample = CalibrationSample(
                id = Uuid.random().toString(), kind = SampleKind.LEARNED, fingerprint = fp, imagePath = path, createdAt = Platform.nowMillis(),
                speedKmh = item.speedKmh, direction = item.direction, sessionId = session.id, learnedBy = Learning.By.VERDICT.key,
            )
            applyMerge(repo, racerId, listOf(sample))
        }
        if (settings.shareCrops && img != null) {
            val (w, h, px) = img
            val stored = Crops.store(w, h, px) ?: return
            val meta = buildJsonObject {
                put("w", w); put("h", h)
                put("line", buildJsonArray { item.line.forEach { add(it) } })
                put("boxes", buildJsonArray {
                    item.boxes.forEach { b ->
                        add(buildJsonObject {
                            put("l", b.l); put("t", b.t); put("r", b.r); put("b", b.b); put("vx", b.vx); put("vy", b.vy)
                            put("racer", if (b.atMs != null && kotlin.math.abs(b.atMs - atMs) <= 2) racerId.ifEmpty { null } else b.racerId)
                            put("subject", b.subject)
                            b.quad?.let { q -> put("quad", buildJsonArray { q.forEach { add(it) } }) }
                        })
                    }
                })
                put("card", item.kind.name)
                put("decided", item.decided)
                put("speedKmh", item.speedKmh)
                put("direction", item.direction)
                put("sport", session.config.sport.name)
                put("build", app.anylap.BuildInfo.BUILD)
            }
            Crops.add(listOf(CropItem(Uuid.random().toString(), session.id, atMs, racerId.ifEmpty { null }, "verdict", stored.first, stored.second, meta, Platform.nowMillis())), cloud.takeIf { settings.cropsNoticeSeen })
        }
    }

    private fun applyMerge(repo: Repository, racerId: String, samples: List<CalibrationSample>) {
        if (samples.isEmpty()) return
        val racer = repo.racer(racerId) ?: return
        val (updated, dropped) = Learning.merge(racer, samples)
        dropped.forEach { d -> d.imagePath?.let { Platform.delete(it) } }
        if (updated !== racer) repo.upsertRacer(updated)
    }

    /** A learned sample from a confirmed crossing: the fingerprint plus a small picture of the car itself. */
    @OptIn(ExperimentalUuidApi::class)
    private fun sampleOf(e: CrossingEvent, by: Learning.By, sessionId: String, snap: Snapshot?): CalibrationSample? {
        val fp = e.fingerprint ?: return null
        val box = snap?.boxes?.firstOrNull { it.trackId == e.trackId }
        val path = if (snap != null && box != null) {
            Bmp.crop(snap.width, snap.height, snap.argb, box.l, box.t, box.r, box.b)?.let { (w, h, px) ->
                try { joinPath(Platform.dataDir, "learn_${e.racerId}_${e.timestampNs / 1_000_000}.bmp").also { Platform.writeBytes(it, Bmp.encode(w, h, px)) } } catch (t: Throwable) { null }
            }
        } else null
        return CalibrationSample(
            id = Uuid.random().toString(), kind = SampleKind.LEARNED, fingerprint = fp.toList(), imagePath = path, createdAt = Platform.nowMillis(),
            speedKmh = e.speedKmh, direction = e.direction, match = e.match.takeIf { it > 0f }, sessionId = sessionId, learnedBy = by.key,
        )
    }

    /** Every k-th of a list so that at most [n] remain, spread over the race rather than its first minutes. */
    private fun <T> spread(list: List<T>, n: Int): List<T> {
        if (n <= 0 || list.isEmpty()) return emptyList()
        if (list.size <= n) return list
        val step = list.size.toFloat() / n
        return (0 until n).map { list[(it * step).toInt().coerceAtMost(list.size - 1)] }
    }

    /** Camera facts + what was in the picture, for the training set. Nothing personal: ids are random UUIDs. */
    private fun meta(e: CrossingEvent, snap: Snapshot, card: ReviewItem?, session: Session, camera: Camera): JsonObject = buildJsonObject {
        put("w", snap.width); put("h", snap.height)
        put("x0", snap.x0); put("y0", snap.y0); put("frameW", snap.frameW); put("frameH", snap.frameH)
        put("lumaRef", snap.lumaRef)
        snap.line?.let { l -> put("line", buildJsonArray { l.forEach { add(it) } }) }
        put("boxes", buildJsonArray {
            // the card's boxes (after the outlines) by position: the one whose centre falls in this tracker box
            val cardBoxes = card?.boxes ?: emptyList()
            snap.boxes.forEach { b ->
                add(buildJsonObject {
                    put("l", b.l); put("t", b.t); put("r", b.r); put("b", b.b); put("vx", b.vx); put("vy", b.vy)
                    put("subject", b.trackId == e.trackId)
                    val cb = cardBoxes.firstOrNull { c -> val cx = (c.l + c.r) / 2; val cy = (c.t + c.b) / 2; cx in b.l..b.r && cy in b.t..b.b }
                    put("racer", if (b.trackId == e.trackId) e.racerId else cb?.racerId)
                    cb?.quad?.let { q -> put("quad", buildJsonArray { q.forEach { add(it) } }) }
                })
            }
        })
        e.quad?.let { q -> put("quad", buildJsonArray { q.forEach { add(it) } }) }
        put("source", e.source.name)
        put("match", e.match); put("confidence", e.confidence)
        put("candidates", buildJsonArray { e.candidates.take(5).forEach { add(buildJsonObject { put("racer", it.first); put("score", it.second) }) } })
        e.fit?.let { f -> put("fit", buildJsonObject { put("cars", f.cars); put("renamed", f.renamed); put("scores", buildJsonArray { f.scores.forEach { add(buildJsonObject { put("racer", it.first); put("score", it.second) }) } }) }) }
        put("split", e.split); put("rescue", e.rescue); put("accepted", e.accepted)
        put("sizeRatio", e.sizeRatio); put("area", e.area); put("along", e.along)
        put("speedKmh", e.speedKmh); put("direction", e.direction)
        card?.let { put("card", it.kind.name); put("decided", it.decided) }
        put("exposureS", camera.exposureS); put("fps", camera.fps); put("metersPerPx", camera.metersPerPx)
        put("sport", session.config.sport.name)
        put("finishEdge", session.config.finishEdge.name)
        put("racers", session.racers.size)
        put("build", app.anylap.BuildInfo.BUILD)
    }
}
