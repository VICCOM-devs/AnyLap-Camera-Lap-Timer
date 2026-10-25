package app.anylap.pro

import app.anylap.data.Session
import app.anylap.data.SessionConfig
import app.anylap.data.Sport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitsTest {
    private fun session(i: Int) = Session(
        id = "s$i", startedAt = 1_000L * i, config = SessionConfig(),
        racers = emptyList(), laps = emptyList(), durationMs = 0, order = emptyList(),
    )

    @Test
    fun freeRacerCapNeverExceedsSportCap() {
        assertEquals(4, Limits.maxRacers(Sport.RC, Tier.FREE))
        assertEquals(4, Limits.maxRacers(Sport.RUNNING, Tier.FREE))
        assertEquals(Sport.RC.maxRacers, Limits.maxRacers(Sport.RC, Tier.PRO))
        assertEquals(Sport.RUNNING.maxRacers, Limits.maxRacers(Sport.RUNNING, Tier.PRO))
        Sport.entries.forEach { assertTrue(Limits.maxRacers(it, Tier.FREE) <= it.maxRacers) }
    }

    @Test
    fun freeShowsNewestFiveAndHidesTheRest() {
        val all = (1..8).map(::session).shuffled()
        val visible = Limits.visibleSessions(all, Tier.FREE)
        assertEquals(listOf("s8", "s7", "s6", "s5", "s4"), visible.map { it.id })
        assertEquals(3, Limits.lockedSessionCount(all, Tier.FREE))
        assertFalse(Limits.canOpenSession("s1", all, Tier.FREE))
        assertTrue(Limits.canOpenSession("s8", all, Tier.FREE))
    }

    @Test
    fun proShowsEverythingAndNothingIsDropped() {
        val all = (1..8).map(::session)
        assertEquals(8, Limits.visibleSessions(all, Tier.PRO).size)
        assertEquals(0, Limits.lockedSessionCount(all, Tier.PRO))
        assertTrue(Limits.canOpenSession("s1", all, Tier.PRO))
        // Limits only filter; the source list is untouched.
        assertEquals(8, all.size)
    }
}
