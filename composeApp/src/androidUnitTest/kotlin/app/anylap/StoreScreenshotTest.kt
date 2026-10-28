package app.anylap

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import app.anylap.ui.session.ResultsScreen
import app.anylap.ui.stats.StatsScreen
import app.anylap.ui.theme.RaceTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Raw captures for the store screenshots (composeApp/screenshots/store/).
 * The camera preview shows a still photo of a track, the rest is the real UI with demo data.
 * Framing and captions are added by scripts/store-screenshots (see that folder).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val track by lazy {
        BitmapFactory.decodeFile(File(SampleData.storeDir, "track.jpg").path).asImageBitmap()
    }

    private fun live(name: String, phase: Phase, flash: Boolean) {
        val repo = AppGraph.state.repo
        SampleData.seed(repo)
        val racers = repo.racersFor(Sport.RC).sortedBy { it.number }.let { l ->
            // Grid order matches the photo: red leads, then blue, yellow, ghost.
            listOf(14, 27, 39, 8).mapNotNull { n -> l.firstOrNull { it.number == n } }
        }
        val config = SessionConfig(sport = Sport.RC, mode = RaceMode.LAPS, targetLaps = 10, racerIds = racers.map { it.id }, cameraDistanceM = 2.5f)
        val gaps = listOf("LEADER", "+0.412", "+1.907", "+1 lap")
        val standings = if (phase == Phase.SETUP) racers.mapIndexed { i, r ->
            Standing(r, i + 1, 0, null, null, null, "—", false, false, false)
        } else racers.mapIndexed { i, r ->
            Standing(
                racer = r, position = i + 1, laps = if (i == 3) 6 else 7,
                lastLapMs = 11_874L + i * 377, bestLapMs = 11_602L + i * 410, lastSpeedKmh = 27.4f + i * 0.3f,
                gap = gaps[i], finished = false, hasFastestLap = i == 0, timing = true,
            )
        }
        val f = if (flash) LapFlash(racers[0], Lap(racers[0].id, 7, 11_602, 84_000, 29.1f), 1, personalBest = true, fastest = true) else null
        val race = RaceState(
            phase = phase, countdown = 0, elapsedMs = 84_312, standings = standings,
            flash = f, leaderLaps = if (phase == Phase.SETUP) 0 else 7,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaceTheme {
                CompositionLocalProvider(LocalApp provides AppGraph.state, LocalNav provides Navigator()) {
                    LiveLayout(
                        config = config, race = race, speedKmh = true, fps = 120.0, recording = true, error = null,
                        line = LineSpec(0.508f, 0.37f, 0.511f, 0.985f), direction = Direction.REVERSE, previewAspect = 16f / 9f,
                        voice = true, onLineChange = {}, onDirection = {}, onResetBackground = {}, onBack = {}, onStart = {},
                        onEnd = {}, onToggleVoice = {}, onAssign = {}, onAddLap = {}, onRemoveLap = {},
                    ) {
                        Image(track, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onRoot().captureRoboImage("screenshots/store/$name.png")
    }

    @Test @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun liveRunning() = live("live_running", Phase.RUNNING, flash = true)

    @Test @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun liveSetup() = live("live_setup", Phase.SETUP, flash = false)

    private fun tall(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        SampleData.seed(AppGraph.state.repo)
        compose.setContent {
            RaceTheme {
                CompositionLocalProvider(LocalApp provides AppGraph.state, LocalNav provides Navigator()) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/store/$name.png")
    }

    // Very tall viewports so the whole scrolling screen is captured; the framing script crops.
    @Test @Config(sdk = [35], qualifiers = "w411dp-h2000dp-xxhdpi")
    fun statsTall() = tall("stats_tall") { StatsScreen(LocalNav.current) }

    @Test @Config(sdk = [35], qualifiers = "w411dp-h2000dp-xxhdpi")
    fun resultsTall() = tall("results_tall") { ResultsScreen(LocalNav.current, SampleData.SESSION_ID) }
}
