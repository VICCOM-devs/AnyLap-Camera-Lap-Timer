package app.anylap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.anylap.ui.LocalApp
import app.anylap.ui.LocalNav
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import app.anylap.ui.home.HomeScreen
import app.anylap.pro.ProFeature
import app.anylap.ui.pro.PaywallScreen
import app.anylap.ui.racers.RacerEditScreen
import app.anylap.ui.racers.RacersScreen
import app.anylap.ui.session.ResultsScreen
import app.anylap.ui.session.SetupScreen
import app.anylap.ui.settings.SettingsScreen
import app.anylap.ui.stats.StatsScreen
import app.anylap.ui.theme.RaceTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens with sample data into PNGs under composeApp/screenshots/.
 * Run: ./gradlew :composeApp:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        SampleData.seed(AppGraph.state.repo)
        compose.setContent {
            val nav = Navigator()
            RaceTheme {
                CompositionLocalProvider(LocalApp provides AppGraph.state, LocalNav provides nav) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun home() = shot("01_home") { HomeScreen() }
    @Test fun setup() = shot("02_setup") { SetupScreen(LocalNav.current) }
    @Test fun racers() = shot("03_racers") { RacersScreen(LocalNav.current) }
    @Test fun racerEdit() = shot("04_racer_edit") { RacerEditScreen(LocalNav.current, SampleData.firstRacerId(AppGraph.state.repo)) }
    @Test fun results() = shot("05_results") { ResultsScreen(LocalNav.current, SampleData.SESSION_ID) }
    @Test fun stats() = shot("06_stats") { StatsScreen(LocalNav.current) }
    @Test fun settings() = shot("07_settings") { SettingsScreen(LocalNav.current) }
    @Test fun paywall() = shot("08_paywall") { PaywallScreen(LocalNav.current, ProFeature.CHARTS) }

    @Test
    fun fullApp() {
        SampleData.seed(AppGraph.state.repo)
        compose.setContent { App() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/00_app_home.png")
    }
}
