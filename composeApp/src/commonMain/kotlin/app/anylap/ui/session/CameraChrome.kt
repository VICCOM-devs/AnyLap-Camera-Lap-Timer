package app.anylap.ui.session

import app.anylap.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.data.Direction
import app.anylap.ui.theme.RT

/*
 * Shared chrome for the full-screen camera screens (line setup, calibration):
 * a slim icon rail on the right and a small line-tool column on the left,
 * so the camera image gets nearly the whole screen.
 */

val CameraRailWidth: Dp = 78.dp

/** Slim vertical icon rail at the right edge of a camera screen. */
@Composable
fun CameraRail(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .width(CameraRailWidth)
            .fillMaxHeight()
            .background(RT.Bg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * One rail button: round icon, tiny label under it, optional badge (e.g. "2/4").
 * 44 dp touch target; [selected] fills it red, [done] gives the badge a green tint.
 */
@Composable
fun RailButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    badge: String? = null,
    done: Boolean = false,
    size: Dp = 44.dp,
    filled: Boolean = false,
) {
    Column(
        Modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = if (badge != null) "$label, $badge" else label
                this.selected = selected
            }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(
                        when {
                            filled -> RT.Accent
                            selected -> RT.Accent
                            else -> RT.Surface2
                        },
                    )
                    .border(1.dp, if (selected || filled) Color.Transparent else RT.Stroke, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.46f))
            }
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = if (done) Color.Black else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (done) RT.Green else RT.Surface3)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.3.sp),
            color = if (selected) RT.Text else RT.TextDim,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Left-side tools while the finish line is being moved. */
@Composable
fun LineTools(
    direction: Direction,
    onDirection: () -> Unit,
    onResetBackground: () -> Unit,
    onCenter: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RailButton(
            Icons.Rounded.SwapHoriz,
            when (direction) {
                Direction.BOTH -> tr("Both ways")
                Direction.FORWARD, Direction.REVERSE -> tr("One way")
            },
            onDirection,
            size = 38.dp,
        )
        RailButton(Icons.Rounded.CenterFocusStrong, tr("Centre"), onCenter, size = 38.dp)
        RailButton(Icons.Rounded.RestartAlt, tr("Reset bg"), onResetBackground, size = 38.dp)
        RailButton(Icons.Rounded.Check, tr("Done"), onDone, size = 38.dp, filled = true)
    }
}

/** Small translucent pill for one-line hints on top of the camera. */
@Composable
fun CameraHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(color = Color.White),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
