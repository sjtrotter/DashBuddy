package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #315 H1 — the Analytics hub ViewModel exposes the read-model economics for the selected
 * period (default [AnalyticsPeriod.THIS_WEEK], switchable via [AnalyticsViewModel.setPeriod])
 * on the selected tab (default [AnalyticsTab.Money], switchable via [AnalyticsViewModel.setTab]),
 * strictly over the existing repository surface. Same stub-flow pattern as DashboardViewModelTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest {

    private val analyticsRepository: AnalyticsRepository = mock()

    private fun stubPeriod(
        period: AnalyticsPeriod,
        economics: PeriodEconomics,
        stores: List<StoreEconomics> = emptyList(),
        decisions: DecisionEconomics = DecisionEconomics.EMPTY,
        time: TimeEconomics = TimeEconomics.EMPTY,
        dailyEarnings: List<DailyEarnings> = emptyList(),
    ) {
        whenever(analyticsRepository.periodEconomics(eq(period), anyOrNull())).thenReturn(flowOf(economics))
        whenever(analyticsRepository.perStoreEconomics(eq(period))).thenReturn(flowOf(stores))
        // The VM collects decisions + time + dailyEarnings unconditionally (see AnalyticsViewModel), so
        // every period stub must supply all three flows or the combine would fold a null.
        whenever(analyticsRepository.decisionEconomics(eq(period))).thenReturn(flowOf(decisions))
        whenever(analyticsRepository.timeEconomics(eq(period))).thenReturn(flowOf(time))
        whenever(analyticsRepository.dailyEarnings(eq(period), anyOrNull())).thenReturn(flowOf(dailyEarnings))
    }

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

    private fun session(id: String, reported: Double?) = SessionRecord(
        sessionId = id, platform = Platform.DoorDash, startedAt = 1_700_000_000_000L, endedAt = null,
        reportedEarnings = reported, reportedDurationMillis = null, miles = 10.0,
        deliveries = 2, jobsCompleted = 2, offersReceived = 4, offersAccepted = 2,
        offersDeclined = 2, offersTimeout = 0,
    )

    private fun buildViewModel() = AnalyticsViewModel(analyticsRepository)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `defaults to THIS_WEEK on the Money tab and maps the read-model into state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubPeriod(
            AnalyticsPeriod.THIS_WEEK,
            economics(net = 312.0, netPerHour = 24.0),
            stores = listOf(store("H-E-B", 120.0, 5), store("Chili's", 40.0, 2)),
        )
        stubSessions(listOf(session("s1", 90.0), session("s2", null)))

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertEquals(AnalyticsPeriod.THIS_WEEK, ui.selectedPeriod)
        assertEquals(AnalyticsTab.Money, ui.selectedTab)
        assertEquals(312.0, ui.economics.netProfit, 1e-9)
        assertEquals(2, ui.topStores.size)
        assertEquals(2, ui.recentSessions.size)
        job.cancel()
    }

    @Test
    fun `setPeriod re-anchors economics and top stores to the selected window`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubPeriod(AnalyticsPeriod.THIS_WEEK, economics(net = 312.0, netPerHour = 24.0), stores = listOf(store("H-E-B", 120.0, 5)))
        stubPeriod(AnalyticsPeriod.THIS_MONTH, economics(net = 1280.0, netPerHour = 22.0), stores = listOf(store("Target", 400.0, 12)))
        stubSessions(emptyList())

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()
        assertEquals(312.0, viewModel.uiState.value.economics.netProfit, 1e-9)

        viewModel.setPeriod(AnalyticsPeriod.THIS_MONTH)
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertEquals(AnalyticsPeriod.THIS_MONTH, ui.selectedPeriod)
        assertEquals(1280.0, ui.economics.netProfit, 1e-9)
        assertEquals("Target", ui.topStores.single().storeName)
        job.cancel()
    }

    @Test
    fun `setTab switches to Decisions and the decision read-model is present in state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubPeriod(
            AnalyticsPeriod.THIS_WEEK,
            economics(net = 100.0, netPerHour = 20.0),
            decisions = decisions(accepted = 3, declined = 5, timedOut = 2, acceptanceRate = 0.3),
        )
        stubSessions(emptyList())

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        // Decisions are collected unconditionally, so they're in state even before the tab switch.
        assertEquals(3, viewModel.uiState.value.decisions.accepted)

        viewModel.setTab(AnalyticsTab.Decisions)
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertEquals(AnalyticsTab.Decisions, ui.selectedTab)
        assertEquals(AnalyticsPeriod.THIS_WEEK, ui.selectedPeriod)
        assertEquals(10, ui.decisions.received)
        assertEquals(5, ui.decisions.declined)
        assertEquals(0.3, ui.decisions.acceptanceRate!!, 1e-9)
        assertEquals(12.5, ui.decisions.declinedEstNet, 1e-9)
        job.cancel()
    }

    @Test
    fun `empty read-model maps to a safe zero-state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubPeriod(AnalyticsPeriod.THIS_WEEK, PeriodEconomics.EMPTY, stores = emptyList())
        stubSessions(emptyList())

        val viewModel = buildViewModel()
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertEquals(0.0, ui.economics.netProfit, 1e-9)
        assertEquals(null, ui.economics.netPerHour)
        assertTrue(ui.topStores.isEmpty())
        assertTrue(ui.recentSessions.isEmpty())
        job.cancel()
    }
}
