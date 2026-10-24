package app.anylap.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.engine.Format
import app.anylap.platform.Platform
import app.anylap.platform.VideoPlayer
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.components.CameraScreenMode
import app.anylap.ui.components.CircleIconButton
import app.anylap.ui.components.PillTabs
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.theme.RT
import kotlin.math.abs

private val SPEEDS = listOf(0.25f, 0.5f, 1f)

@Composable
fun ReplayScreen(nav: Navigator, sessionId: String, racerId: String, lapNumber: Int) {
    CameraScreenMode()
    val app = LocalApp.current
    val sessions by app.repo.sessions.collectAsState()
    val s = sessions.firstOrNull { it.id == sessionId }
    val racer = s?.racer(racerId)
    val lap = s?.lapsOf(racerId)?.firstOrNull { it.number == lapNumber }
    val path = s?.videoPath
    if (s == null || racer == null || lap == null || path == null || !Platform.exists(path)) {
        Box(Modifier.fillMaxSize().background(RT.Bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Replay unavailable.", color = RT.TextDim)
                androidx.compose.material3.TextButton(onClick = { nav.pop() }) { Text("Back", color = RT.Accent) }
            }
        }
        return
    }

    var speedIdx by remember { mutableIntStateOf(0) }
    var wholeLap by remember { mutableIntStateOf(0) } // 0 = finish moment, 1 = whole lap
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var seekTo by remember { mutableStateOf<Long?>(null) }

    // Window in the video around this lap.
    val crossMs = s.videoOffsetMs + lap.atMs
    val startMs = if (wholeLap == 1) (crossMs - lap.timeMs - 800).coerceAtLeast(0) else (crossMs - 1800).coerceAtLeast(0)
    val endMs = crossMs + 1200

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            VideoPlayer(
                path = path,
                loopStartMs = startMs,
                loopEndMs = endMs,
                speed = SPEEDS[speedIdx],
                playing = playing,
                seekToMs = seekTo,
                onPosition = { positionMs = it },
                modifier = Modifier.fillMaxSize(),
            )
            // Time relative to the crossing: 0.000 = on the line.
            val rel = positionMs - crossMs
            Text(
                (if (rel >= 0) "+" else "") + Format.lap(rel),
                color = if (abs(rel) < 40) RT.Accent else Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(
            Modifier.width(300.dp).fillMaxHeight().background(RT.Bg).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { nav.pop() })
                Spacer(Modifier.width(12.dp))
                Text("Slow-mo replay", style = MaterialTheme.typography.titleLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RacerAvatar(racer, 44.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(racer.name, style = MaterialTheme.typography.titleMedium)
                    Text("Lap ${lap.number} · ${Format.lap(lap.timeMs)}", style = MaterialTheme.typography.bodySmall)
                    lap.speedKmh?.let {
                        Text(Format.speed(it, app.repo.settings.value.speedUnitKmh), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            PillTabs(listOf("Finish", "Whole lap"), wholeLap, { wholeLap = it; seekTo = null }, Modifier.fillMaxWidth())
            PillTabs(listOf("0.25x", "0.5x", "1x"), speedIdx, { speedIdx = it }, Modifier.fillMaxWidth())
            Slider(
                value = ((positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f),
                onValueChange = { f ->
                    playing = false
                    seekTo = (startMs + f * (endMs - startMs)).toLong()
                },
                colors = SliderDefaults.colors(thumbColor = RT.Accent, activeTrackColor = RT.Accent, inactiveTrackColor = RT.Surface3),
            )
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(RT.Accent)
                    .clickable { playing = !playing; seekTo = null },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "Pause" else "Play", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
