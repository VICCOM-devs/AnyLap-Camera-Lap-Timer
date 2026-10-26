package app.anylap.vision

import app.anylap.vision.SyntheticTrack.BAD
import app.anylap.vision.SyntheticTrack.Car
import app.anylap.vision.SyntheticTrack.GOOD
import app.anylap.vision.SyntheticTrack.Renderer
import app.anylap.vision.SyntheticTrack.WORST
import app.anylap.vision.SyntheticTrack.calibrate
import app.anylap.vision.SyntheticTrack.lane
import app.anylap.vision.SyntheticTrack.passesFor
import app.anylap.vision.SyntheticTrack.race
import app.anylap.vision.SyntheticTrack.rgb
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Engine bench on [SyntheticTrack]: bad light, hard shadows, motion blur, look-alike cars, close
 * calls. The quick tests guard against regressions; [fullBench] prints the whole table and is
 * ignored by default (several minutes).
 *
 * Reference (Oct 26 2026, PR "engine: shadows, exposure-relative fingerprint, track adoption"):
 *   good, 3 distinct cars, 5 laps              15/15 found, 0 false, identity 15/0/0, timing 0.5 ms
 *   bad, white+silver+red+blue + close calls   23/24, 6 false, identity 22/1/0, timing 3.3 ms
 *   bad, 10 identical white cars, 10 tags      30/30, 0 false, identity 30/0/0, timing 1.7 ms
 *   worst (1/120 s), same 10                   30/30, 0 false, identity 30/0/0, timing 3.7 ms
 * The remaining error in every scenario is the side-by-side pass (one merged blob): one car is
 * missed and the other gets the wrong identity – blob separation is the next engine task.
 */
class EngineBenchTest {

    private val white = Car("white", rgb(235, 235, 235))
    private val silver = Car("silver", rgb(190, 192, 198))
    private val red = Car("red", rgb(225, 20, 20))
    private val blue = Car("blue", rgb(30, 60, 220))
    private val green = Car("green", rgb(30, 190, 60))

    private val pink = rgb(255, 40, 160)
    private val orange = rgb(255, 120, 0)
    private val yellow = rgb(250, 230, 20)
    private val lime = rgb(120, 250, 40)
    private val cyan = rgb(0, 220, 230)
    private val blueTag = rgb(20, 80, 255)

    /** The 10-tag scheme: 6 singles, 3 complementary pairs, red as the 10th. */
    private val tagSet = listOf(
        listOf(pink), listOf(orange), listOf(yellow), listOf(lime), listOf(cyan), listOf(blueTag),
        listOf(pink, lime), listOf(orange, cyan), listOf(yellow, blueTag), listOf(rgb(255, 0, 0)),
    )

    private val quickSpeeds = listOf(1.2, 4.0, 5.0)

    @Test
    fun shadowsAndDimLightDoNotInventCrossings() {
        val cars = listOf(white, silver, red, blue)
        val r = Renderer(cond = BAD, seed = 21, subSamples = 4)
        val racers = cars.mapIndexed { i, c -> calibrate(c, r, lane(i), 100.0 * (i + 1), quickSpeeds, margin = 1.0) }
        val passes = passesFor(cars, laps = 1, closeCalls = true, lapTimeS = 8.0)
        val s = race(r, racers, passes)
        println("bad light + shadows + close calls: $s\n  " + s.notes.joinToString("\n  "))
        // 12 passes; the side-by-side pair merges into one blob (1 missed, 1 wrong) – allowed for now
        assertTrue(s.detected >= s.passes - 1, "detected ${s.detected} of ${s.passes}")
        assertTrue(s.falseExtra <= 3, "false crossings: ${s.falseExtra}")
        assertTrue(s.idWrong <= 1, "wrong identities: ${s.idWrong}")
        assertTrue(s.idCorrect >= s.passes - 2, "correct identities: ${s.idCorrect} of ${s.passes}")
        assertTrue(s.timingMeanMs < 8.0, "timing error ${s.timingMeanMs} ms")
    }

    @Test
    fun colourTagsTellIdenticalCarsApartInDimLight() {
        val cars = tagSet.mapIndexed { i, t -> Car("w${i + 1}", white.body, t) }
        val r = Renderer(cond = BAD, seed = 22, subSamples = 4)
        val racers = cars.mapIndexed { i, c -> calibrate(c, r, lane(i % 5), 100.0 * (i + 1), quickSpeeds, margin = 1.0) }
        val passes = passesFor(cars, laps = 1, closeCalls = false)
        val s = race(r, racers, passes)
        println("10 identical white cars with tags: $s\n  " + s.notes.joinToString("\n  "))
        assertTrue(s.detected == s.passes, "detected ${s.detected} of ${s.passes}")
        assertTrue(s.falseExtra <= 1, "false crossings: ${s.falseExtra}")
        assertTrue(s.idWrong == 0, "wrong identities: ${s.idWrong}")
        assertTrue(s.idCorrect >= s.passes - 1, "correct identities: ${s.idCorrect} of ${s.passes}")
    }

    @Ignore
    @Test
    fun fullBench() {
        fun scenario(name: String, cond: SyntheticTrack.Conditions, cars: List<Car>, laps: Int, closeCalls: Boolean) {
            val r = Renderer(cond = cond, seed = name.hashCode())
            val racers = cars.mapIndexed { i, c -> calibrate(c, r, lane(i % 5), 100.0 * (i + 1)) }
            val s = race(r, racers, passesFor(cars, laps, closeCalls))
            println("== $name\n$s\n  " + s.notes.joinToString("\n  "))
        }
        val tenWhite = tagSet.mapIndexed { i, t -> Car("w${i + 1}", white.body, t) }
        scenario("A good, 3 distinct cars, 5 laps", GOOD, listOf(red, blue, green), 5, false)
        scenario("B bad, white+silver+red+blue, 4 laps + close calls", BAD, listOf(white, silver, red, blue), 4, true)
        scenario("C bad, same grid, pink tag on white, cyan on silver", BAD, listOf(white.copy(tag = listOf(pink)), silver.copy(tag = listOf(cyan)), red, blue), 4, true)
        scenario("D bad, 10 identical white cars with the 10-tag set, 3 laps", BAD, tenWhite, 3, false)
        scenario("E worst (1/120 s), 10 identical white cars with tags, 3 laps", WORST, tenWhite, 3, false)
        scenario("F worst (1/120 s), white+silver+red+blue, no tags", WORST, listOf(white, silver, red, blue), 4, true)
    }
}
