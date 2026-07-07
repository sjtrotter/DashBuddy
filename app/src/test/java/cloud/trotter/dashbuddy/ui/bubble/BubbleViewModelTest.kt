package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #693 — the bubble's post-dash idle surface reads the analytics read-model, not an in-memory
 * capture. The regression: the OLD `SessionSummary` scan was liveness-gated and silently dropped a
 * dash that ended while the bubble was collapsed, so the HUD showed the last dash it happened to
 * catch (the stale "last dash with money"). These prove the ViewModel now surfaces whatever
 * `recentSessions(1)` returns as the true last session — a $0/zero-delivery dash included — with no
 * `hasEarnings`-style filter, plus the gas quick-edit write path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleViewModelTest {

    private val bubbleManager: BubbleManager = mock()
    private val chatRepository: ChatRepository = mock()
    private val odometerRepository: OdometerRepository = mock()
    private val stateManager: StateManagerV2 = mock()
    private val appEventRepo: AppEventRepo = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val analyticsRepository: AnalyticsRepository = mock()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Init-time flow accessors — the VM builds several stateIn() flows in its property initializers.
        whenever(stateManager.state).thenReturn(MutableStateFlow(AppState()))
        whenever(bubbleManager.activeSessionId).thenReturn(MutableStateFlow(null))
        whenever(appEventRepo.getMostRecentSessionId()).thenReturn(flowOf(null))
        whenever(odometerRepository.sessionMilesFlow).thenReturn(flowOf(0.0))
        whenever(appPreferencesRepository.glanceMode).thenReturn(flowOf(false))
        whenever(appPreferencesRepository.gasPrice).thenReturn(flowOf(3.50f))
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flowOf(true))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BubbleViewModel(
        bubbleManager = bubbleManager,
        chatRepository = chatRepository,
        odometerRepository = odometerRepository,
        stateManager = stateManager,
        appEventRepo = appEventRepo,
        appPreferencesRepository = appPreferencesRepository,
        analyticsRepository = analyticsRepository,
    )

    private fun session(
        id: String,
        earnings: Double?,
        deliveries: Int,
        endedAt: Long? = 1_000L,
    ) = SessionRecord(
        sessionId = id,
        platform = Platform.DoorDash,
        startedAt = 0L,
        endedAt = endedAt,
        reportedEarnings = earnings,
        reportedDurationMillis = endedAt,
        miles = 4.2,
        deliveries = deliveries,
        jobsCompleted = deliveries,
        offersReceived = 3,
        offersAccepted = 1,
        offersDeclined = 2,
        offersTimeout = 0,
    )

    @Test
    fun `lastSession surfaces the most recent dash even when it earned zero`() = runTest {
        // The repro shape: the newest dash is a $0, zero-delivery unassign session.
        val zero = session(id = "session-doordash-1783294721320-15", earnings = 0.0, deliveries = 0)
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(listOf(zero)))

        val vm = viewModel()
        val job = launch { vm.lastSession.collect {} }
        advanceUntilIdle()

        assertEquals(zero, vm.lastSession.value)
        job.cancel()
    }

    @Test
    fun `lastSession is null when there are no dashes yet`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))

        val vm = viewModel()
        val job = launch { vm.lastSession.collect {} }
        advanceUntilIdle()

        assertNull(vm.lastSession.value)
        job.cancel()
    }

    @Test
    fun `setGasPrice writes a manual override to the economy settings store`() = runTest {
        whenever(analyticsRepository.recentSessions(any())).thenReturn(flowOf(emptyList()))
        val vm = viewModel()

        vm.setGasPrice(3.49f)
        advanceUntilIdle()

        verify(appPreferencesRepository).updateGasPriceManual(eq(3.49f))
    }
}
