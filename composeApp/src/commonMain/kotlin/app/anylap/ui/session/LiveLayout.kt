package app.anylap.ui.session

import app.anylap.engine.RaceEngine
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.Direction
import app.anylap.data.LineSpec
import app.anylap.data.RaceMode
import app.anylap.data.Racer
import app.anylap.data.SessionConfig
import app.anylap.engine.Format
import app.anylap.engine.LapFlash
import app.anylap.engine.Phase
import app.anylap.engine.RaceState
import app.anylap.engine.Standing
import app.anylap.engine.Unassigned
import app.anylap.ui.components.CircleIconButton
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.Tag
import app.anylap.ui.components.code
import app.anylap.ui.components.uiColor
import app.anylap.ui.theme.Num
import app.anylap.ui.theme.RT
import app.anylap.util.fixed
import kotlinx.coroutines.delay

/**
 * Live timing screen, broadcast style: camera on the left, timing tower on the right.
 * Stateless – everything comes in through parameters (see LiveScreen for the wiring).
 */
@Composable
fun LiveLayout(
    config: SessionConfig,
    race: RaceState,
    speedKmh: Boolean,
    fps: Double,
    recording: Boolean,
    error: String?,
    line: LineSpec,
    direction: Direction,
    previewAspect: Float,
    voice: Boolean,
    live: Boolean,
    onLive: () -> Unit,
    onLineChange: (LineSpec) -> Unit,
    onDirection: () -> Unit,
    onResetBackground: () -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onToggleVoice: () -> Unit,
    onAssign: (Unassigned) -> Unit,
    onAddLap: (String) -> Unit,
    onRemoveLap: (String) -> Unit,
    camera: @Composable BoxScope.() -> Unit,
) {
    val setup = race.phase == Phase.SETUP
    // The finish line only moves while this is on (no accidental drags mid-session).
    var editingLine by remember { mutableStateOf(true) }
    Row(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------------------------------------------------------------- camera
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(previewAspect)) {
                camera()
                FinishLineOverlay(line, direction, editable = setup && editingLine, onChange = onLineChange)

                if (setup) {
                    // Compact: a one-line summary and fps, nothing covering the track.
                    Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("${modeLabel(config)} · ${race.standings.size} ${config.sport.unitName}${if (race.standings.size == 1) "" else "s"}")
                        HudChip("${fps.fixed(0)} FPS")
                    }
                    if (editingLine) {
                        LineTools(
                            direction = direction,
                            onDirection = onDirection,
                            onResetBackground = onResetBackground,
                            onCenter = { onLineChange(LineSpec()) },
                            onDone = { editingLine = false },
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                        )
                        CameraHint("Drag the ends onto the tape on the ground", Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                    }
                } else {
                    Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                        ClockHud(race, config)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudChip("${fps.fixed(0)} FPS")
                            if (recording) RecChip()
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = race.phase == Phase.COUNTDOWN,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) { StartLights(race.countdown) }

                LapFlashBanner(race.flash, speedKmh, Modifier.align(Alignment.BottomStart).padding(14.dp))

                if (race.unassigned.isNotEmpty()) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(14.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        race.unassigned.takeLast(6).forEach { u ->
                            Row(
                                Modifier
                                    .heightIn(min = 44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(RT.Amber)
                                    .clickable { onAssign(u) }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.HelpOutline, "Unknown crossing – tap to assign", tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(Format.clock(u.atMs), color = Color.Black, style = Num.style(14.sp, FontWeight.Bold))
                            }
                        }
                    }
                }
                if (error != null) {
                    Text(
                        error, color = Color.White, style = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
                            .clip(RoundedCornerShape(10.dp)).background(RT.Accent.copy(alpha = 0.9f))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- right side
        if (setup) {
            SetupRail(
                config = config,
                race = race,
                voice = voice,
                live = live,
                editingLine = editingLine,
                onEditLine = { editingLine = !editingLine },
                onBack = onBack,
                onLive = onLive,
                onToggleVoice = onToggleVoice,
                onStart = { editingLine = false; onStart() },
            )
        } else TimingTower(
            config = config,
            race = race,
            voice = voice,
            live = live,
            onLive = onLive,
            onToggleVoice = onToggleVoice,
            onStart = onStart,
            onEnd = onEnd,
            onAddLap = onAddLap,
            onRemoveLap = onRemoveLap,
            modifier = Modifier.width(352.dp).fillMaxHeight(),
        )
    }
}

// ------------------------------------------------------------------ HUD

@Composable
private fun ClockHud(race: RaceState, config: SessionConfig) {
    val timeUp = race.timeUp
    val main = when {
        race.phase == Phase.SETUP -> "READY"
        config.mode == RaceMode.TIME && !timeUp -> Format.clock(config.timeLimitSec * 1000L - race.elapsedMs)
        else -> Format.clock(race.elapsedMs)
    }
    val label = when (config.mode) {
        RaceMode.PRACTICE -> "PRACTICE"
        RaceMode.LAPS -> "LAP ${(race.leaderLaps + 1).coerceAtMost(config.targetLaps)}/${config.targetLaps}"
        RaceMode.TIME -> if (timeUp) "FINAL LAP" else "TIME LEFT"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.background(if (timeUp) RT.Amber else RT.Accent).padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (timeUp) Color.Black else Color.White)
        }
        Text(
            main,
            style = Num.style(30.sp, FontWeight.Black, italic = true),
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

@Composable
private fun HudChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(color = Color.White, letterSpacing = 1.sp),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun RecChip() {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(RT.AccentHot))
        Spacer(Modifier.width(5.dp))
        Text("REC", style = MaterialTheme.typography.labelSmall.copy(color = Color.White))
    }
}

/** Five red start lights that come on one per second; the race starts when they all go out. */
@Composable
private fun StartLights(lit: Int) {
    Column(
        Modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(RaceEngine.START_LIGHTS) { i ->
                // a column of two lamps per light, like a real gantry
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(2) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (i < lit) Brush.radialGradient(listOf(RT.AccentHot, RT.Accent, RT.AccentDeep)) else Brush.radialGradient(listOf(RT.Surface3, RT.Surface)))
                                .border(2.dp, Color.White.copy(alpha = 0.08f), CircleShape),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (lit < RaceEngine.START_LIGHTS) "GET READY" else "LIGHTS OUT AND AWAY WE GO…",
            style = MaterialTheme.typography.labelSmall.copy(color = Color.White, letterSpacing = 1.5.sp),
        )
    }
}

