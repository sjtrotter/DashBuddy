package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionPausedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
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
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that EffectMap.diff() emits rich JSON payloads at every
 * phase-boundary AppEvent. The flow-card stack (#257) folds these events
 * into per-phase snapshots without joining other entities, so each payload
 * must carry the full state of the phase as it closed.
 */
class EffectMapPayloadTest {

    private val gson = Gson()
    private val metadataProvider = MetadataProvider { "{}" }
    private val effectMap = EffectMap(metadataProvider)

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun appState(
        flow: FlowRegion = FlowRegion(),
        platforms: Map<Platform, PlatformRegion> = emptyMap(),
    ) = AppState(
        regions = Regions(
            flow = flow,
            platforms = platforms,
            crossPlatform = CrossPlatformRegion(),
        ),
    )

    private fun screenObs(
        flow: Flow? = null,
        modeHint: Mode? = null,
        parsed: ParsedFields = ParsedFields.None,
        timestamp: Long = 1000L,
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "test.rule",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = parsed,
    )

    private fun parsedOffer(hash: String = "offer-1", pay: Double = 7.50, miles: Double = 4.2) =
        ParsedOffer(
            offerHash = hash,
            payAmount = pay,
            distanceMiles = miles,
            itemCount = 1,
        )

    private fun evaluation(score: Double = 75.0, netPay: Double = 6.50) = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = score,
        qualityLevel = "Good",
        recommendationText = "Recommended: ACCEPT",
        payAmount = 7.50,
        fuelCostEstimate = 0.5,
        nonFuelCostEstimate = 0.5,
        totalOperatingCost = 1.0,
        operatingCostPerMile = 0.24,
        netPayAmount = netPay,
        distanceMiles = 4.2,
        dollarsPerMile = 1.55,
        dollarsPerHour = 22.0,
        estimatedTimeMinutes = 18.0,
        itemCount = 1.0,
        merchantName = "Wendy's",
    )

    private fun pendingOffer(
        hash: String = "offer-1",
        presentedAt: Long = 900L,
    ) = PendingOffer(
        offerHash = hash,
        offerFields = ParsedFields.OfferFields(parsedOffer = parsedOffer(hash)),
        presentedAt = presentedAt,
        evaluation = evaluation(),
        returnFlow = Flow.Idle,
        lastClickIntent = null,
    )

    private fun task(
        taskId: String = "task-1",
        jobId: String = "job-1",
        phase: TaskPhase = TaskPhase.PICKUP,
        storeName: String? = "Wendy's",
        startedAt: Long = 1000L,
        arrivedAt: Long? = null,
        deadlineMillis: Long? = null,
    ) = Task(
        taskId = taskId,
        jobId = jobId,
        phase = phase,
        storeName = storeName,
        startedAt = startedAt,
        arrivedAt = arrivedAt,
        deadlineMillis = deadlineMillis,
    )

    private fun logEvents(
        prev: AppState,
        next: AppState,
        obs: Observation,
        type: AppEventType,
    ): List<AppEffect.LogEvent> = effectMap.diff(prev, next, obs)
        .filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.eventType == type }

    // =========================================================================
    // Tests — Offer payloads
    // =========================================================================

    @Test
    fun `OFFER_RECEIVED emitted on offer arrival with typed payload`() {
        val region = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("dash-7", startedAt = 500L),
        )
        val prev = appState(
            flow = FlowRegion(flow = Flow.Idle, pendingOffer = null, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to region),
        )
        val newOffer = pendingOffer(hash = "new-1", presentedAt = 1234L)
        val next = appState(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = newOffer, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to region),
        )
        val obs = screenObs(flow = Flow.OfferPresented, timestamp = 1234L)

        val logs = logEvents(prev, next, obs, AppEventType.OFFER_RECEIVED)
        assertEquals(1, logs.size)

        // Regression: aggregateId must match sessionId so the bubble HUD's
        // getEventsForDash(dashId) query sees it.
        assertEquals("dash-7", logs[0].event.aggregateId)

        val payload = gson.fromJson(logs[0].event.eventPayload, OfferReceivedPayload::class.java)
        assertEquals("new-1", payload.offerHash)
        assertEquals(1234L, payload.presentedAt)
        assertEquals("DoorDash", payload.platform)
        assertEquals(7.50, payload.parsedOffer.payAmount!!, 0.001)
        assertEquals(4.2, payload.parsedOffer.distanceMiles!!, 0.001)
    }

    @Test
    fun `OFFER_ACCEPTED carries rich payload AND correct aggregateId (sessionId)`() {
        val offer = pendingOffer(hash = "abc", presentedAt = 900L)
        val region = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("dash-42", startedAt = 500L),
        )
        val prev = appState(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = offer, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to region),
        )
        val next = appState(
            flow = FlowRegion(flow = Flow.Idle, pendingOffer = null, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to region),
        )
        val click = Observation.Click(
            timestamp = 1500L,
            captureId = null,
            ruleId = "test.click",
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.ClickFields(intent = "accept_offer"),
        )

        val logs = logEvents(prev, next, click, AppEventType.OFFER_ACCEPTED)
        assertEquals(1, logs.size)

        // Regression: AppEventDao.getEventsForDash(dashId) filters by
        // aggregateId. Offer events with null aggregateId are invisible
        // to the bubble HUD's flow-card stack (#257).
        assertEquals("dash-42", logs[0].event.aggregateId)

        val payload = gson.fromJson(logs[0].event.eventPayload, OfferPayload::class.java)
        assertEquals("abc", payload.offerHash)
        assertEquals(7.50, payload.parsedOffer.payAmount!!, 0.001)
        assertEquals(4.2, payload.parsedOffer.distanceMiles!!, 0.001)
        assertNotNull(payload.evaluation)
        assertEquals(75.0, payload.evaluation!!.score, 0.001)
        assertEquals(AppEventType.OFFER_ACCEPTED, payload.outcome)
        assertEquals(900L, payload.presentedAt)
        assertEquals(1500L, payload.decidedAt)
    }

    @Test
    fun `OFFER_DECLINED carries rich payload`() {
        val offer = pendingOffer(hash = "xyz")
        val prev = appState(flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = offer))
        val next = appState(flow = FlowRegion(flow = Flow.Idle, pendingOffer = null))
        val click = Observation.Click(
            timestamp = 1600L,
            captureId = null,
            ruleId = "test.click",
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.ClickFields(intent = "decline_offer"),
        )

        val logs = logEvents(prev, next, click, AppEventType.OFFER_DECLINED)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, OfferPayload::class.java)
        assertEquals("xyz", payload.offerHash)
        assertEquals(AppEventType.OFFER_DECLINED, payload.outcome)
    }

    @Test
    fun `OFFER_TIMEOUT carries rich payload`() {
        val offer = pendingOffer(hash = "to1")
        val prev = appState(flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = offer))
        val next = appState(flow = FlowRegion(flow = Flow.Idle, pendingOffer = null))
        // No click — falls through to TIMEOUT
        val obs = screenObs(flow = Flow.Idle, timestamp = 1700L)

        val logs = logEvents(prev, next, obs, AppEventType.OFFER_TIMEOUT)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, OfferPayload::class.java)
        assertEquals("to1", payload.offerHash)
        assertEquals(AppEventType.OFFER_TIMEOUT, payload.outcome)
    }

    // =========================================================================
    // Tests — Pickup payloads
    // =========================================================================

    @Test
    fun `PICKUP_NAV_STARTED carries jobId taskId storeName phaseStartedAt`() {
        val region = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 500L),
        )
        val prev = appState(
            flow = FlowRegion(flow = Flow.OfferPresented),
            platforms = mapOf(Platform.DoorDash to region),
        )
        val next = appState(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
            platforms = mapOf(
                Platform.DoorDash to region.copy(
                    activeTask = task(
                        taskId = "T1",
                        jobId = "J1",
                        storeName = "Wendy's",
                        startedAt = 1100L,
                        deadlineMillis = 5000L,
                    ),
                ),
            ),
        )
        val obs = screenObs(flow = Flow.TaskPickupNavigation, timestamp = 1100L)

        val logs = logEvents(prev, next, obs, AppEventType.PICKUP_NAV_STARTED)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, PickupPayload::class.java)
        assertEquals("T1", payload.taskId)
        assertEquals("J1", payload.jobId)
        assertEquals("Wendy's", payload.storeName)
        assertEquals(1100L, payload.phaseStartedAt)
        assertEquals(5000L, payload.deadlineMillis)
        assertNull(payload.arrivedAt)
        assertNull(payload.confirmedAt)
    }

    @Test
    fun `PICKUP_ARRIVED carries arrivedAt + storeName`() {
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 500L),
            activeTask = task(taskId = "T2", jobId = "J2", arrivedAt = null),
        )
        val regionNext = regionPrev.copy(
            activeTask = task(taskId = "T2", jobId = "J2", arrivedAt = 1800L),
        )
        val prev = appState(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
            platforms = mapOf(Platform.DoorDash to regionPrev),
        )
        val next = appState(
            flow = FlowRegion(flow = Flow.TaskPickupArrived),
            platforms = mapOf(Platform.DoorDash to regionNext),
        )
        val obs = screenObs(flow = Flow.TaskPickupArrived, timestamp = 1800L)

        val logs = logEvents(prev, next, obs, AppEventType.PICKUP_ARRIVED)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, PickupPayload::class.java)
        assertEquals("T2", payload.taskId)
        assertEquals("Wendy's", payload.storeName)
        assertEquals(1800L, payload.arrivedAt)
    }

    @Test
    fun `PICKUP_CONFIRMED + DELIVERY_NAV_STARTED both carry rich payloads`() {
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 500L),
            activeTask = task(
                taskId = "T3",
                jobId = "J3",
                phase = TaskPhase.PICKUP,
                arrivedAt = 1800L,
            ),
        )
        val regionNext = regionPrev.copy(
            activeTask = task(
                taskId = "T4",
                jobId = "J3",
                phase = TaskPhase.DROPOFF,
                storeName = null,
                arrivedAt = null,
                startedAt = 2000L,
            ).copy(customerNameHash = "cust-abc"),
        )
        val prev = appState(
            flow = FlowRegion(flow = Flow.TaskPickupArrived),
            platforms = mapOf(Platform.DoorDash to regionPrev),
        )
        val next = appState(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(Platform.DoorDash to regionNext),
        )
        val obs = screenObs(flow = Flow.TaskDropoffNavigation, timestamp = 2000L)

        val effects = effectMap.diff(prev, next, obs).filterIsInstance<AppEffect.LogEvent>()
        val confirmed = effects.firstOrNull { it.event.eventType == AppEventType.PICKUP_CONFIRMED }
        val delivery = effects.firstOrNull { it.event.eventType == AppEventType.DELIVERY_NAV_STARTED }

        assertNotNull("PICKUP_CONFIRMED missing", confirmed)
        assertNotNull("DELIVERY_NAV_STARTED missing", delivery)

        val pickupPayload = gson.fromJson(confirmed!!.event.eventPayload, PickupPayload::class.java)
        assertEquals("T3", pickupPayload.taskId)
        assertEquals(2000L, pickupPayload.confirmedAt)

        val deliveryPayload = gson.fromJson(delivery!!.event.eventPayload, DeliveryPayload::class.java)
        assertEquals("T4", deliveryPayload.taskId)
        assertEquals("cust-abc", deliveryPayload.customerHash)
        assertEquals(2000L, deliveryPayload.phaseStartedAt)
    }

    // =========================================================================
    // Tests — Delivery payloads
    // =========================================================================

    @Test
    fun `DELIVERY_ARRIVED carries dropoff task details`() {
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 500L),
            activeTask = task(
                taskId = "T5",
                jobId = "J5",
                phase = TaskPhase.DROPOFF,
                arrivedAt = null,
            ).copy(customerNameHash = "cust-xyz"),
        )
        val regionNext = regionPrev.copy(
            activeTask = regionPrev.activeTask?.copy(arrivedAt = 2500L),
        )
        val prev = appState(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(Platform.DoorDash to regionPrev),
        )
        val next = appState(
            flow = FlowRegion(flow = Flow.TaskDropoffArrived),
            platforms = mapOf(Platform.DoorDash to regionNext),
        )
        val obs = screenObs(flow = Flow.TaskDropoffArrived, timestamp = 2500L)

        val logs = logEvents(prev, next, obs, AppEventType.DELIVERY_ARRIVED)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, DeliveryPayload::class.java)
        assertEquals("T5", payload.taskId)
        assertEquals("cust-xyz", payload.customerHash)
        assertEquals(2500L, payload.arrivedAt)
    }

    @Test
    fun `DELIVERY_COMPLETED carries totalPay parsedPay sessionEarnings`() {
        val parsedPay = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 4.50)),
            customerTips = listOf(ParsedPayItem("Wendy's", 3.00)),
        )
        val postFields = ParsedFields.PostTaskFields(
            totalPay = 7.50,
            parsedPay = parsedPay,
            sessionEarnings = 47.50,
        )
        val completedTask = task(
            taskId = "T6",
            jobId = "J6",
            phase = TaskPhase.DROPOFF,
            arrivedAt = 2500L,
        )
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 500L, runningEarnings = 47.50),
            recentTasks = listOf(completedTask),
            lastPostTaskFields = postFields,
        )
        val regionNext = regionPrev.copy()
        val prev = appState(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(Platform.DoorDash to regionPrev),
        )
        val next = appState(
            flow = FlowRegion(flow = Flow.Idle),
            platforms = mapOf(Platform.DoorDash to regionNext),
        )
        val obs = screenObs(flow = Flow.Idle, timestamp = 3000L)

        val logs = logEvents(prev, next, obs, AppEventType.DELIVERY_COMPLETED)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, DeliveryPayload::class.java)
        assertEquals("T6", payload.taskId)
        assertEquals(7.50, payload.totalPay!!, 0.001)
        assertNotNull(payload.parsedPay)
        assertEquals(7.50, payload.parsedPay!!.total, 0.001)
        assertEquals(47.50, payload.sessionEarningsAtCompletion!!, 0.001)
        assertEquals(3000L, payload.completedAt)
    }

    // =========================================================================
    // Tests — Session payloads
    // =========================================================================

    @Test
    fun `DASH_START carries sessionId platform source startScreen`() {
        val regionPrev = PlatformRegion(platform = Platform.DoorDash, mode = Mode.Offline)
        val regionNext = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session(sessionId = "s-new", startedAt = 1000L),
        )
        val prev = appState(platforms = mapOf(Platform.DoorDash to regionPrev))
        val next = appState(platforms = mapOf(Platform.DoorDash to regionNext))
        val obs = screenObs(modeHint = Mode.Online, timestamp = 1000L)

        val logs = logEvents(prev, next, obs, AppEventType.DASH_START)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, SessionStartPayload::class.java)
        assertEquals("s-new", payload.sessionId)
        assertEquals("DoorDash", payload.platform)
        assertEquals(1000L, payload.startedAt)
        assertTrue(payload.source == "interaction" || payload.source == "recovery")
    }

    @Test
    fun `DASH_STOP with summary screen carries totalEarnings and source=summary_screen`() {
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 1000L, runningEarnings = 50.0),
        )
        val regionNext = PlatformRegion(platform = Platform.DoorDash, mode = Mode.Offline)
        val prev = appState(platforms = mapOf(Platform.DoorDash to regionPrev))
        val next = appState(platforms = mapOf(Platform.DoorDash to regionNext))
        val summaryFields = ParsedFields.SessionEndedFields(
            totalEarnings = 50.0,
            sessionDurationMillis = 4 * 60 * 60 * 1000L,
            offersAccepted = 5,
            offersTotal = 8,
        )
        val obs = screenObs(
            flow = Flow.SessionEnded,
            modeHint = Mode.Offline,
            parsed = summaryFields,
            timestamp = 6000L,
        )

        val logs = logEvents(prev, next, obs, AppEventType.DASH_STOP)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, SessionStopPayload::class.java)
        assertEquals("summary_screen", payload.source)
        assertEquals(50.0, payload.totalEarnings!!, 0.001)
        assertEquals(5, payload.offersAccepted)
        assertEquals(8, payload.offersTotal)
        assertEquals(6000L, payload.endedAt)
    }

    @Test
    fun `DASH_STOP without summary screen carries source=early_offline`() {
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 1000L, runningEarnings = 30.0),
        )
        val regionNext = PlatformRegion(platform = Platform.DoorDash, mode = Mode.Offline)
        val prev = appState(platforms = mapOf(Platform.DoorDash to regionPrev))
        val next = appState(platforms = mapOf(Platform.DoorDash to regionNext))
        val obs = screenObs(modeHint = Mode.Offline, timestamp = 5500L)

        val logs = logEvents(prev, next, obs, AppEventType.DASH_STOP)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, SessionStopPayload::class.java)
        assertEquals("early_offline", payload.source)
        assertEquals(30.0, payload.totalEarnings!!, 0.001)
    }

    @Test
    fun `offline while session is graced does NOT finalize (deferred for the summary)`() {
        // idle_map offline: the stepper preserves the session under a grace
        // deadline (next.session still present). EffectMap must NOT finalize here —
        // the dash summary commonly shows AFTER this idle/offline screen.
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 1000L, runningEarnings = 30.0),
        )
        val regionNext = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Offline,
            session = Session("s1", startedAt = 1000L, runningEarnings = 30.0),
            sessionGraceDeadline = 15_000L,
        )
        val prev = appState(platforms = mapOf(Platform.DoorDash to regionPrev))
        val next = appState(platforms = mapOf(Platform.DoorDash to regionNext))
        val obs = screenObs(flow = Flow.Idle, modeHint = Mode.Offline, timestamp = 5000L)

        val effects = effectMap.diff(prev, next, obs)
        assertTrue(
            "no DASH_STOP while graced",
            effects.none { it is AppEffect.LogEvent && it.event.eventType == AppEventType.DASH_STOP },
        )
        assertTrue("no EndSession while graced", effects.none { it is AppEffect.EndSession })
        assertTrue("no StopOdometer while graced", effects.none { it is AppEffect.StopOdometer })
    }

    @Test
    fun `summary AFTER the idle screen (offline to offline) still finalizes with summary_screen`() {
        // Already Offline under grace; the dash_summary now arrives and the stepper
        // ends the session (next.session == null). The rich summary must attribute
        // to the just-ended dash even though the mode didn't change.
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Offline,
            session = Session("s1", startedAt = 1000L, runningEarnings = 50.0),
            sessionGraceDeadline = 15_000L,
        )
        val regionNext = PlatformRegion(platform = Platform.DoorDash, mode = Mode.Offline)
        val prev = appState(platforms = mapOf(Platform.DoorDash to regionPrev))
        val next = appState(platforms = mapOf(Platform.DoorDash to regionNext))
        val summaryFields = ParsedFields.SessionEndedFields(
            totalEarnings = 50.0,
            sessionDurationMillis = 4 * 60 * 60 * 1000L,
            offersAccepted = 5,
            offersTotal = 8,
        )
        val obs = screenObs(
            flow = Flow.SessionEnded,
            modeHint = Mode.Offline,
            parsed = summaryFields,
            timestamp = 6000L,
        )

        val logs = logEvents(prev, next, obs, AppEventType.DASH_STOP)
        assertEquals(1, logs.size)
        val payload = gson.fromJson(logs[0].event.eventPayload, SessionStopPayload::class.java)
        assertEquals("summary_screen", payload.source)
        assertEquals(50.0, payload.totalEarnings!!, 0.001)
        assertEquals(5, payload.offersAccepted)
    }

    @Test
    fun `DASH_PAUSED carries sessionId pausedAt remainingMillis`() {
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 1000L),
        )
        val regionNext = regionPrev.copy(mode = Mode.Paused)
        val prev = appState(platforms = mapOf(Platform.DoorDash to regionPrev))
        val next = appState(platforms = mapOf(Platform.DoorDash to regionNext))
        val pausedFields = ParsedFields.PausedFields(
            remainingText = "29:42",
            remainingMillis = 29 * 60 * 1000L,
        )
        val obs = screenObs(
            modeHint = Mode.Paused,
            parsed = pausedFields,
            timestamp = 4000L,
        )

        val logs = logEvents(prev, next, obs, AppEventType.DASH_PAUSED)
        assertEquals(1, logs.size)

        val payload = gson.fromJson(logs[0].event.eventPayload, SessionPausedPayload::class.java)
        assertEquals("s1", payload.sessionId)
        assertEquals(4000L, payload.pausedAt)
        assertEquals(29 * 60 * 1000L, payload.remainingMillis)
        assertEquals("29:42", payload.remainingText)
    }
}
