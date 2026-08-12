package cloud.trotter.dashbuddy.ui.main.playbook

import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.WeeklyPlanRepository
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.HourOfWeekSamples
import cloud.trotter.dashbuddy.domain.analytics.PlanTarget
import cloud.trotter.dashbuddy.domain.analytics.SampledDay
import cloud.trotter.dashbuddy.domain.analytics.SavedPlanWindow
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * #1024 section C — the Playbook ViewModel: it reads four already-shipping sources and grades **this**
 * week's saved plan against the record.
 *
 * **Harness note — the midnight re-anchor is an unbounded virtual-time loop** (the documented
 * `WeeklyPlanViewModelTest` / `AnalyticsViewModelTest` pattern): settle with `runCurrent()`, never
 * `advanceUntilIdle()`, and cancel the ViewModel's scope inside the test body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybookViewModelTest {

    private val analyticsRepository: AnalyticsRepository = mock()
    private val weeklyPlanRepository: WeeklyPlanRepository = mock()

    private val savedPlans = MutableStateFlow<List<SavedWeeklyPlan>>(emptyList())

    /** This week's Monday — the week the driver is IN, which is the plan the Playbook shows. */
    private val thisWeek: LocalDate = WeeklyPlanSchedule.weekStartOf(LocalDate.now())

    /** A Monday 9–11am window worked in full: 2h online, $40 kept. */
    private val samples = HourOfWeekSamples(
        listOf(
            SampledDay(
                date = thisWeek,
                dayIndex = 0,
                coverageHours = MutableList(EarningsHeatmap.HOURS) { 0.0 }.also { it[9] = 1.0; it[10] = 1.0 },
                netDollars = MutableList(EarningsHeatmap.HOURS) { 0.0 }.also { it[9] = 25.0; it[10] = 15.0 },
            ),
        ),
    )

    private fun planFor(weekStart: LocalDate) = SavedWeeklyPlan(
        weekStart = weekStart,
        savedAtMillis = 0L,
        target = PlanTarget.Hours(2),
        windows = listOf(SavedPlanWindow(0, 9, 11, 25.0, 5)),
        projectedKept = 50.0,
        randomKept = null,
    )

    @Before
    fun setUp() {
        whenever(analyticsRepository.hourOfWeekSamples(any())).thenAnswer { flowOf(samples) }
        whenever(analyticsRepository.earningsHeatmap(any())).thenAnswer { flowOf(EarningsHeatmap.EMPTY) }
        whenever(analyticsRepository.storeReportCards()).thenAnswer { flowOf(emptyList<StoreReportCard>()) }
        whenever(weeklyPlanRepository.savedPlans).thenReturn(savedPlans)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.runWithViewModel(body: (PlaybookViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PlaybookViewModel(analyticsRepository, weeklyPlanRepository)
        val collector = launch { viewModel.uiState.collect { } }
        testScheduler.runCurrent()
        try {
            body(viewModel)
        } finally {
            collector.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `this week's saved plan is graded against the record`() = runTest {
        savedPlans.value = listOf(planFor(thisWeek))

        runWithViewModel { viewModel ->
            val state = viewModel.uiState.value
            assertFalse(state.loading)
            assertNotNull(state.savedPlan)
            val grade = requireNotNull(state.planGrade)
            // The week the card labels itself with has ONE owner: the plan's own frozen weekStart.
            assertEquals(thisWeek, grade.weekStart)
            assertEquals(2, grade.plannedHours)
            assertEquals(2.0, grade.actualHours, 1e-9)
            assertEquals(40.0, grade.actualKept, 1e-9)
            assertEquals(50.0, grade.projectedKept, 1e-9)
        }
    }

    @Test
    fun `no plan for this week means no grade, never a fabricated one`() = runTest {
        // A plan exists — for a DIFFERENT week. The Playbook shows the week being worked, so this one
        // must not be picked up and graded as if it were this week's commitment.
        savedPlans.value = listOf(planFor(thisWeek.minusWeeks(1)))

        runWithViewModel { viewModel ->
            val state = viewModel.uiState.value
            assertNull(state.savedPlan)
            assertNull(state.planGrade)
        }
    }

    /**
     * F3 (#1024 review). `WeeklyPlanCodec.decode` drops structurally-impossible windows with
     * `mapNotNull` but keeps the plan row, so a corrupt/older blob can decode to a window-less plan
     * that still carries a `projectedKept`. Rendering it produces "Not started yet — 0 hours
     * scheduled, $280 projected" forever, and outlines nothing on a heatmap captioned as outlined.
     */
    @Test
    fun `a decoded plan with no windows is treated as no plan at all`() = runTest {
        savedPlans.value = listOf(planFor(thisWeek).copy(windows = emptyList()))

        runWithViewModel { viewModel ->
            val state = viewModel.uiState.value
            assertNull(state.savedPlan)
            assertNull(state.planGrade)
        }
    }

    @Test
    fun `the lifetime sources ride through untouched`() = runTest {
        val heatmap = EarningsHeatmap.EMPTY.copy(minCoverageHours = 0.75)
        whenever(analyticsRepository.earningsHeatmap(any())).thenAnswer { flowOf(heatmap) }
        whenever(analyticsRepository.storeReportCards()).thenAnswer { flowOf(listOf(storeReportCard("Wendys"))) }

        runWithViewModel { viewModel ->
            val state = viewModel.uiState.value
            assertEquals(0.75, state.heatmap.minCoverageHours, 1e-9)
            assertEquals("Wendys", state.storeCards.single().chainDisplay)
        }
    }

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
}
