package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionDetail
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #650 PR A — the per-dash drill-down ViewModel pulls its sessionId from [SavedStateHandle] and maps
 * [AnalyticsRepository.sessionDetail] into an immutable [SessionDetailUiState], loading → loaded.
 * Same stub-flow pattern as [AnalyticsViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private val analyticsRepository: AnalyticsRepository = mock()
    private val correctionRepository: CorrectionRepository = mock()

    private fun session(id: String) = SessionRecord(
        sessionId = id, platform = Platform.DoorDash, startedAt = 1_700_000_000_000L,
        endedAt = 1_700_003_600_000L, reportedEarnings = 30.0, reportedDurationMillis = 3_600_000L,
        miles = 12.0, deliveries = 1, jobsCompleted = 1, offersReceived = 2, offersAccepted = 1,
        offersDeclined = 1, offersTimeout = 0,
    )

    private fun delivery(id: String) = DeliveryRecord(
        eventSequenceId = 1L, sessionId = id, platform = Platform.DoorDash, jobId = "job-1",
        taskId = "task-1", storeName = "Wendys", completedAt = 1_700_001_000_000L,
        realizedPay = 8.0, payBasis = "DROP_SHARE", netProfit = 3.0, realizedMiles = 3.0,
        realizedMinutes = 10.0, tip = 2.0, basePay = 6.0,
    )

    private fun buildViewModel(sessionId: String) = SessionDetailViewModel(
        SavedStateHandle(mapOf(Screen.SessionDetail.ARG_SESSION_ID to sessionId)),
        analyticsRepository,
        correctionRepository,
    )

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads the detail for the SavedStateHandle sessionId, loading to loaded`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val detail = SessionDetail(session("s1"), listOf(delivery("s1")))
        whenever(analyticsRepository.sessionDetail(eq("s1"))).thenReturn(flowOf(detail))

        val viewModel = buildViewModel("s1")

        // Initial stateIn value is the loading placeholder before the first emission.
        assertTrue(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.detail)

        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val loaded = viewModel.uiState.value
        assertTrue(!loaded.loading)
        val detailOut = loaded.detail!!
        assertEquals("s1", detailOut.session.sessionId)
        assertEquals(1, detailOut.deliveries.size)
        job.cancel()
    }

    @Test
    fun `an unknown session id resolves to a loaded null detail`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        whenever(analyticsRepository.sessionDetail(eq("gone"))).thenReturn(flowOf(null))

        val viewModel = buildViewModel("gone")
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(!ui.loading)
        assertNull(ui.detail)
        job.cancel()
    }

    @Test
    fun `addManualDelivery intent appends via the repository, defaulting completedAt to the session's end`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val detail = SessionDetail(session("s1"), listOf(delivery("s1")))
        whenever(analyticsRepository.sessionDetail(eq("s1"))).thenReturn(flowOf(detail))
        val viewModel = buildViewModel("s1")
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.addManualDelivery(pay = 9.5, tip = 2.0, cashTip = 3.0, storeName = "Chipotle", note = "missed")
        testScheduler.advanceUntilIdle()

        // completedAt defaults to the loaded session's endedAt; miles is v1-null.
        verify(correctionRepository).addManualDelivery(
            eq("s1"), eq("Chipotle"), eq(9.5), eq(2.0), eq(3.0), eq(1_700_003_600_000L), isNull(), eq("missed"),
        )
        job.cancel()
    }

    @Test
    fun `adjustDelivery intent appends via the repository with the target seq and the VM sessionId`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val detail = SessionDetail(session("s1"), listOf(delivery("s1")))
        whenever(analyticsRepository.sessionDetail(eq("s1"))).thenReturn(flowOf(detail))
        val viewModel = buildViewModel("s1")
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        viewModel.adjustDelivery(
            targetEventSequenceId = 42L, newStoreName = "Bill Millers", newPay = 15.0,
            newTip = null, newCashTip = 4.0, newMiles = null, note = "tip",
        )
        testScheduler.advanceUntilIdle()

        verify(correctionRepository).adjustDelivery(
            eq(42L), eq("s1"), eq("Bill Millers"), eq(15.0), isNull(), eq(4.0), isNull(), eq("tip"),
        )
        job.cancel()
    }
}
