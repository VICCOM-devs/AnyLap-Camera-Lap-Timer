package app.anylap.ui.session

import app.anylap.util.fixed
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import app.anylap.platform.Platform
import app.anylap.platform.PlatformBackHandler
import app.anylap.platform.Speaker
import app.anylap.platform.joinPath
import app.anylap.vision.CameraPermissionGate
import app.anylap.vision.CameraPreview
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.Direction
import app.anylap.data.RaceMode
import app.anylap.data.Sport
import app.anylap.engine.Format
import app.anylap.engine.LapFlash
import app.anylap.engine.Phase
import app.anylap.engine.RaceEngine
import app.anylap.engine.Standing
import app.anylap.engine.Unassigned
import app.anylap.ui.components.CameraScreenMode
import app.anylap.ui.components.CircleIconButton
import app.anylap.ui.components.PositionBadge
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.Tag
import app.anylap.ui.components.uiColor
import app.anylap.ui.theme.RT
import app.anylap.vision.FrameAnalyzer
import app.anylap.vision.Optics
import app.anylap.vision.RacerRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(nav: Navigator) {
    CameraScreenMode()
    CameraPermissionGate(onBack = { nav.pop() }) { LiveContent(nav) }
}

@Composable
private fun LiveContent(nav: Navigator) {
    val app = LocalApp.current
    val config = remember { app.pendingConfig ?: app.repo.settings.value.lastConfig }
    val racers = remember { config.racerIds.mapNotNull { app.repo.racer(it) } }
    val settings by app.repo.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val session = rememberVisionSession()

    var line by remember { mutableStateOf(config.line) }
    var direction by remember { mutableStateOf(config.direction) }
    var finishedSignal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<Unassigned?>(null) }
    var voice by remember { mutableStateOf(settings.announceLaps) }
    var recordingStartNs by remember { mutableStateOf<Long?>(null) }
    val videoPath = remember { joinPath(Platform.mediaDir, "session_${Platform.nowMillis()}.mp4") }

    val tts = remember { TtsHolder() }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    val engine = remember {
        RaceEngine(
            config = config.copy(line = line, direction = direction),
            racers = racers,
            scope = scope,
            onLap = { f -> if (tts.enabled) tts.say(announcement(f)) },
            onFinished = { finishedSignal = true },
        )
    }
    val race by engine.state.collectAsState()
    tts.enabled = voice

    if (session == null) {
        Box(Modifier.fillMaxSize().background(RT.Bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("The camera couldn't be started on this device.", color = RT.TextDim)
                TextButton(onClick = { nav.pop() }) { Text("Back", color = RT.Accent) }
            }
        }
        return
    }
    val camStats by session.camera.stats.collectAsState()
    val boxes by session.overlay.collectAsState()

    // Keep the analyzer in sync with the line, direction and racers.
    LaunchedEffect(line, direction) {
        val mpp = session.camera.setup.hfovRad?.let { Optics.metersPerPixel(config.cameraDistanceM, it, FrameAnalyzer.PROC_W) }
        session.analyzer.config = session.analyzer.config.copy(
            line = line,
            direction = direction,
            sport = config.sport,
            racers = racers.map { r ->
                RacerRef(r.id, r.fingerprint.takeIf { it.isNotEmpty() }?.toFloatArray(), r.markerId)
            },
            metersPerProcPx = mpp,
            useMarkers = config.sport == Sport.RUNNING && racers.any { it.markerId != null },
        )
    }
    LaunchedEffect(session, engine) {
        session.crossings.collect { engine.onCrossing(it) }
    }

    fun begin() {
        app.repo.updateSettings { s -> s.copy(lastConfig = s.lastConfig.copy(line = line, direction = direction)) }
        session.camera.setExposureLock(true)
        session.camera.startRecording(videoPath) { ns -> recordingStartNs = ns }
        engine.start()
    }

    // Save and show results once the session is over.
    LaunchedEffect(finishedSignal) {
        if (!finishedSignal || saving) return@LaunchedEffect
        saving = true
        session.analyzer.flush(Platform.monotonicNanos())
        session.camera.stopRecording { path ->
            val result = engine.buildSession(path, recordingStartNs)
                .let { it.copy(config = it.config.copy(line = line, direction = direction)) }
            app.repo.saveSession(result)
            app.pendingConfig = null
            scope.launchMain {
                nav.replace(Screen.Results(result.id))
            }
        }
    }

    PlatformBackHandler(true) {
        when (race.phase) {
            Phase.SETUP -> nav.pop()
            Phase.FINISHED -> {}
            else -> confirmEnd = true
        }
    }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------------------------------------------------------------- camera side
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(session.camera.setup.previewWidth.toFloat() / session.camera.setup.previewHeight)) {
                CameraPreview(session.camera, Modifier.fillMaxSize())
                TrackBoxesOverlay(boxes)
                FinishLineOverlay(line, direction, editable = race.phase == Phase.SETUP, onChange = { line = it })

                // HUD top-left
                Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    ClockHud(race.phase, race.elapsedMs, config.mode, config.timeLimitSec, config.targetLaps, race.leaderLaps, race.timeUp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("${(camStats.fps).fixed(0)} fps")
                        if (recordingStartNs != null && race.phase != Phase.SETUP) HudChip("● REC", RT.Accent)
                    }
                }
                // Controls top-right (setup only)
                if (race.phase == Phase.SETUP) {
                    Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { nav.pop() })
                    }
                    SetupHint(
                        direction = direction,
                        onDirection = {
                            direction = when (direction) {
                                Direction.BOTH -> Direction.FORWARD
                                Direction.FORWARD -> Direction.REVERSE
                                Direction.REVERSE -> Direction.BOTH
                            }
                        },
                        onResetBg = { session.analyzer.resetBackground() },
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    )
                }

                // Countdown
                androidx.compose.animation.AnimatedVisibility(
                    visible = race.phase == Phase.COUNTDOWN,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        race.countdown.toString(),
                        fontSize = 140.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                }

                // Lap flash
                LapFlashBanner(race.flash, settings.speedUnitKmh, Modifier.align(Alignment.BottomStart).padding(12.dp))

                // Unknown crossings tray
                if (race.unassigned.isNotEmpty()) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        race.unassigned.takeLast(6).forEach { u ->
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(RT.Amber)
                                    .clickable { assigning = u }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.HelpOutline, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(Format.clock(u.atMs), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                camStats.error?.let {
                    Text(
                        it, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                            .clip(RoundedCornerShape(8.dp)).background(RT.Accent.copy(alpha = 0.85f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- leaderboard side
        Column(Modifier.width(330.dp).fillMaxHeight().background(RT.Bg)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(RT.AccentDeep, RT.Bg))).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (config.mode) {
                            RaceMode.PRACTICE -> "Practice"
                            RaceMode.LAPS -> "${config.targetLaps}-lap race"
                            RaceMode.TIME -> "${config.timeLimitSec / 60}-minute race"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "${racers.size} ${config.sport.unitName}${if (racers.size == 1) "" else "s"} · ${"${(config.cameraDistanceM).fixed(1)}"} m",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                CircleIconButton(if (voice) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, {
                    voice = !voice
                    app.repo.updateSettings { it.copy(announceLaps = voice) }
                }, size = 38.dp)
            }
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(race.standings, key = { it.racer.id }) { s ->
                    StandingRow(
                        s,
                        isRace = config.mode != RaceMode.PRACTICE,
                        running = race.phase == Phase.RUNNING,
                        onAddLap = { engine.addManualLap(s.racer.id) },
                        onRemoveLap = { engine.removeLastLap(s.racer.id) },
                    )
                }
            }
            Box(Modifier.padding(12.dp)) {
                when (race.phase) {
                    Phase.SETUP -> PrimaryButton(
                        if (config.mode == RaceMode.PRACTICE) "Start practice" else "Start race",
                        { begin() },
                        Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.PlayArrow,
                    )
                    Phase.COUNTDOWN -> PrimaryButton("Get ready…", {}, Modifier.fillMaxWidth(), enabled = false)
                    Phase.RUNNING -> PrimaryButton(
                        "End session", { confirmEnd = true }, Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.Stop, color = Color(0xFF2B2B31),
                    )
                    Phase.FINISHED -> PrimaryButton("Saving…", {}, Modifier.fillMaxWidth(), icon = Icons.Rounded.Flag, enabled = false)
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            containerColor = RT.Surface2,
            title = { Text("End session?") },
            text = { Text("Results and video are saved.", color = RT.TextDim) },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; engine.finish() }) { Text("End", color = RT.Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going", color = RT.Text) } },
        )
    }

    assigning?.let { u ->
        AlertDialog(
            onDismissRequest = { assigning = null },
            containerColor = RT.Surface2,
            title = { Text("Who crossed at ${Format.clock(u.atMs)}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    u.suggestions.mapNotNull { id -> racers.firstOrNull { it.id == id } }.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RT.Surface3)
                                .clickable { engine.assign(u.id, r.id); assigning = null }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RacerAvatar(r, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { engine.dismiss(u.id); assigning = null }) { Text("Not a racer", color = RT.TextDim) }
            },
        )
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun ClockHud(phase: Phase, elapsedMs: Long, mode: RaceMode, limitSec: Int, targetLaps: Int, leaderLaps: Int, timeUp: Boolean) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        val main = when {
            phase == Phase.SETUP -> "READY"
            mode == RaceMode.TIME && !timeUp -> Format.clock(limitSec * 1000L - elapsedMs)
            else -> Format.clock(elapsedMs)
        }
        Text(main, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        val sub = when (mode) {
            RaceMode.PRACTICE -> "Practice"
            RaceMode.LAPS -> "Lap ${(leaderLaps + 1).coerceAtMost(targetLaps)} / $targetLaps"
            RaceMode.TIME -> if (timeUp) "Final lap" else "Time left"
        }
        Text(sub.uppercase(), color = if (timeUp) RT.Amber else RT.TextDim, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HudChip(text: String, color: Color = Color.White) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun SetupHint(direction: Direction, onDirection: () -> Unit, onResetBg: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Drag the line onto the tape on the ground", color = Color.White, style = MaterialTheme.typography.titleSmall)
        Text("Keep the phone still. Boxes show what the camera sees moving.", color = RT.TextDim, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(Icons.Rounded.SwapHoriz, when (direction) {
                Direction.BOTH -> "Counts both ways"
                Direction.FORWARD -> "One way →"
                Direction.REVERSE -> "One way ←"
            }, onDirection)
            SmallAction(Icons.Rounded.RestartAlt, "Reset background", onResetBg)
        }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(RT.Surface3).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LapFlashBanner(flash: LapFlash?, kmh: Boolean, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash != null) {
            visible = true
            delay(2800)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible && flash != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val f = flash ?: return@AnimatedVisibility
        val accent = when {
            f.fastest -> RT.Purple
            f.personalBest -> RT.Green
            else -> f.racer.uiColor
        }
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.78f))
                .border(1.dp, accent, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PositionBadge(f.position, 32.dp)
            Spacer(Modifier.width(10.dp))
            RacerAvatar(f.racer, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("${f.racer.name} · Lap ${f.lap.number}", color = Color.White, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.lap(f.lap.timeMs), color = accent, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    if (f.lap.speedKmh != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(Format.speed(f.lap.speedKmh, kmh), color = RT.TextDim, fontSize = 13.sp)
                    }
                }
            }
            if (f.fastest || f.personalBest) {
                Spacer(Modifier.width(10.dp))
                Tag(if (f.fastest) "Fastest" else "PB", accent, filled = true)
            }
        }
    }
}

@Composable
private fun StandingRow(s: Standing, isRace: Boolean, running: Boolean, onAddLap: () -> Unit, onRemoveLap: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (s.position == 1 && s.laps > 0) RT.Surface2 else RT.Surface)
                .border(1.dp, if (s.finished) RT.Gold.copy(alpha = 0.6f) else RT.Stroke, RoundedCornerShape(14.dp))
                .clickable(enabled = running) { menu = true }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PositionBadge(s.position, 30.dp)
            Spacer(Modifier.width(8.dp))
            RacerAvatar(s.racer, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.racer.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (s.finished) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Flag, null, tint = RT.Gold, modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    when {
                        !isRace && !s.timing -> "Waiting for first pass"
                        s.lastLapMs == null -> s.gap
                        else -> "Last ${Format.lap(s.lastLapMs)} · ${s.gap}"
                    },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("L${s.laps}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text(
                    Format.lap(s.bestLapMs),
                    color = if (s.hasFastestLap) RT.Purple else RT.TextDim,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Add lap now") }, onClick = { menu = false; onAddLap() })
            DropdownMenuItem(text = { Text("Remove last lap") }, onClick = { menu = false; onRemoveLap() })
        }
    }
}

private fun announcement(f: LapFlash): String {
    val t = f.lap.timeMs / 1000.0
    val time = "${(t).fixed(1)}"
    val tag = when {
        f.fastest -> ", fastest lap"
        f.personalBest -> ", personal best"
        else -> ""
    }
    return "${f.racer.name}, $time$tag"
}

/** Lap announcements that can be muted. */
private class TtsHolder {
    private val speaker = Speaker()
    var enabled = true

    fun say(text: String) {
        if (enabled) speaker.say(text)
    }

    fun shutdown() = speaker.shutdown()
}

private fun kotlinx.coroutines.CoroutineScope.launchMain(block: () -> Unit) {
    launch(kotlinx.coroutines.Dispatchers.Main) { block() }
}
