package app.anylap.ui.session

import app.anylap.platform.Platform
import app.anylap.util.fixed
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.LapSource
import app.anylap.data.RaceMode
import app.anylap.data.Racer
import app.anylap.data.Session
import app.anylap.data.ReviewItem
import app.anylap.engine.Format
import app.anylap.engine.LapMath
import app.anylap.engine.Stats
import app.anylap.ui.charts.BarList
import app.anylap.ui.charts.ChartSeries
import app.anylap.ui.charts.LineChart
import app.anylap.ui.components.CircleIconButton
import app.anylap.ui.components.KeyValue
import app.anylap.ui.components.PillTabs
import app.anylap.ui.components.PositionBadge
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.StatTile
import app.anylap.ui.components.Tag
import app.anylap.ui.components.TileRow
import app.anylap.ui.components.uiColor
import app.anylap.ui.theme.Num
import app.anylap.ui.theme.RT
import app.anylap.pro.Limits
import app.anylap.cloud.timedByLabel
import app.anylap.pro.ProFeature
import app.anylap.ui.pro.ProLockCard
import app.anylap.ui.pro.currentTier

@Composable
fun ResultsScreen(nav: Navigator, sessionId: String) {
    val app = LocalApp.current
    val sessions by app.repo.sessions.collectAsState()
    val settings by app.repo.settings.collectAsState()
    val s = sessions.firstOrNull { it.id == sessionId }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (s == null) {
        ScreenScaffold("Session", onBack = { nav.pop() }) {
            item { Text("Session not found.", color = RT.TextDim) }
        }
        return
    }
    val tier = currentTier()
    if (!Limits.canOpenSession(s.id, sessions, tier)) {
        ScreenScaffold(s.title, onBack = { nav.pop() }) {
            item { ProLockCard(nav, ProFeature.HISTORY) }
        }
        return
    }
    val hasVideo = s.videoPath != null && Platform.exists(s.videoPath)
    val ordered = s.order.mapNotNull { s.racer(it) } + s.racers.filterNot { it.id in s.order }

    ScreenScaffold(
        title = s.title,
        subtitle = Platform.formatDate(s.startedAt, "EEE d MMM · HH:mm") +
            " · " + Format.duration(s.durationMs) + (s.timedByLabel()?.let { " · $it" } ?: ""),
        onBack = {
            // Event rounds go back to the event; everything else to Home.
            if (app.repo.events.value.any { ev -> ev.rounds.any { it.sessionId == s.id } }) nav.pop() else nav.tab(Screen.Home)
        },
        actions = {
            CircleIconButton(Icons.Rounded.Share, { shareResults(s, branded = !tier.isPro) })
        },
        bottomPadding = 40.dp,
    ) {
        item { Podium(s, ordered) }
        item {
            val open = s.reviews.count { it.open }
            val tabs = listOf("Overview", "Laps", "Charts", "Replays") + if (s.reviews.isNotEmpty()) listOf(if (open > 0) "Check ($open)" else "Checked") else emptyList()
            PillTabs(tabs, tab, { tab = it }, Modifier.fillMaxWidth())
        }
        if (tab == 4) {
            item { SectionLabel("Crossings the camera wasn't sure about") }
            s.reviews.sortedWith(compareBy<ReviewItem> { !it.open }.thenBy { it.atMs }).forEach { item ->
                item(key = "rv-${item.id}") {
                    ReviewCard(
                        s, item,
                        onReassign = { atMs, to -> app.repo.saveSession(LapMath.reassign(s, item.id, atMs, to)) },
                        onCheck = { app.repo.saveSession(LapMath.check(s, item.id)) },
                    )
                }
            }
        }
        when (tab) {
            0 -> {
                ordered.forEachIndexed { i, r ->
                    item(key = "ov-${r.id}") { RacerSummary(s, r, i + 1, settings.speedUnitKmh) }
                }
                item { SectionLabel("Session") }
                item {
                    RtCard(Modifier.fillMaxWidth()) {
                        KeyValue("Format", s.config.mode.title)
                        KeyValue("Duration", Format.duration(s.durationMs))
                        KeyValue("Total laps", s.laps.size.toString())
                        KeyValue("Camera distance", "${(s.config.cameraDistanceM).fixed(1)} m")
                        s.config.lapLengthM?.let { KeyValue("Lap length", "${it.toInt()} m") }
                        KeyValue("Manual fixes", s.laps.count { it.source == LapSource.MANUAL || it.source == LapSource.ASSIGNED }.toString())
                        if (s.unassignedCrossings > 0) KeyValue("Unassigned crossings", s.unassignedCrossings.toString(), RT.Amber)
                        KeyValue("Video", if (hasVideo) "Saved" else "None")
                    }
                }
                item {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = RT.Accent)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete session", color = RT.Accent)
                    }
                }
            }
            1 -> item { LapTable(s, ordered) { r, lap -> if (hasVideo) nav.push(Screen.Replay(s.id, r.id, lap)) } }
            2 -> {
                item { SectionLabel("Lap times", Icons.Rounded.Timer) }
                item {
                    RtCard(Modifier.fillMaxWidth()) {
                        LineChart(
                            series = ordered.map { r ->
                                ChartSeries(r.name, r.uiColor, s.lapsOf(r.id).map { it.number.toFloat() to it.timeMs / 1000f })
                            },
                            yLabel = { "${(it).fixed(1)}s" },
                            highlightMin = true,
                        )
                    }
                }
                if (s.config.mode != RaceMode.PRACTICE && ordered.size > 1) {
                    item { SectionLabel("Positions", Icons.Rounded.EmojiEvents) }
                    item {
                        val pos = remember(s.id) { Stats.positionsByLap(s) }
                        RtCard(Modifier.fillMaxWidth()) {
                            LineChart(
                                series = ordered.map { r ->
                                    ChartSeries(r.name, r.uiColor, (pos[r.id] ?: emptyList()).mapIndexed { i, p -> (i + 1).toFloat() to p.toFloat() })
                                },
                                yLabel = { "P${it.toInt()}" },
                                invertY = true,
                                integerY = true,
                            )
                        }
                    }
                }
                if (s.laps.any { it.speedKmh != null }) {
                    item { SectionLabel("Speed at the line", Icons.Rounded.Speed) }
                    item {
                        RtCard(Modifier.fillMaxWidth()) {
                            LineChart(
                                series = ordered.map { r ->
                                    ChartSeries(r.name, r.uiColor, s.lapsOf(r.id).mapNotNull { l ->
                                        l.speedKmh?.let { v -> l.number.toFloat() to if (settings.speedUnitKmh) v else v / 1.609344f }
                                    })
                                },
                                yLabel = { "${(it).fixed(0)}" },
                            )
                        }
                    }
                }
                item { SectionLabel("Best lap", Icons.Rounded.Insights) }
                item {
                    RtCard(Modifier.fillMaxWidth()) {
                        BarList(
                            ordered.mapNotNull { r ->
                                Stats.forRacer(s, r.id).bestMs?.let { Triple(r.name, r.uiColor, it / 1000f) }
                            }.sortedBy { it.third },
                            valueLabel = { "${(it).fixed(3)}" },
                        )
                    }
                }
            }
            else -> {
                if (!hasVideo) {
                    item {
                        RtCard(Modifier.fillMaxWidth()) {
                            Text("No video for this session.", style = MaterialTheme.typography.bodyMedium, color = RT.TextDim)
                        }
                    }
                } else {
                    val winner = ordered.firstOrNull()
                    val winnerLast = winner?.let { s.lapsOf(it.id).lastOrNull() }
                    if (winner != null && winnerLast != null) {
                        item {
                            ReplayCard(
                                title = if (s.config.mode == RaceMode.PRACTICE) "Last lap · ${winner.name}" else "Finish · ${winner.name}",
                                subtitle = "Lap ${winnerLast.number} · ${Format.lap(winnerLast.timeMs)}",
                                racer = winner,
                                highlight = true,
                            ) { nav.push(Screen.Replay(s.id, winner.id, winnerLast.number)) }
                        }
                    }
                    Stats.fastestLap(s)?.let { f ->
                        val r = s.racer(f.racerId)
                        if (r != null) item {
                            ReplayCard("Fastest lap · ${r.name}", "Lap ${f.number} · ${Format.lap(f.timeMs)}", r, false) {
                                nav.push(Screen.Replay(s.id, r.id, f.number))
                            }
                        }
                    }
                    item { SectionLabel("Every lap", Icons.Rounded.SlowMotionVideo) }
                    item {
                        Text("Tap any time in the Laps tab to replay that crossing in slow motion.", style = MaterialTheme.typography.bodySmall)
                    }
                    item { LapTable(s, ordered) { r, lap -> nav.push(Screen.Replay(s.id, r.id, lap)) } }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = RT.Surface2,
            title = { Text("Delete this session?") },
            text = { Text("Laps and video will be removed.", color = RT.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    nav.pop()
                    app.repo.deleteSession(s.id)
                }) { Text("Delete", color = RT.Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = RT.Text) } },
        )
    }
}

