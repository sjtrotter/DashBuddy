package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality

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
        timestamp: Long = 1000L,
        effects: List<RequestedEffect> = emptyList(),
    ) = Observation.Notification(
        timestamp = timestamp,
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
        effects = effects,
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

    private val testEvaluation = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = 74.0,
        qualityLevel = OfferQuality.GOOD,
        payAmount = 7.50,
        fuelCostEstimate = 0.50,
        netPayAmount = 7.00,
        distanceMiles = 3.2,
        dollarsPerMile = 2.19,
        dollarsPerHour = 22.0,
        estimatedTimeMinutes = 19.0,
        itemCount = 1.0,
        merchantName = "Chipotle",
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
        logEvents().map { it.event.type }

    // =========================================================================
    // OFFER EFFECTS
    // =========================================================================

    @Test
    fun `offer presented emits Evaluate, Speak, and OFFER_RECEIVED log`() {
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
        // SpeakOffer (like the notification) waits for the evaluation to land — not on first sighting.
        assertTrue("No SpeakOffer before eval lands", effects.none { it is AppEffect.SpeakOffer })
        // OFFER_RECEIVED now emitted from EffectMap with a typed payload
        // (#257) — moved out of rule-declared `log` effects which never
        // persisted to the DB.
        assertTrue("Should emit OFFER_RECEIVED", effects.logEventTypes().contains(AppEventType.OFFER_RECEIVED))
        // Screenshot still handled by rule-declared effects
        assertTrue("No hardcoded CaptureScreenshot", effects.none { it is AppEffect.CaptureScreenshot })
        // Notification waits for the evaluation to land — not posted on first sighting.
        assertTrue("No PostOfferNotification before eval lands", effects.none { it is AppEffect.PostOfferNotification })
    }

    @Test
    fun `evaluation landing on the pending offer emits PostOfferNotification`() {
        // The async eval loopback attaches the evaluation to the SAME pending offer
        // (eval null → non-null). EffectMap surfaces it as a heads-up notification here,
        // rather than the EvaluateOffer handler firing the notification inline.
        val prev = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer, // evaluation == null
                activePlatform = Platform.DoorDash,
            ),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(evaluation = testEvaluation),
                activePlatform = Platform.DoorDash,
            ),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.OfferPresented))

        val posts = effects.filterIsInstance<AppEffect.PostOfferNotification>()
        assertEquals("Exactly one PostOfferNotification", 1, posts.size)
        assertEquals("Carries the landed evaluation", testEvaluation, posts[0].evaluation)
        // Keys the engine's delayed post so an offer-resolved cancel can abort it (#436).
        assertEquals("Carries the offer hash", "hash-123", posts[0].offerHash)
        // #578: also carries the rich offer card snapshot (same offerHash + the eval's score/action)
        // so the heads-up renders the mini offer card, not just a text line.
        assertEquals("Carries the offer card for the rich notification", "hash-123", posts[0].offer.offerHash)
        assertEquals("Card reflects the landed evaluation's action", "ACCEPT", posts[0].offer.evaluationAction)
        // Spoken read also fires on eval-landing, carrying the same evaluation.
        val spoken = effects.filterIsInstance<AppEffect.SpeakOffer>()
        assertEquals("Exactly one SpeakOffer", 1, spoken.size)
        assertEquals("Speaks the landed evaluation", testEvaluation, spoken[0].evaluation)
        // Offer didn't just appear — only its evaluation landed — so don't re-evaluate.
        assertTrue("Should not re-evaluate", effects.none { it is AppEffect.EvaluateOffer })
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
    fun `the decline-commit latch beats a later ACCEPT lastClickIntent (#594)`() {
        // The dasher committed the decline (confirm sheet) then hit Review offer→Accept: the latch is
        // set and lastClickIntent is the racing accept. The outcome must still be OFFER_DECLINED — the
        // latch wins over lastClickIntent — and the payload records the race for forensics.
        val prev = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(
                    declineCommittedAt = 900L,
                    lastClickIntent = "accept_offer",
                ),
            ),
        ))
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.Idle)))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))
        assertTrue(
            "a committed decline resolves DECLINED even when the last click was ACCEPT",
            effects.logEventTypes().contains(AppEventType.OFFER_DECLINED),
        )
        assertFalse(
            "the racing Accept must not log OFFER_ACCEPTED",
            effects.logEventTypes().contains(AppEventType.OFFER_ACCEPTED),
        )
        val declined = effects.logEvents().first { it.event.type == AppEventType.OFFER_DECLINED }
        val payload = declined.event.payload as OfferPayload
        assertTrue(
            "payload describes the accept-after-decline race",
            payload.description?.contains("decline stands") == true,
        )
    }

    @Test
    fun `an ACCEPT click after a committed decline shows the race bubble, not Offer Accepted (#594)`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(declineCommittedAt = 900L),
            ),
        ))
        // A click doesn't change flow by itself; the latch survives on the pending offer.
        val next = prev

        val effects = effectMap.diff(prev, next, clickObs(intent = "accept_offer"))
        assertTrue(
            "the accept race is surfaced honestly",
            effects.any { it is AppEffect.UpdateBubble && it.text == "Decline already submitted — Accept won't take" },
        )
        assertFalse(
            "the contradictory Offer Accepted bubble must not fire",
            effects.any { it is AppEffect.UpdateBubble && it.text == "Offer Accepted" },
        )
    }

    @Test
    fun `a resolved offer emits CancelOfferNotification for its hash`() {
        // The heads-up post is delayed ~750ms behind the screenshot settle —
        // resolving the offer inside that window must abort the post (#436).
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
        val cancels = effects.filterIsInstance<AppEffect.CancelOfferNotification>()
        assertEquals(1, cancels.size)
        assertEquals("hash-123", cancels[0].offerHash)
    }

    @Test
    fun `an offer replaced by a new offer dismisses the old heads-up (#457)`() {
        // Separate-id heads-up (#457): when a new offer replaces the old one, the OLD banner must be
        // dismissed now — else it lingers until the new offer's async eval lands and a tap in that
        // window would resolve against the NEW offer.
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer.copy(offerHash = "hash-456")),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.OfferPresented))
        val cancels = effects.filterIsInstance<AppEffect.CancelOfferNotification>()
        assertEquals("the old offer's heads-up is dismissed on replace", 1, cancels.size)
        assertEquals("hash-123", cancels[0].offerHash)
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
    fun `click accept during offer emits an instant ack, not an outcome claim (#601)`() {
        // #601: a click is a tap acknowledgement, not an outcome — the card that says what
        // actually happened fires from the resolution pop (see the #601 tests below), off the
        // same outcome value logged to the ledger.
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer),
        ))
        // Next state doesn't change flow (click doesn't change flow by itself)
        val next = prev

        val effects = effectMap.diff(prev, next, clickObs(intent = "accept_offer"))
        assertTrue("the tap acks instantly", effects.any {
            it is AppEffect.UpdateBubble && it.text == "Accepting…"
        })
        assertFalse("a click must never claim the outcome (#601)", effects.any {
            it is AppEffect.UpdateBubble && it.text == "Offer Accepted"
        })
    }

    @Test
    fun `click decline during offer emits an instant ack, not an outcome claim (#601)`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer),
        ))
        val next = prev

        val effects = effectMap.diff(prev, next, clickObs(intent = "decline_offer"))
        assertTrue("the tap acks instantly", effects.any {
            it is AppEffect.UpdateBubble && it.text == "Declining…"
        })
        assertFalse("a click must never claim the outcome (#601)", effects.any {
            it is AppEffect.UpdateBubble && it.text == "Offer Declined"
        })
    }

    // =========================================================================
    // #601 — outcome cards derive from the committed outcome (SSOT)
    // =========================================================================

    // ONE function used by every outcome-card assertion below, so a new test can't drift by
    // hardcoding a fresh string per outcome. Deliberately reimplements (rather than imports)
    // production's private EffectMap.outcomeCardText table, so a divergence between the two
    // still fails a test instead of being tautologically hidden.
    private fun expectedOutcomeCard(outcome: AppEventType, replaced: Boolean = false): String {
        val base = when (outcome) {
            AppEventType.OFFER_ACCEPTED -> "Offer Accepted"
            AppEventType.OFFER_DECLINED -> "Offer Declined"
            AppEventType.OFFER_TIMEOUT -> "Offer Timed Out!"
            else -> error("not an offer outcome: $outcome")
        }
        return if (replaced) "$base (offer replaced)" else base
    }

    @Test
    fun `accept click acks instantly, then the resolution pop shows the matching outcome card (#601)`() {
        // Step 1 — click: instant ack, no outcome claim yet.
        val prevAtClick = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer),
        ))
        val clickEffects = effectMap.diff(prevAtClick, prevAtClick, clickObs(intent = "accept_offer"))
        assertTrue("instant ack at click time", clickEffects.any {
            it is AppEffect.UpdateBubble && it.text == "Accepting…"
        })

        // Step 2 — pop: the offer resolves (screen transition) carrying the click intent the
        // state machine already recorded from step 1. The card must equal
        // expectedOutcomeCard(loggedOutcome) — card==ledger by construction, not two hand-kept
        // strings that happen to agree.
        val prevAtPop = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(lastClickIntent = OfferIntent.ACCEPT),
            ),
        ))
        val nextAtPop = AppState(regions = Regions(flow = FlowRegion(flow = Flow.TaskPickupNavigation)))
        val popEffects = effectMap.diff(prevAtPop, nextAtPop, screenObs(flow = Flow.TaskPickupNavigation))

        val outcome = popEffects.logEvents().first { it.event.type == AppEventType.OFFER_ACCEPTED }.event.type
        assertTrue(
            "the pop card equals expectedOutcomeCard(loggedOutcome)",
            popEffects.any { it is AppEffect.UpdateBubble && it.text == expectedOutcomeCard(outcome) },
        )
    }

    @Test
    fun `decline-latch race (#594)- click shows the race warning not an ack, pop logs and shows Declined (#601)`() {
        // The dasher's decline already committed server-side; a later Accept click races it.
        val prevAtClick = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(declineCommittedAt = 900L),
            ),
        ))
        val clickEffects = effectMap.diff(prevAtClick, prevAtClick, clickObs(intent = "accept_offer"))
        assertTrue("the race warning fires instead of an ack", clickEffects.any {
            it is AppEffect.UpdateBubble && it.text == "Decline already submitted — Accept won't take"
        })
        assertFalse("no Accepting… ack when the decline already committed", clickEffects.any {
            it is AppEffect.UpdateBubble && it.text == "Accepting…"
        })

        // Pop: the latch wins — OFFER_DECLINED is what's logged and shown, regardless of the
        // racing Accept click recorded as lastClickIntent.
        val prevAtPop = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(
                    declineCommittedAt = 900L,
                    lastClickIntent = OfferIntent.ACCEPT,
                ),
            ),
        ))
        val nextAtPop = AppState(regions = Regions(flow = FlowRegion(flow = Flow.Idle)))
        val popEffects = effectMap.diff(prevAtPop, nextAtPop, screenObs(flow = Flow.Idle))

        assertTrue(popEffects.logEventTypes().contains(AppEventType.OFFER_DECLINED))
        assertTrue(
            "card==ledger: OFFER_DECLINED shows the Declined card even though the last click was Accept",
            popEffects.any {
                it is AppEffect.UpdateBubble && it.text == expectedOutcomeCard(AppEventType.OFFER_DECLINED)
            },
        )
    }

    @Test
    fun `no click, timeout pop shows only the outcome card — no ack ever fired (#601)`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer),
        ))
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.Idle)))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))

        assertTrue(effects.logEventTypes().contains(AppEventType.OFFER_TIMEOUT))
        val bubbles = effects.filterIsInstance<AppEffect.UpdateBubble>()
        assertEquals("exactly one card — no click means no ack ever fired", 1, bubbles.size)
        assertEquals(expectedOutcomeCard(AppEventType.OFFER_TIMEOUT), bubbles[0].text)
    }

    @Test
    fun `a replaced offer with a stored ACCEPT intent surfaces the OLD offer's outcome card, suffixed (#601)`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(
                flow = Flow.OfferPresented,
                pendingOffer = testPendingOffer.copy(lastClickIntent = OfferIntent.ACCEPT),
            ),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer.copy(offerHash = "hash-456")),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.OfferPresented))

        assertTrue(
            "the old offer's OFFER_ACCEPTED is logged even though it was silently replaced",
            effects.logEventTypes().contains(AppEventType.OFFER_ACCEPTED),
        )
        assertTrue(
            "the card is faithful to the ledger — suffixed so it reads as the OLD offer's fate",
            effects.any {
                it is AppEffect.UpdateBubble &&
                    it.text == expectedOutcomeCard(AppEventType.OFFER_ACCEPTED, replaced = true)
            },
        )
    }

    @Test
    fun `a replaced offer with no click intent logs and shows OFFER_TIMEOUT, suffixed — no 4th outcome string (#601 vet amdt 1)`() {
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer.copy(offerHash = "hash-456")),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.OfferPresented))

        assertTrue(effects.logEventTypes().contains(AppEventType.OFFER_TIMEOUT))
        assertTrue(
            "faithful to the ledger's OFFER_TIMEOUT — no invented 4th outcome string",
            effects.any { it is AppEffect.UpdateBubble && it.text == "Offer Timed Out! (offer replaced)" },
        )
    }

    // =========================================================================
    // MODE TRANSITION EFFECTS
    // =========================================================================

    @Test
    fun `session start emits DASH_START, StartOdometer, StartSession`() {
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
        assertTrue("Should emit StartSession", effects.any { it is AppEffect.StartSession })
    }

    @Test
    fun `session end emits DASH_STOP, StopOdometer, EndSession`() {
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
        assertTrue("Should emit EndSession", effects.any { it is AppEffect.EndSession })
        // #606: the dash_summary RULE effect (deduped + throttled, fires on
        // recognition) already owns the DashSummary screenshot. EffectMap's
        // own SESSION_ENDED commit used to ALSO fire one — effectKey null,
        // so it bypassed both effects_fired and the throttle — producing two
        // "DashSummary - <earnings>" captures ~2.5s apart (the
        // AUTHORITATIVE_GRACE_MS window) for one session end.
        assertTrue(
            "Should NOT emit CaptureScreenshot — the dash_summary rule owns it (#606)",
            effects.none { it is AppEffect.CaptureScreenshot },
        )
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
    // PAUSE-FLAP EDGE ELIMINATION (#605)
    // =========================================================================

    /**
     * The 06-28 receipt: DoorDash's pause sheet is a modal on top of the just-completed
     * delivery summary, so accessibility frames alternate `dash_paused` ↔
     * `delivery_summary_collapsed` (modeHint online) for a few seconds. Before #605 each
     * online→paused edge re-minted `DASH_PAUSED` + "Dash Paused!" and each paused→online edge
     * fired a spurious "Session resumed (grace)" card while the dasher was still paused.
     *
     * Driven through the REAL state machine (the fix is edge-flap elimination in the stepper, so
     * hand-built diff pairs can't exercise it): the whole flap must collapse to exactly ONE
     * DASH_PAUSED, ONE "Dash Paused!", and ZERO resume cards.
     */
    @Test
    fun `#605 the 06-28 pause-flap collapses to one DASH_PAUSED and no spurious resume card`() {
        val machine = StateMachine(
            flowStepper = FlowRegionStepper(),
            platformStepper = PlatformRegionStepper(),
            crossPlatformStepper = CrossPlatformRegionStepper(),
            transitionPolicy = TransitionPolicy(),
            effectMap = EffectMap(),
        )
        var state = AppState()
        val all = mutableListOf<AppEffect>()
        var ts = 1_000_000L
        fun feed(obs: Observation) {
            val t = machine.step(state, obs)
            state = t.newState
            all += t.effects
        }
        fun at() = (ts + 1000L).also { ts = it }

        // A live delivery (online) right before the pause.
        feed(screenObs(flow = Flow.OfferPresented, parsed = testOfferFields, timestamp = at()))
        // The ONE real pause.
        feed(screenObs(
            modeHint = Mode.Paused,
            parsed = ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000),
            timestamp = at(),
        ))
        // The flap — receipt (delivery_summary, online) ↔ pause, all inside the 8s grace.
        repeat(2) {
            feed(screenObs(
                flow = Flow.PostTask, modeHint = Mode.Online,
                parsed = ParsedFields.PostTaskFields(totalPay = 0.0), timestamp = at(),
            ))
            feed(screenObs(
                modeHint = Mode.Paused,
                parsed = ParsedFields.PausedFields(remainingText = "4:00", remainingMillis = 240_000),
                timestamp = at(),
            ))
        }

        assertEquals(
            "exactly one DASH_PAUSED across the whole flap",
            1, all.logEventTypes().count { it == AppEventType.DASH_PAUSED },
        )
        assertEquals(
            "exactly one 'Dash Paused!' card",
            1, all.count { it is AppEffect.UpdateBubble && it.text.contains("Dash Paused!") },
        )
        assertFalse(
            "no spurious 'Session resumed (grace)' card while the pause sheet is up",
            all.any { it is AppEffect.UpdateBubble && it.text.contains("resumed") },
        )
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
    fun `DELIVERY_COMPLETED does not re-fire a prior job's already-completed task on PostTask exit (#518)`() {
        val (platform, base) = stateWithPlatform()
        // A stale PRIOR-job dropoff that already completed (sits in recentTasks); the active job is NEW.
        val stale = Task(
            taskId = "task-prior-33", jobId = "job-prior", phase = TaskPhase.DROPOFF,
            customerNameHash = "3c53c662", startedAt = 100L, completedAt = 200L,
        )
        val region = base.copy(
            activeJob = Job("job-new", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 1000L),
            activeTask = null,
            recentTasks = listOf(stale),
        )
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.PostTask), platforms = mapOf(platform to region)))
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.TaskPickupArrived), platforms = mapOf(platform to region)))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskPickupArrived))
        assertFalse(
            "a PostTask exit must not re-complete a prior job's stale task (cross-job leak)",
            effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
    }

    @Test
    fun `DELIVERY_COMPLETED fires for the task just retired on this PostTask exit (#518 keeps normal)`() {
        val (platform, base) = stateWithPlatform()
        val job = Job("job-new", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 1000L)
        val active = Task(
            taskId = "task-36", jobId = "job-new", phase = TaskPhase.DROPOFF,
            customerNameHash = "abc123", startedAt = 100L, completedAt = null,
        )
        // prev: PostTask, the delivered task is ACTIVE (NOT yet in recentTasks).
        val prevRegion = base.copy(activeJob = job, activeTask = active, recentTasks = emptyList())
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.PostTask), platforms = mapOf(platform to prevRegion)))
        // next: PostTask exit; the task is freshly retired into recentTasks (was NOT in prev.recentTasks).
        val nextRegion = prevRegion.copy(activeTask = null, recentTasks = listOf(active.copy(completedAt = 2000L)))
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.TaskDropoffArrived), platforms = mapOf(platform to nextRegion)))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskDropoffArrived))
        assertTrue(
            "a genuinely just-retired task still completes",
            effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
    }

    @Test
    fun `session end does NOT mint a completion for an undelivered active drop (#596 amdt-5, kills M6)`() {
        // endSession force-stamps completedAt on whatever task is active. The close-out sweep's
        // qualification rule (already-completed-before-this-step OR active-under-TASK_RETIRE)
        // must exclude exactly that force-stamp — otherwise ending the dash mid-delivery
        // fabricates a DELIVERY_COMPLETED for an order still in the car (#518/#564 phantom class).
        val (platform, base) = stateWithPlatform()
        val job = Job("job-open", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 1000L)
        val active = Task(
            taskId = "task-undelivered", jobId = "job-open", phase = TaskPhase.DROPOFF,
            customerNameHash = "abc123", startedAt = 100L, arrivedAt = null, completedAt = null,
        )
        // prev: open job, active identity-carrying dropoff, NO retire pending.
        val prevRegion = base.copy(activeJob = job, activeTask = active, recentTasks = emptyList())
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.TaskDropoffNavigation), platforms = mapOf(platform to prevRegion)))
        // next: endSession applied — session/job/task null, the drop force-stamped into recentTasks.
        val nextRegion = prevRegion.copy(
            session = null, activeJob = null, activeTask = null,
            recentTasks = listOf(active.copy(completedAt = 5_000L)),
        )
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.Idle), platforms = mapOf(platform to nextRegion)))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))
        assertFalse(
            "ending the session mid-delivery must not fabricate a completion",
            effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
    }

    @Test
    fun `PostTask exit with nothing in flight does NOT re-fire a stale completed task (#596 amdt-2, kills M5)`() {
        // The job was already closed by T1 on a PRIOR step (its completion minted then). A later
        // PostTask exit with no job, no active task, and no retire pending must not let the
        // job-less fallback grab the stale completed recentTask and re-emit its key.
        val (platform, base) = stateWithPlatform()
        val stale = Task(
            taskId = "task-done-earlier", jobId = "job-closed", phase = TaskPhase.DROPOFF,
            customerNameHash = "abc123", startedAt = 100L, arrivedAt = 150L, completedAt = 200L,
        )
        val region = base.copy(activeJob = null, activeTask = null, recentTasks = listOf(stale), pendingDestructive = null)
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.PostTask), platforms = mapOf(platform to region)))
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.Idle), platforms = mapOf(platform to region)))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))
        assertFalse(
            "a job-less PostTask exit with nothing being completed must not re-fire a stale completion",
            effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
    }

    @Test
    fun `DELIVERY_COMPLETED does not fire when the retired task is a PICKUP that never reached dropoff (#564)`() {
        val (platform, base) = stateWithPlatform()
        val job = Job("job-new", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 1000L)
        // 06-21 seq98: a mid-stack Burger King add-on offer grace-retires an in-flight PICKUP task
        // (Smoky Mo's …32, never picked up), and a transient/misrecognized delivery-summary frame
        // drives this PostTask exit. The retired task is a PICKUP — it never reached the dropoff —
        // so it must NOT fabricate a $0, customer-less "completion" of a store never delivered.
        val pickup = Task(
            taskId = "task-32", jobId = "job-new", phase = TaskPhase.PICKUP,
            storeName = "Smoky Mo's BBQ", startedAt = 100L, completedAt = null,
        )
        val prevRegion = base.copy(activeJob = job, activeTask = pickup, recentTasks = emptyList())
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.PostTask), platforms = mapOf(platform to prevRegion)))
        // PostTask exit; the PICKUP task is freshly retired into recentTasks (the grace-retire commit).
        val nextRegion = prevRegion.copy(activeTask = null, recentTasks = listOf(pickup.copy(completedAt = 2000L)))
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.TaskPickupNavigation), platforms = mapOf(platform to nextRegion)))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskPickupNavigation))
        assertFalse(
            "a retired PICKUP task that never reached dropoff must not complete (#564 add-on phantom)",
            effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
    }

    @Test
    fun `stacked pickup transition fires PICKUP_NAV_STARTED and ResumeOdometer for the new task`() {
        // Costa Pacifica pickup completed (moved to recentTasks) and a new
        // Chili's pickup task minted by the stepper. The diff-task gate must
        // fire even though prevTask is non-null — it now keys on "taskId changed".
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val costaPacifica = Task(
            taskId = "task-A", jobId = "job-1", phase = TaskPhase.PICKUP,
            storeName = "Costa Pacifica", startedAt = 800L, arrivedAt = 850L, completedAt = 1_000L,
        )
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupArrived),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session, activeTask = costaPacifica,
            )),
        ))

        val chilis = Task(
            taskId = "task-B", jobId = "job-1", phase = TaskPhase.PICKUP,
            storeName = "Chili's Grill & Bar", startedAt = 1_001L,
        )
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session,
                activeTask = chilis, recentTasks = listOf(costaPacifica),
            )),
        ))

        val effects = effectMap.diff(
            prev, next,
            screenObs(
                flow = Flow.TaskPickupNavigation,
                parsed = ParsedFields.TaskFields(
                    storeName = "Chili's Grill & Bar",
                    phase = TaskPhase.PICKUP,
                    subFlow = TaskSubFlow.NAVIGATION,
                ),
            ),
        )

        assertTrue(
            "Stacked transition should emit PICKUP_NAV_STARTED for the new task",
            effects.logEventTypes().contains(AppEventType.PICKUP_NAV_STARTED),
        )
        assertTrue(
            "Stacked transition should emit ResumeOdometer for the inter-store leg",
            effects.any { it is AppEffect.ResumeOdometer },
        )
        assertTrue(
            "Bubble should announce the new store",
            effects.any { it is AppEffect.UpdateBubble && it.text.contains("Chili") },
        )
    }

    @Test
    fun `dropoff to PostTask (activeTask null) emits DELIVERY_CONFIRMED`() {
        // The platform routed dasher into PostTask; the dropoff is done.
        // activeTask transitions to null (stepper moved it to recentTasks).
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val dropoffTask = Task(
            taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF,
            storeName = "Chipotle", startedAt = 900L, arrivedAt = null,
        )
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session, activeTask = dropoffTask,
            )),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session,
                activeTask = null, recentTasks = listOf(dropoffTask.copy(completedAt = 1000L)),
            )),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.PostTask, parsed = ParsedFields.PostTaskFields(totalPay = 7.50)))

        assertTrue(
            "Should emit DELIVERY_CONFIRMED on activeTask cleared from dropoff",
            effects.logEventTypes().contains(AppEventType.DELIVERY_CONFIRMED),
        )
    }

    @Test
    fun `dropoff to next-pickup (different taskId) emits DELIVERY_CONFIRMED`() {
        // Stacked next-offer accepted before PostTask shows: prev dropoff
        // is no longer active; the next pickup task takes over.
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val prevDropoff = Task(
            taskId = "task-A", jobId = "job-1", phase = TaskPhase.DROPOFF,
            storeName = "Chipotle", startedAt = 900L,
        )
        val nextPickup = Task(
            taskId = "task-B", jobId = "job-2", phase = TaskPhase.PICKUP,
            storeName = "Wendy's", startedAt = 1100L,
        )
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffArrived),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session, activeTask = prevDropoff,
            )),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupNavigation),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session, activeTask = nextPickup,
            )),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskPickupNavigation, parsed = ParsedFields.TaskFields(storeName = "Wendy's", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION)))

        assertTrue(
            "Should emit DELIVERY_CONFIRMED for the leaving dropoff",
            effects.logEventTypes().contains(AppEventType.DELIVERY_CONFIRMED),
        )
    }

    @Test
    fun `same dropoff task (in-place update) does NOT emit DELIVERY_CONFIRMED`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val task = Task(
            taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF,
            storeName = "Chipotle", startedAt = 900L,
        )
        val prevRegion = PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = task)
        val nextRegion = PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = task.copy(storeName = "Updated Chipotle"))
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.TaskDropoffNavigation), platforms = mapOf(platform to prevRegion)))
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.TaskDropoffNavigation), platforms = mapOf(platform to nextRegion)))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskDropoffNavigation, parsed = ParsedFields.TaskFields(storeName = "Updated Chipotle", phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION)))

        assertTrue(
            "Should NOT emit DELIVERY_CONFIRMED for same-task update",
            !effects.logEventTypes().contains(AppEventType.DELIVERY_CONFIRMED),
        )
    }

    @Test
    fun `pickup to dropoff emits PICKUP_CONFIRMED and DELIVERY_NAV_STARTED`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val pickupTask = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP, storeName = "Chipotle", arrivedAt = 950L, startedAt = 900L)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupArrived),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = pickupTask)),
        ))

        val dropoffTask = Task(taskId = "task-2", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", startedAt = 1000L)
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            // #526 sweep design: the displaced pickup is confirmed from the job's lineage in
            // recentTasks (the stepper displaces it there in the same step).
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session,
                activeTask = dropoffTask, recentTasks = listOf(pickupTask.copy(completedAt = 1000L)))),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.TaskDropoffNavigation, parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION)))

        assertTrue("Should emit PICKUP_CONFIRMED", effects.logEventTypes().contains(AppEventType.PICKUP_CONFIRMED))
        assertTrue("Should emit DELIVERY_NAV_STARTED", effects.logEventTypes().contains(AppEventType.DELIVERY_NAV_STARTED))
        assertTrue("Should emit ResumeOdometer", effects.any { it is AppEffect.ResumeOdometer })
        assertTrue(
            "Should NOT emit DELIVERY_CONFIRMED on a pickup-leaving transition",
            !effects.logEventTypes().contains(AppEventType.DELIVERY_CONFIRMED),
        )
        assertTrue(
            "a non-shop pickup feeds no shop-rate sample (#556)",
            effects.none { it is AppEffect.RecordShopRate },
        )
        // #568: the dropoff bubble is store-flavored (the resolved store's customer), never a hash.
        val bubble = effects.effectsOfType<AppEffect.UpdateBubble>().first { it.text.startsWith("Heading to") }
        assertEquals("Heading to Chipotle's customer", bubble.text)
        assertEquals(ChatPersona.Customer("Chipotle's customer"), bubble.persona)
    }

    @Test
    fun `a completed SHOP pickup emits RecordShopRate with measured items and duration (#556)`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val arrivedAt = 100_000L
        val confirmAt = arrivedAt + 30 * 60_000L  // 30 min in-store → 24 items = 0.8/min
        val shopPickup = Task(
            taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP, storeName = "H-E-B",
            activity = PickupActivity.SHOPPING, itemsShopped = 24, arrivedAt = arrivedAt, startedAt = 900L,
        )
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskPickupArrived),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = shopPickup)),
        ))
        val dropoff = Task(taskId = "task-2", jobId = "job-1", phase = TaskPhase.DROPOFF, startedAt = confirmAt)
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            // #526 sweep design: the displaced shop pickup is confirmed from recentTasks; the
            // shop-rate window is arrived→completedAt (== confirmAt here).
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session,
                activeTask = dropoff, recentTasks = listOf(shopPickup.copy(completedAt = confirmAt)))),
        ))

        val effects = effectMap.diff(prev, next, screenObs(
            flow = Flow.TaskDropoffNavigation, timestamp = confirmAt,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION),
        ))

        val rec = effects.filterIsInstance<AppEffect.RecordShopRate>().single()
        assertEquals(24, rec.itemsShopped)
        assertEquals(30 * 60_000L, rec.shopDurationMs)
        assertEquals("task-1", rec.taskId)
        assertEquals("the sample is idempotent per pickup task", "shop_rate:task-1", rec.effectKey)
    }

    @Test
    fun `stacked leg-2 dropoff (dropoff to a new dropoff) mints DELIVERY_NAV_STARTED, ResumeOdometer, and a Heading-to bubble (#603)`() {
        // A multi-drop stack: leg-1's drop is retired and leg-2's drop becomes active — a
        // different taskId, freshly minted on THIS frame (startedAt == obs.timestamp). Both
        // sides are DROPOFF, so the pickup→dropoff branch never sees it; before #603 leg-2 was
        // a silent drop (no nav event, no odometer resume, no bubble).
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val leg1 = Task(
            taskId = "drop-A", jobId = "job-1", phase = TaskPhase.DROPOFF,
            storeName = "Panera Bread", startedAt = 800L,
        )
        val leg2 = Task(
            taskId = "drop-B", jobId = "job-1", phase = TaskPhase.DROPOFF,
            storeName = "Panera Bread", startedAt = 1000L,
        )
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffArrived),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = leg1)),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = leg2)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(
            flow = Flow.TaskDropoffNavigation, timestamp = 1000L,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION),
        ))

        assertTrue("leg-2 must mint DELIVERY_NAV_STARTED", effects.logEventTypes().contains(AppEventType.DELIVERY_NAV_STARTED))
        assertTrue("leg-2 must resume the odometer for its drive", effects.any { it is AppEffect.ResumeOdometer })
        assertTrue(
            "leg-1's drop is confirmed as the active task moves on",
            effects.logEventTypes().contains(AppEventType.DELIVERY_CONFIRMED),
        )
        // #568: the leg-2 bubble is store-flavored (never the raw hash).
        val bubble = effects.effectsOfType<AppEffect.UpdateBubble>().first { it.text.startsWith("Heading to") }
        assertEquals("Heading to Panera Bread's customer", bubble.text)
        assertEquals(ChatPersona.Customer("Panera Bread's customer"), bubble.persona)
    }

    @Test
    fun `a RESUMED dropoff (startedAt predates the frame) does NOT re-mint DELIVERY_NAV_STARTED (#603)`() {
        // The new-leg branch is guarded on startedAt == obs.timestamp, so a replay / re-sight of
        // an already-running drop (which keeps its original startedAt) can't re-fire the nav trio.
        // Here a null active task is replaced by a dropoff whose startedAt predates this frame — a
        // resume, not a fresh leg.
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val resumed = Task(
            taskId = "drop-B", jobId = "job-1", phase = TaskPhase.DROPOFF,
            storeName = "Panera Bread", startedAt = 500L,
        )
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = null)),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeTask = resumed)),
        ))

        val effects = effectMap.diff(prev, next, screenObs(
            flow = Flow.TaskDropoffNavigation, timestamp = 1000L,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION),
        ))

        assertFalse(
            "a resumed (not freshly-minted) drop must not re-fire the nav trio",
            effects.logEventTypes().contains(AppEventType.DELIVERY_NAV_STARTED),
        )
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
        // #653: identity-bearing (a real delivered drop) so the PostTask-exit firewall admits it.
        val completedTask = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", customerNameHash = "cust-1", startedAt = 900L, completedAt = 950L)
        // #596: a real receipt exit has the job still ACTIVE going in (it closes on this same step),
        // so completedJobId is non-null → the SCOPED fallback finds the task. The unscoped (job-less)
        // fallback is now gated when there's genuinely nothing to complete (job already closed, no
        // active task, no retire pending) — see the amdt-2 unscoped-fallback gate.
        val job = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeJob = job, recentTasks = listOf(completedTask))),
        ))

        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.Idle),
            platforms = mapOf(platform to PlatformRegion(platform, mode = Mode.Online, session = session, activeJob = job, recentTasks = listOf(completedTask))),
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

    @Test
    fun `leaving PostTask with no recent task does NOT emit DELIVERY_COMPLETED (duplicate-skip)`() {
        // Reproduces the per-platform iteration bug: a platform that didn't own
        // the delivery still enters this code path on PostTask exit, but has no
        // recentTasks. The deliveryCompletedPayload "unknown" fallback fires
        // unless we skip on null. This test asserts the skip works.
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session,
                recentTasks = emptyList(), activeJob = null,
            )),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.Idle),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session,
                recentTasks = emptyList(), activeJob = null,
            )),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))
        assertTrue(
            "Should NOT emit DELIVERY_COMPLETED when there's no completed task to attribute",
            !effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
    }

    @Test
    fun `leaving PostTask with the task still in retire grace emits DELIVERY_COMPLETED for it (#431)`() {
        // #431 pt 2: the delivered task stays ACTIVE through the receipt grace,
        // so on PostTask exit recentTasks does NOT yet contain it. The event
        // must name the still-active task, stamped at the receipt's appearance.
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        // #653: identity-bearing (a real delivered drop) so the PostTask-exit firewall admits it.
        val active = Task(taskId = "task-9", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", customerNameHash = "cust-9", startedAt = 900L)
        val pend = PendingDestructive(
            kind = DestructiveKind.TASK_RETIRE, since = 950L, deadline = 3_450L, authoritative = true,
        )
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session,
                activeTask = active, pendingDestructive = pend,
            )),
        ))
        val next = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.Idle),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session,
                activeTask = active, pendingDestructive = pend,
            )),
        ))

        val effects = effectMap.diff(prev, next, screenObs(flow = Flow.Idle))
        assertTrue(
            "DELIVERY_COMPLETED must fire for the deferred (still-active) task",
            effects.logEventTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
    }

    // =========================================================================
    // POST-TASK ANNOUNCEMENT (single-bubble, per-task idempotency)
    // =========================================================================

    @Test
    fun `deferred receipt announces exactly once across collapsed and expanded frames (#431)`() {
        // Under the receipt grace the completing task is still ACTIVE on every
        // PostTask frame. Frame 1 announces; the stepper stamps the gate with
        // the SAME id the diff resolved, so the expanded re-observation cannot
        // double-fire (the old recentTasks-only stamp lagged by one frame).
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val active = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", startedAt = 900L)
        fun regionWith(announced: String?) = PlatformRegion(
            platform, mode = Mode.Online, session = session,
            activeTask = active,
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE, since = 950L, deadline = 3_450L, authoritative = true,
            ),
            lastAnnouncedPostTaskTaskId = announced,
        )
        val beforeFirst = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to regionWith(announced = null)),
        ))
        val afterFirst = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to regionWith(announced = "task-1")),
        ))

        val first = effectMap.diff(beforeFirst, afterFirst, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50, parsedPay = null),
        ))
        assertEquals(
            "first sighting announces",
            1, first.filterIsInstance<AppEffect.UpdateBubble>().count { it.text.contains("Saved") },
        )

        val second = effectMap.diff(afterFirst, afterFirst, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50, parsedPay = null),
        ))
        assertTrue(
            "the expanded re-observation must NOT re-announce",
            second.filterIsInstance<AppEffect.UpdateBubble>().none { it.text.contains("Saved") },
        )
    }

    @Test
    fun `collapsed-only PostTask emits single minimal Saved bubble`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val task = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", startedAt = 900L, completedAt = 1000L)
        val prev = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.PostTask),
            platforms = mapOf(platform to PlatformRegion(
                platform, mode = Mode.Online, session = session, recentTasks = listOf(task),
                lastAnnouncedPostTaskTaskId = null,
            )),
        ))
        val next = prev
        val effects = effectMap.diff(prev, next, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50, parsedPay = null),
        ))
        val bubbles = effects.filterIsInstance<AppEffect.UpdateBubble>()
        assertEquals(1, bubbles.size)
        assertTrue("Bubble should contain Saved", bubbles[0].text.contains("Saved"))
        assertTrue("Bubble should contain totalPay value", bubbles[0].text.contains("7.50"))
    }

    @Test
    fun `PostTask with same taskId already announced does NOT re-emit bubble`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val task = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", startedAt = 900L, completedAt = 1000L)
        // prev region already has lastAnnouncedPostTaskTaskId = task-1
        val region = PlatformRegion(
            platform, mode = Mode.Online, session = session, recentTasks = listOf(task),
            lastAnnouncedPostTaskTaskId = "task-1",
        )
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.PostTask), platforms = mapOf(platform to region)))
        val next = prev
        val effects = effectMap.diff(prev, next, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50, parsedPay = null),
        ))
        assertTrue(
            "Should NOT emit a second bubble for the same taskId",
            effects.filterIsInstance<AppEffect.UpdateBubble>().isEmpty(),
        )
    }

    @Test
    fun `expanded PostTask on first sighting emits single full receipt bubble`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val task = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "Chipotle", startedAt = 900L, completedAt = 1000L)
        val region = PlatformRegion(
            platform, mode = Mode.Online, session = session, recentTasks = listOf(task),
            lastAnnouncedPostTaskTaskId = null,
        )
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.PostTask), platforms = mapOf(platform to region)))
        val next = prev
        val parsedPay = cloud.trotter.dashbuddy.domain.model.pay.ParsedPay(
            appPayComponents = listOf(cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem("Base", 4.50)),
            customerTips = listOf(cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem("Chipotle", 3.00)),
        )
        val effects = effectMap.diff(prev, next, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 7.50, parsedPay = parsedPay),
        ))
        val bubbles = effects.filterIsInstance<AppEffect.UpdateBubble>()
        assertEquals(1, bubbles.size)
        assertTrue("Bubble should contain breakdown line", bubbles[0].text.contains("Tip"))
        assertTrue("Bubble should contain store name", bubbles[0].text.contains("Chipotle"))
    }

    @Test
    fun `expanded PostTask with a bare-number tip label renders as Store number - #607`() {
        val (platform, _) = stateWithPlatform()
        val session = Session("sess-1", startedAt = 100L)
        val task = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = "618", startedAt = 900L, completedAt = 1000L)
        val region = PlatformRegion(
            platform, mode = Mode.Online, session = session, recentTasks = listOf(task),
            lastAnnouncedPostTaskTaskId = null,
        )
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.PostTask), platforms = mapOf(platform to region)))
        val next = prev
        val parsedPay = cloud.trotter.dashbuddy.domain.model.pay.ParsedPay(
            appPayComponents = listOf(cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem("DoorDash pay", 8.00)),
            customerTips = listOf(cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem("618", 10.00)),
        )
        val effects = effectMap.diff(prev, next, screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 18.00, parsedPay = parsedPay),
        ))
        val bubbles = effects.filterIsInstance<AppEffect.UpdateBubble>()
        assertEquals(1, bubbles.size)
        assertTrue(
            "Bare-number tip label should render as 'Store #618', not the raw digits",
            bubbles[0].text.contains("Tip: Store #618 • \$10.00"),
        )
        assertFalse(
            "The raw bare-digit label alone must not appear unqualified",
            bubbles[0].text.contains("Tip: 618 •"),
        )
    }

    // =========================================================================
    // APP-OWNED ACTIONS (#425): expand decision + deferred-action round-trip +
    // UiInput accept/decline aimed by rule-bound targets
    // =========================================================================

    private fun testNodeRef(id: String = "com.example:id/btn") =
        cloud.trotter.dashbuddy.domain.pipeline.NodeRef(
            viewIdSuffix = id,
            text = null, classNameHint = "android.widget.Button",
            boundsInScreen = cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox(0, 0, 100, 50),
            pathFingerprint = "fp",
        )

    @Test
    fun `collapsed summary with expand target schedules a deferred EXPAND_EARNINGS`() {
        val obs = screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 25.0, isExpanded = false),
            ruleId = "doordash.screen.delivery_summary_collapsed",
        ).copy(targets = mapOf("expandButton" to testNodeRef()))

        val effects = effectMap.diff(AppState(), AppState(), obs)
        val scheduled = effects.filterIsInstance<AppEffect.ScheduleTimeout>()
            .filter { it.type == cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.SETTLE_UI }
        assertEquals(1, scheduled.size)
        assertEquals(EffectMap.EXPAND_SETTLE_MS, scheduled[0].durationMs)
        val deferred = scheduled[0].payload as ObservationPayload.DeferredAction
        assertEquals(RuleAction.EXPAND_EARNINGS.wire, deferred.action)
        assertEquals("com.example:id/btn", deferred.target.viewIdSuffix)
        assertEquals("doordash.screen.delivery_summary_collapsed", deferred.ruleId)
        // No direct tap effect — the round-trip is mandatory.
        assertTrue(effects.filterIsInstance<AppEffect.PerformRuleAction>().isEmpty())
    }

    @Test
    fun `expanded summary schedules no expand action`() {
        val obs = screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 25.0, isExpanded = true),
            ruleId = "doordash.screen.delivery_summary_expanded",
        ).copy(targets = mapOf("expandButton" to testNodeRef()))
        val effects = effectMap.diff(AppState(), AppState(), obs)
        assertTrue(
            effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .none { it.payload is ObservationPayload.DeferredAction },
        )
    }

    @Test
    fun `collapsed summary WITHOUT expand target schedules nothing - fail closed`() {
        val obs = screenObs(
            flow = Flow.PostTask,
            parsed = ParsedFields.PostTaskFields(totalPay = 25.0, isExpanded = false),
            ruleId = "doordash.screen.delivery_summary_collapsed",
        )
        val effects = effectMap.diff(AppState(), AppState(), obs)
        assertTrue(
            effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .none { it.payload is ObservationPayload.DeferredAction },
        )
    }

    // =========================================================================
    // #577 — quick-decline: deferred CONFIRM_DECLINE on the confirm screen
    // =========================================================================

    private fun confirmDeclineObs() = screenObs(
        flow = Flow.OfferPresented,
        ruleId = "doordash.screen.offer_popup_confirm_decline",
    ).copy(targets = mapOf("confirmDeclineButton" to testNodeRef("com.doordash.driverapp:id/textView_prism_button_title")))

    private fun offerPresentedState() = AppState(
        regions = Regions(flow = FlowRegion(flow = Flow.OfferPresented, pendingOffer = testPendingOffer)),
    )

    @Test
    fun `confirm-decline screen with bound target during an offer schedules a deferred CONFIRM_DECLINE (#577)`() {
        // prev already OfferPresented so no offer-presentation noise; the confirm dialog is mid-offer.
        val effects = effectMap.diff(offerPresentedState(), offerPresentedState(), confirmDeclineObs())
        val scheduled = effects.filterIsInstance<AppEffect.ScheduleTimeout>()
            .filter { it.type == cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.SETTLE_UI }
        assertEquals(1, scheduled.size)
        val deferred = scheduled[0].payload as ObservationPayload.DeferredAction
        assertEquals(RuleAction.CONFIRM_DECLINE.wire, deferred.action)
        // No direct tap — the engine's quick-declines setting gate decides at fire time.
        assertTrue(effects.filterIsInstance<AppEffect.PerformRuleAction>().isEmpty())
    }

    @Test
    fun `confirm-decline screen WITHOUT a bound target schedules nothing - fail closed (#577)`() {
        val obs = screenObs(flow = Flow.OfferPresented, ruleId = "doordash.screen.offer_popup_confirm_decline")
        val effects = effectMap.diff(offerPresentedState(), offerPresentedState(), obs)
        assertTrue(
            effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .none { it.payload is ObservationPayload.DeferredAction },
        )
    }

    @Test
    fun `confirm-decline screen with NO pending offer schedules nothing (#577)`() {
        val effects = effectMap.diff(AppState(), AppState(), confirmDeclineObs())
        assertTrue(
            effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .none { it.payload is ObservationPayload.DeferredAction },
        )
    }

    @Test
    fun `SETTLE_UI routes a deferred CONFIRM_DECLINE to an AUTOMATION PerformRuleAction (#577)`() {
        val timeoutObs = Observation.Timeout(
            timestamp = 1000L,
            type = cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.SETTLE_UI,
            payload = ObservationPayload.DeferredAction(
                action = RuleAction.CONFIRM_DECLINE.wire,
                platform = Platform.DoorDash.wire,
                ruleId = "doordash.screen.offer_popup_confirm_decline",
                target = testNodeRef("com.doordash.driverapp:id/textView_prism_button_title"),
            ),
        )
        val actions = effectMap.diff(AppState(), AppState(), timeoutObs)
            .filterIsInstance<AppEffect.PerformRuleAction>()
        assertEquals(1, actions.size)
        assertEquals(RuleAction.CONFIRM_DECLINE, actions[0].action)
        assertEquals(ActionTrigger.AUTOMATION, actions[0].trigger)
    }

    @Test
    fun `SETTLE_UI timeout emits the deferred action as immediate PerformRuleAction`() {
        val payload = ObservationPayload.DeferredAction(
            action = RuleAction.EXPAND_EARNINGS.wire,
            platform = Platform.DoorDash.wire,
            ruleId = "doordash.screen.delivery_summary_collapsed",
            target = testNodeRef(),
        )
        val timeoutObs = Observation.Timeout(
            timestamp = 1000L,
            type = cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.SETTLE_UI,
            payload = payload,
        )
        val effects = effectMap.diff(AppState(), AppState(), timeoutObs)
        val actions = effects.filterIsInstance<AppEffect.PerformRuleAction>()
        assertEquals(1, actions.size)
        assertEquals(RuleAction.EXPAND_EARNINGS, actions[0].action)
        assertEquals(Platform.DoorDash, actions[0].platform)
        assertEquals("com.example:id/btn", actions[0].targetRef.viewIdSuffix)
        assertEquals("doordash.screen.delivery_summary_collapsed", actions[0].sourceRuleId)
        // App-decided tap → must pass the engine's capability grant gate (#417).
        assertEquals(ActionTrigger.AUTOMATION, actions[0].trigger)
    }

    @Test
    fun `UiInput accept fires PerformRuleAction aimed by the offer rule's bound target`() {
        val acceptRef = testNodeRef("com.doordash.driverapp:id/accept_button")
        val offerWithTargets = testPendingOffer.copy(
            targets = mapOf("acceptButton" to acceptRef),
            sourceRuleId = "doordash.screen.offer_popup",
        )
        val flowRegion = FlowRegion(
            flow = Flow.OfferPresented,
            pendingOffer = offerWithTargets,
            activePlatform = Platform.DoorDash,
        )
        val state = AppState(regions = Regions(flow = flowRegion))
        val obs = Observation.UiInput(timestamp = 2000L, action = OfferIntent.ACCEPT)

        val effects = effectMap.diff(state, state, obs)
        val actions = effects.filterIsInstance<AppEffect.PerformRuleAction>()
        assertEquals(1, actions.size)
        assertEquals(RuleAction.ACCEPT_OFFER, actions[0].action)
        assertEquals(acceptRef, actions[0].targetRef)
        assertEquals("doordash.screen.offer_popup", actions[0].sourceRuleId)
        // Dasher-pressed → its own consent; the grant gate does not apply (#417).
        assertEquals(ActionTrigger.USER, actions[0].trigger)
    }

    @Test
    fun `UiInput accept with NO bound target fires nothing - action unavailable`() {
        val flowRegion = FlowRegion(
            flow = Flow.OfferPresented,
            pendingOffer = testPendingOffer, // no targets
            activePlatform = Platform.DoorDash,
        )
        val state = AppState(regions = Regions(flow = flowRegion))
        val obs = Observation.UiInput(timestamp = 2000L, action = OfferIntent.ACCEPT)

        val effects = effectMap.diff(state, state, obs)
        assertTrue(
            "No target bound → no tap (fail closed)",
            effects.filterIsInstance<AppEffect.PerformRuleAction>().isEmpty(),
        )
    }

    @Test
    fun `UiInput accept when R0 left OfferPresented fires nothing — the #457 shade-drop path`() {
        // The heads-up notification can outlive the on-screen offer: by the
        // time a SHADE Accept tap dispatches, R0 may have advanced past
        // OfferPresented (a UiInput never changes the flow itself). The action
        // is dropped — pinned here as the documented #457 drop path so a future
        // fix (and the new diagnostic log) is deliberate, not accidental.
        val acceptRef = testNodeRef("com.doordash.driverapp:id/accept_button")
        val offerWithTargets = testPendingOffer.copy(
            targets = mapOf("acceptButton" to acceptRef),
            sourceRuleId = "doordash.screen.offer_popup",
        )
        // Same offer state, but flow has moved to PostTask (offer left the screen).
        val flowRegion = FlowRegion(
            flow = Flow.PostTask,
            pendingOffer = offerWithTargets,
            activePlatform = Platform.DoorDash,
        )
        val state = AppState(regions = Regions(flow = flowRegion))
        val obs = Observation.UiInput(timestamp = 2000L, action = OfferIntent.ACCEPT)

        val effects = effectMap.diff(state, state, obs)
        assertTrue(
            "off-OfferPresented UiInput must not fire a tap (the #457 silent drop)",
            effects.filterIsInstance<AppEffect.PerformRuleAction>().isEmpty(),
        )
    }

    // =========================================================================
    // NOTIFICATION EFFECTS
    // =========================================================================

    @Test
    fun `additional_tip notification emits ProcessTipNotification`() {
        val (platform, onlineRegion) = stateWithPlatform()
        val state = AppState(regions = Regions(platforms = mapOf(platform to onlineRegion)))

        val effects = effectMap.diff(state, state, notificationObs(
            intent = "additional_tip",
            amount = 5.0,
            storeName = "Chipotle",
            deliveredAt = "2024-01-01",
        ))

        assertTrue("Should emit ProcessTipNotification", effects.any { it is AppEffect.ProcessTipNotification })
    }

    @Test
    fun `new_order notification does not crash`() {
        // Log effects are now rule-declared (JSON) and dispatched via diffRuleEffects.
        // This test verifies the observation is processed without errors.
        val (platform, onlineRegion) = stateWithPlatform()
        val state = AppState(regions = Regions(platforms = mapOf(platform to onlineRegion)))

        val effects = effectMap.diff(state, state, notificationObs(intent = "new_order"))
        // No hardcoded log effects — logging comes from rule-declared effects
        assertTrue("Should produce no hardcoded effects for new_order", effects.isEmpty())
    }

    // =========================================================================
    // PER-NOTIFICATION EFFECT KEYS (#604)
    //
    // A notification is a discrete arrival, not an install-once fact: a
    // rule-declared log effect with no dedupeKey must key per-arrival
    // (postTime), not "once ever" — otherwise the second `new_order`
    // notification of the dash silently no-ops against `effects_fired`
    // (the #604 bug). Screen observations are deliberately NOT suffixed —
    // their cross-frame dedup (e.g. offer-ss-{parsedHash}) is intended.
    // =========================================================================

    @Test
    fun `two notifications with the same rule effect but different timestamps get distinct effectKeys`() {
        val (platform, onlineRegion) = stateWithPlatform()
        val state = AppState(regions = Regions(platforms = mapOf(platform to onlineRegion)))
        val logEffect = listOf(makeRequestedEffect(EffectVerb.LOG, ruleId = "doordash.notification.new_order"))

        val firstArrival = effectMap.diff(
            state, state,
            notificationObs(intent = "new_order", timestamp = 1000L, effects = logEffect),
        ).filterIsInstance<AppEffect.RequestEffect>()
        val secondArrival = effectMap.diff(
            state, state,
            notificationObs(intent = "new_order", timestamp = 2000L, effects = logEffect),
        ).filterIsInstance<AppEffect.RequestEffect>()

        assertEquals(1, firstArrival.size)
        assertEquals(1, secondArrival.size)
        assertNotEquals(
            "Distinct notification arrivals must not share an idempotency key",
            firstArrival[0].effectKey,
            secondArrival[0].effectKey,
        )
    }

    @Test
    fun `two notifications with the same rule effect and same timestamp share an effectKey`() {
        val (platform, onlineRegion) = stateWithPlatform()
        val state = AppState(regions = Regions(platforms = mapOf(platform to onlineRegion)))
        val logEffect = listOf(makeRequestedEffect(EffectVerb.LOG, ruleId = "doordash.notification.new_order"))

        val first = effectMap.diff(
            state, state,
            notificationObs(intent = "new_order", timestamp = 1000L, effects = logEffect),
        ).filterIsInstance<AppEffect.RequestEffect>()
        val repost = effectMap.diff(
            state, state,
            notificationObs(intent = "new_order", timestamp = 1000L, effects = logEffect),
        ).filterIsInstance<AppEffect.RequestEffect>()

        assertEquals(1, first.size)
        assertEquals(1, repost.size)
        assertEquals(
            "An identical repost (same postTime) must still dedup",
            first[0].effectKey,
            repost[0].effectKey,
        )
    }

    @Test
    fun `a Screen observation's rule effect key is unsuffixed - exact string regression pin`() {
        val logEffect = RequestedEffect(
            verb = EffectVerb.LOG,
            ruleId = "doordash.screen.test",
        )
        val obs = Observation.Screen(
            timestamp = 1000L,
            captureId = "cap-1000",
            ruleId = "doordash.screen.test",
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.None,
            effects = listOf(logEffect),
        )

        val effects = effectMap.diff(AppState(), AppState(), obs).filterIsInstance<AppEffect.RequestEffect>()

        assertEquals(1, effects.size)
        assertEquals(
            "Screen rule-effect keys are unsuffixed — cross-frame dedup is intended behavior",
            "effect:doordash.screen.test:log",
            effects[0].effectKey,
        )
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
        assertTrue("No StartSession", effects.none { it is AppEffect.StartSession })
        assertTrue("No EndSession", effects.none { it is AppEffect.EndSession })
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
        assertTrue("No StartSession", effects.none { it is AppEffect.StartSession })
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
        assertTrue("No EndSession", effects.none { it is AppEffect.EndSession })
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
        assertTrue("Should emit StartSession", effects.any { it is AppEffect.StartSession })
        assertTrue("Should emit DASH_START", effects.logEventTypes().contains(AppEventType.DASH_START))
    }

    // =====================================================================================
    // #566 — per-task bubble idempotency key (double fly-away on a stacked/new pickup)
    // =====================================================================================

    @Test
    fun `a new pickup bubble carries a per-task dedupe key (#566, wiring)`() {
        // A new pickup (prevTask null → nextTask PICKUP) emits a KEYED UpdateBubble, so the engine's
        // effects_fired gate can collapse the two-site double emission of "Pickup: <store>".
        val (platform, base) = stateWithPlatform()
        val pickup = Task(taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP, storeName = "H-E-B", startedAt = 100L)
        val prev = AppState(regions = Regions(flow = FlowRegion(flow = Flow.OfferPresented), platforms = mapOf(platform to base)))
        val nextRegion = base.copy(activeTask = pickup)
        val next = AppState(regions = Regions(flow = FlowRegion(flow = Flow.TaskPickupNavigation), platforms = mapOf(platform to nextRegion)))

        val bubble = effectMap.diff(
            prev, next,
            screenObs(
                flow = Flow.TaskPickupNavigation,
                parsed = ParsedFields.TaskFields(storeName = "H-E-B", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION),
            ),
        ).effectsOfType<AppEffect.UpdateBubble>().firstOrNull { it.text.contains("Pickup") }

        assertNotNull("a new pickup should emit a bubble", bubble)
        assertNotNull("the pickup bubble must carry a dedupe key so the double fly-away dedups (#566)", bubble!!.effectKey)
        assertTrue("the key is scoped to the task id", bubble.effectKey!!.contains("task-1"))
    }

    @Test
    fun `the same task switching persona produces different bubble keys, so the activity change still fires (#566)`() {
        // H-E-B leg: NAVIGATOR (heading to store) → SHOPPER (now shopping). Same task, same text, but
        // distinct personas → distinct keys → the legitimate re-emit is NOT suppressed.
        val nav = AppEffect.UpdateBubble("Pickup: H-E-B", ChatPersona.Navigator, dedupeScope = "task-1")
        val shop = AppEffect.UpdateBubble("Pickup: H-E-B", ChatPersona.Shopper, dedupeScope = "task-1")
        assertNotEquals("NAVIGATOR→SHOPPER on the same leg must still fire", nav.effectKey, shop.effectKey)
    }

    @Test
    fun `the same store on a later distinct leg produces a different bubble key (#566)`() {
        // Two separate H-E-B pickups in one dash (effects_fired persists ~48h) must each fire.
        val first = AppEffect.UpdateBubble("Pickup: H-E-B", ChatPersona.Navigator, dedupeScope = "task-1")
        val later = AppEffect.UpdateBubble("Pickup: H-E-B", ChatPersona.Navigator, dedupeScope = "task-9")
        assertNotEquals("a later same-store leg must still fire", first.effectKey, later.effectKey)
    }

    @Test
    fun `identical task, persona and text produce the same key, so the consecutive double dedups (#566)`() {
        // The bug: two EffectMap sites emit the byte-identical bubble on consecutive frames of one leg.
        val a = AppEffect.UpdateBubble("Pickup: Petsmart", ChatPersona.Navigator, dedupeScope = "task-1")
        val b = AppEffect.UpdateBubble("Pickup: Petsmart", ChatPersona.Navigator, dedupeScope = "task-1")
        assertEquals("the consecutive identical double must collapse to one", a.effectKey, b.effectKey)
        assertNotNull(a.effectKey)
    }

    @Test
    fun `a one-shot non-task bubble stays unkeyed so it can legitimately recur (#566)`() {
        // Offer/session/resume/paused/earnings bubbles pass no dedupeScope → null key → never deduped.
        assertNull("Offer Accepted must stay unkeyed", AppEffect.UpdateBubble("Offer Accepted", ChatPersona.Dispatcher).effectKey)
        assertNull("a default bubble is unkeyed", AppEffect.UpdateBubble("Dash Paused!").effectKey)
    }
}
