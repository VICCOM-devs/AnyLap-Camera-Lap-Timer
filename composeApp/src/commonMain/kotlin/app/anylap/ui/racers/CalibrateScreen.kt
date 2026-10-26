package app.anylap.ui.racers

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.anylap.data.CalibrationPlan
import app.anylap.data.CalibrationSample
import app.anylap.data.Direction
import app.anylap.data.LineSpec
import app.anylap.data.Racer
import app.anylap.data.SampleKind
import app.anylap.data.Sport
import app.anylap.platform.Platform
import app.anylap.platform.PlatformBackHandler
import app.anylap.platform.joinPath
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.components.CameraScreenMode
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.session.CameraHint
import app.anylap.ui.session.CameraRail
import app.anylap.ui.session.FinishLineOverlay
import app.anylap.ui.session.LineTools
import app.anylap.ui.session.RailButton
import app.anylap.ui.session.TrackBoxesOverlay
import app.anylap.ui.session.rememberVisionSession
import app.anylap.ui.theme.RT
import app.anylap.util.Bmp
import app.anylap.util.fixed
import app.anylap.vision.CameraPermissionGate
import app.anylap.vision.CameraPreview
import app.anylap.vision.FrameAnalyzer
import app.anylap.vision.Optics
import app.anylap.vision.RacerRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Normalised (l, t, r, b) box the racer is placed in for photos. */
internal fun photoBox(sport: Sport): FloatArray = when (sport) {
    Sport.RC -> floatArrayOf(0.30f, 0.24f, 0.70f, 0.80f)
    Sport.RUNNING -> floatArrayOf(0.36f, 0.06f, 0.64f, 0.96f)
}

/** Where the assisted calibration is. */
sealed interface CalStep {
    data class Photo(val index: Int) : CalStep
    data class Pass(val kind: SampleKind) : CalStep
    data object Review : CalStep
}

/** The order of the wizard for this racer. */
fun calSteps(sport: Sport): List<CalStep> =
    CalibrationPlan.photoViews(sport).indices.map { CalStep.Photo(it) } +
        CalStep.Pass(SampleKind.PASS_SLOW) + CalStep.Pass(SampleKind.PASS_FAST) + CalStep.Review

/** First thing still missing; the review when everything is there. */
fun firstOpenStep(racer: Racer): CalStep {
    val views = CalibrationPlan.photoViews(racer.sport)
    views.forEachIndexed { i, v ->
        if (v.required && racer.calibration.none { it.kind == SampleKind.PHOTO && it.view == v.key }) return CalStep.Photo(i)
    }
    for (k in listOf(SampleKind.PASS_SLOW, SampleKind.PASS_FAST)) {
        if (racer.samplesOf(k).size < CalibrationPlan.PASSES_PER_SPEED) return CalStep.Pass(k)
    }
    return CalStep.Review
}

fun nextStep(sport: Sport, s: CalStep): CalStep {
    val all = calSteps(sport)
    return all.getOrElse(all.indexOf(s) + 1) { CalStep.Review }
}

