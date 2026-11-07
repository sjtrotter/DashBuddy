package cloud.trotter.log.gig.ui.main.analytics

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.CorrectionRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.ShiftTimelineRepository
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.ReportSource
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.main.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The shift page's ViewModel reads its sessionId and landing row from [SavedStateHandle] and assembles one
 * [SessionDetailUiState] from the shift's detail, its timeline and the records status: loading, loaded, held back
 * while rebuilding, failed with Retry. The money check's independent-total rule is pinned here too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private val analyticsRepository: AnalyticsRepository = mock()
    private val correctionRepository: CorrectionRepository = mock()
    private val shiftTimelineRepository: ShiftTimelineRepository = mock {
        on { timeline(any(), any()) } doReturn flowOf(emptyList())
    }
    private val recordsStatus = MutableStateFlow(RecordsStatus.UP_TO_DATE)
    private val recordsStatusRepository: RecordsStatusRepository = mock { on { status } doReturn recordsStatus }
    private val clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC)

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

    private fun buildViewModel(sessionId: String, row: String? = null) = SessionDetailViewModel(
        SavedStateHandle(mapOf(Screen.SessionDetail.ARG_SESSION_ID to sessionId, Screen.SessionDetail.ARG_ROW to row)),
        analyticsRepository,
        shiftTimelineRepository,
        recordsStatusRepository,
        correctionRepository,
        clock,
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

    private fun detailWith(record: SessionRecord, vararg pays: Pair<Double?, String>) = SessionDetail(
        record,
        pays.mapIndexed { i, (pay, basis) -> delivery(record.sessionId).copy(eventSequenceId = i + 1L, realizedPay = pay, payBasis = basis) },
    )

    @Test
    fun `the money check reads only an independent total`() {
        val base = session("c").copy(machineReportedEarnings = null, reportedEarnings = null)
        // The app's summary: checked, the app total higher.
        val summary = detailWith(base.copy(machineReportedEarnings = 30.0, reportedEarnings = 30.0,
            endSource = SessionEndSource.SUMMARY_SCREEN), 8.0 to PayBasis.DROP_SHARE)
        assertEquals(ReportSource.DASH_SUMMARY, shiftReportSource(summary))
        val summaryCheck = MoneyCheck.of(summary)!!
        assertEquals(MoneyCheck.Higher.APP_TOTAL, summaryCheck.higher)
        assertEquals(22.0, summaryCheck.difference, 1e-9)
        // Set by the dasher: checked, delivery pay higher, estimated when an offer amount priced a delivery.
        val set = detailWith(base.copy(machineReportedEarnings = 40.14, endSource = SessionEndSource.EARLY_OFFLINE,
            reportOverrideMode = SessionReportOperation.SET, reportOverride = 5.0, reportedEarnings = 5.0),
            8.0 to PayBasis.OFFER_PAY)
        assertEquals(ReportSource.DRIVER_SET, shiftReportSource(set))
        val setCheck = MoneyCheck.of(set)!!
        assertEquals(MoneyCheck.Higher.DELIVERY_PAY, setCheck.higher)
        assertTrue(setCheck.deliveryPayEstimated)
        // The fallbacks: no report, a running total floored at recorded pay, a running total alone. No check.
        assertNull(MoneyCheck.of(detailWith(base, 8.0 to PayBasis.DROP_SHARE)))
        val floored = detailWith(base.copy(machineReportedEarnings = 6.0, reportedEarnings = 8.0,
            endSource = SessionEndSource.EARLY_OFFLINE), 8.0 to PayBasis.DROP_SHARE)
        assertEquals(ReportSource.RECORDED_PAY, shiftReportSource(floored))
        assertNull(MoneyCheck.of(floored))
        val counter = detailWith(base.copy(machineReportedEarnings = 12.0, reportedEarnings = 12.0,
            endSource = SessionEndSource.EARLY_OFFLINE), 8.0 to PayBasis.DROP_SHARE)
        assertEquals(ReportSource.IN_DASH_COUNTER, shiftReportSource(counter))
        assertNull(MoneyCheck.of(counter))
        val cleared = detailWith(base.copy(machineReportedEarnings = 12.0, reportOverrideMode = SessionReportOperation.CLEAR),
            8.0 to PayBasis.DROP_SHARE)
        assertNull(MoneyCheck.of(cleared))
    }

    @Test
    fun `totals within half a cent name no higher side`() {
        assertNull(MoneyCheck(30.004, 30.0, deliveryPayEstimated = false).higher)
        assertNull(MoneyCheck(0.30, 0.10 + 0.20, deliveryPayEstimated = false).higher)
        assertEquals(MoneyCheck.Higher.APP_TOTAL, MoneyCheck(30.01, 30.0, deliveryPayEstimated = false).higher)
        assertEquals(MoneyCheck.Higher.DELIVERY_PAY, MoneyCheck(30.0, 30.01, deliveryPayEstimated = false).higher)
    }

    @Test
    fun `earned includes cash, profit adds unreported pay and cash, a shift with nothing known is unknown`() {
        val record = session("m").copy(machineReportedEarnings = 30.0, reportedEarnings = 30.0,
            endSource = SessionEndSource.SUMMARY_SCREEN)
        val money = ShiftMoney.of(SessionDetail(record, listOf(delivery("m").copy(cashTip = 5.0))))
        assertEquals(35.0, money.earned!!, 1e-9)
        assertEquals(5.0, money.cash, 1e-9)
        // Net 3.00 + the 22.00 the summary reported beyond delivery pay + 5.00 cash.
        assertEquals(30.0, money.profit!!, 1e-9)
        assertEquals(15.0, money.profitPerHour(7_200_000L)!!, 1e-9)
        assertNull(money.profitPerHour(0L))
        val empty = ShiftMoney.of(SessionDetail(record.copy(machineReportedEarnings = null, reportedEarnings = null,
            endSource = null), emptyList()))
        assertNull(empty.earned)
        assertNull(empty.profit)
    }

    @Test
    fun `the page loads its detail and timeline, folded as running until the shift ends`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val running = SessionDetail(session("r").copy(endedAt = null), emptyList())
        whenever(analyticsRepository.sessionDetail(eq("r"))).thenReturn(flowOf(running))
        val viewModel = buildViewModel("r", row = "offer:7")
        assertEquals("offer:7", viewModel.landingRow)
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.figuresShown())
        verify(shiftTimelineRepository).timeline("r", true)
        job.cancel()

        whenever(analyticsRepository.sessionDetail(eq("e"))).thenReturn(flowOf(SessionDetail(session("e"), emptyList())))
        val ended = buildViewModel("e")
        assertNull(ended.landingRow)
        val job2 = launch { ended.uiState.collect {} }
        testScheduler.advanceUntilIdle()
        verify(shiftTimelineRepository).timeline("e", false)
        job2.cancel()
    }

    @Test
    fun `rebuilding records hold the figures back, updating keeps them`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        whenever(analyticsRepository.sessionDetail(eq("s1"))).thenReturn(flowOf(SessionDetail(session("s1"), emptyList())))
        val viewModel = buildViewModel("s1")
        val job = launch { viewModel.uiState.collect {} }
        recordsStatus.value = RecordsStatus.REBUILDING
        testScheduler.advanceUntilIdle()
        assertEquals(RecordsStatus.REBUILDING, viewModel.uiState.value.records)
        assertFalse(viewModel.uiState.value.figuresShown())
        recordsStatus.value = RecordsStatus.UPDATING
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.figuresShown())
        job.cancel()
    }

    @Test
    fun `a failed read shows the failure and Retry reads again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var reads = 0
        whenever(analyticsRepository.sessionDetail(eq("s1"))).thenReturn(flow {
            reads++
            if (reads == 1) error("disk") else emit(SessionDetail(session("s1"), emptyList()))
        })
        val viewModel = buildViewModel("s1")
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failed)
        assertFalse(viewModel.uiState.value.figuresShown())
        viewModel.retry()
        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.failed)
        assertNotNull(viewModel.uiState.value.detail)
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
    @Test
    fun `correctSessionReport forwards operation value and note for this session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        whenever(analyticsRepository.sessionDetail(eq("483"))).thenReturn(flowOf(null))
        val viewModel = buildViewModel("483")
        viewModel.correctSessionReport(SessionReportOperation.SET, 12.5, "note")
        testScheduler.advanceUntilIdle()
        verify(correctionRepository).correctSessionReport("483", SessionReportOperation.SET, 12.5, "note")
    }

}
