package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.EventMetadata
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionMilesCorrectionPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionMilesOperation
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMilesCorrectionFoldTest {
    private val ended = SessionFoldContext("1152", Platform.DoorDash, 100L, 200L, endedAt = 200L,
        startOdometer = 100.0, lastOdometer = 159.4)
    private val notEnded = "SESSION_MILES_CORRECTION: target session unknown or not ended at this log position"

    private fun ev(payload: AppEventPayload?, envelopeSession: String? = "1152", odometer: Double? = null) = SequencedAppEvent(
        sequenceId = 9,
        event = AppEvent(AppEventType.SESSION_MILES_CORRECTION, 999_999L, envelopeSession, payload),
        metadata = odometer?.let { EventMetadata(odometer = it) },
    )

    private fun set(miles: Double?) = SessionMilesCorrectionPayload("1152", SessionMilesOperation.SET, miles)

    @Test
    fun `is a bookkeeping event`() {
        assertTrue(AppEventType.SESSION_MILES_CORRECTION in RecordFolds.BOOKKEEPING_EVENTS)
    }

    @Test
    fun `SET and RESTORE_MACHINE dispatch a decision and carry no context back`() {
        for ((payload, expected) in listOf(
            set(2.5) to SessionMilesCorrectionFold("1152", SessionMilesOperation.SET, 2.5),
            set(0.0) to SessionMilesCorrectionFold("1152", SessionMilesOperation.SET, 0.0),
            SessionMilesCorrectionPayload("1152", SessionMilesOperation.RESTORE_MACHINE) to
                SessionMilesCorrectionFold("1152", SessionMilesOperation.RESTORE_MACHINE, null),
        )) {
            // An odometer stamp on the correction never measures the shift (bookkeeping bypass).
            val outcome = RecordFolds.foldEvent(ev(payload, odometer = 999.0), ended, null)
            assertNull(outcome.context)
            assertNull(outcome.skip)
            assertEquals(expected, outcome.sessionMilesCorrection)
        }
    }

    @Test
    fun `mismatched session ids are skipped`() {
        val cases = listOf(
            ev(set(2.5), envelopeSession = "other"),
            ev(set(2.5), envelopeSession = null),
            ev(SessionMilesCorrectionPayload("other", SessionMilesOperation.SET, 2.5)),
        )
        for (event in cases) {
            val outcome = RecordFolds.foldEvent(event, ended, null)
            assertNull(outcome.sessionMilesCorrection)
            assertNull(outcome.context)
            assertEquals(notEnded, outcome.skip)
        }
        val otherContext = ended.copy(sessionId = "other")
        assertEquals(notEnded, RecordFolds.foldEvent(ev(set(2.5)), otherContext, null).skip)
    }

    @Test
    fun `a correction before the shift ended, or for an unknown shift, is skipped`() {
        for (ctx in listOf(ended.copy(endedAt = null), null)) {
            val outcome = RecordFolds.foldEvent(ev(set(2.5)), ctx, null)
            assertNull(outcome.sessionMilesCorrection)
            assertNull(outcome.context)
            assertEquals(notEnded, outcome.skip)
        }
    }

    @Test
    fun `an unknown operation, an out of bounds SET or a malformed payload is skipped`() {
        assertEquals("SESSION_MILES_CORRECTION: unknown operation",
            RecordFolds.foldEvent(ev(SessionMilesCorrectionPayload("1152", "CLEAR")), ended, null).skip)
        for (bad in listOf(null, -1.0, 2_000.5, Double.NaN)) {
            val outcome = RecordFolds.foldEvent(ev(set(bad)), ended, null)
            assertNull(outcome.sessionMilesCorrection)
            assertEquals("SESSION_MILES_CORRECTION: SET value out of bounds", outcome.skip)
        }
        for (payload in listOf(null, SessionStopPayload("1152", 200L, SessionEndSource.EARLY_OFFLINE))) {
            val outcome = RecordFolds.foldEvent(ev(payload), ended, null)
            assertNull(outcome.sessionMilesCorrection)
            assertEquals("SESSION_MILES_CORRECTION: missing/malformed payload", outcome.skip)
        }
    }

    @Test
    fun `repeated SET then RESTORE_MACHINE ends on the tracked miles`() {
        var override: Double? = null
        for (payload in listOf(set(5.0), set(7.25), SessionMilesCorrectionPayload("1152", SessionMilesOperation.RESTORE_MACHINE))) {
            override = RecordFolds.foldEvent(ev(payload), ended, null).sessionMilesCorrection!!.miles
        }
        assertNull(override)
        assertEquals(59.4, SessionMilesRule.effective(ended.startOdometer, ended.lastOdometer, override)!!, 1e-9)
    }
}
