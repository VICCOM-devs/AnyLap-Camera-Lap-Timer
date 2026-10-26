package app.anylap.ui.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.anylap.data.RaceMode
import app.anylap.data.Sport
import app.anylap.events.Event
import app.anylap.events.EventLogic
import app.anylap.events.Round
import app.anylap.events.RoundKind
import app.anylap.platform.Platform
import app.anylap.pro.Limits
import app.anylap.pro.ProFeature
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import app.anylap.ui.components.AvatarStack
import app.anylap.ui.components.EmptyState
import app.anylap.ui.components.PillTabs
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.RtTextField
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.Stepper
import app.anylap.ui.components.StripeCard
import app.anylap.ui.components.Tag
import app.anylap.ui.components.uiColor
import app.anylap.ui.pro.ProLockCard
import app.anylap.ui.pro.currentTier
import app.anylap.ui.session.RacerChip
import app.anylap.ui.theme.RT
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalLayoutApi::class, ExperimentalUuidApi::class)
@Composable
fun EventsScreen(nav: Navigator) {
    val app = LocalApp.current
    val tier = currentTier()
    val events by app.repo.events.collectAsState()
    val racers by app.repo.racers.collectAsState()
    val sessions by app.repo.sessions.collectAsState()

    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var sportIdx by remember { mutableIntStateOf(0) }
    val sport = if (sportIdx == 0) Sport.RC else Sport.RUNNING
    var selected by remember(sport) { mutableStateOf(racers.filter { it.sport == sport }.map { it.id }) }
    var timed by remember { mutableStateOf(false) }
    var laps by remember { mutableIntStateOf(10) }
    var minutes by remember { mutableIntStateOf(5) }
    var heatsEach by remember { mutableIntStateOf(2) }
    val perHeat = Limits.maxRacers(sport, tier)

    ScreenScaffold("Events", subtitle = "Heats, points and a final", onBack = { nav.pop() }) {
        if (!tier.isPro) {
            item { ProLockCard(nav, ProFeature.EVENTS) }
            return@ScreenScaffold
        }

        if (!creating) {
            item {
                PrimaryButton("New event", {
                    creating = true
                    name = "Race day " + Platform.formatDate(Platform.nowMillis(), "d MMM")
                }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
            }
        } else {
            item { SectionLabel("New event", Icons.Rounded.Flag) }
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    RtTextField(name, { name = it.take(40) }, "Name")
                    Spacer(Modifier.height(12.dp))
                    PillTabs(listOf("RC cars", "Running"), sportIdx, { sportIdx = it }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    val sportRacers = racers.filter { it.sport == sport }.sortedBy { it.number }
                    if (sportRacers.isEmpty()) {
                        Text("Add some ${sport.unitName}s under Racers first.", style = MaterialTheme.typography.bodyMedium, color = RT.Amber)
                    } else {
                        Text("Who's racing", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sportRacers.forEach { r ->
                                val sel = r.id in selected
                                RacerChip(r, sel) { selected = if (sel) selected - r.id else selected + r.id }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PillTabs(listOf("Race to laps", "Timed"), if (timed) 1 else 0, { timed = it == 1 }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (timed) "Each race" else "Each race to", style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.weight(1f))
                        if (timed) Stepper(minutes, { minutes = it }, 1..60, "min") else Stepper(laps, { laps = it }, 1..100, "laps")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Heats per racer", style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.weight(1f))
                        Stepper(heatsEach, { heatsEach = it }, 1..5, "")
                    }
                    val plan = EventLogic.heats(selected, perHeat, heatsEach)
                    Text(
                        if (selected.size < 2) "Pick at least 2 racers."
                        else "${plan.size} heat${if (plan.size == 1) "" else "s"} of up to $perHeat · then an optional final.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Cancel", { creating = false })
                        PrimaryButton(
                            "Create",
                            {
                                val ordered = sportRacers.map { it.id }.filter { it in selected }
                                val rounds = EventLogic.heats(ordered, perHeat, heatsEach).mapIndexed { i, ids ->
                                    Round(Uuid.random().toString(), "Heat ${i + 1}", RoundKind.HEAT, ids)
                                }
                                val e = Event(
                                    id = Uuid.random().toString(),
                                    name = name.ifBlank { "Race day" },
                                    sport = sport,
                                    createdAt = Platform.nowMillis(),
                                    racerIds = ordered,
                                    mode = if (timed) RaceMode.TIME else RaceMode.LAPS,
                                    targetLaps = laps,
                                    timeLimitSec = minutes * 60,
                                    rounds = rounds,
                                )
                                app.repo.upsertEvent(e)
                                creating = false
                                nav.push(Screen.EventDetail(e.id))
                            },
                            Modifier.weight(1f),
                            enabled = selected.size >= 2,
                        )
                    }
                }
            }
        }

        item { SectionLabel("Your events", Icons.Rounded.EmojiEvents) }
        if (events.isEmpty()) {
            item {
                EmptyState(
                    Icons.Rounded.EmojiEvents, "No events yet",
                    "Set up heats for the whole group, run them one by one and the points table builds itself. Finish with a final for the top racers.",
                )
            }
        }
        items(events, key = { it.id }) { e ->
            val leader = remember(e, sessions) {
                EventLogic.standings(e, sessions).firstOrNull()?.takeIf { it.points > 0 }?.let { app.repo.racer(it.racerId) }
            }
            val done = e.rounds.count { it.sessionId != null }
            StripeCard(leader?.uiColor ?: RT.Accent, Modifier.fillMaxWidth(), onClick = { nav.push(Screen.EventDetail(e.id)) }) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        Platform.formatDate(e.createdAt, "d MMM yyyy") + " · ${e.racerIds.size} ${e.sport.unitName}s · $done/${e.rounds.size} rounds",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (e.finished) Tag("Finished", RT.Green) else Tag("In progress", RT.Amber)
                        leader?.let { Tag("Leader: ${it.name}", RT.Gold) }
                    }
                }
                Spacer(Modifier.width(10.dp))
                AvatarStack(e.racerIds.mapNotNull { app.repo.racer(it) }.take(4), 28.dp)
            }
        }
    }
}
