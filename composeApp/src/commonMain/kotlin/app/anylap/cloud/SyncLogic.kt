package app.anylap.cloud

import app.anylap.data.Racer
import app.anylap.data.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure sync rules (no network), unit-tested in commonTest.
 *
 * Each record remembers the hash of the version last exchanged with the server.
 * - local hash != synced hash → changed on this phone → push.
 * - remote arrives and local is unchanged (or missing) → take remote.
 * - both changed → this phone wins and pushes again (last writer wins).
 */
@Serializable
data class SyncState(
    val userId: String? = null,
    val racerCursor: String? = null,
    val sessionCursor: String? = null,
    val racerHashes: Map<String, String> = emptyMap(),
    val sessionHashes: Map<String, String> = emptyMap(),
    /** Sessions timed by someone else that the user removed here: don't pull them again. */
    val hiddenSessions: Set<String> = emptySet(),
    /** Synced sessions that were timed on someone else's phone. */
    val foreignSessions: Set<String> = emptySet(),
    val lastSyncAt: Long? = null,
    /** Last known server entitlement, so Pro survives an offline start. */
    val cachedPro: Boolean = false,
)

object SyncLogic {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** What goes to the server for a racer: no local file paths, no per-sample calibration images. */
    fun cloudRacer(r: Racer): Racer = r.copy(photoPath = null, calibration = emptyList())

    /** What goes to the server for a session: no video, no photos, no appearance fingerprints of the racers. */
    fun cloudSession(s: Session): Session = s.copy(
        videoPath = null,
        videoOffsetMs = 0,
        racers = s.racers.map { it.copy(photoPath = null, fingerprint = emptyList(), fingerprintSamples = 0, calibration = emptyList()) },
        reviews = s.reviews.map { it.copy(imagePath = null, fingerprint = null) },
    )

    fun hash(r: Racer): String = json.encodeToString(Racer.serializer(), cloudRacer(r)).hashCode().toString(16)
    fun hash(s: Session): String = json.encodeToString(Session.serializer(), cloudSession(s)).hashCode().toString(16)

    /** Own racers (not ones borrowed from another account) that changed since the last sync. */
    fun racersToPush(local: List<Racer>, state: SyncState, me: String): List<Racer> =
        local.filter { (it.ownerUserId == null || it.ownerUserId == me) && state.racerHashes[it.id] != hash(it) }

    /** Sessions this phone timed that changed since the last sync. */
    fun sessionsToPush(local: List<Session>, state: SyncState, me: String): List<Session> =
        local.filter { (it.timedByUserId == null || it.timedByUserId == me) && state.sessionHashes[it.id] != hash(it) }

    /** Own sessions that were synced before and are gone locally → mark deleted on the server. */
    fun sessionsDeletedLocally(local: List<Session>, state: SyncState): Set<String> {
        val ids = local.map { it.id }.toSet()
        return state.sessionHashes.keys.filter { it !in ids && it !in state.hiddenSessions && it !in state.foreignSessions }.toSet()
    }

    /** Should a remote version replace (or create) the local one? */
    fun <T> acceptRemote(local: T?, localHash: String?, syncedHash: String?): Boolean =
        local == null || localHash == syncedHash

    /** Racer ids in a session that can be tagged so the session shows up in their owner's history. */
    fun taggableRacers(s: Session, ownRacerIdsOnServer: Set<String>): List<String> =
        s.racers.filter { it.id in ownRacerIdsOnServer || it.ownerUserId != null }.map { it.id }
}
