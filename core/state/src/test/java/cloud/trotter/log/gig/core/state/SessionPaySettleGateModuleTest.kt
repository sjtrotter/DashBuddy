package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.ClosedJobReceipt
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingSessionPay
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Test

/** Direct module contracts; the existing settle suites exercise the unchanged stepper paths. */
class SessionPaySettleGateModuleTest {
    private val policy = TransitionPolicy()
    private val now = 10_000L
    private val flow = FlowRegion(flow = Flow.Idle, activePlatform = Platform.DoorDash)
    private val liveJob = Job(
        jobId = "job-1",
        offerStoreHint = emptyList(),
        parentOfferHash = null,
        startedAt = 200L,
    )

    // SessionPaySettleGateTest's builders are private; use the same small region shape here.
    private fun region(pending: PendingSessionPay? = null) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session(
            sessionId = "s1",
            startedAt = 100L,
            runningEarnings = 16.70,
            accumulatedDeliveryPay = 10.0,
        ),
        lastObservedAt = 500L,
        pendingSessionPay = pending,
        wakeSeq = 7L,
    )

    private fun park(since: Long = now) = PendingSessionPay(
        value = 25.20,
        since = since,
        deadline = since + policy.sessionPaySettleMs(Platform.DoorDash),
        flow = Flow.Idle,
        wakeId = 7L,
    )

    private fun idleScreen(timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.IdleFields(sessionPay = null),
    )

    @Test
    fun `summary total drops only parks older than the write`() {
        for (since in listOf(now - 1L, now, now + 1L)) {
            val pending = park(since)
            val before = region(pending)
            val after = SessionPaySettleGate.summaryTotal(before, 30.0, now)

            assertEquals(
                "park since=$since, summary at=$now",
                before.copy(
                    session = before.session!!.copy(runningEarnings = 30.0),
                    pendingSessionPay = pending.takeIf { since >= now },
                ),
                after,
            )
        }
    }

    @Test
    fun `receipt entry without an owned job leaves the entire region unchanged`() {
        val before = region(park(now - 1L))

        assertEquals(
            before,
            SessionPaySettleGate.receiptEntry(before, ParsedFields.PostTaskFields(totalPay = 8.0), now),
        )
    }

    @Test
    fun `owned receipt entry accumulates and keeps the maximum running total`() {
        val owners = listOf(
            region(park(now - 1L)).copy(activeJob = liveJob),
            region(park(now - 1L)).copy(lastClosedJobReceipt = ClosedJobReceipt(jobId = "job-1")),
        )
        for (before in owners) {
            for ((totalPay, accumulated, running) in listOf(
                Triple(5.0, 15.0, 16.70),
                Triple(8.0, 18.0, 18.0),
            )) {
                assertEquals(
                    before.copy(
                        session = before.session!!.copy(
                            accumulatedDeliveryPay = accumulated,
                            runningEarnings = running,
                        ),
                        pendingSessionPay = null,
                    ),
                    SessionPaySettleGate.receiptEntry(
                        before, ParsedFields.PostTaskFields(totalPay = totalPay), now,
                    ),
                )
            }
        }
    }

    @Test
    fun `lapse commits exactly at the deadline on a frame`() {
        val pending = park()
        val before = region(pending)

        assertEquals(
            before.copy(
                session = before.session!!.copy(runningEarnings = pending.value),
                pendingSessionPay = null,
            ),
            SessionPaySettleGate.lapse(before, flow, flow, idleScreen(pending.deadline)),
        )
    }

    @Test
    fun `lapse keeps the park one millisecond before the deadline`() {
        val pending = park()
        val before = region(pending)

        assertEquals(
            before,
            SessionPaySettleGate.lapse(before, flow, flow, idleScreen(pending.deadline - 1L)),
        )
    }

    @Test
    fun `zero pill read never replaces a positive total or its park`() {
        val before = region(park())

        assertEquals(before, SessionPaySettleGate.pillRead(before, 0.0, Flow.Idle, now, policy))
    }

    @Test
    fun `session boundary drops only the park`() {
        val before = region(park())

        assertEquals(before.copy(pendingSessionPay = null), SessionPaySettleGate.sessionBoundary(before))
    }
}
