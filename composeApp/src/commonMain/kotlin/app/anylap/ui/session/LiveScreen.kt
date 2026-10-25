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

    LiveLayout(
        config = config,
        race = race,
        speedKmh = settings.speedUnitKmh,
        fps = camStats.fps,
        recording = recordingStartNs != null,
        error = camStats.error,
        line = line,
        direction = direction,
        previewAspect = session.camera.setup.previewWidth.toFloat() / session.camera.setup.previewHeight,
        voice = voice,
        onLineChange = { line = it },
        onDirection = {
            direction = when (direction) {
                Direction.BOTH -> Direction.FORWARD
                Direction.FORWARD -> Direction.REVERSE
                Direction.REVERSE -> Direction.BOTH
            }
        },
        onResetBackground = { session.analyzer.resetBackground() },
        onBack = { nav.pop() },
        onStart = { begin() },
        onEnd = { confirmEnd = true },
        onToggleVoice = {
            voice = !voice
            app.repo.updateSettings { it.copy(announceLaps = voice) }
        },
        onAssign = { assigning = it },
        onAddLap = { engine.addManualLap(it) },
        onRemoveLap = { engine.removeLastLap(it) },
    ) {
        CameraPreview(session.camera, Modifier.fillMaxSize())
        TrackBoxesOverlay(boxes)
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
