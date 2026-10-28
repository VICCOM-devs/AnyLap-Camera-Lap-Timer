package app.anylap.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.ReviewBox
import app.anylap.data.ReviewItem
import app.anylap.data.FinishEdge
import app.anylap.data.Racer
import app.anylap.data.ReviewKind
import app.anylap.data.Session
import app.anylap.engine.Format
import app.anylap.engine.LapMath
import app.anylap.platform.Platform
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.Tag
import app.anylap.ui.components.uiColor
import app.anylap.ui.theme.RT
import app.anylap.vision.LabelLayout
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
        item.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = RT.Text)
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
    val style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    // the picture sits inside a dark band: the name tags go wherever no car is – in the picture or in
    // the band – so a tag never covers a car (LabelLayout, the same code as the simulation page)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RT.Surface2)) {
        val band = 34.dp
        val bandPx = with(LocalDensity.current) { band.toPx() }
        val outerW = constraints.maxWidth.toFloat()
        val picW = outerW - 2 * bandPx
        val picH = picW * bmp.height / bmp.width
        val outerH = picH + 2 * bandPx
        val names = boxes.map { b -> (b.atMs?.let { LapMath.ownerAt(s, it) } ?: b.racerId)?.let { s.racer(it) } }
        // every car's outline in canvas px (fitted outline, else the tracker's box)
        val polys = boxes.map { b ->
            val q = b.quad?.takeIf { it.size == 8 } ?: listOf(b.l, b.t, b.r, b.t, b.r, b.b, b.l, b.b)
            FloatArray(8) { k -> if (k % 2 == 0) bandPx + q[k] * picW else bandPx + q[k] * picH }
        }
        val labelled = boxes.indices.filter { boxes[it].atMs != null || boxes[it].racerId != null }
        val layouts = labelled.map { i -> measurer.measure(names[i]?.name ?: "?", style) }
        val padX = with(LocalDensity.current) { 6.dp.toPx() }; val padY = with(LocalDensity.current) { 3.dp.toPx() }
        val gapPx = with(LocalDensity.current) { 3.dp.toPx() }
        val tags = remember(boxes, outerW, names, layouts.map { it.size }) {
            LabelLayout.place(
                labelled.map { polys[it] },
                layouts.map { (it.size.width + 2 * padX) to (it.size.height + 2 * padY) },
                2f, 2f, outerW - 2f, outerH - 2f,
                gap = gapPx,
            )
        }
        Canvas(
            Modifier.fillMaxWidth().height(with(LocalDensity.current) { outerH.toDp() }).pointerInput(boxes, outerW) {
                detectTapGestures { p ->
                    // a tag, or the smallest outline under the finger (a car inside a bigger box can be picked)
                    val onTag = labelled.indices.firstOrNull { k -> val t = tags[k]; p.x in t.x..(t.x + t.w) && p.y in t.y..(t.y + t.h) }?.let { labelled[it] }
                    val grow = 0.04f * picW
                    val hit = onTag ?: boxes.indices.filter { i -> boxes[i].atMs != null && insidePoly(polys[i], p.x, p.y, grow) }
                        .minByOrNull { i -> (boxes[i].r - boxes[i].l) * (boxes[i].b - boxes[i].t) }
                    if (hit != null) onTap(hit)
                }
            },
        ) {
            drawImage(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), IntOffset(bandPx.toInt(), bandPx.toInt()), IntSize(picW.toInt(), picH.toInt()))
            clipRect(bandPx, bandPx, bandPx + picW, bandPx + picH) {
                if (item.line.size == 4) drawLine(Color.White.copy(alpha = 0.55f), Offset(bandPx + item.line[0] * picW, bandPx + item.line[1] * picH), Offset(bandPx + item.line[2] * picW, bandPx + item.line[3] * picH), strokeWidth = 1.5.dp.toPx())
                for ((i, b) in boxes.withIndex()) {
                    val col = colourOf(b, names[i])
                    if (ghosts && b.atMs != null) drawGhosts(item, b, polys[i], picW, picH, col)
                    val stroke = if (picking == i) 3.dp.toPx() else 2.dp.toPx()
                    drawPath(polyPath(polys[i]), col, style = Stroke(stroke))
                    if (b.quad != null) drawLine(Color.White, Offset(polys[i][0], polys[i][1]), Offset(polys[i][2], polys[i][3]), strokeWidth = stroke)   // nose edge
                }
            }
            for ((k, i) in labelled.withIndex()) {
                val t = tags[k]; val col = colourOf(boxes[i], names[i])
                val lx = t.ax.coerceIn(t.x, t.x + t.w); val ly = t.ay.coerceIn(t.y, t.y + t.h)
                drawLine(col, Offset(t.ax, t.ay), Offset(lx, ly), strokeWidth = 1.5.dp.toPx())
                drawCircle(col, 2.5.dp.toPx(), Offset(t.ax, t.ay))
                drawRoundRect(Color(0xE6141418), Offset(t.x, t.y), Size(t.w, t.h), CornerRadius(6.dp.toPx()))
                drawRoundRect(col, Offset(t.x, t.y), Size(t.w, t.h), CornerRadius(6.dp.toPx()), style = Stroke(if (picking == i) 2.5.dp.toPx() else 1.5.dp.toPx()))
                drawText(layouts[k], topLeft = Offset(t.x + padX, t.y + padY))
            }
            directionArrow(boxes.firstOrNull { it.subject } ?: boxes.first(), tags)
        }
    }
}

