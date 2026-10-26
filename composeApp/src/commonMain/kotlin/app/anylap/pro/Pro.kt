package app.anylap.pro

import app.anylap.data.Repository
import app.anylap.data.Session
import app.anylap.data.Sport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the user has paid for. */
enum class Tier {
    FREE,
    PRO,
    ;

    val isPro: Boolean get() = this == PRO
}

/** Things Pro unlocks. Used to tailor the paywall headline to what the user just tried to do. */
enum class ProFeature(val headline: String, val body: String) {
    MORE_RACERS("Race the whole grid", "Free races up to ${Limits.FREE_RACERS} at once. Pro lifts that to the full grid for every sport."),
    HISTORY("Keep every race", "Free keeps your last ${Limits.FREE_HISTORY} sessions. Pro keeps all of them – nothing you already timed is ever deleted."),
    CHARTS("See your progress", "Lap-time trends, consistency and speed charts across every session you've timed."),
    BIBS("Print marker bibs", "Printable bibs with markers the camera reads from metres away – the most reliable way to tell racers apart."),
    CLEAN_SHARE("Share without branding", "Share results without the \"Timed with AnyLap\" line."),
    SYNC("Your races on every phone", "Sign in to back up every session, time on a friend's phone and have the laps land in your own history."),
}

/**
 * The free/pro rules in one place. Pure functions, so they're unit-tested and identical on both platforms.
 * Limits never delete data: on Free, older sessions are hidden and come back when Pro is unlocked.
 */
object Limits {
    const val FREE_RACERS = 4
    const val FREE_HISTORY = 5

    /** Racers allowed in one session. The sport's own cap (what the vision engine is tuned for) always applies. */
    fun maxRacers(sport: Sport, tier: Tier): Int =
        if (tier.isPro) sport.maxRacers else minOf(FREE_RACERS, sport.maxRacers)

    /** Sessions the user can open, newest first. */
    fun visibleSessions(all: List<Session>, tier: Tier): List<Session> {
        val sorted = all.sortedByDescending { it.startedAt }
        return if (tier.isPro) sorted else sorted.take(FREE_HISTORY)
    }

    fun lockedSessionCount(all: List<Session>, tier: Tier): Int = all.size - visibleSessions(all, tier).size

    fun canOpenSession(id: String, all: List<Session>, tier: Tier): Boolean =
        visibleSessions(all, tier).any { it.id == id }

    fun has(feature: ProFeature, tier: Tier): Boolean = tier.isPro
}

/** Store products. Prices here are placeholders shown until the store reports localised prices. */
enum class Plan(val productId: String, val title: String, val placeholderPrice: String, val note: String) {
    YEARLY("anylap_pro_yearly", "Yearly", "€24.99 / year", "7-day free trial · best value"),
    MONTHLY("anylap_pro_monthly", "Monthly", "€3.49 / month", "Cancel anytime"),
    LIFETIME("anylap_pro_lifetime", "Lifetime", "€54.99 once", "Pay once, keep it forever"),
}

sealed interface PurchaseResult {
    data object Success : PurchaseResult
    data object Cancelled : PurchaseResult
    data object StoreUnavailable : PurchaseResult
    data class Failed(val message: String) : PurchaseResult
}

/**
 * Store billing. The app only talks to this interface; a RevenueCat-backed implementation
 * (Play Billing + StoreKit) replaces [LocalBilling] when the store pipeline is set up.
 */
interface Billing {
    val tier: StateFlow<Tier>

    /** False until a real store implementation is wired in. */
    val storeAvailable: Boolean

    /** Localised price for a plan, or null if the store hasn't reported one. */
    fun price(plan: Plan): String?

    suspend fun purchase(plan: Plan): PurchaseResult

    /** @return true if a previous purchase was found. */
    suspend fun restore(): Boolean
}

/**
 * Pre-release billing: no store. Pro can be switched on for testing from Settings,
 * stored in settings.json. Remove the testing switch when the store implementation lands.
 */
class LocalBilling(
    private val repo: Repository,
    /** Pro bought on another phone / granted, as reported by the server for the signed-in account. */
    private val serverPro: StateFlow<Boolean> = MutableStateFlow(false),
) : Billing {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun tierOf(testing: Boolean, server: Boolean) = if (testing || server) Tier.PRO else Tier.FREE

    override val tier: StateFlow<Tier> = combine(repo.settings, serverPro) { s, p -> tierOf(s.testingProUnlocked, p) }
        .stateIn(scope, SharingStarted.Eagerly, tierOf(repo.settings.value.testingProUnlocked, serverPro.value))

    override val storeAvailable: Boolean = false

    override fun price(plan: Plan): String? = null

    override suspend fun purchase(plan: Plan): PurchaseResult = PurchaseResult.StoreUnavailable

    override suspend fun restore(): Boolean = repo.settings.value.testingProUnlocked

    fun setTestingUnlocked(on: Boolean) {
        repo.updateSettings { it.copy(testingProUnlocked = on) }
    }
}
