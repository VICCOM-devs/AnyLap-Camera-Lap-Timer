package app.anylap.update

import app.anylap.BuildInfo
import app.anylap.cloud.BackendConfig
import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the operating system / store does for updates (Android: Play In-App Updates + test APKs; iOS: App Store link). */
expect object UpdatePlatform {
    /** "test" (GitHub test builds), "play" (Google Play) or "appstore". */
    val channel: String
    /** Device language, e.g. "sv", "en". */
    val language: String
    /** The newer build the store offers right now, or null (none / not installed from the store). */
    suspend fun storeUpdateBuild(): Int?
    /** Starts the store's own update flow; false when it can't (then the store page is opened). */
    fun startStoreUpdate(immediate: Boolean): Boolean
    /** A background store download is finished: install it and restart. */
    fun completeStoreUpdate()
    /** Opens the system installer for a downloaded test APK. null = installer shown, else a message for the user. */
    fun installApk(path: String): String?
}

/**
 * VICCOM auto-update for AnyLap, checked on launch and on every resume:
 *  1. version gate from Supabase `anylap_app_updates` (min / latest build, force, sv/en message) – never blocks offline;
 *  2. the platform's own update: Play In-App Updates (release builds), App Store link (iOS),
 *     or for test builds the newest APK on GitHub "test-latest", downloaded and installed in the app.
 * Nothing is shown or installed while a race or calibration is running ([hold]).
 */
object Updates {
    sealed class Offer {
        abstract val build: Int
        data class TestApk(override val build: Int, val url: String, val notes: String) : Offer()
        data class Store(override val build: Int, val url: String?) : Offer()
    }

    data class State(
        val offer: Offer? = null,
        val blocking: Boolean = false,
        val message: String? = null,
        val dismissedBuild: Int = 0,
        val progress: Float? = null,   // downloading a test APK
        val storeReady: Boolean = false, // Play finished a background download
        val error: String? = null,
    ) {
        /** The nudge card: an offer the user hasn't put off for this build. */
        val nudge: Offer? get() = offer?.takeIf { !blocking && it.build > dismissedBuild }
    }

    private val _state = MutableStateFlow(State(dismissedBuild = readDismissed()))
    val state: StateFlow<State> = _state

    private val _held = MutableStateFlow(0)
    /** True while a race or calibration is on screen: no blocking screen, no restart. */
    val held: StateFlow<Int> = _held

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var lastCheck = 0L
    private const val MIN_GAP_MS = 10 * 60_000L
    private const val PLAY_URL = "https://play.google.com/store/apps/details?id=se.viccom.anylap"

    val sv: Boolean get() = UpdatePlatform.language.startsWith("sv")
    fun t(svText: String, enText: String) = if (sv) svText else enText

    /** Called on launch and on every resume (Android: MainActivity.onResume). Throttled to once per 10 min. */
    fun onResume() {
        scope.launch { refresh(force = false) }
    }

    fun hold(on: Boolean) {
        _held.update { (it + if (on) 1 else -1).coerceAtLeast(0) }
        if (!on && _held.value == 0 && _state.value.storeReady) UpdatePlatform.completeStoreUpdate()
    }

    /** Play finished downloading a flexible update. */
    fun onStoreDownloaded() {
        _state.update { it.copy(storeReady = true) }
        if (_held.value == 0) UpdatePlatform.completeStoreUpdate()
    }

    fun dismiss() {
        val b = _state.value.offer?.build ?: return
        Platform.writeText(dismissedPath, b.toString())
        _state.update { it.copy(dismissedBuild = b) }
    }

