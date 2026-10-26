package app.anylap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.anylap.data.CalibrationSample
import app.anylap.data.Direction
import app.anylap.data.LineSpec
import app.anylap.data.SampleKind
import app.anylap.data.Sport
import app.anylap.data.withCalibration
import app.anylap.ui.LocalApp
import app.anylap.ui.LocalNav
import app.anylap.ui.Navigator
import app.anylap.ui.racers.CalStep
import app.anylap.ui.racers.CalibrateActions
import app.anylap.ui.racers.CalibrateLayout
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
class CalibrateScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val noop = CalibrateActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun shot(name: String, step: CalStep, editingLine: Boolean = false, withPass: Boolean = false) {
        val repo = AppGraph.state.repo
        SampleData.seed(repo)
        val base = repo.racersFor(Sport.RC).minBy { it.number }
        fun s(id: String, kind: SampleKind, view: String = "", kmh: Float? = null) =
            CalibrationSample(id, kind, view, List(264) { if (it == 7) 1f else 0f }, null, 0, kmh, 1)
        val racer = base.withCalibration(
            listOf(
                s("p1", SampleKind.PHOTO, "left"), s("p2", SampleKind.PHOTO, "right"),
                s("a", SampleKind.PASS_SLOW, kmh = 9.8f), s("b", SampleKind.PASS_SLOW, kmh = 11.2f), s("c", SampleKind.PASS_SLOW, kmh = 10.4f),
                s("d", SampleKind.PASS_FAST, kmh = 31.5f),
            ),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaceTheme {
                CompositionLocalProvider(LocalApp provides AppGraph.state, LocalNav provides Navigator()) {
                    CalibrateLayout(
                        racer = racer, step = step, line = LineSpec(0.52f, 0.08f, 0.47f, 0.95f), direction = Direction.FORWARD,
                        editingLine = editingLine, previewAspect = 16f / 9f, fps = 60.0, error = null, busy = false,
                        lastPass = if (withPass) racer.calibration.last() else null, markerSeen = null, notice = null, kmh = true,
                        actions = noop,
                    ) {
                        // Stand-in for the camera: asphalt, white tape, a car-ish block.
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Brush.verticalGradient(listOf(Color(0xFF5B5E63), Color(0xFF3B3D41), Color(0xFF2A2B2E))))
                            drawLine(Color(0xFFE8E8E8), Offset(size.width * 0.52f, size.height * 0.08f), Offset(size.width * 0.47f, size.height * 0.95f), strokeWidth = 14f)
                            drawRect(Color(0xFFEDEDED), Offset(size.width * 0.38f, size.height * 0.42f), Size(size.width * 0.24f, size.height * 0.2f))
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun photo() = shot("20_calibrate_photo", CalStep.Photo(2))
    @Test fun pass() = shot("21_calibrate_fast_pass", CalStep.Pass(SampleKind.PASS_FAST), withPass = true)
    @Test fun line() = shot("22_calibrate_line", CalStep.Pass(SampleKind.PASS_FAST), editingLine = true)
    @Test fun review() = shot("23_calibrate_review", CalStep.Review)
}
