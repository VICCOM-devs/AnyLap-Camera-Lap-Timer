package app.anylap.ui.racers

import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.anylap.bib.BibPdf
import app.anylap.data.Racer
import app.anylap.data.Sport
import app.anylap.ui.components.EmptyState
import app.anylap.ui.components.PillTabs
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RacerAvatar
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.StripeCard
import app.anylap.ui.components.uiColor
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.Tag
import app.anylap.ui.theme.RT
import app.anylap.pro.Limits
import app.anylap.pro.ProFeature
import app.anylap.ui.pro.currentTier

@Composable
fun RacersScreen(nav: Navigator) {
    val app = LocalApp.current
    val racers by app.repo.racers.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sport = if (tab == 0) Sport.RC else Sport.RUNNING
    val list = racers.filter { it.sport == sport }.sortedBy { it.number }
    val tier = currentTier()

    ScreenScaffold(title = "Racers", subtitle = "Profiles, calibration and bibs") {
        item { PillTabs(listOf("RC cars", "Runners"), tab, { tab = it }, Modifier.fillMaxWidth()) }
        item {
            SectionLabel(
                if (sport == Sport.RC) "Your cars" else "Your runners",
                trailing = { Text("${list.size} saved · ${Limits.maxRacers(sport, tier)} per race", style = MaterialTheme.typography.bodySmall) },
            )
        }
        if (list.isEmpty()) {
            item {
                EmptyState(
                    Icons.Rounded.Groups,
                    if (sport == Sport.RC) "Add your first car" else "Add your first runner",
                    if (sport == Sport.RC) "Give it a name, snap a photo and do a few calibration passes so the camera learns what it looks like."
                    else "Each runner gets a printable bib with a marker the camera can read from metres away.",
                )
            }
        }
        items(list, key = { it.id }) { r -> RacerRow(r) { nav.push(Screen.RacerEdit(r.id)) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    if (sport == Sport.RC) "Add car" else "Add runner",
                    onClick = {
                        val r = app.repo.newRacer(sport)
                        app.repo.upsertRacer(r)
                        nav.push(Screen.RacerEdit(r.id))
                    },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Add,
                )
                if (sport == Sport.RUNNING && list.isNotEmpty()) {
                    SecondaryButton(if (tier.isPro) "All bibs" else "Bibs · Pro", onClick = {
                        if (tier.isPro) BibPdf.share(list) else nav.push(Screen.Paywall(ProFeature.BIBS))
                    }, icon = Icons.Rounded.Print)
                }
            }
        }
    }
}

@Composable
private fun RacerRow(r: Racer, onClick: () -> Unit) {
    StripeCard(r.uiColor, Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RacerAvatar(r, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag("#${r.number}", RT.TextDim)
                    when {
                        r.isCalibrated -> Tag("Calibrated · ${r.fingerprintSamples}", RT.Green)
                        r.sport == Sport.RC -> Tag("Needs calibration", RT.Amber)
                        else -> {}
                    }
                    if (r.markerId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.QrCode2, null, tint = RT.TextDim, modifier = Modifier.size(14.dp))
                            Text(" ${r.markerId}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = RT.TextFaint)
        }
    }
}
