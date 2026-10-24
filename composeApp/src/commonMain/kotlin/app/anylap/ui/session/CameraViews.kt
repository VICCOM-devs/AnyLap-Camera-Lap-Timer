package app.anylap.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import app.anylap.data.Direction
import app.anylap.data.LineSpec
import app.anylap.ui.theme.RT
import app.anylap.vision.VisionSession
import app.anylap.vision.rememberCameraController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.anylap.vision.OverlayBox
import kotlin.math.hypot

/** Camera + shared analyzer for a camera screen; null if the camera can't be opened. */
@Composable
fun rememberVisionSession(): VisionSession? {
    val camera = rememberCameraController() ?: return null
    val session = remember(camera) { VisionSession(camera) }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}

/**
 * Draggable finish line. Drag an end to move it, drag the middle to move the whole line.
 * The arrow shows which crossing direction counts (both arrows = either way).
 */
@Composable
fun FinishLineOverlay(
    line: LineSpec,
    direction: Direction,
    editable: Boolean,
    onChange: (LineSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(line)
    val latestOnChange by rememberUpdatedState(onChange)
    val gestures = if (editable) Modifier.pointerInput(Unit) {
        var target = 0
        detectDragGestures(
            onDragStart = { pos ->
                val l = latest
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val da = hypot(pos.x - l.x1 * w, pos.y - l.y1 * h)
                val db = hypot(pos.x - l.x2 * w, pos.y - l.y2 * h)
                val grab = 56.dp2px(this)
                target = when {
                    da < grab && da <= db -> 1
                    db < grab -> 2
                    else -> 3
                }
            },
            onDrag = { change, drag ->
                change.consume()
                val dx = drag.x / size.width
                val dy = drag.y / size.height
                val l = latest
                fun c(v: Float) = v.coerceIn(0f, 1f)
                latestOnChange(
                    when (target) {
                        1 -> l.copy(x1 = c(l.x1 + dx), y1 = c(l.y1 + dy))
                        2 -> l.copy(x2 = c(l.x2 + dx), y2 = c(l.y2 + dy))
                        else -> l.copy(x1 = c(l.x1 + dx), y1 = c(l.y1 + dy), x2 = c(l.x2 + dx), y2 = c(l.y2 + dy))
                    },
                )
            },
        )
    } else Modifier

    Canvas(modifier.fillMaxSize().then(gestures)) {
        val a = Offset(line.x1 * size.width, line.y1 * size.height)
        val b = Offset(line.x2 * size.width, line.y2 * size.height)
        // Chequered look: dark under-stroke, white dashes on red.
        drawLine(Color.Black.copy(alpha = 0.55f), a, b, strokeWidth = 12.dp2px(this), cap = StrokeCap.Round)
        drawLine(RT.Accent, a, b, strokeWidth = 6.dp2px(this), cap = StrokeCap.Round)
        drawLine(
            Color.White, a, b, strokeWidth = 6.dp2px(this),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 14f)),
        )
        if (editable) {
            for (p in listOf(a, b)) {
                drawCircle(Color.Black.copy(alpha = 0.55f), radius = 17.dp2px(this), center = p)
                drawCircle(Color.White, radius = 12.dp2px(this), center = p)
                drawCircle(RT.Accent, radius = 6.dp2px(this), center = p)
            }
        }
        // Direction arrow(s) at the middle, perpendicular to the line.
        val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy).coerceAtLeast(1f)
        // Normal pointing to the side a racer moves *to* when crossing "forward".
        val nx = -dy / len
        val ny = dx / len
        fun arrow(sign: Float) {
            val tip = Offset(mid.x + nx * sign * 46.dp2px(this), mid.y + ny * sign * 46.dp2px(this))
            val base = Offset(mid.x + nx * sign * 14.dp2px(this), mid.y + ny * sign * 14.dp2px(this))
            drawLine(Color.White, base, tip, strokeWidth = 4.dp2px(this), cap = StrokeCap.Round)
            val px = dx / len * 10.dp2px(this)
            val py = dy / len * 10.dp2px(this)
            val back = Offset(tip.x - nx * sign * 12.dp2px(this), tip.y - ny * sign * 12.dp2px(this))
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(back.x + px, back.y + py)
                lineTo(back.x - px, back.y - py)
                close()
            }
            drawPath(path, Color.White)
        }
        when (direction) {
            Direction.BOTH -> { arrow(1f); arrow(-1f) }
            Direction.FORWARD -> arrow(1f)
            Direction.REVERSE -> arrow(-1f)
        }
    }
}

/** Thin boxes around everything the camera currently sees moving. */
@Composable
fun TrackBoxesOverlay(boxes: List<OverlayBox>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        for (b in boxes) {
            val c = if (b.nearLine) RT.Amber else Color.White.copy(alpha = 0.7f)
            drawRect(
                c,
                topLeft = Offset(b.l * size.width, b.t * size.height),
                size = Size((b.r - b.l) * size.width, (b.b - b.t) * size.height),
                style = Stroke(width = 2f),
            )
        }
    }
}

private fun Int.dp2px(d: androidx.compose.ui.unit.Density): Float = with(d) { this@dp2px.toFloat() * density }
