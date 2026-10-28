package app.anylap.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.anylap.cloud.BackendConfig
import app.anylap.engine.Format
import app.anylap.platform.Platform
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.RtTextField
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.Tag
import app.anylap.ui.pro.currentTier
import app.anylap.ui.theme.RT
import app.anylap.util.Qr
import app.anylap.util.fixed
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.floatOrNull

/** The code in a scanned QR (the join link https://viccom.se/anylap/live/?c=CODE) or a typed code. */
fun liveCodeFrom(text: String): String? {
    val t = text.trim()
    val fromLink = Regex("[?&]c=([A-Za-z0-9]{6})").find(t)?.groupValues?.get(1)
    val code = (fromLink ?: t).uppercase().filter { it.isLetterOrDigit() }
    return code.takeIf { it.length == 6 }
}

/** Join a race on another phone: scan its QR, or type the 6-character code. */
@Composable
fun JoinScreen(nav: Navigator) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun go(raw: String) {
        val c = liveCodeFrom(raw)
        if (c == null) error = "That isn't an AnyLap race code." else nav.replace(Screen.Watch(c))
    }
    ScreenScaffold(title = "Join a race", subtitle = "Follow the timing from the camera phone", onBack = { nav.pop() }) {
        if (Platform.canScanQr) item {
            RtCard(Modifier.fillMaxWidth()) {
                Text("On the camera phone: tap Sync. Then scan the QR it shows.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Scan the QR", { Platform.scanQr { r -> if (r != null) go(r) } }, Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCodeScanner)
            }
        }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                Text(if (Platform.canScanQr) "…or type the code under the QR" else "Type the code the camera phone shows under its QR", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                RtTextField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6); error = null }, "Race code", Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Join", { go(code) }, Modifier.fillMaxWidth())
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = RT.Amber, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * A joined race: the camera phone's timing, about once a second. What shows depends on both phones'
 * plans – the server only sends this phone what it may see (stats need a Pro camera, a Pro viewer
 * and a Pro car). Nothing is kept on this phone.
 */
@Composable
fun WatchScreen(nav: Navigator, code: String) {
    val app = LocalApp.current
    val pro = currentTier().isPro
    var snap by remember { mutableStateOf<JsonObject?>(null) }
    var state by remember { mutableStateOf("Joining…") }
    LaunchedEffect(code, pro) {
        var fails = 0
        while (true) {
            app.cloud.joinLive(code, pro)
                .onSuccess { o ->
                    fails = 0
                    when (o["error"]?.jsonPrimitive?.content) {
                        null -> { snap = o; state = "" }
                        "gone" -> { snap = null; state = "This race has ended, or the code is wrong." }
                        "full" -> { snap = null; state = "This race already has a viewer: the camera phone runs AnyLap Free (one viewer at a time). With Pro on the camera, everyone can follow." }
                        else -> state = "Couldn't join."
                    }
                }
                .onFailure { if (++fails > 2) state = "No connection – trying again…" }
            delay(1_000)
        }
    }
    val s = snap
    ScreenScaffold(
        title = s?.get("title")?.jsonPrimitive?.content ?: "Race $code",
        subtitle = if (s != null) phaseText(s) else "Code $code",
        onBack = { nav.pop() },
    ) {
        if (s == null) {
            item { RtCard(Modifier.fillMaxWidth()) { Text(state, style = MaterialTheme.typography.bodyMedium) } }
            return@ScreenScaffold
        }
        val hostPro = s["hostPro"]?.jsonPrimitive?.booleanOrNull == true
        val viewerPro = s["viewerPro"]?.jsonPrimitive?.booleanOrNull == true
        val rows = s["rows"]?.jsonArray?.map { it.jsonObject }?.sortedBy { it["pos"]?.jsonPrimitive?.intOrNull ?: 99 } ?: emptyList()
        val unit = s["speedUnit"]?.jsonPrimitive?.content ?: "km/h"
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(if (hostPro) "Pro camera" else "Free camera", color = if (hostPro) RT.Gold else RT.TextDim)
                Tag(if (viewerPro) "You: Pro" else "You: Free", color = if (viewerPro) RT.Gold else RT.TextDim)
                Spacer(Modifier.weight(1f))
                Text("${s["viewers"]?.jsonPrimitive?.intOrNull ?: 1} watching", style = MaterialTheme.typography.labelSmall, color = RT.TextDim)
            }
        }
        for (r in rows) item {
            val color = runCatching { Color(("FF" + (r["color"]?.jsonPrimitive?.content ?: "#888888").removePrefix("#")).toLong(16)) }.getOrDefault(RT.TextDim)
            val laps = r["lapTimes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull } ?: emptyList()
            val stats = r["stats"]?.jsonObject
            RtCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r["pos"]?.jsonPrimitive?.intOrNull ?: "–"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text((r["number"]?.jsonPrimitive?.content?.let { "$it " } ?: "") + (r["name"]?.jsonPrimitive?.content ?: ""), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Last ${Format.lap(r["lastMs"]?.jsonPrimitive?.longOrNull)} · best ${Format.lap(r["bestMs"]?.jsonPrimitive?.longOrNull)}",
                            style = MaterialTheme.typography.bodySmall, color = RT.TextDim,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(r["gap"]?.jsonPrimitive?.content ?: "", style = MaterialTheme.typography.titleSmall)
                        Text("${r["laps"]?.jsonPrimitive?.intOrNull ?: 0} laps", style = MaterialTheme.typography.labelSmall, color = RT.TextDim)
                    }
                }
                if (laps.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(laps.withIndex().joinToString("   ") { (i, t) -> "${i + 1}: ${Format.lap(t)}" }, style = MaterialTheme.typography.labelSmall, color = RT.TextDim)
                }
                if (stats != null) {
                    Spacer(Modifier.height(6.dp))
                    val parts = listOfNotNull(
                        stats["avgMs"]?.jsonPrimitive?.longOrNull?.let { "avg ${Format.lap(it)}" },
                        stats["sdMs"]?.jsonPrimitive?.longOrNull?.let { "spread ±${(it / 1000.0).fixed(2)} s" },
                        stats["topSpeed"]?.jsonPrimitive?.floatOrNull?.let { "top ${it.fixed(0)} $unit" },
                    )
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = RT.Gold)
                }
            }
        }
        if (!(hostPro && viewerPro)) item {
            Text(
                when {
                    !hostPro -> "Lap stats like speed and consistency need Pro on the camera phone."
                    else -> "Lap stats like speed and consistency are for Pro – yours and the cars' owners'."
                },
                style = MaterialTheme.typography.bodySmall, color = RT.TextFaint,
            )
        }
    }
}

private fun phaseText(s: JsonObject): String = when (s["phase"]?.jsonPrimitive?.content) {
    "RUNNING" -> "Live"
    "COUNTDOWN" -> "Starting"
    "FINISHED" -> "Finished"
    else -> "Waiting for the start"
}

/** A QR code (dark modules on white, with its quiet zone). */
@Composable
fun QrCodeView(text: String, modifier: Modifier = Modifier) {
    val code = remember(text) { Qr.encode(text) }
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
        val k = size.width / code.size
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (code[x, y]) drawRect(Color.Black, Offset(x * k, y * k), Size(k + 0.5f, k + 0.5f))
        }
    }
}

/** The join link a QR carries: opens the web page, or (scanned in AnyLap) the race in the app. */
fun joinLink(code: String) = BackendConfig.liveUrl(code)
