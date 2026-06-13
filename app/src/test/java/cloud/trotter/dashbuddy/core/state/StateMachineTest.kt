package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the full state machine step pipeline.
 * Uses real steppers (no mocking) to verify end-to-end behavior.
 */
class StateMachineTest {

    private lateinit var machine: StateMachine

    @Before
    fun setUp() {
        machine = StateMachine(
            flowStepper = FlowRegionStepper(),
            platformStepper = PlatformRegionStepper(),
            crossPlatformStepper = CrossPlatformRegionStepper(),
            transitionPolicy = TransitionPolicy(),
            effectMap = EffectMap(),
        )
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private var clock = 1_000_000L

    private fun tick(deltaMs: Long = 1000): Long {
        clock += deltaMs
        return clock
    }

    private fun screenObs(
        flow: Flow? = null,
        modeHint: Mode? = null,
        parsed: ParsedFields = ParsedFields.None,
        ruleId: String = "doordash.screen.test",
        timestamp: Long = tick(),
        expectedOutcomes: Set<Flow>? = null,
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = parsed,
        expectedOutcomes = expectedOutcomes,
    )

    private fun clickObs(
        flow: Flow? = null,
        modeHint: Mode? = null,
        intent: String = "unknown",
        ruleId: String = "doordash.click.test",
        timestamp: Long = tick(),
    ) = Observation.Click(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = ParsedFields.ClickFields(intent = intent),
    )

    private fun timeoutObs(
        type: TimeoutType = TimeoutType.SESSION_PAUSED_SAFETY,
        timestamp: Long = tick(),
        targetPlatform: Platform? = null,
    ) = Observation.Timeout(timestamp = timestamp, type = type, targetPlatform = targetPlatform)

    private fun offerFields(storeName: String = "Chipotle", hash: String = "hash-123") =
        ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = hash,
                payAmount = 7.50,
                distanceMiles = 3.2,
                orders = listOf(
                    ParsedOrder(
                        orderIndex = 0,
                        orderType = OrderType.PICKUP,
                        storeName = storeName,
                        itemCount = 1,
                        isItemCountEstimated = false,
                        badges = emptySet(),
                    )
                ),
            ),
        )

    private fun taskFields(
        storeName: String = "Chipotle",
        phase: TaskPhase = TaskPhase.PICKUP,
        subFlow: TaskSubFlow = TaskSubFlow.NAVIGATION,
    ) = ParsedFields.TaskFields(
        storeName = storeName,
        phase = phase,
        subFlow = subFlow,
    )

    // =========================================================================
    // CORRELATION VERSION
    // =========================================================================

    @Test
    fun `correlationVersion increments on each step`() {
        val state0 = AppState()
        assertEquals(0L, state0.correlationVersion)

        val t1 = machine.step(state0, screenObs(flow = Flow.Idle))
        assertEquals(1L, t1.newState.correlationVersion)

        val t2 = machine.step(t1.newState, screenObs(flow = Flow.Idle))
        assertEquals(2L, t2.newState.correlationVersion)
    }

    // =========================================================================
    // FLOW TRANSITIONS
    // =========================================================================

    @Test
    fun `flow updates on screen observation`() {
        val state = AppState()
        val t = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields()))
        assertEquals(Flow.OfferPresented, t.newState.regions.flow.flow)
    }

    @Test
    fun `flow transitions through full delivery lifecycle`() {
        var state = AppState()

        // 1. Idle
        state = machine.step(state, screenObs(flow = Flow.Idle)).newState
        assertEquals(Flow.Idle, state.regions.flow.flow)

        // 2. Offer presented
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields())).newState
        assertEquals(Flow.OfferPresented, state.regions.flow.flow)
        assertNotNull(state.regions.flow.pendingOffer)

        // 3. Pickup navigation (offer accepted)
        state = machine.step(state, screenObs(flow = Flow.TaskPickupNavigation, parsed = taskFields())).newState
        assertEquals(Flow.TaskPickupNavigation, state.regions.flow.flow)
        assertNull(state.regions.flow.pendingOffer) // offer popped

        // 4. Pickup arrived
        state = machine.step(state, screenObs(flow = Flow.TaskPickupArrived, parsed = taskFields())).newState
        assertEquals(Flow.TaskPickupArrived, state.regions.flow.flow)

        // 5. Dropoff navigation
        state = machine.step(state, screenObs(
            flow = Flow.TaskDropoffNavigation,
            parsed = taskFields(phase = TaskPhase.DROPOFF),
        )).newState
        assertEquals(Flow.TaskDropoffNavigation, state.regions.flow.flow)

        // 6. Dropoff arrived
        state = machine.step(state, screenObs(
            flow = Flow.TaskDropoffArrived,
            parsed = taskFields(phase = TaskPhase.DROPOFF),
        )).newState
        assertEquals(Flow.TaskDropoffArrived, state.regions.flow.flow)

        // 7. Post task
        state = machine.step(state, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50),
        )).newState
        assertEquals(Flow.PostTask, state.regions.flow.flow)

        // 8. Back to idle
        state = machine.step(state, screenObs(flow = Flow.Idle)).newState
        assertEquals(Flow.Idle, state.regions.flow.flow)
    }

    // =========================================================================
    // OFFER LIFECYCLE
    // =========================================================================

    @Test
    fun `offer push — pendingOffer created on OfferPresented`() {
        var state = AppState()
        state = machine.step(state, screenObs(flow = Flow.Idle)).newState

        val offer = offerFields("Chipotle", "hash-A")
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offer)).newState

        val pending = state.regions.flow.pendingOffer
        assertNotNull(pending)
        assertEquals("hash-A", pending!!.offerHash)
        assertEquals(Flow.Idle, pending.returnFlow)
    }

    @Test
    fun `offer replace — new hash replaces existing offer`() {
        var state = AppState()
        state = machine.step(state, screenObs(flow = Flow.Idle)).newState
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields("Chipotle", "hash-A"))).newState

        // Replace with new offer
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields("Taco Bell", "hash-B"))).newState

        val pending = state.regions.flow.pendingOffer
        assertNotNull(pending)
        assertEquals("hash-B", pending!!.offerHash)
        // Return flow should be inherited from original offer
        assertEquals(Flow.Idle, pending.returnFlow)
    }

    @Test
    fun `offer update — same hash updates fields`() {
        var state = AppState()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields("Chipotle", "hash-A"))).newState

        val presentedAt = state.regions.flow.pendingOffer!!.presentedAt

        // Same hash, different fields
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields("Chipotle Mexican Grill", "hash-A"))).newState

        val pending = state.regions.flow.pendingOffer!!
        assertEquals("hash-A", pending.offerHash)
        // presentedAt should remain the original time
        assertEquals(presentedAt, pending.presentedAt)
    }

    @Test
    fun `offer pop on accept — cleared when transitioning to pickup`() {
        var state = AppState()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields())).newState
        assertNotNull(state.regions.flow.pendingOffer)

        state = machine.step(state, screenObs(flow = Flow.TaskPickupNavigation, parsed = taskFields())).newState
        assertNull(state.regions.flow.pendingOffer)
    }

    @Test
    fun `offer pop on decline — cleared when transitioning to idle`() {
        var state = AppState()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields())).newState
        assertNotNull(state.regions.flow.pendingOffer)

        state = machine.step(state, screenObs(flow = Flow.Idle)).newState
        assertNull(state.regions.flow.pendingOffer)
    }

    @Test
    fun `click intent recorded on pendingOffer`() {
        var state = AppState()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields())).newState
        assertNull(state.regions.flow.pendingOffer!!.lastClickIntent)

        state = machine.step(state, clickObs(intent = "accept_offer")).newState
        assertEquals("accept_offer", state.regions.flow.pendingOffer!!.lastClickIntent)
    }

    @Test
    fun `non-click-fields observation does not overwrite existing intent`() {
        var state = AppState()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields())).newState
        state = machine.step(state, clickObs(intent = "accept_offer")).newState
        assertEquals("accept_offer", state.regions.flow.pendingOffer!!.lastClickIntent)

        // Click with non-ClickFields parsed data should not clear intent
        // (fields?.intent is null when parsed is not ClickFields)
        val nonClickObs = Observation.Click(
            timestamp = tick(),
            captureId = "cap-nc",
            ruleId = "doordash.click.test",
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.None,
        )
        state = machine.step(state, nonClickObs).newState
        assertEquals("accept_offer", state.regions.flow.pendingOffer!!.lastClickIntent)
    }

    // =========================================================================
    // SCREEN-AUTHORITATIVE MODE TRANSITIONS
    // =========================================================================

    @Test
    fun `screen authoritative — single screen transitions Offline to Online immediately`() {
        var state = AppState()

        // Single screen implying Online — should apply immediately (screen-authoritative)
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Online, dd.mode)
    }

    @Test
    fun `screen authoritative — single screen transitions Online to Offline immediately`() {
        var state = AppState()

        // Get Online (one screen)
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.DoorDash]!!.mode)

        // Single offline screen — mode changes immediately (screen-authoritative)
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline,
            flow = Flow.Idle,
            parsed = ParsedFields.IdleFields(),
        )).newState

        assertEquals(Mode.Offline, state.regions.platforms[Platform.DoorDash]!!.mode)
    }

    @Test
    fun `screen authoritative — Online to Paused applies immediately`() {
        var state = AppState()

        // Get Online
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.DoorDash]!!.mode)

        // Pause screen → mode=Paused immediately
        state = machine.step(state, screenObs(
            modeHint = Mode.Paused,
            parsed = ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000),
        )).newState
        assertEquals(Mode.Paused, state.regions.platforms[Platform.DoorDash]!!.mode)
    }

    @Test
    fun `click does NOT change mode — click is intent not authoritative`() {
        var state = AppState()

        // Get Online
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
            ruleId = "uber.screen.offer",
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.Uber]!!.mode)

        // go_offline click — mode should stay Online
        state = machine.step(state, clickObs(
            modeHint = Mode.Offline,
            intent = "go_offline",
            ruleId = "uber.click.go_offline",
        )).newState

        val uber = state.regions.platforms[Platform.Uber]!!
        assertEquals(Mode.Online, uber.mode) // NOT Offline — click is just intent
    }

    @Test
    fun `click followed by confirming screen transitions mode`() {
        var state = AppState()

        // Get Online
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
            ruleId = "uber.screen.offer",
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.Uber]!!.mode)

        // go_offline click — mode stays Online (click is intent only)
        state = machine.step(state, clickObs(
            modeHint = Mode.Offline,
            intent = "go_offline",
            ruleId = "uber.click.go_offline",
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.Uber]!!.mode)

        // Stale awaiting_offer screen (still visible 0.3s after click) — Online confirmed
        state = machine.step(state, screenObs(
            flow = Flow.Idle,
            modeHint = Mode.Online,
            ruleId = "uber.screen.awaiting_offer",
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.Uber]!!.mode)

        // idle_map screen (authoritative offline signal)
        state = machine.step(state, screenObs(
            flow = Flow.Idle,
            modeHint = Mode.Offline,
            ruleId = "uber.screen.idle_map",
        )).newState

        // Screen-authoritative: one offline screen = Offline immediately
        assertEquals(Mode.Offline, state.regions.platforms[Platform.Uber]!!.mode)
    }

    // =========================================================================
    // SESSION GRACE PERIOD
    // =========================================================================

    @Test
    fun `session created when transitioning to Online`() {
        var state = AppState()

        // Single screen → Online (screen-authoritative)
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState

        val session = state.regions.platforms[Platform.DoorDash]!!.session
        assertNotNull(session)
        assertTrue(session!!.sessionId.isNotEmpty())
    }

    @Test
    fun `SessionEnded arms a short authoritative grace - not an immediate end (#431)`() {
        var state = AppState()

        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState
        val sessionId = state.regions.platforms[Platform.DoorDash]!!.session!!.sessionId
        assertNotNull(sessionId)

        val summaryAt = tick()
        val transition = machine.step(state, screenObs(
            flow = Flow.SessionEnded,
            parsed = ParsedFields.SessionEndedFields(totalEarnings = 25.0),
            timestamp = summaryAt,
        ))
        state = transition.newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Offline, dd.mode)
        // One frame must never split a live session: it stays alive through
        // the SHORT authoritative grace, with the summary's data stashed.
        assertNotNull(dd.session)
        val pend = dd.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, pend.kind)
        assertTrue(pend.authoritative)
        assertEquals(summaryAt, pend.since)
        assertEquals(summaryAt + TransitionPolicy.AUTHORITATIVE_GRACE_MS, pend.deadline)
        assertEquals(25.0, pend.endFields!!.totalEarnings, 0.001)
        // And the machine armed a wake-up so the commit doesn't wait for the
        // next observation.
        val timer = transition.effects.filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.GRACE_COMMIT }
        assertEquals(Platform.DoorDash, timer.platform)
    }

    @Test
    fun `false summary mid-dash - a task flow inside the window cancels the end (#431)`() {
        var state = AppState()

        // Online and mid-pickup (heal path mints job+task from the task flow).
        state = machine.step(state, screenObs(
            flow = Flow.TaskPickupNavigation,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = "Chipotle"),
        )).newState
        val sessionId = state.regions.platforms[Platform.DoorDash]!!.session!!.sessionId

        // Misrecognized dash summary.
        state = machine.step(state, screenObs(
            flow = Flow.SessionEnded,
            parsed = ParsedFields.SessionEndedFields(totalEarnings = 99.0),
        )).newState
        assertNotNull(state.regions.platforms[Platform.DoorDash]!!.pendingDestructive)

        // The pickup screen is still there 1s later — clearly still dashing.
        state = machine.step(state, screenObs(
            flow = Flow.TaskPickupNavigation,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = "Chipotle"),
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertNull("misrecognition cancelled", dd.pendingDestructive)
        assertEquals("same session survives", sessionId, dd.session!!.sessionId)
        assertNotNull("task survives", dd.activeTask)
    }

    @Test
    fun `real summary commits at the deadline via GRACE_COMMIT - honest endedAt + full payload (#431)`() {
        var state = AppState()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState

        val summaryAt = tick()
        state = machine.step(state, screenObs(
            flow = Flow.SessionEnded,
            parsed = ParsedFields.SessionEndedFields(
                totalEarnings = 25.0,
                sessionDurationMillis = 3_600_000L,
                offersAccepted = 5,
                offersTotal = 8,
            ),
            timestamp = summaryAt,
        )).newState

        // The grace timer fires just past the deadline.
        val transition = machine.step(state, timeoutObs(
            type = TimeoutType.GRACE_COMMIT,
            timestamp = summaryAt + TransitionPolicy.AUTHORITATIVE_GRACE_MS + 100,
            targetPlatform = Platform.DoorDash,
        ))
        state = transition.newState

        assertNull(state.regions.platforms[Platform.DoorDash]!!.session)
        val stop = transition.effects.filterIsInstance<AppEffect.LogEvent>()
            .single { it.event.type == AppEventType.DASH_STOP }
        val payload = stop.event.payload as SessionStopPayload
        assertEquals(SessionEndSource.SUMMARY_SCREEN, payload.source)
        assertEquals(25.0, payload.totalEarnings!!, 0.001)
        assertEquals(3_600_000L, payload.sessionDurationMillis)
        // endedAt = when the summary appeared, not when the timer got around
        // to committing it.
        assertEquals(summaryAt, payload.endedAt)
    }

    @Test
    fun `post-summary online flash does NOT resurrect the session (#431)`() {
        var state = AppState()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState

        val summaryAt = tick()
        state = machine.step(state, screenObs(
            flow = Flow.SessionEnded,
            parsed = ParsedFields.SessionEndedFields(totalEarnings = 25.0),
            timestamp = summaryAt,
        )).newState

        // The idle map flashes "online" right after the summary — the old
        // immediate-commit existed exactly to stop this resurrecting the
        // session. The authoritative grace must not cancel on it.
        state = machine.step(state, screenObs(
            flow = Flow.Idle, modeHint = Mode.Online,
        )).newState
        assertNotNull(
            "authoritative pending survives an online flash",
            state.regions.platforms[Platform.DoorDash]!!.pendingDestructive,
        )

        // Commit still lands at the deadline.
        state = machine.step(state, timeoutObs(
            type = TimeoutType.GRACE_COMMIT,
            timestamp = summaryAt + TransitionPolicy.AUTHORITATIVE_GRACE_MS + 100,
            targetPlatform = Platform.DoorDash,
        )).newState
        assertNull(state.regions.platforms[Platform.DoorDash]!!.session)
    }

    @Test
    fun `session grace — offline then online within grace resumes same session`() {
        var state = AppState()

        // Get Online
        val t1 = tick()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1,
        )).newState

        val originalSessionId = state.regions.platforms[Platform.DoorDash]!!.session!!.sessionId

        // Non-authoritative offline (not SessionEnded) — grace period starts
        val t2 = t1 + 2000
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline,
            flow = Flow.Idle,
            parsed = ParsedFields.IdleFields(),
            timestamp = t2,
        )).newState

        val afterOffline = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Offline, afterOffline.mode)
        assertNotNull(afterOffline.session) // session preserved during grace
        assertNotNull(afterOffline.pendingDestructive)
        assertEquals(DestructiveKind.SESSION_END, afterOffline.pendingDestructive?.kind)

        // Back to Online within grace window (well within 10s)
        val t3 = t2 + 3000
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t3,
        )).newState

        val resumed = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Online, resumed.mode)
        assertEquals(originalSessionId, resumed.session!!.sessionId) // SAME session
        assertNull(resumed.pendingDestructive) // grace cleared
    }

    @Test
    fun `session grace — starting a new dash within the grace window starts fresh, not a resume`() {
        var state = AppState()

        // Online
        val t1 = tick()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1,
        )).newState
        val originalSessionId = state.regions.platforms[Platform.DoorDash]!!.session!!.sessionId

        // Offline — dash-end is provisional (grace armed)
        val t2 = t1 + 2000
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline, flow = Flow.Idle, parsed = ParsedFields.IdleFields(), timestamp = t2,
        )).newState
        assertNotNull(state.regions.platforms[Platform.DoorDash]!!.pendingDestructive)

        // The set-end-time screen (startingSession) arrives WITHIN the grace window —
        // the old dash really ended; commit it now (#279-B).
        val t3 = t2 + 1000
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline, flow = Flow.Idle,
            parsed = ParsedFields.IdleFields(startingSession = true), timestamp = t3,
        )).newState
        val afterStart = state.regions.platforms[Platform.DoorDash]!!
        assertNull("the dash-start signal ends the old dash", afterStart.session)
        assertNull(afterStart.pendingDestructive)

        // Dash Now → Online → a FRESH session, not the resumed old one.
        val t4 = t3 + 1000
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t4,
        )).newState
        val fresh = state.regions.platforms[Platform.DoorDash]!!
        assertNotNull(fresh.session)
        assertTrue(
            "a genuinely new dash, not a grace-resume of the old session",
            fresh.session!!.sessionId != originalSessionId,
        )
    }

    @Test
    fun `session grace — offline past grace then online creates new session`() {
        var state = AppState()

        // Get Online
        val t1 = tick()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1,
        )).newState

        val originalSessionId = state.regions.platforms[Platform.DoorDash]!!.session!!.sessionId

        // Non-authoritative offline — grace starts
        val t2 = t1 + 2000
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline,
            flow = Flow.Idle,
            parsed = ParsedFields.IdleFields(),
            timestamp = t2,
        )).newState

        // Grace expires lazily: next observation AFTER grace deadline triggers session end.
        // Grace deadline = t2 + 10_000 = t2 + 10s
        val t3 = t2 + 15_000 // well past grace
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t3,
        )).newState

        val afterExpiry = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Online, afterExpiry.mode)
        assertNotNull(afterExpiry.session)
        // Must be a NEW session — old one expired
        assertTrue(
            "Expected new session ID, but got same as original",
            afterExpiry.session!!.sessionId != originalSessionId,
        )
        assertNull(afterExpiry.pendingDestructive)
    }

    @Test
    fun `pause timeout transitions paused platform region to Offline with grace`() {
        val stepper = PlatformRegionStepper()
        val policy = TransitionPolicy()

        val session = cloud.trotter.dashbuddy.domain.state.Session(
            sessionId = "sess-1",
            startedAt = 100L,
        )
        val pausedRegion = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Paused,
            session = session,
        )
        val flow = FlowRegion(flow = Flow.Idle)

        val result = stepper.step(
            pausedRegion, flow, flow,
            timeoutObs(type = TimeoutType.SESSION_PAUSED_SAFETY),
            policy,
        )

        assertEquals(Mode.Offline, result.mode)
        // Pause timeout goes through applyModeTransition, gets grace
        assertNotNull(result.session) // session preserved during grace
        assertNotNull(result.pendingDestructive)
        assertEquals(DestructiveKind.SESSION_END, result.pendingDestructive?.kind)
    }

    // =========================================================================
    // TRANSITION CLASSIFICATION
    // =========================================================================

    @Test
    fun `unexpected transition — app restart mid-task synthesizes lifecycle`() {
        var state = AppState()

        // Cold start: first screen is a pickup navigation (app launched mid-delivery)
        // This is Offline→Online which is an unexpected transition when outcomes
        // don't include the observed flow
        state = machine.step(state, screenObs(
            flow = Flow.TaskPickupNavigation,
            parsed = taskFields(),
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Online, dd.mode)
        // healActiveLifecycle should have synthesized job + task
        assertNotNull(dd.activeJob)
        assertNotNull(dd.activeTask)
        assertTrue(dd.activeTask!!.recovered)
    }

    @Test
    fun `transition kind is Confirmed when mode stays the same`() {
        var state = AppState()

        // Get Online
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState

        // Another Online screen — confirms mode
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Online, dd.mode)
        assertEquals(TransitionKind.Confirmed, dd.lastTransitionKind)
    }

    // =========================================================================
    // TASK LIFECYCLE
    // =========================================================================

    @Test
    fun `job created when accepting offer`() {
        var state = AppState()

        // Get Online + present offer (one screen each, screen-authoritative)
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState

        // Accept: OfferPresented → TaskPickupNavigation
        state = machine.step(state, screenObs(flow = Flow.TaskPickupNavigation, parsed = taskFields())).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertNotNull(dd.activeJob)
        assertNotNull(dd.activeTask)
        assertEquals(TaskPhase.PICKUP, dd.activeTask!!.phase)
    }

    @Test
    fun `task phase changes from pickup to dropoff`() {
        var state = setupOnlineWithPickup()

        // Transition to dropoff
        state = machine.step(state, screenObs(
            flow = Flow.TaskDropoffNavigation,
            parsed = taskFields(phase = TaskPhase.DROPOFF),
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(TaskPhase.DROPOFF, dd.activeTask!!.phase)
        // Old pickup task should be in recentTasks
        assertTrue(dd.recentTasks.any { it.phase == TaskPhase.PICKUP })
    }

    @Test
    fun `task completes after the receipt grace - not on the PostTask frame (#431)`() {
        var state = setupOnlineWithPickup()

        // Complete delivery — the receipt frame arms the short retire grace.
        state = machine.step(state, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50),
        )).newState

        var dd = state.regions.platforms[Platform.DoorDash]!!
        assertNotNull("the task survives the receipt frame", dd.activeTask)
        assertEquals(DestructiveKind.TASK_RETIRE, dd.pendingDestructive?.kind)
        assertTrue(dd.pendingDestructive!!.authoritative)

        // Past the deadline (the GRACE_COMMIT timer in production; any later
        // observation commits via lazy expiry here).
        state = machine.step(state, screenObs(flow = Flow.Idle, timestamp = tick(3000))).newState

        dd = state.regions.platforms[Platform.DoorDash]!!
        assertNull("expiry completed the task", dd.activeTask)
        assertNull(dd.pendingDestructive)
        assertTrue(dd.recentTasks.isNotEmpty())
        assertNotNull(dd.recentTasks.last().completedAt)
    }

    @Test
    fun `GRACE_COMMIT timeout commits the pending task retire (#431)`() {
        var state = setupOnlineWithPickup()
        state = machine.step(state, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50),
        )).newState
        val deadline = state.regions.platforms[Platform.DoorDash]!!.pendingDestructive!!.deadline

        state = machine.step(state, timeoutObs(
            type = TimeoutType.GRACE_COMMIT,
            timestamp = deadline + 1,
            targetPlatform = Platform.DoorDash,
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertNull("the timer commit retired the task", dd.activeTask)
        assertNull(dd.pendingDestructive)
        assertTrue(dd.recentTasks.any { it.completedAt != null })
    }

    @Test
    fun `recentTasks capped at 20`() {
        var state = AppState()

        // Get Online (one screen)
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState

        // Cycle through 25 deliveries
        for (i in 1..25) {
            state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(hash = "hash-$i"))).newState
            state = machine.step(state, screenObs(flow = Flow.TaskPickupNavigation, parsed = taskFields())).newState
            state = machine.step(state, screenObs(flow = Flow.TaskDropoffNavigation, parsed = taskFields(phase = TaskPhase.DROPOFF))).newState
            state = machine.step(state, screenObs(flow = Flow.PostTask, parsed = ParsedFields.PostTaskFields(totalPay = 7.50))).newState
        }

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertTrue(
            "recentTasks should be capped at ${PlatformRegionStepper.MAX_RECENT_TASKS}, was ${dd.recentTasks.size}",
            dd.recentTasks.size <= PlatformRegionStepper.MAX_RECENT_TASKS,
        )
    }

    // =========================================================================
    // MULTI-PLATFORM ISOLATION
    // =========================================================================

    @Test
    fun `observations from different platforms create independent regions`() {
        var state = AppState()

        // DoorDash observation
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
            ruleId = "doordash.screen.offer",
        )).newState

        // Uber observation
        state = machine.step(state, screenObs(
            flow = Flow.Idle,
            ruleId = "uber.screen.idle",
        )).newState

        assertTrue(state.regions.platforms.containsKey(Platform.DoorDash))
        assertTrue(state.regions.platforms.containsKey(Platform.Uber))
        assertEquals(2, state.regions.platforms.size)
    }

    @Test
    fun `mode change on one platform does not affect another`() {
        var state = AppState()

        // Get DoorDash Online (single screen — screen-authoritative)
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
            ruleId = "doordash.screen.offer",
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.DoorDash]!!.mode)

        // Uber observation — should not affect DoorDash
        state = machine.step(state, screenObs(flow = Flow.Idle, ruleId = "uber.screen.idle")).newState

        assertEquals(Mode.Online, state.regions.platforms[Platform.DoorDash]!!.mode)
        assertEquals(Mode.Offline, state.regions.platforms[Platform.Uber]!!.mode)
    }

    // =========================================================================
    // CROSS-PLATFORM REGION
    // =========================================================================

    @Test
    fun `crossPlatform reflects aggregate state`() {
        var state = AppState()

        // Get DoorDash Online (single screen)
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
            ruleId = "doordash.screen.offer",
        )).newState

        assertTrue(state.regions.crossPlatform.anyPlatformOnline)
        assertEquals(1, state.regions.crossPlatform.activeSessionCount)
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Returns an AppState where DoorDash is Online with an active pickup task.
     */
    private fun setupOnlineWithPickup(): AppState {
        var state = AppState()

        // Screen-authoritative: one screen → Online
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented, parsed = offerFields(),
        )).newState

        // Accept offer → pickup
        state = machine.step(state, screenObs(flow = Flow.TaskPickupNavigation, parsed = taskFields())).newState
        return state
    }
}
