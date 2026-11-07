package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.log.gig.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1517: the fold emits the recorded unassign's offer stamp and a missed delivery's source link as decisions. */
class OfferRepairFoldsTest {

    private fun fold(type: AppEventType, sessionId: String?, payload: AppEventPayload) = RecordFolds.foldEvent(
        SequencedAppEvent(sequenceId = 42, event = AppEvent(type = type, occurredAt = 30, sessionId = sessionId, payload = payload)),
        context = null,
        currentCostPerMile = null,
    )

    private fun unassign(hashes: List<String>) = TaskUnassignedPayload(
        jobId = "J1", taskId = "T1", phase = TaskPhase.PICKUP, startedAt = 10, unassignedAt = 20, jobOfferHashes = hashes,
    )

    @Test fun `a recorded unassign stamps its job's distinct offer hashes before itself`() {
        val out = fold(AppEventType.TASK_UNASSIGNED, "S", unassign(listOf("a", "b", "a", " ")))
        assertEquals(OfferUnassignFold("S", listOf("a", "b"), 20, beforeSequenceId = 42), out.offerUnassign)
        // The leg-state arm still runs: the session context advances as before.
        assertEquals("S", out.context?.sessionId)
    }

    @Test fun `an unassign with no hashes or no session stamps nothing`() {
        assertNull(fold(AppEventType.TASK_UNASSIGNED, "S", unassign(emptyList())).offerUnassign)
        assertNull(fold(AppEventType.TASK_UNASSIGNED, null, unassign(listOf("a"))).offerUnassign)
    }

    @Test fun `a missed delivery from an offer carries its source link, an ordinary one none`() {
        val from = fold(
            AppEventType.MANUAL_DELIVERY, "S",
            ManualDeliveryPayload(sessionId = "S", pay = 7.5, completedAt = 25, fromOfferEventSequenceId = 7),
        )
        assertEquals(ManualSourceLinkFold(deliverySequenceId = 42, sessionId = "S", offerSequenceId = 7), from.manualSourceLink)
        assertEquals(42L, from.delivery?.eventSequenceId)
        val plain = fold(AppEventType.MANUAL_DELIVERY, "S", ManualDeliveryPayload(sessionId = "S", pay = 7.5, completedAt = 25))
        assertNull(plain.manualSourceLink)
    }
}
