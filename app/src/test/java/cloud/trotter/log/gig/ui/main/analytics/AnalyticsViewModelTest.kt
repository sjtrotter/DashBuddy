package cloud.trotter.log.gig.ui.main.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.CorrectionRepository
import cloud.trotter.log.gig.core.data.analytics.LedgerRepository
import cloud.trotter.log.gig.core.data.analytics.RecapRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.ShiftChecksRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.analytics.checks.MoneyCheck
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.recap.DayFacts
import cloud.trotter.log.gig.domain.analytics.recap.DayMark
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1515: Recap's ViewModel over mocked reads. The persisted week round-trips through the mocked preferences (the
 * setter writes the flow the getter exposes, the DataStore-is-SSOT contract the ViewModel relies on).
 *
 * **Harness note.** The ViewModel resolves its week against the local-date flow, which sleeps until the next local
 * midnight forever. Under a `StandardTestDispatcher` that delay is virtual, so settle with `runCurrent()`, never
 * `advanceUntilIdle()`, and cancel the ViewModel's scope before the body ends ([runWithViewModel] does it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest {

    private val analyticsRepository: AnalyticsRepository = mock()
    private val recapRepository: RecapRepository = mock()
    private val shiftChecksRepository: ShiftChecksRepository = mock()
    private val ledgerRepository: LedgerRepository = mock()
    private val correctionRepository: CorrectionRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val recordsStatus = MutableStateFlow(RecordsStatus.UP_TO_DATE)
    private val recordsStatusRepository: RecordsStatusRepository = mock { on { status } doReturn recordsStatus }
    private val appState = MutableStateFlow(AppState())
    private val stateManager: StateManagerV2 = mock { on { state } doReturn appState }

    private val selectionFlow = MutableStateFlow(AnalyticsWindowSelection.DEFAULT)
    private val economicsByWindow = mutableMapOf<AnalyticsWindow, PeriodEconomics>()
    private val factsByWindow = mutableMapOf<AnalyticsWindow, List<DayFacts>>()
    private var checks: Map<String, List<ShiftCheck>> = emptyMap()
    private var anyShift = true
    private var failDays = false

    private val today: LocalDate get() = LocalDate.now()
    private fun thisWeek() = AnalyticsWindows.current(WindowGranularity.WEEK, today)
    private fun lastWeek() = AnalyticsWindows.step(thisWeek(), -1)

    @Before
    fun stubRepositories() {
        whenever(appPreferencesRepository.analyticsWindow).thenReturn(selectionFlow)
        whenever(runBlocking { appPreferencesRepository.setAnalyticsWindow(any()) }).thenAnswer { invocation ->
            selectionFlow.value = invocation.getArgument(0)
            Unit
        }
        whenever(analyticsRepository.periodEconomics(any<AnalyticsWindow>(), anyOrNull(), any<ZoneId>())).thenAnswer {
            flowOf(economicsByWindow[it.getArgument<AnalyticsWindow>(0)] ?: PeriodEconomics.EMPTY)
        }
        whenever(analyticsRepository.noSessionDeliveries(any<AnalyticsWindow>(), any<ZoneId>())).thenReturn(flowOf(emptyList()))
        whenever(recapRepository.days(any<AnalyticsWindow>(), any<ZoneId>())).thenAnswer { invocation ->
            val window = invocation.getArgument<AnalyticsWindow>(0)
            if (failDays) flow<List<DayFacts>> { throw IllegalStateException("read failed") }
            else flowOf(factsByWindow[window].orEmpty())
        }
        whenever(recapRepository.anyShift).thenAnswer { flowOf(anyShift) }
        whenever(shiftChecksRepository.checks(any<List<String>>())).thenAnswer { invocation ->
            val ids = invocation.getArgument<List<String>>(0)
            flowOf(checks.filterKeys { it in ids })
        }
        whenever(ledgerRepository.spending(any<AnalyticsWindow>(), any<ZoneId>())).thenReturn(flowOf(42.0))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.runWithViewModel(handle: SavedStateHandle = SavedStateHandle(), block: (AnalyticsViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = AnalyticsViewModel(
            analyticsRepository, recapRepository, shiftChecksRepository, ledgerRepository, correctionRepository,
            appPreferencesRepository, recordsStatusRepository, stateManager, handle,
        )
        val collector = launch { viewModel.uiState.collect {} }
        testScheduler.runCurrent()
        try {
            block(viewModel)
        } finally {
            collector.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

    private fun shift(id: String, earned: Double, profit: Double, ended: Boolean = true) =
        DayShift(id, Platform.DoorDash, startedAt = 0L, endedAt = if (ended) 1L else null, earned = earned, profit = profit, paySeen = true)

    /** A shift on every day of [week] that has happened, plus [extra]. */
    private fun seedWeek(week: AnalyticsWindow, earned: Double, profit: Double, extra: Map<LocalDate, List<DayShift>> = emptyMap()) {
        val start = week.startDate!!
        factsByWindow[week] = (0L..6L).map { start.plusDays(it) }.filter { !it.isAfter(today) }.map { date ->
            DayFacts(date, listOf(shift("s-$date", earned, profit)) + extra[date].orEmpty())
        }
    }

    private fun running() = AppState(
        regions = Regions(platforms = mapOf(Platform.DoorDash to PlatformRegion(platform = Platform.DoorDash, session = Session(sessionId = "live", startedAt = 1L)))),
    )

    @Test
    fun `the switch picks the measure and the total follows it`() = runTest {
        economicsByWindow[thisWeek()] = PeriodEconomics.EMPTY.copy(grossEarnings = 300.0, netProfit = 210.0)
        runWithViewModel { viewModel ->
            assertEquals(RecapMeasure.EARNED, viewModel.uiState.value.measure)
            assertEquals(300.0, viewModel.uiState.value.total, 1e-9)
            viewModel.setMeasure(RecapMeasure.PROFIT)
            testScheduler.runCurrent()
            assertEquals(RecapMeasure.PROFIT, viewModel.uiState.value.measure)
            assertEquals(210.0, viewModel.uiState.value.total, 1e-9)
        }
    }

    @Test
    fun `the switch survives in saved state`() = runTest {
        runWithViewModel(SavedStateHandle(mapOf(AnalyticsViewModel.KEY_MEASURE to RecapMeasure.PROFIT))) { viewModel ->
            assertEquals(RecapMeasure.PROFIT, viewModel.uiState.value.measure)
        }
    }

    @Test
    fun `a past week compares all seven days with the week before`() = runTest {
        selectionFlow.value = AnalyticsWindowSelection(-1)
        seedWeek(lastWeek(), earned = 20.0, profit = 12.0)
        seedWeek(AnalyticsWindows.step(lastWeek(), -1), earned = 10.0, profit = 6.0)
        runWithViewModel { viewModel ->
            val comparison = requireNotNull(viewModel.uiState.value.comparison)
            assertEquals(140.0, comparison.thisWeek, 1e-9)
            assertEquals(70.0, comparison.lastWeek, 1e-9)
            viewModel.setMeasure(RecapMeasure.PROFIT)
            testScheduler.runCurrent()
            assertEquals(84.0, viewModel.uiState.value.comparison!!.thisWeek, 1e-9)
        }
    }

    @Test
    fun `the comparison is hidden while a shift runs`() = runTest {
        selectionFlow.value = AnalyticsWindowSelection(-1)
        seedWeek(lastWeek(), earned = 20.0, profit = 12.0)
        runWithViewModel { viewModel ->
            assertNotNull(viewModel.uiState.value.comparison)
            appState.value = running()
            testScheduler.runCurrent()
            assertTrue(viewModel.uiState.value.shiftRunning)
            assertNull(viewModel.uiState.value.comparison)
            appState.value = AppState()
            testScheduler.runCurrent()
            assertNotNull(viewModel.uiState.value.comparison)
        }
    }

    @Test
    fun `shifts to check carry the reasons ShiftChecks gives, ended shifts only`() = runTest {
        val monday = thisWeek().startDate!!
        val reasons = listOf(
            ShiftCheck.NoDelivery(offerEventSequenceId = 7),
            ShiftCheck.MoneyDiffers(MoneyCheck.Higher.APP_TOTAL, 23.5),
        )
        factsByWindow[thisWeek()] = listOf(
            DayFacts(monday, listOf(shift("checked", 50.0, 30.0), shift("clean", 20.0, 12.0), shift("live", 5.0, 3.0, ended = false))),
        )
        checks = mapOf("checked" to reasons, "clean" to emptyList(), "live" to listOf(ShiftCheck.NoDelivery(9)))
        runWithViewModel { viewModel ->
            val toCheck = viewModel.uiState.value.shiftsToCheck
            assertEquals(listOf("checked"), toCheck.map { it.shift.sessionId })
            assertEquals(reasons, toCheck.single().checks)
            assertEquals(monday, toCheck.single().date)
        }
    }

    @Test
    fun `the period bar steps back and never past this week`() = runTest {
        runWithViewModel { viewModel ->
            assertEquals(thisWeek(), viewModel.uiState.value.week)
            assertFalse(viewModel.uiState.value.canStepForward)
            viewModel.stepWeek(1)
            testScheduler.runCurrent()
            assertEquals(AnalyticsWindowSelection.DEFAULT, selectionFlow.value)
            viewModel.stepWeek(-1)
            testScheduler.runCurrent()
            assertEquals(lastWeek(), viewModel.uiState.value.week)
            assertTrue(viewModel.uiState.value.canStepForward)
        }
    }

    @Test
    fun `no shift ever recorded is the no-shifts-yet state, and a week off is told apart`() = runTest {
        anyShift = false
        runWithViewModel { viewModel ->
            assertFalse(viewModel.uiState.value.anyShift)
        }
        anyShift = true
        runWithViewModel { viewModel ->
            val state = viewModel.uiState.value
            assertTrue(state.anyShift)
            assertTrue(state.weekOff)
            assertTrue(state.days.all { it.mark == DayMark.NO_SHIFT || it.mark == DayMark.AHEAD })
            assertEquals(42.0, state.spending, 1e-9)
        }
    }

    @Test
    fun `the records status rides the state and a failed read shows the failure`() = runTest {
        runWithViewModel { viewModel ->
            assertTrue(viewModel.uiState.value.loaded)
            recordsStatus.value = RecordsStatus.REBUILDING
            testScheduler.runCurrent()
            assertEquals(RecordsStatus.REBUILDING, viewModel.uiState.value.records)
            assertFalse(viewModel.uiState.value.figuresShown())
        }
        recordsStatus.value = RecordsStatus.REBUILDING
        failDays = true
        runWithViewModel { viewModel ->
            assertTrue(viewModel.uiState.value.failed)
            assertEquals("a failure mid-rebuild keeps the real status", RecordsStatus.REBUILDING, viewModel.uiState.value.records)
            failDays = false
            viewModel.retry()
            testScheduler.runCurrent()
            assertFalse(viewModel.uiState.value.failed)
        }
    }
}
