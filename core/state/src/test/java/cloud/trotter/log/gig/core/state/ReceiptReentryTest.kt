package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1460 — the PostTask ENTRY edge adds the receipt's total to `Session.accumulatedDeliveryPay`
 * ([SessionPaySettleGate.receiptEntry]). An offer pop-up over the receipt walks the acted flow
 * PostTask → OfferPresented → PostTask, and the second entry edge used to add the SAME receipt's
 * pay again. A genuinely different receipt — another drop's, or the same drop's with a different
 * total — must still add, and so must any receipt whose entry is not a direct hand-back from the
 * overlay: the receipt key is not identity, because a job whose task screens were missed names the
 * PREVIOUS job's drop (or no drop) as its subject.
 *
 * Driven through the real [PlatformRegionStepper]; the flow edge comes from the region's own
 * `lastActedFlow`, which the stepper stamps on every flow-bearing frame. Timestamps sit well inside
 * the collapsed receipt's retire grace, so the drop stays active and its job stays open (ownership).
 */
class ReceiptReentryTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()

    private val t0 = 10_000L

    private fun dropoff(id: String, customer: String?, arrivedAt: Long? = null) = Task(
        taskId = id,
        jobId = "J1",
        phase = TaskPhase.DROPOFF,
        customerNameHash = customer?.let { "name-$it" },
        customerAddressHash = customer?.let { "addr-$it" },
        startedAt = 3_000L,
        arrivedAt = arrivedAt,
    )

    /** A live dash sitting on an arrived dropoff [active], about to see its receipt. */
    private fun region(active: Task, placeholders: List<Task> = emptyList()) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("S1", startedAt = 100L),
        activeJob = Job(
            "J1",
            offerStoreHint = emptyList(),
            parentOfferHash = null,
            startedAt = 200L,
            tasks = listOf(active) + placeholders,
        ),
        activeTask = active,
        lastActedFlow = Flow.TaskDropoffArrived,
    )

    private fun screen(flow: Flow, ruleId: String, parsed: ParsedFields, timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = Mode.Online,
        parsed = parsed,
    )

    /** A collapsed receipt: a total and nothing else (the 8 s receipt-expand grace). */
    private fun receipt(totalPay: Double, timestamp: Long) = screen(
        Flow.PostTask,
        "doordash.screen.delivery_summary",
        ParsedFields.PostTaskFields(totalPay = totalPay),
        timestamp,
    )

    /** The offer pop-up landing over the receipt; parses no offer, carries no pay. */
    private fun offerOverlay(timestamp: Long) = screen(
        Flow.OfferPresented,
        "doordash.screen.offer_popup",
        ParsedFields.None,
        timestamp,
    )

    /** The waiting-for-offers map: neither the receipt nor its overlay. */
    private fun idle(timestamp: Long) = screen(
        Flow.Idle,
        "doordash.screen.waiting_for_offer",
        ParsedFields.IdleFields(sessionPay = null),
        timestamp,
    )

    /** Navigation toward the next customer of the same job — a stacked-dropoff transition. */
    private fun navigateTo(customer: String, timestamp: Long) = screen(
        Flow.TaskDropoffNavigation,
        "doordash.screen.dropoff_navigation",
        ParsedFields.TaskFields(
            phase = TaskPhase.DROPOFF,
            subFlow = TaskSubFlow.NAVIGATION,
            customerNameHash = "name-$customer",
            customerAddressHash = "addr-$customer",
        ),
        timestamp,
    )

    private fun step(region: PlatformRegion, flow: Flow, obs: Observation): PlatformRegion = stepper.step(
        region,
        FlowRegion(flow = region.lastActedFlow ?: Flow.Idle, activePlatform = Platform.DoorDash),
        FlowRegion(flow = flow, activePlatform = Platform.DoorDash),
        obs,
        policy,
    )

    private fun accumulated(region: PlatformRegion) = region.session?.accumulatedDeliveryPay ?: Double.NaN

    private fun running(region: PlatformRegion) = region.session?.runningEarnings ?: Double.NaN

    @Test
    fun `a receipt re-entered after an offer pop-up adds its pay once`() {
        val start = region(dropoff("d1", "c1", arrivedAt = 3_500L))

        val onReceipt = step(start, Flow.PostTask, receipt(8.50, t0))
        assertEquals("the receipt is about the active drop", "d1", onReceipt.receiptSubjectTaskId())
        assertEquals("the first entry adds the receipt", 8.50, accumulated(onReceipt), 0.0001)

        val underOverlay = step(onReceipt, Flow.OfferPresented, offerOverlay(t0 + 1_000L))
        val backOnReceipt = step(underOverlay, Flow.PostTask, receipt(8.50, t0 + 2_000L))
        assertEquals("the same receipt, same drop", "d1", backOnReceipt.receiptSubjectTaskId())

        assertEquals(
            "a re-entry after the overlay is the same receipt: added once",
            8.50, accumulated(backOnReceipt), 0.0001,
        )
        assertEquals("and the headline is not doubled", 8.50, running(backOnReceipt), 0.0001)
    }

    @Test
    fun `a different drop's receipt still adds`() {
        // Both receipts show the SAME total, so only the drop tells them apart: this pins the
        // subject half of the key (the same-drop case below pins the pay half).
        val start = region(
            active = dropoff("d1", "c1", arrivedAt = 3_500L),
            placeholders = listOf(dropoff("d2", customer = null)),
        )

        val onFirst = step(start, Flow.PostTask, receipt(8.50, t0))
        assertEquals("d1", onFirst.receiptSubjectTaskId())

        val toSecond = step(onFirst, Flow.TaskDropoffNavigation, navigateTo("c2", t0 + 1_000L))
        assertEquals("the stacked transition activated the second drop", "d2", toSecond.activeTask?.taskId)

        val onSecond = step(toSecond, Flow.PostTask, receipt(8.50, t0 + 2_000L))
        assertEquals("d2", onSecond.receiptSubjectTaskId())

        assertEquals("a second drop's receipt is a new receipt", 17.00, accumulated(onSecond), 0.0001)
        assertEquals(17.00, running(onSecond), 0.0001)
    }

    @Test
    fun `the same drop's receipt with a different total still adds`() {
        // A corrected receipt for the same drop (e.g. a tip landing) is not an identical re-entry.
        val start = region(dropoff("d1", "c1", arrivedAt = 3_500L))

        val onReceipt = step(start, Flow.PostTask, receipt(8.50, t0))
        val underOverlay = step(onReceipt, Flow.OfferPresented, offerOverlay(t0 + 1_000L))
        val backOnReceipt = step(underOverlay, Flow.PostTask, receipt(9.75, t0 + 2_000L))
        assertEquals("d1", backOnReceipt.receiptSubjectTaskId())

        assertEquals(18.25, accumulated(backOnReceipt), 0.0001)
        assertEquals(18.25, running(backOnReceipt), 0.0001)
    }

    @Test
    fun `a new job's receipt naming the previous job's drop still adds`() {
        // Job A's receipt, then A closes on the idle map. Job B is offered and accepted, and every
        // one of B's task screens is missed, so B's receipt arrives straight from the offer screen.
        // With no task of its own, B's receipt names A's drop as its subject and A's closed job as
        // its owner: the same key as A's receipt. Only the broken episode tells them apart.
        val start = region(dropoff("d1", "c1", arrivedAt = 3_500L))

        val onA = step(start, Flow.PostTask, receipt(8.50, t0))
        val idleAfterA = step(onA, Flow.Idle, idle(t0 + 1_000L))
        assertNull("A closed on the idle map", idleAfterA.activeJob)
        assertEquals("J1", idleAfterA.lastClosedJobReceipt?.jobId)

        val offerB = step(idleAfterA, Flow.OfferPresented, offerOverlay(t0 + 2_000L))
        val onB = step(offerB, Flow.PostTask, receipt(8.50, t0 + 3_000L))
        assertEquals("B's receipt falls back to A's drop", "d1", onB.receiptSubjectTaskId())

        assertEquals("B's receipt is new money", 17.00, accumulated(onB), 0.0001)
        assertEquals(17.00, running(onB), 0.0001)
    }

    @Test
    fun `two receipts with no subject and the same total both add`() {
        // No dropoff was ever seen, so neither receipt names a subject.
        val start = region(dropoff("d1", "c1")).let {
            it.copy(activeTask = null, activeJob = it.activeJob?.copy(tasks = emptyList()))
        }

        val onFirst = step(start, Flow.PostTask, receipt(8.50, t0))
        assertNull(onFirst.receiptSubjectTaskId())
        val between = step(onFirst, Flow.Idle, idle(t0 + 1_000L))
        val onSecond = step(between, Flow.PostTask, receipt(8.50, t0 + 2_000L))
        assertNull(onSecond.receiptSubjectTaskId())

        assertEquals(17.00, accumulated(onSecond), 0.0001)
        assertEquals(17.00, running(onSecond), 0.0001)
    }

    @Test
    fun `a receipt reached through another screen after the overlay adds again`() {
        // The trade-off, stated: once any frame other than the receipt or its overlay lands, the
        // machine can no longer tell the same receipt from a new one, so it adds (the pre-#1460
        // behaviour on this path). The headline's max with recorded delivery pay (#1443) bounds it.
        val start = region(dropoff("d1", "c1", arrivedAt = 3_500L))

        val onReceipt = step(start, Flow.PostTask, receipt(8.50, t0))
        val underOverlay = step(onReceipt, Flow.OfferPresented, offerOverlay(t0 + 1_000L))
        val idleBetween = step(underOverlay, Flow.Idle, idle(t0 + 2_000L))
        assertNull("the episode ended on the idle frame", idleBetween.session?.receiptEpisodeKey)
        val backOnReceipt = step(idleBetween, Flow.PostTask, receipt(8.50, t0 + 3_000L))

        assertEquals(17.00, accumulated(backOnReceipt), 0.0001)
    }
}
