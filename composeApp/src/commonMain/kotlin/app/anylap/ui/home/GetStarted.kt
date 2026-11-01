package app.anylap.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.anylap.data.CalibrationPlan
import app.anylap.data.Racer
import app.anylap.data.Sport
import app.anylap.i18n.tr
import app.anylap.i18n.trn
import app.anylap.ui.LocalApp
import app.anylap.ui.LocalNav
import app.anylap.ui.Screen
import app.anylap.ui.components.RtCard
import app.anylap.ui.components.PrimaryButton
import app.anylap.ui.theme.RT

/**
 * First-run card on Home: three steps a single person can do alone, each one a tap that goes to the
 * right place. Shown until every car on the list has its laps; after that the hero card takes over.
 */
@Composable
fun GetStartedCard(sport: Sport, racers: List<Racer>) {
    val nav = LocalNav.current
    val app = LocalApp.current
    val ready = racers.filter { it.hasEnoughPasses || it.markerId != null }
    val todo = racers.filter { !it.hasEnoughPasses && it.markerId == null }
    val rc = sport == Sport.RC

    RtCard(Modifier.fillMaxWidth()) {
        Text(tr("GET STARTED"), style = MaterialTheme.typography.labelSmall, color = RT.AccentHot)
        Spacer(Modifier.height(4.dp))
        Text(
            if (rc) tr("Three steps, no helper needed") else tr("Three steps to your first timed laps"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        StepRow(
            n = 1,
            done = false,
            title = tr("Put the phone above the finish line"),
            body = if (rc) tr("On a stand or a shelf, 1–2 m up, looking straight down at a straight. Tape on the ground marks the line.")
            else tr("On a tripod, as high as you can, looking across the finish line."),
            onClick = null,
        )
        StepRow(
            n = 2,
            done = racers.isNotEmpty(),
            title = if (rc) tr("Add your cars") else tr("Add your runners"),
            body = if (racers.isEmpty()) tr("A name and a colour is enough.") else sport.count(racers.size),
            onClick = { nav.tab(Screen.Racers) },
        )
        StepRow(
            n = 3,
            done = racers.isNotEmpty() && todo.isEmpty(),
            title = if (rc) tr("Drive {0} laps with each car", CalibrationPlan.PASSES_TOTAL) else tr("Pass the line {0} times each", CalibrationPlan.PASSES_TOTAL),
            body = when {
                racers.isEmpty() -> tr("The phone counts the laps out loud and learns what each car looks like.")
                todo.isEmpty() -> tr("{0} ready", ready.size)
                else -> trn(todo.size, "{0} car to go – the phone talks you through it.", "{0} cars to go – the phone talks you through it.", todo.size)
            },
            onClick = if (todo.isEmpty()) null else {
                { nav.push(Screen.Calibrate(todo.first().id, todo.drop(1).map { it.id })) }
            },
            highlight = racers.isNotEmpty() && todo.isNotEmpty(),
        )
        if (racers.isNotEmpty() && todo.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                if (rc) tr("Start your first race") else tr("Start your first session"),
                {
                    app.repo.updateSettings { s -> s.copy(lastConfig = s.lastConfig.copy(sport = sport)) }
                    nav.tab(Screen.Race)
                },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepRow(n: Int, done: Boolean, title: String, body: String, onClick: (() -> Unit)?, highlight: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (highlight) RT.AccentSoft else Color.Transparent)
            .let { m -> if (onClick != null) m.clickable(onClick = onClick).semantics { role = Role.Button } else m }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (done) RT.Green else if (highlight) RT.Accent else RT.Surface3),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
            else Text("$n", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (done) RT.TextDim else RT.Text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = RT.TextDim)
        }
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = RT.TextDim)
    }
}
