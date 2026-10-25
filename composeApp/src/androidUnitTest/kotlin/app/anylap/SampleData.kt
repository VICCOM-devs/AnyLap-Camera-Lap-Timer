package app.anylap

import app.anylap.data.Lap
import app.anylap.data.LapSource
import app.anylap.data.RaceMode
import app.anylap.data.Racer
import app.anylap.data.Repository
import app.anylap.data.Session
import app.anylap.data.SessionConfig
import app.anylap.data.Sport
import kotlin.random.Random

/** Believable demo data for screenshots. */
object SampleData {
    const val SESSION_ID = "demo-session"
    private var seeded = false

    fun firstRacerId(repo: Repository) = repo.racersFor(Sport.RC).minBy { it.number }.id

    fun seed(repo: Repository) {
        if (seeded) return
        seeded = true
        val names = listOf("Red Mini-Z", "Blue Thunder", "Yellow Jacket", "Ghost")
        val racers = names.mapIndexed { i, n ->
            repo.newRacer(Sport.RC).copy(name = n, fingerprint = List(264) { 0f }, fingerprintSamples = 3 + i).also { repo.upsertRacer(it) }
        }
        listOf("Anna", "Erik").forEach { n -> repo.upsertRacer(repo.newRacer(Sport.RUNNING).copy(name = n)) }

        val now = 1_761_400_000_000L
        val rnd = Random(4)
        fun session(id: String, start: Long, base: List<Long>, laps: Int): Session {
            val all = ArrayList<Lap>()
            racers.forEachIndexed { ri, r ->
                var at = 0L
                for (n in 1..laps) {
                    val t = base[ri] + rnd.nextLong(-450, 650)
                    at += t
                    all += Lap(r.id, n, t, at, 24f + rnd.nextFloat() * 6f, LapSource.AUTO)
                }
            }
            val order = racers.sortedWith(compareByDescending<Racer> { r -> all.count { it.racerId == r.id } }
                .thenBy { r -> all.filter { it.racerId == r.id }.maxOf { it.atMs } }).map { it.id }
            return Session(
                id = id, startedAt = start,
                config = SessionConfig(sport = Sport.RC, mode = RaceMode.LAPS, targetLaps = laps, racerIds = racers.map { it.id }, cameraDistanceM = 2.5f),
                racers = racers, laps = all, durationMs = all.maxOf { it.atMs } + 2000, order = order,
            )
        }
        repo.saveSession(session(SESSION_ID, now, listOf(12_400, 12_900, 13_300, 14_100), 10))
        repo.saveSession(session("older-1", now - 86_400_000L, listOf(12_800, 13_100, 13_200, 14_500), 8))
        repo.saveSession(session("older-2", now - 3 * 86_400_000L, listOf(13_100, 13_400, 13_900, 14_900), 8))
    }
}
