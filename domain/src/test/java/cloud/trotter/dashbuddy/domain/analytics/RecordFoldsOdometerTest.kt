package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.*
import cloud.trotter.dashbuddy.domain.model.event.payload.OdometerReadingPayload
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.*
import org.junit.Test

class RecordFoldsOdometerTest {
    private val context = SessionFoldContext("dash", Platform.DoorDash, 100, 110,
        startOdometer = 1.0, lastOdometer = 2.0)
    private fun event(session: String? = null) = SequencedAppEvent(10,
        AppEvent(AppEventType.ODOMETER_READING, 1_000, session, OdometerReadingPayload("civic", 52_388, 999.0)),
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
        // Membership keeps odometer readings on the bookkeeping bypass.
        assertTrue(AppEventType.ODOMETER_READING in RecordFolds.BOOKKEEPING_EVENTS)
    }
}
