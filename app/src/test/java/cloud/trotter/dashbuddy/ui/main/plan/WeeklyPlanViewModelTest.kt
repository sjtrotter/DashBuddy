package cloud.trotter.dashbuddy.ui.main.plan

import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.WeeklyPlanRepository
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.HourOfWeekSamples
import cloud.trotter.dashbuddy.domain.analytics.PlanTarget
import cloud.trotter.dashbuddy.domain.analytics.SampledDay
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanSchedule
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * #981 — the Weekly Plan ViewModel: it holds only the driver's choices (target + edits) and recomputes
 * the plan from the record, and it saves a FROZEN snapshot of exactly what was on screen.
 *
 * **Harness note — the midnight re-anchor is an unbounded virtual-time loop** (the documented
 * `AnalyticsViewModelTest` / `DashboardViewModelTest` pattern, a 12-minute CI hang was its receipt).
 * The ViewModel resolves its plan week against `currentLocalDateFlow`, which emits and then sleeps
 * until the next local midnight, forever. Under a `StandardTestDispatcher` that delay is *virtual*, so
 * anything draining the scheduler spins through midnight after midnight and never runs out of work.
 * Hence: settle with **`runCurrent()`**, never `advanceUntilIdle()`, and **cancel the ViewModel's
 * scope before the test body ends** ([runWithViewModel] does both) — `runTest` finishes with its own
 * `advanceUntilIdle()` and `viewModelScope` is not a child of the test scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyPlanViewModelTest {

    private val analyticsRepository: AnalyticsRepository = mock()
    private val weeklyPlanRepository: WeeklyPlanRepository = mock()

    /** The saved plans the stubbed repository serves; writes land here so a save round-trips. */
    private val savedPlans = MutableStateFlow<List<SavedWeeklyPlan>>(emptyList())

    /** A record with two clearly-ranked evenings: Fridays at $30/hr, Tuesdays at $12/hr. */
    private val samples = HourOfWeekSamples(
        buildList {
            addAll(weekday(dayIndex = 4, hours = 17..20, rate = 30.0, occurrences = 4))
            addAll(weekday(dayIndex = 1, hours = 17..20, rate = 12.0, occurrences = 4))
        },
    )

    private fun weekday(dayIndex: Int, hours: IntRange, rate: Double, occurrences: Int): List<SampledDay> {
        val monday = LocalDate.of(2026, 1, 5)
        return (0 until occurrences).map { occ ->
            val coverage = MutableList(EarningsHeatmap.HOURS) { 0.0 }
            val net = MutableList(EarningsHeatmap.HOURS) { 0.0 }
            for (h in hours) {
                coverage[h] = 1.0
                net[h] = rate
            }
            SampledDay(monday.plusDays((dayIndex + 7 * occ).toLong()), dayIndex, coverage, net)
        }
    }

    @Before
    fun setUp() {
        whenever(analyticsRepository.hourOfWeekSamples(any())).thenAnswer { flowOf(samples) }
        whenever(analyticsRepository.earningsHeatmap(any())).thenAnswer { flowOf(EarningsHeatmap.EMPTY) }
        whenever(weeklyPlanRepository.savedPlans).thenReturn(savedPlans)
        whenever(runBlocking { weeklyPlanRepository.save(any()) }).thenAnswer { invocation ->
            val plan = invocation.getArgument<SavedWeeklyPlan>(0)
            savedPlans.value = listOf(plan) + savedPlans.value.filterNot { it.weekStart == plan.weekStart }
            Unit
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Build the ViewModel, keep its `uiState` hot for the duration, and cancel its scope at the end —
     * without which `runTest`'s teardown `advanceUntilIdle()` stalls on the midnight loop.
     */
    private fun TestScope.runWithViewModel(body: (WeeklyPlanViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = WeeklyPlanViewModel(analyticsRepository, weeklyPlanRepository)
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
    fun `plans the driver's best windows for the default target`() = runTest {
        runWithViewModel { viewModel ->
            val state = viewModel.uiState.value
            assertFalse(state.loading)
            assertTrue(state.hasRecord)
            // Fridays outrank Tuesdays, so the BEST window is a Friday one. (The list itself is in
            // weekday order — a week reads chronologically — so "first placed" is not "first shown".)
            assertEquals(4, state.plan.windows.first { it.isBest }.dayIndex)
            assertTrue(state.plan.projectedKept > 0.0)
        }
    }

    @Test
    fun `the plan week is the Monday-anchored week the schedule owns`() = runTest {
        runWithViewModel { viewModel ->
            assertEquals(
                WeeklyPlanSchedule.planWeekStart(LocalDate.now()),
                viewModel.uiState.value.weekStart,
            )
        }
    }

    @Test
    fun `changing the target re-plans and drops the edits made against the old one`() = runTest {
        runWithViewModel { viewModel ->
            val first = viewModel.uiState.value.plan.windows.first()
            viewModel.dropWindow(first)
            testScheduler.runCurrent()
            assertEquals(1, viewModel.uiState.value.plan.edits.size)

            viewModel.setTarget(PlanTarget.Hours(8))
            testScheduler.runCurrent()

            val state = viewModel.uiState.value
            assertEquals(PlanTarget.Hours(8), state.target)
            assertTrue(state.plan.edits.isEmpty())
            assertTrue(state.plan.totalHours <= 8)
        }
    }

    @Test
    fun `dropping a window records an edit and re-plans without it`() = runTest {
        runWithViewModel { viewModel ->
            val dropped = viewModel.uiState.value.plan.windows.first()
            viewModel.dropWindow(dropped)
            testScheduler.runCurrent()

            val windows = viewModel.uiState.value.plan.windows
            assertTrue(windows.none { it.dayIndex == dropped.dayIndex && it.startHour == dropped.startHour })
        }
    }

    @Test
    fun `moving a window re-derives it at the new hours and marks it moved`() = runTest {
        runWithViewModel { viewModel ->
            val window = viewModel.uiState.value.plan.windows.first()
            viewModel.moveWindow(window, -1)
            testScheduler.runCurrent()

            val moved = viewModel.uiState.value.plan.windows.first { it.moved }
            assertEquals(window.startHour - 1, moved.startHour)
        }
    }

    @Test
    fun `undo removes the last edit only`() = runTest {
        runWithViewModel { viewModel ->
            val window = viewModel.uiState.value.plan.windows.first()
            viewModel.moveWindow(window, -1)
            testScheduler.runCurrent()
            viewModel.dropWindow(viewModel.uiState.value.plan.windows.first())
            testScheduler.runCurrent()
            assertEquals(2, viewModel.uiState.value.plan.edits.size)

            viewModel.undoLastEdit()
            testScheduler.runCurrent()

            assertEquals(1, viewModel.uiState.value.plan.edits.size)
            assertTrue(viewModel.uiState.value.plan.windows.any { it.moved })
        }
    }

    @Test
    fun `saving freezes exactly what is on screen, and the pointer state reads it back`() = runTest {
        runWithViewModel { viewModel ->
            val shown = viewModel.uiState.value.plan
            assertNull(viewModel.uiState.value.savedPlan)

            viewModel.savePlan(savedAtMillis = 1_753_000_000_000L)
            testScheduler.runCurrent()

            val saved = savedPlans.value.single()
            assertEquals(viewModel.uiState.value.weekStart, saved.weekStart)
            assertEquals(shown.projectedKept, saved.projectedKept, 1e-9)
            assertEquals(shown.totalHours, saved.totalHours)
            assertEquals(shown.windows.map { it.startHour }, saved.windows.map { it.startHour })
            // …and the state now reports it as saved, which is what flips the button copy.
            assertTrue(viewModel.uiState.value.isSaved)
        }
    }

    @Test
    fun `a finished week's saved plan is graded, an unfinished one is not`() = runTest {
        val thisWeek = WeeklyPlanSchedule.weekStartOf(LocalDate.now())
        savedPlans.value = listOf(
            SavedWeeklyPlan(
                weekStart = thisWeek,
                savedAtMillis = 0L,
                target = PlanTarget.Hours(4),
                windows = emptyList(),
                projectedKept = 0.0,
                randomKept = null,
            ),
        )
        runWithViewModel { viewModel ->
            // The week in progress is deliberately not graded — a shortfall it can still work off.
            assertNull(viewModel.uiState.value.lastWeekGrade)
        }

        savedPlans.value = listOf(savedPlans.value.single().copy(weekStart = thisWeek.minusWeeks(2)))
        runWithViewModel { viewModel ->
            assertNotNull(viewModel.uiState.value.lastWeekGrade)
            assertEquals(thisWeek.minusWeeks(2), viewModel.uiState.value.lastWeekGrade!!.weekStart)
        }
    }

    @Test
    fun `an empty record reports no baseline instead of an empty plan that looks like a bad week`() = runTest {
        whenever(analyticsRepository.hourOfWeekSamples(any())).thenAnswer { flowOf(HourOfWeekSamples.EMPTY) }
        runWithViewModel { viewModel ->
            val state = viewModel.uiState.value
            assertFalse(state.hasRecord)
            assertTrue(state.plan.windows.isEmpty())
            assertNull(state.plan.planWorth)
        }
    }
}
