package app.anylap.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.ReviewBox
import app.anylap.data.ReviewItem
import app.anylap.data.ReviewKind
import app.anylap.data.Session
import app.anylap.engine.Format
import app.anylap.engine.LapMath
import app.anylap.platform.Platform
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.Tag
import app.anylap.ui.components.uiColor
import app.anylap.ui.theme.RT
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.hypot

/**
 * "Check these": a crossing the camera wasn't sure about. Picture first – every car in it boxed and
 * named, the finish line, an arrow for the way they drive, and for close finishes the photo-finish
 * ghosts (each car's box at ten steps between the two frames, at its own speed, with the moment it
 * reached the line marked). Tap a car to change who it is; tap the numbers for what they mean.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewCard(
    s: Session,
    item: ReviewItem,
    onReassign: (atMs: Long, to: String) -> Unit,
    onCheck: () -> Unit,
) {
    val bmp = remember(item.imagePath) { loadReviewImage(item.imagePath) }
    // legacy items (before boxes were stored): the subject is the whole picture
    val boxes = item.boxes.ifEmpty { listOf(ReviewBox(0.15f, 0.15f, 0.85f, 0.85f, atMs = item.atMs, subject = true)) }
    var picking by remember(item.id) { mutableStateOf<Int?>(null) }
    var explain by remember(item.id) { mutableStateOf(false) }

    RtCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(item.kind.title, color = if (item.open) kindColor(item.kind) else RT.TextDim)
            Spacer(Modifier.width(8.dp))
            Text(Format.lap(item.atMs), style = MaterialTheme.typography.bodySmall, color = RT.TextDim, modifier = Modifier.weight(1f))
            if (item.open) Pill("Looks right", RT.Green, onCheck) else Text("✓ checked", style = MaterialTheme.typography.labelSmall, color = RT.Green)
        }
        Spacer(Modifier.height(8.dp))
        if (bmp != null) {
            ReviewPicture(s, item, boxes, bmp, picking) { i -> picking = if (picking == i) null else i }
            Spacer(Modifier.height(6.dp))
        }
        photoFinish(s, item, boxes)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }

        // the numbers – tap for what they mean
        Text(
            scoreLine(s, item),
            style = MaterialTheme.typography.bodySmall,
            color = RT.TextDim,
            modifier = Modifier.fillMaxWidth().clickable { explain = !explain }.padding(vertical = 4.dp),
        )
        if (explain) Text(explanation(s, item), style = MaterialTheme.typography.bodySmall, color = RT.Text, modifier = Modifier.padding(bottom = 6.dp))

        val sel = picking?.let { boxes.getOrNull(it) }
        if (sel?.atMs != null) {
            val atMs = sel.atMs
            val now = LapMath.ownerAt(s, atMs)
            Text("Who is this?", style = MaterialTheme.typography.labelSmall, color = RT.TextDim)
            Spacer(Modifier.height(6.dp))
            val ranked = (if (sel.subject) item.candidates.mapNotNull { c -> s.racer(c.racerId) } else emptyList())
            val order = ranked + s.racers.filterNot { r -> ranked.any { it.id == r.id } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in order) {
                    val sc = if (sel.subject) item.candidates.firstOrNull { it.racerId == r.id }?.score else null
                    Choice(r.name + (sc?.let { "  " + (it * 100).toInt() + " %" } ?: ""), r.uiColor, selected = now == r.id) {
                        onReassign(atMs, r.id); picking = null
                    }
                }
                Choice("Not a car", RT.TextDim, selected = now == null && !item.open) { onReassign(atMs, ""); picking = null }
            }
        } else if (item.open) {
            Text("Tap a car to change who it is.", style = MaterialTheme.typography.labelSmall, color = RT.TextFaint)
        }
    }
}

@Composable
private fun ReviewPicture(s: Session, item: ReviewItem, boxes: List<ReviewBox>, bmp: ImageBitmap, picking: Int?, onTap: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val ghosts = showGhosts(item, boxes)
    Box(
        Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(12.dp)),
    ) {
        Image(bmp, null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.matchParentSize().pointerInput(boxes) {
                detectTapGestures { p ->
                    val x = p.x / size.width; val y = p.y / size.height
                    // the smallest box under the finger that crossed (so a car inside a bigger box can be picked)
                    val hit = boxes.withIndex().filter { (_, b) -> b.atMs != null && x in (b.l - 0.04f)..(b.r + 0.04f) && y in (b.t - 0.04f)..(b.b + 0.04f) }
                        .minByOrNull { (_, b) -> (b.r - b.l) * (b.b - b.t) }
                    if (hit != null) onTap(hit.index)
                }
            },
        ) {
            val w = size.width; val h = size.height
            // finish line
            if (item.line.size == 4) drawLine(Color.White.copy(alpha = 0.55f), Offset(item.line[0] * w, item.line[1] * h), Offset(item.line[2] * w, item.line[3] * h), strokeWidth = 1.5.dp.toPx())
            for ((i, b) in boxes.withIndex()) {
                val owner = b.atMs?.let { LapMath.ownerAt(s, it) }?.let { s.racer(it) }
                val col = when {
                    b.atMs == null -> Color.White.copy(alpha = 0.35f)
                    owner != null -> owner.uiColor
                    else -> RT.Amber
                }
                if (ghosts && b.atMs != null) drawGhosts(item, b, col)
                val stroke = if (picking == i) 3.dp.toPx() else 2.dp.toPx()
                drawRect(col, Offset(b.l * w, b.t * h), Size((b.r - b.l) * w, (b.b - b.t) * h), style = Stroke(stroke))
                if (b.atMs != null) label(measurer, owner?.name ?: "?", col, b, w, h)
            }
            directionArrow(boxes.firstOrNull { it.subject } ?: boxes.first())
        }
    }
}

/** Photo-finish ghosts only where the order matters: touching cars, a close call, or two cars within 0.1 s. */
private fun showGhosts(item: ReviewItem, boxes: List<ReviewBox>): Boolean {
    if (item.frameMs <= 0f || item.shotMs == null) return false
    if (item.kind == ReviewKind.SPLIT || item.kind == ReviewKind.LOW_MARGIN) return true
    val times = boxes.mapNotNull { it.atMs }.sorted()
    return times.zipWithNext().any { (a, b) -> b - a < 100 }
}

