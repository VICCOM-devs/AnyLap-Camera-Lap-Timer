package app.anylap

import app.anylap.data.Lap
import app.anylap.data.LapSource
import app.anylap.data.RaceMode
import app.anylap.data.Racer
import app.anylap.data.Repository
import app.anylap.data.Session
import app.anylap.data.SessionConfig
import app.anylap.data.Sport
import java.io.File
import kotlin.random.Random

/** Believable demo data for screenshots and store images. */
object SampleData {
    const val SESSION_ID = "demo-session"
    private var seeded = false

    /** Store assets (track photo, car photos) shipped with the unit tests. */
    val storeDir: File = File("src/androidUnitTest/resources/store").absoluteFile

    fun firstRacerId(repo: Repository) = repo.racersFor(Sport.RC).first { it.number == 14 }.id

    private data class Car(val name: String, val number: Int, val color: Long, val photo: String?, val baseMs: Long)

    private val cars = listOf(
        Car("Red Rocket", 14, 0xFFE10600, "car_red.jpg", 11_950),
        Car("Blue Comet", 27, 0xFF2D7FF9, "car_blue.jpg", 12_300),
        Car("Yellow Jacket", 39, 0xFFF5B700, "car_yellow.jpg", 12_700),
        Car("Ghost", 44, 0xFFB45CFF, null, 13_400),
    )

    fun seed(repo: Repository) {
        if (seeded) return
        seeded = true
        val racers = cars.mapIndexed { i, c ->
            repo.newRacer(Sport.RC).copy(
                name = c.name, number = c.number, color = c.color,
                photoPath = c.photo?.let { File(storeDir, it).path },
                fingerprint = List(264) { 0f }, fingerprintSamples = 3 + i,
            ).also { repo.upsertRacer(it) }
        }
        listOf("Anna", "Erik").forEach { n -> repo.upsertRacer(repo.newRacer(Sport.RUNNING).copy(name = n)) }

        val day = 86_400_000L
        val now = 1_761_400_000_000L
        val rnd = Random(4)
        fun session(id: String, start: Long, improve: Long, laps: Int): Session {
            val all = ArrayList<Lap>()
            racers.forEachIndexed { ri, r ->
                var at = 0L
                for (n in 1..laps) {
                    val t = cars[ri].baseMs + improve + rnd.nextLong(-380, 560)
                    at += t
                    all += Lap(r.id, n, t, at, 24.5f + rnd.nextFloat() * 5.5f, LapSource.AUTO)
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
        // Eight sessions over three weeks, everyone slowly getting faster.
        val history = listOf(21 to 1_350L, 18 to 1_150L, 15 to 980L, 12 to 760L, 9 to 620L, 6 to 410L, 3 to 230L)
        history.forEachIndexed { i, (daysAgo, slower) ->
            repo.saveSession(session("older-$i", now - daysAgo * day, slower, 8))
        }
        repo.saveSession(featureRace(racers, now))
    }

    /** Lap times (ms) for the featured race, scripted so the charts tell a story. */
    private val featureLaps = listOf(
        // Red Rocket: sets the fastest lap (11.602) on lap 7, bobbles on lap 8, wins by 0.008 s.
        listOf(12104L, 11962, 11874, 11702, 11795, 11688, 11602, 12105, 11690, 11716),
        // Blue Comet: leads laps 1–5 and 8, loses at the line.
        listOf(11986L, 11851, 11940, 11808, 11795, 11912, 11690, 11830, 11750, 11684),
        // Yellow Jacket: drops behind Ghost on laps 2–4, takes P3 back on lap 5.
        listOf(12388L, 12512, 12298, 12455, 12410, 12366, 12482, 12301, 12436, 12392),
        // Ghost
        listOf(12450L, 12290, 12310, 12388, 12802, 12577, 12498, 12640, 12455, 12530),
    )

    /** Ten-lap race with five lead changes, won by 0.008 s. */
    private fun featureRace(racers: List<Racer>, start: Long): Session {
        val rnd = Random(11)
        val all = ArrayList<Lap>()
        racers.forEachIndexed { ri, r ->
            var at = 0L
            featureLaps[ri].forEachIndexed { i, t ->
                at += t
                // Speed at the line: quicker laps carry more speed, with a little noise.
                val kmh = 29.6f - (t - 11_600) / 260f + (rnd.nextFloat() - 0.5f) * 0.6f
                all += Lap(r.id, i + 1, t, at, kmh, LapSource.AUTO)
            }
        }
        val order = racers.sortedBy { r -> all.filter { it.racerId == r.id }.maxOf { it.atMs } }.map { it.id }
        return Session(
            id = SESSION_ID, startedAt = start,
            config = SessionConfig(sport = Sport.RC, mode = RaceMode.LAPS, targetLaps = 10, racerIds = racers.map { it.id }, cameraDistanceM = 2.5f),
            racers = racers, laps = all, durationMs = all.maxOf { it.atMs } + 2000, order = order,
        )
    }
}