    suspend fun refresh(force: Boolean = true) = lock.withLock {
        val now = Platform.nowMillis()
        if (!force && now - lastCheck < MIN_GAP_MS) return@withLock
        lastCheck = now
        val build = BuildInfo.BUILD
        if (build == 0) return@withLock // local build: nothing to compare with
        val channel = UpdatePlatform.channel
        val gate = fetchGate(channel)
        val offer: Offer? = when (channel) {
            "test" -> TestUpdates.check()?.let { Offer.TestApk(it.build, it.apkUrl, it.notes) }
            "play" -> {
                val store = runCatching { UpdatePlatform.storeUpdateBuild() }.getOrNull()
                val latest = maxOf(store ?: 0, gate?.latest ?: 0)
                if (latest > build) Offer.Store(latest, gate?.storeUrl ?: PLAY_URL) else null
            }
            else -> gate?.takeIf { it.latest > build && it.storeUrl != null }?.let { Offer.Store(it.latest, it.storeUrl) }
        }
        val mustUpdate = gate != null && (build < gate.min || (gate.force && (offer?.build ?: 0) > build))
        val inRollout = gate == null || gate.rollout >= 100 || bucket() < gate.rollout
        val blocking = mustUpdate && offer != null // never block without a way to update
        val msg = gate?.let { if (sv) it.sv else it.en }?.takeIf { it.isNotBlank() }
        _state.update {
            it.copy(
                offer = if (blocking || inRollout) offer else null,
                blocking = blocking,
                message = msg,
                error = null,
            )
        }
    }

    /** "Update" tapped. [openUrl] opens a store page when the platform flow isn't available. */
    fun install(openUrl: (String) -> Unit) {
        when (val o = _state.value.offer ?: return) {
            is Offer.Store -> {
                if (UpdatePlatform.channel == "play" && UpdatePlatform.startStoreUpdate(immediate = _state.value.blocking)) return
                o.url?.let(openUrl)
            }
            is Offer.TestApk -> {
                if (_state.value.progress != null) return
                _state.update { it.copy(progress = 0f, error = null) }
                scope.launch {
                    val err = try {
                        val path = download(o)
                        UpdatePlatform.installApk(path)
                    } catch (e: Throwable) {
                        t("Nedladdningen misslyckades – försök igen.", "Download failed – try again.")
                    }
                    _state.update { it.copy(progress = null, error = err) }
                }
            }
        }
    }

    private suspend fun download(o: Offer.TestApk): String {
        val dir = joinPath(Platform.cacheDir, "shared")
        val path = joinPath(dir, "anylap-test-${o.build}.apk")
        val client = HttpClient()
        try {
            val bytes = client.get(o.url) {
                onDownload { sent, total ->
                    val p = if (total != null && total > 0) sent.toFloat() / total else 0f
                    _state.update { it.copy(progress = p.coerceIn(0f, 1f)) }
                }
            }.readRawBytes()
            check(bytes.size > 100_000) { "short download" }
            Platform.writeBytes(path, bytes)
        } finally { client.close() }
        return path
    }

    private class Gate(val min: Int, val latest: Int, val force: Boolean, val sv: String?, val en: String?, val storeUrl: String?, val rollout: Int)

    private suspend fun fetchGate(channel: String): Gate? = try {
        val platform = if (channel == "appstore") "ios" else "android"
        val client = HttpClient()
        val text = try {
            client.get("${BackendConfig.URL}/rest/v1/anylap_app_updates?platform=eq.$platform&channel=eq.$channel&select=*") {
                header("apikey", BackendConfig.PUBLISHABLE_KEY)
                header("Accept", "application/json")
            }.bodyAsText()
        } finally { client.close() }
        val row = Json.parseToJsonElement(text).jsonArray.firstOrNull()?.jsonObject
        row?.let {
            Gate(
                min = it.int("min_version_code"), latest = it.int("latest_version_code"),
                force = it["force"]?.jsonPrimitive?.booleanOrNull == true,
                sv = it.str("message_sv"), en = it.str("message_en"), storeUrl = it.str("store_url"),
                rollout = it["rollout_percent"]?.jsonPrimitive?.intOrNull ?: 100,
            )
        }
    } catch (t: Throwable) { null } // offline, table missing, … → never block

    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull ?: 0
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.takeIf { it.isString }?.content

    /** Stable 0–99 bucket per install for staged rollouts. */
    private fun bucket(): Int {
        val p = joinPath(Platform.dataDir, "install.id")
        val id = Platform.readText(p) ?: (Platform.nowMillis().toString() + (0..999999).random()).also { Platform.writeText(p, it) }
        var h = 0
        for (c in id) h = h * 31 + c.code
        return ((h % 100) + 100) % 100
    }

    private val dismissedPath get() = joinPath(Platform.dataDir, "update.dismissed")
    private fun readDismissed() = runCatching { Platform.readText(dismissedPath)?.trim()?.toInt() }.getOrNull() ?: 0
}
