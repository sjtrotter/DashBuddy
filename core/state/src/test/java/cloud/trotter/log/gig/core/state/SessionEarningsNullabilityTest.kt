package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.settings.GraceConfig
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1041: absent earnings survive reduction and event stamping without becoming a read zero. */
class SessionEarningsNullabilityTest {
    private val platform = Platform.DoorDash
    private val policy = TransitionPolicy()
    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val effectMap = EffectMap()
    private val t0 = 10_000L

    private fun region(earnings: Double? = null) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = Session("session-1", startedAt = 100L, runningEarnings = earnings),
    )

    private fun screen(
        flow: Flow = Flow.Idle,
        parsed: ParsedFields = ParsedFields.None,
        timestamp: Long = t0,
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "${platform.wire}.screen.test",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = Mode.Online,
        parsed = parsed,
    )

    private fun step(region: PlatformRegion, obs: Observation): PlatformRegion {
        val flow = FlowRegion(flow = region.lastActedFlow ?: Flow.Idle, activePlatform = platform)
        return stepper.step(region, flow, flowStepper.step(flow, obs), obs, policy)
    }

    private fun wake(timestamp: Long) = Observation.Timeout(
        timestamp = timestamp,
        type = TimeoutType.SESSION_PAY_SETTLE,
        targetPlatform = platform,
    )

    private fun state(region: PlatformRegion) = AppState(
        regions = Regions(platforms = mapOf(platform to region)),
    )

    @Test
    fun `fresh session has no running or accumulated earnings`() {
        val session = Session("fresh", startedAt = 100L)
        assertNull(session.runningEarnings)
        assertNull(session.accumulatedDeliveryPay)
    }

    @Test
    fun `owned receipt accumulates from null to its real total`() {
        val start = region().copy(
            activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        )
        val result = step(start, screen(Flow.PostTask, ParsedFields.PostTaskFields(totalPay = 12.50)))

        assertEquals(12.50, result.session?.accumulatedDeliveryPay ?: Double.NaN, 0.0001)
        assertEquals(12.50, result.session?.runningEarnings ?: Double.NaN, 0.0001)
    }

    @Test
    fun `first running total stays null until its settle window commits`() {
        val parked = step(region(), screen(parsed = ParsedFields.IdleFields(sessionPay = 16.70)))
        val deadline = t0 + GraceConfig.SESSION_PAY_SETTLE_MS
        assertNull(parked.session?.runningEarnings)
        assertEquals(16.70, parked.pendingSessionPay?.value ?: Double.NaN, 0.0001)
        assertNull(step(parked, wake(deadline - 1L)).session?.runningEarnings)

        val committed = step(parked, wake(deadline))
        assertEquals(16.70, committed.session?.runningEarnings ?: Double.NaN, 0.0001)
        assertNull(committed.session?.accumulatedDeliveryPay)
        assertNull(committed.pendingSessionPay)
    }

    @Test
    fun `zero read with no committed figure clears a positive park before the settle timer`() {
        val parked = step(region(), screen(parsed = ParsedFields.IdleFields(sessionPay = 470.00)))
        assertNull(parked.session?.runningEarnings)
        assertEquals(470.00, parked.pendingSessionPay?.value ?: Double.NaN, 0.0001)

        val contradicted = step(
            parked,
            screen(parsed = ParsedFields.IdleFields(sessionPay = 0.0), timestamp = t0 + 200L),
        )
        assertNull(contradicted.session?.runningEarnings)
        assertNull(contradicted.pendingSessionPay)

        val later = step(contradicted, wake(t0 + GraceConfig.SESSION_PAY_SETTLE_MS))
        assertNull(later.session?.runningEarnings)
        assertNull(later.pendingSessionPay)
    }

    @Test
    fun `zero wheel read leaves null and positive totals unchanged even after the window`() {
        for (earnings in listOf(null, 16.70)) {
            val read = step(region(earnings), screen(parsed = ParsedFields.IdleFields(sessionPay = 0.0)))
            assertEquals(earnings, read.session?.runningEarnings)
            assertNull(read.pendingSessionPay)
            val later = step(read, wake(t0 + GraceConfig.SESSION_PAY_SETTLE_MS))
            assertEquals(earnings, later.session?.runningEarnings)
        }
    }

    private fun completedRegion() = region().copy(
        activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        recentTasks = listOf(
            Task(
                taskId = "drop-1",
                jobId = "job-1",
                phase = TaskPhase.DROPOFF,
                customerNameHash = "customer-hash",
                startedAt = 300L,
                arrivedAt = 400L,
                completedAt = 500L,
            ),
        ),
    )

    private fun completion(prev: PlatformRegion, next: PlatformRegion): DeliveryPayload =
        effectMap.diff(state(prev), state(next), screen())
            .filterIsInstance<AppEffect.LogEvent>()
            .single { it.event.type == AppEventType.DELIVERY_COMPLETED }
            .event.payload as DeliveryPayload

    @Test
    fun `PostTask exit stamps absent session earnings on delivery completion`() {
        val prev = completedRegion().copy(lastActedFlow = Flow.PostTask)
        val payload = completion(prev, prev.copy(lastActedFlow = Flow.Idle))
        assertNull(payload.sessionEarningsAtCompletion)
    }

    @Test
    fun `job close-out stamps absent session earnings on delivery completion`() {
        val prev = completedRegion().copy(lastActedFlow = Flow.Idle)
        val payload = completion(prev, prev.copy(activeJob = null))
        assertNull(payload.sessionEarningsAtCompletion)
    }

    @Test
    fun `early-offline stop stamps no report for absent or parsed zero earnings`() {
        for (earnings in listOf(null, 0.0)) {
            val prev = region(earnings)
            val next = prev.copy(mode = Mode.Offline, session = null)
            val payload = effectMap.diff(state(prev), state(next), screen())
                .filterIsInstance<AppEffect.LogEvent>()
                .single { it.event.type == AppEventType.DASH_STOP }
                .event.payload as SessionStopPayload

            assertEquals(SessionEndSource.EARLY_OFFLINE, payload.source)
            assertNull(payload.totalEarnings)
        }
    }
}
