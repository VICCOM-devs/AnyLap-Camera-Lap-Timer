package app.anylap.ui.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.anylap.pro.Limits
import app.anylap.pro.LocalBilling
import app.anylap.pro.Plan
import app.anylap.pro.ProFeature
import app.anylap.pro.PurchaseResult
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.theme.RT
import kotlinx.coroutines.launch

private data class Row3(val label: String, val free: String?, val pro: String)

private val comparison = listOf(
    Row3("Lap timing, speed, live leaderboard", "✓", "✓"),
    Row3("Voice announcements & slow-mo replays", "✓", "✓"),
    Row3("Racers per session", "${Limits.FREE_RACERS}", "Full grid"),
    Row3("Saved sessions", "Last ${Limits.FREE_HISTORY}", "Unlimited"),
    Row3("Trend, consistency & speed charts", null, "✓"),
    Row3("Printable marker bibs", null, "✓"),
    Row3("Share without branding", null, "✓"),
    Row3("Accounts, cloud sync & events", null, "Coming"),
)

@Composable
fun PaywallScreen(nav: Navigator, feature: ProFeature?) {
    val app = LocalApp.current
    val billing = app.billing
    val tier = currentTier()
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf(Plan.YEARLY) }
    var message by remember { mutableStateOf<String?>(null) }

    ScreenScaffold("AnyLap Pro", onBack = { nav.pop() }, bottomPadding = 40.dp) {
        item {
            val shape = RoundedCornerShape(26.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape)
                    .background(Brush.linearGradient(listOf(Color(0xFF4A3A0A), Color(0xFF1E160A), RT.Surface)))
                    .border(1.dp, RT.Gold.copy(alpha = 0.4f), shape)
                    .padding(20.dp),
            ) {
                ProBadge()
                Spacer(Modifier.height(10.dp))
                Text(
                    if (tier.isPro) "You're on Pro" else (feature?.headline ?: "Time like a pro"),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (tier.isPro) "Everything is unlocked. Thanks for supporting AnyLap."
                    else feature?.body ?: "Unlimited history, the full grid, charts and printable bibs – for every sport.",
                    style = MaterialTheme.typography.bodyMedium, color = RT.TextDim,
                )
            }
        }

        item { SectionLabel("Free vs Pro") }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Spacer(Modifier.weight(1f))
                    Text("Free", style = MaterialTheme.typography.labelMedium, color = RT.TextDim, modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
                    Text("Pro", style = MaterialTheme.typography.labelMedium, color = RT.Gold, modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
                }
                comparison.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Cell(r.free, RT.TextDim)
                        Cell(r.pro, RT.Gold)
                    }
                }
            }
        }

        if (!tier.isPro) {
            item { SectionLabel("Choose a plan") }
            Plan.entries.forEach { p ->
                item(key = p.name) {
                    PlanCard(p, billing.price(p) ?: p.placeholderPrice, p == plan) { plan = p }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PrimaryButton(
                        when {
                            !billing.storeAvailable -> "Store opens at launch"
                            plan == Plan.YEARLY -> "Start free trial"
                            else -> "Continue"
                        },
                        onClick = {
                            scope.launch {
                                message = when (val r = billing.purchase(plan)) {
                                    PurchaseResult.Success -> null
                                    PurchaseResult.Cancelled -> null
                                    PurchaseResult.StoreUnavailable -> "Purchases open when AnyLap is in the stores."
                                    is PurchaseResult.Failed -> r.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.WorkspacePremium,
                        enabled = billing.storeAvailable,
                    )
                    TextButton(onClick = {
                        scope.launch { message = if (billing.restore()) "Purchase restored." else "No previous purchase found." }
                    }) { Text("Restore purchases", color = RT.TextDim) }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RT.Amber, textAlign = TextAlign.Center) }
                    Text(
                        "Subscriptions renew automatically until cancelled in your store account. Nothing you've timed is ever deleted if Pro ends – older sessions are just hidden again.",
                        style = MaterialTheme.typography.bodySmall, color = RT.TextFaint, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        if (billing is LocalBilling) {
            item { SectionLabel("Pre-release testing") }
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Unlock Pro for testing", style = MaterialTheme.typography.titleSmall)
                            Text("Testers only. Goes away when store purchases are live.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = tier.isPro,
                            onCheckedChange = { billing.setTestingUnlocked(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = RT.Gold),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(value: String?, color: Color) {
    Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
        when (value) {
            null -> Icon(Icons.Rounded.Remove, "Not included", tint = RT.TextFaint, modifier = Modifier.size(16.dp))
            "✓" -> Icon(Icons.Rounded.Check, "Included", tint = color, modifier = Modifier.size(18.dp))
            else -> Text(value, style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, price: String, selected: Boolean, onClick: () -> Unit) {
    RtCard(Modifier.fillMaxWidth(), onClick = onClick, highlight = if (selected) RT.Gold else null, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .border(2.dp, if (selected) RT.Gold else RT.StrokeHigh, CircleShape)
                    .background(if (selected) RT.Gold.copy(alpha = 0.2f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(RT.Gold))
            }
            Column(Modifier.weight(1f)) {
                Text(plan.title, style = MaterialTheme.typography.titleMedium)
                Text(plan.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(price, style = MaterialTheme.typography.titleSmall, color = if (selected) RT.Gold else RT.Text)
        }
    }
}
