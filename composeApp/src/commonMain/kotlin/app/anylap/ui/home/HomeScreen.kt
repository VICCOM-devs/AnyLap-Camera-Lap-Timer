package app.anylap.ui.home

import app.anylap.ui.LocalApp
import app.anylap.ui.LocalNav
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsMotorsports
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.Session
import app.anylap.platform.Platform
import app.anylap.data.Sport
import app.anylap.engine.Format
import app.anylap.engine.Stats
import app.anylap.ui.components.CircleIconButton
import app.anylap.ui.components.DateDivider
import app.anylap.ui.components.EmptyState
import app.anylap.ui.components.PillTabs
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.StatTile
import app.anylap.ui.components.Tag
import app.anylap.ui.components.TileRow
import app.anylap.ui.components.TimelineItem
import app.anylap.ui.theme.RT
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.anylap.ui.components.AvatarStack
import app.anylap.ui.components.BrandWordmark
import app.anylap.ui.components.ChequeredMark
import app.anylap.ui.components.StripeCard
import app.anylap.ui.components.speedLines
import app.anylap.ui.components.uiColor
import app.anylap.ui.theme.Num

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val app = LocalApp.current
    val sessions by app.repo.sessions.collectAsState()
    val racers by app.repo.racers.collectAsState()
    val settings by app.repo.settings.collectAsState()
    var sportIdx by remember { mutableIntStateOf(if (settings.lastConfig.sport == Sport.RUNNING) 1 else 0) }
    val sport = if (sportIdx == 0) Sport.RC else Sport.RUNNING
    val (count, laps, best) = remember(sessions, sport) { Stats.totals(sessions, sport) }
    val sportRacers = racers.filter { it.sport == sport }

    ScreenScaffold(
        title = "AnyLap",
        subtitle = Platform.formatDate(Platform.nowMillis(), "EEEE d MMMM"),
        titleSlot = { BrandWordmark() },
        actions = { CircleIconButton(Icons.Rounded.Settings, { nav.push(Screen.Settings) }, contentDescription = "Settings") },
    ) {
        item {
            PillTabs(listOf("RC cars", "Running"), sportIdx, { sportIdx = it }, Modifier.fillMaxWidth())
        }
        item {
            HeroCard(
                sport = sport,
                racerCount = sportRacers.size,
                calibrated = sportRacers.count { it.isCalibrated || it.markerId != null },
                onStart = {
                    app.repo.updateSettings { s -> s.copy(lastConfig = s.lastConfig.copy(sport = sport)) }
                    nav.tab(Screen.Race)
                },
                onRacers = { nav.tab(Screen.Racers) },
            )
        }
        item { SectionLabel("Overview", Icons.Rounded.Speed) }
        item {
            TileRow {
                StatTile("Sessions", count.toString(), Modifier.weight(1f), Icons.Rounded.Flag)
                StatTile("Laps timed", laps.toString(), Modifier.weight(1f), Icons.Rounded.Loop)
            }
        }
        item {
            val bestRacer = remember(sessions, sport) {
                sessions.filter { it.config.sport == sport }.flatMap { s -> s.laps.map { l -> s to l } }
                    .minByOrNull { it.second.timeMs }?.let { (s, l) -> s.racer(l.racerId)?.name }
            }
            val topSpeed = remember(sessions, sport) {
                sessions.filter { it.config.sport == sport }.flatMap { it.laps }.mapNotNull { it.speedKmh }.maxOrNull()
            }
            TileRow {
                StatTile("Fastest lap", Format.lap(best), Modifier.weight(1f), Icons.Rounded.Timer, sub = bestRacer, valueColor = if (best != null) RT.Purple else RT.Text)
                StatTile("Top speed", Format.speed(topSpeed, settings.speedUnitKmh), Modifier.weight(1f), Icons.Rounded.Speed)
            }
        }

        item { SectionLabel("Recent sessions", Icons.Rounded.History) }
        val recent = sessions.filter { it.config.sport == sport }.take(12)
        if (recent.isEmpty()) {
            item {
                EmptyState(
                    icon = if (sport == Sport.RC) Icons.Rounded.SportsMotorsports else Icons.Rounded.DirectionsRun,
                    title = "No sessions yet",
                    body = "Set up a finish line and your first laps will show up here.",
                )
            }
        } else {
            var lastDay = ""
            recent.forEachIndexed { index, s ->
                val day = dayLabel(s.startedAt)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day-$index") { DateDivider(day) }
                }
                item(key = s.id) {
                    TimelineItem(active = index == 0) {
                        SessionCard(s) { nav.push(Screen.Results(s.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(sport: Sport, racerCount: Int, calibrated: Int, onStart: () -> Unit, onRacers: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF5A0A0D), Color(0xFF1E0A0C), RT.Surface)))
            .border(1.dp, RT.AccentDeep, shape),
    ) {
        // Backdrop: speed lines + a big chequered flag bleeding off the corner.
        Canvas(Modifier.matchParentSize()) { speedLines(size, alpha = 0.06f) }
        ChequeredMark(Modifier.align(Alignment.TopEnd).offset(x = 26.dp, y = (-14).dp).size(150.dp).alpha(0.22f))
        Column(Modifier.padding(20.dp)) {
            Text("READY TO RACE", style = MaterialTheme.typography.labelSmall, color = RT.AccentHot)
            Spacer(Modifier.height(6.dp))
            Text(
                if (sport == Sport.RC) "LINE UP\nYOUR CARS" else "LINE UP\nYOUR RUNNERS",
                style = MaterialTheme.typography.displaySmall.copy(lineHeight = 34.sp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("$racerCount ${sport.unitName}${if (racerCount == 1) "" else "s"}", RT.Text)
                Tag("$calibrated ready", if (calibrated > 0) RT.Green else RT.Amber)
                Tag("max ${sport.maxRacers}", RT.TextDim)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("New session", onStart, Modifier.weight(1f), icon = Icons.Rounded.Flag)
                SecondaryButton("Racers", onRacers, icon = Icons.Rounded.Groups)
            }
        }
    }
}

@Composable
private fun SessionCard(s: Session, onClick: () -> Unit) {
    val podium = remember(s.id) { s.order.take(3).mapNotNull { s.racer(it) } }
    val fastest = remember(s.id) { Stats.fastestLap(s) }
    val stripe = podium.firstOrNull()?.uiColor ?: RT.Accent
    StripeCard(stripe, Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Tag(s.config.mode.short, RT.TextDim)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                Platform.formatDate(s.startedAt, "HH:mm") +
                    " · ${s.racers.size} ${s.config.sport.unitName}${if (s.racers.size == 1) "" else "s"} · ${s.laps.size} laps",
                style = MaterialTheme.typography.bodySmall,
            )
            if (fastest != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Timer, null, tint = RT.Purple, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(Format.lap(fastest.timeMs), color = RT.Purple, style = Num.style(15.sp, FontWeight.Bold))
                    Text(" · ${s.racer(fastest.racerId)?.name ?: ""}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        if (podium.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Icon(Icons.Rounded.EmojiEvents, "Winner", tint = RT.Gold, modifier = Modifier.size(16.dp))
                Spacer(Modifier.height(4.dp))
                AvatarStack(podium, 30.dp)
            }
        }
    }
}

private fun dayLabel(epochMs: Long): String {
    val day = Platform.localDay(epochMs)
    val today = Platform.localDay(Platform.nowMillis())
    val fmt = Platform.formatDate(epochMs, "EEE, MMM d")
    return when (day) {
        today -> "Today · $fmt"
        today - 1 -> "Yesterday · $fmt"
        else -> fmt
    }
}
