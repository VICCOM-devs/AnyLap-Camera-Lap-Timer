package app.anylap.ui.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anylap.pro.ProFeature
import app.anylap.pro.Tier
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.theme.RT

/** Current tier, recomposes when it changes. */
@Composable
fun currentTier(): Tier = LocalApp.current.tier.collectAsState().value

/** Small gold "PRO" pill. */
@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(RT.Gold.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WorkspacePremium, null, tint = RT.Gold, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text("PRO", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold), color = RT.Gold)
    }
}

/** Placeholder card where a Pro-only section would be. Taps open the paywall. */
@Composable
fun ProLockCard(
    nav: Navigator,
    feature: ProFeature,
    modifier: Modifier = Modifier,
    title: String = feature.headline,
    body: String = feature.body,
) {
    RtCard(modifier.fillMaxWidth(), onClick = { nav.push(Screen.Paywall(feature)) }, highlight = RT.Gold.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(RT.Gold.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Lock, null, tint = RT.Gold, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    ProBadge()
                }
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("See AnyLap Pro", { nav.push(Screen.Paywall(feature)) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.WorkspacePremium)
    }
}
