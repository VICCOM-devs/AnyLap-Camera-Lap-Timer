package app.anylap.ui.racers

import app.anylap.i18n.tr
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
import app.anylap.data.matchViews
import app.anylap.platform.Platform
import app.anylap.platform.PlatformBackHandler
import app.anylap.platform.Speaker
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

/**
 * Where the assisted calibration is. Since 0.5.0 the required part is [Laps] alone (drive three laps past
 * the camera, one person); [Photo]s are an optional extra reachable from the rail and the review.
 */
sealed interface CalStep {
    data object Laps : CalStep
    data class Photo(val index: Int) : CalStep
    data object Review : CalStep
}

/** First thing still missing; the review when everything is there. */
fun firstOpenStep(racer: Racer): CalStep = if (racer.hasEnoughPasses) CalStep.Review else CalStep.Laps

fun nextStep(sport: Sport, s: CalStep): CalStep = when (s) {
    CalStep.Laps -> CalStep.Review
    is CalStep.Photo -> if (s.index + 1 < CalibrationPlan.photoViews(sport).size) CalStep.Photo(s.index + 1) else CalStep.Review
    CalStep.Review -> CalStep.Review
}

/** What the phone says out loud, so one person can calibrate from the transmitter. */
internal object CalVoice {
    fun start(name: String, sport: Sport, laps: Int): String =
        if (sport == Sport.RC) tr("{0}: drive {1} laps past the line. The first one slowly.", name, laps)
        else tr("{0}: pass the line {1} times. The first time slowly.", name, laps)
    fun lap(n: Int, of: Int): String = if (n < of) tr("Lap {0} of {1} saved. Keep going.", n, of) else tr("Lap {0} saved.", n)
    fun done(name: String): String = tr("{0} is done.", name)
    fun next(name: String): String = tr("Next car: {0}. Drive {1} laps, the first one slowly.", name, CalibrationPlan.PASSES_TOTAL)
    fun allDone(): String = tr("All cars are calibrated. You can start the race.")
    fun ignored(): String = tr("That didn't look like the same car – ignored.")
}