@Composable
fun CalibrateScreen(nav: Navigator, racerId: String) {
    CameraScreenMode()
    CameraPermissionGate(onBack = { nav.pop() }) {
        CalibrateContent(nav, racerId)
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun CalibrateContent(nav: Navigator, racerId: String) {
    val app = LocalApp.current
    val racers by app.repo.racers.collectAsState()
    val racer = racers.firstOrNull { it.id == racerId } ?: return
    val settings by app.repo.settings.collectAsState()
    val session = rememberVisionSession()
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(firstOpenStep(racer)) }
    var line by remember { mutableStateOf(settings.lastConfig.line) }
    var direction by remember { mutableStateOf(settings.lastConfig.direction) }
    var editingLine by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lastPass by remember { mutableStateOf<CalibrationSample?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var markerSeen by remember { mutableStateOf<Int?>(null) }
    val currentStep by rememberUpdatedState(step)
    val currentEditing by rememberUpdatedState(editingLine)

    fun exit() {
        app.repo.updateSettings { s -> s.copy(lastConfig = s.lastConfig.copy(line = line, direction = direction)) }
        nav.pop()
    }
    PlatformBackHandler(true) { exit() }

    if (session == null) {
        Box(Modifier.fillMaxSize().background(RT.Bg), contentAlignment = Alignment.Center) {
            Text("Camera unavailable on this device.", color = RT.TextDim)
        }
        return
    }
    val stats by session.camera.stats.collectAsState()
    val boxes by session.overlay.collectAsState()

    // Only this racer is "in the race", so every crossing is theirs.
    LaunchedEffect(line, direction, racer.id) {
        val mpp = session.camera.setup.hfovRad?.let { Optics.metersPerPixel(settings.lastConfig.cameraDistanceM, it, FrameAnalyzer.PROC_W) }
        session.analyzer.config = session.analyzer.config.copy(
            line = line,
            direction = direction,
            sport = racer.sport,
            racers = listOf(RacerRef(racer.id, null, racer.markerId)),
            useMarkers = racer.markerId != null,
            metersPerProcPx = mpp,
            captureSnapshots = true,
        )
    }
    LaunchedEffect(session) {
        session.crossings.collect { e ->
            val st = currentStep
            if (st !is CalStep.Pass || currentEditing) return@collect
            val fp = e.fingerprint ?: return@collect
            val current = app.repo.racer(racerId) ?: return@collect
            val before = current.samplesOf(st.kind).size
            if (before >= CalibrationPlan.MAX_PASSES_PER_SPEED) {
                notice = "That's plenty – delete one in Review to record more."
                return@collect
            }
            val now = Platform.nowMillis()
            val img = e.snapshot?.let { snap ->
                val path = joinPath(Platform.dataDir, "cal_${racerId}_$now.bmp")
                Platform.writeBytes(path, Bmp.encode(snap.width, snap.height, snap.argb))
                path
            }
            val sample = CalibrationSample(
                id = Uuid.random().toString(),
                kind = st.kind,
                fingerprint = fp.toList(),
                imagePath = img,
                createdAt = now,
                speedKmh = e.speedKmh,
                direction = e.direction,
            )
            app.repo.addCalibrationSample(racerId, sample)
            lastPass = sample
            notice = null
            if (e.markerSeen != null) markerSeen = e.markerSeen
            if (before + 1 == CalibrationPlan.PASSES_PER_SPEED) {
                scope.launch {
                    delay(1_400)
                    if (currentStep == st) step = nextStep(racer.sport, st)
                }
            }
        }
    }
    // The "pass saved" toast goes away by itself.
    LaunchedEffect(lastPass) {
        if (lastPass != null) {
            delay(5_000)
            lastPass = null
        }
    }

    fun capture() {
        val st = step as? CalStep.Photo ?: return
        if (busy) return
        val view = CalibrationPlan.photoViews(racer.sport)[st.index]
        val box = photoBox(racer.sport)
        busy = true
        val fpReady = CompletableDeferred<FloatArray>()
        session.analyzer.requestRegionFingerprint(box) { fpReady.complete(it) }
        session.camera.capturePhoto(box, 360) { jpeg ->
            scope.launch {
                val fp = withTimeoutOrNull(2_000) { fpReady.await() }
                if (fp == null) {
                    notice = "Couldn't take the photo – try again."
                } else {
                    val now = Platform.nowMillis()
                    val path = jpeg?.let {
                        val p = joinPath(Platform.dataDir, "cal_${racerId}_$now.jpg")
                        Platform.writeBytes(p, it)
                        p
                    }
                    app.repo.addCalibrationSample(
                        racerId,
                        CalibrationSample(Uuid.random().toString(), SampleKind.PHOTO, view.key, fp.toList(), path, now),
                    )
                    // The first side doubles as the racer's avatar.
                    if (jpeg != null && st.index == 0) {
                        val avatar = joinPath(Platform.dataDir, "photo_${racerId}_$now.jpg")
                        Platform.writeBytes(avatar, jpeg)
                        app.repo.racer(racerId)?.let { r ->
                            r.photoPath?.let { old -> Platform.delete(old) }
                            app.repo.upsertRacer(r.copy(photoPath = avatar))
                        }
                    }
                    notice = null
                    step = nextStep(racer.sport, st)
                }
                busy = false
            }
        }
    }

    var open by remember { mutableStateOf<CalibrationSample?>(null) }
    CalibrateLayout(
        racer = racer,
        step = step,
        line = line,
        direction = direction,
        editingLine = editingLine,
        previewAspect = session.camera.setup.previewWidth.toFloat() / session.camera.setup.previewHeight,
        fps = stats.fps,
        error = stats.error,
        busy = busy,
        lastPass = lastPass,
        markerSeen = markerSeen,
        notice = notice,
        kmh = settings.speedUnitKmh,
        actions = CalibrateActions(
            onBack = { exit() },
            onStep = { editingLine = false; notice = null; step = it },
            onCapture = { capture() },
            onEditLine = { editingLine = it },
            onLineChange = { line = it },
            onDirection = {
                direction = when (direction) {
                    Direction.BOTH -> Direction.FORWARD
                    Direction.FORWARD -> Direction.REVERSE
                    Direction.REVERSE -> Direction.BOTH
                }
            },
            onResetBackground = { session.analyzer.resetBackground() },
            onOpenSample = { open = it },
            onDeleteSample = { s ->
                app.repo.removeCalibrationSample(racerId, s.id)
                if (lastPass?.id == s.id) lastPass = null
            },
            onDone = { exit() },
        ),
    ) {
        CameraPreview(session.camera, Modifier.fillMaxSize())
        if (step is CalStep.Pass) TrackBoxesOverlay(boxes)
    }
    open?.let { s ->
        SampleDialog(
            racer, s, settings.speedUnitKmh,
            onDelete = { app.repo.removeCalibrationSample(racerId, s.id); open = null },
            onDismiss = { open = null },
        )
    }
}

class CalibrateActions(
    val onBack: () -> Unit,
    val onStep: (CalStep) -> Unit,
    val onCapture: () -> Unit,
    val onEditLine: (Boolean) -> Unit,
    val onLineChange: (LineSpec) -> Unit,
    val onDirection: () -> Unit,
    val onResetBackground: () -> Unit,
    val onOpenSample: (CalibrationSample) -> Unit,
    val onDeleteSample: (CalibrationSample) -> Unit,
    val onDone: () -> Unit,
)

/**
 * Full-screen camera with a slim rail on the right (back, racer, photos, slow, fast, review, line)
 * and one small instruction card. Stateless so it can be screenshot-tested.
 */
@Composable
fun CalibrateLayout(
    racer: Racer,
    step: CalStep,
    line: LineSpec,
    direction: Direction,
    editingLine: Boolean,
    previewAspect: Float,
    fps: Double,
    error: String?,
    busy: Boolean,
    lastPass: CalibrationSample?,
    markerSeen: Int?,
    notice: String?,
    kmh: Boolean,
    actions: CalibrateActions,
    camera: @Composable BoxScope.() -> Unit,
) {
    val views = CalibrationPlan.photoViews(racer.sport)
    val photos = racer.samplesOf(SampleKind.PHOTO)
    val slow = racer.samplesOf(SampleKind.PASS_SLOW).size
    val fast = racer.samplesOf(SampleKind.PASS_FAST).size
    val target = CalibrationPlan.PASSES_PER_SPEED
    val requiredPhotosDone = views.filter { it.required }.all { v -> photos.any { it.view == v.key } }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(previewAspect)) {
                camera()
                when (step) {
                    is CalStep.Photo -> PhotoGuide(photoBox(racer.sport))
                    is CalStep.Pass -> FinishLineOverlay(line, direction, editable = editingLine, onChange = actions.onLineChange)
                    CalStep.Review -> {}
                }

                CameraHint("${fps.fixed(0)} fps", Modifier.align(Alignment.TopEnd).padding(10.dp))

                if (step is CalStep.Pass && editingLine) {
                    LineTools(
                        direction = direction,
                        onDirection = actions.onDirection,
                        onResetBackground = actions.onResetBackground,
                        onCenter = { actions.onLineChange(LineSpec()) },
                        onDone = { actions.onEditLine(false) },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    )
                    CameraHint("Drag the ends onto the tape on the ground", Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                } else if (step != CalStep.Review) {
                    StepCard(racer, step, views, slow, fast, markerSeen, notice, actions, Modifier.align(Alignment.BottomStart).padding(10.dp))
                }

                if (step is CalStep.Photo) {
                    Shutter(busy, actions.onCapture, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
                }

                if (step is CalStep.Pass && !editingLine) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = lastPass != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    ) {
                        lastPass?.let { s ->
                            Column(horizontalAlignment = Alignment.End) {
                                SampleTile(s, s.caption(racer, kmh), { actions.onOpenSample(s) }, { actions.onDeleteSample(s) }, size = 76.dp)
                                Spacer(Modifier.height(4.dp))
                                CameraHint("Saved – tap × if it was a bad pass")
                            }
                        }
                    }
                }

                if (step == CalStep.Review) {
                    ReviewPanel(racer, kmh, actions, requiredPhotosDone && slow + fast > 0)
                }

                if (error != null) {
                    Text(
                        error, style = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                            .clip(RoundedCornerShape(10.dp)).background(RT.Accent.copy(alpha = 0.9f))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        CameraRail {
            RailButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", actions.onBack)
            Box(Modifier.padding(vertical = 2.dp)) { RacerAvatar(racer, 40.dp) }
            RailButton(
                Icons.Rounded.CameraAlt, "Photos",
                {
                    val missing = views.indexOfFirst { v -> photos.none { it.view == v.key } }
                    actions.onStep(CalStep.Photo(if (missing >= 0) missing else 0))
                },
                selected = step is CalStep.Photo,
                badge = "${photos.size}/${views.size}",
                done = requiredPhotosDone,
            )
            RailButton(
                Icons.Rounded.SlowMotionVideo, "Slow", { actions.onStep(CalStep.Pass(SampleKind.PASS_SLOW)) },
                selected = step == CalStep.Pass(SampleKind.PASS_SLOW) && !editingLine,
                badge = "$slow/$target", done = slow >= target,
            )
            RailButton(
                Icons.Rounded.Speed, "Fast", { actions.onStep(CalStep.Pass(SampleKind.PASS_FAST)) },
                selected = step == CalStep.Pass(SampleKind.PASS_FAST) && !editingLine,
                badge = "$fast/$target", done = fast >= target,
            )
            RailButton(
                Icons.Rounded.Flag, "Line",
                {
                    if (step !is CalStep.Pass) actions.onStep(CalStep.Pass(if (slow < target) SampleKind.PASS_SLOW else SampleKind.PASS_FAST))
                    actions.onEditLine(!(editingLine && step is CalStep.Pass))
                },
                selected = editingLine && step is CalStep.Pass,
            )
            RailButton(
                Icons.Rounded.GridView, "Review", { actions.onStep(CalStep.Review) },
                selected = step == CalStep.Review,
                badge = "${racer.calibration.size}",
            )
        }
    }
}

/** Small instruction card in the bottom-left corner of the camera. */
@Composable
private fun StepCard(
    racer: Racer,
    step: CalStep,
    views: List<CalibrationPlan.PhotoView>,
    slow: Int,
    fast: Int,
    markerSeen: Int?,
    notice: String?,
    actions: CalibrateActions,
    modifier: Modifier,
) {
    val rc = racer.sport == Sport.RC
    val unit = racer.sport.unitName
    Column(
        modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.66f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (step) {
            is CalStep.Photo -> {
                val v = views[step.index]
                Text(
                    "PHOTO ${step.index + 1}/${views.size}${if (v.required) "" else " · OPTIONAL"}",
                    style = MaterialTheme.typography.labelSmall, color = RT.Accent,
                )
                Text(v.title, style = MaterialTheme.typography.titleSmall.copy(color = Color.White))
                Text(
                    "${v.hint} Fill the white box${if (rc) " with the car" else ""}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (step.index + 1 < views.size) "Skip this side" else "Skip to passes",
                        style = MaterialTheme.typography.labelMedium, color = RT.TextDim,
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { actions.onStep(nextStep(racer.sport, step)) }
                            .semantics { role = Role.Button }
                            .padding(horizontal = 4.dp, vertical = 9.dp),
                    )
                }
            }
            is CalStep.Pass -> {
                val isSlow = step.kind == SampleKind.PASS_SLOW
                val count = if (isSlow) slow else fast
                Text(
                    if (isSlow) "SLOW PASSES" else "FAST PASSES",
                    style = MaterialTheme.typography.labelSmall, color = RT.Accent,
                )
                Text(
                    when {
                        rc && isSlow -> "Drive past the line slowly"
                        rc -> "Now at race speed"
                        isSlow -> "Walk or jog past the line"
                        else -> "Now at race pace"
                    },
                    style = MaterialTheme.typography.titleSmall.copy(color = Color.White),
                )
                Text(
                    "${CalibrationPlan.PASSES_PER_SPEED} times, the way you'll race, with nothing else moving. " +
                        "Line not on your tape? Tap Line.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                PassDots(count)
                if (racer.markerId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.QrCode2, null, tint = if (markerSeen == racer.markerId) RT.Green else RT.TextDim, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when (markerSeen) {
                                null -> "Bib not seen yet"
                                racer.markerId -> "Bib read ✓"
                                else -> "Saw bib $markerSeen – wrong bib?"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (markerSeen == racer.markerId) RT.Green else RT.TextDim,
                        )
                    }
                }
                if (count >= CalibrationPlan.PASSES_PER_SPEED) {
                    Text(
                        if (isSlow) "Done – moving on to fast passes…" else "Done – check them in Review.",
                        style = MaterialTheme.typography.labelMedium, color = RT.Green,
                    )
                }
            }
            CalStep.Review -> {}
        }
        notice?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = RT.Amber) }
        if (step is CalStep.Pass) {
            Text("Each $unit pass is saved with a picture you can delete.", style = MaterialTheme.typography.labelSmall, color = RT.TextFaint)
        }
    }
}

