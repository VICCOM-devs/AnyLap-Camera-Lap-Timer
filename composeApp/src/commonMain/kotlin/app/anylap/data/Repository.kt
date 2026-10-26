package app.anylap.data

import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import app.anylap.events.Event
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tiny JSON-file store. Everything lives in memory as StateFlows and is written
 * to disk in the background on every change. Works offline, same on Android and iOS.
 */
class Repository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeLock = Mutex()

    private val racersFile = joinPath(Platform.dataDir, "racers.json")
    private val sessionsFile = joinPath(Platform.dataDir, "sessions.json")
    private val settingsFile = joinPath(Platform.dataDir, "settings.json")
    private val eventsFile = joinPath(Platform.dataDir, "events.json")

    private val _racers = MutableStateFlow(load(racersFile, ListSerializer(Racer.serializer())) ?: emptyList())
    val racers: StateFlow<List<Racer>> = _racers

    private val _sessions = MutableStateFlow(
        (load(sessionsFile, ListSerializer(Session.serializer())) ?: emptyList()).sortedByDescending { it.startedAt },
    )
    val sessions: StateFlow<List<Session>> = _sessions

    private val _settings = MutableStateFlow(load(settingsFile, AppSettings.serializer()) ?: AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    // ---------------------------------------------------------------- racers

    fun racer(id: String): Racer? = _racers.value.firstOrNull { it.id == id }

    fun racersFor(sport: Sport): List<Racer> = _racers.value.filter { it.sport == sport }

    @OptIn(ExperimentalUuidApi::class)
    fun newRacer(sport: Sport): Racer {
        val existing = racersFor(sport)
        val usedColors = existing.map { it.color }.toSet()
        val color = RacerPalette.firstOrNull { it !in usedColors } ?: RacerPalette[existing.size % RacerPalette.size]
        val number = (existing.maxOfOrNull { it.number } ?: 0) + 1
        val markerId = if (sport == Sport.RUNNING) nextMarkerId() else null
        val name = if (sport == Sport.RC) "Car $number" else "Runner $number"
        return Racer(
            id = Uuid.random().toString(),
            name = name,
            sport = sport,
            number = number,
            color = color,
            markerId = markerId,
            createdAt = Platform.nowMillis(),
        )
    }

    private fun nextMarkerId(): Int {
        val used = _racers.value.mapNotNull { it.markerId }.toSet()
        return (0 until 50).firstOrNull { it !in used } ?: 0
    }

    fun upsertRacer(racer: Racer) {
        val list = _racers.value.toMutableList()
        val i = list.indexOfFirst { it.id == racer.id }
        if (i >= 0) list[i] = racer else list += racer
        _racers.value = list
        persist(racersFile, ListSerializer(Racer.serializer())) { _racers.value }
    }

    fun deleteRacer(id: String) {
        racer(id)?.photoPath?.let { Platform.delete(it) }
        _racers.value = _racers.value.filterNot { it.id == id }
        persist(racersFile, ListSerializer(Racer.serializer())) { _racers.value }
    }

    // ---------------------------------------------------------------- sessions

    fun session(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    fun saveSession(session: Session) {
        _sessions.value = (_sessions.value.filterNot { it.id == session.id } + session).sortedByDescending { it.startedAt }
        persist(sessionsFile, ListSerializer(Session.serializer())) { _sessions.value }
    }

    fun deleteSession(id: String) {
        session(id)?.videoPath?.let { Platform.delete(it) }
        _sessions.value = _sessions.value.filterNot { it.id == id }
        persist(sessionsFile, ListSerializer(Session.serializer())) { _sessions.value }
    }

    // ---------------------------------------------------------------- events

    private val _events = MutableStateFlow(
        (load(eventsFile, ListSerializer(Event.serializer())) ?: emptyList()).sortedByDescending { it.createdAt },
    )
    val events: StateFlow<List<Event>> = _events

    fun event(id: String): Event? = _events.value.firstOrNull { it.id == id }

    fun upsertEvent(event: Event) {
        _events.value = (_events.value.filterNot { it.id == event.id } + event).sortedByDescending { it.createdAt }
        persist(eventsFile, ListSerializer(Event.serializer())) { _events.value }
    }

    fun deleteEvent(id: String) {
        _events.value = _events.value.filterNot { it.id == id }
        persist(eventsFile, ListSerializer(Event.serializer())) { _events.value }
    }

    /** Record that [sessionId] is the result of a round. */
    fun linkRound(eventId: String, roundId: String, sessionId: String) {
        val e = event(eventId) ?: return
        upsertEvent(e.copy(rounds = e.rounds.map { if (it.id == roundId) it.copy(sessionId = sessionId) else it }))
    }

    // ---------------------------------------------------------------- settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
        persist(settingsFile, AppSettings.serializer()) { _settings.value }
    }

    // ---------------------------------------------------------------- io

    private fun <T> load(path: String, serializer: KSerializer<T>): T? {
        val text = Platform.readText(path) ?: return null
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            Platform.writeText("$path.broken", text)
            null
        }
    }

    /** Writes the *latest* value (read inside the lock), so out-of-order launches can't save stale data. */
    private fun <T> persist(path: String, serializer: KSerializer<T>, current: () -> T) {
        scope.launch {
            writeLock.withLock {
                try {
                    Platform.writeText(path, json.encodeToString(serializer, current()))
                } catch (e: Exception) {
                    println("AnyLap: could not write $path: $e")
                }
            }
        }
    }
}
