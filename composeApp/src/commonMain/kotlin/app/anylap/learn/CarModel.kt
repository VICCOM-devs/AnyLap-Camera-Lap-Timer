package app.anylap.learn

import app.anylap.BuildInfo
import app.anylap.cloud.BackendConfig
import app.anylap.data.Racer
import app.anylap.data.SampleKind
import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import app.anylap.util.Bmp
import app.anylap.util.Sha256
import app.anylap.vision.CrossingEvent
import app.anylap.platform.SignatureRunner
import app.anylap.vision.Snapshot
import app.anylap.vision.cropForSignature
import app.anylap.vision.signatureSimilarity
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The recognition model's opinion about one crossing: nearest calibrated car by signature. */
class MlVerdict(
    /** Racers ranked by signature similarity to their calibration prototypes, best first. */
    val ranked: List<Pair<String, Float>>,
    /** Probability that there is a car at all at the crop centre. */
    val carProbability: Float,
    val modelVersion: Int,
) {
    val best: Pair<String, Float>? get() = ranked.firstOrNull()
    val margin: Float get() = (ranked.getOrNull(0)?.second ?: 0f) - (ranked.getOrNull(1)?.second ?: 0f)
}

/**
 * The car-recognition network on the phone (fail-safe 1): downloaded from `anylap_models` (newest version
 * for this platform and build, sha256-checked), run on the crop of every crossing. **Shadow mode (0.5.0):**
 * its answer is logged (crop meta, crossing log) and used only as a tie-breaker when the colour match is
 * unsure – the colour engine + lap timing still decide. Prototypes come from each racer's calibration passes
 * (and learned views), nothing is trained on the phone. No model row yet → everything behaves as before.
 */
object CarModel {
    const val NAME = "signature"
    /** Signature similarity the tie-breaker needs before it speaks up. */
    const val MIN_SCORE = 0.60f
    const val MIN_MARGIN = 0.10f
    /** When the model and the colour runner-up agree against the colour winner by this much, the runner-up wins. */
    const val SWITCH_MARGIN = 0.15f

    data class State(
        val version: Int = 0,
        val ready: Boolean = false,
        val downloading: Boolean = false,
        val error: String? = null,
        /** Crossings scored this session (Settings shows it). */
        val scored: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val runLock = Mutex()
    private var lastCheck = 0L
    private const val CHECK_GAP_MS = 6 * 3_600_000L

    private val dir get() = joinPath(Platform.dataDir, "models")
    private fun modelPath(v: Int) = joinPath(dir, "$NAME-v$v.tflite")
    private val versionFile get() = joinPath(dir, "$NAME.version")

    private val _state = MutableStateFlow(State(version = installedVersion()))
    val state: StateFlow<State> = _state

    private var runner: SignatureRunner? = null
    private var loadedVersion = 0

    /** Signatures of calibration samples, by sample id (memory only; recomputed per app run). */
    private val sampleSignatures = HashMap<String, FloatArray?>()
    /** Per-racer prototype = mean of its sample signatures, by racer id. */
    private val prototypes = HashMap<String, FloatArray>()

    val isReady: Boolean get() = _state.value.ready

    private fun installedVersion(): Int = runCatching { Platform.readText(versionFile)?.trim()?.toInt() }.getOrNull()?.takeIf { Platform.exists(modelPath(it)) } ?: 0

    /** On launch / resume: fetch the newest model (throttled) and load it. */
    fun refreshInBackground() {
        scope.launch { refresh() }
    }

    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val now = Platform.nowMillis()
        if (!force && now - lastCheck < CHECK_GAP_MS && _state.value.ready) return@withLock
        lastCheck = now
        try {
            val row = fetchRow()
            if (row != null && row.version > installedVersion()) download(row)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(downloading = false, error = t.message)
        }
        load()
    }

    private class Row(val version: Int, val url: String, val sha256: String, val bytes: Int)

