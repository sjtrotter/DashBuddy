package cloud.trotter.dashbuddy.ui.main.dashboard

import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #657 — the home screen is a **review / configure** surface, not a live mirror of the
 * bubble HUD. The ViewModel exposes the read-model economics for the user-selected
 * period (default Today, switchable via [DashboardViewModel.setPeriod]) and a registry-
 * resolved [DashboardUiState.isDashing] pointer — no live-ticking this-dash glance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val appStateRepository: AppStateRepository = mock()
    private val stateManager: StateManagerV2 = mock()
    private val analyticsRepository: AnalyticsRepository = mock()
    private val bubbleManager: BubbleManager = mock()

    /** Stub a specific period's read-model flow (platform arg defaults to null). */
    private fun stubPeriod(period: AnalyticsPeriod, economics: PeriodEconomics) {
        whenever(analyticsRepository.periodEconomics(eq(period), anyOrNull()))
            .thenReturn(flowOf(economics))
    }

    private fun onlineState(): AppState = AppState(
        regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = Platform.DoorDash),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    mode = Mode.Online,
                    session = Session(sessionId = "s1", startedAt = 1_000_000L),
                ),
            ),
        ),
    )

    private fun offlineState(): AppState = AppState(
        regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = Platform.DoorDash),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    mode = Mode.Offline,
                ),
            ),
        ),
    )

    private fun economics(net: Double, miles: Double, netPerHour: Double?): PeriodEconomics =
        PeriodEconomics(
            totals = PeriodTotals(earnings = net, miles = miles, deliveries = 1, jobs = 1, onlineDuration = 3_600_000L),
            grossEarnings = net,
            netProfit = net,
            unattributedPay = 0.0,
            netPerHour = netPerHour,
            netPerMile = null,
        )

    private fun buildViewModel() = DashboardViewModel(
        appStateRepository = appStateRepository,
        stateManager = stateManager,
        analyticsRepository = analyticsRepository,
        bubbleManager = bubbleManager,
    )

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default period is TODAY and its read-model economics map into state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val today = economics(net = 55.0, miles = 20.0, netPerHour = 27.5)
        whenever(stateManager.state).thenReturn(MutableStateFlow(onlineState()))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))
        stubPeriod(AnalyticsPeriod.TODAY, today)

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertEquals(AnalyticsPeriod.TODAY, ui.selectedPeriod)
        assertEquals(today, ui.economics)
        assertEquals(55.0, ui.economics.netProfit, 1e-9)
        job.cancel()
    }

    @Test
    fun `setPeriod switches the tiles to the selected window's economics`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val today = economics(net = 55.0, miles = 20.0, netPerHour = 27.5)
        val week = economics(net = 312.0, miles = 140.0, netPerHour = 24.0)
        whenever(stateManager.state).thenReturn(MutableStateFlow(onlineState()))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))
        stubPeriod(AnalyticsPeriod.TODAY, today)
        stubPeriod(AnalyticsPeriod.THIS_WEEK, week)

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        // Default window.
        assertEquals(today, viewModel.uiState.value.economics)

        viewModel.setPeriod(AnalyticsPeriod.THIS_WEEK)
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertEquals(AnalyticsPeriod.THIS_WEEK, ui.selectedPeriod)
        assertEquals(week, ui.economics)
        assertEquals(312.0, ui.economics.netProfit, 1e-9)
        job.cancel()
    }

    @Test
    fun `isDashing and statusText reflect the registry-resolved mode`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))
        stubPeriod(AnalyticsPeriod.TODAY, PeriodEconomics.EMPTY)

        // Online → dashing + "looking for offers".
        whenever(stateManager.state).thenReturn(MutableStateFlow(onlineState()))
        val onlineVm = buildViewModel()
        val onlineJob = launch { onlineVm.uiState.collect {} }
        testScheduler.advanceUntilIdle()
        assertTrue(onlineVm.uiState.value.isDashing)
        assertEquals(R.string.dashboard_status_looking_for_offers, onlineVm.uiState.value.statusText)
        onlineJob.cancel()

        // Offline → not dashing + "ready to start a session".
        whenever(stateManager.state).thenReturn(MutableStateFlow(offlineState()))
        val offlineVm = buildViewModel()
        val offlineJob = launch { offlineVm.uiState.collect {} }
        testScheduler.advanceUntilIdle()
        assertFalse(offlineVm.uiState.value.isDashing)
        assertEquals(R.string.dashboard_status_ready, offlineVm.uiState.value.statusText)
        offlineJob.cancel()
    }
}
