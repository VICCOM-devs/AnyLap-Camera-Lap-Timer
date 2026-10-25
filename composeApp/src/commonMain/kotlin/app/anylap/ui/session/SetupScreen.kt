package app.anylap.ui.session

import app.anylap.util.fixed
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.anylap.data.RaceMode
import app.anylap.data.Racer
import app.anylap.data.SessionConfig
import app.anylap.data.Sport
import app.anylap.ui.components.PillTabs
import app.anylap.ui.components.RtSlider
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.Stepper
import app.anylap.ui.components.Tag
import app.anylap.ui.theme.RT
import app.anylap.pro.Limits
import app.anylap.pro.ProFeature
import app.anylap.ui.pro.currentTier

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(nav: Navigator) {
    val app = LocalApp.current
    val settings by app.repo.settings.collectAsState()
    val allRacers by app.repo.racers.collectAsState()
    var cfg by remember { mutableStateOf(settings.lastConfig) }

    val sportRacers = allRacers.filter { it.sport == cfg.sport }.sortedBy { it.number }
    val tier = currentTier()
    val maxRacers = Limits.maxRacers(cfg.sport, tier)
    val selected = cfg.racerIds.filter { id -> sportRacers.any { it.id == id } }.take(maxRacers)

    fun setSport(s: Sport) {
        if (s == cfg.sport) return
        cfg = cfg.copy(
            sport = s,
            racerIds = emptyList(),
            cameraDistanceM = s.defaultDistanceM,
            minLapSec = s.defaultMinLapSec,
            lapLengthM = if (s == Sport.RUNNING) (cfg.lapLengthM ?: 400f) else null,
        )
    }

    ScreenScaffold(title = "New session", subtitle = "Pick racers, format and camera distance") {
        item { PillTabs(listOf("RC cars", "Running"), if (cfg.sport == Sport.RC) 0 else 1, { setSport(if (it == 0) Sport.RC else Sport.RUNNING) }, Modifier.fillMaxWidth()) }

        // ---------------- format
        item { SectionLabel("Format", Icons.Rounded.Timer) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                PillTabs(
                    RaceMode.entries.map { it.short },
                    RaceMode.entries.indexOf(cfg.mode),
                    { cfg = cfg.copy(mode = RaceMode.entries[it]) },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                when (cfg.mode) {
                    RaceMode.PRACTICE -> Text(
                        "Open practice. Each racer's clock starts at their first crossing; every pass after that is a lap. Ranked by best lap.",
                        style = MaterialTheme.typography.bodyMedium, color = RT.TextDim,
                    )
                    RaceMode.LAPS -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("First to", style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.weight(1f))
                        Stepper(cfg.targetLaps, { cfg = cfg.copy(targetLaps = it) }, 1..200, "laps")
                    }
                    RaceMode.TIME -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Most laps in", style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.weight(1f))
                        Stepper(cfg.timeLimitSec / 60, { cfg = cfg.copy(timeLimitSec = it * 60) }, 1..180, "min")
                    }
                }
            }
        }

        // ---------------- racers
        item {
            SectionLabel(
                "Racers",
                Icons.Rounded.Groups,
                trailing = { Tag("${selected.size}/$maxRacers", if (selected.isEmpty()) RT.Amber else RT.Green) },
            )
        }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                if (sportRacers.isEmpty()) {
                    Text("No ${cfg.sport.unitName}s yet.", style = MaterialTheme.typography.bodyMedium, color = RT.TextDim)
                    Spacer(Modifier.height(10.dp))
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        sportRacers.forEach { r ->
                            val isSel = r.id in selected
                            RacerChip(r, isSel) {
                                cfg = if (isSel) cfg.copy(racerIds = selected - r.id)
                                else if (selected.size < maxRacers) cfg.copy(racerIds = selected + r.id)
                                else {
                                    if (!tier.isPro && selected.size < cfg.sport.maxRacers) nav.push(Screen.Paywall(ProFeature.MORE_RACERS))
                                    cfg
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!tier.isPro && sportRacers.size > maxRacers) {
                        Text(
                            "Free races up to $maxRacers at once. Pro races the full grid of ${cfg.sport.maxRacers}.",
                            style = MaterialTheme.typography.bodySmall, color = RT.Gold,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    val uncalibrated = sportRacers.filter { it.id in selected && !it.isCalibrated && it.markerId == null }
                    if (selected.size > 1 && uncalibrated.isNotEmpty()) {
                        Text(
                            "${uncalibrated.joinToString { it.name }} ${if (uncalibrated.size == 1) "isn't" else "aren't"} calibrated – " +
                                "their crossings will ask you who it was.",
                            style = MaterialTheme.typography.bodySmall, color = RT.Amber,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
                SecondaryButton(
                    "Add ${cfg.sport.unitName}",
                    {
                        val r = app.repo.newRacer(cfg.sport)
                        app.repo.upsertRacer(r)
                        nav.push(Screen.RacerEdit(r.id))
                    },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Add,
                )
            }
        }

        // ---------------- camera
        item { SectionLabel("Camera", Icons.Rounded.CameraAlt) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Distance to finish line", style = MaterialTheme.typography.titleSmall)
                        Text("From the phone to where racers cross. Used for speed.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${(cfg.cameraDistanceM).fixed(1)} m", style = MaterialTheme.typography.titleLarge)
                }
                RtSlider(
                    value = cfg.cameraDistanceM,
                    onValueChange = { cfg = cfg.copy(cameraDistanceM = (kotlin.math.round(it * 2) / 2f)) },
                    valueRange = 0.5f..15f,
                )
                Text(
                    if (cfg.sport == Sport.RC) "Tip: 1.5–4 m works well for 1/28–1/10 cars. Point the camera across the track, square to it."
                    else "Tip: 3–6 m keeps bib markers readable. Point the camera across the track at chest height.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (cfg.sport == Sport.RUNNING) {
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Straighten, null, tint = RT.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Lap length", style = MaterialTheme.typography.titleSmall)
                            Text("For pace and distance. 0 = off.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Stepper((cfg.lapLengthM ?: 0f).toInt(), { cfg = cfg.copy(lapLengthM = it.toFloat().takeIf { v -> v > 0f }) }, 0..20000, "m", step = 50)
                    }
                }
            }
        }

        item { SectionLabel("Timing", Icons.Rounded.Tune) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Shortest possible lap", style = MaterialTheme.typography.titleSmall)
                        Text("Crossings closer together are ignored (stops double counts).", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Stepper(cfg.minLapSec.toInt(), { cfg = cfg.copy(minLapSec = it.toFloat()) }, 1..600, "s")
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                "Line up camera",
                {
                    val final = cfg.copy(racerIds = selected)
                    app.repo.updateSettings { it.copy(lastConfig = final) }
                    app.pendingConfig = final
                    nav.push(Screen.Live)
                },
                Modifier.fillMaxWidth(),
                icon = Icons.Rounded.CameraAlt,
                enabled = selected.isNotEmpty(),
            )
            if (selected.isEmpty()) {
                Text(
                    "Pick at least one ${cfg.sport.unitName}.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RacerChip(r: Racer, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) RT.AccentSoft else RT.Surface2)
            .border(1.dp, if (selected) RT.Accent else RT.Stroke, shape)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RacerAvatar(r, 30.dp)
        Spacer(Modifier.width(8.dp))
        Text(r.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = IntrinsicWidthCap))
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(16.dp).clip(CircleShape).background(RT.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
}

private val IntrinsicWidthCap = 120.dp
