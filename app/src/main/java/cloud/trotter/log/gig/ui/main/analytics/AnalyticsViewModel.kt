package cloud.trotter.log.gig.ui.main.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.CorrectionRepository
import cloud.trotter.log.gig.core.data.analytics.LedgerRepository
import cloud.trotter.log.gig.core.data.analytics.RecapRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.ShiftChecksRepository
import cloud.trotter.log.gig.core.data.analytics.currentLocalDateFlow
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.recap.DayFacts
import cloud.trotter.log.gig.domain.analytics.recap.RecapComparisons
import cloud.trotter.log.gig.domain.analytics.recap.RecapDay
import cloud.trotter.log.gig.domain.analytics.recap.RecapDays
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import cloud.trotter.log.gig.state.shiftRunning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

/**
 * State holder for Recap, the week (#1515): one immutable [AnalyticsUiState] assembled from the read model for the
 * selected Monday week and the week before (Principle 1, state down, intents up).
 *
 * **The week.** The selection is persisted in app preferences and is its one owner: [stepWeek] and the date picker's
 * [selectWeek] write it (as the Month screen's rows do) and the resolved week comes back through [AppPreferencesRepository.analyticsWindow], with no local copy (Principle 5).
 * Home writes this week before it opens Recap. The week resolves against [currentLocalDateFlow], so it re-anchors at
 * local midnight without a per-second tick.
 *
 * **The day series** is [RecapRepository.days] judged by the pure [RecapDays] (the five marks and the one "has this
 * day happened" rule), and the comparison is the pure [RecapComparisons] over it, held back while a shift runs
 * ([shiftRunning], the one owner the bubble reads too). **Shifts to check** come from [ShiftChecksRepository], whose
 * `ShiftChecks.of` is the only judge.
 *
 * Privacy: economics, counts and the app's name only; no customer data reaches this state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val recapRepository: RecapRepository,
    private val shiftChecksRepository: ShiftChecksRepository,
    private val ledgerRepository: LedgerRepository,
    private val correctionRepository: CorrectionRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    /** The records status every look-back screen reads; REBUILDING holds Recap's figures back. */
    private val recordsStatusRepository: RecordsStatusRepository,
    stateManager: StateManagerV2,
    /** The switch lives here (saved state); a fresh open from Home starts a new entry on Earned. */
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val measure: StateFlow<RecapMeasure> = handle.getStateFlow(KEY_MEASURE, RecapMeasure.EARNED)

    /** The persisted week selection: DataStore is the one owner; intents write, this reads back. */
    private val selection: StateFlow<AnalyticsWindowSelection> =
        appPreferencesRepository.analyticsWindow
            .stateIn(viewModelScope, SharingStarted.Eagerly, AnalyticsWindowSelection.DEFAULT)

    /** The device's local date, re-emitting at midnight. */
    private val today: StateFlow<LocalDate> = currentLocalDateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    private val running: Flow<Boolean> = stateManager.state.map { shiftRunning(it) }.distinctUntilChanged()

    /** Bumped by [retry]; every bump re-subscribes the reads. */
    private val attempts = MutableStateFlow(0)

    /** UDF intent: the failure pattern's Retry. */
    fun retry() {
        attempts.value++
    }

    val uiState: StateFlow<AnalyticsUiState> = attempts.flatMapLatest { attempt ->
        val week = combine(selection, today) { sel, day -> sel.resolve(day) to day }
            .distinctUntilChanged()
            .flatMapLatest { (w, day) -> weekData(w, day) }
        combine(week, measure, recapRepository.anyShift, running) { data, m, any, isRunning ->
            AnalyticsUiState(
                week = data.week,
                today = data.today,
                canStepForward = AnalyticsWindows.canStepForward(data.week, data.today),
                measure = m,
                anyShift = any,
                shiftRunning = isRunning,
                economics = data.economics,
                lastWeekEconomics = data.lastWeekEconomics,
                days = data.days,
                lastWeekDays = data.lastWeekDays,
                comparison = RecapComparisons.of(data.days, data.lastWeekDays, m, isRunning),
                shiftsToCheck = data.days.flatMap { day ->
                    day.shifts.mapNotNull { shift ->
                        data.checks[shift.sessionId]?.takeIf { it.isNotEmpty() }?.let { ShiftToCheck(day.date, shift, it) }
                    }
                },
                spending = data.spending,
                noSessionDeliveries = data.noSessionDeliveries,
            )
        }
            .onStart { if (attempt > 0) emit(AnalyticsUiState(loaded = false)) }
            .catch { e -> emit(failedRead(e, AnalyticsUiState(failed = true))) }
            // The records status joins AFTER the reads' catch, so a failed read keeps the real status
            // (a failure mid-rebuild still reads REBUILDING) rather than a default UP_TO_DATE.
            .combine(recordsStatusRepository.status) { state, records -> state.copy(records = records) }
            .catch { e -> emit(failedRead(e, AnalyticsUiState(failed = true))) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsUiState(loaded = false))

    /** One week's reads and the week before's, re-anchored together so no figure shows under another week's label. */
    private fun weekData(week: AnalyticsWindow, today: LocalDate): Flow<WeekData> {
        val lastWeek = AnalyticsWindows.step(week, -1)
        val withChecks = recapRepository.days(week).flatMapLatest { facts ->
            val ended = facts.flatMap { it.shifts }.filter { it.endedAt != null }.map { it.sessionId }
            shiftChecksRepository.checks(ended).map { checks -> facts to checks }
        }
        return combine(
            analyticsRepository.periodEconomics(week),
            analyticsRepository.periodEconomics(lastWeek),
            withChecks,
            recapRepository.days(lastWeek),
            combine(ledgerRepository.spending(week), analyticsRepository.noSessionDeliveries(week)) { s, o -> s to o },
        ) { economics, lastEconomics, (facts, checks), lastFacts, (spending, orphans) ->
            WeekData(
                week = week,
                today = today,
                economics = economics,
                lastWeekEconomics = lastEconomics,
                days = recapDays(week, today, facts),
                lastWeekDays = recapDays(lastWeek, today, lastFacts),
                checks = checks,
                spending = spending,
                noSessionDeliveries = orphans,
            )
        }
    }

    private fun recapDays(week: AnalyticsWindow, today: LocalDate, facts: List<DayFacts>): List<RecapDay> =
        RecapDays.of(requireNotNull(week.startDate), today, facts)

    /** A failed read's state, after logging it. Cancellation is never a failed read. */
    private fun <T> failedRead(e: Throwable, failed: T): T {
        if (e is CancellationException) throw e
        // P7: the failure class only, never record contents.
        Timber.tag(TAG).w(e, "recap read failed")
        return failed
    }

    /** UDF intent: the Earned / Est. Profit switch. */
    fun setMeasure(value: RecapMeasure) {
        handle[KEY_MEASURE] = value
    }

    /**
     * UDF intent: the period bar's `‹`/`›`. [steps] is negative to page back. A step past this week is ignored (the
     * arrow is disabled there; this is the backstop for the intent itself).
     */
    fun stepWeek(steps: Int) {
        if (steps == 0) return
        val next = selection.value.weekOffset + steps
        if (next > 0) return
        viewModelScope.launch { appPreferencesRepository.writeRecapWeek(AnalyticsWindowSelection(next), TAG) }
    }

    /**
     * UDF intent: the date picker's Week. Recap moves to [date]'s Monday week through the same selection the arrows
     * write ([AnalyticsWindowSelection.weekOf]); a date after this week selects this week.
     */
    fun selectWeek(date: LocalDate) {
        val selection = AnalyticsWindowSelection.weekOf(date, today.value)
        viewModelScope.launch { appPreferencesRepository.writeRecapWeek(selection, TAG) }
    }

    /** The shifts a delivery with no shift could be assigned to; the repository owns the policy (±48 h, ended, same app). */
    suspend fun candidateSessionsFor(orphan: DeliveryRecord): List<SessionRecord> =
        analyticsRepository.candidateSessionsForOrphan(orphan.completedAt, orphan.platform)

    /**
     * UDF intent (#660 piece 2): assign a delivery with no shift to [newSessionId] by appending a
     * `DELIVERY_SESSION_ASSIGN`. The projector applies it and Room invalidation refreshes the week.
     */
    fun assignToSession(targetEventSequenceId: Long, newSessionId: String) {
        viewModelScope.launch {
            try {
                correctionRepository.assignDeliverySession(
                    targetEventSequenceId = targetEventSequenceId,
                    newSessionId = newSessionId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // P7: ids only.
                Timber.tag(TAG).w(e, "assignToSession rejected for delivery seq %d", targetEventSequenceId)
            }
        }
    }

    /** One week's read model, re-anchored atomically under [flatMapLatest]. */
    private data class WeekData(
        val week: AnalyticsWindow,
        val today: LocalDate,
        val economics: PeriodEconomics,
        val lastWeekEconomics: PeriodEconomics,
        val days: List<RecapDay>,
        val lastWeekDays: List<RecapDay>,
        val checks: Map<String, List<ShiftCheck>>,
        val spending: Double,
        val noSessionDeliveries: List<DeliveryRecord>,
    )

    companion object {
        private const val TAG = "AnalyticsVm"

        /** SavedStateHandle key for the switch. */
        internal const val KEY_MEASURE = "recap.measure"
    }
}
