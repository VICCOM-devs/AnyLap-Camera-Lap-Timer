package app.anylap.ui.racers

import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.anylap.data.Direction
import app.anylap.data.Sport
import app.anylap.ui.components.CameraScreenMode
import app.anylap.ui.components.CircleIconButton
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.SecondaryButton
import app.anylap.vision.CameraPermissionGate
import app.anylap.vision.CameraPreview
import app.anylap.ui.session.FinishLineOverlay
import app.anylap.ui.session.TrackBoxesOverlay
import app.anylap.ui.session.rememberVisionSession
import app.anylap.platform.Platform
import app.anylap.platform.joinPath
import app.anylap.util.fixed
import app.anylap.ui.theme.RT
import app.anylap.vision.Fingerprint
import app.anylap.vision.RacerRef
import kotlinx.coroutines.launch

private val PHOTO_BOX = floatArrayOf(0.32f, 0.22f, 0.68f, 0.78f)

@Composable
fun CalibrateScreen(nav: Navigator, racerId: String) {
    CameraScreenMode()
    CameraPermissionGate(onBack = { nav.pop() }) {
        CalibrateContent(nav, racerId)
    }
}

@Composable
private fun CalibrateContent(nav: Navigator, racerId: String) {
    val app = LocalApp.current
    val racers by app.repo.racers.collectAsState()
    val racer = racers.firstOrNull { it.id == racerId } ?: return
    val settings by app.repo.settings.collectAsState()
    val session = rememberVisionSession()
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) } // 0 = photo, 1 = passes
    var line by remember { mutableStateOf(settings.lastConfig.line) }
    var passes by remember { mutableIntStateOf(0) }
    var markerSeen by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    if (session == null) {
        Box(Modifier.fillMaxSize().background(RT.Bg), contentAlignment = Alignment.Center) {
            Text("Camera unavailable on this device.", color = RT.TextDim)
        }
        return
    }
    val stats by session.camera.stats.collectAsState()
    val boxes by session.overlay.collectAsState()

    // Only this racer is "in the race", so every crossing is theirs.
    LaunchedEffect(line, racer.id) {
        session.analyzer.config = session.analyzer.config.copy(
            line = line,
            sport = racer.sport,
            racers = listOf(RacerRef(racer.id, null, racer.markerId)),
            useMarkers = racer.markerId != null,
        )
    }
    LaunchedEffect(session) {
        session.crossings.collect { e ->
            if (step != 1) return@collect
            val fp = e.fingerprint ?: return@collect
            val current = app.repo.racer(racerId) ?: return@collect
            app.repo.upsertRacer(
                current.copy(
                    fingerprint = Fingerprint.blend(current.fingerprint, current.fingerprintSamples, fp),
                    fingerprintSamples = current.fingerprintSamples + 1,
                ),
            )
            passes++
            if (e.markerSeen != null) markerSeen = e.markerSeen
        }
    }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(session.camera.setup.previewWidth.toFloat() / session.camera.setup.previewHeight)) {
                CameraPreview(session.camera, Modifier.fillMaxSize())
                if (step == 0) {
                    PhotoGuide()
                } else {
                    TrackBoxesOverlay(boxes)
                    FinishLineOverlay(line, Direction.BOTH, editable = true, onChange = { line = it })
                }
                Text(
                    "${stats.fps.fixed(0)} fps",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Column(
            Modifier.width(300.dp).fillMaxHeight().background(RT.Bg).padding(18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { nav.pop() })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Calibrate", style = MaterialTheme.typography.titleLarge)
                    Text(racer.name, style = MaterialTheme.typography.bodySmall)
                }
            }
            StepHeader(1, "Photo", done = racer.photoPath != null, active = step == 0)
            StepHeader(2, if (racer.sport == Sport.RC) "Drive past the line" else "Run past the line", done = passes >= 3, active = step == 1)

            if (step == 0) {
                Text(
                    if (racer.sport == Sport.RC) "Place the car so it fills the white box, then capture."
                    else "Have the runner stand inside the box, facing the camera.",
                    style = MaterialTheme.typography.bodyMedium, color = RT.TextDim,
                )
                PrimaryButton(if (busy) "Capturing…" else "Capture", {
                    if (busy) return@PrimaryButton
                    busy = true
                    session.analyzer.requestRegionFingerprint(PHOTO_BOX) { fp ->
                        val current = app.repo.racer(racerId)
                        if (current != null) {
                            app.repo.upsertRacer(
                                current.copy(
                                    fingerprint = Fingerprint.blend(current.fingerprint, current.fingerprintSamples, fp),
                                    fingerprintSamples = current.fingerprintSamples + 1,
                                ),
                            )
                        }
                    }
                    session.camera.capturePhoto(PHOTO_BOX, 360) { jpeg ->
                        scope.launch {
                            if (jpeg != null) {
                                val path = joinPath(Platform.dataDir, "photo_${racerId}_${Platform.nowMillis()}.jpg")
                                Platform.writeBytes(path, jpeg)
                                app.repo.racer(racerId)?.let { r ->
                                    r.photoPath?.let { old -> Platform.delete(old) }
                                    app.repo.upsertRacer(r.copy(photoPath = path))
                                }
                                message = "Photo saved"
                            }
                            busy = false
                            step = 1
                        }
                    }
                }, Modifier.fillMaxWidth(), icon = Icons.Rounded.CameraAlt)
                SecondaryButton("Skip photo", { step = 1 }, Modifier.fillMaxWidth())
            } else {
                Text(
                    "Line the red line up with the tape or crack on the ground. Then ${if (racer.sport == Sport.RC) "drive" else "run"} past it 3–5 times, alone, at normal speed.",
                    style = MaterialTheme.typography.bodyMedium, color = RT.TextDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(5) { i ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(if (i < passes) RT.Green else RT.Surface2)
                                .border(1.dp, if (i < passes) RT.Green else RT.Stroke, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i < passes) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            else Text("${i + 1}", color = RT.TextDim, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (racer.markerId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.QrCode2, null, tint = if (markerSeen == racer.markerId) RT.Green else RT.TextDim, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (markerSeen) {
                                null -> "Bib marker not seen yet"
                                racer.markerId -> "Bib marker read ✓"
                                else -> "Saw marker $markerSeen – wrong bib?"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (markerSeen == racer.markerId) RT.Green else RT.TextDim,
                        )
                    }
                }
                SecondaryButton("Reset background", { session.analyzer.resetBackground() }, Modifier.fillMaxWidth())
                PrimaryButton(if (passes >= 3) "Done" else "Finish", {
                    app.repo.updateSettings { s -> s.copy(lastConfig = s.lastConfig.copy(line = line)) }
                    nav.pop()
                }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Check, enabled = true)
            }
            message?.let { Text(it, color = RT.Green, style = MaterialTheme.typography.bodySmall) }
            stats.error?.let { Text(it, color = RT.Accent, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PhotoGuide() {
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val l = PHOTO_BOX[0] * size.width
            val t = PHOTO_BOX[1] * size.height
            val r = PHOTO_BOX[2] * size.width
            val b = PHOTO_BOX[3] * size.height
            val dim = Color.Black.copy(alpha = 0.45f)
            drawRect(dim, size = androidx.compose.ui.geometry.Size(size.width, t))
            drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(0f, b), size = androidx.compose.ui.geometry.Size(size.width, size.height - b))
            drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(0f, t), size = androidx.compose.ui.geometry.Size(l, b - t))
            drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(r, t), size = androidx.compose.ui.geometry.Size(size.width - r, b - t))
            drawRoundRect(
                Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(l, t),
                size = androidx.compose.ui.geometry.Size(r - l, b - t),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
            )
        }
    }
}

@Composable
private fun StepHeader(n: Int, title: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(
                when {
                    done -> RT.Green
                    active -> RT.Accent
                    else -> RT.Surface3
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
            else Text("$n", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (active) RT.Text else RT.TextDim)
    }
}
