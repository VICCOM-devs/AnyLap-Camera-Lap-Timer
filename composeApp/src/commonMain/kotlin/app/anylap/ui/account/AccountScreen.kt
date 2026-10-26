package app.anylap.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.anylap.cloud.Account
import app.anylap.platform.Platform
import app.anylap.pro.ProFeature
import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.RtTextField
import app.anylap.ui.components.ScreenScaffold
import app.anylap.ui.components.SecondaryButton
import app.anylap.ui.components.SectionLabel
import app.anylap.ui.components.Tag
import app.anylap.ui.pro.ProLockCard
import app.anylap.ui.pro.currentTier
import app.anylap.ui.theme.RT
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(nav: Navigator) {
    val app = LocalApp.current
    val cloud = app.cloud
    val tier = currentTier()
    val account by cloud.account.collectAsState()
    val status by cloud.status.collectAsState()
    val savedName by cloud.displayName.collectAsState()
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(savedName) { if (name.isEmpty()) name = savedName }

    fun run(block: suspend () -> Result<*>, ok: String? = null) {
        scope.launch {
            busy = true
            message = null
            val r = block()
            busy = false
            message = r.exceptionOrNull()?.let { friendlyAuthError(it) } ?: ok
        }
    }

    ScreenScaffold("Account & sync", onBack = { nav.pop() }, bottomPadding = 40.dp) {
        if (!tier.isPro) {
            item { ProLockCard(nav, ProFeature.SYNC) }
        }

        item { SectionLabel("Account", Icons.Rounded.Person) }
        item {
            RtCard(Modifier.fillMaxWidth()) {
                when (val a = account) {
                    Account.Loading -> Text("Connecting…", style = MaterialTheme.typography.bodyMedium, color = RT.TextDim)
                    Account.SignedOut -> {
                        Text("Sync is off", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Everything stays on this phone. Turn on sync to back up your racers and sessions and use them on other phones.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(
                            "Turn on sync",
                            { run({ cloud.startGuest() }, "Sync is on.") },
                            Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.CloudSync,
                            enabled = tier.isPro && !busy,
                        )
                    }
                    is Account.Guest -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Syncing as guest", style = MaterialTheme.typography.titleMedium)
                            Tag("This phone only", RT.Amber)
                        }
                        Text(
                            "Add your email to sign in on other phones – and to get everything back if you lose this one.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is Account.WithEmail -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(a.email, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            Tag("Signed in", RT.Green)
                        }
                        Text("Sign in with this email on any phone to get your racers and history.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Email: add to a guest account, or sign in (also from signed-out state on a new phone).
        if (account is Account.Guest || account is Account.SignedOut) {
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (account is Account.Guest) "Add email" else "Already have an account?",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    RtTextField(email, { email = it }, "Email", keyboardType = KeyboardType.Email, enabled = !codeSent)
                    Spacer(Modifier.height(10.dp))
                    if (!codeSent) {
                        SecondaryButton("Send code", {
                            if (!email.contains('@')) { message = "Enter a valid email."; return@SecondaryButton }
                            scope.launch {
                                busy = true
                                val r = cloud.requestEmailCode(email)
                                busy = false
                                r.onSuccess { linking = it; codeSent = true; message = "We sent a 6-digit code to $email." }
                                    .onFailure { message = friendlyAuthError(it) }
                            }
                        }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Email)
                    } else {
                        RtTextField(code, { code = it.filter(Char::isDigit).take(8) }, "Code", keyboardType = KeyboardType.Number)
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton("Verify", {
                            run({ cloud.verifyEmailCode(email, code, linking).onSuccess { codeSent = false; code = "" } }, "Email confirmed.")
                        }, Modifier.fillMaxWidth(), enabled = code.length >= 6 && !busy)
                        TextButton(onClick = { codeSent = false; code = "" }) { Text("Use another email", color = RT.TextDim) }
                    }
                }
            }
        }

        if (account.userId != null) {
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    Text("Display name", style = MaterialTheme.typography.titleSmall)
                    Text("Shown to friends when you share a racer or time their laps.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    RtTextField(name, { name = it.take(40) }, "Name")
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Save name", { run({ cloud.setDisplayName(name) }, "Saved.") }, Modifier.fillMaxWidth())
                }
            }

            item { SectionLabel("Sync", Icons.Rounded.CloudDone) }
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            status.running -> "Syncing…"
                            status.lastSyncAt != null -> "Last synced " + Platform.formatDate(status.lastSyncAt!!, "d MMM · HH:mm")
                            else -> "Not synced yet"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RT.Amber) }
                    Text(
                        "Racers and lap data sync automatically. Videos stay on the phone that recorded them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Sync now", { cloud.syncSoon() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Sync)
                }
            }

            item { SectionLabel("Your data") }
            item {
                RtCard(Modifier.fillMaxWidth()) {
                    SecondaryButton("Export my data", {
                        scope.launch {
                            cloud.exportData().onSuccess { Platform.shareFile(it, "application/json", "AnyLap data export") }
                                .onFailure { message = friendlyAuthError(it) }
                        }
                    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Sign out", { run({ cloud.signOut() }, "Signed out. Your data is still on this phone.") }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Logout)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete my cloud data", color = RT.Accent)
                    }
                }
            }
        }

        message?.let { m ->
            item { Text(m, style = MaterialTheme.typography.bodyMedium, color = RT.TextDim, modifier = Modifier.fillMaxWidth()) }
        }
        item {
            Column {
                Text(
                    "Stored in the EU (Stockholm). We keep your racers' names, numbers, colours and appearance data, and your lap times – no video, no location, no contacts.",
                    style = MaterialTheme.typography.bodySmall, color = RT.TextFaint,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = RT.Surface2,
            icon = { androidx.compose.material3.Icon(Icons.Rounded.Delete, null, tint = RT.Accent) },
            title = { Text("Delete cloud data?") },
            text = {
                Text(
                    "Removes your racers, sessions and profile from AnyLap's servers and signs you out. Everything on this phone stays.",
                    color = RT.TextDim,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    run({ cloud.deleteCloudData() }, "Your cloud data is deleted.")
                }) { Text("Delete", color = RT.Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = RT.Text) } },
        )
    }
}

internal fun friendlyAuthError(e: Throwable): String {
    val m = e.message.orEmpty().lowercase()
    return when {
        "otp" in m && ("expired" in m || "invalid" in m) -> "That code is wrong or has expired. Send a new one."
        "rate limit" in m || "too many" in m -> "Too many attempts. Wait a minute and try again."
        "anonymous" in m && "disabled" in m -> "Guest sign-in is turned off on the server."
        "not found or expired" in m -> "That share code is wrong or has expired."
        "unable to resolve" in m || "timeout" in m || "failed to connect" in m -> "You're offline. Try again when you have signal."
        else -> "Something went wrong. Try again."
    }
}
