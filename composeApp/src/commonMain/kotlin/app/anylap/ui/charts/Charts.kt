package app.anylap.ui.charts

import app.anylap.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.ui.theme.RT

data class ChartSeries(val name: String, val color: Color, val points: List<Pair<Float, Float>>)

/**
 * Minimal, clean multi-series line chart.
 * @param invertY true for positions (P1 at the top).
 * @param integerY draw whole-number y labels only (positions).
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    yLabel: (Float) -> String = { it.toString() },
    xLabel: (Float) -> String = { it.toInt().toString() },
    invertY: Boolean = false,
    integerY: Boolean = false,
    showLegend: Boolean = true,
    highlightMin: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = RT.TextFaint, fontSize = 10.sp)
    val all = series.flatMap { it.points }
    Column(modifier) {
        if (all.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                Text(tr("Not enough data yet"), style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        var minX = all.minOf { it.first }
        var maxX = all.maxOf { it.first }
        var minY = all.minOf { it.second }
        var maxY = all.maxOf { it.second }
        if (maxX - minX < 1e-6f) { minX -= 1f; maxX += 1f }
        if (maxY - minY < 1e-6f) { minY -= 1f; maxY += 1f }
        if (!integerY) {
            val pad = (maxY - minY) * 0.08f
            minY -= pad; maxY += pad
        } else {
            minY = minY.coerceAtMost(1f); maxY += 0.3f; minY -= 0.3f
        }
        val lowest = if (highlightMin) all.minByOrNull { it.second } else null

        Canvas(Modifier.fillMaxWidth().height(height)) {
            val left = 46.dp.toPx()
            val bottom = size.height - 22.dp.toPx()
            val top = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            fun px(x: Float) = left + (x - minX) / (maxX - minX) * (right - left)
            fun py(y: Float): Float {
                val f = (y - minY) / (maxY - minY)
                return if (invertY) top + f * (bottom - top) else bottom - f * (bottom - top)
            }

            // Grid + y labels
            val ticks = if (integerY) {
                (kotlin.math.ceil(minY).toInt()..kotlin.math.floor(maxY).toInt()).map { it.toFloat() }
            } else List(5) { i -> minY + (maxY - minY) * i / 4f }
            for (t in ticks) {
                val y = py(t)
                drawLine(RT.Stroke, Offset(left, y), Offset(right, y), strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val tl = measurer.measure(yLabel(t), labelStyle)
                drawText(tl, topLeft = Offset(left - tl.size.width - 6.dp.toPx(), y - tl.size.height / 2f))
            }
            // X labels (up to ~8)
            val xs = all.map { it.first }.distinct().sorted()
            val stepX = kotlin.math.max(1, xs.size / 8)
            xs.filterIndexed { i, _ -> i % stepX == 0 }.forEach { x ->
                val tl = measurer.measure(xLabel(x), labelStyle)
                drawText(tl, topLeft = Offset(px(x) - tl.size.width / 2f, bottom + 5.dp.toPx()))
            }

            for (s in series) {
                if (s.points.isEmpty()) continue
                val pts = s.points.sortedBy { it.first }
                val path = Path()
                pts.forEachIndexed { i, (x, y) ->
                    if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
                }
                if (series.size == 1 && !invertY && pts.size > 1) {
                    // Soft area fill under a single line.
                    val area = Path().apply {
                        addPath(path)
                        lineTo(px(pts.last().first), bottom)
                        lineTo(px(pts.first().first), bottom)
                        close()
                    }
                    drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), Color.Transparent), startY = top, endY = bottom))
                }
                drawPath(path, s.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                for ((x, y) in pts) {
                    drawCircle(RT.Bg, radius = 4.dp.toPx(), center = Offset(px(x), py(y)))
                    drawCircle(s.color, radius = 2.6.dp.toPx(), center = Offset(px(x), py(y)))
                }
            }
            if (lowest != null) {
                drawCircle(RT.Purple, radius = 6.dp.toPx(), center = Offset(px(lowest.first), py(lowest.second)), style = Stroke(2.dp.toPx()))
            }
        }
        if (showLegend && series.size > 1) {
            Spacer(Modifier.height(8.dp))
            Legend(series)
        }
    }
}

@Composable
fun Legend(series: List<ChartSeries>) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        series.take(6).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(5.dp))
                Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

/** Horizontal bars, e.g. best lap per racer. Shorter is better when [lowerIsBetter]. */
@Composable
fun BarList(
    items: List<Triple<String, Color, Float>>,
    valueLabel: (Float) -> String,
    lowerIsBetter: Boolean = true,
) {
    if (items.isEmpty()) return
    val maxV = items.maxOf { it.third }.coerceAtLeast(1e-6f)
    val minV = items.minOf { it.third }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (name, color, v) ->
            val frac = if (lowerIsBetter) (minV / v).coerceIn(0.05f, 1f) else (v / maxV).coerceIn(0.05f, 1f)
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(valueLabel(v), style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(RT.Surface3)) {
                    Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(CircleShape).background(color))
                }
            }
        }
    }
}
