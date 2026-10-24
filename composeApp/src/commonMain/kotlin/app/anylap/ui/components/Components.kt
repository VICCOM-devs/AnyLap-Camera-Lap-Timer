package app.anylap.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.Racer
import app.anylap.ui.theme.RT
import app.anylap.platform.Platform
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Racer colour as a Compose colour. */
val Racer.uiColor: Color get() = Color(color.toInt())

// ------------------------------------------------------------------ screen frame

/**
 * Standard screen: deep-red glow at the top (like a race broadcast), a header row and a scrolling list.
 */
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomPadding: Dp = 110.dp,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(RT.Bg)) {
        HeaderGlow()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) {
                            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, onBack)
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                    }
                }
            }
            content()
        }
    }
}

@Composable
fun HeaderGlow(height: Dp = 320.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    0f to RT.AccentDeep.copy(alpha = 0.85f),
                    0.45f to Color(0xFF2A0709).copy(alpha = 0.7f),
                    1f to Color.Transparent,
                ),
            ),
    )
}

@Composable
fun CircleIconButton(icon: ImageVector, onClick: () -> Unit, tint: Color = RT.Text, size: Dp = 42.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
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
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(RT.Surface)
            .border(1.dp, highlight ?: RT.Stroke, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
        color = if (filled) Color.Black else color,
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
                Icon(icon, null, tint = accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), color = valueColor, maxLines = 1)
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
            val bg by animateColorAsState(if (isSel) RT.Accent else Color.Transparent, label = "pill")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSel) Color.White else RT.TextDim,
                    maxLines = 1,
                )
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
    Row(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(
                if (enabled) Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.82f)))
                else Brush.horizontalGradient(listOf(RT.Surface3, RT.Surface3)),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) Color.White else RT.TextFaint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) Color.White else RT.TextFaint)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(RT.Surface2)
            .border(1.dp, RT.Stroke, shape)
            .clickable(onClick = onClick)
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
        StepButton("−") { onChange((value - step).coerceIn(range)) }
        Text(
            "$value $suffix",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(110.dp),
        )
        StepButton("+") { onChange((value + step).coerceIn(range)) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(RT.Surface3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
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
            .background(racer.uiColor)
            .border(2.dp, ring ?: racer.uiColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = racer.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            NumberBadge(racer, Modifier.align(Alignment.BottomEnd), small = true)
        } else {
            Text(
                racer.number.toString(),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.4f).sp,
            )
        }
    }
}

@Composable
fun NumberBadge(racer: Racer, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        racer.number.toString(),
        color = Color.White,
        fontWeight = FontWeight.Black,
        fontSize = if (small) 9.sp else 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun PositionBadge(pos: Int, size: Dp = 30.dp) {
    val c = RT.podium(pos)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(9.dp))
            .background(if (pos <= 3) c.copy(alpha = 0.16f) else RT.Surface3),
        contentAlignment = Alignment.Center,
    ) {
        Text("P$pos", color = if (pos <= 3) c else RT.TextDim, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ timeline

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
                    .border(2.dp, if (active) RT.Accent else RT.TextFaint, CircleShape),
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
                Modifier.size(56.dp).clip(CircleShape).background(RT.AccentSoft),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = RT.Accent, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.height(12.dp))
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
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor)
    }
}
