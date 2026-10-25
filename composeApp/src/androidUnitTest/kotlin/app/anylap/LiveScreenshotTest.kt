package app.anylap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.anylap.data.Direction
import app.anylap.data.Lap
import app.anylap.data.LineSpec
import app.anylap.data.RaceMode
import app.anylap.data.SessionConfig
import app.anylap.data.Sport
import app.anylap.engine.LapFlash
import app.anylap.engine.Phase
import app.anylap.engine.RaceState
import app.anylap.engine.Standing
import app.anylap.ui.LocalApp
import app.anylap.ui.LocalNav
import app.anylap.ui.Navigator
import app.anylap.ui.session.LiveLayout
import app.anylap.ui.theme.RaceTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi")
class LiveScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun state(phase: Phase, countdown: Int = 0, flash: Boolean = false): Pair<SessionConfig, RaceState> {
        val repo = AppGraph.state.repo
        SampleData.seed(repo)
        val racers = repo.racersFor(Sport.RC).sortedBy { it.number }
        val config = SessionConfig(sport = Sport.RC, mode = RaceMode.LAPS, targetLaps = 10, racerIds = racers.map { it.id }, cameraDistanceM = 2.5f)
        val gaps = listOf("LEADER", "+0.412", "+1.907", "+1 lap")
        val standings = if (phase == Phase.SETUP) racers.mapIndexed { i, r ->
            Standing(r, i + 1, 0, null, null, null, "—", false, false, false)
        } else racers.mapIndexed { i, r ->
            Standing(
                racer = r, position = i + 1, laps = if (i == 3) 5 else 6,
                lastLapMs = 12_480L + i * 377, bestLapMs = 11_989L + i * 410, lastSpeedKmh = 27.4f,
                gap = gaps[i], finished = false, hasFastestLap = i == 0, timing = true,
            )
        }
        val f = if (flash) LapFlash(racers[1], Lap(racers[1].id, 6, 12_102, 75_000, 28.3f), 2, personalBest = true, fastest = false) else null
        return config to RaceState(
            phase = phase, countdown = countdown, elapsedMs = 75_432, standings = standings,
            flash = f, leaderLaps = if (phase == Phase.SETUP) 0 else 6,
        )
    }

    private fun shot(name: String, phase: Phase, countdown: Int = 0, flash: Boolean = false) {
        val (config, race) = state(phase, countdown, flash)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaceTheme {
                CompositionLocalProvider(LocalApp provides AppGraph.state, LocalNav provides Navigator()) {
                    LiveLayout(
                        config = config, race = race, speedKmh = true, fps = 60.0, recording = true, error = null,
                        line = LineSpec(0.52f, 0.08f, 0.47f, 0.95f), direction = Direction.FORWARD, previewAspect = 16f / 9f,
                        voice = true, onLineChange = {}, onDirection = {}, onResetBackground = {}, onBack = {}, onStart = {},
                        onEnd = {}, onToggleVoice = {}, onAssign = {}, onAddLap = {}, onRemoveLap = {},
                    ) {
                        // Stand-in for the camera: asphalt with a white tape line.
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Brush.verticalGradient(listOf(Color(0xFF5B5E63), Color(0xFF3B3D41), Color(0xFF2A2B2E))))
                            drawLine(Color(0xFFE8E8E8), Offset(size.width * 0.52f, size.height * 0.08f), Offset(size.width * 0.47f, size.height * 0.95f), strokeWidth = 14f)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun setup() = shot("10_live_setup", Phase.SETUP)
    @Test fun countdown() = shot("11_live_countdown", Phase.COUNTDOWN, countdown = 2)
    @Test fun running() = shot("12_live_running", Phase.RUNNING, flash = true)
}
