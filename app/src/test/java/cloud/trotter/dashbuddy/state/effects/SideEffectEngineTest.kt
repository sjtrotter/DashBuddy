package cloud.trotter.dashbuddy.state.effects

import android.util.Log
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredEntity
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.core.state.MetadataProvider
import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.config.EvidenceCategory
import cloud.trotter.dashbuddy.domain.config.EvidenceConfig
import cloud.trotter.dashbuddy.domain.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.RecordingTree
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import timber.log.Timber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

/**
 * Behavior tests for the SideEffectEngine execution edge:
 * - #341: crash isolation, recovery suppression of external effects, timer lifecycle.
 * - #351: strict in-order execution via the serialized worker, and the
 *   "execute, then markFired" idempotency ordering (with the real
 *   correlationVersion stamped).
 *
 * The engine owns its execution scope, built from the injected default
 * dispatcher — tests pass the scheduler's dispatcher, so time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SideEffectEngineTest {

    /** Backs the evidence gate (#426). Default: everything allowed — individual tests stub denial. */
    private val evidenceConfig = MutableStateFlow(
        EvidenceConfig(
            masterEnabled = true,
            saveOffers = true,
            saveDeliverySummaries = true,
            saveSessionSummaries = true,
        ),
    )

    private val appEventRepo: AppEventRepo = mock()
    private val odometerEffectHandler: OdometerEffectHandler = mock()
    private val tipEffectHandler: TipEffectHandler = mock()
    private val bubbleManager: BubbleManager = mock()
    private val offerEvaluator: OfferEvaluator = mock()
    /** Backs the eval-config read (#436). Non-null so EvaluateOffer never suspends in tests. */
    private val evaluationConfig =
        MutableStateFlow<EvaluationConfig?>(EvaluationConfig())

    private val strategyRepository: StrategyRepository = mock {
        on { this.evidenceConfig } doReturn this@SideEffectEngineTest.evidenceConfig
        on { this.evaluationConfig } doReturn this@SideEffectEngineTest.evaluationConfig
    }
    private val screenShotHandler: ScreenShotHandler = mock()
    private val uiInteractionHandler: UiInteractionHandler = mock()
    /** Live-path dedupe (#436) consults this for every keyed effect — default "not fired". */
    private val effectsFiredDao: EffectsFiredDao = mock {
        on { hasBeenFired(any()) } doReturn false
    }
    private val ttsEffectHandler: TtsEffectHandler = mock()

    /** Default: every tier granted — individual tests stub denial. */
    private val permissionTierChecker: PermissionTierChecker = mock {
        on { isGranted(any()) } doReturn true
    }

    /** Default: every capability granted — individual tests stub denial. */
    private val capabilityGrants: RuleCapabilityGrants = mock {
        on { isActionGranted(anyOrNull(), any()) } doReturn true
    }

    private fun buildEngine(defaultDispatcher: CoroutineDispatcher) = SideEffectEngine(
        appEventRepo = appEventRepo,
        odometerEffectHandler = odometerEffectHandler,
        tipEffectHandler = tipEffectHandler,
        bubbleManager = bubbleManager,
        offerEvaluator = offerEvaluator,
        strategyRepository = strategyRepository,
        screenShotHandler = screenShotHandler,
        uiInteractionHandler = uiInteractionHandler,
        effectsFiredDao = effectsFiredDao,
        ttsEffectHandler = ttsEffectHandler,
        permissionTierChecker = permissionTierChecker,
        capabilityGrants = capabilityGrants,
        metadataProvider = MetadataProvider { "{}" },
        defaultDispatcher = defaultDispatcher,
    )

    private fun logEvent(type: AppEventType, occurredAt: Long) = AppEffect.LogEvent(
        AppEvent(
            type = type,
            occurredAt = occurredAt,
            sessionId = "sess-1",
        )
    )

    // =========================================================================
    // #341 — crash isolation
    // =========================================================================

    @Test
    fun `a throwing handler is isolated and the engine keeps processing`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val eval: OfferEvaluation = mock()
        doThrow(RuntimeException("boom")).whenever(ttsEffectHandler).speakOffer(any())

        engine.process(AppEffect.SpeakOffer(eval))
        runCurrent()

        // Worker is still alive: the next effect executes normally.
        engine.process(AppEffect.SpeakOffer(eval))
        runCurrent()
        verify(ttsEffectHandler, times(2)).speakOffer(any())
    }

    // =========================================================================
    // #341/#425 — recovery suppression: PerformRuleAction must never replay a tap
    // =========================================================================

    private fun acceptActionEffect(trigger: ActionTrigger = ActionTrigger.AUTOMATION) =
        AppEffect.PerformRuleAction(
            action = RuleAction.ACCEPT_OFFER,
            platform = Platform.DoorDash,
            targetRef = NodeRef(
                viewIdSuffix = "com.doordash.driverapp:id/accept_button",
                text = null,
                classNameHint = "android.widget.Button",
                boundsInScreen = BoundingBox(0, 100, 200, 150),
                pathFingerprint = "View[0]/Button[1]",
            ),
            sourceRuleId = "doordash.screen.offer_popup",
            trigger = trigger,
        )

    @Test
    fun `PerformRuleAction is suppressed during recovery and executes live`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val effect = acceptActionEffect()

        engine.process(effect, recovering = true)
        runCurrent()
        verify(uiInteractionHandler, never()).performVerifiedClick(any(), any(), any(), any(), any())

        engine.process(effect, recovering = false)
        runCurrent()
        verify(uiInteractionHandler, times(1)).performVerifiedClick(
            eq(effect.targetRef),
            eq(Platform.DoorDash.packageName),
            eq(RuleAction.ACCEPT_OFFER.verification),
            any(),
            eq(false), // AUTOMATION trigger → no retry (#618 F2: retry is USER-taps only)
        )
    }

    @Test
    fun `PerformRuleAction is throttled per action and platform`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))

        engine.process(acceptActionEffect())
        runCurrent()
        engine.process(acceptActionEffect())
        runCurrent()

        // Second fire inside RULE_ACTION_THROTTLE_MS is swallowed — an
        // app-owned bound on automated taps (#425).
        verify(uiInteractionHandler, times(1)).performVerifiedClick(any(), any(), any(), any(), any())
    }

    // =========================================================================
    // #577 — quick-decline: the setting is the consent (engine-edge gate)
    // =========================================================================

    private fun confirmDeclineEffect() = AppEffect.PerformRuleAction(
        action = RuleAction.CONFIRM_DECLINE,
        platform = Platform.DoorDash,
        targetRef = NodeRef(
            viewIdSuffix = "com.doordash.driverapp:id/textView_prism_button_title",
            text = null, classNameHint = "android.widget.TextView",
            boundsInScreen = BoundingBox(0, 100, 200, 150), pathFingerprint = "fp",
        ),
        sourceRuleId = "doordash.screen.offer_popup_confirm_decline",
        trigger = ActionTrigger.AUTOMATION,
    )

    @Test
    fun `CONFIRM_DECLINE fires when quick declines is ON (#577)`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        whenever { strategyRepository.automationConfig }
            .thenReturn(flowOf(OfferAutomationConfig(quickDeclinesEnabled = true)))
        engine.process(confirmDeclineEffect())
        runCurrent()
        verify(uiInteractionHandler, times(1)).performVerifiedClick(any(), any(), any(), any(), any())
    }

    @Test
    fun `CONFIRM_DECLINE is denied when quick declines is OFF (#577)`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        whenever { strategyRepository.automationConfig }
            .thenReturn(flowOf(OfferAutomationConfig(quickDeclinesEnabled = false)))
        engine.process(confirmDeclineEffect())
        runCurrent()
        verify(uiInteractionHandler, never()).performVerifiedClick(any(), any(), any(), any(), any())
    }

    // =========================================================================
    // #417 — consent gate at the PerformRuleAction seam
    // =========================================================================

    @Test
    fun `an AUTOMATION fire without a granted capability is denied`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        whenever { capabilityGrants.isActionGranted(anyOrNull(), any()) }
            .thenReturn(false)

        engine.process(acceptActionEffect(trigger = ActionTrigger.AUTOMATION))
        runCurrent()

        verify(uiInteractionHandler, never()).performVerifiedClick(any(), any(), any(), any(), any())
    }

    @Test
    fun `an AUTOMATION fire with a granted capability fires`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))

        engine.process(acceptActionEffect(trigger = ActionTrigger.AUTOMATION))
        runCurrent()

        verify(uiInteractionHandler, times(1)).performVerifiedClick(any(), any(), any(), any(), any())
        verifyBlocking(capabilityGrants) {
            isActionGranted(eq("doordash.screen.offer_popup"), eq(RuleAction.ACCEPT_OFFER))
        }
    }

    @Test
    fun `a USER fire is its own consent — the grant store is not consulted`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        whenever { capabilityGrants.isActionGranted(anyOrNull(), any()) }
            .thenReturn(false)

        engine.process(acceptActionEffect(trigger = ActionTrigger.USER))
        runCurrent()

        verify(uiInteractionHandler, times(1)).performVerifiedClick(any(), any(), any(), any(), any())
        verifyBlocking(capabilityGrants, never()) { isActionGranted(anyOrNull(), any()) }
    }

    @Test
    fun `a denied ACCESSIBILITY tier blocks even a USER fire`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        whenever(permissionTierChecker.isGranted(any())).thenReturn(false)

        engine.process(acceptActionEffect(trigger = ActionTrigger.USER))
        runCurrent()

        verify(uiInteractionHandler, never()).performVerifiedClick(any(), any(), any(), any(), any())
    }

    @Test
    fun `rule-verb dispatch consults the real tier checker`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        // Distinct dedupeKeys: the engine throttles RequestEffect per effectKey
        // on the wall clock, and a denied fire still consumes the slot.
        // Category present + evidence fully allowed (defaults), so the TIER is
        // the only gate under test here.
        fun screenshot(key: String) = AppEffect.RequestEffect(
            RequestedEffect(
                verb = EffectVerb.SCREENSHOT,
                ruleId = "doordash.screen.offer_popup",
                args = mapOf("category" to "offer"),
                dedupeKey = key,
            ),
        )

        whenever(permissionTierChecker.isGranted(any())).thenReturn(false)
        engine.process(screenshot("denied"))
        runCurrent()
        verify(screenShotHandler, never()).capture(any(), any())

        whenever(permissionTierChecker.isGranted(any())).thenReturn(true)
        engine.process(screenshot("granted"))
        runCurrent()
        verify(screenShotHandler, times(1)).capture(any(), any())
    }

    // =========================================================================
    // #426 — evidence gate on screenshots
    // =========================================================================

    private fun dashSummaryShot() = AppEffect.CaptureScreenshot(
        filenamePrefix = "DashSummary - 87.50",
        category = EvidenceCategory.SESSION_SUMMARY,
    )

    @Test
    fun `master off suppresses an EffectMap-emitted screenshot`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        evidenceConfig.value = EvidenceConfig(masterEnabled = false)

        engine.process(dashSummaryShot())
        runCurrent()

        verify(screenShotHandler, never()).capture(any(), any())
    }

    @Test
    fun `a disabled category suppresses its screenshot while others fire`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        evidenceConfig.value = EvidenceConfig(
            masterEnabled = true,
            saveOffers = true,
            saveSessionSummaries = false,
        )

        engine.process(dashSummaryShot())
        runCurrent()
        verify(screenShotHandler, never()).capture(any(), any())

        engine.process(
            AppEffect.CaptureScreenshot("Offer - Chipotle", category = EvidenceCategory.OFFER),
        )
        runCurrent()
        verify(screenShotHandler, times(1)).capture(any(), any())
    }

    @Test
    fun `an uncategorized screenshot never fires - fail closed`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))

        // Everything enabled — the missing category alone must deny it.
        engine.process(AppEffect.CaptureScreenshot("Mystery", category = null))
        runCurrent()

        verify(screenShotHandler, never()).capture(any(), any())
    }

    @Test
    fun `a rule-declared screenshot is gated by its category arg`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        evidenceConfig.value = EvidenceConfig(masterEnabled = true, saveOffers = false)

        engine.process(
            AppEffect.RequestEffect(
                RequestedEffect(
                    verb = EffectVerb.SCREENSHOT,
                    ruleId = "doordash.screen.offer_popup",
                    args = mapOf("prefix" to "Offer - Chipotle", "category" to "offer"),
                    dedupeKey = "offer-gated",
                ),
            ),
        )
        runCurrent()
        verify(screenShotHandler, never()).capture(any(), any())

        evidenceConfig.value = EvidenceConfig(masterEnabled = true, saveOffers = true)
        engine.process(
            AppEffect.RequestEffect(
                RequestedEffect(
                    verb = EffectVerb.SCREENSHOT,
                    ruleId = "doordash.screen.offer_popup",
                    args = mapOf("prefix" to "Offer - Chipotle", "category" to "offer"),
                    dedupeKey = "offer-allowed",
                ),
            ),
        )
        runCurrent()
        verify(screenShotHandler, times(1)).capture(any(), any())
    }

    // =========================================================================
    // #436 — engine latency + dedupe pack
    // =========================================================================

    /** Real evaluation — the summary formatter and persona mapping read its fields. */
    private fun testEvaluation() = OfferEvaluation(
        action = cloud.trotter.dashbuddy.domain.evaluation.OfferAction.ACCEPT,
        score = 74.0,
        qualityLevel = cloud.trotter.dashbuddy.domain.evaluation.OfferQuality.GOOD,
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

    private fun testOfferCard() = FlowCardSnapshot.Offer(
        phaseStartedAt = 0L,
        offerHash = "hash-9",
        payAmount = 7.50,
        netPayAmount = 7.0,
        distanceMiles = 3.2,
        dollarsPerMile = 2.19,
        dollarsPerHour = 22.0,
        evaluationScore = 74.0,
        evaluationAction = "ACCEPT",
    )

    @Test
    fun `a resolved offer cancels the pending notification post`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))

        engine.process(AppEffect.PostOfferNotification(testEvaluation(), testOfferCard(), offerHash = "hash-9", platform = Platform.DoorDash))
        runCurrent()
        engine.process(AppEffect.CancelOfferNotification(offerHash = "hash-9"))
        runCurrent()
        advanceTimeBy(SideEffectEngine.OFFER_NOTIFICATION_DELAY_MS + 100)
        runCurrent()

        verify(bubbleManager, never()).postOfferNotification(any(), any(), any())
    }

    @Test
    fun `an unresolved offer notification still posts after the settle delay`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))

        engine.process(AppEffect.PostOfferNotification(testEvaluation(), testOfferCard(), offerHash = "hash-9", platform = Platform.DoorDash))
        runCurrent()
        advanceTimeBy(SideEffectEngine.OFFER_NOTIFICATION_DELAY_MS + 100)
        runCurrent()

        verify(bubbleManager, times(1)).postOfferNotification(any(), any(), any())
    }

    @Test
    fun `a resolved offer dismisses an already-posted heads-up (#457)`() = runTest {
        // The offer heads-up is now a SEPARATE notification (own id), not the self-replacing bubble,
        // so once it has posted, resolution must explicitly dismiss it.
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        engine.process(AppEffect.PostOfferNotification(testEvaluation(), testOfferCard(), offerHash = "hash-9", platform = Platform.DoorDash))
        runCurrent()
        advanceTimeBy(SideEffectEngine.OFFER_NOTIFICATION_DELAY_MS + 100)
        runCurrent()
        verify(bubbleManager, times(1)).postOfferNotification(any(), any(), any())

        engine.process(AppEffect.CancelOfferNotification(offerHash = "hash-9"))
        runCurrent()
        verify(bubbleManager, times(1)).cancelOfferNotification()
    }

    @Test
    fun `EvaluateOffer stamps the offer's platform onto the eval loopback (#438 item 8a)`() = runTest {
        // Pre-B3 the evaluation still lands in R0 — this asserts the STAMPING (identity carried on
        // the loopback), not the region landing. Without the stamp an Unknown-platform loopback
        // steps no region post-#682, silently killing the offer's notification/TTS.
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        whenever(offerEvaluator.evaluate(any(), any())).thenReturn(testEvaluation())
        val collected = mutableListOf<cloud.trotter.dashbuddy.domain.model.state.StateEvent>()
        val job = launch { engine.events.collect { collected += it } }
        runCurrent()

        engine.process(
            AppEffect.EvaluateOffer(
                cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer(offerHash = "hash-U", payAmount = 7.5),
                offerHash = "hash-U",
                platform = Platform.Uber,
            ),
        )
        runCurrent()
        job.cancel()

        val loop = collected.filterIsInstance<cloud.trotter.dashbuddy.domain.pipeline.Observation.Loopback>().single()
        assertEquals(Platform.Uber, loop.targetPlatform)
        assertEquals(Platform.Uber, loop.platform)
    }

    @Test
    fun `keyed effects dedupe on the live path - not just during recovery`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val effect = AppEffect.StartSession("sess-7", "DoorDash")
        whenever { effectsFiredDao.hasBeenFired(effect.effectKey) }
            .thenReturn(true)

        engine.process(effect, recovering = false)
        runCurrent()

        verify(bubbleManager, never()).startSession(any(), any())
        verifyBlocking(effectsFiredDao, never()) { markFired(any()) }
    }

    @Test
    fun `two identical keyed pickup bubbles post the fly-away exactly once (#566 end-to-end)`() = runTest {
        // The double fly-away: two EffectMap sites emit the byte-identical per-task bubble on
        // consecutive frames. With a dedupeScope (taskId) the bubble now carries an effectKey, so the
        // engine's effects_fired gate collapses the second. Stateful: not-fired on the first call,
        // fired on the second (as markFired would have recorded between them).
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val bubble = AppEffect.UpdateBubble("Pickup: Petsmart", dedupeScope = "task-1")
        assertNotNull("the per-task bubble must be keyed for this to work", bubble.effectKey)
        whenever { effectsFiredDao.hasBeenFired(bubble.effectKey!!) }.thenReturn(false, true)

        engine.process(bubble, recovering = false)
        runCurrent()
        engine.process(bubble, recovering = false)
        runCurrent()

        verify(bubbleManager, times(1)).postMessage(eq("Pickup: Petsmart"), any(), any())
        verifyBlocking(effectsFiredDao, times(1)) { markFired(any()) }
    }

    @Test
    fun `an unkeyed one-shot bubble posts every time (#566 does not over-dedup)`() = runTest {
        // Offer/session/etc. bubbles have no dedupeScope → null key → the gate never suppresses them.
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val bubble = AppEffect.UpdateBubble("Offer Accepted")
        assertNull("a one-shot bubble must stay unkeyed", bubble.effectKey)

        engine.process(bubble, recovering = false)
        runCurrent()
        engine.process(bubble, recovering = false)
        runCurrent()

        verify(bubbleManager, times(2)).postMessage(eq("Offer Accepted"), any(), any())
    }

    @Test
    fun `a gate-denied rule effect is not marked fired`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        // Distinct dedupeKeys: a denied fire still consumes the wall-clock
        // throttle slot, so the granted attempt must not share its key.
        fun offerShot(key: String) = AppEffect.RequestEffect(
            RequestedEffect(
                verb = EffectVerb.SCREENSHOT,
                ruleId = "doordash.screen.offer_popup",
                args = mapOf("category" to "offer"),
                dedupeKey = key,
            ),
        )

        evidenceConfig.value = EvidenceConfig(masterEnabled = false)
        engine.process(offerShot("denied"))
        runCurrent()
        verify(screenShotHandler, never()).capture(any(), any())
        // Denied ≠ fired (#436): marking it would skip the effect forever
        // (live-path dedupe) once the user enables the Evidence setting.
        verifyBlocking(effectsFiredDao, never()) { markFired(any()) }

        evidenceConfig.value = EvidenceConfig(masterEnabled = true, saveOffers = true)
        engine.process(offerShot("granted"))
        runCurrent()
        verify(screenShotHandler, times(1)).capture(any(), any())
        verifyBlocking(effectsFiredDao, times(1)) { markFired(any()) }
    }

    // =========================================================================
    // #351 — strict ordering + execute-then-mark
    // =========================================================================

    @Test
    fun `effects execute strictly in process order`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))

        engine.process(AppEffect.UpdateBubble("first"))
        engine.process(AppEffect.UpdateBubble("second"))
        engine.process(AppEffect.UpdateBubble("third"))
        runCurrent()

        inOrder(bubbleManager) {
            verify(bubbleManager).postMessage(eq("first"), any(), any())
            verify(bubbleManager).postMessage(eq("second"), any(), any())
            verify(bubbleManager).postMessage(eq("third"), any(), any())
        }
    }

    @Test
    fun `LogEvent inserts and marks atomically through the repo with the real cv`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val effect = logEvent(AppEventType.OFFER_RECEIVED, occurredAt = 1_000L)

        engine.process(effect, correlationVersion = 42L)
        runCurrent()

        // Entity assembly + idempotency mark are the repo transaction's job (#354) —
        // the engine passes the domain event, edge metadata, key, and the real cv.
        verifyBlocking(appEventRepo) {
            insertAndMark(eq(effect.event), eq("{}"), eq(effect.effectKey), eq(42L))
        }
        // The generic markFired tail must NOT double-mark a LogEvent.
        verifyBlocking(effectsFiredDao, never()) { markFired(any()) }
    }

    @Test
    fun `a keyed non-LogEvent effect completes its durable work before markFired, with the real cv`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val effect = AppEffect.StartSession("sess-9", "DoorDash")

        engine.process(effect, correlationVersion = 7L)
        runCurrent()

        val ordered = inOrder(bubbleManager, effectsFiredDao)
        ordered.verify(bubbleManager).startSession("sess-9", "DoorDash")
        runBlocking { ordered.verify(effectsFiredDao).markFired(any()) }
        val captor = argumentCaptor<EffectsFiredEntity>()
        verifyBlocking(effectsFiredDao) { markFired(captor.capture()) }
        assertEquals(effect.effectKey, captor.firstValue.effectKey)
        assertEquals(7L, captor.firstValue.correlationVersion)
    }

    @Test
    fun `a scheduled timer does not block subsequent effects`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))

        engine.process(AppEffect.ScheduleTimeout(durationMs = 10_000, type = TimeoutType.SETTLE_UI))
        engine.process(AppEffect.UpdateBubble("after-timer"))
        runCurrent() // no time advanced — the timer is still pending

        verify(bubbleManager).postMessage(eq("after-timer"), any(), any())
    }

    // =========================================================================
    // #341 — timer lifecycle (through the queue)
    // =========================================================================

    @Test
    fun `an expired timer cannot untrack a replacement of the same type`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val fired = mutableListOf<TimeoutEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            engine.events.collect { if (it is TimeoutEvent) fired.add(it) }
        }
        runCurrent()

        engine.process(AppEffect.ScheduleTimeout(durationMs = 10, type = TimeoutType.SETTLE_UI))
        advanceTimeBy(11)
        runCurrent()
        assertEquals(1, fired.size)

        engine.process(AppEffect.ScheduleTimeout(durationMs = 100, type = TimeoutType.SETTLE_UI))
        runCurrent()
        engine.process(AppEffect.CancelTimeout(TimeoutType.SETTLE_UI))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fired.size) // replacement was cancelled, not orphaned
    }

    @Test
    fun `two platforms' timers of the same type coexist and each fires`() = runTest {
        // #438 item 1: the registry is keyed by (type, platform), so two paused
        // platforms each hold their own SESSION_PAUSED_SAFETY timer — before this,
        // the second schedule cancelled the first (shared type key).
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val fired = mutableListOf<TimeoutEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            engine.events.collect { if (it is TimeoutEvent) fired.add(it) }
        }
        runCurrent()

        engine.process(
            AppEffect.ScheduleTimeout(
                durationMs = 10, type = TimeoutType.SESSION_PAUSED_SAFETY,
                platform = Platform.DoorDash,
            ),
        )
        engine.process(
            AppEffect.ScheduleTimeout(
                durationMs = 10, type = TimeoutType.SESSION_PAUSED_SAFETY,
                platform = Platform.Uber,
            ),
        )
        runCurrent()
        advanceTimeBy(11)
        runCurrent()

        assertEquals(2, fired.size)
        assertEquals(
            setOf(Platform.DoorDash, Platform.Uber),
            fired.map { it.platform }.toSet(),
        )
    }

    @Test
    fun `cancelling one platform's timer leaves the other platform's timer armed`() = runTest {
        // #438 item 1: platform A's resume CancelTimeout must not kill platform B's
        // pause-safety timer of the same type.
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val fired = mutableListOf<TimeoutEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            engine.events.collect { if (it is TimeoutEvent) fired.add(it) }
        }
        runCurrent()

        engine.process(
            AppEffect.ScheduleTimeout(
                durationMs = 100, type = TimeoutType.SESSION_PAUSED_SAFETY,
                platform = Platform.DoorDash,
            ),
        )
        engine.process(
            AppEffect.ScheduleTimeout(
                durationMs = 100, type = TimeoutType.SESSION_PAUSED_SAFETY,
                platform = Platform.Uber,
            ),
        )
        runCurrent()
        // Cancel only DoorDash's timer.
        engine.process(
            AppEffect.CancelTimeout(TimeoutType.SESSION_PAUSED_SAFETY, Platform.DoorDash),
        )
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        // Only Uber's timer survived and fired.
        assertEquals(1, fired.size)
        assertEquals(Platform.Uber, fired.single().platform)
    }

    @Test
    fun `timers still arm during recovery (loopback, not external)`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val fired = mutableListOf<TimeoutEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            engine.events.collect { if (it is TimeoutEvent) fired.add(it) }
        }
        runCurrent()

        engine.process(
            AppEffect.ScheduleTimeout(durationMs = 10, type = TimeoutType.SESSION_PAUSED_SAFETY),
            recovering = true,
        )
        advanceTimeBy(11)
        runCurrent()
        assertEquals(1, fired.size)
    }

    // =========================================================================
    // #551 P7 — RecordShopRate: rate math is INFO-safe, the store name stays on DEBUG
    // =========================================================================

    @Test
    fun `RecordShopRate keeps the store name out of INFO+ but on the DEBUG firehose`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            engine.process(
                AppEffect.RecordShopRate(
                    itemsShopped = 24,
                    shopDurationMs = 18 * 60_000L,
                    storeName = "H-E-B",
                    jobId = "job-1",
                    taskId = "task-1",
                ),
            )
            runCurrent()

            // The shareable INFO stream carries the pace math but never the merchant name.
            tree.assertNoInfoPlusContains("H-E-B")
            tree.assertLevelContains(Log.INFO, "24 items")
            // The DEBUG firehose still names the store.
            tree.assertLevelContains(Log.DEBUG, "H-E-B")
        } finally {
            Timber.uproot(tree)
        }
    }
}