@Composable
private fun Podium(s: Session, ordered: List<Racer>) {
    if (ordered.isEmpty()) return
    val isRace = s.config.mode != RaceMode.PRACTICE
    RtCard(Modifier.fillMaxWidth(), highlight = RT.AccentDeep, padding = 18.dp) {
        Text(if (isRace) "RESULT" else "FASTEST LAPS", style = MaterialTheme.typography.labelSmall, color = RT.Accent)
        Spacer(Modifier.height(14.dp))
        val top = ordered.take(3)
        val display = when (top.size) {
            3 -> listOf(top[1] to 2, top[0] to 1, top[2] to 3)
            2 -> listOf(top[0] to 1, top[1] to 2)
            else -> listOf(top[0] to 1)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            display.forEach { (r, pos) ->
                val st = Stats.forRacer(s, r.id)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
                    if (pos == 1) Icon(Icons.Rounded.EmojiEvents, null, tint = RT.Gold, modifier = Modifier.size(22.dp))
                    RacerAvatar(r, if (pos == 1) 72.dp else 56.dp, ring = RT.podium(pos))
                    Spacer(Modifier.height(8.dp))
                    Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(
                        if (isRace) "${st.laps} laps" else Format.lap(st.bestMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    PodiumStep(pos, if (pos == 1) 46.dp else if (pos == 2) 32.dp else 22.dp)
                }
            }
        }
    }
}

@Composable
private fun PodiumStep(pos: Int, h: Dp) {
    Box(
        Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .background(RT.podium(pos).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$pos", color = RT.podium(pos), fontWeight = FontWeight.Black, fontSize = 18.sp)
    }
}

@Composable
private fun RacerSummary(s: Session, r: Racer, pos: Int, kmh: Boolean) {
    val st = remember(s.id, r.id) { Stats.forRacer(s, r.id) }
    val fastest = Stats.fastestLap(s)
    RtCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PositionBadge(pos)
            Spacer(Modifier.width(10.dp))
            RacerAvatar(r, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text("${st.laps} laps" + (st.distanceM?.let { " · ${"${(it / 1000).fixed(2)}"} km" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            if (fastest != null && fastest.racerId == r.id) Tag("Fastest", RT.Purple)
        }
        Spacer(Modifier.height(12.dp))
        TileRow {
            MiniStat("Best", Format.lap(st.bestMs), Modifier.weight(1f), if (fastest?.racerId == r.id) RT.Purple else RT.Text)
            MiniStat("Average", Format.lap(st.avgMs), Modifier.weight(1f))
            MiniStat("Consistency", Format.percent(st.consistencyPct), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        TileRow {
            if (st.bestPaceSecPerKm != null) {
                MiniStat("Best pace", Format.pace(st.bestPaceSecPerKm), Modifier.weight(1f))
                MiniStat("Avg pace", Format.pace(st.avgPaceSecPerKm), Modifier.weight(1f))
            } else {
                MiniStat("Top speed", Format.speed(st.topSpeedKmh, kmh), Modifier.weight(1f))
                MiniStat("Avg speed", Format.speed(st.avgSpeedKmh, kmh), Modifier.weight(1f))
            }
            MiniStat("Gain to best", st.potentialGainMs?.let { "−" + Format.lap(it) } ?: "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier, color: Color = RT.Text) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(RT.Surface2).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Text(value, color = color, style = Num.style(17.sp, FontWeight.Bold), maxLines = 1)
    }
}

@Composable
private fun LapTable(s: Session, racers: List<Racer>, onLap: (Racer, Int) -> Unit) {
    val maxLaps = racers.maxOfOrNull { s.lapsOf(it.id).size } ?: 0
    val fastest = Stats.fastestLap(s)
    val bests = racers.associate { r -> r.id to s.lapsOf(r.id).minOfOrNull { it.timeMs } }
    val colW = 92.dp
    RtCard(Modifier.fillMaxWidth(), padding = 10.dp) {
        if (maxLaps == 0) {
            Text("No laps recorded.", color = RT.TextDim)
            return@RtCard
        }
        Row {
            // Lap number column
            Column {
                Box(Modifier.height(44.dp).width(40.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.TableChart, null, tint = RT.TextFaint, modifier = Modifier.size(16.dp))
                }
                for (n in 1..maxLaps) {
                    Box(Modifier.height(34.dp).width(40.dp), contentAlignment = Alignment.Center) {
                        Text("$n", color = RT.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                racers.forEach { r ->
                    val laps = s.lapsOf(r.id)
                    Column(Modifier.width(colW)) {
                        Box(Modifier.height(44.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RacerAvatar(r, 24.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(r.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = RT.TextDim)
                            }
                        }
                        for (n in 1..maxLaps) {
                            val lap = laps.getOrNull(n - 1)
                            val isFastest = lap != null && fastest != null && lap.racerId == fastest.racerId && lap.number == fastest.number
                            val isPb = lap != null && lap.timeMs == bests[r.id]
                            val bg = when {
                                isFastest -> RT.Purple.copy(alpha = 0.22f)
                                isPb -> RT.Green.copy(alpha = 0.16f)
                                else -> Color.Transparent
                            }
                            Box(
                                Modifier.height(34.dp).fillMaxWidth().padding(2.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                                    .then(if (lap != null) Modifier.clickable { onLap(r, lap.number) } else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    lap?.let { Format.lap(it.timeMs) } ?: "",
                                    style = Num.style(14.sp, FontWeight.SemiBold),
                                    color = when {
                                        isFastest -> RT.Purple
                                        isPb -> RT.Green
                                        lap?.source == LapSource.MANUAL || lap?.source == LapSource.ASSIGNED -> RT.Amber
                                        else -> RT.Text
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(RT.Purple, "Fastest")
            LegendDot(RT.Green, "Personal best")
            LegendDot(RT.Amber, "Manual")
        }
    }
}

@Composable
private fun LegendDot(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReplayCard(title: String, subtitle: String, racer: Racer, highlight: Boolean, onClick: () -> Unit) {
    RtCard(Modifier.fillMaxWidth(), onClick = onClick, highlight = if (highlight) RT.AccentDeep else null, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(RT.AccentSoft).border(1.dp, RT.AccentDeep, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayCircle, null, tint = RT.Accent, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            RacerAvatar(racer, 34.dp)
        }
    }
}

private fun shareResults(s: Session, branded: Boolean) {
    val sb = StringBuilder()
    sb.appendLine("${s.title} – ${Platform.formatDate(s.startedAt, "d MMM yyyy HH:mm")}")
    val ordered = s.order.mapNotNull { s.racer(it) }
    ordered.forEachIndexed { i, r ->
        val st = Stats.forRacer(s, r.id)
        sb.appendLine("P${i + 1} ${r.name}: ${st.laps} laps, best ${Format.lap(st.bestMs)}, avg ${Format.lap(st.avgMs)}")
    }
    Stats.fastestLap(s)?.let { f -> sb.appendLine("Fastest lap: ${s.racer(f.racerId)?.name} ${Format.lap(f.timeMs)}") }
    if (branded) sb.appendLine().append("Timed with AnyLap – camera lap timer · viccom.se")
    Platform.shareText(sb.toString(), "Share results")
}
