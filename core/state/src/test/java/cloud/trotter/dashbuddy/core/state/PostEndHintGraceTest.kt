package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.settings.GraceConfigProvider
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1311: chrome re-shown after a dash end must not mint a ghost session. */
class PostEndHintGraceTest {
    private val platform = Platform.DoorDash
    private val t0 = 100_000L
    private val machine = machine()

    private fun machine(policy: TransitionPolicy = TransitionPolicy()) = StateMachine(
        flowStepper = FlowRegionStepper(),
        platformStepper = PlatformRegionStepper(),
        crossPlatformStepper = CrossPlatformRegionStepper(),
        transitionPolicy = policy,
        effectMap = EffectMap(),
    )

    private fun ended(at: Long? = t0) = AppState(
        timestamp = t0,
        regions = Regions(platforms = mapOf(platform to PlatformRegion(
            platform = platform,
            lastSessionEndedAt = at,
        ))),
    )

    private fun AppState.region() = regions.platforms.getValue(platform)

    private fun screen(offset: Long, flow: Flow? = null, hint: Mode? = Mode.Online) = Observation.Screen(
        timestamp = t0 + offset,
        captureId = null,
        ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = hint,
        parsed = ParsedFields.None,
    )

    private fun wake(at: Long, id: Long) = Observation.Timeout(
        timestamp = at,
        type = TimeoutType.MODE_RESUME_COMMIT,
        targetPlatform = platform,
        payload = ObservationPayload.GraceWake(id),
    )

