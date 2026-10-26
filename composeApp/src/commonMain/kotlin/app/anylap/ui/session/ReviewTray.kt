package app.anylap.ui.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.anylap.data.ReviewItem
import app.anylap.data.ReviewKind
import app.anylap.data.Session
import app.anylap.platform.Platform
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.Tag
import app.anylap.ui.theme.RT
import app.anylap.engine.Format
import app.anylap.ui.components.uiColor
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * "Check these": crossings the camera wasn't sure about, each with its picture, what the app
 * counted it as, and one tap to set it right. A verdict rebuilds the laps from the session's
 * crossings ([app.anylap.engine.LapMath.resolve]).
 */
@Composable
fun ReviewCard(s: Session, item: ReviewItem, onResolve: (String) -> Unit) {
    val bmp = remember(item.imagePath) { loadReviewImage(item.imagePath) }
    val decidedRacer = item.decided?.let { s.racer(it) }
    val answer = item.resolvedTo
    RtCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(item.kind.title, color = if (answer == null) RT.Amber else RT.TextDim)
            Spacer(Modifier.width(8.dp))
            Text(Format.lap(item.atMs) + " into the session", style = MaterialTheme.typography.bodySmall, color = RT.TextDim)
        }
        Spacer(Modifier.height(8.dp))
        if (bmp != null) {
            Image(
                bmp, null,
                modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            when {
                answer == "" -> "You said: not a car"
                answer != null -> "You said: ${s.racer(answer)?.name ?: answer}"
                decidedRacer != null -> "App counted it for ${decidedRacer.name}" + (if (item.kind == ReviewKind.LOW_MARGIN) " – but only just" else "")
                item.kind == ReviewKind.REJECTED -> "App ignored it (didn't look like any racer)"
                item.kind == ReviewKind.MISSED -> "Something passed the line that the app didn't count"
                else -> "App counted it for nobody"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        Text("Who was it?", style = MaterialTheme.typography.labelSmall, color = RT.TextDim)
        Spacer(Modifier.height(6.dp))
        val shown = item.candidates.mapNotNull { c -> s.racer(c.racerId)?.let { it to c.score } }
        val others = s.racers.filterNot { r -> shown.any { it.first.id == r.id } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for ((racer, score) in shown) {
                Choice(racer.name + "  " + (score * 100).toInt() + " %", racer.uiColor, selected = answer == racer.id, modifier = Modifier.weight(1f)) { onResolve(racer.id) }
            }
        }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (racer in others.take(4)) Choice(racer.name, racer.uiColor, selected = answer == racer.id, modifier = Modifier.weight(1f)) { onResolve(racer.id) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Choice("Not a car", RT.TextDim, selected = answer == "", modifier = Modifier.fillMaxWidth()) { onResolve("") }
    }
}

@Composable
private fun Choice(text: String, color: Color, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color.Black else RT.Text,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (selected) color else RT.Surface2)
            .border(1.dp, if (selected) color else color.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}

private fun loadReviewImage(path: String?): ImageBitmap? = path?.let { p ->
    try { Platform.readBytes(p)?.decodeToImageBitmap() } catch (e: Throwable) { null }
}
