package app.anylap.cloud

import app.anylap.i18n.tr
import app.anylap.data.Racer
import app.anylap.data.Repository
import app.anylap.data.Session
import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** AnyLap's Supabase backend (shared with psst; all tables are anylap_*). Publishable key only. */
object BackendConfig {
    const val URL = "https://ecdkjmvfjusznvggzvfs.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_luYMNaJjBpzYM2fhzmBMZw_xPcR_hon"

    /** Public spectator page (VICCOM_Site/anylap/live/). */
    const val LIVE_PAGE = "https://viccom.se/anylap/live/"
    fun liveUrl(code: String) = "$LIVE_PAGE?c=$code"
}

sealed interface Account {
    data object Loading : Account
    data object SignedOut : Account

    /** Signed in without an email: works on this phone only until an email is added. */
    data class Guest(val id: String) : Account

    data class WithEmail(val id: String, val email: String) : Account

    val userId: String? get() = when (this) {
        is Guest -> id
        is WithEmail -> id
        else -> null
    }
}

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
)

@Serializable
private data class RacerRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val data: JsonElement,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
private data class SessionRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val data: JsonElement,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/**
 * Accounts and cloud sync. Offline-first: the local [Repository] stays the source the UI reads;
 * this pushes local changes and pulls changes from the user's other phones (and sessions where
 * a friend timed one of the user's racers).
 *
 * The Supabase client is created lazily so nothing touches the network (or platform settings)
 * until the user has turned sync on.
 */
