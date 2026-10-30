package app.anylap.ui.settings

import app.anylap.i18n.tr
import app.anylap.data.FinishEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Flag
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
import app.anylap.BuildInfo
import app.anylap.ui.theme.RT
import app.anylap.pro.Limits
import app.anylap.cloud.Account
import app.anylap.ui.Screen
import app.anylap.ui.pro.ProBadge
import app.anylap.ui.pro.currentTier
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.rounded.WorkspacePremium
import app.anylap.vision.cameraReport

@Composable
fun SettingsScreen(nav: Navigator) {
    val app = LocalApp.current
    val settings by app.repo.settings.collectAsState()
    val tier = currentTier()
    val report = remember { try { cameraReport() } catch (e: Throwable) { tr("Camera information unavailable.") } }

    ScreenScaffold(tr("Settings"), onBack = { nav.pop() }, bottomPadding = 40.dp) {
        item { SectionLabel(tr("AnyLap Pro"), Icons.Rounded.WorkspacePremium) }
        item {
            RtCard(Modifier.fillMaxWidth(), onClick = { nav.push(Screen.Paywall()) }, highlight = if (tier.isPro) RT.Gold.copy(alpha = 0.4f) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (tier.isPro) tr("Pro is active") else tr("Free plan"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (tier.isPro) tr("Unlimited history, full grid, charts and bibs.")
                            else tr("{0} racers per session · last {1} sessions. Tap to see Pro.", Limits.FREE_RACERS, Limits.FREE_HISTORY),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ProBadge()
                }
            }
        }
        item {
            val account by app.cloud.account.collectAsState()
            RtCard(Modifier.fillMaxWidth(), onClick = { nav.push(Screen.Account) }) {
                Text(tr("Account & sync"), style = MaterialTheme.typography.titleSmall)
                Text(
                    when (val a = account) {
                        is Account.WithEmail -> tr("Signed in as {0}", a.email)
                        is Account.Guest -> tr("Syncing as guest – add your email")
                        else -> tr("Off – everything stays on this phone")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { SectionLabel(tr("Preferences"), Icons.Rounded.Tune) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text(tr("Speed unit"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                PillTabs(
                    listOf("km/h", "mph"),
                    if (settings.speedUnitKmh) 0 else 1,
                    { i -> app.repo.updateSettings { it.copy(speedUnitKmh = i == 0) } },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Announce lap times"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.announceLaps,
                        onCheckedChange = { v -> app.repo.updateSettings { it.copy(announceLaps = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = RT.Accent),
                    )
                }
            }
        }

        item { SectionLabel(tr("Timing"), Icons.Rounded.Flag) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text(tr("A car finishes when"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val edges = listOf(FinishEdge.FRONT, FinishEdge.REAR)
                PillTabs(
                    listOf(tr("Front touches"), tr("Whole car passed")),
                    edges.indexOf(settings.finishEdge).coerceAtLeast(0),
                    { i -> app.repo.updateSettings { it.copy(finishEdge = edges[i]) } },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(settings.finishEdge.detail + tr(" Used for every lap of a session, so lap times stay comparable either way."), style = MaterialTheme.typography.bodySmall)
            }
        }

        item { SectionLabel(tr("Sports"), Icons.Rounded.Flag) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Running and other sports"), style = MaterialTheme.typography.titleSmall)
                        Text(tr("Off: AnyLap is an RC lap timer. On: pick the sport for each session, racers and stats."), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = settings.otherSports,
                        onCheckedChange = { v -> app.repo.updateSettings { it.copy(otherSports = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = RT.Accent),
                    )
                }
            }
        }

        item { SectionLabel(tr("Car recognition"), Icons.Rounded.AutoAwesome) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Keep learning my cars"), style = MaterialTheme.typography.titleSmall)
                        Text(tr("Crossings the camera, the lap timing and you agreed on become extra views of each car, on this phone. See them under the car's calibration."), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = settings.learnCars,
                        onCheckedChange = { v -> app.repo.updateSettings { it.copy(learnCars = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = RT.Accent),
                    )
                }
                Spacer(Modifier.height(12.dp))
                val pending by app.anylap.learn.Crops.pending.collectAsState()
                val uploaded by app.anylap.learn.Crops.uploaded.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Help improve car recognition"), style = MaterialTheme.typography.titleSmall)
                        Text(tr("Shares small pictures of the cars at the line, with which car it was, to train the recognition model of future versions – so it also works for cars nobody has calibrated yet. No names, no video, no location; shared under your account (a guest account is created if you have none)."), style = MaterialTheme.typography.bodySmall)
                        if (uploaded > 0 || pending > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    if (uploaded > 0) tr("{0} shared", uploaded) else null,
                                    if (pending > 0) tr("{0} waiting", pending) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = RT.TextDim,
                            )
                        }
                    }
                    Switch(
                        checked = settings.shareCrops,
                        onCheckedChange = { v ->
                            app.repo.updateSettings { it.copy(shareCrops = v, cropsNoticeSeen = true) }
                            if (v) app.anylap.learn.Crops.flushSoon(app.cloud) else app.anylap.learn.Crops.clear()
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = RT.Accent),
                    )
                }
            }
        }

        item { SectionLabel(tr("Camera"), Icons.Rounded.CameraAlt) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text(report.lines().take(8).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                SecondaryButton(tr("Share full camera report"), {
                    Platform.shareText(report, tr("Camera report"))
                }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Share)
            }
        }

        item { SectionLabel(tr("About"), Icons.Rounded.Info) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text("AnyLap ${BuildInfo.label} · ${Platform.name}", style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Camera lap timing for RC cars and runners. Video is processed on this phone and never uploaded. Lap data leaves the phone only if you turn on sync. ") +
                        tr("Timing is for practice and fun, not official results."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(tr("by VICCOM · viccom.se"), style = MaterialTheme.typography.bodySmall, color = RT.TextFaint)
            }
        }
    }
}
