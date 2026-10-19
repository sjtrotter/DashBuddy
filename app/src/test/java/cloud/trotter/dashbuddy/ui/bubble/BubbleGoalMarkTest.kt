package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.LedgerRepository
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.feature.bubble.hud.MarkRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class BubbleGoalMarkTest {
    private val dispatcher = StandardTestDispatcher()
    private val marks = MutableSharedFlow<String?>(replay = 1)
    private val appState: AppStateRepository = mock()
    private val analytics: AnalyticsRepository = mock()
    private val preferences: AppPreferencesRepository = mock()
    private val state: StateManagerV2 = mock()
    private val strategy: StrategyRepository = mock()
    private val events: AppEventRepo = mock()
    private val odometer: OdometerRepository = mock()
    private val dev: DevSettingsRepository = mock()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        whenever(appState.goalMetMark).thenReturn(marks)
        whenever(analytics.recentSessions(any())).thenReturn(flowOf(emptyList()))
        whenever(analytics.periodEconomics(AnalyticsPeriod.TODAY)).thenReturn(flowOf(PeriodEconomics.EMPTY))
        whenever(state.state).thenReturn(MutableStateFlow(AppState()))
        whenever(strategy.evaluationConfig).thenReturn(MutableStateFlow<EvaluationConfig?>(null))
        whenever(events.getMostRecentSessionId()).thenReturn(flowOf(null))
        whenever(odometer.sessionMilesFlow).thenReturn(flowOf(0.0))
        whenever(dev.bubbleSessionMode).thenReturn(flowOf(BubbleSessionMode.Default))
        whenever(preferences.glanceMode).thenReturn(flowOf(false))
        whenever(preferences.countRealExpensesInKept).thenReturn(flowOf(false))
        whenever(preferences.dailyKeptGoalDollars).thenReturn(flowOf(null))
        whenever(preferences.gasPrice).thenReturn(flowOf(null))
        whenever(preferences.isGasPriceAuto).thenReturn(flowOf(true))
    }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun model() = BubbleViewModel(
        chatRepository = mock<ChatRepository>(), odometerRepository = odometer, stateManager = state,
        appEventRepo = events, appPreferencesRepository = preferences, fuelPriceRepository = mock<FuelPriceRepository>(),
        analyticsRepository = analytics, ledgerRepository = mock<LedgerRepository>(),
        devSettingsRepository = dev, strategyRepository = strategy, appStateRepository = appState,
    )
    @Test fun goalMark_staysLoadingUntilStoreAnswers() = runTest(dispatcher) {
        val vm = model()
        val read = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.goalMark.collect {} }
        runCurrent()
        assertEquals(MarkRead.Loading, vm.goalMark.value)
        marks.emit(null); runCurrent()
        assertEquals(MarkRead.Loaded(null), vm.goalMark.value)
        read.cancel()
    }
    @Test fun goalCelebrated_pendingBeatsOldPersistedMarkBeforeWriteFinishes() = runTest(dispatcher) {
        val releaseWrite = CompletableDeferred<Unit>()
        // Suspending the repository write models slow DataStore I/O.
        doSuspendableAnswer { releaseWrite.await() }.whenever(appState).setGoalMetMark(any())
        marks.emit("yesterday|7900")
        val vm = model()
        val read = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.goalMark.collect {} }
        runCurrent()
        vm.goalCelebrated("today|7900"); runCurrent()
        assertEquals(MarkRead.Loaded("today|7900"), vm.goalMark.value)
        vm.goalCelebrated("today|7900"); runCurrent()
        verify(appState, times(1)).setGoalMetMark("today|7900")
        marks.emit("yesterday|8000"); runCurrent()
        assertEquals(MarkRead.Loaded("today|7900"), vm.goalMark.value)
        releaseWrite.complete(Unit); runCurrent()
        marks.emit("today|7900"); runCurrent()
        assertEquals(MarkRead.Loaded("today|7900"), vm.goalMark.value)
        read.cancel()
    }
    @Test fun goalMark_recreatedViewModelUsesDurableMark() = runTest(dispatcher) {
        marks.emit("2026-10-18|7900")
        val vm = model()
        val read = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.goalMark.collect {} }
        runCurrent()
        assertEquals(MarkRead.Loaded("2026-10-18|7900"), vm.goalMark.value)
        verify(appState, never()).setGoalMetMark(any())
        read.cancel()
    }
}
