package app.anylap.ui.stats

import app.anylap.platform.Platform
import app.anylap.util.fixed
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.anylap.data.Sport
import app.anylap.engine.Format
import app.anylap.engine.Stats
import app.anylap.ui.charts.BarList
import app.anylap.ui.charts.ChartSeries
import app.anylap.ui.charts.LineChart
import app.anylap.ui.components.EmptyState
import app.anylap.ui.components.PillTabs
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.StatTile
import app.anylap.ui.components.TileRow
import app.anylap.ui.components.uiColor
import app.anylap.ui.theme.RT
import app.anylap.pro.Limits
import app.anylap.pro.ProFeature
import app.anylap.ui.pro.ProLockCard
import app.anylap.ui.pro.currentTier

@Composable
fun StatsScreen(nav: Navigator) {
    val app = LocalApp.current
    val allSessions by app.repo.sessions.collectAsState()
    val tier = currentTier()
    val sessions = remember(allSessions, tier) { Limits.visibleSessions(allSessions, tier) }
    val locked = allSessions.size - sessions.size
    val racers by app.repo.racers.collectAsState()
    val settings by app.repo.settings.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sport = if (tab == 0) Sport.RC else Sport.RUNNING
    val sportRacers = racers.filter { it.sport == sport }.sortedBy { it.number }
    var selectedId by remember(sport) { mutableStateOf(sportRacers.firstOrNull()?.id) }
    val selected = sportRacers.firstOrNull { it.id == selectedId } ?: sportRacers.firstOrNull()
    val allTime = remember(sessions, sportRacers) { sportRacers.map { Stats.allTime(sessions, it) } }

    ScreenScaffold(title = "Stats", subtitle = "Personal bests, trends and rankings") {
        item { PillTabs(listOf("RC cars", "Running"), tab, { tab = it }, Modifier.fillMaxWidth()) }

        if (sportRacers.isEmpty() || allTime.all { it.sessions == 0 }) {
            item {
                EmptyState(Icons.Rounded.BarChart, "No stats yet", "Finish a session and your numbers land here – best laps, consistency, speed and head-to-head rankings.")
            }
            return@ScreenScaffold
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sportRacers, key = { it.id }) { r ->
                    val sel = r.id == selected?.id
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) RT.AccentSoft else RT.Surface)
                            .border(1.dp, if (sel) RT.Accent else RT.Stroke, RoundedCornerShape(16.dp))
                            .clickable { selectedId = r.id }
                            .padding(10.dp)
                            .width(72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RacerAvatar(r, 44.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(r.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        val st = allTime.firstOrNull { it.racer.id == selected?.id }
        if (st != null) {
            item { SectionLabel("${st.racer.name} · all time", Icons.Rounded.Insights) }
            item {
                TileRow {
                    StatTile(
                        "Best lap", Format.lap(st.bestLapMs), Modifier.weight(1f), Icons.Rounded.Timer,
                        sub = st.bestLapAt?.let { Platform.formatDate(it, "d MMM yyyy") },
                        valueColor = RT.Purple,
                    )
                    StatTile("Top speed", Format.speed(st.topSpeedKmh, settings.speedUnitKmh), Modifier.weight(1f), Icons.Rounded.Speed)
                }
            }
            item {
                TileRow {
                    StatTile("Sessions", st.sessions.toString(), Modifier.weight(1f), Icons.Rounded.Flag)
                    StatTile("Laps", st.laps.toString(), Modifier.weight(1f), Icons.Rounded.Loop)
                    StatTile("Wins", "${st.wins}", Modifier.weight(1f), Icons.Rounded.EmojiEvents, sub = "${st.podiums} podiums", accent = RT.Gold)
                }
            }
            if (sport == Sport.RUNNING && st.distanceM > 0) {
                item {
                    StatTile(
                        "Distance timed", "${(st.distanceM / 1000).fixed(2)} km", Modifier.fillMaxWidth(), Icons.Rounded.Timeline,
                    )
                }
            }
            if (!tier.isPro) {
                item { SectionLabel("Trends") }
                item {
                    ProLockCard(
                        nav, ProFeature.CHARTS,
                        body = "Best lap, consistency and top speed per session for ${st.racer.name}" +
                            if (locked > 0) " – plus $locked older session${if (locked == 1) "" else "s"} counted in." else ".",
                    )
                }
            }
            if (tier.isPro) item { SectionLabel("Best lap per session") }
            if (tier.isPro) item {
                RtCard(Modifier.fillMaxWidth()) {
                    LineChart(
                        series = listOf(ChartSeries(st.racer.name, st.racer.uiColor, st.bestLapTrend.mapIndexed { i, (_, ms) -> (i + 1).toFloat() to ms / 1000f })),
                        yLabel = { "${(it).fixed(1)}s" },
                        xLabel = { "#${it.toInt()}" },
                        highlightMin = true,
                        showLegend = false,
                    )
                    Text("Lower is faster. Purple ring = all-time best.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (tier.isPro) item { SectionLabel("Consistency per session") }
            if (tier.isPro) item {
                RtCard(Modifier.fillMaxWidth()) {
                    LineChart(
                        series = listOf(ChartSeries("Consistency", RT.Green, st.consistencyTrend.mapIndexed { i, (_, c) -> (i + 1).toFloat() to c.toFloat() })),
                        yLabel = { "${(it).fixed(0)}%" },
                        xLabel = { "#${it.toInt()}" },
                        showLegend = false,
                        height = 180.dp,
                    )
                    Text("How close your laps are to each other. 100 % = every lap identical.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (tier.isPro && st.speedTrend.size > 1) {
                item { SectionLabel("Top speed per session") }
                item {
                    RtCard(Modifier.fillMaxWidth()) {
                        LineChart(
                            series = listOf(
                                ChartSeries(
                                    "Top speed", RT.Accent,
                                    st.speedTrend.mapIndexed { i, (_, kmh) ->
                                        (i + 1).toFloat() to (if (settings.speedUnitKmh) kmh else kmh / 1.609344f)
                                    },
                                ),
                            ),
                            yLabel = { "${it.fixed(0)}" },
                            xLabel = { "#${it.toInt()}" },
                            showLegend = false,
                            height = 180.dp,
                        )
                        Text("Fastest pass over the line each session, in ${if (settings.speedUnitKmh) "km/h" else "mph"}.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item { SectionLabel("All-time ranking · best lap", Icons.Rounded.Leaderboard) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                BarList(
                    allTime.mapNotNull { a -> a.bestLapMs?.let { Triple(a.racer.name, a.racer.uiColor, it / 1000f) } }.sortedBy { it.third },
                    valueLabel = { "${(it).fixed(3)}" },
                )
            }
        }
        item { SectionLabel("All-time ranking · wins") }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                val winners = allTime.filter { it.wins > 0 }.sortedByDescending { it.wins }
                if (winners.isEmpty()) Text("No races finished yet.", style = MaterialTheme.typography.bodySmall)
                else BarList(
                    winners.map { Triple(it.racer.name, it.racer.uiColor, it.wins.toFloat()) },
                    valueLabel = { "${it.toInt()}" },
                    lowerIsBetter = false,
                )
            }
        }
    }
}
