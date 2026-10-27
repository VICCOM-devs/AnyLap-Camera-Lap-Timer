package app.anylap.engine

import app.anylap.engine.LapIdentity.In
import app.anylap.engine.LapIdentity.Why
import kotlin.test.Test
import kotlin.test.assertEquals

class LapIdentityTest {
    private val cars = listOf("A", "B", "C")
    private fun s(x: Double) = (x * 1e9).toLong()
    private fun lap(c: String, t: Double, alt: String) = In(s(t), c, listOf(c to 0.95f, alt to 0.6f), 0.3f)
    /** Six clean laps each, A/B/C half a second apart, 18 s pace. */
    private val base = (1..6).flatMap { k -> listOf(lap("A", 18.0 * k, "B"), lap("B", 18.0 * k + 0.5, "C"), lap("C", 18.0 * k + 1.0, "A")) }
    private fun solve(xs: List<In>) = LapIdentity.solve(xs, cars, 0L, 2_500_000_000L, s(200.0))
    private fun counts(o: List<LapIdentity.Out>) = LapIdentity.counted(o).mapValues { it.value.size }
    private val six = mapOf("A" to 6, "B" to 6, "C" to 6)

    @Test fun wrongNameIsRenamedToTheCarThatWasDue() {
        val o = solve(base.map { if (it.named == "C" && it.ns == s(73.0)) In(it.ns, "A", listOf("A" to 0.80f, "C" to 0.78f), 0.02f) else it })
        assertEquals(six, counts(o))
        assertEquals("C", o.first { it.ns == s(73.0) }.racer)
        assertEquals(Why.RENAMED_BY_TIMING, o.first { it.ns == s(73.0) }.why)
    }

    @Test fun unnamedCrossingGoesToTheOnlyDueLookAlike() {
        val o = solve(base.map { if (it.named == "B" && it.ns == s(54.5)) In(it.ns, null, listOf("B" to 0.7f, "C" to 0.62f, "A" to 0.5f), 0f) else it })
        assertEquals(six, counts(o))
        assertEquals(Why.NAMED_BY_TIMING, o.first { it.ns == s(54.5) }.why)
    }

    @Test fun carHiddenInAnotherCarsBlobGetsItsLap() {
        val xs = base.filterNot { it.named == "C" && it.ns == s(91.0) }
            .map { if (it.named == "A" && it.ns == s(90.0)) In(s(90.9), "A", listOf("A" to 0.9f, "C" to 0.6f), 0.2f, sizeRatio = 1.9f) else it }
        val o = solve(xs)
        assertEquals(six, counts(o))
        assertEquals(1, o.count { it.why == Why.SECOND_IN_BLOB && it.racer == "C" })
    }

    @Test fun checkLineRescueCountsWhenTheCarIsDue() {
        val o = solve(base.filterNot { it.named == "B" && it.ns == s(36.5) } + In(s(36.52), null, listOf("B" to 0.8f, "A" to 0.5f), 0f, rescue = true))
        assertEquals(six, counts(o))
        assertEquals(Why.CHECK_LINE, o.first { it.ns == s(36.52) }.why)
    }

    @Test fun phantomPieceOfACarThatIsNotDueIsNotCounted() {
        val o = solve(base + In(s(58.0), "C", listOf("C" to 0.88f, "A" to 0.6f), 0.15f, split = true))
        assertEquals(six, counts(o))
        assertEquals(Why.NOT_COUNTED, o.first { it.ns == s(58.0) }.why)
    }

    @Test fun humanVerdictWins() {
        val xs = base.map { if (it.named == "A" && it.ns == s(36.0)) In(it.ns, "A", it.cands, it.margin, locked = "") else it }
        assertEquals(5, counts(solve(xs))["A"])
    }
}
