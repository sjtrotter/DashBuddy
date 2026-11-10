package cloud.trotter.log.gig.ui.main.analytics.month

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.RecapRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.recap.DayFacts
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import cloud.trotter.log.gig.domain.analytics.recap.ShiftWeeks
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.ui.main.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * #1515: the Month screen's ViewModel over mocked reads. The month and the switch live in saved state under the
 * route's keys; a week row writes Recap's one week selection.
 *
 * **Harness note** (as `AnalyticsViewModelTest`'s): the local-date flow sleeps until the next local midnight forever,
 * so settle with `runCurrent()`, never `advanceUntilIdle()`, and cancel the ViewModel's scope before the body ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonthViewModelTest {

    private val recapRepository: RecapRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val recordsStatus = MutableStateFlow(RecordsStatus.UP_TO_DATE)
    private val recordsStatusRepository: RecordsStatusRepository = mock { on { status } doReturn recordsStatus }
    private val appState = MutableStateFlow(AppState())
    private val stateManager: StateManagerV2 = mock { on { state } doReturn appState }
    private val selectionFlow = MutableStateFlow(AnalyticsWindowSelection.DEFAULT)
    private var failDays = false

    private val today: LocalDate get() = LocalDate.now()
    private val thisMonth: YearMonth get() = YearMonth.from(today)

    @Before
    fun stubRepositories() {
        whenever(runBlocking { appPreferencesRepository.setAnalyticsWindow(any()) }).thenAnswer { invocation ->
            selectionFlow.value = invocation.getArgument(0)
            Unit
        }
        // Every date that has happened has a $10 shift ($6 profit).
        whenever(recapRepository.days(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { invocation ->
            val window = invocation.getArgument<AnalyticsWindow>(0)
            if (failDays) return@thenAnswer flow<List<DayFacts>> { throw IllegalStateException("read failed") }
            val dates = generateSequence(window.startDate!!) { it.plusDays(1) }.takeWhile { it.isBefore(window.endDateExclusive!!) }
            flowOf(dates.filter { it.isBefore(today) }.map { date ->
                DayFacts(date, listOf(DayShift("s-$date", Platform.DoorDash, 0L, 1L, 10.0, 6.0, paySeen = true)))
            }.toList())
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.runWithViewModel(
        month: String? = thisMonth.toString(),
        measure: String? = null,
        block: suspend (MonthViewModel) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val args = listOfNotNull(month?.let { Screen.Month.ARG_MONTH to it }, measure?.let { Screen.Month.ARG_MEASURE to it })
        val viewModel = MonthViewModel(SavedStateHandle(args.toMap()), recapRepository, recordsStatusRepository,
            appPreferencesRepository, stateManager)
        val collector = launch { viewModel.uiState.collect {} }
        testScheduler.runCurrent()
        try {
            runBlocking { block(viewModel) }
        } finally {
            collector.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `it opens on the route's month and switch value`() = runTest {
        val last = thisMonth.minusMonths(1)
        runWithViewModel(month = last.toString(), measure = RecapMeasure.PROFIT.name) { viewModel ->
            val state = viewModel.uiState.value
            assertEquals(last, state.month)
            assertEquals(RecapMeasure.PROFIT, state.measure)
            assertEquals(last.lengthOfMonth(), state.days.size)
            assertEquals(last.lengthOfMonth() * 6.0, state.total, 1e-9)
            assertTrue(requireNotNull(state.comparison).wholeMonths)
            assertTrue(state.canStepForward)
        }
    }

    @Test
    fun `a future or unparsable month opens the current month`() = runTest {
        runWithViewModel(month = thisMonth.plusMonths(2).toString()) { viewModel ->
            assertEquals(thisMonth, viewModel.uiState.value.month)
        }
        runWithViewModel(month = "not a month") { viewModel ->
            assertEquals(thisMonth, viewModel.uiState.value.month)
            assertFalse(viewModel.uiState.value.canStepForward)
        }
    }

    @Test
    fun `month stepping stops at this month, and the picker moves the screen in place`() = runTest {
        runWithViewModel { viewModel ->
            viewModel.stepMonth(1)
            testScheduler.runCurrent()
            assertEquals(thisMonth, viewModel.uiState.value.month)
            viewModel.stepMonth(-1)
            testScheduler.runCurrent()
            assertEquals(thisMonth.minusMonths(1), viewModel.uiState.value.month)
            viewModel.selectMonth(thisMonth.plusMonths(1))
            testScheduler.runCurrent()
            assertEquals(thisMonth.minusMonths(1), viewModel.uiState.value.month)
            viewModel.selectMonth(thisMonth.minusYears(1))
            testScheduler.runCurrent()
            assertEquals(thisMonth.minusYears(1), viewModel.uiState.value.month)
            viewModel.setMeasure(RecapMeasure.PROFIT)
            testScheduler.runCurrent()
            assertEquals(RecapMeasure.PROFIT, viewModel.uiState.value.measure)
            // It never writes Recap's week by itself.
            assertEquals(AnalyticsWindowSelection.DEFAULT, selectionFlow.value)
        }
    }

    @Test
    fun `a week row writes Recap's week`() = runTest {
        runWithViewModel(month = thisMonth.minusMonths(2).toString()) { viewModel ->
            val row = viewModel.uiState.value.weeks[1]
            viewModel.selectWeek(row.monday)
            assertEquals(AnalyticsWindowSelection.weekOf(row.monday, today), selectionFlow.value)
            assertEquals(row.monday, selectionFlow.value.resolve(today).startDate)
            assertEquals(ShiftWeeks.weekStart(row.first), row.monday)
        }
    }

    @Test
    fun `the comparison waits while a shift runs`() = runTest {
        runWithViewModel(month = thisMonth.minusMonths(1).toString()) { viewModel ->
            assertNotNull(viewModel.uiState.value.comparison)
            appState.value = AppState(
                regions = Regions(platforms = mapOf(Platform.DoorDash to PlatformRegion(platform = Platform.DoorDash,
                    session = Session(sessionId = "live", startedAt = 1L)))),
            )
            testScheduler.runCurrent()
            assertNull(viewModel.uiState.value.comparison)
        }
    }

    @Test
    fun `the records status rides the state and a failed read shows the failure`() = runTest {
        recordsStatus.value = RecordsStatus.REBUILDING
        runWithViewModel { viewModel -> assertEquals(RecordsStatus.REBUILDING, viewModel.uiState.value.records) }
        failDays = true
        runWithViewModel { viewModel ->
            assertTrue(viewModel.uiState.value.failed)
            failDays = false
            viewModel.retry()
            testScheduler.runCurrent()
            assertFalse(viewModel.uiState.value.failed)
        }
    }
}
