package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1311: lifecycle effects follow session identity, including a same-step end and start. */
class ModeEffectsSessionIdentityTest {
    private val platform = Platform.DoorDash
    private val t0 = 100_000L
    private val sessionA = Session("dash-A", startedAt = 10_000L)
    private val effectMap = EffectMap()
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(),
        TransitionPolicy(), effectMap,
    )

    private fun screen(offset: Long, flow: Flow? = null) = Observation.Screen(
        timestamp = t0 + offset,
        captureId = null,
        ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = if (flow == Flow.SessionEnded) null else Mode.Online,
        parsed = if (flow == Flow.SessionEnded) ParsedFields.SessionEndedFields(totalEarnings = 21.0)
            else ParsedFields.None,
    )

    private fun AppState.region() = regions.platforms.getValue(platform)

    private fun summary() = machine.step(
        AppState(regions = Regions(platforms = mapOf(platform to PlatformRegion(
            platform, mode = Mode.Online, session = sessionA,
        )))),
        screen(0L, Flow.SessionEnded),
    )

    private fun lifecycle(effects: List<AppEffect>) = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.DASH_STOP || it.event.type == AppEventType.DASH_START }

    private fun assertReplacement(effects: List<AppEffect>, sessionB: Session) {
        assertNotEquals(sessionA.sessionId, sessionB.sessionId)
        val logs = lifecycle(effects)
        assertEquals(listOf(AppEventType.DASH_STOP, AppEventType.DASH_START), logs.map { it.event.type })
        val stop = logs[0].event
        assertEquals(sessionA.sessionId, stop.sessionId)
        assertEquals(sessionA.sessionId, (stop.payload as SessionStopPayload).sessionId)
        assertEquals(t0, (stop.payload as SessionStopPayload).endedAt)
        val start = logs[1].event
        assertEquals(sessionB.sessionId, start.sessionId)
        assertEquals(sessionB.sessionId, (start.payload as SessionStartPayload).sessionId)
        assertEquals(sessionB.startedAt, (start.payload as SessionStartPayload).startedAt)
        assertEquals(sessionB.startedAt, start.occurredAt)
        val endEffect = effects.filterIsInstance<AppEffect.EndSession>().single()
        val startEffect = effects.filterIsInstance<AppEffect.StartSession>().single()
        assertEquals(sessionA.sessionId, endEffect.sessionId)
        assertEquals(sessionB.sessionId, startEffect.sessionId)
        assertTrue(effects.indexOf(endEffect) < effects.indexOf(logs[1]))
        assertTrue(effects.indexOf(logs[0]) < effects.indexOf(startEffect))
        val endBubble = effects.filterIsInstance<AppEffect.UpdateBubble>()
            .single { it.text.startsWith("Shift ended") }
        assertEquals(sessionA.sessionId, endBubble.sessionId)
        assertTrue(effects.indexOf(endBubble) < effects.indexOf(logs[1]))
    }

    @Test
    fun `late frame commits both end and hinted start with STOP A before START B`() {
        val summary = summary()
        val hint = machine.step(summary.newState, screen(1_000L))
        assertEquals(Mode.Offline, hint.newState.region().mode)
        assertNotNull(hint.newState.region().pendingDestructive)
        assertNotNull(hint.newState.region().pendingModeResume)
        assertTrue(lifecycle(summary.effects + hint.effects).isEmpty())
        val commit = machine.step(hint.newState, screen(10_000L))
        val sessionB = requireNotNull(commit.newState.region().session)
        assertEquals(t0 + 1_000L, sessionB.startedAt)
        assertNull(commit.newState.region().pendingDestructive)
        assertNull(commit.newState.region().pendingModeResume)
        assertReplacement(summary.effects + hint.effects + commit.effects, sessionB)
        assertTrue(lifecycle(machine.step(commit.newState, screen(11_000L)).effects).isEmpty())
    }

    @Test
    fun `offer before end commit and task after deadline replace session across Online to Online`() {
        val summary = summary()
        val offer = machine.step(summary.newState, screen(1_000L, Flow.OfferPresented))
        assertEquals(Mode.Online, offer.newState.region().mode)
        assertEquals(sessionA.sessionId, offer.newState.region().session?.sessionId)
        assertNotNull(offer.newState.region().pendingDestructive)
        assertNull(offer.newState.region().pendingModeResume)
        assertTrue(lifecycle(summary.effects + offer.effects).isEmpty())
        val commit = machine.step(offer.newState, screen(2_501L, Flow.TaskPickupNavigation))
        assertEquals(Mode.Online, commit.newState.region().mode)
        val sessionB = requireNotNull(commit.newState.region().session)
        assertEquals(t0 + 2_501L, sessionB.startedAt)
        assertReplacement(summary.effects + offer.effects + commit.effects, sessionB)
        assertTrue(lifecycle(machine.step(commit.newState, screen(3_000L, Flow.TaskPickupNavigation)).effects).isEmpty())
    }

    @Test
    fun `mode flips with the same session never emit start or stop`() {
        for ((from, to) in listOf(
            Mode.Paused to Mode.Online, Mode.Offline to Mode.Online,
            Mode.Online to Mode.Paused, Mode.Online to Mode.Offline,
        )) {
            val prev = PlatformRegion(platform, mode = from, session = sessionA)
            val effects = effectMap.diffMode(prev, prev.copy(mode = to), screen(1_000L))
            assertTrue(lifecycle(effects).isEmpty())
            assertFalse(effects.any { it is AppEffect.StartSession || it is AppEffect.EndSession })
        }
    }

    @Test
    fun `session end without successor emits exactly one stop even with unchanged mode`() {
        val summary = summary()
        val commit = machine.step(summary.newState, screen(2_501L, Flow.SessionEnded))
        assertEquals(Mode.Offline, summary.newState.region().mode)
        assertEquals(Mode.Offline, commit.newState.region().mode)
        assertNull(commit.newState.region().session)
        val stop = lifecycle(summary.effects + commit.effects).single().event
        assertEquals(AppEventType.DASH_STOP, stop.type)
        assertEquals(sessionA.sessionId, stop.sessionId)
        assertEquals(sessionA.sessionId, (stop.payload as SessionStopPayload).sessionId)
        assertEquals(sessionA.sessionId, commit.effects.filterIsInstance<AppEffect.EndSession>().single().sessionId)
        assertFalse(commit.effects.any { it is AppEffect.StartSession })
        assertTrue(lifecycle(machine.step(commit.newState, screen(3_000L, Flow.SessionEnded)).effects).isEmpty())
    }
}
