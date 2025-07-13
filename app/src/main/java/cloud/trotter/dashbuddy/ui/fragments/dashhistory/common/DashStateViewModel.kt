package cloud.trotter.dashbuddy.ui.fragments.dashhistory.common

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.log.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar

class DashStateViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tag = "DashStateViewModel"

    companion object {
        const val VIEW_TYPE_KEY = "currentViewType"
        const val YEAR_KEY = "selectedYear"
        const val MONTH_KEY = "selectedMonth"
        const val DAY_KEY = "selectedDay"
        const val STAT_MODE_KEY = "statDisplayMode"

        // Define reference dates (epochs) for continuous scrolling calculations
        val REFERENCE_MONTH_DATE: LocalDate = LocalDate.of(2020, 1, 1)
        val REFERENCE_DAY_DATE: LocalDate = LocalDate.of(2020, 1, 1)
    }

    val currentViewType: StateFlow<HistoryViewType> =
        savedStateHandle.getStateFlow(VIEW_TYPE_KEY, HistoryViewType.ANNUAL)
    val selectedYear: StateFlow<Int> =
        savedStateHandle.getStateFlow(YEAR_KEY, Calendar.getInstance().get(Calendar.YEAR))
    val selectedMonth: StateFlow<Int?> = savedStateHandle.getStateFlow(MONTH_KEY, null)
    val selectedDay: StateFlow<Int?> = savedStateHandle.getStateFlow(DAY_KEY, null)
    val statDisplayMode: StateFlow<StatDisplayMode> =
        savedStateHandle.getStateFlow(STAT_MODE_KEY, StatDisplayMode.ACTIVE)
    private val _swipeEvent = MutableSharedFlow<SwipeDirection>()
    val swipeEvent = _swipeEvent.asSharedFlow()

    fun navigateUp() {
        Logger.i(tag, "navigateUp called from ${currentViewType.value}")
        val newViewType = when (currentViewType.value) {
            HistoryViewType.DAILY -> HistoryViewType.MONTHLY
            HistoryViewType.MONTHLY -> HistoryViewType.ANNUAL
            else -> {
                Logger.d(tag, "Already at top level (ANNUAL), cannot navigate up.")
                return
            }
        }
        savedStateHandle[VIEW_TYPE_KEY] = newViewType
    }

    fun onNextClicked() {
        viewModelScope.launch {
            _swipeEvent.emit(SwipeDirection.NEXT)
        }
    }

    fun onPreviousClicked() {
        viewModelScope.launch {
            _swipeEvent.emit(SwipeDirection.PREVIOUS)
        }
    }

    fun navigateToToday() {
        Logger.i(tag, "navigateToToday called.")
        val today = LocalDate.now()
        savedStateHandle[YEAR_KEY] = today.year
        savedStateHandle[MONTH_KEY] = today.monthValue
        savedStateHandle[DAY_KEY] = today.dayOfMonth
        savedStateHandle[VIEW_TYPE_KEY] = HistoryViewType.DAILY
    }

    fun selectMonth(month: Int) {
        Logger.i(tag, "selectMonth called for month: $month")
        savedStateHandle[MONTH_KEY] = month
        savedStateHandle[VIEW_TYPE_KEY] = HistoryViewType.MONTHLY
    }

    fun selectDay(day: Int) {
        Logger.i(tag, "selectDay called for day: $day")
        savedStateHandle[DAY_KEY] = day
        savedStateHandle[VIEW_TYPE_KEY] = HistoryViewType.DAILY
    }

    fun onYearPageSwiped(year: Int) {
        if (year != selectedYear.value) {
            Logger.i(tag, "Updating ViewModel year to: $year")
            savedStateHandle[YEAR_KEY] = year
        }
    }

    fun onMonthPageSwiped(position: Int) {
        val monthOffset = position - 10000 // Using a fixed START_POSITION
        val newDate = REFERENCE_MONTH_DATE.plusMonths(monthOffset.toLong())
        if (newDate.year != selectedYear.value || newDate.monthValue != selectedMonth.value) {
            Logger.i(tag, "Updating ViewModel month to: ${newDate.monthValue}/${newDate.year}")
            savedStateHandle[YEAR_KEY] = newDate.year
            savedStateHandle[MONTH_KEY] = newDate.monthValue
        }
    }

    fun onDailyPageSwiped(position: Int) {
        val dayOffset = position - 10000 // Using a fixed START_POSITION
        val newDate = REFERENCE_DAY_DATE.plusDays(dayOffset.toLong())
        if (newDate.year != selectedYear.value || newDate.monthValue != selectedMonth.value || newDate.dayOfMonth != selectedDay.value) {
            Logger.i(tag, "Updating ViewModel day to: $newDate")
            savedStateHandle[YEAR_KEY] = newDate.year
            savedStateHandle[MONTH_KEY] = newDate.monthValue
            savedStateHandle[DAY_KEY] = newDate.dayOfMonth
        }
    }

    fun toggleStatMode() {
        Logger.i(tag, "toggleStatMode called. Current mode: ${statDisplayMode.value}")
        savedStateHandle[STAT_MODE_KEY] = when (statDisplayMode.value) {
            StatDisplayMode.ACTIVE -> StatDisplayMode.TOTAL
            StatDisplayMode.TOTAL -> StatDisplayMode.ACTIVE
        }
    }
}