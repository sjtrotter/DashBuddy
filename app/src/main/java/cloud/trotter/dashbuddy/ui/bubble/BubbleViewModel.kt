package cloud.trotter.dashbuddy.ui.bubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.designsystem.theme.DRIVING_GLANCE_MULTIPLIER
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.focusedPlatform
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.feature.bubble.cards.BubbleCards
import cloud.trotter.dashbuddy.feature.bubble.cards.CardStackAssembler
import cloud.trotter.dashbuddy.feature.bubble.cards.FlowCardMapper
import cloud.trotter.dashbuddy.feature.bubble.cards.LiveCardBuilder
import cloud.trotter.dashbuddy.feature.bubble.session.BubbleSessionPresentation
import cloud.trotter.dashbuddy.feature.bubble.session.BubbleSessionResolver
import cloud.trotter.dashbuddy.feature.bubble.session.LiveSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
    private val chatRepository: ChatRepository,
    odometerRepository: OdometerRepository,
    private val stateManager: StateManagerV2,
    appEventRepo: AppEventRepo,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val fuelPriceRepository: FuelPriceRepository,
    analyticsRepository: AnalyticsRepository,
    devSettingsRepository: DevSettingsRepository,
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

    // ---------------------------------------------------------------------------------------
    // #867 — WHICH dash the bubble is showing (multi-app session presentation)
    // ---------------------------------------------------------------------------------------

    /**
     * The dev-settings experiment switch, read reactively so flipping it re-scopes the live HUD
     * with no restart. Fail-closed decode lives in the repository
     * ([cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository.bubbleSessionMode]).
     */
    private val sessionMode = devSettingsRepository.bubbleSessionMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BubbleSessionMode.Default)

    /**
     * The manual switcher's pick — pure UI state, deliberately **in-memory / session-lifetime**
     * (not persisted, even in `PINNED` mode). It is a live-testing control over a surface that only
     * exists while dashing; persisting it would let a forgotten pin silently re-scope a later dash,
     * which is the exact class of defect #867 is about. Cleared to null when the picked platform's
     * dash ends in `FOLLOW_ACTIVE` (the temporary pin); kept in `PINNED` so it re-takes effect on
     * that platform's next dash. See [BubbleSessionMode] for the full semantics.
     */
    private val _selectedPlatform = MutableStateFlow<Platform?>(null)

    /** The platform whose frame we last recognized — the base the presentation follows. */
    private val followPlatform: Flow<Platform?> = stateManager.state
        .map { it.focusedPlatform() }
        .distinctUntilChanged()

    /** Every platform currently dashing (`PlatformRegion.session != null`), oldest dash first. */
    private val liveSessions = stateManager.state
        .map { state ->
            state.regions.platforms
                .mapNotNull { (platform, region) ->
                    region.session?.let { LiveSession(platform, it.sessionId, it.startedAt) }
                }
                // Deterministic order: the switcher chip row and the merged interleave must not
                // reshuffle on an unrelated state emission (Map iteration order is not a contract).
                .sortedWith(compareBy({ it.startedAt }, { it.platform.name }))
        }
        .distinctUntilChanged()

    /**
     * The resolved presentation — the single owner of "what is the bubble showing" (#867).
     *
     * The durable most-recent session from the event log (#459) is the fallback when nothing is
     * live: the old in-memory latch was wiped by process death AND by a post-dash bubble
     * re-subscribe, emptying the chat/cards.
     */
    private val presentation = combine(
        sessionMode,
        _selectedPlatform,
        followPlatform,
        liveSessions,
        appEventRepo.getMostRecentSessionId(),
    ) { mode, selection, follow, live, mostRecent ->
        BubbleSessionResolver.resolve(
            mode = mode,
            selection = selection,
            followPlatform = follow,
            liveSessions = live,
            fallbackSessionId = mostRecent,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            BubbleSessionPresentation.Empty,
        )

    // Which platform the bubble is currently showing — the switcher's pick when one is in effect,
    // otherwise the last-active platform (the pre-#867 behavior). Everything platform-scoped
    // (status badge, mode cards, session earnings, chat, cards) hangs off this ONE value.
    val focusedPlatform = presentation
        .map { it.displayedPlatform }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The switcher chip row's options; fewer than two ⇒ the row hides itself. */
    val switchablePlatforms = presentation
        .map { it.switchablePlatforms }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The displayed platform's badge for the chat header — non-null only while ≥2 dashes are live,
     * which is when the identity is ambiguous. Same gate as the per-card chips.
     */
    val displayedPlatformLabel = presentation
        .map { pres ->
            pres.displayedPlatform?.shortName?.takeIf { pres.labelsVisible && it.isNotBlank() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * The switcher intent (UDF: the UI dispatches, the ViewModel owns the state). Re-picking the
     * platform already displayed clears the pin — a toggle back to plain follow-active.
     */
    fun selectPlatform(platform: Platform) {
        _selectedPlatform.value = if (_selectedPlatform.value == platform) null else platform
    }

    init {
        // The temporary-pin release (#867): in FOLLOW_ACTIVE a pick lasts only as long as the dash
        // it points at, so when that platform goes offline the pin is DROPPED and the HUD follows
        // again. PINNED keeps it (durable until the user switches); MERGED ignores it entirely.
        viewModelScope.launch {
            combine(sessionMode, liveSessions) { mode, live -> mode to live.map { it.platform } }
                .collect { (mode, livePlatforms) ->
                    val pinned = _selectedPlatform.value
                    if (mode == BubbleSessionMode.FOLLOW_ACTIVE &&
                        pinned != null && pinned !in livePlatforms
                    ) {
                        _selectedPlatform.value = null
                    }
                }
        }
    }

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


    /**
     * Messages scoped to the DISPLAYED dash session (#867 — it was the *active* one).
     *
     * Chat and cards key off the SAME displayed dash id (#367): [presentation] resolves it once
     * from the mode + the switcher pick + liveness, so a pin re-scopes both together. Chat never
     * merges — even in `MERGED` mode it stays on one session (see [BubbleSessionResolver]) and the
     * header badge names it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = presentation
        .map { it.chatSessionId }
        .distinctUntilChanged()
        .flatMapLatest { dashId ->
            if (dashId != null) {
                chatRepository.getMessages(dashId)
            } else {
                flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The folded completed cards for every displayed session — one list in the normal modes, N in
     * `MERGED`. Each fold stays per-session because [FlowCardMapper.fold] is a per-session fold by
     * construction (DASH_START clears the stack); the interleave happens after, in
     * [CardStackAssembler].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val foldedCards = presentation
        .map { it.cardSources }
        .distinctUntilChanged()
        .flatMapLatest { sources ->
            if (sources.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    sources.map { source ->
                        appEventRepo.getEventsForSession(source.sessionId)
                            .map { events -> source to FlowCardMapper.fold(events) }
                    }
                ) { folds -> folds.toList() }
            }
        }

    /**
     * Bubble HUD flow-card stack (#257) + its per-card platform badges (#867). Completed cards are
     * folded from the AppEvent log scoped to the displayed session(s); the live card is built from
     * current [appState]. A new dash clears the stack via DASH_START's fold logic, so the user can
     * review the previous dash's cards until they go Online again.
     *
     * The live card is dropped when the display is pinned away from the platform whose frame we
     * last saw — the live card is derived from the global `FlowRegion`, which only ever describes
     * that platform (see [BubbleSessionPresentation.showLiveCard]).
     */
    val cards = combine(
        stateManager.state,
        foldedCards,
        presentation,
    ) { state, folded, pres ->
        // CardStack.of drops a frozen completed card that duplicates the active
        // card's id — the at-door frozen+live overlap (#458).
        CardStackAssembler.assemble(
            folded = folded,
            activeCard = if (pres.showLiveCard) LiveCardBuilder.build(state) else null,
            activePlatform = pres.followPlatform,
            labelsVisible = pres.labelsVisible,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BubbleCards.Empty)

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
