package cloud.trotter.dashbuddy.ui.bubble.fragments.dashhistory.common

//import cloud.trotter.dashbuddy.log.Logger
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate

class DashStateViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tag = "DashStateViewModel"

    companion object {
        const val VIEW_TYPE_KEY = "currentViewType"
        const val DATE_KEY = "selectedFullDate" // New Atomic Key
        const val STAT_MODE_KEY = "statDisplayMode"

        val REFERENCE_MONTH_DATE: LocalDate = LocalDate.of(2020, 1, 1)
        val REFERENCE_DAY_DATE: LocalDate = LocalDate.of(2020, 1, 1)
    }

    // --- 1. THE SINGLE SOURCE OF TRUTH ---
    // Stores the date as a Long (Epoch Day) to be crash-proof and lightweight
    private val _selectedDateEpoch = savedStateHandle.getStateFlow(
        DATE_KEY,
        LocalDate.now().toEpochDay()
    )

    // Exposed as a useful LocalDate object for everyone to observe
    val selectedDate: StateFlow<LocalDate> = _selectedDateEpoch
        .map { LocalDate.ofEpochDay(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LocalDate.now()
        )

    // --- 2. OPTIMIZED SUB-FLOWS (Distinct) ---
    // These only emit when the specific part changes, preventing unnecessary reloads

    // Annual View ONLY updates when this changes
    val selectedYear: StateFlow<Int> = selectedDate
        .map { it.year }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LocalDate.now().year)

    // Monthly View ONLY updates when this changes
    val selectedMonth: StateFlow<Int> = selectedDate
        .map { it.monthValue }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LocalDate.now().monthValue)

    // Daily View updates when this changes
    val selectedDay: StateFlow<Int> = selectedDate
        .map { it.dayOfMonth }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LocalDate.now().dayOfMonth)

    // --- 3. EXISTING STATE ---
    val currentViewType: StateFlow<cloud.trotter.dashbuddy.ui.bubble.fragments.dashhistory.common.HistoryViewType> =
        savedStateHandle.getStateFlow(
            VIEW_TYPE_KEY,
            _root_ide_package_.cloud.trotter.dashbuddy.ui.bubble.fragments.dashhistory.common.HistoryViewType.ANNUAL
        )
    val statDisplayMode: StateFlow<cloud.trotter.dashbuddy.ui.bubble.fragments.dashhistory.common.StatDisplayMode> =
        savedStateHandle.getStateFlow(
            STAT_MODE_KEY,
            _root_ide_package_.cloud.trotter.dashbuddy.ui.bubble.fragments.dashhistory.common.StatDisplayMode.ACTIVE
        )
    private val _swipeEvent =
        MutableSharedFlow<cloud.trotter.dashbuddy.ui.bubble.fragments.dashhistory.common.SwipeDirection>()
    val swipeEvent = _swipeEvent.asSharedFlow()

    // --- 4. ATOMIC UPDATE FUNCTIONS ---

    fun onDailyPageSwiped(position: Int) {
        val dayOffset = position - 10000
        val newDate = REFERENCE_DAY_DATE.plusDays(dayOffset.toLong())
        updateDateAtomically(newDate)
    }

    fun onMonthPageSwiped(position: Int) {
        val monthOffset = position - 10000
        // When swiping months, we default to the 1st of that month to avoid overflow
        val newDateBase = REFERENCE_MONTH_DATE.plusMonths(monthOffset.toLong())
        // Preserve the current day IF valid, otherwise reset to 1st.
        // Resetting to 1st is safest for pure month navigation.
        val newDate = newDateBase.withDayOfMonth(1)
        updateDateAtomically(newDate)
    }

    fun onYearPageSwiped(year: Int) {
        val currentDate = selectedDate.value
        if (year != currentDate.year) {
            val newDate = try {
                currentDate.withYear(year)
            } catch (_: Exception) {
                // Handle leap year edge case (Feb 29 -> non-leap year)
                currentDate.withYear(year).withDayOfMonth(28)
            }
            updateDateAtomically(newDate)
        }
    }

    // Helper to perform the actual update
    private fun updateDateAtomically(newDate: LocalDate) {
        if (newDate.toEpochDay() != _selectedDateEpoch.value) {
            Timber.i("Atomic update to date: $newDate")
            savedStateHandle[DATE_KEY] = newDate.toEpochDay()
        }
    }

    // --- Navigation Helpers (Updated to use Atomic logic) ---

    fun navigateToToday() {
        updateDateAtomically(LocalDate.now())
        savedStateHandle[VIEW_TYPE_KEY] = HistoryViewType.DAILY
    }

    fun selectMonth(month: Int) {
        // When selecting via menu/list, handle the "Sept 31" crash case
        val current = selectedDate.value
        val maxDays = java.time.YearMonth.of(current.year, month).lengthOfMonth()
        val safeDay = if (current.dayOfMonth > maxDays) 1 else current.dayOfMonth

        val newDate = LocalDate.of(current.year, month, safeDay)
        updateDateAtomically(newDate)
        savedStateHandle[VIEW_TYPE_KEY] = HistoryViewType.MONTHLY
    }

    fun selectDay(day: Int) {
        val current = selectedDate.value
        val newDate = current.withDayOfMonth(day)
        updateDateAtomically(newDate)
        savedStateHandle[VIEW_TYPE_KEY] = HistoryViewType.DAILY
    }

    fun navigateUp() {
        val newViewType = when (currentViewType.value) {
            HistoryViewType.DAILY -> HistoryViewType.MONTHLY
            HistoryViewType.MONTHLY -> HistoryViewType.ANNUAL
            else -> return
        }
        savedStateHandle[VIEW_TYPE_KEY] = newViewType
    }

    fun onNextClicked() {
        viewModelScope.launch { _swipeEvent.emit(SwipeDirection.NEXT) }
    }

    fun onPreviousClicked() {
        viewModelScope.launch { _swipeEvent.emit(SwipeDirection.PREVIOUS) }
    }

    fun toggleStatMode() {
        savedStateHandle[STAT_MODE_KEY] = when (statDisplayMode.value) {
            StatDisplayMode.ACTIVE -> StatDisplayMode.TOTAL
            StatDisplayMode.TOTAL -> StatDisplayMode.ACTIVE
        }
    }
}