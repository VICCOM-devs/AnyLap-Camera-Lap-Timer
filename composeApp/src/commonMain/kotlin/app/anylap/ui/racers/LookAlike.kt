package app.anylap.ui.racers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.anylap.data.Racer
import app.anylap.engine.RaceEngine
import app.anylap.i18n.tr
import app.anylap.ui.components.StripeCard
import app.anylap.ui.theme.RT

/**
 * Fail-safe 4: after calibration, cars the camera can hardly tell apart (calibration ≥ 88 % alike –
 * same body and paint, only the number differs) get a warning with the fix: a strip of bright tape on
 * one of them. Lap timing sorts most of their laps out, but two look-alikes at the same pace can end
 * up with their names swapped for a whole race; the tape prevents that at the source.
 */
@Composable
fun LookAlikeWarning(racers: List<Racer>, modifier: Modifier = Modifier) {
    val pairs = remember(racers) { RaceEngine.lookAlikes(racers) }
    if (pairs.isEmpty()) return
    StripeCard(RT.Amber, modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, tint = RT.Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Look-alikes"), style = MaterialTheme.typography.titleSmall, color = RT.Amber)
            }
            Spacer(Modifier.height(4.dp))
            for ((a, b, sim) in pairs.take(4)) {
                Text(tr("{0} and {1} look the same to the camera ({2} % alike).", a.name, b.name, (sim * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Put a strip of bright tape (pink, orange, yellow, green, cyan or blue – a different colour on each car) along the roof or side of one of them, then re-run its calibration passes. Otherwise the two may get each other's laps."),
                style = MaterialTheme.typography.bodySmall, color = RT.TextDim,
            )
        }
    }
}
