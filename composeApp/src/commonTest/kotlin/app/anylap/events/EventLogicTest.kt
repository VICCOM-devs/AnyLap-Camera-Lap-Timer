package app.anylap.events

import app.anylap.data.Lap
import app.anylap.data.Session
import app.anylap.data.SessionConfig
import app.anylap.data.Sport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventLogicTest {
    private val ids = listOf("a", "b", "c", "d", "e", "f", "g")

    private fun session(id: String, order: List<String>, best: Map<String, Long> = emptyMap()) = Session(
        id = id, startedAt = 0, config = SessionConfig(), racers = emptyList(),
        laps = best.map { (r, ms) -> Lap(r, 1, ms, ms) }, durationMs = 0, order = order,
    )

    @Test
    fun heatsAreEvenAndNeverOverTheCap() {
        val h = EventLogic.heats(ids, maxPerHeat = 4)
        assertEquals(2, h.size)
        assertEquals(listOf(4, 3), h.map { it.size })
        assertEquals(ids.toSet(), h.flatten().toSet())
        assertTrue(EventLogic.heats(ids, 4, rounds = 3).all { it.size <= 4 })
    }

    @Test
    fun everyRacerGetsOneHeatPerRound() {
        val h = EventLogic.heats(ids, 4, rounds = 2)
        assertEquals(4, h.size)
        ids.forEach { id -> assertEquals(2, h.count { id in it }) }
        // Second round mixes the groups differently.
        assertTrue(h[0].toSet() != h[2].toSet())
    }

    @Test
    fun pointsTableAndFinalDoublePoints() {
        val e = Event(
            id = "e", name = "t", sport = Sport.RC, createdAt = 0, racerIds = listOf("a", "b", "c"),
            rounds = listOf(
                Round("h1", "Heat 1", RoundKind.HEAT, listOf("a", "b", "c"), sessionId = "s1"),
                Round("f", "Final", RoundKind.FINAL, listOf("a", "b"), sessionId = "s2"),
            ),
        )
        val sessions = listOf(session("s1", listOf("a", "b", "c")), session("s2", listOf("b", "a")))
        val st = EventLogic.standings(e, sessions)
        // a: 10 + 8*2 = 26, b: 8 + 10*2 = 28, c: 6
        assertEquals(listOf("b" to 28, "a" to 26, "c" to 6), st.map { it.racerId to it.points })
        assertTrue(e.finished)
        assertNull(EventLogic.nextRound(e))
    }

    @Test
    fun tiesBreakOnWinsThenBestLap() {
        val e = Event(
            id = "e", name = "t", sport = Sport.RC, createdAt = 0, racerIds = listOf("a", "b"),
            rounds = listOf(
                Round("1", "Heat 1", RoundKind.HEAT, listOf("a", "b"), sessionId = "s1"),
                Round("2", "Heat 2", RoundKind.HEAT, listOf("a", "b"), sessionId = "s2"),
            ),
        )
        val sessions = listOf(
            session("s1", listOf("a", "b"), mapOf("a" to 5000, "b" to 4000)),
            session("s2", listOf("b", "a"), mapOf("a" to 5000, "b" to 4500)),
        )
        // 18 points and 1 win each → b has the faster best lap.
        assertEquals(listOf("b", "a"), EventLogic.standings(e, sessions).map { it.racerId })
    }

    @Test
    fun finalGridIsTopOfHeatTable() {
        val e = Event(
            id = "e", name = "t", sport = Sport.RC, createdAt = 0, racerIds = listOf("a", "b", "c", "d"),
            rounds = listOf(
                Round("1", "Heat 1", RoundKind.HEAT, listOf("a", "b"), sessionId = "s1"),
                Round("2", "Heat 2", RoundKind.HEAT, listOf("c", "d"), sessionId = "s2"),
                Round("3", "Heat 3", RoundKind.HEAT, listOf("a", "d")),
            ),
        )
        val sessions = listOf(session("s1", listOf("b", "a")), session("s2", listOf("d", "c")))
        assertEquals("3", EventLogic.nextRound(e)?.id)
        assertEquals(listOf("b", "d"), EventLogic.finalGrid(e, sessions, 2))
    }
}
