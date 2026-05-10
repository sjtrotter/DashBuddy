package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EffectMap.diff] — verifies correct effects are emitted
 * for each type of state transition.
 */
class EffectMapTest {

    private val effectMap = EffectMap()

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun screenObs(
        flow: Flow? = null,
        modeHint: Mode? = null,
        parsed: ParsedFields = ParsedFields.None,
        ruleId: String = "doordash.screen.test",
        timestamp: Long = 1000L,
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = parsed,
    )

    private fun notificationObs(
        intent: String,
        amount: Double? = null,
        storeName: String? = null,
        deliveredAt: String? = null,
        rawText: String? = null,
        ruleId: String = "doordash.notification.test",
    ) = Observation.Notification(
        timestamp = 1000L,
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.NotificationFields(
            intent = intent,
            amount = amount,
            storeName = storeName,
            deliveredAt = deliveredAt,
            rawText = rawText,
        ),
    )

    private fun clickObs(
        intent: String = "unknown",
        ruleId: String = "doordash.click.test",
    ) = Observation.Click(
        timestamp = 1000L,
        captureId = "cap-1000",
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.ClickFields(intent = intent),
    )

    private val testParsedOffer = ParsedOffer(
        offerHash = "hash-123",
        payAmount = 7.50,
        distanceMiles = 3.2,
        orders = listOf(
            ParsedOrder(
                orderIndex = 0,
                orderType = OrderType.PICKUP,
                storeName = "Chipotle",
                itemCount = 1,
                isItemCountEstimated = false,
                badges = emptySet(),
            ),
        ),
    )

    private val testOfferFields = ParsedFields.OfferFields(parsedOffer = testParsedOffer)

    private val testPendingOffer = PendingOffer(
        offerHash = "hash-123",
        offerFields = testOfferFields,
        presentedAt = 500L,
        returnFlow = Flow.Idle,
    )

    private fun stateWithPlatform(
        mode: Mode = Mode.Online,
        sessionId: String? = "sess-1",
        activeTask: Task? = null,
        recentTasks: List<Task> = emptyList(),
    ): Pair<Platform, PlatformRegion> {
        val session = sessionId?.let { Session(it, startedAt = 100L) }
        return Platform.DoorDash to PlatformRegion(
            platform = Platform.DoorDash,
            mode = mode,
            session = session,
            activeTask = activeTask,
            recentTasks = recentTasks,
        )
    }

    private inline fun <reified T : AppEffect> List<AppEffect>.effectsOfType(): List<T> =
        filterIsInstance<T>()

    private fun List<AppEffect>.logEvents(): List<AppEffect.LogEvent> = effectsOfType()

    private fun List<AppEffect>.logEventTypes(): List<AppEventType> =
        logEvents().map { it.event.eventType }

    // =========================================================================
    // OFFER EFFECTS
    // =========================================================================

