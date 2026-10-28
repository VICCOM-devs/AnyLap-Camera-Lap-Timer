package app.anylap.ui.racers

import app.anylap.i18n.tr
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.anylap.bib.BibPdf
import app.anylap.vision.Markers
import app.anylap.data.RacerPalette
import app.anylap.data.Sport
import app.anylap.ui.components.KeyValue
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.Stepper
import app.anylap.ui.components.Tag
import app.anylap.ui.theme.RT
import app.anylap.pro.ProFeature
import app.anylap.ui.account.friendlyAuthError
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.Share
import kotlinx.coroutines.launch
import app.anylap.ui.pro.currentTier

@Composable
fun RacerEditScreen(nav: Navigator, racerId: String) {
    val app = LocalApp.current
    val tier = currentTier()
    val account by app.cloud.account.collectAsState()
    val scope = rememberCoroutineScope()
    var shareCode by remember { mutableStateOf<String?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val racers by app.repo.racers.collectAsState()
    val racer = racers.firstOrNull { it.id == racerId }
    if (racer == null) {
        ScreenScaffold(tr("Racer"), onBack = { nav.pop() }) {
            item { Text(tr("This racer was deleted."), style = MaterialTheme.typography.bodyMedium) }
        }
        return
    }
    var name by remember(racer.id) { mutableStateOf(racer.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var openSample by remember { mutableStateOf<app.anylap.data.CalibrationSample?>(null) }
    val settings by app.repo.settings.collectAsState()

    fun save(n: String = name) {
        val trimmed = n.trim()
        if (trimmed.isNotEmpty()) app.repo.upsertRacer(racer.copy(name = trimmed))
    }

    ScreenScaffold(
        title = racer.name,
        subtitle = if (racer.sport == Sport.RC) tr("RC car") else tr("Runner"),
        onBack = { save(); nav.pop() },
        bottomPadding = 40.dp,
    ) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RacerAvatar(racer, 104.dp)
            }
        }
        item { SectionLabel(tr("Details")) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; save(it) },
                    label = { Text(tr("Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RT.Accent,
                        unfocusedBorderColor = RT.Stroke,
                        focusedLabelColor = RT.Accent,
                        cursorColor = RT.Accent,
                    ),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Number"), style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.weight(1f))
                    Stepper(racer.number, { app.repo.upsertRacer(racer.copy(number = it)) }, 1..999, "")
                }
                Spacer(Modifier.height(16.dp))
                Text(tr("Colour"), style = MaterialTheme.typography.bodyMedium, color = RT.TextDim)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(RacerPalette) { c ->
                        val sel = c == racer.color
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .border(if (sel) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { app.repo.upsertRacer(racer.copy(color = c)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sel) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        item {
            SectionLabel(tr("Camera calibration"), Icons.Rounded.Tune, trailing = {
                if (racer.isCalibrated) Tag(tr("Ready"), RT.Green) else Tag(tr("Not calibrated"), RT.Amber)
            })
        }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text(
                    if (racer.sport == Sport.RC)
                        tr("Teach the camera what this car looks like: a photo of each side, then a few slow and a few fast passes over the finish line – ideally where you'll race, in the same light.")
                    else
                        tr("The bib marker identifies this runner. Calibrating their clothing as well helps when the bib is hidden (arms, other runners)."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RT.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                if (racer.hasLegacyCalibration) {
                    Text(
                        tr("Calibrated with an older version of the app ({0} samples). The camera reads colours differently now, so calibrate again.", racer.fingerprintSamples),
                        style = MaterialTheme.typography.bodySmall,
                        color = RT.Amber,
                    )
                    Spacer(Modifier.height(12.dp))
                } else if (racer.calibration.isNotEmpty()) {
                    CalibrationGallery(
                        racer = racer,
                        onOpen = { openSample = it },
                        onDelete = { app.repo.removeCalibrationSample(racer.id, it.id) },
                        onAddPhoto = null,
                        onAddPass = null,
                        kmh = settings.speedUnitKmh,
                        tile = 72.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        when {
                            !racer.isCalibrated -> tr("Calibrate")
                            racer.hasLegacyCalibration -> tr("Calibrate again")
                            else -> tr("Review & add")
                        },
                        {
                            save()
                            if (racer.hasLegacyCalibration) app.repo.resetCalibration(racer.id)
                            nav.push(Screen.Calibrate(racer.id))
                        },
                        Modifier.weight(1f),
                        icon = Icons.Rounded.CameraAlt,
                    )
                    if (racer.isCalibrated) {
                        SecondaryButton(tr("Reset"), { confirmReset = true }, icon = Icons.Rounded.RestartAlt)
                    }
                }
            }
        }

        val markerId = racer.markerId
        if (racer.sport == Sport.RUNNING && markerId != null) {
            item { SectionLabel(tr("Race bib")) }
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(92.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                        ) {
                            MarkerImage(markerId, Modifier.fillMaxWidth().aspectRatio(1f))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr("Marker {0}", markerId), style = MaterialTheme.typography.titleMedium)
                            Text(
                                tr("Print on A4 at 100 %, wear on the chest. Readable up to ~6 m from the camera."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(if (tier.isPro) tr("Print / share bib") else tr("Print bib · Pro"), {
                        save()
                        if (tier.isPro) BibPdf.share(listOf(racer.copy(name = name)))
                        else nav.push(Screen.Paywall(ProFeature.BIBS))
                    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Print)
                }
            }
        }

        item { SectionLabel(tr("Other phones")) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                if (racer.ownerUserId != null) {
                    Text(tr("Shared by {0}", racer.ownerName ?: tr("a friend")), style = MaterialTheme.typography.titleSmall)
                    Text(tr("Sessions you time with this racer also show up in their history."), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(tr("Race on a friend's phone"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("Get a code they enter under Racers → Add with code. Their phone learns this racer, and laps they time land in your history."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    shareCode?.let {
                        Text(it, style = MaterialTheme.typography.displaySmall, color = RT.Gold)
                        Text(tr("Valid for 24 hours."), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                    }
                    shareError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RT.Amber); Spacer(Modifier.height(8.dp)) }
                    SecondaryButton(if (tier.isPro) tr("Get share code") else tr("Get share code · Pro"), {
                        save()
                        when {
                            !tier.isPro -> nav.push(Screen.Paywall(ProFeature.SYNC))
                            account.userId == null -> nav.push(Screen.Account)
                            else -> scope.launch {
                                shareError = null
                                app.cloud.shareRacer(racer.copy(name = name))
                                    .onSuccess { shareCode = it }
                                    .onFailure { shareError = friendlyAuthError(it) }
                            }
                        }
                    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Share)
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = RT.Accent)
                Spacer(Modifier.width(6.dp))
                Text(if (racer.sport == Sport.RC) tr("Delete car") else tr("Delete runner"), color = RT.Accent)
            }
        }
    }

    openSample?.let { s ->
        SampleDialog(
            racer, s, settings.speedUnitKmh,
            onDelete = { app.repo.removeCalibrationSample(racer.id, s.id); openSample = null },
            onDismiss = { openSample = null },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = RT.Surface2,
            title = { Text(tr("Reset calibration?")) },
            text = { Text(tr("All photos and passes for {0} are deleted. The avatar photo stays.", racer.name), color = RT.TextDim) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; app.repo.resetCalibration(racer.id) }) { Text(tr("Reset"), color = RT.Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("Cancel"), color = RT.Text) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = RT.Surface2,
            title = { Text(tr("Delete {0}?", racer.name)) },
            text = { Text(tr("Past sessions keep their results."), color = RT.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    nav.pop()
                    app.repo.deleteRacer(racer.id)
                }) { Text(tr("Delete"), color = RT.Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel"), color = RT.Text) } },
        )
    }
}

/** The bib marker drawn as squares (same cells as the printed PDF). */
@Composable
fun MarkerImage(markerId: Int, modifier: Modifier = Modifier) {
    val cells = remember(markerId) { Markers.cells(markerId) }
    androidx.compose.foundation.Canvas(modifier) {
        val c = size.minDimension / 6f
        for (r in 0 until 6) for (col in 0 until 6) {
            if (cells[r][col]) {
                drawRect(
                    Color.Black,
                    topLeft = androidx.compose.ui.geometry.Offset(col * c, r * c),
                    size = androidx.compose.ui.geometry.Size(c + 0.5f, c + 0.5f),
                )
            }
        }
    }
}
