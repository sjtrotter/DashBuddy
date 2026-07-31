package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.WeeklyPlanRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmapCalculator
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmapCell
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

/**
 * #977 (redesign stage 4) — Home is **"Today"**: the ViewModel serves the rolling `TODAY` economics,
 * the lifetime hour×day heatmap the plan strip reads, and the current pay week's trio (economics /
 * previous week / per-day net) plus its orphan-offer groups, all from the existing read-model.
 *
 * The registry-resolved status vocabulary (#657) is unchanged and still asserted here.
 *
 * **Harness note — the midnight re-anchor is an unbounded virtual-time loop.** Since #977 this
 * ViewModel resolves its week window against `currentLocalDateFlow`, which emits and then sleeps
 * until the next local midnight, forever. Under a `StandardTestDispatcher` that delay is *virtual*,
 * so anything that drains the scheduler spins through midnight after midnight and never runs out of
 * work — the same hazard `AnalyticsViewModelTest` documents (a 12-minute CI hang was its receipt).
 * Two consequences, both load-bearing:
 *  1. settle with **`runCurrent()`**, never `advanceUntilIdle()` — it drains everything scheduled at
 *     the current instant (which is every real emission) and leaves the re-anchor pending;
 *  2. **cancel the ViewModel's scope before the test body ends** ([runWithViewModel] does it),
 *     because `runTest` finishes with its OWN `advanceUntilIdle()` and `viewModelScope` is not a
 *     child of the test scope — an uncancelled Home would stall that teardown instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val appStateRepository: AppStateRepository = mock()
    private val stateManager: StateManagerV2 = mock()
    private val analyticsRepository: AnalyticsRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val bubbleManager: BubbleManager = mock()
    private val weeklyPlanRepository: WeeklyPlanRepository = mock()

    /** The plan the stubbed repository serves for the current week; null ⇒ Home renders no pointer. */
    private var savedPlan: SavedWeeklyPlan? = null

    private val today: LocalDate get() = LocalDate.now()
    private val thisWeek: AnalyticsWindow get() = AnalyticsWindows.current(WindowGranularity.WEEK, today)
    private val lastWeek: AnalyticsWindow get() = AnalyticsWindows.step(thisWeek, -1)

    /** Per-window economics the stubbed repository serves; anything unlisted reads EMPTY. */
    private val economicsByWindow = mutableMapOf<AnalyticsWindow, PeriodEconomics>()
    private var todayEconomics: PeriodEconomics = PeriodEconomics.EMPTY
    private var heatmap: EarningsHeatmap = EarningsHeatmap.EMPTY
    private var dailyEarnings: List<DailyEarnings> = emptyList()
    private var orphanOfferGroups: List<OrphanOfferGroup> = emptyList()

    /** The persisted analytics selection, round-tripped by the mocked prefs repository. */
    private val selectionFlow = MutableStateFlow<AnalyticsWindowSelection>(
        AnalyticsWindowSelection.Relative(WindowGranularity.MONTH, -3),
    )

    private fun stubRepositories() {
        whenever(analyticsRepository.periodEconomics(eq(AnalyticsPeriod.TODAY), anyOrNull()))
            .thenAnswer { flowOf(todayEconomics) }
        whenever(analyticsRepository.periodEconomics(any<AnalyticsWindow>(), anyOrNull(), any<ZoneId>()))
            .thenAnswer { invocation ->
                flowOf(economicsByWindow[invocation.getArgument<AnalyticsWindow>(0)] ?: PeriodEconomics.EMPTY)
            }
        whenever(analyticsRepository.earningsHeatmap(any())).thenAnswer { flowOf(heatmap) }
        whenever(analyticsRepository.dailyEarnings(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(dailyEarnings) }
        whenever(analyticsRepository.orphanOfferGroups(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(orphanOfferGroups) }
        // #981 — the weekly-plan pointer row's source. No saved plan by default, which is the
        // state every pre-#981 assertion here was written against.
        whenever(weeklyPlanRepository.planFor(any())).thenAnswer { flowOf(savedPlan) }
        whenever(appPreferencesRepository.analyticsWindow).thenReturn(selectionFlow)
        whenever(runBlocking { appPreferencesRepository.setAnalyticsWindow(any()) }).thenAnswer { invocation ->
            selectionFlow.value = invocation.getArgument(0)
            Unit
        }
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
                Platform.DoorDash to PlatformRegion(platform = Platform.DoorDash, mode = Mode.Offline),
            ),
        ),
    )

    private fun economics(net: Double, miles: Double = 0.0, netPerHour: Double? = null): PeriodEconomics =
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
        appPreferencesRepository = appPreferencesRepository,
        bubbleManager = bubbleManager,
        weeklyPlanRepository = weeklyPlanRepository,
    )

    /**
     * Build + collect + settle with `runCurrent()`, then cancel — see the harness note above. Never
     * `advanceUntilIdle()`: the midnight re-anchor makes the virtual scheduler infinitely busy.
     */
    private fun TestScope.runWithViewModel(block: (DashboardViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val viewModel = buildViewModel()
        val collector = launch { viewModel.uiState.collect {} }
        testScheduler.runCurrent()
        try {
            block(viewModel)
        } finally {
            collector.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `today, this week and last week each land in their own field`() = runTest {
        whenever(stateManager.state).thenReturn(MutableStateFlow(onlineState()))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))
        todayEconomics = economics(net = 55.0, miles = 20.0, netPerHour = 27.5)
        economicsByWindow[thisWeek] = economics(net = 312.0, miles = 140.0, netPerHour = 24.0)
        economicsByWindow[lastWeek] = economics(net = 260.0, miles = 130.0, netPerHour = 21.0)

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals(today, ui.today)
            assertEquals(55.0, ui.todayEconomics.netProfit, 1e-9)
            assertEquals(312.0, ui.weekEconomics.netProfit, 1e-9)
            assertEquals(260.0, ui.previousWeekEconomics!!.netProfit, 1e-9)
        }
    }

    /** The plan strip's only source — served straight through, lifetime-scoped, no window filter. */
    @Test
    fun `the lifetime heatmap reaches the state for the plan strip`() = runTest {
        whenever(stateManager.state).thenReturn(MutableStateFlow(onlineState()))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))
        heatmap = heatmapWithMondayEvening()

        runWithViewModel { viewModel ->
            val cell = viewModel.uiState.value.heatmap.cell(0, 18)
            assertEquals(22.0, cell.dollarsPerHour!!, 1e-9)
        }
    }

    /** The week block's sparkline + the review row's other input both ride the week fan-out. */
    @Test
    fun `the week's per-day net and orphan offers reach the state`() = runTest {
        whenever(stateManager.state).thenReturn(MutableStateFlow(onlineState()))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))
        dailyEarnings = (0..6).map {
            DailyEarnings(date = thisWeek.startDate!!.plusDays(it.toLong()), gross = 10.0 * it, net = 6.0 * it)
        }
        orphanOfferGroups = listOf(
            OrphanOfferGroup(jobId = "job-3", sessionId = "s1", orphansOwed = 1, offers = emptyList()),
        )

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals(7, ui.weekDailyEarnings.size)
            assertEquals(1, ui.orphanOfferGroups.size)
            assertEquals("job-3", ui.orphanOfferGroups.first().jobId)
        }
    }

    /**
     * `Recap →` anchors the hub on THIS pay week before the caller navigates — and stores it as a
     * *relative* selection, so coming back tomorrow still means "this week" rather than a frozen span.
     */
    @Test
    fun `selecting the recap window persists the current week relatively`() = runTest {
        whenever(stateManager.state).thenReturn(MutableStateFlow(offlineState()))
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))

        runWithViewModel { viewModel ->
            launch { viewModel.selectWeekRecapWindow() }
            testScheduler.runCurrent()

            assertEquals(
                AnalyticsWindowSelection.Relative(WindowGranularity.WEEK, 0),
                selectionFlow.value,
            )
        }
    }

    @Test
    fun `isDashing and statusText reflect the registry-resolved mode`() = runTest {
        whenever(appStateRepository.isFirstRun).thenReturn(flowOf(false))

        whenever(stateManager.state).thenReturn(MutableStateFlow(onlineState()))
        runWithViewModel { viewModel ->
            assertTrue(viewModel.uiState.value.isDashing)
            assertEquals(R.string.dashboard_status_looking_for_offers, viewModel.uiState.value.statusText)
        }

        whenever(stateManager.state).thenReturn(MutableStateFlow(offlineState()))
        runWithViewModel { viewModel ->
            assertFalse(viewModel.uiState.value.isDashing)
            assertEquals(R.string.dashboard_status_ready, viewModel.uiState.value.statusText)
        }
    }

    /** A Monday-evening heatmap: hour 18 carries a $22/hr cell, everything else is masked. */
    private fun heatmapWithMondayEvening(): EarningsHeatmap {
        val cells = List(EarningsHeatmap.DAYS * EarningsHeatmap.HOURS) { index ->
            val day = index / EarningsHeatmap.HOURS
            val hour = index % EarningsHeatmap.HOURS
            val lit = day == 0 && hour == 18
            EarningsHeatmapCell(
                dayIndex = day,
                hour = hour,
                netDollars = if (lit) 22.0 else 0.0,
                coverageHours = if (lit) 1.0 else 0.0,
                dollarsPerHour = if (lit) 22.0 else null,
            )
        }
        return EarningsHeatmap(cells, EarningsHeatmapCalculator.DEFAULT_MIN_COVERAGE_HOURS)
    }
}
