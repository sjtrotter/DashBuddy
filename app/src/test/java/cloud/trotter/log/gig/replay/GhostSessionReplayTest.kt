package cloud.trotter.log.gig.replay

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1311 Level B: mixed corpus replay of a summary followed by the re-shown dash-controls sheet.
 * The 2026-10-09 ghost's frames were dedup-suppressed, so these are existing, unmodified corpus
 * captures with explicit observation times, not a claim to replay the missing capture sequence.
 */
class GhostSessionReplayTest {
    private val waiting = "snapshots/waiting_for_offer/7416147f.json"
    private val summary = "snapshots/dash_summary/20260128_184416_408_DASH_SUMMARY_SCREEN.json"
    private val idle = "snapshots/idle_map/20260128_184418_211_MAIN_MAP_IDLE.json"
    // Carries "Dash ends at" + "Pause orders": Online hint, deliberately NO flow.
    private val timeline = "snapshots/timeline/ac5be6a4.json"

    private fun frame(path: String, at: Long) = SessionReplay.ScreenInput(SessionReplay.loadScreenFrame(path, at))

    private fun prefix() = listOf(
        frame(waiting, 0L),
        frame(summary, 3_000L),
        frame(idle, 5_000L),
        frame(timeline, 11_000L),
    )

    private fun SessionReplay.ReplayStep.region() = stateAfter.regions.platforms.getValue(Platform.DoorDash)

    private fun assertPendingTimeline(steps: List<SessionReplay.ReplayStep>) {
        assertEquals(
            listOf("waiting_for_offer", "dash_summary", "idle_map", "timeline"),
            steps.take(4).map { (it.observation as Observation.Screen).target },
        )
        val sheet = steps[3]
        assertNull((sheet.observation as Observation.Screen).flow)
        assertEquals(Mode.Online, sheet.observation.modeHint)
        assertEquals(Mode.Offline, sheet.region().mode)
        assertNull(sheet.region().session)
        assertEquals(3_000L, sheet.region().lastSessionEndedAt)
        assertEquals(11_000L, sheet.region().pendingModeResume!!.since)
    }

    @Test
    fun `re-shown controls followed by idle never starts a second dash`() {
        val steps = SessionReplay.reduceMixed(prefix() + frame(idle, 16_000L))
        val trace = SessionReplay.trace(steps)
        assertPendingTimeline(steps)
        val events = steps.flatMap { it.events }
        assertEquals(trace, 1, events.count { it.type == AppEventType.DASH_START })
        assertEquals(trace, 1, events.count { it.type == AppEventType.DASH_STOP })
        assertEquals(Mode.Offline, steps.last().region().mode)
        assertNull(steps.last().region().session)
        assertNull(steps.last().region().pendingModeResume)
    }

    @Test
    fun `controls followed by sustained waiting starts a second dash at the controls instant`() {
        val steps = SessionReplay.reduceMixed(prefix() + listOf(frame(waiting, 20_000L), frame(waiting, 40_000L)))
        val trace = SessionReplay.trace(steps)
        assertPendingTimeline(steps)
        val events = steps.flatMap { it.events }
        val starts = events.filter { it.type == AppEventType.DASH_START }
        assertEquals(trace, 2, starts.size)
        assertEquals(trace, 1, events.count { it.type == AppEventType.DASH_STOP })
        val session = steps.last().region().session
        assertNotNull(session)
        assertEquals(11_000L, session!!.startedAt)
        assertNotEquals(steps.first().region().session!!.sessionId, session.sessionId)
        assertEquals(steps[4].region().session!!.sessionId, session.sessionId)
        assertEquals(11_000L, (starts.last().payload as SessionStartPayload).startedAt)
        assertNull(steps.last().region().pendingModeResume)
    }
}
