package app.anylap

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.platform.PlatformBackHandler
import app.anylap.ui.AppState
import app.anylap.ui.LocalApp
import app.anylap.ui.LocalNav
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import app.anylap.ui.home.HomeScreen
import app.anylap.ui.racers.CalibrateScreen
import app.anylap.ui.racers.RacerEditScreen
import app.anylap.ui.racers.RacersScreen
import app.anylap.ui.session.LiveScreen
import app.anylap.ui.session.ReplayScreen
import app.anylap.ui.session.ResultsScreen
import app.anylap.ui.session.SetupScreen
import app.anylap.ui.settings.SettingsScreen
import app.anylap.ui.stats.StatsScreen
import app.anylap.ui.theme.RT
import app.anylap.ui.theme.RaceTheme

/** One app state per process (survives Android activity recreation). */
object AppGraph {
    val state: AppState by lazy { AppState() }
}

/** Shared entry point for Android and iOS. */
@Composable
fun App() {
    val nav = remember { Navigator() }
    RaceTheme {
        CompositionLocalProvider(LocalApp provides AppGraph.state, LocalNav provides nav) {
            Box(Modifier.fillMaxSize().background(RT.Bg)) {
                PlatformBackHandler(enabled = nav.stack.size > 1) { nav.pop() }
                AnimatedContent(
                    targetState = nav.current,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen()
                        Screen.Race -> SetupScreen(nav)
                        Screen.Stats -> StatsScreen(nav)
                        Screen.Racers -> RacersScreen(nav)
                        Screen.Settings -> SettingsScreen(nav)
                        Screen.Live -> LiveScreen(nav)
                        is Screen.RacerEdit -> RacerEditScreen(nav, screen.id)
                        is Screen.Calibrate -> CalibrateScreen(nav, screen.id)
                        is Screen.Results -> ResultsScreen(nav, screen.id)
                        is Screen.Replay -> ReplayScreen(nav, screen.sessionId, screen.racerId, screen.lap)
                    }
                }
                if (nav.current.isTab) {
                    BottomBar(nav, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Screen.Home, "Home", Icons.Rounded.Home),
    Tab(Screen.Race, "Race", Icons.Rounded.Flag),
    Tab(Screen.Stats, "Stats", Icons.Rounded.BarChart),
    Tab(Screen.Racers, "Racers", Icons.Rounded.Groups),
)

@Composable
private fun BottomBar(nav: Navigator, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xF2141417))
            .border(1.dp, RT.Stroke, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        tabs.forEach { tab ->
            val sel = tab.screen == nav.current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (sel) RT.AccentSoft else Color.Transparent)
                    .clickable { nav.tab(tab.screen) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, tab.label, tint = if (sel) RT.Accent else RT.TextDim, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = if (sel) RT.Text else RT.TextDim,
                )
            }
        }
    }
}
