package app.anylap.ui.components

import app.anylap.i18n.tr
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.Racer
import app.anylap.platform.Platform
import app.anylap.ui.theme.Num
import app.anylap.ui.theme.RT
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Racer colour as a Compose colour. */
val Racer.uiColor: Color get() = Color(color.toInt())

/** Three-letter code like a timing screen: "Red Mini-Z" → "RED". */
val Racer.code: String
    get() {
        val letters = name.filter { it.isLetterOrDigit() }.uppercase()
        return if (letters.length >= 3) letters.take(3) else letters.padEnd(3, '·')
    }

// ------------------------------------------------------------------ screen frame

/**
 * Standard screen: broadcast-style red glow at the top, a header row and one scrolling list.
 * [titleSlot] replaces the plain title (e.g. the AnyLap wordmark on Home).
 */
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomPadding: Dp = 112.dp,
    titleSlot: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(RT.Bg)) {
        HeaderGlow()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header", contentType = "header") {
                Row(
                    Modifier.statusBarsPadding().padding(top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, onBack, contentDescription = tr("Back"))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        if (titleSlot != null) titleSlot() else {
                            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                }
            }
            content()
        }
    }
}

/** Deep red radial glow with faint diagonal speed lines – the AnyLap backdrop. */
@Composable
fun HeaderGlow(height: Dp = 340.dp) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        drawRect(
            Brush.radialGradient(
                colors = listOf(RT.AccentDeep.copy(alpha = 0.95f), Color(0xFF3A080B).copy(alpha = 0.6f), Color.Transparent),
                center = Offset(size.width * 0.15f, -size.height * 0.15f),
                radius = size.width * 1.15f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(RT.Accent.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.05f),
                radius = size.width * 0.6f,
            ),
        )
        speedLines(size, alpha = 0.05f)
        // fade into the page
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, RT.Bg), startY = size.height * 0.55f, endY = size.height))
    }
}

/** Thin diagonal streaks, like motion blur on a broadcast graphic. */
fun DrawScope.speedLines(area: Size, alpha: Float) {
    val step = 22.dp.toPx()
    var x = -area.height
    var i = 0
    while (x < area.width) {
        val w = if (i % 3 == 0) 2.dp.toPx() else 1.dp.toPx()
        drawLine(
            Color.White.copy(alpha = alpha * (if (i % 3 == 0) 1.4f else 0.8f)),
            Offset(x, area.height), Offset(x + area.height * 0.55f, 0f), strokeWidth = w,
        )
        x += step
        i++
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = RT.Text,
    size: Dp = 44.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------ brand

/** "AnyLap" wordmark with the chequered mark. */
@Composable
fun BrandWordmark(size: Dp = 30.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChequeredMark(Modifier.size(size))
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                append("ANY")
                withStyle(SpanStyle(color = RT.Accent)) { append("LAP") }
            },
            style = MaterialTheme.typography.headlineLarge.copy(
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

/** Slanted 3x3 chequered flag tile with a red swoosh. */
@Composable
fun ChequeredMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val cell = s / 3.4f
        val skew = cell * 0.35f
        for (r in 0 until 3) for (c in 0 until 3) {
            val x = c * cell + (2 - r) * skew * 0.5f
            val y = r * cell
            val p = Path().apply {
                moveTo(x + skew * 0.5f, y)
                lineTo(x + cell + skew * 0.5f, y)
                lineTo(x + cell, y + cell)
                lineTo(x, y + cell)
                close()
            }
            drawPath(p, if ((r + c) % 2 == 0) Color.White else Color.White.copy(alpha = 0.18f))
        }
        val swoosh = Path().apply {
            moveTo(0f, s * 0.92f)
            quadraticTo(s * 0.55f, s * 0.70f, s, s * 0.80f)
            lineTo(s, s * 0.92f)
            quadraticTo(s * 0.55f, s * 0.86f, 0f, s)
            close()
        }
        drawPath(swoosh, RT.Accent)
    }
}

// ------------------------------------------------------------------ cards & labels

@Composable
fun RtCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Color? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(RT.CardGradient)
            .border(1.dp, highlight ?: RT.Stroke, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Card with a coloured stripe down the left edge (racer colour, like a timing tower). */
@Composable
fun StripeCard(
    stripe: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(RT.CardGradient)
            .border(1.dp, RT.Stroke, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                drawRect(stripe, size = Size(4.dp.toPx(), size.height))
                drawRect(
                    Brush.horizontalGradient(listOf(stripe.copy(alpha = 0.16f), Color.Transparent), endX = size.width * 0.45f),
                )
            }
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(RT.Accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        if (icon != null) {
            Icon(icon, null, tint = RT.TextDim, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = RT.TextDim, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Tag(text: String, color: Color = RT.Amber, filled: Boolean = false) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.9.sp),
        color = if (filled) Color.Black else color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    accent: Color = RT.Accent,
    valueColor: Color = RT.Text,
) {
    RtCard(modifier = modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = Num.style(26.sp, FontWeight.Bold), color = valueColor, maxLines = 1)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall.copy(color = RT.TextFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

// ------------------------------------------------------------------ pills & buttons

@Composable
fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(RT.Surface)
            .border(1.dp, RT.Stroke, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            val textColor by animateColorAsState(if (isSel) Color.White else RT.TextDim, label = "pillText")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(50))
                    .then(if (isSel) Modifier.background(RT.AccentGradient) else Modifier)
                    .clickable { onSelect(i) }
                    .semantics { role = Role.Tab; this.selected = isSel }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = textColor, maxLines = 1)
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = RT.Accent,
) {
    val shape = RoundedCornerShape(16.dp)
    val brush = when {
        !enabled -> Brush.horizontalGradient(listOf(RT.Surface3, RT.Surface3))
        color == RT.Accent -> RT.AccentGradient
        else -> Brush.horizontalGradient(listOf(color, color))
    }
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(brush)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.12f else 0.04f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) Color.White else RT.TextFaint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
            color = if (enabled) Color.White else RT.TextFaint,
        )
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(RT.Surface2)
            .border(1.dp, RT.StrokeHigh, shape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = RT.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, range: IntRange, suffix: String, step: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", tr("Decrease")) { onChange((value - step).coerceIn(range)) }
        Row(Modifier.width(118.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text("$value", style = Num.style(28.sp, FontWeight.ExtraBold))
            if (suffix.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(suffix, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        StepButton("+", tr("Increase")) { onChange((value + step).coerceIn(range)) }
    }
}

@Composable
private fun StepButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(RT.Surface3)
            .border(1.dp, RT.StrokeHigh, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Slider with a chunky track and a white thumb ringed in red. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RtSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = RT.Accent, inactiveTrackColor = RT.Surface3),
        thumb = {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(Color.White).border(5.dp, RT.Accent, CircleShape),
            )
        },
        track = { state ->
            val frac = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(RT.Surface3)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(RT.AccentGradient))
            }
        },
    )
}

// ------------------------------------------------------------------ racers

@OptIn(ExperimentalResourceApi::class)
private fun loadPhoto(path: String?) = path?.let { p ->
    try {
        Platform.readBytes(p)?.decodeToImageBitmap()
    } catch (e: Throwable) {
        null
    }
}

@Composable
fun RacerAvatar(racer: Racer, size: Dp = 44.dp, ring: Color? = null) {
    val bmp = remember(racer.photoPath) { loadPhoto(racer.photoPath) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(racer.uiColor, racer.uiColor.copy(alpha = 0.65f))))
            .border(if (ring != null) 2.5.dp else 0.dp, ring ?: Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = racer.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            NumberBadge(racer, Modifier.align(Alignment.BottomEnd), small = true)
        } else {
            Text(
                racer.number.toString(),
                color = Color.White,
                style = Num.style((size.value * 0.42f).sp, FontWeight.Black, italic = true),
            )
        }
    }
}

