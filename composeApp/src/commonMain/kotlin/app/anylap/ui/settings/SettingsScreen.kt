package app.anylap.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.anylap.platform.Platform
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.components.PillTabs
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.theme.RT
import app.anylap.vision.cameraReport

@Composable
fun SettingsScreen(nav: Navigator) {
    val app = LocalApp.current
    val settings by app.repo.settings.collectAsState()
    val report = remember { try { cameraReport() } catch (e: Throwable) { "Camera information unavailable." } }

    ScreenScaffold("Settings", onBack = { nav.pop() }, bottomPadding = 40.dp) {
        item { SectionLabel("Preferences", Icons.Rounded.Tune) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text("Speed unit", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                PillTabs(
                    listOf("km/h", "mph"),
                    if (settings.speedUnitKmh) 0 else 1,
                    { i -> app.repo.updateSettings { it.copy(speedUnitKmh = i == 0) } },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Announce lap times", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.announceLaps,
                        onCheckedChange = { v -> app.repo.updateSettings { it.copy(announceLaps = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = RT.Accent),
                    )
                }
            }
        }

        item { SectionLabel("Camera", Icons.Rounded.CameraAlt) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text(report.lines().take(8).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Share full camera report", {
                    Platform.shareText(report, "Camera report")
                }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Share)
            }
        }

        item { SectionLabel("About", Icons.Rounded.Info) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text("AnyLap · ${Platform.name}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Camera lap timing for RC cars and runners. Everything is processed on this phone – no account, no uploads. " +
                        "Timing is for practice and fun, not official results.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("by VICCOM · viccom.se", style = MaterialTheme.typography.bodySmall, color = RT.TextFaint)
            }
        }
    }
}