/**
 * The car's box at ten steps from the frame before the picture to the picture itself, moved at its
 * own speed; the step nearest its crossing time (the moment its front – or rear – reached the line)
 * is drawn solid.
 */
private fun DrawScope.drawGhosts(item: ReviewItem, b: ReviewBox, col: Color) {
    val shot = item.shotMs ?: return
    val w = size.width; val h = size.height
    val frame = item.frameMs
    val atMs = b.atMs ?: return
    val hit = (((atMs - (shot - frame)) / frame) * GHOSTS).toInt().coerceIn(0, GHOSTS)
    for (k in 0..GHOSTS) {
        val dtS = (-frame + frame * k / GHOSTS) / 1000f   // seconds before the picture (≤ 0)
        val dx = b.vx * dtS * w; val dy = b.vy * dtS * h
        val solid = k == hit
        drawRect(
            col.copy(alpha = if (solid) 0.95f else 0.28f),
            Offset(b.l * w + dx, b.t * h + dy), Size((b.r - b.l) * w, (b.b - b.t) * h),
            style = Stroke(if (solid) 1.5.dp.toPx() else 1.dp.toPx()),
        )
    }
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, col: Color, b: ReviewBox, w: Float, h: Float) {
    val style = TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val layout = measurer.measure(text, style)
    val pad = 3.dp.toPx()
    val bw = layout.size.width + 2 * pad; val bh = layout.size.height.toFloat()
    val x = (b.l * w).coerceIn(0f, (w - bw).coerceAtLeast(0f))
    val y = (b.t * h - bh - 2.dp.toPx()).let { if (it < 0f) b.b * h + 2.dp.toPx() else it }.coerceIn(0f, (h - bh).coerceAtLeast(0f))
    drawRect(col, Offset(x, y), Size(bw, bh))
    drawText(layout, topLeft = Offset(x + pad, y))
}

