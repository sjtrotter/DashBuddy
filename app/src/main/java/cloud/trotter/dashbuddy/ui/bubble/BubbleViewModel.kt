package cloud.trotter.dashbuddy.ui.bubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.designsystem.theme.DRIVING_GLANCE_MULTIPLIER
import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.ui.bubble.cards.FlowCardMapper
import cloud.trotter.dashbuddy.ui.bubble.cards.LiveCardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SessionSummary(
    val sessionId: String,
    val earnings: Double,
    val miles: Double,
    /** Anchors (#367): the UI derives duration — the frozen summary can't drift. */
    val startedAt: Long,
    val endedAt: Long,
)

@HiltViewModel
class BubbleViewModel @Inject constructor(
    private val bubbleManager: BubbleManager,
    private val chatRepository: ChatRepository,
    odometerRepository: OdometerRepository,
    private val stateManager: StateManagerV2,
    appEventRepo: AppEventRepo,
    appPreferencesRepository: AppPreferencesRepository,
) : ViewModel() {

    // Current app state — drives the mode card in the HUD
    val appState = stateManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppState())

    // Driving / glance mode (#318) — the HUD's LocalGlance multiplier, reactive to the
    // Settings toggle so flipping it updates the live HUD without a restart. The main app
    // window (MainActivity) never reads this — only BubbleActivity does.
    val glanceMultiplier = appPreferencesRepository.glanceMode
        .map { enabled -> if (enabled) DRIVING_GLANCE_MULTIPLIER else 1f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    // Which platform the bubble is currently showing
    val focusedPlatform = stateManager.state
        .map { state ->
            state.regions.flow.activePlatform
                ?: state.regions.crossPlatform.mostRecentActivityPlatform
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // The focused platform's region — drives all mode composables
    val focusedRegion = combine(stateManager.state, focusedPlatform) { state, platform ->
        platform?.let { state.regions.platforms[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Session earnings: carries the last known value forward through states
    // that don't have it. Resets when session changes.
    val sessionEarnings = combine(stateManager.state, focusedPlatform) { state, platform ->
        platform?.let { state.regions.platforms[it] }
    }.scan(Pair<String?, Double>(null, 0.0)) { (lastSessionId, lastKnown), region ->
        val session = region?.session
        val sessionId = session?.sessionId
        val isNewSession = sessionId != null && sessionId != lastSessionId && lastSessionId != null
        val earnings = when {
            isNewSession -> 0.0
            session != null -> {
                val running = session.runningEarnings
                if (running > 0) running else lastKnown
            }
            else -> lastKnown
        }
        Pair(sessionId ?: lastSessionId, earnings)
    }
        .map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)


    // Messages scoped to the active dash session.
    //
    // Chat and cards key off the SAME displayed dash id (#367). The fallback
    // when no dash is live is now the DURABLE most-recent dash from the event
    // log (#459), not an in-memory scan latch: the old latch was wiped by
    // process death AND by a post-dash bubble re-subscribe (>5s collapsed),
    // emptying the chat/cards. The active dash wins while one is running; the
    // DB fallback survives both restarts, so the bubble reviews the last dash
    // until the next one starts (when activeSessionId takes over again).
    private val displayedSessionId = combine(
        bubbleManager.activeSessionId,
        appEventRepo.getMostRecentSessionId(),
    ) { active, mostRecent -> active ?: mostRecent }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = displayedSessionId.flatMapLatest { dashId ->
        if (dashId != null) {
            chatRepository.getMessages(dashId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dash whose event log feeds the card stack — the current active dash
    // when one is running, otherwise the most-recently-completed one so the

    /**
     * Bubble HUD flow-card stack (#257). Completed cards are folded from
     * the AppEvent log scoped to [displayedSessionId]; the live card is built
     * from current [appState]. A new dash clears the stack via DASH_START's
     * fold logic, so the user can review the previous dash's cards until
     * they go Online again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cardStack = combine(
        stateManager.state,
        displayedSessionId.flatMapLatest { dashId ->
            if (dashId != null) appEventRepo.getEventsForSession(dashId)
            else flowOf(emptyList())
        },
    ) { state, events ->
        // CardStack.of drops a frozen completed card that duplicates the active
        // card's id — the at-door frozen+live overlap (#458).
        CardStack.of(
            completed = FlowCardMapper.fold(events),
            active = LiveCardBuilder.build(state),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CardStack.Empty)

    // Real-time session miles from GPS odometer
    val sessionMiles = odometerRepository.sessionMilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Snapshot of the last completed dash — populated when session ends,
    // persists through idle.
    val lastSessionSummary = combine(
        stateManager.state, focusedPlatform, odometerRepository.sessionMilesFlow
    ) { state, platform, miles ->
        Triple(state, platform, miles)
    }
        .scan(null as SessionSummary?) { last, (state, platform, miles) ->
            val region = platform?.let { state.regions.platforms[it] }
            val session = region?.session
            // Capture summary when flow is SessionEnded
            if (state.regions.flow.flow == Flow.SessionEnded && session != null &&
                last?.sessionId != session.sessionId
            ) {
                // Captured ONCE per session (#367): the old scan re-stamped a
                // wall-clock duration on every upstream emission, so the
                // "frozen" summary grew while idle.
                SessionSummary(
                    sessionId = session.sessionId,
                    earnings = session.runningEarnings,
                    miles = miles,
                    startedAt = session.startedAt,
                    endedAt = state.timestamp,
                )
            } else {
                last
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- Offer actions (bubble Accept/Decline) ---

    // Signals the bubble to collapse to its head after the user acts on an offer.
    private val _collapse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val collapse = _collapse.asSharedFlow()

    /** User tapped Accept in the bubble → perform the platform accept + collapse. */
    fun acceptOffer() = onOfferAction(OfferIntent.ACCEPT)

    /** User tapped Decline → perform the platform's initial decline + collapse. */
    fun declineOffer() = onOfferAction(OfferIntent.DECLINE)

    private fun onOfferAction(action: String) {
        // #438 item 8a: stamp the acted offer's identity from the state the bubble renders so the
        // dispatched UiInput targets the owning region (an Unknown-platform tap steps no region
        // post-#682). The pending offer's platform is its own provenance, not the focused/global
        // one — they coincide today (offers live on R0), diverge under B3's platform-owned offers.
        val pendingOffer = stateManager.state.value.regions.flow.pendingOffer
        val platform = pendingOffer?.sourceRuleId?.let(Platform::fromRuleId)?.takeIf { it != Platform.Unknown }
            ?: focusedPlatform.value
        stateManager.dispatch(
            Observation.UiInput(
                timestamp = System.currentTimeMillis(),
                action = action,
                targetPlatform = platform,
                offerHash = pendingOffer?.offerHash,
            )
        )
        _collapse.tryEmit(Unit)
    }
}