    private suspend fun fetchRow(): Row? {
        val platform = if (Platform.name.startsWith("iOS")) "ios" else "android"
        val client = HttpClient()
        val text = try {
            client.get("${BackendConfig.URL}/rest/v1/anylap_models?name=eq.$NAME&platform=in.($platform,all)&select=version,url,sha256,bytes,min_app_build&order=version.desc") {
                header("apikey", BackendConfig.PUBLISHABLE_KEY)
                header("Accept", "application/json")
            }.bodyAsText()
        } finally { client.close() }
        val build = BuildInfo.BUILD
        return json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
            .firstOrNull { r -> build == 0 || (r["min_app_build"]?.jsonPrimitive?.intOrNull ?: 0) <= build }
            ?.let { r ->
                Row(
                    r["version"]?.jsonPrimitive?.intOrNull ?: return null,
                    r["url"]?.jsonPrimitive?.contentOrNull ?: return null,
                    r["sha256"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null,
                    r["bytes"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
    }

    private suspend fun download(row: Row) {
        _state.value = _state.value.copy(downloading = true, error = null)
        val client = HttpClient()
        val bytes = try { client.get(row.url).readRawBytes() } finally { client.close() }
        check(bytes.size > 10_000) { "short download" }
        val hash = Sha256.hex(bytes)
        check(hash == row.sha256) { "checksum mismatch" }
        Platform.writeBytes(modelPath(row.version), bytes)
        Platform.writeText(versionFile, row.version.toString())
        // older files are not needed any more
        val old = installedVersion()
        _state.value = _state.value.copy(downloading = false, version = row.version)
        if (old != 0 && old != row.version) runCatching { Platform.delete(modelPath(old)) }
    }

    private suspend fun load() = runLock.withLock {
        val v = installedVersion()
        if (v == 0) { _state.value = _state.value.copy(ready = false, version = 0); return@withLock }
        if (v == loadedVersion && runner != null) { _state.value = _state.value.copy(ready = true, version = v); return@withLock }
        val r = SignatureRunner()
        val ok = r.load(modelPath(v))
        if (ok) {
            runner?.close()
            runner = r
            loadedVersion = v
            sampleSignatures.clear(); prototypes.clear()
            _state.value = _state.value.copy(ready = true, version = v, error = null)
        } else {
            r.close()
            _state.value = _state.value.copy(ready = false, version = v, error = "model not loadable on this device")
        }
    }

    // ------------------------------------------------------------------ signatures

    /** Signature of the subject's crop in a snapshot (the box with [trackId], else the whole picture). */
    fun signature(snap: Snapshot, trackId: Int): Pair<FloatArray, Float>? {
        val r = runner ?: return null
        val box = snap.boxes.firstOrNull { it.trackId == trackId }
        val crop = if (box != null) cropForSignature(snap.argb, snap.width, snap.height, box.l, box.t, box.r, box.b, r.inputSize)
        else cropForSignature(snap.argb, snap.width, snap.height, 0f, 0f, 1f, 1f, r.inputSize)
        val out = r.run(crop) ?: return null
        return out.signature to out.carProbability
    }

    /** Signature of a calibration picture (a BMP crop of the whole car), cached by sample id. */
    private fun sampleSignature(id: String, imagePath: String?): FloatArray? {
        if (runner == null) return null
        sampleSignatures[id]?.let { return it }
        if (sampleSignatures.containsKey(id)) return null
        val sig = imagePath?.let { p -> Platform.readBytes(p) }?.let { Bmp.decode(it) }?.let { (w, h, argb) ->
            val r = runner ?: return null
            r.run(cropForSignature(argb, w, h, 0f, 0f, 1f, 1f, r.inputSize))?.signature
        }
        sampleSignatures[id] = sig
        return sig
    }

    /**
     * Builds (or refreshes) the prototypes of these racers from their calibration passes and learned views
     * (photos are side views – a different look from the line). Call before a race from a background
     * coroutine; cheap when nothing changed. Returns how many racers have a prototype.
     */
    suspend fun prepare(racers: List<Racer>): Int = runLock.withLock {
        if (runner == null) return@withLock 0
        var n = 0
        for (racer in racers) {
            val sigs = racer.calibration
                .filter { it.kind.isPass || it.kind == SampleKind.LEARNED }
                .mapNotNull { s -> sampleSignature(s.id, s.imagePath) }
            if (sigs.isEmpty()) { prototypes.remove(racer.id); continue }
            val dim = sigs.first().size
            val mean = FloatArray(dim)
            for (s in sigs) for (i in 0 until dim) mean[i] += s[i]
            var norm = 0f
            for (i in 0 until dim) norm += mean[i] * mean[i]
            norm = kotlin.math.sqrt(norm).takeIf { it > 1e-6f } ?: 1f
            for (i in 0 until dim) mean[i] /= norm
            prototypes[racer.id] = mean
            n++
        }
        n
    }

    fun hasPrototype(racerId: String): Boolean = prototypes.containsKey(racerId)

    /**
     * The model's opinion on a crossing: nearest prototypes among [racerIds]. Null when there is no model,
     * no picture or no prototypes. Synchronous (one 64–96 px inference, a few ms on a phone).
     */
    fun verdict(e: CrossingEvent, racerIds: Collection<String>): MlVerdict? {
        if (runner == null || prototypes.isEmpty()) return null
        val snap = e.snapshot ?: return null
        val (sig, carP) = signature(snap, e.trackId) ?: return null
        val ranked = racerIds.mapNotNull { id -> prototypes[id]?.let { p -> id to signatureSimilarity(sig, p) } }
            .sortedByDescending { it.second }
        if (ranked.isEmpty()) return null
        _state.value = _state.value.copy(scored = _state.value.scored + 1)
        return MlVerdict(ranked, carP, loadedVersion)
    }
}
