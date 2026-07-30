package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

/**
 * #315 H1 / #970 — the Analytics hub ViewModel exposes the read-model economics for the selected
 * **window** (default: the current pay week, paged by `stepWindow` and re-shaped by
 * `setGranularity`/`setCustomRange`) on the selected tab (default [AnalyticsTab.Money], switchable
 * via [AnalyticsViewModel.setTab]), strictly over the existing repository surface.
 *
 * The window selection round-trips through [AppPreferencesRepository] — the mock's setter writes
 * into the same flow its getter exposes, which is exactly the DataStore-is-SSOT contract the
 * ViewModel relies on (it holds no local copy). Same stub-flow pattern as DashboardViewModelTest.
 *
 * **Harness note — the midnight re-anchor is an unbounded virtual-time loop.** The ViewModel
 * resolves its window against the local-date flow, which (like `periodBoundariesFlow`) emits and
 * then sleeps until the next local midnight, forever. Under a `StandardTestDispatcher` that delay is
 * *virtual*, so anything that tries to drain the scheduler spins through midnight after midnight and
 * never runs out of work. Two consequences, both load-bearing — a 12-minute CI hang was the receipt:
 *  1. settle with **`runCurrent()`**, never `advanceUntilIdle()` — it drains everything scheduled at
 *     the current instant (which is every real emission) and leaves the re-anchor pending;
 *  2. **cancel the ViewModel's scope before the test body ends** ([runWithViewModel] does it), because
 *     `runTest` finishes with its OWN `advanceUntilIdle()` and `viewModelScope` is not a child of the
 *     test scope — an uncancelled hub would stall that teardown instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest {

    private val analyticsRepository: AnalyticsRepository = mock()
    private val correctionRepository: cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()

    /** The persisted selection, round-tripped by the mocked prefs repository. */
    private val selectionFlow = MutableStateFlow(AnalyticsWindowSelection.DEFAULT)

    /** Per-window economics the stubbed repository serves; anything unlisted reads EMPTY. */
    private val economicsByWindow = mutableMapOf<AnalyticsWindow, PeriodEconomics>()

    /** Per-window top stores; anything unlisted reads empty. */
    private val storesByWindow = mutableMapOf<AnalyticsWindow, List<StoreEconomics>>()

    private var decisions: DecisionEconomics = DecisionEconomics.EMPTY
    private var dailyEarnings: List<DailyEarnings> = emptyList()

    private val today: LocalDate get() = LocalDate.now()

    /** Every repository read the VM collects is window-shaped now, so one stub block covers them all. */
    @Before
    fun stubRepositories() {
        whenever(appPreferencesRepository.analyticsWindow).thenReturn(selectionFlow)
        // The setter writes into the SAME flow the getter exposes — the DataStore-is-SSOT contract
        // the ViewModel depends on (it keeps no local copy of the selection).
        whenever(runBlocking { appPreferencesRepository.setAnalyticsWindow(any()) }).thenAnswer { invocation ->
            selectionFlow.value = invocation.getArgument(0)
            Unit
        }

        whenever(analyticsRepository.periodEconomics(any<AnalyticsWindow>(), anyOrNull(), any<ZoneId>()))
            .thenAnswer { invocation ->
                flowOf(economicsByWindow[invocation.getArgument<AnalyticsWindow>(0)] ?: PeriodEconomics.EMPTY)
            }
        whenever(analyticsRepository.perStoreEconomics(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { invocation ->
                flowOf(storesByWindow[invocation.getArgument<AnalyticsWindow>(0)] ?: emptyList<StoreEconomics>())
            }
        whenever(analyticsRepository.decisionEconomics(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(decisions) }
        whenever(analyticsRepository.timeEconomics(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenReturn(flowOf(TimeEconomics.EMPTY))
        whenever(analyticsRepository.dailyEarnings(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(dailyEarnings) }
        whenever(analyticsRepository.noSessionDeliveries(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenReturn(flowOf(emptyList()))
        whenever(analyticsRepository.orphanOfferGroups(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenReturn(flowOf(emptyList()))
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        // The Patterns-tab sources (#315 H5) are LIFETIME-scoped and collected unconditionally in
        // the combine, so they must always be stubbed or the combine folds a null Flow.
        whenever(analyticsRepository.storeReportCards()).thenReturn(flowOf(emptyList<StoreReportCard>()))
        whenever(analyticsRepository.earningsHeatmap(anyOrNull())).thenReturn(flowOf(EarningsHeatmap.EMPTY))
    }

    private fun currentWeek() = AnalyticsWindows.current(WindowGranularity.WEEK, today)
    private fun lastWeek() = AnalyticsWindows.step(currentWeek(), -1)
    private fun currentMonth() = AnalyticsWindows.current(WindowGranularity.MONTH, today)

    private fun decisions(accepted: Int, declined: Int, timedOut: Int, acceptanceRate: Double?) =
        DecisionEconomics(
            received = accepted + declined + timedOut,
            accepted = accepted,
            declined = declined,
            timedOut = timedOut,
            acceptanceRate = acceptanceRate,
            declinedEstNet = 12.5,
            avgScoreAccepted = 0.8,
            avgScoreDeclined = 0.2,
            avgEstPerHourAccepted = 22.0,
            avgEstPerHourDeclined = 7.0,
        )

    private fun stubSessions(sessions: List<SessionRecord>) {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(sessions))
    }

    private fun economics(net: Double, netPerHour: Double?, unattributed: Double = 0.0): PeriodEconomics =
        PeriodEconomics(
            totals = PeriodTotals(earnings = net, miles = 20.0, deliveries = 3, jobs = 2, onlineDuration = 3_600_000L),
            grossEarnings = net + unattributed,
            netProfit = net,
            unattributedPay = unattributed,
            netPerHour = netPerHour,
            netPerMile = null,
        )

    private fun store(name: String, net: Double, deliveries: Int) =
        StoreEconomics(storeName = name, net = net, gross = net, deliveries = deliveries)

    private fun storeReportCard(chainDisplay: String) = StoreReportCard(
        storeKey = "doordash|${chainDisplay.lowercase()}|02426",
        platform = "doordash",
        normalizedChain = chainDisplay.lowercase(),
        chainDisplay = chainDisplay,
        runningKey = "02426",
        address = "123 Main St",
        locationKnown = true,
        pickups = 4,
        deliveries = 6,
        gross = 88.0,
        net = 61.0,
        avgDwellMillis = 300_000.0,
        p50DwellMillis = 280_000L,
        p95DwellMillis = 540_000L,
        firstSeenAt = 1_700_000_000_000L,
        lastSeenAt = 1_700_100_000_000L,
    )

    private fun session(id: String, reported: Double?) = SessionRecord(
        sessionId = id, platform = Platform.DoorDash, startedAt = 1_700_000_000_000L, endedAt = null,
        reportedEarnings = reported, reportedDurationMillis = null, miles = 10.0,
        deliveries = 2, jobsCompleted = 2, offersReceived = 4, offersAccepted = 2,
        offersDeclined = 2, offersTimeout = 0,
    )

    private fun buildViewModel() =
        AnalyticsViewModel(analyticsRepository, correctionRepository, appPreferencesRepository)


    /**
     * Build the ViewModel, subscribe to [AnalyticsViewModel.uiState], settle, run [block], then tear
     * BOTH down. Cancelling `viewModelScope` is not optional — see the harness note in the class
     * KDoc: `runTest`'s own teardown drains the scheduler, and the hub's midnight re-anchor flow
     * never runs dry while its scope is alive.
     */
    private fun TestScope.runWithViewModel(block: (AnalyticsViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
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
    fun `defaults to the current pay week on the Money tab and maps the read-model into state`() = runTest {
        economicsByWindow[currentWeek()] = economics(net = 312.0, netPerHour = 24.0)
        storesByWindow[currentWeek()] = listOf(store("H-E-B", 120.0, 5), store("Chili's", 40.0, 2))
        stubSessions(listOf(session("s1", 90.0), session("s2", null)))

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals(currentWeek(), ui.window)
            assertEquals(WindowGranularity.WEEK, ui.window.granularity)
            assertEquals(AnalyticsTab.Money, ui.selectedTab)
            assertEquals(312.0, ui.economics.netProfit, 1e-9)
            assertEquals(2, ui.topStores.size)
            assertEquals(2, ui.recentSessions.size)
        }
    }

    /** The `›` arrow must be dead at the current window and live once the pager has moved back. */
    @Test
    fun `forward paging is disabled at the current window and enabled after stepping back`() = runTest {
        runWithViewModel { viewModel ->
            assertTrue(viewModel.uiState.value.canStepBack)
            assertFalse(viewModel.uiState.value.canStepForward)

            viewModel.stepWindow(-1)
            testScheduler.runCurrent()

            assertEquals(lastWeek(), viewModel.uiState.value.window)
            assertTrue(viewModel.uiState.value.canStepForward)
        }
    }

    /** A forward step at the current window is a fail-closed no-op — never a future range. */
    @Test
    fun `stepping forward at the current window is ignored`() = runTest {
        runWithViewModel { viewModel ->
            viewModel.stepWindow(1)
            testScheduler.runCurrent()

            assertEquals(currentWeek(), viewModel.uiState.value.window)
            assertEquals(AnalyticsWindowSelection.Relative(WindowGranularity.WEEK, 0), selectionFlow.value)
        }
    }

    @Test
    fun `stepWindow re-anchors economics and top stores to the paged window`() = runTest {
        economicsByWindow[currentWeek()] = economics(net = 312.0, netPerHour = 24.0)
        storesByWindow[currentWeek()] = listOf(store("H-E-B", 120.0, 5))
        economicsByWindow[lastWeek()] = economics(net = 1280.0, netPerHour = 22.0)
        storesByWindow[lastWeek()] = listOf(store("Target", 400.0, 12))

        runWithViewModel { viewModel ->
            assertEquals(312.0, viewModel.uiState.value.economics.netProfit, 1e-9)

            viewModel.stepWindow(-1)
            testScheduler.runCurrent()

            val ui = viewModel.uiState.value
            assertEquals(lastWeek(), ui.window)
            assertEquals(1280.0, ui.economics.netProfit, 1e-9)
            assertEquals("Target", ui.topStores.single().storeName)
        }
    }

    @Test
    fun `setGranularity switches to the current window of that granularity`() = runTest {
        economicsByWindow[currentMonth()] = economics(net = 2100.0, netPerHour = 21.0)

        runWithViewModel { viewModel ->
            viewModel.setGranularity(WindowGranularity.MONTH)
            testScheduler.runCurrent()

            val ui = viewModel.uiState.value
            assertEquals(currentMonth(), ui.window)
            assertEquals(2100.0, ui.economics.netProfit, 1e-9)
            // Landing on a granularity always lands on ITS current window, so forward stays disabled.
            assertFalse(ui.canStepForward)
        }
    }

    /** A committed calendar range persists as an absolute CUSTOM selection. */
    @Test
    fun `setCustomRange commits an absolute window and persists it`() = runTest {
        val start = today.minusDays(10)
        val end = today.minusDays(4)
        economicsByWindow[AnalyticsWindows.custom(start, end)] = economics(net = 77.0, netPerHour = 11.0)

        runWithViewModel { viewModel ->
            viewModel.setCustomRange(start, end)
            testScheduler.runCurrent()

            val ui = viewModel.uiState.value
            assertEquals(WindowGranularity.CUSTOM, ui.window.granularity)
            assertEquals(start, ui.window.startDate)
            assertEquals(end, ui.window.endDateInclusive)
            assertEquals(77.0, ui.economics.netProfit, 1e-9)
            assertEquals(AnalyticsWindowSelection.Custom(start, end), selectionFlow.value)
        }
    }

    /** A persisted selection is restored on construction — the across-restart half of the contract. */
    @Test
    fun `a persisted selection is restored instead of the default`() = runTest {
        selectionFlow.value = AnalyticsWindowSelection.Relative(WindowGranularity.WEEK, -2)
        val twoWeeksBack = AnalyticsWindows.step(currentWeek(), -2)
        economicsByWindow[twoWeeksBack] = economics(net = 42.0, netPerHour = 9.0)

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals(twoWeeksBack, ui.window)
            assertEquals(42.0, ui.economics.netProfit, 1e-9)
        }
    }

    /** The recap hero's delta source: the previous equivalent window's economics ride the same fan-out. */
    @Test
    fun `previous-window economics are exposed for the delta chip`() = runTest {
        economicsByWindow[currentWeek()] = economics(net = 300.0, netPerHour = 20.0)
        economicsByWindow[lastWeek()] = economics(net = 200.0, netPerHour = 15.0)

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals(300.0, ui.economics.netProfit, 1e-9)
            assertEquals(200.0, ui.previousEconomics!!.netProfit, 1e-9)
        }
    }

    /** Lifetime has no predecessor, so the hero must get a null rather than a fabricated comparison. */
    @Test
    fun `lifetime exposes no previous-window economics`() = runTest {
        economicsByWindow[AnalyticsWindows.LIFETIME] = economics(net = 9000.0, netPerHour = 19.0)

        runWithViewModel { viewModel ->
            viewModel.setGranularity(WindowGranularity.LIFETIME)
            testScheduler.runCurrent()

            val ui = viewModel.uiState.value
            assertTrue(ui.window.isLifetime)
            assertEquals(9000.0, ui.economics.netProfit, 1e-9)
            assertNull(ui.previousEconomics)
            // Lifetime pages nowhere in either direction.
            assertFalse(ui.canStepBack)
            assertFalse(ui.canStepForward)
        }
    }

    @Test
    fun `setTab switches to Decisions and the decision read-model is present in state`() = runTest {
        economicsByWindow[currentWeek()] = economics(net = 100.0, netPerHour = 20.0)
        decisions = decisions(accepted = 3, declined = 5, timedOut = 2, acceptanceRate = 0.3)

        runWithViewModel { viewModel ->
            // Decisions are collected unconditionally, so they're in state even before the tab switch.
            assertEquals(3, viewModel.uiState.value.decisions.accepted)

            viewModel.setTab(AnalyticsTab.Decisions)
            testScheduler.runCurrent()

            val ui = viewModel.uiState.value
            assertEquals(AnalyticsTab.Decisions, ui.selectedTab)
            assertEquals(currentWeek(), ui.window)
            assertEquals(10, ui.decisions.received)
            assertEquals(5, ui.decisions.declined)
            assertEquals(0.3, ui.decisions.acceptanceRate!!, 1e-9)
            assertEquals(12.5, ui.decisions.declinedEstNet, 1e-9)
        }
    }

    @Test
    fun `patterns-tab sources (store cards + heatmap) are wired into state`() = runTest {
        // Override the @Before defaults with NON-DEFAULT values so the assertion is falsifiable — an
        // empty stub would equal the UiState defaults and prove nothing about the wiring.
        val heatmap = EarningsHeatmap.EMPTY.copy(minCoverageHours = 0.75)
        whenever(analyticsRepository.storeReportCards())
            .thenReturn(flowOf(listOf(storeReportCard("Wendys"))))
        whenever(analyticsRepository.earningsHeatmap(anyOrNull())).thenReturn(flowOf(heatmap))

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals("Wendys", ui.storeReportCards.single().chainDisplay)
            assertEquals(0.75, ui.earningsHeatmap.minCoverageHours, 1e-9)
        }
    }

    @Test
    fun `empty read-model maps to a safe zero-state`() = runTest {

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals(0.0, ui.economics.netProfit, 1e-9)
            assertEquals(null, ui.economics.netPerHour)
            assertTrue(ui.topStores.isEmpty())
            assertTrue(ui.recentSessions.isEmpty())
        }
    }
}
