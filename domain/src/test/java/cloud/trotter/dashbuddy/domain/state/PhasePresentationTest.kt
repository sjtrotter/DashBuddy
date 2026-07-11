package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the phase-presentation SSOT (#audit-12) to the exact labels and color
 * tokens the two former hand-maintained tables — `statusBadge()` and
 * `PhaseChip()` — produced. This consolidation must not change a single
 * displayed string or color, so the table is asserted byte-for-byte; a future
 * edit that drifts a label/color fails here instead of silently diverging.
 */
class PhasePresentationTest {

    @Test
    fun `long-form status-badge labels and colors match the legacy statusBadge table`() {
        // The exact (label, color) pairs statusBadge() returned for each online flow phase.
        val expected = mapOf(
            Flow.Idle to ("WAITING" to PhaseColor.GOOD),
            Flow.OfferPresented to ("OFFER" to PhaseColor.OFFER),
            Flow.TaskPickupNavigation to ("PICKUP" to PhaseColor.GOOD),
            Flow.TaskPickupArrived to ("AT STORE" to PhaseColor.GOOD),
            Flow.TaskDropoffNavigation to ("DELIVERING" to PhaseColor.GOOD),
            Flow.TaskDropoffArrived to ("AT DOOR" to PhaseColor.GOOD),
            Flow.PostTask to ("DELIVERED" to PhaseColor.GOOD),
            // #736: TaskUnassigned is presented as the Idle-equivalent (a transient teardown).
            Flow.TaskUnassigned to ("WAITING" to PhaseColor.GOOD),
            Flow.SessionEnded to ("DONE" to PhaseColor.NEUTRAL),
        )
        Flow.entries.forEach { flow ->
            val p = flow.presentation
            assertEquals("longLabel for $flow", expected.getValue(flow).first, p.longLabel)
            assertEquals("longColor for $flow", expected.getValue(flow).second, p.longColor)
        }
    }

    @Test
    fun `short-form chip labels and colors match the legacy PhaseChip table`() {
        // The exact (label, color) pairs PhaseChip() produced, resolved via flowPhase().
        val cases = listOf(
            FlowCardSnapshot.Awaiting(id = "a", phaseStartedAt = 0L) to ("AWAIT" to PhaseColor.NEUTRAL),
            FlowCardSnapshot.Offer(phaseStartedAt = 0L, offerHash = "h") to ("OFFER" to PhaseColor.OFFER),
            FlowCardSnapshot.Pickup(phaseStartedAt = 0L, taskId = "t", jobId = "j", storeName = "s")
                to ("PICKUP" to PhaseColor.PICKUP),
            FlowCardSnapshot.Delivery(phaseStartedAt = 0L, taskId = "t", jobId = "j")
                to ("DROPOFF" to PhaseColor.PICKUP),
            FlowCardSnapshot.PostTask(phaseStartedAt = 0L, jobId = "j")
                to ("PAID" to PhaseColor.GOOD),
        )
        cases.forEach { (snap, expected) ->
            val p = snap.flowPhase().presentation
            assertEquals("shortLabel for ${snap::class.simpleName}", expected.first, p.shortLabel)
            assertEquals("shortColor for ${snap::class.simpleName}", expected.second, p.shortColor)
        }
    }

    @Test
    fun `every Flow phase has a presentation`() {
        // Exhaustive `when` guarantees this; the assertion documents the contract.
        Flow.entries.forEach { flow ->
            val p = flow.presentation
            assertEquals("non-blank longLabel for $flow", false, p.longLabel.isBlank())
            assertEquals("non-blank shortLabel for $flow", false, p.shortLabel.isBlank())
        }
    }
}