@OptIn(FlowPreview::class, ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)
class Cloud(private val repo: Repository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val json = SyncLogic.json
    private val stateFile = joinPath(Platform.dataDir, "sync.json")

    private var state: SyncState = Platform.readText(stateFile)?.let {
        try { json.decodeFromString(SyncState.serializer(), it) } catch (e: Exception) { null }
    } ?: SyncState()

    /** Supplied by AppState: is the user Pro right now (sync is a Pro feature). */
    var isPro: () -> Boolean = { false }

    private val _account = MutableStateFlow<Account>(if (state.userId != null) Account.Loading else Account.SignedOut)
    val account: StateFlow<Account> = _account

    private val _serverPro = MutableStateFlow(state.cachedPro)
    /** An active store purchase / grant on the server. */
    val serverPro: StateFlow<Boolean> = _serverPro

    private val _status = MutableStateFlow(SyncStatus(lastSyncAt = state.lastSyncAt))
    val status: StateFlow<SyncStatus> = _status

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName

    private var started = false
    private val client: SupabaseClient by lazy {
        createSupabaseClient(BackendConfig.URL, BackendConfig.PUBLISHABLE_KEY) {
            install(Auth)
            install(Postgrest)
        }
    }

    /** Call once at app start. Connects only if the user has signed in before. */
    fun start() {
        if (state.userId != null) connect()
    }

    private fun connect() {
        if (started) return
        started = true
        scope.launch {
            client.auth.sessionStatus.collect { st ->
                _account.value = when (st) {
                    is SessionStatus.Authenticated -> {
                        val u = st.session.user
                        val id = u?.id ?: return@collect
                        val email = u.email
                        if (email.isNullOrBlank()) Account.Guest(id) else Account.WithEmail(id, email)
                    }
                    is SessionStatus.NotAuthenticated -> Account.SignedOut
                    SessionStatus.Initializing -> Account.Loading
                    is SessionStatus.RefreshFailure -> _account.value // offline: keep last known
                }
                val uid = _account.value.userId
                if (uid != null) {
                    if (state.userId == null) saveState(state.copy(userId = uid))
                    refreshAccountInfo()
                    syncNow()
                }
            }
        }
        // Push local changes a few seconds after they happen.
        scope.launch {
            combine(repo.sessions, repo.racers) { _, _ -> }.drop(1).debounce(4_000).collect { syncNow() }
        }
    }

    // ------------------------------------------------------------------ account

    /** First step: a guest account (no email) so sync works immediately. */
    suspend fun startGuest(): Result<Unit> = runCatching {
        connect()
        if (client.auth.currentUserOrNull() == null) client.auth.signInAnonymously()
    }

    /**
     * Sends a 6-digit code. For a guest this adds the email to the current account;
     * if the email already has an account (e.g. from another phone) it signs into that one.
     * @return true if the code links the email to this account, false if it signs into another.
     */
    suspend fun requestEmailCode(email: String): Result<Boolean> = runCatching {
        connect()
        val current = client.auth.currentUserOrNull()
        if (current != null && current.email.isNullOrBlank()) {
            try {
                client.auth.updateUser { this.email = email.trim() }
                return@runCatching true
            } catch (e: Exception) {
                // Most likely the email already belongs to an account: sign into that one instead.
            }
        }
        client.auth.signInWith(OTP) {
            this.email = email.trim()
            createUser = true
        }
        false
    }

    suspend fun verifyEmailCode(email: String, code: String, linking: Boolean): Result<Unit> = runCatching {
        client.auth.verifyEmailOtp(
            type = if (linking) OtpType.Email.EMAIL_CHANGE else OtpType.Email.EMAIL,
            email = email.trim(),
            token = code.trim(),
        )
        refreshAccountInfo()
    }

    suspend fun setDisplayName(name: String): Result<Unit> = runCatching {
        val clean = name.trim().take(40)
        val me = _account.value.userId ?: error("Not signed in")
        client.from("anylap_profiles").upsert(JsonArray(listOf(buildJsonObject { put("user_id", me); put("display_name", clean) }))) {
            onConflict = "user_id"
        }
        _displayName.value = clean
    }

    suspend fun signOut(): Result<Unit> = runCatching {
        client.auth.signOut()
        _serverPro.value = false
        saveState(SyncState())
    }

    private suspend fun refreshAccountInfo() {
        try {
            val pro = client.postgrest.rpc("anylap_has_entitlement").data.trim() == "true"
            _serverPro.value = pro
            val rows = client.from("anylap_profiles").select().data
            val name = json.parseToJsonElement(rows).let { it as? JsonArray }?.firstOrNull()
                ?.jsonObject?.get("display_name")?.jsonPrimitive?.content
            _displayName.value = name.orEmpty()
            saveState(state.copy(cachedPro = pro))
        } catch (e: Exception) {
            println("AnyLap cloud: account info failed: $e")
        }
    }

    // ------------------------------------------------------------------ racers shared between accounts

    /** A 6-character code (valid 24 h) a friend enters to time this racer on their phone. */
    suspend fun shareRacer(racer: Racer): Result<String> = runCatching {
        syncNow() // the racer has to be on the server first
        val res = client.postgrest.rpc("anylap_share_racer", buildJsonObject { put("p_racer", racer.id) })
        json.decodeFromString<String>(res.data)
    }

    /** Adds a friend's racer from a share code. Their sessions with you show up in their history. */
    suspend fun claimRacer(code: String): Result<Racer> = runCatching {
        val res = client.postgrest.rpc("anylap_claim_racer", buildJsonObject { put("p_code", code.trim().uppercase()) })
        val obj = json.parseToJsonElement(res.data).jsonObject
        val racer = json.decodeFromJsonElement(Racer.serializer(), obj.getValue("data"))
        val owner = obj["owner"]?.jsonPrimitive?.content
        val me = _account.value.userId
        val name = obj["owner_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val local = if (owner == me) racer else racer.copy(ownerUserId = owner, ownerName = name)
        repo.racer(racer.id).let { mine -> repo.upsertRacer(local.copy(photoPath = mine?.photoPath, calibration = mine?.calibration ?: emptyList())) }
        local
    }

    // ------------------------------------------------------------------ live timing board

    /**
     * Opens a live session and returns its 6-character code. Any tier: a guest account is made when
     * nobody is signed in (no email needed). [pro] = this phone's plan (counts while the beta is open).
     */
    suspend fun startLive(pro: Boolean): Result<String> = runCatching {
        if (client.auth.currentUserOrNull() == null) startGuest().getOrThrow()
        json.decodeFromString<String>(client.postgrest.rpc("anylap_live_start", buildJsonObject { put("p_pro", pro) }).data)
    }

    /** A random id for this phone as a viewer (kept, so re-joining doesn't take a second seat). */
    val deviceId: String by lazy {
        val p = joinPath(Platform.dataDir, "device.id")
        Platform.readText(p)?.trim()?.takeIf { it.length >= 8 } ?: run {
            val id = kotlin.uuid.Uuid.random().toString()
            Platform.writeText(p, id); id
        }
    }

    /**
     * Join / keep watching a live session (call about once a second). No sign-in needed. The server
     * returns only what this phone may see: {"error":"gone"|"full"} or the timing snapshot.
     */
    suspend fun joinLive(code: String, pro: Boolean): Result<kotlinx.serialization.json.JsonObject> = runCatching {
        val res = client.postgrest.rpc("anylap_live_join", buildJsonObject {
            put("p_code", code.trim().uppercase()); put("p_device", deviceId); put("p_pro", pro)
        })
        json.parseToJsonElement(res.data).jsonObject
    }

    /** How many phones watch the camera's session right now (seen in the last 30 s). */
    suspend fun liveViewers(code: String): Int = try {
        val res = client.from("anylap_live_viewers").select { filter { eq("code", code) } }
        val cutoff = Platform.nowMillis() - 30_000
        json.parseToJsonElement(res.data).let { it as JsonArray }.count { v ->
            val t = v.jsonObject["last_seen"]?.jsonPrimitive?.content ?: return@count false
            (runCatching { Instant.parse(t).toEpochMilliseconds() }.getOrNull() ?: 0L) > cutoff
        }
    } catch (e: Exception) { 0 }

    /** Replaces the board's snapshot. Errors are swallowed: a missed update is fixed by the next one. */
    suspend fun pushLive(code: String, snapshot: JsonElement) {
        try {
            client.from("anylap_live").update(buildJsonObject { put("snapshot", snapshot) }) {
                filter { eq("code", code) }
            }
        } catch (e: Exception) {
            println("AnyLap cloud: live update failed: $e")
        }
    }

    suspend fun stopLive(code: String) {
        try {
            client.from("anylap_live").delete { filter { eq("code", code) } }
        } catch (e: Exception) {
            println("AnyLap cloud: live stop failed: $e")
        }
    }

    // ------------------------------------------------------------------ GDPR

    /** Everything stored about the user on the server, as a JSON file path ready to share. */
    suspend fun exportData(): Result<String> = runCatching {
        val res = client.postgrest.rpc("anylap_export_my_data")
        val path = joinPath(Platform.cacheDir, "anylap-my-data.json")
        Platform.writeText(path, res.data)
        path
    }

    /** Removes the user's AnyLap data from the server and signs out. Local data on this phone stays. */
    suspend fun deleteCloudData(): Result<Unit> = runCatching {
        client.postgrest.rpc("anylap_delete_my_data")
        client.auth.signOut()
        _serverPro.value = false
        saveState(SyncState())
    }

    // ------------------------------------------------------------------ sync

    fun syncSoon() {
        scope.launch { syncNow() }
    }

    suspend fun syncNow() {
        val me = _account.value.userId ?: return
        if (!isPro()) return
        lock.withLock {
            _status.value = _status.value.copy(running = true, error = null)
            try {
                if (state.userId != me) state = SyncState(userId = me) // other account → full resync
                pushRacers(me)
                pullRacers(me)
                pushSessions(me)
                pullSessions(me)
                state = state.copy(lastSyncAt = Platform.nowMillis())
                saveState(state)
                _status.value = SyncStatus(running = false, lastSyncAt = state.lastSyncAt)
            } catch (e: Exception) {
                println("AnyLap cloud: sync failed: $e")
                saveState(state)
                _status.value = _status.value.copy(running = false, error = friendly(e))
            }
        }
    }

    private suspend fun pushRacers(me: String) {
        val local = repo.racers.value
        val changed = SyncLogic.racersToPush(local, state, me)
        val deleted = state.racerHashes.keys - local.map { it.id }.toSet()
        if (changed.isNotEmpty()) {
            val rows = changed.map { r ->
                buildJsonObject {
                    put("id", r.id)
                    put("sport", r.sport.name)
                    put("data", json.encodeToJsonElement(Racer.serializer(), SyncLogic.cloudRacer(r)))
                    put("deleted_at", null as String?)
                }
            }
            client.from("anylap_racers").upsert(JsonArray(rows)) { onConflict = "id" }
        }
        if (deleted.isNotEmpty()) {
            client.from("anylap_racers").update(buildJsonObject { put("deleted_at", nowIso()) }) {
                filter { isIn("id", deleted.toList()) }
            }
        }
        state = state.copy(racerHashes = state.racerHashes - deleted + changed.associate { it.id to SyncLogic.hash(it) })
    }

    private suspend fun pullRacers(me: String) {
        val rows = client.from("anylap_racers").select {
            state.racerCursor?.let { c -> filter { gt("updated_at", c) } }
            order("updated_at", Order.ASCENDING)
            limit(500)
        }.decodeList<RacerRow>()
        var hashes = state.racerHashes
        for (row in rows) {
            val local = repo.racer(row.id)
            val localHash = local?.let { SyncLogic.hash(it) }
            if (row.deletedAt != null) {
                if (local != null && localHash == hashes[row.id]) repo.deleteRacer(row.id)
                hashes = hashes - row.id
                continue
            }
            val remote = json.decodeFromJsonElement(Racer.serializer(), row.data)
            if (SyncLogic.acceptRemote(local, localHash, hashes[row.id])) {
                val merged = remote.copy(photoPath = local?.photoPath, calibration = local?.calibration ?: emptyList())
                repo.upsertRacer(merged)
                hashes = hashes + (row.id to SyncLogic.hash(merged))
            }
        }
        state = state.copy(racerHashes = hashes, racerCursor = rows.lastOrNull()?.updatedAt ?: state.racerCursor)
    }

    private suspend fun pushSessions(me: String) {
        val local = repo.sessions.value
        val changed = SyncLogic.sessionsToPush(local, state, me)
        val deleted = SyncLogic.sessionsDeletedLocally(local, state)
        val foreignRemoved = state.sessionHashes.keys.filter { id -> id in state.foreignSessions && local.none { it.id == id } }
        val name = _displayName.value.ifBlank { null }
        if (changed.isNotEmpty()) {
            val rows = changed.map { s ->
                buildJsonObject {
                    put("id", s.id)
                    put("sport", s.config.sport.name)
                    put("started_at", Instant.fromEpochMilliseconds(s.startedAt).toString())
                    put("data", json.encodeToJsonElement(Session.serializer(), SyncLogic.cloudSession(s).copy(timedByName = name)))
                    put("deleted_at", null as String?)
                }
            }
            client.from("anylap_sessions").upsert(JsonArray(rows)) { onConflict = "id" }
            // Tag racers so each session also lands in their owners' history.
            val ownOnServer = state.racerHashes.keys
            for (s in changed) {
                val tags = SyncLogic.taggableRacers(s, ownOnServer)
                if (tags.isEmpty()) continue
                try {
                    client.from("anylap_session_racers").upsert(
                        JsonArray(tags.map { rid -> buildJsonObject { put("session_id", s.id); put("racer_id", rid); put("racer_owner", me) } }),
                    ) {
                        onConflict = "session_id,racer_id"
                        ignoreDuplicates = true
                    }
                } catch (e: Exception) {
                    println("AnyLap cloud: tagging ${s.id} failed: $e")
                }
            }
        }
        if (deleted.isNotEmpty()) {
            client.from("anylap_sessions").update(buildJsonObject { put("deleted_at", nowIso()) }) {
                filter { isIn("id", deleted.toList()) }
            }
        }
        state = state.copy(
            sessionHashes = state.sessionHashes - deleted - foreignRemoved.toSet() + changed.associate { it.id to SyncLogic.hash(it) },
            hiddenSessions = state.hiddenSessions + foreignRemoved,
            foreignSessions = state.foreignSessions - foreignRemoved.toSet(),
        )
    }

    private suspend fun pullSessions(me: String) {
        val rows = client.from("anylap_sessions").select {
            state.sessionCursor?.let { c -> filter { gt("updated_at", c) } }
            order("updated_at", Order.ASCENDING)
            limit(500)
        }.decodeList<SessionRow>()
        var hashes = state.sessionHashes
        var foreign = state.foreignSessions
        for (row in rows) {
            if (row.id in state.hiddenSessions) continue
            val local = repo.session(row.id)
            val localHash = local?.let { SyncLogic.hash(it) }
            if (row.deletedAt != null) {
                if (local != null && localHash == hashes[row.id]) repo.deleteSession(row.id)
                hashes = hashes - row.id
                continue
            }
            val remote = json.decodeFromJsonElement(Session.serializer(), row.data)
            if (!SyncLogic.acceptRemote(local, localHash, hashes[row.id])) continue
            val isMine = row.userId == me
            val merged = remote.copy(
                videoPath = local?.videoPath,
                videoOffsetMs = local?.videoOffsetMs ?: 0,
                timedByUserId = if (isMine) null else row.userId,
                timedByName = if (isMine) null else remote.timedByName,
            )
            repo.saveSession(merged)
            hashes = hashes + (row.id to SyncLogic.hash(merged))
            if (!isMine) foreign = foreign + row.id
        }
        state = state.copy(sessionHashes = hashes, foreignSessions = foreign, sessionCursor = rows.lastOrNull()?.updatedAt ?: state.sessionCursor)
    }

    // ------------------------------------------------------------------ helpers

    private fun nowIso(): String = Instant.fromEpochMilliseconds(Platform.nowMillis()).toString()

    private fun saveState(s: SyncState) {
        state = s
        try {
            Platform.writeText(stateFile, json.encodeToString(SyncState.serializer(), s))
        } catch (e: Exception) {
            println("AnyLap cloud: could not save sync state: $e")
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            "row-level security" in m || "42501" in m -> tr("Sync needs AnyLap Pro on this account.")
            "timeout" in m.lowercase() || "Unable to resolve" in m || "failed to connect" in m.lowercase() -> tr("Offline – will sync when you're back online.")
            else -> tr("Sync failed. It will retry automatically.")
        }
    }
}

/** Shown under results/sessions timed on someone else's phone. */
fun Session.timedByLabel(): String? = timedByUserId?.let { tr("Timed by {0}", timedByName ?: tr("a friend")) }
