package cloud.trotter.log.gig.ui.main.analytics.day

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.RecapRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject

/** The day page's state (#1515): the day's figures and its shifts, newest first. */
data class DayUiState(
    /** False until the first read emits. */
    val loaded: Boolean = true,
    val failed: Boolean = false,
    val records: RecordsStatus = RecordsStatus.UP_TO_DATE,
    /** Null for a date argument that does not parse. */
    val date: LocalDate? = null,
    val economics: PeriodEconomics = PeriodEconomics.EMPTY,
    /** The shifts that started this day, newest first. */
    val shifts: List<DayShift> = emptyList(),
) {
    val figuresShown: Boolean get() = loaded && !failed && records != RecordsStatus.REBUILDING
}

/**
 * State holder for the day page, opened from Recap for a day with two or more shifts. Reads the day's figures from
 * `periodEconomics` over the one-day window (shift-anchored, as Recap's week) and the shifts that started on it.
 * The date is the route's ISO argument. Follows the shared states: loading, updating, rebuilding, couldn't load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val analyticsRepository: AnalyticsRepository,
    private val recapRepository: RecapRepository,
    private val recordsStatusRepository: RecordsStatusRepository,
) : ViewModel() {

    private val date: LocalDate? = savedStateHandle.get<String>(Screen.Day.ARG_DATE)?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private val attempts = MutableStateFlow(0)

    /** UDF intent: the failure pattern's Retry. */
    fun retry() {
        attempts.value++
    }

    val uiState: StateFlow<DayUiState> = attempts.flatMapLatest { attempt ->
        val day = date
        val reads = if (day == null) {
            flowOf(DayUiState(failed = true))
        } else {
            val window = AnalyticsWindows.current(WindowGranularity.DAY, day)
            combine(analyticsRepository.periodEconomics(window), recapRepository.shifts(window)) { economics, shifts ->
                DayUiState(date = day, economics = economics, shifts = shifts.sortedByDescending { it.startedAt })
            }
        }
        reads
            .onStart { if (attempt > 0) emit(DayUiState(loaded = false, date = day)) }
            .catch { e -> emit(failedRead(e, DayUiState(failed = true, date = day))) }
            .combine(recordsStatusRepository.status) { state, records -> state.copy(records = records) }
            .catch { e -> emit(failedRead(e, DayUiState(failed = true, date = day))) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayUiState(loaded = false, date = date))

    private fun <T> failedRead(e: Throwable, failed: T): T {
        if (e is CancellationException) throw e
        // P7: the failure class only, never record contents.
        Timber.tag(TAG).w(e, "day page read failed")
        return failed
    }

    private companion object {
        const val TAG = "DayVm"
    }
}
