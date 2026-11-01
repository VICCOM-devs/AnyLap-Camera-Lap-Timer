package app.anylap.pro

import app.anylap.cloud.BackendConfig
import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Open beta: every feature is unlocked for everyone until a date, so testers never see a paywall,
 * a plan or a price. The date comes from `anylap_config` (`open_beta`, `beta_pro_until`), read anonymously
 * on launch / resume together with the update gate and cached on disk; until the first successful read
 * the built-in [DEFAULT_UNTIL] applies, so a fresh install is open even offline. Extending the beta is one
 * SQL update – no app release.
 *
 * The server mirrors the same rule in `anylap_has_pro()` / `anylap_effective_pro()` (migration 0007).
 */
object Beta {
    /** 2027-01-31 23:59:59 UTC. Keep in step with the row in migration 0007. */
    const val DEFAULT_UNTIL: Long = 1_801_439_999_000L
    const val DEFAULT_UNTIL_LABEL = "2027-01-31"

    private val file get() = joinPath(Platform.dataDir, "beta.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    /** Epoch ms the beta ends, or 0 when the server has switched it off. */
    private val _until = MutableStateFlow(readCached() ?: DEFAULT_UNTIL)
    val until: StateFlow<Long> = _until

    /** True while every feature is open for everyone. Evaluated at call time (the date can pass mid-session). */
    val active: Boolean get() = _until.value > Platform.nowMillis()

    /** ISO date (yyyy-mm-dd) of the end of the beta, for the About line. */
    val untilLabel: String get() = isoDate(_until.value)

    fun refreshInBackground() {
        scope.launch { refresh() }
    }

    suspend fun refresh() = lock.withLock {
        val fetched = fetch() ?: return@withLock
        _until.value = fetched
        runCatching { Platform.writeText(file, fetched.toString()) }
    }

    private suspend fun fetch(): Long? = try {
        val client = HttpClient()
        val text = try {
            client.get("${BackendConfig.URL}/rest/v1/anylap_config?select=open_beta,beta_pro_until&id=eq.1") {
                header("apikey", BackendConfig.PUBLISHABLE_KEY)
                header("Accept", "application/json")
            }.bodyAsText()
        } finally { client.close() }
        val row = json.parseToJsonElement(text).jsonArray.firstOrNull()?.jsonObject ?: return null
        val open = row["open_beta"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!open) 0L
        else row["beta_pro_until"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let(::parseIso) ?: Long.MAX_VALUE
    } catch (t: Throwable) { null } // offline → keep what we have

    private fun readCached(): Long? = runCatching { Platform.readText(file)?.trim()?.toLong() }.getOrNull()

    /** Parses `2027-01-31T23:59:59+00:00` / `…Z` (what PostgREST returns for timestamptz) to epoch ms. */
    internal fun parseIso(s: String): Long? {
        val m = ISO.find(s.trim()) ?: return null
        val (y, mo, d, h, mi, sec) = m.destructured
        var t = epochDays(y.toInt(), mo.toInt(), d.toInt()) * 86_400_000L +
            (h.toLong() * 3600 + mi.toLong() * 60 + sec.toLong()) * 1000
        val off = OFFSET.find(s)?.let { r ->
            val sign = if (r.groupValues[1] == "-") -1 else 1
            sign * (r.groupValues[2].toLong() * 3600 + r.groupValues[3].toLong() * 60) * 1000
        } ?: 0L
        t -= off
        return t
    }

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})""")
    private val OFFSET = Regex("""([+-])(\d{2}):?(\d{2})$""")

    /** Days since 1970-01-01 for a civil date (proleptic Gregorian). */
    private fun epochDays(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    internal fun isoDate(ms: Long): String {
        if (ms == Long.MAX_VALUE) return ""
        var z = ms / 86_400_000L + 719_468L
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val yy = if (m <= 2) y + 1 else y
        fun two(n: Long) = if (n < 10) "0$n" else "$n"
        return "$yy-${two(m)}-${two(d)}"
    }
}
