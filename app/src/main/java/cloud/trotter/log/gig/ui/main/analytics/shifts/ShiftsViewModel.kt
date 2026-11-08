package cloud.trotter.log.gig.ui.main.analytics.shifts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.RecapRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.ShiftChecksRepository
import cloud.trotter.log.gig.core.data.analytics.currentLocalDateFlow
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.recap.ShiftWeek
import cloud.trotter.log.gig.domain.analytics.recap.ShiftWeeks
import cloud.trotter.log.gig.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.inject.Inject

/** The Shifts list's state (#1515): every shift by Monday week, newest first. */
data class ShiftsUiState(
    /** False until the first read emits. */
    val loaded: Boolean = true,
    val failed: Boolean = false,
    val records: RecordsStatus = RecordsStatus.UP_TO_DATE,
    /** The device's local date: the anchor of a header's year. */
    val today: LocalDate = LocalDate.now(),
    /** The Monday of the week the list opens at; always one of [weeks] once loaded. */
    val targetWeek: LocalDate = ShiftWeeks.weekStart(today),
    /** Newest first; only [targetWeek] may be empty. */
    val weeks: List<ShiftWeek> = emptyList(),
    /** The checks of the ended shifts in the weeks on screen, by id; a shift not in it shows no reason. */
    val checks: Map<String, List<ShiftCheck>> = emptyMap(),
) {
    /** False when no shift has ever been recorded: the "no shifts yet" line. */
    val anyShift: Boolean get() = weeks.any { it.shifts.isNotEmpty() }
}

/**
 * State holder for the Shifts list, opened from Recap's Shifts row at Recap's week (the route's ISO `week`
 * argument; absent or unparsable, this week). Reads every shift once ([RecapRepository.allShifts]) and groups it with
 * the pure [ShiftWeeks]. **Checks** come from [ShiftChecksRepository] (`ShiftChecks` stays the only judge), and only
 * for the ended shifts of the weeks the list reports on screen ([onVisibleWeeks]): each id costs two Room flows, so
 * the list never fans out over every shift. This screen never writes Recap's week. Follows the shared states:
 * loading, updating, rebuilding, couldn't load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShiftsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recapRepository: RecapRepository,
    private val shiftChecksRepository: ShiftChecksRepository,
    private val recordsStatusRepository: RecordsStatusRepository,
) : ViewModel() {

    private val weekArg: LocalDate? = savedStateHandle.get<String>(Screen.Shifts.ARG_WEEK)?.let {
        try {
            ShiftWeeks.weekStart(LocalDate.parse(it))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** The Mondays of the weeks on screen, reported by the list. */
    private val visibleWeeks = MutableStateFlow<Set<LocalDate>>(emptySet())

    private val attempts = MutableStateFlow(0)

    /** UDF intent: the weeks whose rows are on screen. */
    fun onVisibleWeeks(weeks: Set<LocalDate>) {
        visibleWeeks.value = weeks
    }

    /** UDF intent: the failure pattern's Retry. */
    fun retry() {
        attempts.value++
    }

    val uiState: StateFlow<ShiftsUiState> = attempts.flatMapLatest { attempt ->
        val zone = ZoneId.systemDefault()
        combine(recapRepository.allShifts(), currentLocalDateFlow(zone)) { shifts, today ->
            val target = weekArg ?: ShiftWeeks.weekStart(today)
            ShiftsUiState(today = today, targetWeek = target, weeks = ShiftWeeks.of(shifts, target, zone))
        }
            .distinctUntilChanged()
            .flatMapLatest { state ->
                visibleWeeks
                    .map { visible -> endedShiftIds(state.weeks, visible) }
                    .distinctUntilChanged()
                    .flatMapLatest { ids -> shiftChecksRepository.checks(ids) }
                    .map { checks -> state.copy(checks = checks) }
            }
            .onStart { if (attempt > 0) emit(ShiftsUiState(loaded = false)) }
            .catch { e -> emit(failedRead(e)) }
            .combine(recordsStatusRepository.status) { state, records -> state.copy(records = records) }
            .catch { e -> emit(failedRead(e)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShiftsUiState(loaded = false))

    private fun failedRead(e: Throwable): ShiftsUiState {
        if (e is CancellationException) throw e
        // P7: the failure class only, never record contents.
        Timber.tag(TAG).w(e, "shifts list read failed")
        return ShiftsUiState(failed = true)
    }

    private companion object {
        const val TAG = "ShiftsVm"
    }
}

/** The ended shifts of [visible] weeks, newest first: the only ids whose checks are read. */
internal fun endedShiftIds(weeks: List<ShiftWeek>, visible: Set<LocalDate>): List<String> =
    weeks.filter { it.start in visible }.flatMap { week -> week.shifts.filter { it.shift.endedAt != null }.map { it.shift.sessionId } }
