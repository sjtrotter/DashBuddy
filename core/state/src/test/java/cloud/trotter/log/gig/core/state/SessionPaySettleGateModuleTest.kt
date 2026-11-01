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
import cloud.trotter.log.gig.domain.state.mintWakeId
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
        wakeId = PlatformRegion(Platform.DoorDash, wakeSeq = (7L) - 1L).mintWakeId().second,
    )

    private fun screen(flow: Flow) = Observation.Screen(
        timestamp = now,
        captureId = null,
        ruleId = "doordash.screen.any",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = Mode.Online,
        parsed = ParsedFields.None,
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
            SessionPaySettleGate.receiptEntry(before, ParsedFields.PostTaskFields(totalPay = 8.0), Flow.TaskDropoffArrived, now),
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
                            // #1460: job-1 owns it, and no dropoff on the region makes the subject "?".
                            receiptEpisodeKey = "job-1|?|${Math.round(totalPay * 100)}",
                        ),
                        pendingSessionPay = null,
                    ),
                    SessionPaySettleGate.receiptEntry(
                        before, ParsedFields.PostTaskFields(totalPay = totalPay), Flow.TaskDropoffArrived, now,
                    ),
                )
            }
        }
    }

    @Test
    fun `an identical receipt re-entered from the offer overlay is not added again but still drops older parks`() {
        val first = SessionPaySettleGate.receiptEntry(
            region().copy(activeJob = liveJob), ParsedFields.PostTaskFields(totalPay = 8.0), Flow.TaskDropoffArrived, now,
        )
        val before = first.copy(pendingSessionPay = park(now))
        val later = now + 2_000L

        assertEquals(
            "#1460: same key, entered from the overlay, so only the older park goes",
            before.copy(pendingSessionPay = null),
            SessionPaySettleGate.receiptEntry(
                before, ParsedFields.PostTaskFields(totalPay = 8.0), Flow.OfferPresented, later,
            ),
        )
    }

    @Test
    fun `a matching key entered from anywhere but the overlay still adds`() {
        val first = SessionPaySettleGate.receiptEntry(
            region().copy(activeJob = liveJob), ParsedFields.PostTaskFields(totalPay = 8.0), Flow.TaskDropoffArrived, now,
        )
        val again = SessionPaySettleGate.receiptEntry(
            first, ParsedFields.PostTaskFields(totalPay = 8.0), Flow.Idle, now + 2_000L,
        )

        assertEquals(26.0, again.session?.accumulatedDeliveryPay ?: Double.NaN, 0.0001)
    }

    @Test
    fun `the receipt episode survives only the receipt and its overlay`() {
        val keyed = region().let {
            it.copy(session = it.session!!.copy(receiptEpisodeKey = "job-1|?|800"))
        }
        val kept = listOf(
            Flow.PostTask to Flow.PostTask,
            Flow.PostTask to Flow.OfferPresented,
            Flow.OfferPresented to Flow.OfferPresented,
            Flow.OfferPresented to Flow.PostTask,
        )
        for ((from, to) in kept) {
            assertEquals("$from -> $to", keyed, SessionPaySettleGate.receiptEpisode(keyed, from, screen(to)))
        }
        val cleared = listOf(
            Flow.PostTask to Flow.Idle,
            Flow.OfferPresented to Flow.Idle,
            Flow.OfferPresented to Flow.TaskPickupNavigation,
            Flow.Idle to Flow.OfferPresented,
        )
        for ((from, to) in cleared) {
            assertEquals(
                "$from -> $to",
                keyed.copy(session = keyed.session!!.copy(receiptEpisodeKey = null)),
                SessionPaySettleGate.receiptEpisode(keyed, from, screen(to)),
            )
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