@Composable
private fun PassDots(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(CalibrationPlan.PASSES_PER_SPEED) { i ->
            val done = i < count
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (done) RT.Green else RT.Surface2)
                    .border(1.dp, if (done) RT.Green else RT.Stroke, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                else Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = RT.TextDim, fontWeight = FontWeight.Bold)
            }
        }
        if (count > CalibrationPlan.PASSES_PER_SPEED) {
            Text("+${count - CalibrationPlan.PASSES_PER_SPEED}", style = MaterialTheme.typography.labelSmall, color = RT.Green)
        }
    }
}

/** Camera-style round shutter button. */
@Composable
private fun Shutter(busy: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "Take photo" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(if (busy) RT.Surface3 else RT.Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CameraAlt, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** Everything captured, with delete buttons; covers the camera while open. */
@Composable
private fun BoxScope.ReviewPanel(racer: Racer, kmh: Boolean, actions: CalibrateActions, ready: Boolean) {
    val views = CalibrationPlan.photoViews(racer.sport)
    Column(
        Modifier
            .matchParentSize()
            .background(RT.Bg.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Review ${racer.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap a picture to look closer. Delete anything that isn't the ${racer.sport.unitName} on its own.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            PrimaryButton(
                if (ready) "Done" else "Finish later",
                actions.onDone,
                icon = if (ready) Icons.Rounded.Check else Icons.Rounded.Close,
                color = if (ready) RT.Accent else RT.Surface3,
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            CalibrationGallery(
                racer = racer,
                onOpen = actions.onOpenSample,
                onDelete = actions.onDeleteSample,
                onAddPhoto = { key -> actions.onStep(CalStep.Photo(views.indexOfFirst { it.key == key }.coerceAtLeast(0))) },
                onAddPass = { kind -> actions.onStep(CalStep.Pass(kind)) },
                kmh = kmh,
                tile = 76.dp,
            )
        }
    }
}

@Composable
private fun PhotoGuide(box: FloatArray) {
    Canvas(Modifier.fillMaxSize()) {
        val l = box[0] * size.width
        val t = box[1] * size.height
        val r = box[2] * size.width
        val b = box[3] * size.height
        val dim = Color.Black.copy(alpha = 0.45f)
        drawRect(dim, size = Size(size.width, t))
        drawRect(dim, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
        drawRect(dim, topLeft = Offset(0f, t), size = Size(l, b - t))
        drawRect(dim, topLeft = Offset(r, t), size = Size(size.width - r, b - t))
        drawRoundRect(
            Color.White,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            cornerRadius = CornerRadius(18f, 18f),
            style = Stroke(width = 4f),
        )
    }
}
