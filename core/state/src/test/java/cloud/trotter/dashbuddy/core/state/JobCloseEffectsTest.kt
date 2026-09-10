package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.JobAcceptMismatchPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #810 B1 + #1095 — the [diffJobClose] effect-edge tripwire, in isolation.
 *
 * v1 kept every session-end close OUT of scope, guarding on session IDENTITY so the same-step
 * end+mint shape could not be mis-attributed to the fresh session. #1095 keeps the attribution fix
 * (the job's OWN session, prev first) and drops the exclusion: a dash that ends on top of a stranded
 * accept is the money-losing shape the tripwire exists for (#1078). The evidence it reads is
 * mint-qualified, so `endSession`'s force-stamp cannot silence its own close.
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

    /** #1095: one accept — below the in-session floor of 2, at the floor of 1 on a session end. */
    private fun singleAcceptJob(id: String) = Job(
        jobId = id,
        offerStoreHint = emptyList(),
        parentOfferHash = null,
        acceptedOffers = listOf(
            AcceptedOfferEconomics(offerHash = "hA", payAmount = 21.0, acceptedAt = 100L),
        ),
        tasks = emptyList(),
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

    private fun sessionIds(effects: List<AppEffect>): List<String?> =
        effects.filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.JOB_ACCEPT_MISMATCH }
            .map { it.event.sessionId }

    @Test
    fun `a real close within the same session fires the tripwire`() {
        val session = Session("dash-A", startedAt = 50L)
        val job = mismatchJob("J1")
        val prev = PlatformRegion(
            Platform.DoorDash, session = session, activeJob = job,
            // Completed BEFORE this step, so the mask keeps it (mintQualified arm a).
            recentTasks = listOf(deliveredDrop("J1")),
        )
        val next = PlatformRegion(
            Platform.DoorDash, session = session, activeJob = null,
            recentTasks = listOf(deliveredDrop("J1")),
        )
        val effects = effectMap.diffJobClose(prev, next, obs())
        val m = mismatches(effects)
        assertEquals("the in-session close fires exactly one tripwire", 1, m.size)
        assertEquals(2, m.single().acceptedCount)
        assertEquals(1, m.single().accountedCount)
        assertEquals(listOf("dash-A"), sessionIds(effects))
    }

    @Test
    fun `a same-step endSession-plus-mint fires, attributed to the ENDED session`() {
        // The stale-grace endSession commits (session A → null, job → null) and the SAME Online frame
        // mints a FRESH session B — so next has NO job but a DIFFERENT (present, non-null) session.
        // v1 dropped this edge entirely to avoid mis-attributing it to session B. #1095 keeps the
        // edge and fixes the attribution instead: the closing job lived in session A, so that is the
        // session the event carries.
        val sessionA = Session("dash-A", startedAt = 50L)
        val sessionB = Session("dash-B", startedAt = 6000L)
        val prev = PlatformRegion(
            Platform.DoorDash, session = sessionA, activeJob = mismatchJob("J1"),
            recentTasks = listOf(deliveredDrop("J1")),
        )
        val next = PlatformRegion(
            Platform.DoorDash, session = sessionB, activeJob = null,
            recentTasks = listOf(deliveredDrop("J1")),
        )
        val effects = effectMap.diffJobClose(prev, next, obs())
        val m = mismatches(effects)
        assertEquals("the end+mint close is in scope (#1095)", 1, m.size)
        assertEquals(2, m.single().acceptedCount)
        assertEquals(1, m.single().accountedCount)
        assertEquals(
            "attributed to the job's OWN session, never the freshly minted one",
            listOf("dash-A"), sessionIds(effects),
        )
    }

    @Test
    fun `a plain endSession fires, attributed to the ended session`() {
        val sessionA = Session("dash-A", startedAt = 50L)
        val prev = PlatformRegion(
            Platform.DoorDash, session = sessionA, activeJob = mismatchJob("J1"),
            recentTasks = listOf(deliveredDrop("J1")),
        )
        val next = PlatformRegion(
            Platform.DoorDash, session = null, activeJob = null,
            recentTasks = listOf(deliveredDrop("J1")),
        )
        val effects = effectMap.diffJobClose(prev, next, obs())
        assertEquals("a session-clearing endSession is in scope (#1095)", 1, mismatches(effects).size)
        assertEquals(listOf("dash-A"), sessionIds(effects))
    }

    @Test
    fun `an in-session single-accept close stays silent (the floor of 2 holds)`() {
        val session = Session("dash-A", startedAt = 50L)
        val prev = PlatformRegion(
            Platform.DoorDash, session = session, activeJob = singleAcceptJob("J1"),
        )
        val next = PlatformRegion(Platform.DoorDash, session = session, activeJob = null)
        assertEquals(
            "one accept closing drop-less in-session is the early-offline class, not this signal",
            0, mismatches(effectMap.diffJobClose(prev, next, obs())).size,
        )
    }

    @Test
    fun `a teardown whose force-stamped drop is unqualified fires 1 of 0`() {
        // #1078 T3: `endSession` force-stamps `completedAt` on the still-active, un-retired task. That
        // stamp mints no row, so the tripwire must not read it as a delivery — the mask reverts it.
        val sessionA = Session("dash-A", startedAt = 50L)
        val active = Task(
            taskId = "d1", jobId = "J1", phase = TaskPhase.DROPOFF,
            customerNameHash = "c1", startedAt = 100L,
        )
        val prev = PlatformRegion(
            Platform.DoorDash, session = sessionA, activeJob = singleAcceptJob("J1"),
            activeTask = active,
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.SESSION_END, since = 4000L, deadline = 6500L,
                authoritative = true,
            ),
        )
        val next = PlatformRegion(
            Platform.DoorDash, session = null, activeJob = null, activeTask = null,
            // The teardown's force-stamp, arrival and all.
            recentTasks = listOf(active.copy(arrivedAt = 4500L, completedAt = 5000L)),
        )
        val effects = effectMap.diffJobClose(prev, next, obs())
        val m = mismatches(effects)
        assertEquals("the stranded accept is visible at last (#1078/#1095)", 1, m.size)
        assertEquals(1, m.single().acceptedCount)
        assertEquals("an unqualified force-stamp is not a delivery", 0, m.single().accountedCount)
        assertEquals(listOf("dash-A"), sessionIds(effects))
    }

    @Test
    fun `a teardown that HONORED the retire is silent — the drop is accounted`() {
        // #1078: the SESSION_END absorbed a live TASK_RETIRE, so the completion is real (it mints a
        // DELIVERY_COMPLETED) and the drop is accounted 1 of 1. Nothing was stranded; stay quiet.
        val sessionA = Session("dash-A", startedAt = 50L)
        val active = Task(
            taskId = "d1", jobId = "J1", phase = TaskPhase.DROPOFF,
            customerNameHash = "c1", startedAt = 100L, arrivedAt = 4000L,
        )
        val prev = PlatformRegion(
            Platform.DoorDash, session = sessionA, activeJob = singleAcceptJob("J1"),
            activeTask = active,
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.SESSION_END, since = 4600L, deadline = 7100L,
                authoritative = true, absorbedRetireSince = 4600L,
            ),
        )
        val next = PlatformRegion(
            Platform.DoorDash, session = null, activeJob = null, activeTask = null,
            recentTasks = listOf(active.copy(completedAt = 4600L)),
        )
        assertEquals(
            "an honored teardown accounts its drop — no tripwire",
            0, mismatches(effectMap.diffJobClose(prev, next, obs())).size,
        )
    }
}
