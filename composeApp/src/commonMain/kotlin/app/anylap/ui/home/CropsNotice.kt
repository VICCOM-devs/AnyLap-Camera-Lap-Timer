package app.anylap.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.anylap.i18n.tr
import app.anylap.learn.Crops
import app.anylap.ui.LocalApp
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.StripeCard
import app.anylap.ui.theme.RT

/**
 * Home: a one-time notice that the app shares small finish-line pictures to improve car recognition
 * (Settings → Car recognition). Shown once there is a race to share from; nothing is uploaded before
 * it has been answered. Either answer records that the notice was seen.
 */
@Composable
fun CropsNotice(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val settings by app.repo.settings.collectAsState()
    val sessions by app.repo.sessions.collectAsState()
    if (settings.cropsNoticeSeen || !settings.shareCrops || sessions.isEmpty()) return
    StripeCard(RT.Accent, modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Help make car recognition better for everyone?"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("AnyLap can share small pictures of your cars at the finish line, with which car it was, to train the recognition model of future versions – so it works for cars nobody has calibrated yet. No names, no video, no location. You can change this any time in Settings → Car recognition."),
                style = MaterialTheme.typography.bodySmall, color = RT.TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(tr("Yes, share"), {
                    app.repo.updateSettings { it.copy(shareCrops = true, cropsNoticeSeen = true) }
                    Crops.flushSoon(app.cloud)
                })
                SecondaryButton(tr("No thanks"), {
                    app.repo.updateSettings { it.copy(shareCrops = false, cropsNoticeSeen = true) }
                    Crops.clear()
                })
            }
        }
    }
}
