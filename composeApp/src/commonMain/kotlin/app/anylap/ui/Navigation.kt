package app.anylap.ui

import app.anylap.ui.LocalApp
import app.anylap.ui.Navigator
import app.anylap.ui.Screen
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import app.anylap.data.Repository
import app.anylap.data.SessionConfig
import app.anylap.pro.Billing
import app.anylap.cloud.Cloud
import app.anylap.pro.LocalBilling
import app.anylap.pro.ProFeature
import app.anylap.pro.Tier
import kotlinx.coroutines.flow.StateFlow

sealed interface Screen {
    data object Home : Screen
    data object Race : Screen
    data object Stats : Screen
    data object Racers : Screen
    data object Settings : Screen
    data object Live : Screen
    data class RacerEdit(val id: String) : Screen
    data class Calibrate(val id: String) : Screen
    data class Results(val id: String) : Screen
    data class Replay(val sessionId: String, val racerId: String, val lap: Int) : Screen
    data class Paywall(val feature: ProFeature? = null) : Screen
    data object Account : Screen
    /** Follow a race on another phone: scan / type its code. */
    data object Join : Screen
    data class Watch(val code: String) : Screen
    data object Events : Screen
    data class EventDetail(val id: String) : Screen

    val isTab: Boolean get() = this == Home || this == Race || this == Stats || this == Racers
}

/** Tiny stack navigator: identical on Android and iOS, no extra library. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(s: Screen) {
        stack.add(s)
    }

    /** @return false if already at the root. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Switch bottom-bar tab: Home stays at the root. */
    fun tab(s: Screen) {
        stack.clear()
        stack.add(Screen.Home)
        if (s != Screen.Home) stack.add(s)
    }

    /** Replace the current screen (e.g. Live → Results). */
    fun replace(s: Screen) {
        stack[stack.lastIndex] = s
    }
}

/** App-wide state shared by all screens. */
class AppState {
    val repo = Repository()
    val cloud = Cloud(repo)
    val billing: Billing = LocalBilling(repo, cloud.serverPro)
    val tier: StateFlow<Tier> get() = billing.tier

    init {
        cloud.isPro = { billing.tier.value.isPro }
    }

    /** Hand-off from the setup screen to the live screen. */
    var pendingConfig: SessionConfig? = null

    /** Set when the live session being started is a round of an event: (eventId, roundId). */
    var pendingRound: Pair<String, String>? = null

    /** A join link opened from outside the app (https://viccom.se/anylap/live/?c=CODE). */
    val pendingJoin = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

val LocalApp = staticCompositionLocalOf<AppState> { error("No AppState") }
val LocalNav = staticCompositionLocalOf<Navigator> { error("No Navigator") }
