package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PickupActivity
import cloud.trotter.log.gig.domain.state.PendingModeResume
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import cloud.trotter.log.gig.domain.state.mintWakeId
import cloud.trotter.log.gig.domain.state.PendingWake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1533: the dasher ends a live shift by hand. */
class ManualShiftEndTest {
    private val platform = Platform.DoorDash
    private val t0 = 1_000_000L
    private val session = Session("session-A", startedAt = t0)
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(),
        TransitionPolicy(), EffectMap(),
    )

    private fun state(region: PlatformRegion) = AppState(regions = Regions(platforms = mapOf(platform to region)))

    private fun live(mode: Mode = Mode.Online) = PlatformRegion(platform, mode = mode, session = session)

    private fun end(at: Long, endAt: Long?, sessionId: String? = session.sessionId) = Observation.UiInput(
        timestamp = at, action = Observation.UiInput.ACTION_END_SHIFT,
        targetPlatform = platform, sessionId = sessionId, endAt = endAt,
    )

    private fun screen(at: Long, flow: Flow?, parsed: ParsedFields = ParsedFields.None, modeHint: Mode? = Mode.Online) =
        Observation.Screen(
            timestamp = at, captureId = null, ruleId = "doordash.screen.test", metadata = ReplayMetadata.EMPTY,
            flow = flow, modeHint = modeHint, parsed = parsed,
        )

    private fun AppState.region() = regions.platforms.getValue(platform)

    private fun stops(effects: List<AppEffect>) = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.DASH_STOP }

    @Test
    fun `a matching command ends the shift at the chosen time and leaves the region Offline`() {
        val wake = PlatformRegion(platform, wakeSeq = 5L).mintWakeId().second
        val paused = live(Mode.Paused).copy(
            pauseSafety = PendingWake(deadline = t0 + 900_000L, wakeId = wake),
            pendingModeResume = PendingModeResume(since = t0 + 1_000L, deadline = t0 + 9_000L, wakeId = wake),
        )
        val chosen = t0 + 60_000L
        val step = machine.step(state(paused), end(t0 + 3_600_000L, chosen))
        val r = step.newState.region()

        assertNull(r.session)
        assertEquals(Mode.Offline, r.mode)
        assertNull(r.pauseSafety)
        assertNull(r.pendingModeResume)
        assertNull(r.pendingDestructive)
        assertNull(r.pendingSessionPay)
        assertEquals(chosen, r.lastSessionEndedAt)

        val stop = stops(step.effects).single()
        val payload = stop.event.payload as SessionStopPayload
        assertEquals(SessionEndSource.MANUAL, payload.source)
        assertEquals(chosen, payload.endedAt)
        assertNull(payload.totalEarnings)
        assertEquals(t0 + 3_600_000L, stop.event.occurredAt)
        assertEquals(session.sessionId, payload.sessionId)
        assertEquals(1, step.effects.filterIsInstance<AppEffect.EndSession>().size)
        assertTrue(step.effects.filterIsInstance<AppEffect.UpdateBubble>().any { it.text == "Shift ended by hand." })

        val next = machine.step(step.newState, screen(t0 + 3_700_000L, Flow.OfferPresented))
        val fresh = next.newState.region().session
        assertNotNull(fresh)
        assertNotEquals(session.sessionId, fresh!!.sessionId)
    }

    @Test
    fun `a wrong session, no session and a repeated command are no-ops`() {
        val wrong = machine.step(state(live()), end(t0 + 10_000L, t0 + 5_000L, sessionId = "session-B"))
        assertEquals(live(), wrong.newState.region())
        assertTrue(stops(wrong.effects).isEmpty())

        val missing = machine.step(state(live()), end(t0 + 10_000L, t0 + 5_000L, sessionId = null))
        assertEquals(live(), missing.newState.region())

        val idle = PlatformRegion(platform)
        val none = machine.step(state(idle), end(t0 + 10_000L, t0 + 5_000L))
        assertEquals(idle, none.newState.region())
        assertTrue(stops(none.effects).isEmpty())

        val first = machine.step(state(live()), end(t0 + 10_000L, t0 + 5_000L))
        val again = machine.step(first.newState, end(t0 + 11_000L, t0 + 5_000L))
        assertEquals(first.newState.region(), again.newState.region())
        assertTrue(stops(again.effects).isEmpty())
    }

    @Test
    fun `the end time is clamped to the shift start and the command time`() {
        val early = machine.step(state(live()), end(t0 + 10_000L, t0 - 50_000L))
        assertEquals(t0, early.newState.region().lastSessionEndedAt)
        assertEquals(t0, (stops(early.effects).single().event.payload as SessionStopPayload).endedAt)

        val late = machine.step(state(live()), end(t0 + 10_000L, t0 + 99_000L))
        assertEquals(t0 + 10_000L, late.newState.region().lastSessionEndedAt)

        val unset = machine.step(state(live()), end(t0 + 10_000L, null))
        assertEquals(t0 + 10_000L, unset.newState.region().lastSessionEndedAt)
    }

    @Test
    fun `a pending summary end does not override the manual time or source`() {
        val summary = machine.step(
            state(live()),
            screen(t0 + 20_000L, Flow.SessionEnded, ParsedFields.SessionEndedFields(totalEarnings = 21.0), modeHint = null),
        )
        assertEquals(DestructiveKind.SESSION_END, summary.newState.region().pendingDestructive?.kind)
        val chosen = t0 + 15_000L
        val step = machine.step(summary.newState, end(t0 + 60_000L, chosen))
        val all = stops(summary.effects + step.effects)
        val payload = all.single().event.payload as SessionStopPayload
        assertEquals(SessionEndSource.MANUAL, payload.source)
        assertEquals(chosen, payload.endedAt)
        assertNull(payload.totalEarnings)
        assertNull(step.newState.region().pendingDestructive)
    }

    @Test
    fun `an active pickup the command force-stamps is not confirmed, an earlier completed one is`() {
        val job = Job(jobId = "job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = t0 + 1_000L)
        val earlier = Task(
            taskId = "pickup-1", jobId = "job-1", phase = TaskPhase.PICKUP, subPhase = TaskSubFlow.ARRIVED,
            storeName = "Store A", startedAt = t0 + 2_000L, arrivedAt = t0 + 3_000L, completedAt = t0 + 4_000L,
        )
        val active = Task(
            taskId = "pickup-2", jobId = "job-1", phase = TaskPhase.PICKUP, subPhase = TaskSubFlow.ARRIVED,
            storeName = "Store B", startedAt = t0 + 5_000L, arrivedAt = t0 + 6_000L,
            activity = PickupActivity.SHOPPING, itemsShopped = 5,
        )
        val region = live().copy(activeJob = job, activeTask = active, recentTasks = listOf(earlier))
        val step = machine.step(state(region), end(t0 + 60_000L, t0 + 30_000L))

        val confirmed = step.effects.filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.PICKUP_CONFIRMED }
            .map { it.effectKey }
        assertEquals(listOf("log:PICKUP_CONFIRMED:pickup-1"), confirmed)
        assertTrue(step.effects.filterIsInstance<AppEffect.RecordShopRate>().isEmpty())
        assertNull(step.newState.region().activeJob)
    }
}
