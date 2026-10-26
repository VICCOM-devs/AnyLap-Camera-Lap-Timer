package app.anylap.ui.racers

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.anylap.data.CalibrationPlan
import app.anylap.data.CalibrationSample
import app.anylap.data.Racer
import app.anylap.data.SampleKind
import app.anylap.platform.Platform
import app.anylap.ui.theme.RT
import app.anylap.util.fixed
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

@OptIn(ExperimentalResourceApi::class)
private fun loadSampleImage(path: String?): ImageBitmap? = path?.let { p ->
    try {
        Platform.readBytes(p)?.decodeToImageBitmap()
    } catch (e: Throwable) {
        null
    }
}

fun SampleKind.title(): String = when (this) {
    SampleKind.PHOTO -> "Photos"
    SampleKind.PASS_SLOW -> "Slow passes"
    SampleKind.PASS_FAST -> "Fast passes"
}

fun SampleKind.icon(): ImageVector = when (this) {
    SampleKind.PHOTO -> Icons.Rounded.CameraAlt
    SampleKind.PASS_SLOW -> Icons.Rounded.SlowMotionVideo
    SampleKind.PASS_FAST -> Icons.Rounded.Speed
}

/** Short caption under a thumbnail: the side for photos, the speed for passes. */
fun CalibrationSample.caption(racer: Racer, kmh: Boolean = true): String = when (kind) {
    SampleKind.PHOTO -> CalibrationPlan.photoViews(racer.sport).firstOrNull { it.key == view }?.title ?: "Photo"
    SampleKind.PASS_SLOW, SampleKind.PASS_FAST -> speedKmh?.let { v ->
        if (kmh) "${v.fixed(0)} km/h" else "${(v / 1.609f).fixed(0)} mph"
    } ?: if (kind == SampleKind.PASS_SLOW) "Slow" else "Fast"
}

/** Thumbnail of one calibration sample, with a delete button in the corner. */
@Composable
fun SampleTile(
    sample: CalibrationSample,
    caption: String,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    size: Dp = 84.dp,
) {
    val bmp = remember(sample.imagePath) { loadSampleImage(sample.imagePath) }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(RT.Surface3)
            .border(1.dp, RT.Stroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .semantics { role = Role.Button; contentDescription = "$caption – open" },
    ) {
        if (bmp != null) {
            Image(bmp, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize())
        } else {
            Icon(sample.kind.icon(), null, tint = RT.TextFaint, modifier = Modifier.align(Alignment.Center).size(28.dp))
        }
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall.copy(color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        if (onDelete != null) {
            // 32 dp visible, but the clickable area is the whole corner box.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(36.dp)
                    .clickable(onClick = onDelete)
                    .semantics { role = Role.Button; contentDescription = "Delete $caption" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/** Empty slot: tap to go and capture it. */
@Composable
fun AddSampleTile(label: String, onClick: () -> Unit, size: Dp = 84.dp, required: Boolean = false) {
    Column(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(RT.Surface)
            .border(1.dp, if (required) RT.Amber.copy(alpha = 0.6f) else RT.StrokeHigh, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "Add $label" }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Add, null, tint = if (required) RT.Amber else RT.TextDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = RT.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Everything the camera learned from, grouped as photos / slow / fast passes.
 * [onAdd] (optional) jumps to capturing more of that kind (photo view key or pass kind).
 */
@Composable
fun CalibrationGallery(
    racer: Racer,
    onOpen: (CalibrationSample) -> Unit,
    onDelete: (CalibrationSample) -> Unit,
    onAddPhoto: ((String) -> Unit)?,
    onAddPass: ((SampleKind) -> Unit)?,
    kmh: Boolean = true,
    tile: Dp = 84.dp,
) {
    val views = CalibrationPlan.photoViews(racer.sport)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (kind in SampleKind.entries) {
            val items = racer.samplesOf(kind)
            val target = if (kind == SampleKind.PHOTO) views.size else CalibrationPlan.PASSES_PER_SPEED
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kind.icon(), null, tint = RT.TextDim, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(kind.title().uppercase(), style = MaterialTheme.typography.labelSmall, color = RT.TextDim)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${items.size}/$target",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (items.size >= target) RT.Green else RT.TextFaint,
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (kind == SampleKind.PHOTO) {
                    // One slot per side, in plan order; missing sides are "+" slots.
                    for (v in views) {
                        val s = items.firstOrNull { it.view == v.key }
                        if (s != null) SampleTile(s, s.caption(racer, kmh), { onOpen(s) }, { onDelete(s) }, tile)
                        else if (onAddPhoto != null) AddSampleTile(v.title, { onAddPhoto(v.key) }, tile, v.required)
                    }
                } else {
                    items.forEach { s -> SampleTile(s, s.caption(racer, kmh), { onOpen(s) }, { onDelete(s) }, tile) }
                    if (onAddPass != null && items.size < CalibrationPlan.MAX_PASSES_PER_SPEED) {
                        AddSampleTile(if (items.size < target) "Record" else "More", { onAddPass(kind) }, tile, items.size < target)
                    }
                }
                if (items.isEmpty() && onAddPhoto == null && onAddPass == null) {
                    Text("None yet", style = MaterialTheme.typography.bodySmall, color = RT.TextFaint)
                }
            }
        }
    }
}

/** Big view of one sample with a delete button. */
@Composable
fun SampleDialog(racer: Racer, sample: CalibrationSample, kmh: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val bmp = remember(sample.imagePath) { loadSampleImage(sample.imagePath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RT.Surface2,
        title = { Text("${sample.kind.title().removeSuffix("s")} · ${sample.caption(racer, kmh)}") },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)).background(RT.Surface3),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize())
                    else Text("No picture saved", color = RT.TextFaint)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (sample.kind == SampleKind.PHOTO) "Delete it if the car wasn't in the box or something else was in the picture."
                    else "Delete it if this wasn't the ${racer.sport.unitName}, it crashed, or someone walked through.",
                    style = MaterialTheme.typography.bodySmall,
                )
                sample.match?.let { m ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Looked ${(m * 100).fixed(0)}% like the other samples.",
                        style = MaterialTheme.typography.labelSmall, color = RT.TextDim,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = RT.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete", color = RT.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = RT.Text) } },
    )
}