@Composable
fun NumberBadge(racer: Racer, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        racer.number.toString(),
        color = Color.White,
        style = Num.style(if (small) 10.sp else 12.sp, FontWeight.Black, italic = true),
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun PositionBadge(pos: Int, size: Dp = 32.dp) {
    val c = RT.podium(pos)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(9.dp))
            .background(if (pos <= 3) c.copy(alpha = 0.16f) else RT.Surface3),
        contentAlignment = Alignment.Center,
    ) {
        Text("$pos", color = if (pos <= 3) c else RT.TextDim, style = Num.style((size.value * 0.5f).sp, FontWeight.Black, italic = true))
    }
}

/** Overlapping avatars (e.g. a session's podium). */
@Composable
fun AvatarStack(racers: List<Racer>, size: Dp = 30.dp) {
    Box(Modifier.width(size + (size * 0.62f) * (racers.size - 1).coerceAtLeast(0)).height(size)) {
        racers.forEachIndexed { i, r ->
            Box(Modifier.padding(start = size * 0.62f * i).clip(CircleShape).border(2.dp, RT.Surface, CircleShape)) {
                RacerAvatar(r, size)
            }
        }
    }
}

// ------------------------------------------------------------------ timeline & misc

@Composable
fun DateDivider(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = RT.TextDim,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(RT.Surface)
                .border(1.dp, RT.Stroke, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Left rail with a dot, like a schedule timeline. */
@Composable
fun TimelineItem(active: Boolean, content: @Composable BoxScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(if (active) RT.Accent else RT.Bg)
                    .border(2.dp, if (active) RT.AccentHot else RT.TextFaint, CircleShape),
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), content = content)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    RtCard(Modifier.fillMaxWidth(), padding = 24.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Brush.radialGradient(listOf(RT.AccentDeep, RT.AccentSoft))),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, textAlign = TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = RT.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = Num.style(15.sp, FontWeight.SemiBold), color = valueColor)
    }
}