/** Setup phase: everything on one slim rail so the camera gets the screen. */
@Composable
private fun SetupRail(
    config: SessionConfig,
    race: RaceState,
    voice: Boolean,
    live: Boolean,
    editingLine: Boolean,
    onEditLine: () -> Unit,
    onBack: () -> Unit,
    onLive: () -> Unit,
    onToggleVoice: () -> Unit,
    onStart: () -> Unit,
) {
    val racers = race.standings.map { it.racer }
    CameraRail {
        RailButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        RailButton(Icons.Rounded.Flag, "Line", onEditLine, selected = editingLine)
        RailButton(
            if (voice) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            if (voice) "Voice on" else "Voice off",
            onToggleVoice,
        )
        RailButton(Icons.Rounded.Sensors, if (live) "Synced" else "Sync", onLive, selected = live)
        Spacer(Modifier.weight(1f))
        // Who's in the session: small avatars, "+n" if they don't fit.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((-8).dp)) {
            racers.take(4).forEach { RacerAvatar(it, 28.dp, ring = RT.Bg) }
        }
        if (racers.size > 4) Text("+${racers.size - 4}", style = MaterialTheme.typography.labelSmall, color = RT.TextDim)
        Spacer(Modifier.height(4.dp))
        RailButton(
            Icons.Rounded.PlayArrow,
            if (config.mode == RaceMode.PRACTICE) "Start" else "Start race",
            onStart,
            size = 56.dp,
            filled = true,
        )
    }
}

private fun modeLabel(c: SessionConfig): String = when (c.mode) {
    RaceMode.PRACTICE -> "PRACTICE"
    RaceMode.LAPS -> "${c.targetLaps} LAPS"
    RaceMode.TIME -> "${c.timeLimitSec / 60} MIN"
}

