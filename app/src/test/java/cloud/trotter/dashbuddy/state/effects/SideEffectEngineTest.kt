package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

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

    private val appEventRepo: AppEventRepo = mock()
    private val odometerEffectHandler: OdometerEffectHandler = mock()
    private val tipEffectHandler: TipEffectHandler = mock()
    private val bubbleManager: BubbleManager = mock()
    private val offerEvaluator: OfferEvaluator = mock()
    private val strategyRepository: StrategyRepository = mock()
    private val screenShotHandler: ScreenShotHandler = mock()
    private val uiInteractionHandler: UiInteractionHandler = mock()
    private val effectsFiredDao: EffectsFiredDao = mock()
    private val ttsEffectHandler: TtsEffectHandler = mock()

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
        defaultDispatcher = defaultDispatcher,
    )

    private fun logEvent(type: AppEventType, occurredAt: Long) = AppEffect.LogEvent(
        AppEventEntity(
            aggregateId = "sess-1",
            eventType = type,
            eventPayload = "{}",
            occurredAt = occurredAt,
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
    // #341 — recovery suppression: PerformOfferAction must never replay a click
    // =========================================================================

    @Test
    fun `PerformOfferAction is suppressed during recovery and executes live`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val effect = AppEffect.PerformOfferAction(OfferAction.ACCEPT, Platform.DoorDash)

        engine.process(effect, recovering = true)
        runCurrent()
        verify(uiInteractionHandler, never()).performClick(any(), any())

        engine.process(effect, recovering = false)
        runCurrent()
        verify(uiInteractionHandler, times(1)).performClick(any(), any())
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
    fun `a keyed effect completes its durable work before markFired, with the real cv`() = runTest {
        val engine = buildEngine(StandardTestDispatcher(testScheduler))
        val effect = logEvent(AppEventType.OFFER_RECEIVED, occurredAt = 1_000L)

        engine.process(effect, correlationVersion = 42L)
        runCurrent()

        val ordered = inOrder(appEventRepo, effectsFiredDao)
        runBlocking {
            ordered.verify(appEventRepo).insert(effect.event)
            ordered.verify(effectsFiredDao).markFired(any())
        }
        val captor = argumentCaptor<EffectsFiredEntity>()
        verifyBlocking(effectsFiredDao) { markFired(captor.capture()) }
        assertEquals(effect.effectKey, captor.firstValue.effectKey)
        assertEquals(42L, captor.firstValue.correlationVersion)
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
}