/** White arrow in a corner pointing the way the cars drive (who is ahead depends on it). */
private fun DrawScope.directionArrow(b: ReviewBox) {
    val len = hypot(b.vx * size.width, b.vy * size.height)
    if (len < 1e-3f) return
    val ux = b.vx * size.width / len; val uy = b.vy * size.height / len
    val r = 14.dp.toPx()
    val cx = size.width - r - 8.dp.toPx(); val cy = size.height - r - 8.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.55f), r + 4.dp.toPx(), Offset(cx, cy))
    val tip = Offset(cx + ux * r, cy + uy * r)
    val tail = Offset(cx - ux * r, cy - uy * r)
    drawLine(Color.White, tail, tip, strokeWidth = 3.dp.toPx())
    val px = -uy; val py = ux
    val head = Path().apply {
        moveTo(tip.x + ux * 3.dp.toPx(), tip.y + uy * 3.dp.toPx())
        lineTo(tip.x - ux * 8.dp.toPx() + px * 7.dp.toPx(), tip.y - uy * 8.dp.toPx() + py * 7.dp.toPx())
        lineTo(tip.x - ux * 8.dp.toPx() - px * 7.dp.toPx(), tip.y - uy * 8.dp.toPx() - py * 7.dp.toPx())
        close()
    }
    drawPath(head, Color.White)
}

/** "Red 7 ahead by 3 ms" when two counted cars in the picture crossed within 0.1 s. */
private fun photoFinish(s: Session, item: ReviewItem, boxes: List<ReviewBox>): String? {
    val crossed = boxes.mapNotNull { b -> b.atMs?.let { t -> LapMath.ownerAt(s, t)?.let { s.racer(it) }?.let { it to t } } }.sortedBy { it.second }
    if (crossed.size < 2) return null
    val (a, ta) = crossed[0]; val (b, tb) = crossed[1]
    if (tb - ta >= 100) return null
    return if (tb - ta < 1) "${a.name} and ${b.name}: dead heat" else "${a.name} ahead of ${b.name} by ${tb - ta} ms"
}

private fun scoreLine(s: Session, item: ReviewItem): String {
    val parts = item.candidates.take(3).mapNotNull { c -> s.racer(c.racerId)?.let { "${it.name} ${(c.score * 100).toInt()} %" } }
    return (if (parts.isEmpty()) "No colour match" else parts.joinToString(" · ")) + "   ⓘ"
}

private fun explanation(s: Session, item: ReviewItem): String {
    val top = item.candidates.firstOrNull()?.let { c -> s.racer(c.racerId)?.name?.let { it to (c.score * 100).toInt() } }
    val first = if (top != null) "${top.second} % = how closely this car matches ${top.first}'s calibration photos and passes (100 % = identical). The next ones are the runners-up." else "The car didn't look like any calibrated car."
    val why = when (item.kind) {
        ReviewKind.SPLIT -> "Cars touching: they were one blob at the line and the camera cut them apart."
        ReviewKind.LOW_MARGIN -> "Close call: the runner-up scored almost as high, and the match wasn't clear on its own."
        ReviewKind.UNKNOWN -> "Unknown: no car matched well enough to be named, so it wasn't counted."
        ReviewKind.REJECTED -> "Not a racer?: it didn't look enough like any car (a shadow, a hand…), so it wasn't counted."
        ReviewKind.MISSED -> "Missed?: something passed the second line just beyond the finish without being counted at the finish."
        ReviewKind.RENAMED -> "Re-named: it was named after a car that had just crossed, so lap timing gave it to the runner-up that was due."
        ReviewKind.NOT_COUNTED -> "Not counted: it was named after a car that had just crossed, and no other car fitted – so nobody got the lap."
    }
    val ghosts = if (item.frameMs > 0f) " The faint boxes show each car between the two frames, at its own speed; the solid one is when it reached the line." else ""
    return "$first $why$ghosts"
}

private fun kindColor(k: ReviewKind): Color = when (k) {
    ReviewKind.SPLIT, ReviewKind.LOW_MARGIN, ReviewKind.RENAMED -> RT.Amber
    else -> RT.AccentHot
}

@Composable
private fun Pill(text: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.heightIn(min = 36.dp).clip(shape).border(1.dp, color.copy(alpha = 0.6f), shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun Choice(text: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color.Black else RT.Text,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) color else RT.Surface2)
            .border(1.dp, if (selected) color else color.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private const val GHOSTS = 10

private fun loadReviewImage(path: String?): ImageBitmap? = path?.let { p ->
    try { Platform.readBytes(p)?.decodeToImageBitmap() } catch (e: Throwable) { null }
}