private fun colourOf(b: ReviewBox, owner: Racer?): Color = when {
    b.atMs == null && owner == null -> Color.White.copy(alpha = 0.35f)
    owner != null -> owner.uiColor
    else -> RT.Amber
}

private fun polyPath(p: FloatArray) = Path().apply { moveTo(p[0], p[1]); for (k in 1 until p.size / 2) lineTo(p[2 * k], p[2 * k + 1]); close() }

/** Point in a convex polygon grown by [grow] px. */
private fun insidePoly(p: FloatArray, x: Float, y: Float, grow: Float): Boolean {
    val n = p.size / 2
    var cx = 0f; var cy = 0f
    for (k in 0 until n) { cx += p[2 * k]; cy += p[2 * k + 1] }
    cx /= n; cy /= n
    for (k in 0 until n) {
        val ax = p[2 * k]; val ay = p[2 * k + 1]; val bx = p[(2 * k + 2) % (2 * n)]; val by = p[(2 * k + 3) % (2 * n)]
        var nx = by - ay; var ny = -(bx - ax)
        val len = maxOf(1e-3f, hypot(nx, ny)); nx /= len; ny /= len
        if ((cx - ax) * nx + (cy - ay) * ny > 0) { nx = -nx; ny = -ny }
        if ((x - ax) * nx + (y - ay) * ny > grow) return false
    }
    return true
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
private fun DrawScope.drawGhosts(item: ReviewItem, b: ReviewBox, poly: FloatArray, picW: Float, picH: Float, col: Color) {
    val shot = item.shotMs ?: return
    val frame = item.frameMs
    val atMs = b.atMs ?: return
    val hit = (((atMs - (shot - frame)) / frame) * GHOSTS).toInt().coerceIn(0, GHOSTS)
    for (k in 0..GHOSTS) {
        val dtS = (-frame + frame * k / GHOSTS) / 1000f   // seconds before the picture (≤ 0)
        val dx = b.vx * dtS * picW; val dy = b.vy * dtS * picH
        val solid = k == hit
        val moved = FloatArray(poly.size) { j -> poly[j] + if (j % 2 == 0) dx else dy }
        drawPath(polyPath(moved), col.copy(alpha = if (solid) 0.95f else 0.28f), style = Stroke(if (solid) 1.5.dp.toPx() else 1.dp.toPx()))
    }
}

/** White arrow in a corner pointing the way the cars drive (who is ahead depends on it). */
private fun DrawScope.directionArrow(b: ReviewBox, tags: List<LabelLayout.Tag>) {
    val len = hypot(b.vx * size.width, b.vy * size.height)
    if (len < 1e-3f) return
    val ux = b.vx * size.width / len; val uy = b.vy * size.height / len
    val r = 11.dp.toPx()
    // a corner of the band no tag uses
    val corners = listOf(size.width - r - 6.dp.toPx() to size.height - r - 6.dp.toPx(), r + 6.dp.toPx() to size.height - r - 6.dp.toPx(), size.width - r - 6.dp.toPx() to r + 6.dp.toPx(), r + 6.dp.toPx() to r + 6.dp.toPx())
    val (cx, cy) = corners.firstOrNull { (x, y) -> tags.none { t -> x + r + 4 > t.x && x - r - 4 < t.x + t.w && y + r + 4 > t.y && y - r - 4 < t.y + t.h } } ?: corners.first()
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
    val ghosts = if (item.frameMs > 0f) " The faint outlines show each car between the two frames, at its own speed; the solid one is when it reached the line." else ""
    val timing = when (s.config.finishEdge) {
        FinishEdge.FRONT -> "the car's front (the white edge of its outline) touches the finish line"
        FinishEdge.REAR -> "the back of the car (the edge opposite the white one) clears the finish line"
        FinishEdge.CENTRE -> "the middle of the car is on the finish line"
    }
    val outline = " The coloured outline only shows which car it is – that car's calibration picture laid over it. The car itself sets the time: a lap is timed when $timing."
    return "$first $why$outline$ghosts"
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
