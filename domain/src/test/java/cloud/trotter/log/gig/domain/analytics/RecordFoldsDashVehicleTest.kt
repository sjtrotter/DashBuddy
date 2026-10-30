package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.*
import cloud.trotter.log.gig.domain.model.event.payload.DashVehiclePayload
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.*
import org.junit.Test

class RecordFoldsDashVehicleTest {
    private val context = SessionFoldContext("dash", Platform.DoorDash, 100, 110,
        startOdometer = 1.0, lastOdometer = 2.0)
    private fun event(session: String? = null) = SequencedAppEvent(10,
        AppEvent(AppEventType.DASH_VEHICLE, 1_000, session, DashVehiclePayload("dash", "civic", 999.0, "START")),
        EventMetadata(odometer = 999.0))
    @Test fun `folds to nothing without a session`() {
        assertEquals(FoldOutcome(), RecordFolds.foldEvent(event(), null, 0.4))
    }
    @Test fun `folds to nothing with a session context present`() {
        val result = RecordFolds.foldEvent(event("dash"), context, 0.4)
        assertEquals(FoldOutcome(), result)
        assertNull(result.context)
        assertNull(result.skip)
    }
    @Test fun `is a bookkeeping event`() {
        // Membership keeps dash vehicle stamps on the bookkeeping bypass.
        assertTrue(AppEventType.DASH_VEHICLE in RecordFolds.BOOKKEEPING_EVENTS)
    }
}