    @Test
    fun `offer presented emits Evaluate and Speak (screenshot and log now via rule effects)`() {
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.Idle)))
        val next = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer,
                activePlatform = Platform.DoorDash,
            ),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.OfferPresented, parsed = testOfferFields))

        assertTrue("Should emit EvaluateOffer", effects.any { it is AppEffect.EvaluateOffer })
        assertTrue("Should emit SpeakOffer", effects.any { it is AppEffect.SpeakOffer })
        // Screenshot + OFFER_RECEIVED log now handled by rule-declared effects
        assertTrue("No hardcoded CaptureScreenshot", effects.none { it is AppEffect.CaptureScreenshot })
        assertTrue("No hardcoded OFFER_RECEIVED log", !effects.logEventTypes().contains(AppEventType.OFFER_RECEIVED))
    }

    @Test
    fun `offer accepted emits OFFER_ACCEPTED log`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(lastClickIntent = "accept_offer"),
            ),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskPickupNavigation))
        assertTrue(effects.logEventTypes().contains(AppEventType.OFFER_ACCEPTED))
    }

    @Test
    fun `offer declined emits OFFER_DECLINED log`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(lastClickIntent = "decline_offer"),
            ),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.Idle),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))
        assertTrue(effects.logEventTypes().contains(AppEventType.OFFER_DECLINED))
    }

    @Test
    fun `offer timeout emits OFFER_TIMEOUT log and UpdateBubble`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer, // no click intent
            ),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.Idle),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))
        assertTrue(effects.logEventTypes().contains(AppEventType.OFFER_TIMEOUT))
        assertTrue("Should show timeout bubble", effects.any {
            it is AppEffect.UpdateBubble && it.text.contains("Timed Out")
        })
    }

    @Test
    fun `click accept during offer emits UpdateBubble`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer),
        ))
        // Next state doesn't change flow (click doesn't change flow by itself)
        val next = prev

        val effects = effectMap.diff(prev, next, clickObs(intent = "accept_offer"))
        assertTrue(effects.any {
            it is AppEffect.UpdateBubble && it.text == "Offer Accepted"
        })
    }

    @Test
    fun `click decline during offer emits UpdateBubble`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer),
        ))
        val next = prev

        val effects = effectMap.diff(prev, next, clickObs(intent = "decline_offer"))
        assertTrue(effects.any {
            it is AppEffect.UpdateBubble && it.text == "Offer Declined"
        })
    }

    // =========================================================================
    // MODE TRANSITION EFFECTS
    // =========================================================================

    @Test
    fun `session start emits DASH_START, StartOdometer, StartDash`() {
        val (platform, _) = stateWithPlatform(mode = Mode.Offline, sessionId = null)
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Offline)),
        ))

        val newSession = Session("sess-new", startedAt = 1000L)
        val next = AppState(regions = Regions(
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = newSession)),
        ))

        val effects = effectMap.diff(prev, next, screenObs())

        assertTrue("Should emit DASH_START", effects.logEventTypes().contains(AppEventType.DASH_START))
        assertTrue("Should emit StartOdometer", effects.any { it is AppEffect.StartOdometer })
        assertTrue("Should emit StartDash", effects.any { it is AppEffect.StartDash })
    }

    @Test
    fun `session end emits DASH_STOP, StopOdometer, EndDash`() {
        val (platform, onlineRegion) = stateWithPlatform(mode = Mode.Online, sessionId = "sess-1")
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to onlineRegion),
        ))

        val next = AppState(regions = Regions(
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Offline)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(
            flow = Flow.SessionEnded,
            parsed = ParsedFields.SessionEndedFields(totalEarnings = 25.0),
        ))

        assertTrue("Should emit DASH_STOP", effects.logEventTypes().contains(AppEventType.DASH_STOP))
        assertTrue("Should emit StopOdometer", effects.any { it is AppEffect.StopOdometer })
        assertTrue("Should emit EndDash", effects.any { it is AppEffect.EndDash })
    }

    @Test
    fun `pause emits DASH_PAUSED, ScheduleTimeout, UpdateBubble`() {
        val (platform, onlineRegion) = stateWithPlatform(mode = Mode.Online, sessionId = "sess-1")
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to onlineRegion),
        ))

        val next = AppState(regions = Regions(
            platforms = mapOf(platform to onlineRegion.copy(mode = Mode.Paused)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(
            modeHint = Mode.Paused,
            parsed = ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000),
        ))

        assertTrue("Should emit DASH_PAUSED", effects.logEventTypes().contains(AppEventType.DASH_PAUSED))
        assertTrue("Should emit ScheduleTimeout", effects.any {
            it is AppEffect.ScheduleTimeout && it.type == TimeoutType.SESSION_PAUSED_SAFETY
        })
        assertTrue("Should emit UpdateBubble", effects.any {
            it is AppEffect.UpdateBubble && it.text.contains("Paused")
        })
    }

    @Test
    fun `resume from pause cancels safety timeout`() {
        val (platform, _) = stateWithPlatform(mode = Mode.Paused, sessionId = "sess-1")
        val pausedRegion = PlatformRegion(platform, mode = Mode.Paused, session = Session("sess-1", startedAt = 100L))
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to pausedRegion),
        ))

        val next = AppState(regions = Regions(
            platforms = mapOf(platform to pausedRegion.copy(mode = Mode.Online)),
        ))

        val effects = effectMap.diff(prev, next, screenObs())

        assertTrue("Should cancel timeout", effects.any {
            it is AppEffect.CancelTimeout && it.type == TimeoutType.SESSION_PAUSED_SAFETY
        })
    }

    // =========================================================================
    // TASK EFFECTS
    // =========================================================================

    @Test
    fun `pickup start emits PICKUP_NAV_STARTED and ResumeOdometer`() {
        val (platform, _) = stateWithPlatform()
        val prevRegion = PlatformRegion(platform, mode = Mode.Online, session = Session("sess-1", startedAt = 100L))
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented),
            platforms = mapOf(platform to prevRegion),
        ))

        val task = Task(
            taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP,
            storeName = "Chipotle", startedAt = 1000L,
        )
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
            platforms = mapOf(platform to prevRegion.copy(activeTask = task)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskPickupNavigation, parsed = ParsedFields.TaskFields(storeName = "Chipotle", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION)))

        assertTrue("Should emit PICKUP_NAV_STARTED", effects.logEventTypes().contains(AppEventType.PICKUP_NAV_STARTED))
        assertTrue("Should emit ResumeOdometer", effects.any { it is AppEffect.ResumeOdometer })
        assertTrue("Should emit UpdateBubble with store name", effects.any {
            it is AppEffect.UpdateBubble && it.text.contains("Chipotle")
        })
    }

    @Test
    fun `pickup to dropoff emits PICKUP_CONFIRMED and DELIVERY_NAV_STARTED`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val pickupTask = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP, storeName = "Chipotle", startedAt = 900L)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupArrived),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = pickupTask)),
        ))

        val dropoffTask = Task(taskId = "task-2", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", startedAt = 1000L)
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = dropoffTask)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskDropoffNavigation, parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION)))

        assertTrue("Should emit PICKUP_CONFIRMED", effects.logEventTypes().contains(AppEventType.PICKUP_CONFIRMED))
        assertTrue("Should emit DELIVERY_NAV_STARTED", effects.logEventTypes().contains(AppEventType.DELIVERY_NAV_STARTED))
        assertTrue("Should emit ResumeOdometer", effects.any { it is AppEffect.ResumeOdometer })
    }

    @Test
    fun `arrival at pickup emits PauseOdometer and PICKUP_ARRIVED`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val navTask = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP, storeName = "Chipotle", startedAt = 900L, arrivedAt = null)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = navTask)),
        ))

        val arrivedTask = navTask.copy(arrivedAt = 1000L)
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupArrived),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = arrivedTask)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskPickupArrived, parsed = ParsedFields.TaskFields(storeName = "Chipotle", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.ARRIVED)))

        assertTrue("Should emit PauseOdometer", effects.any { it is AppEffect.PauseOdometer })
        assertTrue("Should emit PICKUP_ARRIVED", effects.logEventTypes().contains(AppEventType.PICKUP_ARRIVED))
    }

    @Test
    fun `arrival at dropoff emits PauseOdometer and DELIVERY_ARRIVED`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val navTask = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", startedAt = 900L, arrivedAt = null)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = navTask)),
        ))

        val arrivedTask = navTask.copy(arrivedAt = 1000L)
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffArrived),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = arrivedTask)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskDropoffArrived, parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.ARRIVED)))

        assertTrue("Should emit PauseOdometer", effects.any { it is AppEffect.PauseOdometer })
        assertTrue("Should emit DELIVERY_ARRIVED", effects.logEventTypes().contains(AppEventType.DELIVERY_ARRIVED))
    }

    // =========================================================================
    // DELIVERY_COMPLETED (Phase 1B fix)
    // =========================================================================

    @Test
    fun `leaving PostTask emits DELIVERY_COMPLETED`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val completedTask = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", startedAt = 900L, completedAt = 950L)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, recentTasks = listOf(completedTask))),
        ))

        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.Idle),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, recentTasks = listOf(completedTask))),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))
        assertTrue("Should emit DELIVERY_COMPLETED", effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED))
    }

    @Test
    fun `staying on PostTask does NOT emit DELIVERY_COMPLETED`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session)),
        ))

        // Same flow, just updated fields
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.PostTask, parsed = ParsedFields.PostTaskFields(totalPay = 7.50)))
        assertTrue(
            "Should NOT emit DELIVERY_COMPLETED when staying on PostTask",
            !effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
    }

    // =========================================================================
    // NOTIFICATION EFFECTS
    // =========================================================================

    @Test
    fun `additional_tip notification emits ProcessTipNotification and LogEvent`() {
        val (platform, onlineRegion) = stateWithPlatform()
        val state = AppState(regions = Regions(platforms = mapOf(platform to onlineRegion)))

        val effects = effectMap.diff(state, state, notificationObs(
            intent = "additional_tip",
            amount = 5.0,
            storeName = "Chipotle",
            deliveredAt = "2024-01-01",
        ))

        assertTrue("Should emit ProcessTipNotification", effects.any { it is AppEffect.ProcessTipNotification })
        assertTrue("Should emit NOTIFICATION_RECEIVED", effects.logEventTypes().contains(AppEventType.NOTIFICATION_RECEIVED))
    }

    @Test
    fun `new_order notification emits LogEvent`() {
        val (platform, onlineRegion) = stateWithPlatform()
        val state = AppState(regions = Regions(platforms = mapOf(platform to onlineRegion)))

        val effects = effectMap.diff(state, state, notificationObs(intent = "new_order"))
        assertTrue(effects.logEventTypes().contains(AppEventType.NOTIFICATION_RECEIVED))
    }

    // =========================================================================
    // NO SPURIOUS EFFECTS
    // =========================================================================

    @Test
    fun `no effects when state unchanged on idle observation`() {
        val state = AppState()
        val effects = effectMap.diff(state, state, screenObs(flow = Flow.Idle))

        // Should have no mode-change effects, no task effects, no offer effects
        val significantEffects = effects.filter { it !is AppEffect.LogEvent }
        assertEquals("No significant effects on unchanged state", 0, significantEffects.size)
    }

    @Test
    fun `no mode effects when mode stays the same`() {
        val (platform, onlineRegion) = stateWithPlatform(mode = Mode.Online)
        val state = AppState(regions = Regions(
            platforms = mapOf(platform to onlineRegion),
        ))

        val effects = effectMap.diff(state, state, screenObs())

        assertTrue("No StartOdometer", effects.none { it is AppEffect.StartOdometer })
        assertTrue("No StopOdometer", effects.none { it is AppEffect.StopOdometer })
        assertTrue("No StartDash", effects.none { it is AppEffect.StartDash })
        assertTrue("No EndDash", effects.none { it is AppEffect.EndDash })
    }

    // =========================================================================
    // TRANSITION OVERRIDE EFFECTS
    // =========================================================================

    private fun makeRequestedEffect(
        verb: EffectVerb,
        args: Map<String, String> = emptyMap(),
        ruleId: String = "uber.screen.test",
    ) = RequestedEffect(verb = verb, args = args, ruleId = ruleId)

    private fun screenObsWithOverrides(
        overrides: Map<TransitionTrigger, List<RequestedEffect>>,
        flow: Flow? = null,
        modeHint: Mode? = null,
        parsed: ParsedFields = ParsedFields.None,
        ruleId: String = "uber.screen.test",
    ) = Observation.Screen(
        timestamp = 1000L,
        captureId = "cap-1000",
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = parsed,
        transitionOverrides = overrides,
    )

    @Test
    fun `MODE_TO_ONLINE override replaces default session start effects`() {
        val (platform, _) = stateWithPlatform(mode = Mode.Offline, sessionId = null)
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Offline)),
        ))

        val newSession = Session("sess-new", startedAt = 1000L)
        val next = AppState(regions = Regions(
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = newSession)),
        ))

        val overrides = mapOf(
            TransitionTrigger.MODE_TO_ONLINE to listOf(
                makeRequestedEffect(EffectVerb.SESSION_START, mapOf("platformName" to "Uber")),
                makeRequestedEffect(EffectVerb.LOG, mapOf("type" to "SESSION_START")),
            ),
        )
        val effects = effectMap.diff(prev, next, screenObsWithOverrides(overrides))

        // Override effects should be present as RequestEffect
        val requestEffects = effects.filterIsInstance<AppEffect.RequestEffect>()
        assertEquals("Should have 2 override effects", 2, requestEffects.size)
        assertEquals(EffectVerb.SESSION_START, requestEffects[0].effect.verb)
        assertEquals(EffectVerb.LOG, requestEffects[1].effect.verb)

        // Default effects should NOT be present
        assertTrue("No StartOdometer", effects.none { it is AppEffect.StartOdometer })
        assertTrue("No StartDash", effects.none { it is AppEffect.StartDash })
    }

    @Test
    fun `MODE_TO_PAUSED override replaces default pause effects`() {
        val (platform, onlineRegion) = stateWithPlatform(mode = Mode.Online, sessionId = "sess-1")
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to onlineRegion),
        ))
        val next = AppState(regions = Regions(
            platforms = mapOf(platform to onlineRegion.copy(mode = Mode.Paused)),
        ))

        val overrides = mapOf(
            TransitionTrigger.MODE_TO_PAUSED to listOf(
                makeRequestedEffect(EffectVerb.BUBBLE, mapOf("text" to "Uber Paused")),
            ),
        )
        val effects = effectMap.diff(prev, next, screenObsWithOverrides(
            overrides,
            modeHint = Mode.Paused,
            parsed = ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000),
        ))

        // Override effects
        val requestEffects = effects.filterIsInstance<AppEffect.RequestEffect>()
        assertEquals("Should have 1 override effect", 1, requestEffects.size)
        assertEquals(EffectVerb.BUBBLE, requestEffects[0].effect.verb)

        // Defaults suppressed
        assertTrue("No ScheduleTimeout", effects.none { it is AppEffect.ScheduleTimeout })
        assertTrue("No default UpdateBubble", effects.none { it is AppEffect.UpdateBubble })
    }

    @Test
    fun `MODE_TO_OFFLINE override replaces default session end effects`() {
        val (platform, onlineRegion) = stateWithPlatform(mode = Mode.Online, sessionId = "sess-1")
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to onlineRegion),
        ))
        val next = AppState(regions = Regions(
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Offline)),
        ))

        val overrides = mapOf(
            TransitionTrigger.MODE_TO_OFFLINE to listOf(
                makeRequestedEffect(EffectVerb.SESSION_END, mapOf("platformName" to "Uber")),
            ),
        )
        val effects = effectMap.diff(prev, next, screenObsWithOverrides(overrides))

        val requestEffects = effects.filterIsInstance<AppEffect.RequestEffect>()
        assertEquals(1, requestEffects.size)
        assertEquals(EffectVerb.SESSION_END, requestEffects[0].effect.verb)

        // Defaults suppressed
        assertTrue("No StopOdometer", effects.none { it is AppEffect.StopOdometer })
        assertTrue("No EndDash", effects.none { it is AppEffect.EndDash })
    }

    @Test
    fun `RESUME_FROM_PAUSE override replaces default cancel timeout`() {
        val (platform, _) = stateWithPlatform(mode = Mode.Paused, sessionId = "sess-1")
        val pausedRegion = PlatformRegion(platform, mode = Mode.Paused, session = Session("sess-1", startedAt = 100L))
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to pausedRegion),
        ))
        val next = AppState(regions = Regions(
            platforms = mapOf(platform to pausedRegion.copy(mode = Mode.Online)),
        ))

        val overrides = mapOf(
            TransitionTrigger.RESUME_FROM_PAUSE to listOf(
                makeRequestedEffect(EffectVerb.CANCEL_TIMEOUT, mapOf("type" to "SESSION_PAUSED_SAFETY")),
                makeRequestedEffect(EffectVerb.LOG, mapOf("type" to "RESUMED")),
            ),
        )
        val effects = effectMap.diff(prev, next, screenObsWithOverrides(overrides))

        val requestEffects = effects.filterIsInstance<AppEffect.RequestEffect>()
        assertTrue("Should have resume override effects", requestEffects.size >= 2)

        // Default CancelTimeout should NOT be present
        assertTrue("No default CancelTimeout", effects.none { it is AppEffect.CancelTimeout })
    }

    @Test
    fun `TASK_START override replaces default pickup effects`() {
        val (platform, _) = stateWithPlatform()
        val prevRegion = PlatformRegion(platform, mode = Mode.Online, session = Session("sess-1", startedAt = 100L))
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented),
            platforms = mapOf(platform to prevRegion),
        ))

        val task = Task(
            taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP,
            storeName = "McDonald's", startedAt = 1000L,
        )
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
            platforms = mapOf(platform to prevRegion.copy(activeTask = task)),
        ))

        val overrides = mapOf(
            TransitionTrigger.TASK_START to listOf(
                makeRequestedEffect(EffectVerb.ODOMETER_RESUME),
                makeRequestedEffect(EffectVerb.BUBBLE, mapOf("text" to "Uber pickup")),
            ),
        )
        val effects = effectMap.diff(prev, next, screenObsWithOverrides(
            overrides,
            flow = Flow.TaskPickupNavigation,
            parsed = ParsedFields.TaskFields(storeName = "McDonald's", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION),
        ))

        val requestEffects = effects.filterIsInstance<AppEffect.RequestEffect>()
        assertEquals(2, requestEffects.size)
        assertEquals(EffectVerb.ODOMETER_RESUME, requestEffects[0].effect.verb)
        assertEquals(EffectVerb.BUBBLE, requestEffects[1].effect.verb)

        // Defaults suppressed
        assertTrue("No default ResumeOdometer", effects.none { it is AppEffect.ResumeOdometer })
        assertTrue("No default UpdateBubble", effects.none { it is AppEffect.UpdateBubble })
    }

    @Test
    fun `TASK_ARRIVED override replaces default arrival effects`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val navTask = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP, storeName = "Chipotle", startedAt = 900L)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = navTask)),
        ))

        val arrivedTask = navTask.copy(arrivedAt = 1000L)
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupArrived),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = arrivedTask)),
        ))

        val overrides = mapOf(
            TransitionTrigger.TASK_ARRIVED to listOf(
                makeRequestedEffect(EffectVerb.ODOMETER_PAUSE),
            ),
        )
        val effects = effectMap.diff(prev, next, screenObsWithOverrides(
            overrides,
            flow = Flow.TaskPickupArrived,
            parsed = ParsedFields.TaskFields(storeName = "Chipotle", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.ARRIVED),
        ))

        val requestEffects = effects.filterIsInstance<AppEffect.RequestEffect>()
        assertEquals(1, requestEffects.size)
        assertEquals(EffectVerb.ODOMETER_PAUSE, requestEffects[0].effect.verb)

        // Default PauseOdometer suppressed
        assertTrue("No default PauseOdometer", effects.none { it is AppEffect.PauseOdometer })
    }

    @Test
    fun `no override present falls through to defaults`() {
        // Same setup as session start test — but with empty overrides
        val (platform, _) = stateWithPlatform(mode = Mode.Offline, sessionId = null)
        val prev = AppState(regions = Regions(
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Offline)),
        ))

        val newSession = Session("sess-new", startedAt = 1000L)
        val next = AppState(regions = Regions(
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = newSession)),
        ))

        // Observation with overrides but NOT for MODE_TO_ONLINE
        val overrides = mapOf(
            TransitionTrigger.MODE_TO_PAUSED to listOf(
                makeRequestedEffect(EffectVerb.LOG),
            ),
        )
        val effects = effectMap.diff(prev, next, screenObsWithOverrides(overrides))

        // Defaults should fire since MODE_TO_ONLINE has no override
        assertTrue("Should emit StartOdometer", effects.any { it is AppEffect.StartOdometer })
        assertTrue("Should emit StartDash", effects.any { it is AppEffect.StartDash })
        assertTrue("Should emit DASH_START", effects.logEventTypes().contains(AppEventType.DASH_START))
    }
}
