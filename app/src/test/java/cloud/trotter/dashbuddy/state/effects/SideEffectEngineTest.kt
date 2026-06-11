package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.state.TimeoutEvent
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Behavior tests for the SideEffectEngine execution edge (#341):
 * crash isolation, recovery suppression of external effects, and timer lifecycle.
 *
 * All engine coroutines run on the test scheduler (the engine inherits the caller
 * scope's dispatcher and takes an injected IO dispatcher), so time is virtual.
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

    private fun buildEngine(ioDispatcher: CoroutineDispatcher) = SideEffectEngine(
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
        ioDispatcher = ioDispatcher,
    )

    /** Mirrors production: StateManagerV2's scope is SupervisorJob + dispatcher, NO exception handler. */
    private fun TestScope.engineScope(dispatcher: CoroutineDispatcher) =
        CoroutineScope(SupervisorJob() + dispatcher)

    // =========================================================================
    // Crash isolation
    // =========================================================================

    @Test
    fun `a throwing handler is isolated and the engine keeps processing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = buildEngine(dispatcher)
        val scope = engineScope(dispatcher)
        val eval: OfferEvaluation = mock()
        doThrow(RuntimeException("boom")).whenever(ttsEffectHandler).speakOffer(any())

        // Pre-#341 this exception escaped to the scope (no handler) and killed the process;
        // in runTest it would surface as an uncaught-exception test failure.
        engine.process(AppEffect.SpeakOffer(eval), scope, recovering = false)
        runCurrent()

        // Engine is still alive: the next effect executes normally.
        engine.process(AppEffect.SpeakOffer(eval), scope, recovering = false)
        runCurrent()
        verify(ttsEffectHandler, times(2)).speakOffer(any())
    }

    // =========================================================================
    // Recovery suppression — PerformOfferAction must never replay a click
    // =========================================================================

    @Test
    fun `PerformOfferAction is suppressed during recovery and executes live`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = buildEngine(dispatcher)
        val scope = engineScope(dispatcher)
        val effect = AppEffect.PerformOfferAction(OfferAction.ACCEPT, Platform.DoorDash)

        engine.process(effect, scope, recovering = true)
        runCurrent()
        verify(uiInteractionHandler, never()).performClick(any(), any())

        engine.process(effect, scope, recovering = false)
        runCurrent()
        verify(uiInteractionHandler, times(1)).performClick(any(), any())
    }

    // =========================================================================
    // Timer lifecycle
    // =========================================================================

    @Test
    fun `an expired timer cannot untrack a replacement of the same type`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = buildEngine(dispatcher)
        val scope = engineScope(dispatcher)
        val fired = mutableListOf<TimeoutEvent>()
        backgroundScope.launch(dispatcher) {
            engine.events.collect { if (it is TimeoutEvent) fired.add(it) }
        }
        runCurrent()

        // First timer expires and self-removes.
        engine.process(AppEffect.ScheduleTimeout(durationMs = 10, type = TimeoutType.SETTLE_UI), scope, false)
        advanceTimeBy(11)
        runCurrent()
        assertEquals(1, fired.size)

        // A replacement scheduled afterwards must remain tracked: cancelling it works.
        engine.process(AppEffect.ScheduleTimeout(durationMs = 100, type = TimeoutType.SETTLE_UI), scope, false)
        runCurrent()
        engine.process(AppEffect.CancelTimeout(TimeoutType.SETTLE_UI), scope, false)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fired.size) // replacement was cancelled, not orphaned
    }

    @Test
    fun `rescheduling a timer type replaces the previous timer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = buildEngine(dispatcher)
        val scope = engineScope(dispatcher)
        val fired = mutableListOf<TimeoutEvent>()
        backgroundScope.launch(dispatcher) {
            engine.events.collect { if (it is TimeoutEvent) fired.add(it) }
        }
        runCurrent()

        engine.process(AppEffect.ScheduleTimeout(durationMs = 100, type = TimeoutType.SETTLE_UI), scope, false)
        runCurrent()
        engine.process(AppEffect.ScheduleTimeout(durationMs = 50, type = TimeoutType.SETTLE_UI), scope, false)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, fired.size) // only the replacement fired
    }

    @Test
    fun `timers still arm during recovery (loopback, not external)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = buildEngine(dispatcher)
        val scope = engineScope(dispatcher)
        val fired = mutableListOf<TimeoutEvent>()
        backgroundScope.launch(dispatcher) {
            engine.events.collect { if (it is TimeoutEvent) fired.add(it) }
        }
        runCurrent()

        engine.process(AppEffect.ScheduleTimeout(durationMs = 10, type = TimeoutType.SESSION_PAUSED_SAFETY), scope, recovering = true)
        advanceTimeBy(11)
        runCurrent()
        assertEquals(1, fired.size)
    }
}
