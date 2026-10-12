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
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.SessionType
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
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

    private fun ending(previousEnd: Long? = null): AppState {
        val online = machine.step(ended(previousEnd), screen(-10_000L)).newState
        val summary = machine.step(online, screen(0L, Flow.SessionEnded, hint = null)).newState
        val pending = requireNotNull(summary.region().pendingDestructive)
        assertEquals(DestructiveKind.SESSION_END, pending.kind)
        assertTrue(pending.authoritative)
        assertEquals(t0 + 2_500L, pending.deadline)
        assertEquals(Mode.Offline, summary.region().mode)
        return summary
    }

    @Test
    fun `hint inside authoritative end window survives end commit then Offline cancels`() {
        for (byWake in listOf(true, false)) {
            val summary = ending()
            val arm = machine.step(summary, screen(1_000L))
            val pending = requireNotNull(arm.newState.region().pendingModeResume)
            assertNotNull(arm.newState.region().session)
            assertEquals(summary.region().session, arm.newState.region().session)
            assertEquals(Mode.Offline, arm.newState.region().mode)
            assertNoStart(arm.effects)
            val end = requireNotNull(arm.newState.region().pendingDestructive)
            val observation = if (byWake) Observation.Timeout(
                timestamp = end.deadline,
                type = TimeoutType.GRACE_COMMIT,
                targetPlatform = platform,
                payload = ObservationPayload.GraceWake(end.wakeId),
            ) else screen(2_501L)
            val commit = machine.step(arm.newState, observation)
            assertNull(commit.newState.region().session)
            assertEquals(t0, commit.newState.region().lastSessionEndedAt)
            assertEquals(pending, commit.newState.region().pendingModeResume)
            assertEquals(Mode.Offline, commit.newState.region().mode)
            assertNoStart(commit.effects)
            val cancel = machine.step(commit.newState, screen(5_000L, Flow.Idle, Mode.Offline))
            assertNull(cancel.newState.region().pendingModeResume)
            assertNull(cancel.newState.region().session)
            assertNoStart(cancel.effects)
            val late = machine.step(cancel.newState, wake(pending.deadline, pending.wakeId))
            assertNull(late.newState.region().session)
            assertNoStart(late.effects)
        }
    }

    @Test
    fun `sustained hints inside authoritative end window mint exactly one new session`() {
        val first = machine.step(ending(), screen(1_000L))
        val second = machine.step(first.newState, screen(4_000L))
        assertNull(second.newState.region().session)
        assertEquals(first.newState.region().pendingModeResume, second.newState.region().pendingModeResume)
        assertNoStart(first.effects + second.effects)
        val third = machine.step(second.newState, screen(10_000L))
        val session = requireNotNull(third.newState.region().session)
        assertEquals(t0 + 1_000L, session.startedAt)
        assertEquals(1, starts(first.effects + second.effects + third.effects).size)
        assertEquals(1, third.effects.filterIsInstance<AppEffect.StartSession>().size)
        val repeat = machine.step(third.newState, screen(11_000L))
        assertEquals(session, repeat.newState.region().session)
        assertNoStart(repeat.effects)
    }

    @Test
    fun `authoritative end after an ungraced Online hint restores Offline so later hints can start`() {
        // The requested anchor prefers the previous completed end, even while another end stands.
        val summary = ending(previousEnd = t0 - 120_000L)
        val flash = machine.step(summary, screen(1_000L))
        assertEquals(Mode.Online, flash.newState.region().mode)
        assertNotNull(flash.newState.region().session)
        assertNull(flash.newState.region().pendingModeResume)
        val end = requireNotNull(flash.newState.region().pendingDestructive)
        val commit = machine.step(flash.newState, Observation.Timeout(
            timestamp = end.deadline,
            type = TimeoutType.GRACE_COMMIT,
            targetPlatform = platform,
            payload = ObservationPayload.GraceWake(end.wakeId),
        ))
        assertNull(commit.newState.region().session)
        assertEquals(Mode.Offline, commit.newState.region().mode)
        val arm = machine.step(commit.newState, screen(4_000L))
        val pending = requireNotNull(arm.newState.region().pendingModeResume)
        val resumed = machine.step(arm.newState, wake(pending.deadline, pending.wakeId))
        assertEquals(t0 + 4_000L, requireNotNull(resumed.newState.region().session).startedAt)
        assertEquals(1, starts(resumed.effects).size)
    }

    @Test
    fun `graced idle frames retain latest fields and replay them through the session settle path`() {
        val first = machine.step(ended(), screen(6_000L, Flow.Idle).copy(parsed = ParsedFields.IdleFields(
            zoneName = "A", sessionType = SessionType.PerOffer, sessionPay = 10.0,
        )))
        val fields = ParsedFields.IdleFields(zoneName = "B", sessionType = SessionType.ByTime, sessionPay = 20.0)
        val second = machine.step(first.newState, screen(7_000L, Flow.Idle).copy(parsed = fields))
        val third = machine.step(second.newState, screen(8_000L))
        val pending = requireNotNull(third.newState.region().pendingModeResume)
        assertEquals(fields, pending.idleFields)
        assertEquals(first.newState.region().pendingModeResume!!.wakeId, pending.wakeId)
        assertEquals(third.newState, StateJson.decodeFromString<AppState>(StateJson.encodeToString(third.newState)))
        val commit = machine.step(third.newState, wake(pending.deadline, pending.wakeId))
        val region = commit.newState.region()
        assertEquals("B", region.zoneName)
        assertEquals(SessionType.ByTime, region.sessionType)
        assertNotNull(region.session)
        assertEquals(20.0, requireNotNull(region.pendingSessionPay).value, 0.0)
        assertEquals(Flow.Idle, region.pendingSessionPay!!.flow)
        assertEquals(pending.deadline, region.pendingSessionPay!!.since)
        val online = machine.step(AppState(), screen(0L)).newState
        val paused = machine.step(online, screen(1_000L, Flow.Idle, Mode.Paused)).newState
        val resume = machine.step(paused, screen(2_000L, Flow.Idle).copy(parsed = fields))
        assertNull(requireNotNull(resume.newState.region().pendingModeResume).idleFields)
    }

    @Test
    fun `wake at fourteen seconds stamps session identity and DASH_START at the six second hint`() {
        val arm = machine.step(ended(at = 0L), screen(6_000L).copy(timestamp = 6_000L))
        val pending = requireNotNull(arm.newState.region().pendingModeResume)
        val commit = machine.step(arm.newState, wake(14_000L, pending.wakeId))
        val session = requireNotNull(commit.newState.region().session)
        assertEquals("session-${platform.wire}-6000-0", session.sessionId)
        assertEquals(6_000L, session.startedAt)
        assertEquals(6_000L, starts(commit.effects).single().event.occurredAt)
        assertEquals(6_000L, (starts(commit.effects).single().event.payload as SessionStartPayload).startedAt)
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
    fun `Offline frame at or just past the deadline drops pending without minting a session`() {
        for (lateness in listOf(0L, 1L)) {
            val arm = machine.step(ended(), screen(6_000L))
            val pending = requireNotNull(arm.newState.region().pendingModeResume)
            val cancel = machine.step(arm.newState, screen(pending.deadline - t0 + lateness, Flow.Idle, Mode.Offline))
            val region = cancel.newState.region()
            assertEquals(Mode.Offline, region.mode)
            assertNull(region.pendingModeResume)
            assertNull(region.session)
            assertEquals(arm.newState.region().mintCounter, region.mintCounter)
            assertNoStart(cancel.effects)
            val lateWake = machine.step(cancel.newState, wake(pending.deadline + 2, pending.wakeId))
            assertNull(lateWake.newState.region().session)
            assertNoStart(lateWake.effects)
        }
    }

    @Test
    fun `Online or neutral frame just past the deadline commits at the first hint instant`() {
        for (hint in listOf(Mode.Online, null)) {
            val arm = machine.step(ended(), screen(6_000L))
            val pending = requireNotNull(arm.newState.region().pendingModeResume)
            val commit = machine.step(arm.newState, screen(pending.deadline - t0 + 1, hint = hint))
            val region = commit.newState.region()
            assertEquals(Mode.Online, region.mode)
            assertNull(region.pendingModeResume)
            assertEquals(pending.since, requireNotNull(region.session).startedAt)
            assertEquals(pending.since, (starts(commit.effects).single().event.payload as SessionStartPayload).startedAt)
            assertEquals(1, commit.effects.filterIsInstance<AppEffect.StartSession>().size)
        }
    }

    @Test
    fun `Paused-origin pending contradicted past deadline takes only the normal mode path`() {
        val online = machine.step(AppState(), screen(0L)).newState
        val paused = machine.step(online, screen(1_000L, Flow.Idle, Mode.Paused)).newState
        // A frozen earnings read exposes an otherwise invisible Paused -> Online -> Offline flap:
        // that intermediate Online transition would rebase it and mint another wake generation.
        val frozen = PendingSessionPay(value = 12.0, since = t0, deadline = t0 + 2_000L, flow = Flow.Idle)
        val withRead = paused.copy(regions = paused.regions.copy(platforms = mapOf(
            platform to paused.region().copy(pendingSessionPay = frozen),
        )))
        val arm = machine.step(withRead, screen(6_000L))
        val armed = arm.newState.region()
        val pending = requireNotNull(armed.pendingModeResume)
        assertEquals(Mode.Paused, armed.mode)
        for (hint in listOf(Mode.Offline, Mode.Paused)) {
            val frame = screen(pending.deadline - t0 + 1, Flow.Idle, hint)
            val cancel = machine.step(arm.newState, frame)
            val region = cancel.newState.region()
            assertEquals(hint, region.mode)
            assertNull(region.pendingModeResume)
            assertEquals(armed.session, region.session)
            assertEquals(frozen, region.pendingSessionPay)
            assertNoStart(cancel.effects)

            // Compare to the ordinary Paused -> Offline (or Paused confirmation) path without
            // a resume pending. Its complete effects occur once, plus the resume timer cancel.
            val withoutPending = arm.newState.copy(regions = arm.newState.regions.copy(platforms = mapOf(
                platform to armed.copy(pendingModeResume = null),
            )))
            val normal = machine.step(withoutPending, frame)
            assertEquals(normal.newState, cancel.newState)
            val resumeCancel = AppEffect.CancelTimeout(TimeoutType.MODE_RESUME_COMMIT, platform)
            assertEquals(1, cancel.effects.count { it == resumeCancel })
            assertEquals(normal.effects, cancel.effects.filterNot { it == resumeCancel })
            if (hint == Mode.Offline) {
                assertEquals(frame.timestamp, requireNotNull(region.pendingDestructive).since)
                assertEquals(1, cancel.effects.count {
                    it == AppEffect.CancelTimeout(TimeoutType.SESSION_PAUSED_SAFETY, platform)
                })
            }
        }
    }

    @Test
    fun `pending armed near window end keeps gracing and an Offline frame cancels it`() {
        val first = machine.step(ended(), screen(59_000L))
        val second = machine.step(first.newState, screen(61_000L))
        val pending = requireNotNull(second.newState.region().pendingModeResume)
        assertEquals(t0 + 59_000L, pending.since)
        assertEquals(first.newState.region().pendingModeResume, pending)
        assertEquals(Mode.Offline, second.newState.region().mode)
        assertNull(second.newState.region().session)
        assertNoStart(first.effects + second.effects)
        val cancel = machine.step(second.newState, screen(62_000L, Flow.Idle, Mode.Offline))
        assertEquals(Mode.Offline, cancel.newState.region().mode)
        assertNull(cancel.newState.region().pendingModeResume)
        assertNull(cancel.newState.region().session)
        assertNoStart(cancel.effects)
    }

    @Test
    fun `pending armed near window end commits sustained hints at its original since`() {
        val first = machine.step(ended(), screen(59_000L))
        val second = machine.step(first.newState, screen(61_000L))
        assertEquals(first.newState.region().pendingModeResume, second.newState.region().pendingModeResume)
        assertNoStart(first.effects + second.effects)
        val commit = machine.step(second.newState, screen(68_000L))
        assertEquals(Mode.Online, commit.newState.region().mode)
        assertNull(commit.newState.region().pendingModeResume)
        assertEquals(t0 + 59_000L, requireNotNull(commit.newState.region().session).startedAt)
        assertEquals(t0 + 59_000L, (starts(commit.effects).single().event.payload as SessionStartPayload).startedAt)
        assertEquals(1, commit.effects.filterIsInstance<AppEffect.StartSession>().size)
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
