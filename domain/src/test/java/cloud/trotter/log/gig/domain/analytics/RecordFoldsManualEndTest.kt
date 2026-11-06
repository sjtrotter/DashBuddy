package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1533 D8: a shift ended by hand folds like any non-summary end, with no platform report. */
class RecordFoldsManualEndTest {
    private val sid = "session-A"

    private fun fold(): SessionFoldContext {
        val start = SequencedAppEvent(1, AppEvent(AppEventType.DASH_START, 1_000, sid,
            SessionStartPayload(sid, Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "WaitingForOffer")))
        val stop = SequencedAppEvent(2, AppEvent(AppEventType.DASH_STOP, 9_000, sid,
            SessionStopPayload(sid, endedAt = 3_000, source = SessionEndSource.MANUAL, totalEarnings = null,
                platform = Platform.DoorDash.name)))
        val ctx = RecordFolds.foldEvent(start, null, null).context
        return RecordFolds.foldEvent(stop, ctx, null).context!!
    }

    @Test
    fun `a manual stop takes its end time from the payload and reports no earnings`() {
        val ctx = fold()
        assertEquals(3_000L, ctx.endedAt)
        assertEquals(SessionEndSource.MANUAL, ctx.endSource)
        assertNull(ctx.reportedEarnings)
    }

    @Test
    fun `the read-side rule reads a manual end as no report`() {
        val ctx = fold()
        assertNull(SessionReportRule.effectiveReported(ctx.reportedEarnings, ctx.endSource, null, null, 12.0))
    }
}
