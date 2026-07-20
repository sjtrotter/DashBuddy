package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.JobAcceptMismatchPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #810 B1 — the [diffJobClose] effect-edge tripwire, in isolation. Focus: the endSession exclusion
 * must key on session IDENTITY, not presence (the same-step end+mint shape, #818 adversarial Finding 1).
 */
class JobCloseEffectsTest {

    private val effectMap = EffectMap()

    private fun obs(): Observation = Observation.Screen(
        timestamp = 5000L, captureId = null, ruleId = "test.rule", metadata = ReplayMetadata.EMPTY,
        flow = null, modeHint = null, parsed = ParsedFields.None,
    )

    /** A job carrying a mismatch shape (2 accepts) so the tripwire WOULD fire if the edge admits it. */
    private fun mismatchJob(id: String) = Job(
        jobId = id,
        offerStoreHint = emptyList(),
        parentOfferHash = null,
        acceptedOffers = listOf(
            AcceptedOfferEconomics(offerHash = "hA", payAmount = 28.5, acceptedAt = 100L),
            AcceptedOfferEconomics(offerHash = "hB", payAmount = 16.14, acceptedAt = 200L),
        ),
        tasks = listOf(Task(taskId = "d-tbd", jobId = id, phase = TaskPhase.DROPOFF, startedAt = 100L)),
        startedAt = 100L,
    )

    private fun deliveredDrop(jobId: String) = Task(
        taskId = "d1", jobId = jobId, phase = TaskPhase.DROPOFF,
        customerNameHash = "c1", startedAt = 100L, arrivedAt = 200L, completedAt = 300L,
    )

    private fun mismatches(effects: List<AppEffect>): List<JobAcceptMismatchPayload> =
        effects.filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.JOB_ACCEPT_MISMATCH }
            .map { it.event.payload as JobAcceptMismatchPayload }

    @Test
    fun `a real close within the same session fires the tripwire`() {
        val session = Session("dash-A", startedAt = 50L)
        val job = mismatchJob("J1")
        val prev = PlatformRegion(Platform.DoorDash, session = session, activeJob = job)
        val next = PlatformRegion(
            Platform.DoorDash, session = session, activeJob = null,
            recentTasks = listOf(deliveredDrop("J1")),
        )
        val m = mismatches(effectMap.diffJobClose(prev, next, obs()))
        assertEquals("the in-session close fires exactly one tripwire", 1, m.size)
        assertEquals(2, m.single().acceptedCount)
        assertEquals(1, m.single().accountedCount)
    }

    @Test
    fun `a same-step endSession-plus-mint does NOT fire (session identity, not presence)`() {
        // The stale-grace endSession commits (session A → null, job → null) and the SAME Online frame
        // mints a FRESH session B — so next has NO job but a DIFFERENT (present, non-null) session. A
        // presence-only guard would fire the tripwire here for the out-of-scope offline-mid-job class,
        // mis-attributed to session B. The identity guard (next.session.id != prev.session.id) blocks it.
        val sessionA = Session("dash-A", startedAt = 50L)
        val sessionB = Session("dash-B", startedAt = 6000L)
        val prev = PlatformRegion(Platform.DoorDash, session = sessionA, activeJob = mismatchJob("J1"))
        val next = PlatformRegion(
            Platform.DoorDash, session = sessionB, activeJob = null,
            recentTasks = listOf(deliveredDrop("J1")),
        )
        assertEquals(
            "endSession+mint in one step is out of scope — the fresh session must not admit the tripwire",
            0, mismatches(effectMap.diffJobClose(prev, next, obs())).size,
        )
    }

    @Test
    fun `a plain endSession (session cleared) does NOT fire`() {
        val sessionA = Session("dash-A", startedAt = 50L)
        val prev = PlatformRegion(Platform.DoorDash, session = sessionA, activeJob = mismatchJob("J1"))
        val next = PlatformRegion(
            Platform.DoorDash, session = null, activeJob = null,
            recentTasks = listOf(deliveredDrop("J1")),
        )
        assertEquals(
            "a session-clearing endSession is out of scope (v1)",
            0, mismatches(effectMap.diffJobClose(prev, next, obs())).size,
        )
    }
}
