package cloud.trotter.dashbuddy.ui.main.dashboard

import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #320 — the home glance must compute live True Net / $-per-hr from the session's
 * running earnings, session miles, and the SAME operating cost the offer verdict
 * uses (via the NetProfit SSOT), and stay platform-agnostic (registry-focused).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val appStateRepository: AppStateRepository = mock()
    private val odometerRepository: OdometerRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val stateManager: StateManagerV2 = mock()
    private val analyticsRepository: AnalyticsRepository = mock()
    private val bubbleManager: BubbleManager = mock()

    /** Stub the read-model Today flow; defaults to empty so a test opts in to real totals. */
    private fun stubToday(today: PeriodEconomics = PeriodEconomics.EMPTY) {
        whenever(analyticsRepository.periodEconomics(any(), anyOrNull())).thenReturn(flowOf(today))
    }

    /** $3.00/gal ÷ 30 mpg = $0.10/mi fuel; every non-fuel cost defaults to 0 → $0.10/mi total. */
    private val tenCentsPerMile = UserEconomy(
        vehicleClass = VehicleClass.SEDAN,
        vehicleMpg = 30.0,
        gasPricePerGallon = 3.0,
    )

    private fun appStateOnline(startedAt: Long, earnings: Double): AppState = AppState(
        regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = Platform.DoorDash),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    mode = Mode.Online,
                    session = Session(
                        sessionId = "s1",
                        startedAt = startedAt,
                        runningEarnings = earnings,
                    ),
                ),
            ),
        ),
    )

    private fun buildViewModel() = DashboardViewModel(
        appStateRepository = appStateRepository,
        odometerRepository = odometerRepository,
        appPreferencesRepository = appPreferencesRepository,
        stateManager = stateManager,
        analyticsRepository = analyticsRepository,
        bubbleManager = bubbleManager,
    )

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `glance computes trueNet and netPerHour from session, miles and economy`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val startedAt = 1_000_000L
        whenever(stateManager.state)
            .thenReturn(MutableStateFlow(appStateOnline(startedAt, earnings = 20.0)))
        whenever(odometerRepository.sessionMilesFlow).thenReturn(flowOf(8.0))
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(tenCentsPerMile))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))
        stubToday()

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val glance = viewModel.uiState.value.glance
        assertTrue(glance.isInSession)
        // trueNet = 20.00 − 8 mi × $0.10/mi = 19.20
        assertEquals(19.20, glance.trueNet, 1e-9)
        assertEquals(8.0, glance.miles, 1e-9)
        // one hour after start → $19.20 / 1 h = $19.20/hr
        assertEquals(19.20, glance.netPerHourAt(startedAt + 3_600_000L)!!, 1e-9)
        // before any time elapses the rate is undefined (null), not a fake $0/hr
        assertNull(glance.netPerHourAt(startedAt))

        assertTrue(viewModel.uiState.value.isInSession)
        assertEquals("Looking for offers...", viewModel.uiState.value.statusText)
        job.cancel()
    }

    @Test
    fun `offline yields an empty glance and not-in-session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val offline = AppState(
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
        whenever(stateManager.state).thenReturn(MutableStateFlow(offline))
        whenever(odometerRepository.sessionMilesFlow).thenReturn(flowOf(0.0))
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(tenCentsPerMile))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))
        stubToday()

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.isInSession)
        assertFalse(ui.glance.isInSession)
        assertEquals(DashGlance.EMPTY_VALUE, ui.glance.trueNetText)
        assertEquals("Ready to Dash", ui.statusText)
        job.cancel()
    }

    @Test
    fun `today read-model economics flow maps into the glance state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val startedAt = 1_000_000L
        whenever(stateManager.state)
            .thenReturn(MutableStateFlow(appStateOnline(startedAt, earnings = 20.0)))
        whenever(odometerRepository.sessionMilesFlow).thenReturn(flowOf(8.0))
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(tenCentsPerMile))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))

        // Real "Today" totals: frozen net independent of the live economy above.
        val todayEconomics = PeriodEconomics(
            totals = PeriodTotals(earnings = 60.0, miles = 20.0, deliveries = 4, jobs = 3, onlineDuration = 7_200_000L),
            grossEarnings = 72.0,
            netProfit = 55.0,
            unattributedPay = 12.0,
            netPerHour = 27.5,
            netPerMile = 2.75,
        )
        stubToday(todayEconomics)

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        // The read-model Today is exposed verbatim (frozen net, not the live-economy net).
        assertEquals(todayEconomics, viewModel.uiState.value.today)
        assertEquals(55.0, viewModel.uiState.value.today.netProfit, 1e-9)
        // The live "this dash" glance still computes its own separate net against the live economy.
        assertEquals(19.20, viewModel.uiState.value.glance.trueNet, 1e-9)
        job.cancel()
    }
}
