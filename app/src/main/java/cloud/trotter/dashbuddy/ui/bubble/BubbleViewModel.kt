package cloud.trotter.dashbuddy.ui.bubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.designsystem.theme.DRIVING_GLANCE_MULTIPLIER
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.feature.bubble.cards.FlowCardMapper
import cloud.trotter.dashbuddy.feature.bubble.cards.LiveCardBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BubbleViewModel @Inject constructor(
    private val bubbleManager: BubbleManager,
    private val chatRepository: ChatRepository,
    odometerRepository: OdometerRepository,
    private val stateManager: StateManagerV2,
    appEventRepo: AppEventRepo,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val fuelPriceRepository: FuelPriceRepository,
    analyticsRepository: AnalyticsRepository,
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

    // The most-recent dash, straight off the analytics read-model (#693). This REPLACED the old
    // in-memory `SessionSummary` scan whose liveness-gated capture (SharingStarted.WhileSubscribed +
    // the transient Flow.SessionEnded frame) silently missed any dash that ended while the bubble was
    // collapsed — the post-dash HUD then showed the last dash it happened to catch (the stale
    // "last dash with money"), not the true last one. `recentSessions(1)` is a Room-invalidation Flow
    // over `session_records`: the row exists from DASH_START's fold and finalizes when DASH_STOP folds,
    // survives process restart, and is the TRUE most-recent session — the $0 unassign dash included.
    // Cross-platform by construction (P8): whichever platform dashed last, labeled by its own chip.
    val lastSession = analyticsRepository.recentSessions(1)
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Gas price for the idle-card quick edit (#693) — read straight off the economy SSOT the settings
    // screen owns; the write path (setGasPrice) flips auto OFF so the daily EIA worker won't clobber it.
    val gasPrice = appPreferencesRepository.gasPrice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isGasPriceAuto = appPreferencesRepository.isGasPriceAuto
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /**
     * Persist a driver override of the pump price from the bubble quick-edit (#693). Writes the SAME
     * economy store the settings wizard owns (no second copy) and disables auto so the value sticks.
     * Frozen-economics invariant (#314): this changes only FUTURE offer evaluations. Also the
     * mode-adaptive AUTO card's "take manual control" gesture (#722): the composable calls this with
     * the CURRENT (unchanged) price to flip auto off — the existing #721 auto-flip semantic, now
     * behind an intentional tap instead of a bare stepper.
     */
    fun setGasPrice(price: Float) {
        viewModelScope.launch { appPreferencesRepository.updateGasPriceManual(price) }
    }

    // Transient loading state for the bubble's gas-price refresh actions (#722) — a StateFlow (not a
    // local composable flag) so an in-flight fetch's spinner survives recomposition/config change
    // (Reactive UI rule 2: the anchor lives in state, the UI only derives from it).
    private val _isGasPriceRefreshing = MutableStateFlow(false)
    val isGasPriceRefreshing = _isGasPriceRefreshing.asStateFlow()

    // One-shot signal for a failed refresh/resume-auto fetch — drives a transient UI flash only.
    // No toast spam: the underlying WARN already logs the reason (#692 levels).
    private val _gasPriceRefreshFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val gasPriceRefreshFailed = _gasPriceRefreshFailed.asSharedFlow()

    /**
     * AUTO-mode refresh icon (#722): fetch today's EIA price now, STAY auto. Routes through the ONE
     * existing fetch path [FuelPriceRepository.fetchAndSaveCurrentGasPrice] — the same call
     * [cloud.trotter.dashbuddy.worker.DailyGasPriceWorker] makes — so there is no second fetch path.
     */
    fun refreshGasPrice() = doGasPriceFetch { fuelType ->
        fuelPriceRepository.fetchAndSaveCurrentGasPrice(fuelType)
    }

    /**
     * MANUAL-mode "Resume auto" chip (#722): re-enable auto AND apply the freshly-fetched price in
     * one atomic write — the inverse of the stepper's manual flip. Same underlying fetch as
     * [refreshGasPrice]; only the save differs (see [FuelPriceRepository.fetchAndResumeAutoGasPrice]).
     */
    fun resumeAutoGasPrice() = doGasPriceFetch { fuelType ->
        fuelPriceRepository.fetchAndResumeAutoGasPrice(fuelType)
    }

    private fun doGasPriceFetch(fetch: suspend (FuelType) -> Result<Float>) {
        if (_isGasPriceRefreshing.value) return
        viewModelScope.launch {
            _isGasPriceRefreshing.value = true
            try {
                val fuelType = appPreferencesRepository.fuelType.first()
                val result = fetch(fuelType)
                if (result.isFailure) _gasPriceRefreshFailed.tryEmit(Unit)
            } finally {
                _isGasPriceRefreshing.value = false
            }
        }
    }

    // --- Offer actions (bubble Accept/Decline) ---

    // Signals the bubble to collapse to its head after the user acts on an offer.
    private val _collapse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val collapse = _collapse.asSharedFlow()

    /** User tapped Accept in the bubble → perform the platform accept + collapse. */
    fun acceptOffer() = onOfferAction(OfferIntent.ACCEPT)

    /** User tapped Decline → perform the platform's initial decline + collapse. */
    fun declineOffer() = onOfferAction(OfferIntent.DECLINE)

    private fun onOfferAction(action: String) {
        // #438 item 8a/B3: stamp the acted offer's identity from the state the bubble renders so the
        // dispatched UiInput targets the owning region (an Unknown-platform tap steps no region
        // post-#682). The offer now lives on the focused platform's OWN region; its platform is its
        // own provenance (PendingOffer.platform SSOT), falling back to the focused platform.
        val focused = focusedPlatform.value
        val pendingOffer = focused?.let { stateManager.state.value.regions.platforms[it]?.presentedOffer() }
        val platform = pendingOffer?.platform?.takeIf { it != Platform.Unknown } ?: focused
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
