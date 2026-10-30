package app.anylap.learn

import app.anylap.cloud.Cloud
import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import app.anylap.util.Bmp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One small picture of the finish line at a crossing, with which car it was, waiting to be shared (Settings →
 * Car recognition → "Help improve car recognition"). The pictures train the recognition model that ships in
 * future versions, so a user a year from now gets better detection for cars nobody has seen. Nothing but
 * the crop, the labels and the camera facts leave the phone: no names, no video, no location.
 */
@Serializable
data class CropItem(
    val id: String,
    val sessionId: String,
    val atMs: Long,
    /** Which car the race (or the human) finally counted it for; null = nobody ("not a car", unknown). */
    val racerId: String?,
    /** "auto" (camera + lap timing agreed), "fit", "card:<ReviewKind>" (a Check card as the race left it), "verdict" (a human's answer). */
    val kind: String,
    val imagePath: String,
    val mime: String,
    /** Boxes, line, speed, exposure, build … – see [CropMeta]. */
    val meta: JsonObject,
    val createdAt: Long,
)

/** The upload queue: survives restarts, uploads in the background, never blocks a race. */
object Crops {
    const val MAX_QUEUE = 600
    /** JPEG quality for the crops (small cars at 480 px: artefacts would eat the decals). */
    const val JPEG_QUALITY = 92

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val file: String get() = joinPath(Platform.dataDir, "crops_queue.json")

    private val _pending = MutableStateFlow(load().size)
    /** How many are waiting (Settings shows it). */
    val pending: StateFlow<Int> = _pending
    private val _uploaded = MutableStateFlow(Platform.readText(joinPath(Platform.dataDir, "crops_uploaded.txt"))?.trim()?.toIntOrNull() ?: 0)
    /** Shared so far from this phone. */
    val uploaded: StateFlow<Int> = _uploaded

    private fun load(): MutableList<CropItem> = try {
        Platform.readText(file)?.let { json.decodeFromString(ListSerializer(CropItem.serializer()), it) }?.toMutableList() ?: ArrayList()
    } catch (t: Throwable) { ArrayList() }

    private fun save(items: List<CropItem>) {
        try { Platform.writeText(file, json.encodeToString(ListSerializer(CropItem.serializer()), items)) } catch (t: Throwable) {}
        _pending.value = items.size
    }

    /** Writes the picture to the queue folder as JPEG (BMP where there is no encoder) and returns (path, mime). */
    @OptIn(ExperimentalUuidApi::class)
    fun store(width: Int, height: Int, argb: IntArray): Pair<String, String>? {
        if (width <= 0 || height <= 0 || argb.size < width * height) return null
        val id = Uuid.random().toString()
        val jpeg = Platform.encodeJpeg(width, height, argb, JPEG_QUALITY)
        val (bytes, ext, mime) = if (jpeg != null) Triple(jpeg, "jpg", "image/jpeg") else Triple(Bmp.encode(width, height, argb), "bmp", "image/bmp")
        val path = joinPath(Platform.dataDir, "crop_$id.$ext")
        return try { Platform.writeBytes(path, bytes); path to mime } catch (t: Throwable) { null }
    }

    /** Queues items (oldest dropped beyond [MAX_QUEUE]) and starts uploading. */
    fun add(items: List<CropItem>, cloud: Cloud?) {
        if (items.isEmpty()) return
        scope.launch {
            lock.withLock {
                val q = load()
                q += items
                while (q.size > MAX_QUEUE) { val old = q.removeAt(0); Platform.delete(old.imagePath) }
                save(q)
            }
            if (cloud != null) flush(cloud)
        }
    }

    /** Drops everything waiting (the user turned sharing off). */
    fun clear() {
        scope.launch {
            lock.withLock {
                load().forEach { Platform.delete(it.imagePath) }
                save(emptyList())
            }
        }
    }

    fun flushSoon(cloud: Cloud) { scope.launch { flush(cloud) } }

    /** Uploads what is waiting, one by one; stops at the first failure (next launch / save tries again). */
    suspend fun flush(cloud: Cloud) {
        lock.withLock {
            val q = load()
            if (q.isEmpty()) return
            var done = 0
            for (item in q.toList()) {
                val ok = try { cloud.uploadCrop(item) } catch (t: Throwable) { false }
                if (!ok) break
                Platform.delete(item.imagePath)
                q.remove(item)
                done++
                if (done % 5 == 0) save(q)
            }
            if (done > 0) {
                save(q)
                _uploaded.value += done
                try { Platform.writeText(joinPath(Platform.dataDir, "crops_uploaded.txt"), _uploaded.value.toString()) } catch (t: Throwable) {}
            }
        }
    }
}
