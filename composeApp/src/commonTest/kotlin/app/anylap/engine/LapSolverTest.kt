package app.anylap.engine

import app.anylap.engine.LapSolver.In
import app.anylap.engine.LapSolver.Why
import kotlin.test.Test
import kotlin.test.assertEquals

class LapSolverTest {
    private val cars = listOf("A", "B", "C")
    private fun s(x: Double) = (x * 1e9).toLong()
    private fun lap(c: String, t: Double, alt: String) = In(s(t), c, listOf(c to 0.95f, alt to 0.6f), 0.3f)
    /** Six clean laps each, A/B/C half a second apart, 18 s pace. */
    private val base = (1..6).flatMap { k -> listOf(lap("A", 18.0 * k, "B"), lap("B", 18.0 * k + 0.5, "C"), lap("C", 18.0 * k + 1.0, "A")) }
    private fun solve(xs: List<In>) = LapSolver.solve(xs, cars, 0L, 2_500_000_000L, s(200.0))
    private fun counts(o: List<LapSolver.Out>) = LapSolver.counted(o).mapValues { it.value.size }
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

    /**
     * Two cars that look the same from above (T1, T2: 0.97 / 0.97) – the camera's names are a coin toss.
     * T1 laps in 18.0 s, T2 in 18.6 s, T2 starting 1 s behind; C is a different-looking car (the field's pace).
     * Lap timing over the whole race must give every crossing to the right twin, none to the wrong one.
     */
    @Test fun lookAlikeTwinsAreSortedByTheirLapRhythm() {
        val xs = ArrayList<In>()
        for (k in 1..8) {
            val coin = (k * 7) % 3 == 0
            xs += In(s(18.0 * k), if (coin) "T2" else "T1", listOf("T1" to 0.97f, "T2" to 0.97f), 0.0f)
            xs += In(s(1.0 + 18.6 * k), if (coin) "T1" else "T2", listOf("T2" to 0.97f, "T1" to 0.96f), 0.01f)
            xs += In(s(9.0 + 18.3 * k), "C", listOf("C" to 0.95f, "T1" to 0.4f), 0.5f)
        }
        val o = LapSolver.solve(xs, listOf("T1", "T2", "C"), 0L, 2_500_000_000L, s(200.0), twins = listOf(listOf("T1", "T2")))
        val got = LapSolver.counted(o)
        assertEquals((1..8).map { s(18.0 * it) }, got["T1"])
        assertEquals((1..8).map { s(1.0 + 18.6 * it) }, got["T2"])
        assertEquals(8, got["C"]?.size)
    }
}
