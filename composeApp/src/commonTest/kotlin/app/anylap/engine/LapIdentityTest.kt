package app.anylap.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LapIdentityTest {
    private val s = 1_000_000_000L
    private fun ns(t: Double) = (t * s).toLong()
    private fun e(t: Double, vararg c: Pair<String, Float>, locked: Boolean = false) = LapIdentity.Entry(ns(t), c.toList(), locked)

    /** Clean 18 s laps: A at 1, 19 … 91 s (6 crossings), B at 10, 28 … 82 s (5). Window 10.8 s, due after 14.4 s. */
    private fun twoCars(): LapIdentity {
        val id = LapIdentity((2.5 * s).toLong())
        id.register("A"); id.register("B")
        for (k in 0 until 6) id.place("A", e(1.0 + 18 * k, "A" to 0.95f, "B" to 0.5f))
        for (k in 0 until 5) id.place("B", e(10.0 + 18 * k, "B" to 0.95f, "A" to 0.5f))
        return id
    }

    @Test
    fun weakNameOfACarThatJustCrossedIsGivenToTheCarThatIsDue() {
        val id = twoCars()
        // B's real crossing at 100 s is named A (A crossed 9 s ago); B matches almost as well and is due (18 s)
        val ch = id.place("A", e(100.0, "A" to 0.88f, "B" to 0.87f))
        assertEquals("B", ch.last().to)
        assertEquals(LapIdentity.Reason.RENAMED, ch.last().reason)
        assertEquals(6, id.crossings("B").size)
        assertEquals(6, id.crossings("A").size)
    }

    @Test
    fun phantomPieceWithNoDueCarIsParked() {
        val id = twoCars()
        // a piece named A 3 s after A crossed; B crossed 12 s ago – not due yet
        val ch = id.place("A", e(94.0, "A" to 0.9f, "B" to 0.86f))
        assertNull(ch.last().to)
        assertEquals(LapIdentity.Reason.PARKED, ch.last().reason)
        assertEquals(6, id.crossings("A").size)
    }

    @Test
    fun paceBeatsASlightlyHigherScore() {
        val id = twoCars()
        // a phantom "A" 9 s after A's on-pace crossing scores a hair higher – it still loses
        val ch = id.place("A", e(100.0, "A" to 0.96f, "B" to 0.6f))
        assertNull(ch.last().to)
        assertTrue(id.crossings("A").any { it.ns == ns(91.0) })
        // and A's next real crossing counts normally
        assertEquals("A", id.place("A", e(109.0, "A" to 0.97f)).single().to)
        assertEquals(7, id.crossings("A").size)
    }

    @Test
    fun onPaceLaterCrossingReplacesAWeakEarlyOne() {
        val id = twoCars()
        id.place("A", e(104.0, "A" to 0.80f, "B" to 0.79f))   // 13 s after A: off pace, weak
        val ch = id.place("A", e(109.0, "A" to 0.97f))       // 18 s after A: on pace, strong
        assertEquals(LapIdentity.Reason.REPLACED, ch.first().reason)
        assertTrue(id.crossings("A").any { it.ns == ns(109.0) })
        assertTrue(id.crossings("A").none { it.ns == ns(104.0) })
        assertEquals(LapIdentity.Reason.PARKED, ch.last().reason)
    }

    @Test
    fun handAssignedCrossingIsNeverMoved() {
        val id = twoCars()
        id.place("A", e(100.0, locked = true))
        val ch = id.place("A", e(101.0, "A" to 0.99f))
        assertNull(ch.last().to)
        assertEquals(1, id.crossings("A").count { it.locked })
    }

    @Test
    fun dueCarsComeFirstInSuggestions() {
        val id = twoCars()
        // at 95 s: A crossed 4 s ago (not due), B 13 s ago (not due) → order kept
        assertEquals(listOf("A", "B"), id.dueFirst(listOf("A", "B"), ns(95.0)))
        // at 98 s: B 16 s ago (due), A 7 s ago (not) → B first
        assertEquals(listOf("B", "A"), id.dueFirst(listOf("A", "B"), ns(98.0)))
    }
}