    private fun starts(effects: List<AppEffect>) = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.DASH_START }

    private fun assertNoStart(effects: List<AppEffect>) {
        assertTrue(starts(effects).isEmpty())
        assertFalse(effects.any { it is AppEffect.StartSession || it is AppEffect.UpdateBubble })
    }

    @Test
    fun `hint-only frames stay Offline and an Offline frame cancels without a session`() {
        // All three flows are ambiguous without their hint, including the flow-less timeline.
        for (flow in listOf(null, Flow.Idle, Flow.TaskUnassigned)) {
            val arm = machine.step(ended(), screen(6_000L, flow))
            val region = arm.newState.region()
            assertEquals(Mode.Offline, region.mode)
            assertNull(region.session)
            val pending = region.pendingModeResume!!
            assertEquals(t0 + 6_000L, pending.since)
            assertEquals(pending.since + GraceConfig.PAUSE_RESUME_GRACE_MS, pending.deadline)
            assertTrue(pending.wakeId > 0)
            assertNoStart(arm.effects)
            val timer = arm.effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .single { it.type == TimeoutType.MODE_RESUME_COMMIT }
            assertEquals(ObservationPayload.GraceWake(pending.wakeId), timer.payload)

            val cancel = machine.step(arm.newState, screen(11_000L, Flow.Idle, Mode.Offline))
            assertEquals(Mode.Offline, cancel.newState.region().mode)
            assertNull(cancel.newState.region().pendingModeResume)
            assertNull(cancel.newState.region().session)
            assertNoStart(cancel.effects)
            val lateWake = machine.step(cancel.newState, wake(pending.deadline + 1, pending.wakeId))
            assertNull(lateWake.newState.region().session)
            assertNoStart(lateWake.effects)
        }
    }

    @Test
    fun `sustained hints lazy-commit exactly one session at the first hint instant`() {
        val first = machine.step(ended(), screen(6_000L))
        val second = machine.step(first.newState, screen(10_000L))
        assertEquals(first.newState.region().pendingModeResume, second.newState.region().pendingModeResume)
        assertNoStart(second.effects)
        val third = machine.step(second.newState, screen(25_000L))
        val session = third.newState.region().session!!
        assertEquals(Mode.Online, third.newState.region().mode)
        assertEquals(t0 + 6_000L, session.startedAt)
        assertNull(third.newState.region().pendingModeResume)
        val start = starts(first.effects + second.effects + third.effects).single()
        assertEquals(session.startedAt, (start.event.payload as SessionStartPayload).startedAt)
        val repeat = machine.step(third.newState, screen(30_000L))
        assertEquals(session.sessionId, repeat.newState.region().session!!.sessionId)
        assertTrue(starts(repeat.effects).isEmpty())
    }

    @Test
    fun `Online-bearing flows start immediately and consume any pending hint`() {
        for (flow in listOf(Flow.OfferPresented, Flow.TaskPickupNavigation, Flow.TaskActive, Flow.PostTask)) {
            val direct = machine.step(ended(), screen(6_000L, flow, hint = null))
            assertEquals(Mode.Online, direct.newState.region().mode)
            assertEquals(t0 + 6_000L, direct.newState.region().session!!.startedAt)
            assertNull(direct.newState.region().pendingModeResume)
            assertEquals(1, starts(direct.effects).size)

            val arm = machine.step(ended(), screen(6_000L))
            val authoritative = machine.step(arm.newState, screen(7_000L, flow, hint = null))
            assertEquals(Mode.Online, authoritative.newState.region().mode)
            assertEquals(t0 + 7_000L, authoritative.newState.region().session!!.startedAt)
            assertNull(authoritative.newState.region().pendingModeResume)
            assertEquals(1, starts(authoritative.effects).size)
        }
    }

    @Test
    fun `hints outside the window or without an ended session start immediately`() {
        for ((state, offset) in listOf(ended() to 90_000L, ended(null) to 6_000L)) {
            val result = machine.step(state, screen(offset))
            assertEquals(Mode.Online, result.newState.region().mode)
            assertEquals(t0 + offset, result.newState.region().session!!.startedAt)
            assertNull(result.newState.region().pendingModeResume)
            assertEquals(1, starts(result.effects).size)
        }
    }

    @Test
    fun `post-end window is inclusive and comes from per-platform config`() {
        val configured = machine(TransitionPolicy(GraceConfigProvider {
            mapOf(platform to GraceConfig(postEndHintGraceWindowMs = 6_000L, pauseResumeGraceMs = 2_000L))
        }))
        val edge = configured.step(ended(), screen(6_000L)).newState.region()
        assertNull(edge.session)
        assertEquals(t0 + 8_000L, edge.pendingModeResume!!.deadline)
        assertNotNull(configured.step(ended(), screen(6_001L)).newState.region().session)
    }

    @Test
    fun `at the deadline a stale wake is inert and the own wake commits at the first hint instant`() {
        val arm = machine.step(ended(), screen(6_000L))
        val pending = arm.newState.region().pendingModeResume!!
        val stale = machine.step(arm.newState, wake(pending.deadline, pending.wakeId + 1))
        assertEquals(Mode.Offline, stale.newState.region().mode)
        assertEquals(pending, stale.newState.region().pendingModeResume)
        assertNull(stale.newState.region().session)
        assertNoStart(stale.effects)
        val commit = machine.step(stale.newState, wake(pending.deadline, pending.wakeId))
        assertEquals(Mode.Online, commit.newState.region().mode)
        assertEquals(pending.since, commit.newState.region().session!!.startedAt)
        assertNull(commit.newState.region().pendingModeResume)
        assertEquals(pending.since, (starts(commit.effects).single().event.payload as SessionStartPayload).startedAt)
    }

    @Test
    fun `a stale wake strictly past the deadline commits by time at the first hint instant`() {
        val arm = machine.step(ended(), screen(6_000L))
        val pending = arm.newState.region().pendingModeResume!!
        // CLAUDE.md §3 rule (e): graceLapsed = timestamp > deadline || isWakeFor(type, wakeId).
        // Any observation strictly past the deadline lapses by TIME, including a stale wake.
        val commit = machine.step(arm.newState, wake(pending.deadline + 1, pending.wakeId + 1))
        assertEquals(Mode.Online, commit.newState.region().mode)
        assertEquals(pending.since, commit.newState.region().session!!.startedAt)
        assertNull(commit.newState.region().pendingModeResume)
        assertEquals(pending.since, (starts(commit.effects).single().event.payload as SessionStartPayload).startedAt)
    }

    @Test
    fun `recovery drops Offline-origin resume and rearms nothing`() {
        val armed = machine.step(ended(), screen(6_000L)).newState
        val pending = armed.region().pendingModeResume!!
        val clean = armed.recoveryHygiene(t0 + 7_000L)
        assertNull(clean.region().pendingModeResume)
        assertEquals(t0, clean.region().lastSessionEndedAt)
        assertTrue(clean.pendingDeadlineTimers().isEmpty())
        val lateWake = machine.step(clean, wake(pending.deadline + 1, pending.wakeId))
        assertNull(lateWake.newState.region().session)
        assertNoStart(lateWake.effects)
        assertNotNull(machine.step(clean, screen(10_000L)).newState.region().pendingModeResume)
    }

    @Test
    fun `ending a session stamps its logical end instant rather than the later commit`() {
        val online = machine.step(AppState(), screen(0L)).newState
        val summary = machine.step(online, screen(3_000L, Flow.SessionEnded, hint = null)).newState
        assertNotNull(summary.region().session)
        val committed = machine.step(summary, screen(6_000L, Flow.Idle, Mode.Offline)).newState
        assertNull(committed.region().session)
        assertEquals(t0 + 3_000L, committed.region().lastSessionEndedAt)
        val repeatedSummary = machine.step(committed, screen(7_000L, Flow.SessionEnded, hint = null)).newState
        assertEquals(committed.region().lastSessionEndedAt, repeatedSummary.region().lastSessionEndedAt)
    }

    @Test
    fun `end anchor survives snapshots and old snapshots default to null`() {
        val state = ended()
        assertEquals(state, StateJson.decodeFromString<AppState>(StateJson.encodeToString(state)))
        val legacy = StateJson.decodeFromString<PlatformRegion>("""{"platform":"DoorDash","futureField":1}""")
        assertNull(legacy.lastSessionEndedAt)
    }
}
