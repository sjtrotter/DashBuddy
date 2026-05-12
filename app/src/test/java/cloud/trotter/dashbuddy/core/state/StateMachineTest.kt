package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.ModeConfidence
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
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
            healingPolicy = HealingPolicy(),
            effectMap = EffectMap(MetadataProvider { """{ "test_mode": true }""" }),
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
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = parsed,
    )

    private fun clickObs(
        flow: Flow? = null,
        intent: String = "unknown",
        ruleId: String = "doordash.click.test",
        timestamp: Long = tick(),
    ) = Observation.Click(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = null,
        parsed = ParsedFields.ClickFields(intent = intent),
    )

    private fun timeoutObs(
        type: TimeoutType = TimeoutType.SESSION_PAUSED_SAFETY,
        timestamp: Long = tick(),
    ) = Observation.Timeout(timestamp = timestamp, type = type)

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
    // HEALING — MODE TRANSITIONS
    // =========================================================================

    @Test
    fun `healing — Offline to Online heals with 1 observation (threshold 1)`() {
        var state = AppState()

        // Single observation implying Online while Offline — should heal immediately
        // because offline→online threshold is 1 (dedup-by-classification means
        // repeated identical screens don't re-send)
        val t1 = tick()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
            timestamp = t1,
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Online, dd.mode) // healed on first observation!
        assertEquals(ModeConfidence.EMPTY, dd.confidence) // reset after healing
    }

    @Test
    fun `healing — Online to Offline requires 2 observations (threshold 2)`() {
        var state = AppState()

        // Get to Online first (heals at threshold 1)
        val t1 = tick()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
            timestamp = t1,
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.DoorDash]!!.mode)

        // First offline signal — should NOT transition yet (threshold 2)
        val t2 = t1 + 1000
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline,
            flow = Flow.Idle,
            parsed = ParsedFields.IdleFields(),
            timestamp = t2,
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Online, dd.mode) // still Online
        assertEquals(1, dd.confidence.supportingObservations)
        assertEquals(Mode.Offline, dd.confidence.pendingMode)

        // Second offline signal within window — should heal to Offline
        val t3 = t2 + 2000
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline,
            flow = Flow.Idle,
            parsed = ParsedFields.IdleFields(),
            timestamp = t3,
        )).newState

        val dd2 = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Offline, dd2.mode) // healed to Offline
        assertEquals(ModeConfidence.EMPTY, dd2.confidence) // reset after healing
    }

    @Test
    fun `healing — stale confidence resets accrual`() {
        var state = AppState()

        // Get to Online first
        val t0 = tick()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
            timestamp = t0,
        )).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.DoorDash]!!.mode)

        // First offline observation (Online→Offline uses threshold 2)
        val t1 = t0 + 1000
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline,
            flow = Flow.Idle,
            parsed = ParsedFields.IdleFields(),
            timestamp = t1,
        )).newState

        assertEquals(Mode.Online, state.regions.platforms[Platform.DoorDash]!!.mode)

        // Second observation OUTSIDE 10s window — should reset, not heal
        val t2 = t1 + 15_000L
        state = machine.step(state, screenObs(
            modeHint = Mode.Offline,
            flow = Flow.Idle,
            parsed = ParsedFields.IdleFields(),
            timestamp = t2,
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertEquals(Mode.Online, dd.mode) // still Online — reset, not healed
        assertEquals(1, dd.confidence.supportingObservations) // reset to 1
        assertEquals(t2, dd.confidence.firstSeenAt) // new window started
    }

    @Test
    fun `plausible transition — Online to Paused applies immediately`() {
        var state = AppState()

        // Get to Online first (need healing from Offline)
        val t1 = tick()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1)).newState
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1 + 1000)).newState
        assertEquals(Mode.Online, state.regions.platforms[Platform.DoorDash]!!.mode)

        // Now Online → Paused should apply immediately (plausible)
        state = machine.step(state, screenObs(
            modeHint = Mode.Paused,
            parsed = ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000),
        )).newState
        assertEquals(Mode.Paused, state.regions.platforms[Platform.DoorDash]!!.mode)
    }

    // =========================================================================
    // SESSION LIFECYCLE
    // =========================================================================

    @Test
    fun `session created when transitioning to Online`() {
        var state = AppState()

        // Heal to Online (threshold 1 — heals on first observation)
        val t1 = tick()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1)).newState

        val session = state.regions.platforms[Platform.DoorDash]!!.session
        assertNotNull(session)
        assertTrue(session!!.sessionId.isNotEmpty())
    }

    @Test
    fun `session cleared on transition to Offline`() {
        var state = AppState()

        // Get Online
        val t1 = tick()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1)).newState
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1 + 1000)).newState
        assertNotNull(state.regions.platforms[Platform.DoorDash]!!.session)

        // End session (Online → Offline via SessionEnded is plausible)
        state = machine.step(state, screenObs(
            flow = Flow.SessionEnded,
            parsed = ParsedFields.SessionEndedFields(totalEarnings = 25.0),
        )).newState
        assertEquals(Mode.Offline, state.regions.platforms[Platform.DoorDash]!!.mode)
        assertNull(state.regions.platforms[Platform.DoorDash]!!.session)
    }

    /**
     * Timeout applied directly to a paused platform region transitions to
     * Offline and ends the session.
     *
     * Note: tested at the stepper level because [Observation.Timeout] has no
     * ruleId, so [StateMachine] routes it to [Platform.Unknown]. In production
     * this works because [SideEffectEngine] fires the timeout and the
     * platform-level effect handles the state change.
     */
    @Test
    fun `pause timeout transitions paused platform region to Offline`() {
        val stepper = PlatformRegionStepper()
        val healing = HealingPolicy()

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
            healing,
        )

        assertEquals(Mode.Offline, result.mode)
        assertNull(result.session)
    }

    // =========================================================================
    // TASK LIFECYCLE
    // =========================================================================

    @Test
    fun `job created when accepting offer`() {
        var state = AppState()

        // Get Online + present offer
        val t1 = tick()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1)).newState
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1 + 1000)).newState

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
    fun `task completed on PostTask`() {
        var state = setupOnlineWithPickup()

        // Complete delivery
        state = machine.step(state, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50),
        )).newState

        val dd = state.regions.platforms[Platform.DoorDash]!!
        assertNull(dd.activeTask) // task completed
        assertTrue(dd.recentTasks.isNotEmpty())
        assertNotNull(dd.recentTasks.last().completedAt)
    }

    @Test
    fun `recentTasks capped at 20`() {
        var state = AppState()

        // Get Online
        val t1 = tick()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1)).newState
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1 + 1000)).newState

        // Cycle through 25 deliveries (pickup → dropoff → postTask → next pickup...)
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
        val t1 = tick()
        state = machine.step(state, screenObs(
            flow = Flow.OfferPresented,
            parsed = offerFields(),
            ruleId = "doordash.screen.offer",
            timestamp = t1,
        )).newState

        // Uber observation
        state = machine.step(state, screenObs(
            flow = Flow.Idle,
            ruleId = "uber.screen.idle",
            timestamp = t1 + 500,
        )).newState

        assertTrue(state.regions.platforms.containsKey(Platform.DoorDash))
        assertTrue(state.regions.platforms.containsKey(Platform.Uber))
        assertEquals(2, state.regions.platforms.size)
    }

    @Test
    fun `mode change on one platform does not affect another`() {
        var state = AppState()

        // Get DoorDash Online (2 observations for healing)
        val t1 = tick()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), ruleId = "doordash.screen.offer", timestamp = t1)).newState
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), ruleId = "doordash.screen.offer", timestamp = t1 + 1000)).newState
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

        // Get DoorDash Online
        val t1 = tick()
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), ruleId = "doordash.screen.offer", timestamp = t1)).newState
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), ruleId = "doordash.screen.offer", timestamp = t1 + 1000)).newState

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
        val t1 = tick()

        // Heal to Online
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1)).newState
        state = machine.step(state, screenObs(flow = Flow.OfferPresented, parsed = offerFields(), timestamp = t1 + 1000)).newState

        // Accept offer → pickup
        state = machine.step(state, screenObs(flow = Flow.TaskPickupNavigation, parsed = taskFields())).newState
        return state
    }
}