/** Broadcast lower-third for each completed lap. */
@Composable
private fun LapFlashBanner(flash: LapFlash?, kmh: Boolean, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash != null) {
            visible = true
            delay(3200)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible && flash != null,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val f = flash ?: return@AnimatedVisibility
        val accent = when {
            f.fastest -> RT.Purple
            f.personalBest -> RT.Green
            else -> RT.Text
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.82f))
                .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "${f.racer.name} lap ${f.lap.number} ${Format.lap(f.lap.timeMs)}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.background(RT.Accent).padding(horizontal = 14.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${f.position}", style = Num.style(26.sp, FontWeight.Black, italic = true)) }
            Box(Modifier.width(5.dp).height(58.dp).background(f.racer.uiColor))
            Column(Modifier.padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.racer.code, style = Num.style(15.sp, FontWeight.Black))
                    Spacer(Modifier.width(8.dp))
                    Text("LAP ${f.lap.number}", style = MaterialTheme.typography.labelSmall)
                    if (f.fastest || f.personalBest) {
                        Spacer(Modifier.width(8.dp))
                        Tag(if (f.fastest) "Fastest lap" else "Personal best", accent, filled = true)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Format.lap(f.lap.timeMs), color = accent, style = Num.style(30.sp, FontWeight.Black, italic = true))
                    if (f.lap.speedKmh != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(Format.speed(f.lap.speedKmh, kmh), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ timing tower

@Composable
private fun TimingTower(
    config: SessionConfig,
    race: RaceState,
    voice: Boolean,
    live: Boolean,
    onLive: () -> Unit,
    onToggleVoice: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onAddLap: (String) -> Unit,
    onRemoveLap: (String) -> Unit,
    modifier: Modifier,
) {
    val isRace = config.mode != RaceMode.PRACTICE
    Column(modifier.background(RT.Bg)) {
        // header
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF3A080B), RT.Bg)))
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (config.mode) {
                        RaceMode.PRACTICE -> "PRACTICE"
                        RaceMode.LAPS -> "${config.targetLaps}-LAP RACE"
                        RaceMode.TIME -> "${config.timeLimitSec / 60}-MIN RACE"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                )
                Text(
                    "${race.standings.size} ${config.sport.unitName}${if (race.standings.size == 1) "" else "s"} · camera ${config.cameraDistanceM.fixed(1)} m",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            CircleIconButton(
                Icons.Rounded.Sensors, onLive, tint = if (live) RT.Accent else RT.Text, size = 44.dp,
                contentDescription = if (live) "Other phones can join" else "Sync: let other phones join",
            )
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                if (voice) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                onToggleVoice, size = 44.dp,
                contentDescription = if (voice) "Mute lap announcements" else "Announce lap times",
            )
        }
        // column headings
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("POS", style = MaterialTheme.typography.labelSmall, color = RT.TextFaint, modifier = Modifier.width(34.dp))
            Text(if (isRace) "RACER · LAST LAP" else "RACER · LAST LAP", style = MaterialTheme.typography.labelSmall, color = RT.TextFaint, modifier = Modifier.weight(1f))
            Text(if (isRace) "GAP" else "BEST", style = MaterialTheme.typography.labelSmall, color = RT.TextFaint)
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(race.standings, key = { it.racer.id }) { s ->
                TowerRow(s, isRace, running = race.phase == Phase.RUNNING, onAddLap = { onAddLap(s.racer.id) }, onRemoveLap = { onRemoveLap(s.racer.id) })
            }
        }
        Box(Modifier.padding(12.dp)) {
            when (race.phase) {
                Phase.SETUP -> PrimaryButton(if (isRace) "Start race" else "Start practice", onStart, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
                Phase.COUNTDOWN -> PrimaryButton("Get ready…", {}, Modifier.fillMaxWidth(), enabled = false)
                Phase.RUNNING -> PrimaryButton("End session", onEnd, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, color = RT.Surface3)
                Phase.FINISHED -> PrimaryButton("Saving…", {}, Modifier.fillMaxWidth(), icon = Icons.Rounded.Flag, enabled = false)
            }
        }
    }
}

@Composable
private fun TowerRow(s: Standing, isRace: Boolean, running: Boolean, onAddLap: () -> Unit, onRemoveLap: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val leader = s.position == 1 && s.laps > 0
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (leader) RT.Surface2 else RT.Surface)
                .border(1.dp, if (s.finished) RT.Gold.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(enabled = running, onClickLabel = "Add or remove a lap") { menu = true }
                .drawBehind {
                    if (leader) drawRect(Brush.horizontalGradient(listOf(RT.Accent.copy(alpha = 0.18f), Color.Transparent), endX = size.width * 0.5f))
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${s.position}",
                style = Num.style(20.sp, FontWeight.Black, italic = true),
                color = if (s.position <= 3 && s.laps > 0) RT.podium(s.position) else RT.TextDim,
                modifier = Modifier.width(34.dp).padding(start = 10.dp),
            )
            Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(s.racer.uiColor))
            Spacer(Modifier.width(10.dp))
            RacerAvatar(s.racer, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.racer.code, style = Num.style(16.sp, FontWeight.Black))
                    Spacer(Modifier.width(6.dp))
                    Text(s.racer.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (s.finished) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Flag, "Finished", tint = RT.Gold, modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    when {
                        !isRace && !s.timing -> "Waiting for first pass"
                        s.lastLapMs == null -> "L${s.laps}"
                        else -> "L${s.laps} · ${Format.lap(s.lastLapMs)}"
                    },
                    style = Num.style(13.sp, FontWeight.Medium),
                    color = RT.TextDim,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                val primary = when {
                    s.laps == 0 && isRace -> "—"
                    isRace -> if (s.position == 1) "LEADER" else s.gap
                    else -> Format.lap(s.bestLapMs)
                }
                Text(
                    primary,
                    style = if (primary == "LEADER") MaterialTheme.typography.labelSmall else Num.style(15.sp, FontWeight.Bold),
                    color = when {
                        !isRace && s.hasFastestLap -> RT.Purple
                        primary == "LEADER" -> RT.AccentHot
                        else -> RT.Text
                    },
                )
                if (isRace && s.bestLapMs != null) {
                    Text(Format.lap(s.bestLapMs), style = Num.style(12.sp, FontWeight.SemiBold), color = if (s.hasFastestLap) RT.Purple else RT.TextFaint)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Add lap now") }, onClick = { menu = false; onAddLap() })
            DropdownMenuItem(text = { Text("Remove last lap") }, onClick = { menu = false; onRemoveLap() })
        }
    }
}
