package cloud.trotter.log.gig.ui.main.analytics.month

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.RecapRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.currentLocalDateFlow
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.analytics.recap.DayMark
import cloud.trotter.log.gig.domain.analytics.recap.MonthComparison
import cloud.trotter.log.gig.domain.analytics.recap.MonthComparisons
import cloud.trotter.log.gig.domain.analytics.recap.MonthWeek
import cloud.trotter.log.gig.domain.analytics.recap.MonthWeeks
import cloud.trotter.log.gig.domain.analytics.recap.RecapDay
import cloud.trotter.log.gig.domain.analytics.recap.RecapDays
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import cloud.trotter.log.gig.state.shiftRunning
import cloud.trotter.log.gig.ui.main.analytics.writeRecapWeek
import cloud.trotter.log.gig.ui.main.navigation.Screen
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
import timber.log.Timber
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import javax.inject.Inject

/** The Month screen's state (#1515): one calendar month's days and the month before's, under the switch. */
data class MonthUiState(
    /** False until the first read emits. */
    val loaded: Boolean = true,
    val failed: Boolean = false,
    val records: RecordsStatus = RecordsStatus.UP_TO_DATE,
    /** The month shown; never after [today]'s. */
    val month: YearMonth = YearMonth.now(),
    /** The device's local date: the anchor of every day's time and of "This week". */
    val today: LocalDate = LocalDate.now(),
    val measure: RecapMeasure = RecapMeasure.EARNED,
    /** True while a shift runs; the comparison waits. */
    val shiftRunning: Boolean = false,
    /** Every day of [month], one mark each. */
    val days: List<RecapDay> = emptyList(),
    /** Every day of the month before. */
    val monthBeforeDays: List<RecapDay> = emptyList(),
) {
    /** `›` is live only before the current month. */
    val canStepForward: Boolean get() = month.isBefore(YearMonth.from(today))

    /** No shift and no delivery on any date of the month: "No shifts in October". */
    val monthOff: Boolean get() = days.all { it.mark == DayMark.NO_SHIFT || it.mark == DayMark.AHEAD }

    /** The month's total for the switch; the rows sum to it. */
    val total: Double? get() = MonthWeeks.total(days, measure)

    /** The Monday-to-Sunday rows, oldest first; in the current month only the weeks that have started. */
    val weeks: List<MonthWeek> get() = MonthWeeks.of(days, today)

    /** Null on the 1st of the current month and while a shift runs. */
    val comparison: MonthComparison? get() = MonthComparisons.of(days, monthBeforeDays, measure, shiftRunning)
}

/**
 * State holder for the Month screen, opened from Recap's date picker on a month and Recap's switch value (the route's
 * `month` and `measure`). The month and the switch live in this screen's [SavedStateHandle], under the route's own
 * keys, so the month bar and the picker move it in place and it survives process death; it never writes Recap's week,
 * except through a week row's tap ([selectWeek]), which writes the one selection Recap's period bar reads.
 *
 * Reads `RecapRepository.days` for the month and the month before, judged by the pure [RecapDays], [MonthWeeks] and
 * [MonthComparisons]; [shiftRunning] holds the comparison back as it does the week's. Follows the shared states:
 * loading, updating, rebuilding, couldn't load. Privacy: economics and dates only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonthViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val recapRepository: RecapRepository,
    private val recordsStatusRepository: RecordsStatusRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    stateManager: StateManagerV2,
) : ViewModel() {

    private val monthArg: StateFlow<String?> = handle.getStateFlow(Screen.Month.ARG_MONTH, null)

    private val measure: Flow<RecapMeasure> = handle.getStateFlow<String?>(Screen.Month.ARG_MEASURE, null)
        .map { name -> RecapMeasure.entries.firstOrNull { it.name == name } ?: RecapMeasure.EARNED }

    /** The device's local date, re-emitting at midnight. */
    private val today: StateFlow<LocalDate> = currentLocalDateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    private val running: Flow<Boolean> = stateManager.state.map { shiftRunning(it) }.distinctUntilChanged()

    private val attempts = MutableStateFlow(0)

    /** UDF intent: the failure pattern's Retry. */
    fun retry() {
        attempts.value++
    }

    val uiState: StateFlow<MonthUiState> = attempts.flatMapLatest { attempt ->
        val month = combine(monthArg, today) { arg, day -> resolve(arg, day) to day }
            .distinctUntilChanged()
            .flatMapLatest { (shown, day) -> monthData(shown, day) }
        combine(month, measure, running) { data, m, isRunning ->
            data.copy(measure = m, shiftRunning = isRunning)
        }
            .onStart { if (attempt > 0) emit(MonthUiState(loaded = false)) }
            .catch { e -> emit(failedRead(e)) }
            .combine(recordsStatusRepository.status) { state, records -> state.copy(records = records) }
            .catch { e -> emit(failedRead(e)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MonthUiState(loaded = false))

    /** The month's days and the month before's, re-anchored together so no figure shows under another month's label. */
    private fun monthData(month: YearMonth, today: LocalDate): Flow<MonthUiState> {
        val window = AnalyticsWindows.current(WindowGranularity.MONTH, month.atDay(1))
        val before = month.minusMonths(1)
        return combine(recapRepository.days(window), recapRepository.days(AnalyticsWindows.step(window, -1))) { facts, beforeFacts ->
            MonthUiState(
                month = month,
                today = today,
                days = RecapDays.ofMonth(month, today, facts),
                monthBeforeDays = RecapDays.ofMonth(before, today, beforeFacts),
            )
        }
    }

    /** UDF intent: the month bar's `‹`/`›`. A step past the current month is ignored (the arrow is disabled there). */
    fun stepMonth(steps: Int) {
        if (steps == 0) return
        val day = today.value
        selectMonth(resolve(monthArg.value, day).plusMonths(steps.toLong()))
    }

    /** UDF intent: the date picker's Month, moving this screen in place. A future month is never shown. */
    fun selectMonth(month: YearMonth) {
        if (month.isAfter(YearMonth.from(today.value))) return
        handle[Screen.Month.ARG_MONTH] = month.toString()
    }

    /** UDF intent: the Earned / Est. Profit switch. */
    fun setMeasure(value: RecapMeasure) {
        handle[Screen.Month.ARG_MEASURE] = value.name
    }

    /**
     * UDF intent: a week row. Writes Recap's week to [monday]'s week, the one selection Recap's period bar and picker
     * write, and returns once it is stored: true when it was, so the caller goes back to Recap on that week, false when
     * the write failed and this screen stays.
     */
    suspend fun selectWeek(monday: LocalDate): Boolean =
        appPreferencesRepository.writeRecapWeek(AnalyticsWindowSelection.weekOf(monday, today.value), TAG)

    private fun failedRead(e: Throwable): MonthUiState {
        if (e is CancellationException) throw e
        // P7: the failure class only, never record contents.
        Timber.tag(TAG).w(e, "month read failed")
        return MonthUiState(failed = true)
    }

    private companion object {
        const val TAG = "MonthVm"

        /** The route's `yyyy-MM`, clamped to [today]'s month; absent or unparsable, the current month. */
        fun resolve(arg: String?, today: LocalDate): YearMonth {
            val current = YearMonth.from(today)
            val parsed = arg?.let {
                try {
                    YearMonth.parse(it)
                } catch (_: DateTimeParseException) {
                    null
                }
            } ?: return current
            return if (parsed.isAfter(current)) current else parsed
        }
    }
}
