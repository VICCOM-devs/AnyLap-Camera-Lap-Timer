package app.anylap.ui.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.RaceMode
import app.anylap.data.SessionConfig
import app.anylap.engine.Format
import app.anylap.events.Event
import app.anylap.events.EventLogic
import app.anylap.events.Round
import app.anylap.events.RoundKind
import app.anylap.platform.Platform
import app.anylap.pro.Limits
import app.anylap.ui.AppState
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import app.anylap.ui.components.AvatarStack
import app.anylap.ui.components.CircleIconButton
import app.anylap.ui.components.PositionBadge
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.Stepper
import app.anylap.ui.components.Tag
import app.anylap.ui.pro.currentTier
import app.anylap.ui.theme.Num
import app.anylap.ui.theme.RT
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Composable
fun EventScreen(nav: Navigator, eventId: String) {
    val app = LocalApp.current
    val tier = currentTier()
    val events by app.repo.events.collectAsState()
    val sessions by app.repo.sessions.collectAsState()
    val e = events.firstOrNull { it.id == eventId }
    var confirmDelete by remember { mutableStateOf(false) }

    if (e == null) {
        ScreenScaffold("Event", onBack = { nav.pop() }) { item { Text("Event not found.", color = RT.TextDim) } }
        return
    }
    val standings = remember(e, sessions) { EventLogic.standings(e, sessions) }
    val next = EventLogic.nextRound(e)
    val heatsDone = e.rounds.filter { it.kind == RoundKind.HEAT }.all { it.sessionId != null }
    val maxGrid = Limits.maxRacers(e.sport, tier)
    var finalSize by remember(e.id) { mutableIntStateOf(minOf(maxGrid, e.racerIds.size)) }

    ScreenScaffold(
        e.name,
        subtitle = formatLine(e),
        onBack = { nav.pop() },
        actions = {
            CircleIconButton(Icons.Rounded.Share, { shareStandings(app, e, sessions) }, contentDescription = "Share standings")
            CircleIconButton(Icons.Rounded.Delete, { confirmDelete = true }, contentDescription = "Delete event")
        },
        bottomPadding = 40.dp,
    ) {
        if (next != null) {
            item {
                PrimaryButton(
                    "Run ${next.name}",
                    { runRound(app, nav, e, next) },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.PlayArrow,
                )
            }
        } else if (e.finished) {
            standings.firstOrNull()?.let { s ->
                app.repo.racer(s.racerId)?.let { champ ->
                    item {
                        RtCard(Modifier.fillMaxWidth(), highlight = RT.Gold.copy(alpha = 0.5f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RacerAvatar(champ, 52.dp, ring = RT.Gold)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("WINNER", style = MaterialTheme.typography.labelSmall, color = RT.Gold)
                                    Text(champ.name, style = MaterialTheme.typography.headlineSmall)
                                    Text("${s.points} points · ${s.wins} wins", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionLabel("Standings", Icons.Rounded.Leaderboard) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                standings.forEach { s ->
                    val r = app.repo.racer(s.racerId) ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PositionBadge(s.position, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        RacerAvatar(r, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                "${s.roundsRun} raced · ${s.wins} wins · best ${Format.lap(s.bestLapMs)}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            )
                        }
                        Text("${s.points}", style = Num.style(22.sp, FontWeight.Bold), color = if (s.position == 1 && s.points > 0) RT.Gold else RT.Text)
                    }
                }
                Text(
                    "Points ${e.points.joinToString("-")} by finishing position. Finals count ×${e.finalMultiplier}.",
                    style = MaterialTheme.typography.bodySmall, color = RT.TextFaint,
                )
            }
        }

        item { SectionLabel("Rounds", Icons.Rounded.Flag) }
        e.rounds.forEach { round ->
            item(key = round.id) {
                val s = round.sessionId?.let { id -> sessions.firstOrNull { it.id == id } }
                val winner = s?.order?.firstOrNull()?.let { app.repo.racer(it) }
                RtCard(
                    Modifier.fillMaxWidth(),
                    onClick = {
                        if (s != null) nav.push(Screen.Results(s.id)) else runRound(app, nav, e, round)
                    },
                    highlight = if (round == next) RT.AccentDeep else null,
                    padding = 14.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(round.name, style = MaterialTheme.typography.titleMedium)
                                if (round.kind == RoundKind.FINAL) Tag("×${e.finalMultiplier} points", RT.Gold)
                            }
                            Text(
                                when {
                                    winner != null -> "Won by ${winner.name}"
                                    s != null -> "Done"
                                    round == next -> "Up next – tap to run"
                                    else -> "${round.racerIds.size} ${e.sport.unitName}s"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        AvatarStack(round.racerIds.mapNotNull { app.repo.racer(it) }, 28.dp)
                    }
                }
            }
        }

        if (heatsDone && !e.hasFinal && e.racerIds.size >= 2) {
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    Text("Add a final", style = MaterialTheme.typography.titleSmall)
                    Text("The top of the points table after the heats, best qualifier listed first.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Racers in the final", style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.weight(1f))
                        Stepper(finalSize, { finalSize = it }, 2..minOf(maxGrid, e.racerIds.size), "")
                    }
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Add final", {
                        val grid = EventLogic.finalGrid(e, sessions, finalSize)
                        app.repo.upsertEvent(e.copy(rounds = e.rounds + Round(Uuid.random().toString(), "Final", RoundKind.FINAL, grid)))
                    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.EmojiEvents)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = RT.Surface2,
            title = { Text("Delete ${e.name}?") },
            text = { Text("The sessions stay in your history; only the event and its points table go.", color = RT.TextDim) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; nav.pop(); app.repo.deleteEvent(e.id) }) { Text("Delete", color = RT.Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = RT.Text) } },
        )
    }
}

private fun formatLine(e: Event): String = when (e.mode) {
    RaceMode.TIME -> "${e.timeLimitSec / 60}-min races"
    else -> "${e.targetLaps}-lap races"
} + " · ${e.racerIds.size} ${e.sport.unitName}s"

/** Opens the live screen for a round with the event's format and the round's racers. */
private fun runRound(app: AppState, nav: Navigator, e: Event, round: Round) {
    val last = app.repo.settings.value.lastConfig
    val base = if (last.sport == e.sport) last else SessionConfig(sport = e.sport)
    app.pendingConfig = base.copy(
        mode = e.mode,
        targetLaps = e.targetLaps,
        timeLimitSec = e.timeLimitSec,
        racerIds = round.racerIds,
    )
    app.pendingRound = e.id to round.id
    nav.push(Screen.Live)
}

private fun shareStandings(app: AppState, e: Event, sessions: List<app.anylap.data.Session>) {
    val sb = StringBuilder()
    sb.appendLine("${e.name} – ${Platform.formatDate(e.createdAt, "d MMM yyyy")}")
    EventLogic.standings(e, sessions).forEach { s ->
        val name = app.repo.racer(s.racerId)?.name ?: "?"
        sb.appendLine("P${s.position} $name – ${s.points} pts (${s.wins} wins)")
    }
    Platform.shareText(sb.toString(), "Share standings")
}
