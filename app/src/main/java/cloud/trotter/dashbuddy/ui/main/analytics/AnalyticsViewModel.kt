package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.core.data.analytics.currentLocalDateFlow
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.EstimateVsReality
import cloud.trotter.dashbuddy.domain.analytics.OfferFilter
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.analytics.PayMixParts
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PlatformEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/**
 * State holder for the Analytics hub (#315 H1, made **period-first** by #970) — a **review** surface
 * assembled reactively from the durable analytics read-model ([AnalyticsRepository]), exposing one
 * immutable [AnalyticsUiState] (Principle 1 — UDF). Over the read-model repository surface:
 * `periodEconomics` (frozen-net totals), `perStoreEconomics` (top stores), `recentSessions`
 * (recent dashes), `decisionEconomics` (H3 funnel/verdicts), and `timeEconomics` (H4 time/mileage) —
 * every *window* source session-anchored (#655) via the DAO join, which owns the bucketing. The
 * Patterns tab's `storeReportCards` (#159) + `earningsHeatmap` (H5) are LIFETIME-scoped and sit
 * outside the window `flatMapLatest`, so a window switch never re-queries them.
 *
 * **The window (#970).** The selection is persisted in app preferences and is the single source of
 * truth: intents write to the DataStore and the resolved window comes back through
 * [AppPreferencesRepository.analyticsWindow] — no optimistic local copy that could drift
 * (Principle 5). A *relative* selection ("this week", "two weeks back") is resolved against
 * [currentLocalDateFlow], so the hub re-anchors at local midnight without a restart and without a
 * per-second ticker (Reactive UI rule 4: the anchor changes once a day, so its flow ticks once a
 * day). The previous-equivalent window's economics ride the same fan-out for the recap hero's delta.
 *
 * Reactive by construction: every source is a Room-invalidation `Flow`, so the tiles re-emit
 * as the projector folds each delivery — fresh-on-open, no `rememberNow()` clock (a historical
 * window's $/hr is a fixed value; same principle as the dashboard reframe #657).
 *
 * Privacy: economics/counts/store names only — customer PII is already sha256'd upstream and
 * never surfaces here (Principle 6); store names are merchants, fine to render (Principle 7
 * governs logs, not this UI). No platform literals — the recent-dashes chip resolves its
 * label through the [cloud.trotter.dashbuddy.domain.state.Platform] registry (Principle 8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val correctionRepository: CorrectionRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
) : ViewModel() {

    /** UDF intent target — the visible tab (transient; only the window is persisted). */
    private val selectedTab = MutableStateFlow(AnalyticsTab.Money)

    /** The persisted window selection — DataStore is the SSOT; intents write, this reads back. */
    private val selection: StateFlow<AnalyticsWindowSelection> =
        appPreferencesRepository.analyticsWindow
            .stateIn(viewModelScope, SharingStarted.Eagerly, AnalyticsWindowSelection.DEFAULT)

    /** The device's local date, re-emitting at midnight — the anchor a relative selection resolves against. */
    private val today: StateFlow<LocalDate> = currentLocalDateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    /** The resolved window the whole hub reads. */
    private val window: StateFlow<AnalyticsWindow> =
        combine(selection, today) { sel, day -> sel.resolve(day) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, AnalyticsWindowSelection.DEFAULT.resolve(LocalDate.now()))

    val uiState: StateFlow<AnalyticsUiState> = combine(
        selectedTab,
        // Re-anchor economics + top stores + decisions together on each window switch so a tile never
        // renders one window's number under another's label. Decisions is collected unconditionally
        // (not gated on the selected tab): the source is a cheap Room-invalidation aggregate and
        // folding it in here keeps one immutable UiState re-anchored atomically with the window.
        combine(window, today) { w, day -> w to day }.flatMapLatest { (w, day) ->
            combine(
                analyticsRepository.periodEconomics(w),
                analyticsRepository.perStoreEconomics(w),
                analyticsRepository.decisionEconomics(w),
                analyticsRepository.timeEconomics(w),
                // The typed `combine` tops out at 5 flows, so every remaining window-anchored read
                // rides ONE nested pair of sub-combines into the 5th slot: the per-day chart + the
                // "(No session)" orphan list (#660 piece 2) + the orphan-OFFER groups (#810 B2 Tier 2)
                // on one side, and the previous-window economics (#970 §7.2) + the pay-mix parts +
                // the platform split (#973 §4.2/§7.6) on the other. All window-anchored, so they
                // re-anchor atomically with everything else.
                combine(
                    combine(
                        analyticsRepository.dailyEarnings(w),
                        analyticsRepository.noSessionDeliveries(w),
                        analyticsRepository.orphanOfferGroups(w),
                    ) { daily, orphans, offerGroups -> Triple(daily, orphans, offerGroups) },
                    combine(
                        previousEconomics(w),
                        analyticsRepository.payMixParts(w),
                        analyticsRepository.platformEconomics(w),
                    ) { previous, payMixParts, platforms -> Triple(previous, payMixParts, platforms) },
                    // #975 §7.5 — the Offers tab's est-vs-realized comparison. Window-anchored like
                    // everything else here, so it re-anchors atomically with the funnel it sits under
                    // (a card claiming "N of M accepted offers" must never quote a different window's M).
                    analyticsRepository.estimateVsReality(w),
                ) { (daily, orphans, offerGroups), (previous, payMixParts, platforms), estVsReality ->
                    WindowExtras(daily, orphans, offerGroups, previous, payMixParts, platforms, estVsReality)
                },
            ) { economics, stores, decisions, time, extras ->
                WindowData(w, day, economics, stores, decisions, time, extras)
            }
        },
        analyticsRepository.recentSessions(RECENT_SESSIONS_LIMIT),
        // The Patterns tab (H5, #159) is LIFETIME-scoped by design — its sources sit OUTSIDE the
        // window flatMapLatest so switching the Money/Decisions/Time window never re-queries them.
        analyticsRepository.storeReportCards(),
        analyticsRepository.earningsHeatmap(),
    ) { tab, data, sessions, storeCards, heatmap ->
        AnalyticsUiState(
            selectedTab = tab,
            window = data.window,
            today = data.today,
            canStepBack = AnalyticsWindows.canStepBack(data.window),
            canStepForward = AnalyticsWindows.canStepForward(data.window, data.today),
            economics = data.economics,
            previousEconomics = data.extras.previousEconomics,
            // The mix is composed HERE, against this window's own gross (#973): gross has exactly one
            // owner (the repository's economics fold) and the "bonuses & other" residue is defined
            // relative to it, so composing at the read site is what keeps the bar reconciling with the
            // hero rather than against a second, independently-derived total (Principle 5).
            payMix = PayMix.of(data.economics.grossEarnings, data.extras.payMixParts),
            platformSplit = data.extras.platformSplit,
            topStores = data.stores.take(TOP_STORES),
            recentSessions = sessions,
            decisions = data.decisions,
            estimateVsReality = data.extras.estimateVsReality,
            time = data.time,
            dailyEarnings = data.extras.dailyEarnings,
            noSessionDeliveries = data.extras.orphanDeliveries,
            orphanOfferGroups = data.extras.orphanOfferGroups,
            storeReportCards = storeCards,
            earningsHeatmap = heatmap,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsUiState())

    /**
     * The previous-equivalent window's economics, or a `null` emission for a window that has no
     * predecessor (Lifetime). Kept as a flow (not a nullable read) so the sub-combine always has five
     * live sources and the hero's delta re-emits with everything else.
     */
    private fun previousEconomics(window: AnalyticsWindow) =
        AnalyticsWindows.previous(window)
            ?.let { analyticsRepository.periodEconomics(it) }
            ?: flowOf<PeriodEconomics?>(null)

    // ── The Offers tab's list feed (#975 / brief §7.4) ───────────────────

    /** Which outcome the list is filtered to — list-local selection, deliberately not persisted. */
    private val offerFilter = MutableStateFlow(OfferFilter.ALL)

    /** How many rows the list is asking for; the `See all` footer grows it, the read clamps it. */
    private val offerPageSize = MutableStateFlow(AnalyticsRepository.DEFAULT_OFFER_PAGE)

    /**
     * The visible offers page + the window's total under the same filter (#975 / brief §7.4).
     *
     * Kept as its OWN `StateFlow` rather than a field of [uiState] (see [OffersFeedState]): the two
     * inputs are list-local, and re-emitting every Money/Time tile on a chip tap would be exactly the
     * "state changed ≠ UI updated" conflation the Reactive UI rules warn about. It re-anchors on the
     * hub's [window] like every other read, so paging back a week can never leave last week's rows
     * under this week's label.
     *
     * Both sources are Room-invalidation Flows, so the page refreshes as the projector folds.
     */
    val offersFeed: StateFlow<OffersFeedState> =
        combine(window, offerFilter, offerPageSize) { w, filter, pageSize -> Triple(w, filter, pageSize) }
            .flatMapLatest { (w, filter, pageSize) ->
                combine(
                    analyticsRepository.offers(w, filter, pageSize),
                    analyticsRepository.offerCount(w, filter),
                ) { rows, total -> OffersFeedState(filter = filter, offers = rows, total = total) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OffersFeedState())

    /** UDF intent — the filter chips. Re-collapses the page: a new filter is a new list. */
    fun setOfferFilter(filter: OfferFilter) {
        if (offerFilter.value == filter) return
        offerFilter.value = filter
        offerPageSize.value = AnalyticsRepository.DEFAULT_OFFER_PAGE
    }

    /**
     * UDF intent — the `See all N offers` footer. Expands the page **in place** rather than pushing a
     * second screen: the whole list is one review surface over one selected window, and a separate
     * route would need its own window/filter plumbing to say the same thing. The repository clamps
     * the request to its own ceiling, so a Lifetime window can't turn this into an unbounded read.
     */
    fun showAllOffers() {
        offerPageSize.value = AnalyticsRepository.MAX_OFFER_PAGE
    }

    // ── The range picker's calendar (brief §3.2) ─────────────────────────

    /** Which month the picker's calendar is showing — picker-local, not part of the hub's UiState. */
    private val calendarMonth = MutableStateFlow(YearMonth.now())

    /** The visible calendar month, for the picker's header + month pager. */
    val pickerMonth: StateFlow<YearMonth> = calendarMonth

    /**
     * Per-day earnings for the picker's visible month — the calendar's **earning dots**. Reuses the
     * same arbitrary-window per-day read (#970 §7.1) the Money chart uses rather than a bespoke
     * query (Principle 5); a month is 28–31 days, well inside the read's bounded day axis.
     */
    val pickerMonthDays: StateFlow<List<DailyEarnings>> = calendarMonth
        .flatMapLatest { month ->
            analyticsRepository.dailyEarnings(
                AnalyticsWindows.custom(month.atDay(1), month.atEndOfMonth()),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** UDF intent — page the picker's calendar to [month]. */
    fun setPickerMonth(month: YearMonth) {
        calendarMonth.value = month
    }

    // ── Window intents ──────────────────────────────────────────────────

    /** UDF intent — switch the visible tab. */
    fun setTab(tab: AnalyticsTab) {
        selectedTab.value = tab
    }

    /**
     * UDF intent — the `‹`/`›` pager. [steps] is negative to page back. A forward step past the
     * current window is **ignored** rather than clamped silently into a future range (the arrow is
     * already disabled there; this is the fail-closed backstop for the intent itself).
     */
    fun stepWindow(steps: Int) {
        if (steps == 0) return
        val current = window.value
        if (steps > 0 && !AnalyticsWindows.canStepForward(current, today.value)) return
        if (steps < 0 && !AnalyticsWindows.canStepBack(current)) return
        val next = when (val sel = selection.value) {
            is AnalyticsWindowSelection.Relative ->
                sel.copy(offset = (sel.offset + steps).coerceAtMost(0))
            is AnalyticsWindowSelection.Custom -> {
                val stepped = AnalyticsWindows.step(current, steps)
                val start = stepped.startDate
                val end = stepped.endDateInclusive
                if (start == null || end == null) sel else AnalyticsWindowSelection.Custom(start, end)
            }
        }
        persist(next)
    }

    /**
     * UDF intent — the granularity chips. Always lands on the CURRENT window of that granularity
     * (offset 0), which is what "Day / Week / Month / Lifetime" reads as. `CUSTOM` is not a
     * granularity you can select this way — that chip opens the picker, which calls [setCustomRange].
     */
    fun setGranularity(granularity: WindowGranularity) {
        if (granularity == WindowGranularity.CUSTOM) return
        persist(AnalyticsWindowSelection.Relative(granularity, 0))
    }

    /** UDF intent — a preset row in the range picker (e.g. "Last week" = week, one step back). */
    fun setRelativeWindow(granularity: WindowGranularity, offset: Int) {
        if (granularity == WindowGranularity.CUSTOM) return
        persist(AnalyticsWindowSelection.Relative(granularity, offset.coerceAtMost(0)))
    }

    /** UDF intent — the picker's committed calendar range (both ends inclusive). */
    fun setCustomRange(startDate: LocalDate, endDateInclusive: LocalDate) {
        if (endDateInclusive.isBefore(startDate)) return
        persist(AnalyticsWindowSelection.Custom(startDate, endDateInclusive))
    }

    private fun persist(selection: AnalyticsWindowSelection) {
        // The ONE funnel every window change goes through, so it is also the one place the Offers
        // list re-collapses: a different window is a different list, and carrying a 250-row page
        // across the pager would make each step an unbounded read (#975).
        offerPageSize.value = AnalyticsRepository.DEFAULT_OFFER_PAGE
        viewModelScope.launch {
            try {
                appPreferencesRepository.setAnalyticsWindow(selection)
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow it
            } catch (e: Exception) {
                // P7: no PII — a granularity name and an integer offset only.
                Timber.tag(TAG).w(e, "window selection not persisted")
            }
        }
    }

    /**
     * The candidate dashes an orphan could be categorized into (#660 piece 2) — a one-shot suspend read
     * the picker runs in a `produceState` when the driver taps an orphan. Delegates to the repository's
     * ±48 h / same-platform / ended-only policy (the pre-filter; the projector's ended-session guard is
     * the authority).
     */
    suspend fun candidateSessionsFor(orphan: DeliveryRecord): List<SessionRecord> =
        analyticsRepository.candidateSessionsForOrphan(orphan.completedAt, orphan.platform)

    /**
     * UDF intent (#660 piece 2) — categorize an orphan delivery into [newSessionId] by appending a
     * `DELIVERY_SESSION_ASSIGN`. The projector applies it (with its fail-closed guards) and Room
     * invalidation refreshes the callout + the orphan list — no optimistic local mutation.
     */
    fun assignToSession(targetEventSequenceId: Long, newSessionId: String) {
        viewModelScope.launch {
            // Fail-closed backstop — a rejected append (blank id) must not crash the launch (parity with
            // the SessionDetail corrections). Alpha-acceptable silent reject; the projector is authoritative.
            try {
                correctionRepository.assignDeliverySession(
                    targetEventSequenceId = targetEventSequenceId,
                    newSessionId = newSessionId,
                )
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow it
            } catch (e: Exception) {
                // P7: counts/ids only.
                Timber.tag(TAG).w(e, "assignToSession rejected for delivery seq %d", targetEventSequenceId)
            }
        }
    }

    /**
     * UDF intent (#810 B2 Tier 2) — attest that an accepted offer was invisibly unassigned (or UNDO)
     * by appending an `OFFER_OUTCOME_CORRECTION`. The projector stamps `offer_records.outcomeResolved`
     * (with its fail-closed guards) and Room invalidation refreshes the callout + the open-mismatch
     * list — no optimistic local mutation. [attested] false is the undo (clears the resolution).
     */
    fun resolveOrphanOffer(offerEventSequenceId: Long, attested: Boolean) {
        viewModelScope.launch {
            try {
                correctionRepository.correctOfferOutcome(
                    targetOfferEventSequenceId = offerEventSequenceId,
                    attested = attested,
                )
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow it
            } catch (e: Exception) {
                // P7: counts/ids only.
                Timber.tag(TAG).w(e, "resolveOrphanOffer rejected for offer seq %d", offerEventSequenceId)
            }
        }
    }

    /** The 5th-slot sub-combine's payload — window-anchored reads that don't fit the typed 5-arity. */
    private data class WindowExtras(
        val dailyEarnings: List<DailyEarnings>,
        val orphanDeliveries: List<DeliveryRecord>,
        val orphanOfferGroups: List<OrphanOfferGroup>,
        val previousEconomics: PeriodEconomics?,
        val payMixParts: PayMixParts,
        val platformSplit: List<PlatformEconomics>,
        val estimateVsReality: EstimateVsReality,
    )

    /** One window switch's worth of read-model, re-anchored atomically under [flatMapLatest]. */
    private data class WindowData(
        val window: AnalyticsWindow,
        val today: LocalDate,
        val economics: PeriodEconomics,
        val stores: List<StoreEconomics>,
        val decisions: DecisionEconomics,
        val time: TimeEconomics,
        val extras: WindowExtras,
    )

    private companion object {
        const val TOP_STORES = 5
        const val RECENT_SESSIONS_LIMIT = 10
        const val TAG = "AnalyticsVm"
    }
}