@Composable
fun CalibrateScreen(nav: Navigator, racerId: String, queue: List<String> = emptyList()) {
    CameraScreenMode()
    androidx.compose.runtime.DisposableEffect(Unit) { app.anylap.update.Updates.hold(true); onDispose { app.anylap.update.Updates.hold(false) } }
    CameraPermissionGate(onBack = { nav.pop() }) {
        CalibrateContent(nav, racerId, queue)
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun CalibrateContent(nav: Navigator, firstRacerId: String, initialQueue: List<String>) {
    val app = LocalApp.current
    val racers by app.repo.racers.collectAsState()
    // One camera session for a whole grid: when this racer's laps are in, the next car in the queue takes over.
    var racerId by remember { mutableStateOf(firstRacerId) }
    var queue by remember { mutableStateOf(initialQueue.filter { it != firstRacerId }) }
    val racer = racers.firstOrNull { it.id == racerId } ?: return
    val settings by app.repo.settings.collectAsState()
    val session = rememberVisionSession()
    val scope = rememberCoroutineScope()
    val speaker = remember { Speaker() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    fun say(text: String) { if (settings.announceLaps) speaker.say(text) }

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
            Text(tr("Camera unavailable on this device."), color = RT.TextDim)
        }
        return
    }
    val stats by session.camera.stats.collectAsState()
    // Exposure time for motion-blur-aware colour sampling (rounded so small AE wobble doesn't churn).
    val exposureMs = (stats.exposureNs / 100_000L) / 10f
    LaunchedEffect(exposureMs) {
        if (exposureMs > 0f) session.analyzer.config = session.analyzer.config.copy(exposureS = exposureMs / 1000f)
    }
    val boxes by session.overlay.collectAsState()

    // Only this racer is "in the race", so every crossing is theirs – but it has to look like
    // the photos (and the passes so far): a pallet, a shoe or a fire extinguisher is not a pass.
    val views = racer.matchViews()
    LaunchedEffect(line, direction, racer.id, views.size) {
        val mpp = session.camera.setup.hfovRad?.let { Optics.metersPerPixel(settings.lastConfig.cameraDistanceM, it, FrameAnalyzer.PROC_W) }
        session.analyzer.config = session.analyzer.config.copy(
            line = line,
            direction = direction,
            sport = racer.sport,
            racers = listOf(RacerRef(racer.id, racer.fingerprint.takeIf { it.isNotEmpty() }?.toFloatArray(), racer.markerId, views)),
            useMarkers = racer.markerId != null,
            metersPerProcPx = mpp,
            captureSnapshots = true,
            captureTemplates = true,
            minMatch = if (views.isEmpty()) 0f else FrameAnalyzer.MIN_MATCH,
        )
    }
    // Same as in a race: fixed exposure while recording passes, so brightness drift isn't "motion".
    LaunchedEffect(step, racerId) {
        session.camera.setExposureLock(step is CalStep.Laps)
        if (step is CalStep.Laps) {
            session.analyzer.resetBackground()
            val r = app.repo.racer(racerId)
            if (r != null && !r.hasEnoughPasses) say(CalVoice.start(r.name, r.sport, CalibrationPlan.PASSES_TOTAL))
        }
    }
    var rejectedInARow by remember { mutableStateOf(0) }
    LaunchedEffect(session) {
        session.rejected.collect { e ->
            if (currentStep !is CalStep.Laps || currentEditing) return@collect
            rejectedInARow++
            if (rejectedInARow == 1) say(CalVoice.ignored())
            val pct = tr("{0}% match", (e.match * 100).fixed(0))
            notice = if (rejectedInARow < 3) tr("Ignored: something crossed that didn't look like {0} ({1}).", racer.name, pct)
            else tr("Still not recognising {0} ({1}). If that was {0}, delete the saved laps in Review and drive them again in this light.", racer.name, pct)
            val shown = notice
            scope.launch {
                delay(5_000)
                if (notice == shown) notice = null
            }
        }
    }
    LaunchedEffect(session) {
        session.crossings.collect { e ->
            val st = currentStep
            if (st !is CalStep.Laps || currentEditing) return@collect
            val fp = e.fingerprint ?: return@collect
            val id = racerId
            val current = app.repo.racer(id) ?: return@collect
            val before = current.passes.size
            if (before >= CalibrationPlan.MAX_PASSES) {
                notice = tr("That's plenty – delete one in Review to record more.")
                return@collect
            }
            val now = Platform.nowMillis()
            val img = e.snapshot?.let { snap ->
                val path = joinPath(Platform.dataDir, "cal_${id}_$now.bmp")
                Platform.writeBytes(path, Bmp.encode(snap.width, snap.height, snap.argb))
                path
            }
            val sample = CalibrationSample(
                id = Uuid.random().toString(),
                kind = CalibrationPlan.passKind(current.sport, e.speedKmh, current.calibration),
                fingerprint = fp.toList(),
                imagePath = img,
                createdAt = now,
                speedKmh = e.speedKmh,
                direction = e.direction,
                match = e.match.takeIf { it > 0f },
                area = e.area.takeIf { it > 0 && !e.split },
                along = e.along.takeIf { e.area > 0 && !e.split },
                topPicture = e.template?.takeIf { !e.split }?.encode()?.toList(),
            )
            app.repo.addCalibrationSample(id, sample)
            lastPass = sample
            rejectedInARow = 0
            notice = null
            if (e.markerSeen != null) markerSeen = e.markerSeen
            val count = before + 1
            if (count < CalibrationPlan.PASSES_TOTAL) {
                say(CalVoice.lap(count, CalibrationPlan.PASSES_TOTAL))
            } else if (count == CalibrationPlan.PASSES_TOTAL) {
                say(CalVoice.done(current.name))
                scope.launch {
                    delay(1_400)
                    if (currentStep != st || racerId != id) return@launch
                    val nextId = queue.firstOrNull { qid -> app.repo.racer(qid)?.let { !it.hasEnoughPasses } == true }
                    if (nextId != null) {
                        // next car: same camera, same line, same light – only the racer changes
                        queue = queue.filter { it != nextId }
                        lastPass = null
                        markerSeen = null
                        rejectedInARow = 0
                        racerId = nextId
                        step = CalStep.Laps
                        app.repo.racer(nextId)?.let { say(CalVoice.next(it.name)) }
                    } else {
                        if (initialQueue.isNotEmpty()) say(CalVoice.allDone())
                        step = CalStep.Review
                    }
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
                    notice = tr("Couldn't take the photo – try again.")
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
        upNext = queue.mapNotNull { qid -> racers.firstOrNull { it.id == qid }?.takeIf { !it.hasEnoughPasses }?.name },
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
            onNextCar = {
                val nextId = queue.firstOrNull()
                if (nextId != null) {
                    queue = queue.drop(1)
                    lastPass = null; markerSeen = null; rejectedInARow = 0; notice = null; editingLine = false
                    racerId = nextId
                    step = firstOpenStep(app.repo.racer(nextId) ?: racer)
                }
            },
        ),
    ) {
        CameraPreview(session.camera, Modifier.fillMaxSize())
        if (step is CalStep.Laps) TrackBoxesOverlay(boxes)
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
    /** Skip to the next car in the queue (null = no queue). */
    val onNextCar: (() -> Unit)? = null,
)

/**
 * Full-screen camera with a slim rail on the right (back, racer, laps, line, photos, review)
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
    upNext: List<String> = emptyList(),
    camera: @Composable BoxScope.() -> Unit,
) {
    val views = CalibrationPlan.photoViews(racer.sport)
    val photos = racer.samplesOf(SampleKind.PHOTO)
    val laps = racer.passes.size
    val target = CalibrationPlan.PASSES_TOTAL

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(previewAspect)) {
                camera()
                when (step) {
                    is CalStep.Photo -> PhotoGuide(photoBox(racer.sport))
                    CalStep.Laps -> FinishLineOverlay(line, direction, editable = editingLine, onChange = actions.onLineChange)
                    CalStep.Review -> {}
                }

                CameraHint(tr("{0} fps", fps.fixed(0)), Modifier.align(Alignment.TopEnd).padding(10.dp))

                if (step is CalStep.Laps && editingLine) {
                    LineTools(
                        direction = direction,
                        onDirection = actions.onDirection,
                        onResetBackground = actions.onResetBackground,
                        onCenter = { actions.onLineChange(LineSpec()) },
                        onDone = { actions.onEditLine(false) },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    )
                    CameraHint(tr("Drag the ends onto the tape on the ground"), Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                } else if (step != CalStep.Review) {
                    StepCard(racer, step, views, laps, markerSeen, notice, upNext, actions, Modifier.align(Alignment.BottomStart).padding(10.dp))
                }

                if (step is CalStep.Photo) {
                    Shutter(busy, actions.onCapture, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
                }

                if (step is CalStep.Laps && !editingLine) {
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
                                CameraHint(tr("Saved – tap × if it was a bad pass"))
                            }
                        }
                    }
                }

                if (step == CalStep.Review) {
                    ReviewPanel(racer, kmh, actions, racer.hasEnoughPasses, upNext)
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
            RailButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), actions.onBack)
            Box(Modifier.padding(vertical = 2.dp)) { RacerAvatar(racer, 40.dp) }
            RailButton(
                Icons.Rounded.Speed, tr("Laps"), { actions.onStep(CalStep.Laps) },
                selected = step == CalStep.Laps && !editingLine,
                badge = "$laps/$target", done = laps >= target,
            )
            RailButton(
                Icons.Rounded.Flag, tr("Line"),
                {
                    if (step !is CalStep.Laps) actions.onStep(CalStep.Laps)
                    actions.onEditLine(!(editingLine && step is CalStep.Laps))
                },
                selected = editingLine && step is CalStep.Laps,
            )
            RailButton(
                Icons.Rounded.CameraAlt, tr("Photos"),
                {
                    val missing = views.indexOfFirst { v -> photos.none { it.view == v.key } }
                    actions.onStep(CalStep.Photo(if (missing >= 0) missing else 0))
                },
                selected = step is CalStep.Photo,
                badge = "${photos.size}/${views.size}",
                done = photos.isNotEmpty(),
            )
            RailButton(
                Icons.Rounded.GridView, tr("Review"), { actions.onStep(CalStep.Review) },
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
    laps: Int,
    markerSeen: Int?,
    notice: String?,
    upNext: List<String>,
    actions: CalibrateActions,
    modifier: Modifier,
) {
    val rc = racer.sport == Sport.RC
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
                    tr("PHOTO {0}/{1}", step.index + 1, views.size) + tr(" · OPTIONAL"),
                    style = MaterialTheme.typography.labelSmall, color = RT.Accent,
                )
                Text(v.title, style = MaterialTheme.typography.titleSmall.copy(color = Color.White))
                Text(
                    if (rc) tr("{0} Fill the white box with the car.", v.hint) else tr("{0} Fill the white box.", v.hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (step.index + 1 < views.size) tr("Skip this side") else tr("Done with photos"),
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
            CalStep.Laps -> {
                val count = laps
                val total = CalibrationPlan.PASSES_TOTAL
                Text(tr("DRIVE {0} LAPS", total), style = MaterialTheme.typography.labelSmall, color = RT.Accent)
                Text(
                    when {
                        count == 0 && rc -> tr("Lap 1 slowly, then at race pace")
                        count == 0 -> tr("First time slowly, then at race pace")
                        count < total -> tr("{0} more – keep going", total - count)
                        else -> tr("Done – {0} is ready", racer.name)
                    },
                    style = MaterialTheme.typography.titleSmall.copy(color = Color.White),
                )
                Text(
                    if (rc) tr("Only this car on the track, nothing else moving. The phone tells you when each lap is saved.")
                    else tr("Only this runner near the line. The phone says when each pass is saved."),
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
                                null -> tr("Bib not seen yet")
                                racer.markerId -> tr("Bib read ✓")
                                else -> tr("Saw bib {0} – wrong bib?", markerSeen)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (markerSeen == racer.markerId) RT.Green else RT.TextDim,
                        )
                    }
                }
                if (upNext.isNotEmpty()) {
                    Text(
                        tr("Then: {0}", upNext.joinToString(", ")),
                        style = MaterialTheme.typography.labelSmall, color = RT.TextDim,
                    )
                }
            }
            CalStep.Review -> {}
        }
        notice?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = RT.Amber) }
        if (step is CalStep.Laps) {
            Text(tr("Each lap is saved with a picture you can delete. Line not on your tape? Tap Line."), style = MaterialTheme.typography.labelSmall, color = RT.TextFaint)
        }
    }
}

@Composable
private fun PassDots(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(CalibrationPlan.PASSES_TOTAL) { i ->
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
        if (count > CalibrationPlan.PASSES_TOTAL) {
            Text("+${count - CalibrationPlan.PASSES_TOTAL}", style = MaterialTheme.typography.labelSmall, color = RT.Green)
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
            .semantics { role = Role.Button; contentDescription = tr("Take photo") },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(if (busy) RT.Surface3 else RT.Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CameraAlt, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** Everything captured, with delete buttons; covers the camera while open. */
@Composable
private fun BoxScope.ReviewPanel(racer: Racer, kmh: Boolean, actions: CalibrateActions, ready: Boolean, upNext: List<String>) {
    val views = CalibrationPlan.photoViews(racer.sport)
    Column(
        Modifier
            .matchParentSize()
            .background(RT.Bg.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ready) tr("{0} is ready", racer.name) else tr("Review {0}", racer.name), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("Tap a picture to look closer. Delete anything that isn't the {0} on its own.", racer.sport.unitName) +
                        if (ready) " " + tr("Side photos are optional – they help when two cars look alike.") else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            val next = actions.onNextCar
            if (next != null && upNext.isNotEmpty()) {
                PrimaryButton(tr("Next: {0}", upNext.first()), next, icon = Icons.Rounded.Speed)
                Spacer(Modifier.width(8.dp))
            }
            PrimaryButton(
                if (ready) tr("Done") else tr("Finish later"),
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
                onAddPass = { _ -> actions.onStep(CalStep.Laps) },
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
