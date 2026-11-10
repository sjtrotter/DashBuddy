package cloud.trotter.log.gig.ui.main.analytics.shifts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.RecapRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.ShiftChecksRepository
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.recap.ShiftWeeks
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.main.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1515: the Shifts list's ViewModel over mocked reads. Checks are read only for the ended shifts of the weeks the
 * list reports on screen, never for every shift.
 *
 * **Harness note** (as `AnalyticsViewModelTest`'s): the ViewModel reads the local-date flow, which sleeps until the next
 * local midnight forever, so settle with `runCurrent()`, never `advanceUntilIdle()`, and cancel the ViewModel's scope
 * before the body ends ([runWithViewModel] does it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftsViewModelTest {

    private val recapRepository: RecapRepository = mock()
    private val shiftChecksRepository: ShiftChecksRepository = mock()
    private val recordsStatus = MutableStateFlow(RecordsStatus.UP_TO_DATE)
    private val recordsStatusRepository: RecordsStatusRepository = mock { on { status } doReturn recordsStatus }

    private val zone: ZoneId = ZoneId.systemDefault()
    private val thisWeek: LocalDate get() = ShiftWeeks.weekStart(LocalDate.now())
    private val lastWeek: LocalDate get() = thisWeek.minusWeeks(1)

    private var shifts: List<DayShift> = emptyList()
    private var failShifts = false
    private val checkReads = mutableListOf<List<String>>()
    private val check = listOf(ShiftCheck.NoDelivery(offerEventSequenceId = 7))

    private fun shift(id: String, monday: LocalDate, ended: Boolean = true): DayShift {
        val start = monday.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return DayShift(id, Platform.DoorDash, start, if (ended) start + 3_600_000L else null, 20.0, 12.0, paySeen = true)
    }

    @Before
    fun stubRepositories() {
        whenever(recapRepository.allShifts()).thenAnswer {
            if (failShifts) flow<List<DayShift>> { throw IllegalStateException("read failed") } else flowOf(shifts)
        }
        whenever(shiftChecksRepository.checks(any<List<String>>())).thenAnswer { invocation ->
            val ids = invocation.getArgument<List<String>>(0)
            checkReads.add(ids)
            flowOf(ids.associateWith { check })
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.runWithViewModel(week: String? = null, block: (ShiftsViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val handle = SavedStateHandle(listOfNotNull(week?.let { Screen.Shifts.ARG_WEEK to it }).toMap())
        val viewModel = ShiftsViewModel(handle, recapRepository, shiftChecksRepository, recordsStatusRepository)
        val collector = launch { viewModel.uiState.collect {} }
        testScheduler.runCurrent()
        try {
            block(viewModel)
        } finally {
            collector.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `checks are read only for the ended shifts of the weeks on screen`() = runTest {
        shifts = listOf(
            shift("now", thisWeek, ended = false),
            shift("a", thisWeek),
            shift("c", lastWeek),
            shift("old", thisWeek.minusWeeks(5)),
        )
        runWithViewModel { viewModel ->
            assertTrue(checkReads.all { it.isEmpty() })
            assertTrue(viewModel.uiState.value.checks.isEmpty())

            viewModel.onVisibleWeeks(setOf(lastWeek))
            testScheduler.runCurrent()
            assertEquals(listOf("c"), checkReads.last())
            assertEquals(mapOf("c" to check), viewModel.uiState.value.checks)

            viewModel.onVisibleWeeks(setOf(thisWeek, lastWeek))
            testScheduler.runCurrent()
            assertEquals(listOf("a", "c"), checkReads.last())
            assertEquals(setOf("a", "c"), viewModel.uiState.value.checks.keys)

            // The same weeks again re-read nothing.
            val reads = checkReads.size
            viewModel.onVisibleWeeks(setOf(lastWeek, thisWeek))
            testScheduler.runCurrent()
            assertEquals(reads, checkReads.size)
            assertTrue(checkReads.none { "old" in it || "now" in it })
        }
    }

    @Test
    fun `the week argument opens its Monday's week, inserted empty when it has no shift`() = runTest {
        shifts = listOf(shift("a", thisWeek))
        val target = lastWeek.minusWeeks(1)
        runWithViewModel(week = target.plusDays(2).toString()) { viewModel ->
            val state = viewModel.uiState.value
            assertEquals(target, state.targetWeek)
            assertEquals(listOf(thisWeek, target), state.weeks.map { it.start })
            assertTrue(state.weeks.last().shifts.isEmpty())
        }
    }

    @Test
    fun `the date picker's jump moves the target week over the route argument`() = runTest {
        shifts = listOf(shift("a", thisWeek), shift("c", lastWeek))
        runWithViewModel(week = lastWeek.toString()) { viewModel ->
            assertEquals(lastWeek, viewModel.uiState.value.targetWeek)
            val empty = thisWeek.minusWeeks(4)
            viewModel.jumpTo(empty.plusDays(3))
            testScheduler.runCurrent()
            val state = viewModel.uiState.value
            assertEquals(empty, state.targetWeek)
            assertEquals(listOf(thisWeek, lastWeek, empty), state.weeks.map { it.start })
            assertTrue(state.weeks.last().shifts.isEmpty())
        }
    }

    @Test
    fun `no week argument, or one that does not parse, opens this week`() = runTest {
        shifts = listOf(shift("c", lastWeek))
        runWithViewModel { viewModel -> assertEquals(thisWeek, viewModel.uiState.value.targetWeek) }
        runWithViewModel(week = "not a date") { viewModel ->
            assertEquals(thisWeek, viewModel.uiState.value.targetWeek)
            assertEquals(listOf(thisWeek, lastWeek), viewModel.uiState.value.weeks.map { it.start })
        }
    }

    @Test
    fun `no shift ever, and the records status, reach the state`() = runTest {
        recordsStatus.value = RecordsStatus.REBUILDING
        runWithViewModel { viewModel ->
            val state = viewModel.uiState.value
            assertTrue(state.loaded)
            assertFalse(state.anyShift)
            assertEquals(RecordsStatus.REBUILDING, state.records)
        }
    }

    @Test
    fun `a failed read shows the failure, and Retry reads again`() = runTest {
        failShifts = true
        runWithViewModel { viewModel ->
            assertTrue(viewModel.uiState.value.failed)
            failShifts = false
            shifts = listOf(shift("a", thisWeek))
            viewModel.retry()
            testScheduler.runCurrent()
            assertFalse(viewModel.uiState.value.failed)
            assertTrue(viewModel.uiState.value.anyShift)
        }
    }
}
