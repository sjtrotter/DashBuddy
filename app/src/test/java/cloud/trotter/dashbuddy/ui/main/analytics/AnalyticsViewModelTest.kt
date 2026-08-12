package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.EstimateVsReality
import cloud.trotter.dashbuddy.domain.analytics.GapStats
import cloud.trotter.dashbuddy.domain.analytics.OfferFilter
import cloud.trotter.dashbuddy.domain.analytics.OfferListing
import cloud.trotter.dashbuddy.domain.analytics.OfferOutcome
import cloud.trotter.dashbuddy.domain.analytics.PayMixParts
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.analytics.PlatformEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
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

    private var decisions: DecisionEconomics = DecisionEconomics.EMPTY
    private var dailyEarnings: List<DailyEarnings> = emptyList()

    /** #973 — the Money tab's pay-mix parts and platform split, served for every window. */
    private var payMixParts: PayMixParts = PayMixParts.EMPTY
    private var platformSplit: List<PlatformEconomics> = emptyList()

    /** #983 — the Time tab's measured time + between-job gaps, served for every window. */
    private var time: TimeEconomics = TimeEconomics.EMPTY
    private var gaps: GapStats = GapStats.EMPTY

    /** #975 — the Offers tab's est-vs-realized comparison and its list feed, served for every window. */
    private var estimateVsReality: EstimateVsReality = EstimateVsReality.EMPTY
    private var offerListings: List<OfferListing> = emptyList()

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
        whenever(analyticsRepository.decisionEconomics(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(decisions) }
        whenever(analyticsRepository.timeEconomics(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(time) }
        // #983: the §7.8 gap read shares the Time tab's slot, so it must always be stubbed.
        whenever(analyticsRepository.gapStats(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(gaps) }
        whenever(analyticsRepository.dailyEarnings(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(dailyEarnings) }
        whenever(analyticsRepository.noSessionDeliveries(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenReturn(flowOf(emptyList()))
        whenever(analyticsRepository.orphanOfferGroups(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenReturn(flowOf(emptyList()))
        // #973: the Money tab's pay mix + platform split ride the same window fan-out.
        whenever(analyticsRepository.payMixParts(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(payMixParts) }
        whenever(analyticsRepository.platformEconomics(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(platformSplit) }
        // #975: the Offers tab's est-vs-realized read rides the window fan-out (collected
        // unconditionally); its list feed is a separate StateFlow, collected only on subscription.
        whenever(analyticsRepository.estimateVsReality(any<AnalyticsWindow>(), any<ZoneId>()))
            .thenAnswer { flowOf(estimateVsReality) }
        whenever(analyticsRepository.offers(any<AnalyticsWindow>(), any(), any(), any(), any<ZoneId>()))
            .thenAnswer { invocation ->
                // Honour the requested page size so a test can assert the `See all` expansion.
                flowOf(offerListings.take(invocation.getArgument<Int>(2)))
            }
        whenever(analyticsRepository.offerCount(any<AnalyticsWindow>(), any(), any<ZoneId>()))
            .thenAnswer { flowOf(offerListings.size) }
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        // #1024: the hub no longer reads the LIFETIME-scoped `storeReportCards`/`earningsHeatmap`
        // (part 1) nor the window-scoped `perStoreEconomics` (part 2, B5) — every store surface is
        // the Playbook's now, so every source stubbed here is window-anchored AND rendered.
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
            declinedWithEstimate = declined,
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
        // #975: the Offers list feed is a SEPARATE WhileSubscribed StateFlow, so it stays cold until
        // something collects it — subscribe here so its intents are observable in a test.
        val offersCollector = launch { viewModel.offersFeed.collect {} }
        testScheduler.runCurrent()
        try {
            block(viewModel)
        } finally {
            collector.cancel()
            offersCollector.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

    /** One list row — the fields the feed test asserts on; the rest are decision-time detail. */
    private fun offerListing(seq: Long, outcome: OfferOutcome) = OfferListing(
        eventSequenceId = seq,
        platform = Platform.DoorDash,
        storeName = "Store $seq",
        decidedAt = seq,
        payAmount = 8.0,
        distanceMiles = 3.0,
        estDollarsPerHour = 18.0,
        score = 6.0,
        outcome = outcome,
    )

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `defaults to the current pay week on the Money tab and maps the read-model into state`() = runTest {
        economicsByWindow[currentWeek()] = economics(net = 312.0, netPerHour = 24.0)
        stubSessions(listOf(session("s1", 90.0), session("s2", null)))

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals(currentWeek(), ui.window)
            assertEquals(WindowGranularity.WEEK, ui.window.granularity)
            assertEquals(AnalyticsTab.Money, ui.selectedTab)
            assertEquals(312.0, ui.economics.netProfit, 1e-9)
            assertEquals(2, ui.recentSessions.size)
        }
    }

    /**
     * #973 — the pay mix is composed at the ViewModel against **this window's own** gross, so the
     * "what made up the gross" bar always reconciles with the figure the recap hero shows. The parts
     * are measured; the bonuses residue is derived from them and that gross, nowhere else.
     */
    @Test
    fun `pay mix is composed against the selected window's gross`() = runTest {
        economicsByWindow[currentWeek()] = economics(net = 200.0, netPerHour = 20.0, unattributed = 40.0)
        payMixParts = PayMixParts(
            basePay = 90.0,
            tips = 100.0,
            cashTips = 10.0,
            deliveries = 6,
            deliveriesWithBreakdown = 4,
        )

        runWithViewModel { viewModel ->
            val mix = viewModel.uiState.value.payMix
            // gross = net + unattributed = 240; 240 − 90 − 100 − 10 = 40 left as bonuses/other.
            assertEquals(240.0, mix.gross, 1e-9)
            assertEquals(40.0, mix.bonusesOther, 1e-9)
            assertEquals(mix.gross, mix.basePay + mix.tipsTotal + mix.bonusesOther, 1e-9)
            assertFalse("4 of 6 itemized — the card must state the coverage", mix.breakdownComplete)
        }
    }

    /** #973 — the platform split rides the same window fan-out and reaches the Money tab's state. */
    @Test
    fun `platform split reaches the ui state`() = runTest {
        platformSplit = listOf(
            PlatformEconomics(Platform.DoorDash, economics(net = 180.0, netPerHour = 18.0)),
            PlatformEconomics(Platform.Uber, economics(net = 60.0, netPerHour = 12.0)),
        )

        runWithViewModel { viewModel ->
            val rows = viewModel.uiState.value.platformSplit
            assertEquals(2, rows.size)
            assertEquals(Platform.DoorDash, rows.first().platform)
            assertEquals(180.0, rows.first().economics.netProfit, 1e-9)
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
    fun `stepWindow re-anchors economics to the paged window`() = runTest {
        economicsByWindow[currentWeek()] = economics(net = 312.0, netPerHour = 24.0)
        economicsByWindow[lastWeek()] = economics(net = 1280.0, netPerHour = 22.0)

        runWithViewModel { viewModel ->
            assertEquals(312.0, viewModel.uiState.value.economics.netProfit, 1e-9)

            viewModel.stepWindow(-1)
            testScheduler.runCurrent()

            val ui = viewModel.uiState.value
            assertEquals(lastWeek(), ui.window)
            assertEquals(1280.0, ui.economics.netProfit, 1e-9)
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
    fun `setTab switches to Offers and the decision read-model is present in state`() = runTest {
        economicsByWindow[currentWeek()] = economics(net = 100.0, netPerHour = 20.0)
        decisions = decisions(accepted = 3, declined = 5, timedOut = 2, acceptanceRate = 0.3)

        runWithViewModel { viewModel ->
            // Decisions are collected unconditionally, so they're in state even before the tab switch.
            assertEquals(3, viewModel.uiState.value.decisions.accepted)

            viewModel.setTab(AnalyticsTab.Offers)
            testScheduler.runCurrent()

            val ui = viewModel.uiState.value
            assertEquals(AnalyticsTab.Offers, ui.selectedTab)
            assertEquals(currentWeek(), ui.window)
            assertEquals(10, ui.decisions.received)
            assertEquals(5, ui.decisions.declined)
            assertEquals(0.3, ui.decisions.acceptanceRate!!, 1e-9)
            assertEquals(12.5, ui.decisions.declinedEstNet, 1e-9)
        }
    }

    /**
     * #983 — the Time tab's two composed models. The ViewModel is the composition site (the pay-mix
     * precedent) because each needs a value that already has an owner: the hour composition needs the
     * window's measured time, and the rate pair needs its FROZEN net. Asserting them here is what
     * proves the Time tab reconciles with the hero above it rather than deriving a second total.
     */
    @Test
    fun `time-tab insights compose the window's measured time, gaps and frozen net`() = runTest {
        val hour = 3_600_000L
        val minute = 60_000L
        time = TimeEconomics(
            sessions = 1,
            onlineMillis = 4 * hour,
            deliveryMinutes = 120.0,
            miles = 40.0,
            deliveryMiles = 30.0,
            deliveriesWithDeadline = 0,
            onTimeDeliveries = 0,
            avgDeadlineMarginMillis = null,
            deliveries = 4,
            dropoffDwellMillis = 8 * minute,
            dropoffsTimed = 4,
            pickups = 4,
            pickupDwellMillis = 22 * minute,
            pickupsTimed = 3,
        )
        gaps = GapStats(
            count = 3,
            totalMillis = 24 * minute,
            medianMillis = 8 * minute,
            p90Millis = 12 * minute,
            longestMillis = 12 * minute,
            longGapCount = 0,
            completionsWithoutGap = 1,
        )
        economicsByWindow[currentWeek()] = economics(net = 96.0, netPerHour = 24.0)

        runWithViewModel { vm ->
            val state = vm.uiState.value

            assertEquals(gaps, state.gaps)
            // At stops = 22m store + 8m door; waiting = the measured gaps.
            assertEquals(30 * minute, state.hourComposition.atStopsMillis)
            assertEquals(24 * minute, state.hourComposition.waitingMillis)
            // 3 of 4 pickups + 4 of 4 drops timed, out of 8 stops — the coverage the card states.
            assertEquals(7, state.hourComposition.stopsTimed)
            assertEquals(8, state.hourComposition.stops)
            // Working = 2h of delivery time − 24m of dry gaps = 1h36m; shift = the whole 4h online.
            assertEquals(2 * hour - 24 * minute, state.netPerHour.workingMillis)
            assertEquals(96.0 / 1.6, state.netPerHour.whileWorking!!, 1e-9)
            assertEquals(24.0, state.netPerHour.wholeShift!!, 1e-9)
        }
    }

    @Test
    fun `empty read-model maps to a safe zero-state`() = runTest {

        runWithViewModel { viewModel ->
            val ui = viewModel.uiState.value
            assertEquals(0.0, ui.economics.netProfit, 1e-9)
            assertEquals(null, ui.economics.netPerHour)
            assertTrue(ui.recentSessions.isEmpty())
        }
    }

    // ── #975 — the Offers tab ────────────────────────────────────────────

    @Test
    fun `estimate vs reality rides the window fan-out into ui state`() = runTest {
        estimateVsReality = EstimateVsReality(
            acceptedOffers = 9,
            offers = 6,
            estPerHour = 20.0,
            realizedPerHour = 17.0,
            ratio = 0.85,
        )

        runWithViewModel { viewModel ->
            val comparison = viewModel.uiState.value.estimateVsReality
            assertEquals(6, comparison.offers)
            assertEquals(9, comparison.acceptedOffers)
            assertEquals(0.85, comparison.ratio!!, 1e-9)
            assertFalse(comparison.isThinData)
        }
    }

    @Test
    fun `the offers feed serves a default page and expands to the whole window on See all`() = runTest {
        offerListings = (1L..40L).map { offerListing(it, OfferOutcome.ACCEPTED) }

        runWithViewModel { viewModel ->
            // The default page is bounded even though the window holds far more.
            assertEquals(AnalyticsRepository.DEFAULT_OFFER_PAGE, viewModel.offersFeed.value.offers.size)
            assertEquals(40, viewModel.offersFeed.value.total)
            assertTrue(viewModel.offersFeed.value.canShowMore)

            viewModel.showAllOffers()
            testScheduler.runCurrent()

            assertEquals(40, viewModel.offersFeed.value.offers.size)
            assertFalse(viewModel.offersFeed.value.canShowMore)
            assertFalse(viewModel.offersFeed.value.cappedByCeiling)
        }
    }

    @Test
    fun `changing the filter re-collapses the page and is reflected in the feed`() = runTest {
        offerListings = (1L..40L).map { offerListing(it, OfferOutcome.DECLINED) }

        runWithViewModel { viewModel ->
            viewModel.showAllOffers()
            testScheduler.runCurrent()
            assertEquals(40, viewModel.offersFeed.value.offers.size)

            viewModel.setOfferFilter(OfferFilter.DECLINED)
            testScheduler.runCurrent()

            assertEquals(OfferFilter.DECLINED, viewModel.offersFeed.value.filter)
            assertEquals(AnalyticsRepository.DEFAULT_OFFER_PAGE, viewModel.offersFeed.value.offers.size)
        }
    }

    @Test
    fun `paging the window re-collapses the offers page`() = runTest {
        offerListings = (1L..40L).map { offerListing(it, OfferOutcome.ACCEPTED) }

        runWithViewModel { viewModel ->
            viewModel.showAllOffers()
            testScheduler.runCurrent()
            assertEquals(40, viewModel.offersFeed.value.offers.size)

            viewModel.stepWindow(-1)
            testScheduler.runCurrent()

            assertEquals(lastWeek(), viewModel.uiState.value.window)
            assertEquals(AnalyticsRepository.DEFAULT_OFFER_PAGE, viewModel.offersFeed.value.offers.size)
        }
    }
}
