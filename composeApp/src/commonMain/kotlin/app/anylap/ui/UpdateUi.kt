package app.anylap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.anylap.platform.PlatformBackHandler
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.StripeCard
import app.anylap.ui.theme.RT
import app.anylap.update.Updates

/** Home: "New version N" nudge (dismissible, once per version). */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val s by Updates.state.collectAsState()
    val offer = s.nudge ?: return
    val uri = LocalUriHandler.current
    val t = Updates::t
    StripeCard(RT.Green, modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = if (offer is Updates.Offer.TestApk) t("Nytt testbygge ${offer.build}", "New test build ${offer.build}")
            else t("Ny version av AnyLap", "New version of AnyLap")
            Text(title, style = MaterialTheme.typography.titleMedium)
            val body = s.message ?: when (offer) {
                is Updates.Offer.TestApk -> (if (offer.notes.isNotBlank()) offer.notes + " · " else "") +
                    t("Installeras ovanpå den här – dina bilar och lopp finns kvar.", "Installs over this one – your cars and races stay.")
                is Updates.Offer.Store -> t("Uppdatera för de senaste förbättringarna.", "Update for the latest improvements.")
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = RT.TextDim)
            Progress(s)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(t("Uppdatera", "Update"), { Updates.install { uri.openUri(it) } }, enabled = s.progress == null, color = RT.Green)
                SecondaryButton(t("Senare", "Later"), { Updates.dismiss() })
            }
        }
    }
}

/** Blocking screen when this build is below the minimum (never during a race or calibration). */
@Composable
fun UpdateGate() {
    val s by Updates.state.collectAsState()
    val held by Updates.held.collectAsState()
    if (!s.blocking || held > 0 || s.offer == null) return
    val uri = LocalUriHandler.current
    val t = Updates::t
    PlatformBackHandler(enabled = true) {}
    Box(
        Modifier.fillMaxSize().background(RT.Bg)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(t("Uppdatering krävs", "Update required"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                s.message ?: t(
                    "Den här versionen av AnyLap fungerar inte längre med servern. Uppdatera för att fortsätta – dina bilar och lopp finns kvar.",
                    "This version of AnyLap no longer works with the server. Update to carry on – your cars and races stay.",
                ),
                style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, textAlign = TextAlign.Center,
            )
            Progress(s)
            Spacer(Modifier.height(4.dp))
            PrimaryButton(t("Uppdatera", "Update"), { Updates.install { uri.openUri(it) } }, Modifier.fillMaxWidth(), enabled = s.progress == null)
        }
    }
}

@Composable
private fun Progress(s: Updates.State) {
    s.progress?.let { p ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = RT.Green)
            Text(Updates.t("Laddar ner … ${(p * 100).toInt()} %", "Downloading … ${(p * 100).toInt()} %"), style = MaterialTheme.typography.labelMedium, color = RT.TextDim)
        }
    }
    s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RT.Accent) }
}
