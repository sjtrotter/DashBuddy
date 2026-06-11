package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload

/**
 * #345 — eval/diff correctness pack:
 * 1. An offer evaluation must land only on the offer it was computed FOR (hash-correlated).
 * 2. Task closures committed by non-flow observations (e.g. a routed timeout, #342) must
 *    still emit DELIVERY_CONFIRMED.
 * 3. Effect gates fail CLOSED: an absent/unextractable field never satisfies a gate.
 */
class EvalDiffCorrectnessTest {

    private val flowStepper = FlowRegionStepper()
    private val effectMap = EffectMap()

    private val machine = StateMachine(
        flowStepper = FlowRegionStepper(),
        platformStepper = PlatformRegionStepper(),
        crossPlatformStepper = CrossPlatformRegionStepper(),
        transitionPolicy = TransitionPolicy(),
        effectMap = effectMap,
    )

    // ---- helpers ----

    private fun offer(hash: String) = PendingOffer(
        offerHash = hash,
        offerFields = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(offerHash = hash, payAmount = 7.5, distanceMiles = 3.0),
        ),
        presentedAt = 1_000L,
        returnFlow = Flow.Idle,
    )

    private fun evaluation() = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = 75.0,
        qualityLevel = OfferQuality.GOOD,
        payAmount = 7.50,
        fuelCostEstimate = 0.5,
        netPayAmount = 6.50,
        distanceMiles = 3.0,
        dollarsPerMile = 2.17,
        dollarsPerHour = 22.0,
        estimatedTimeMinutes = 18.0,
        itemCount = 1.0,
        merchantName = "Wendy's",
    )

    private fun loopback(evalForHash: String?) = Observation.Loopback(
        timestamp = 2_000L,
        effect = "offer_evaluated",
        payload = ObservationPayload.EvaluationResult(
            action = OfferAction.ACCEPT.name,
            offerHash = evalForHash,
            evaluation = evaluation(),
        ),
    )

    // ---- 1. eval correlation ----

    @Test
    fun `evaluation for a replaced offer does not land on the current offer`() {
        val region = FlowRegion(flow = Flow.OfferPresented, pendingOffer = offer("offer-B"))

        val next = flowStepper.step(region, loopback(evalForHash = "offer-A"))

        assertNull(next.pendingOffer?.evaluation)
    }

    @Test
    fun `evaluation with the matching hash lands`() {
        val region = FlowRegion(flow = Flow.OfferPresented, pendingOffer = offer("offer-A"))

        val next = flowStepper.step(region, loopback(evalForHash = "offer-A"))

        assertNotNull(next.pendingOffer?.evaluation)
    }

    @Test
    fun `evaluation with no hash lands (legacy replay stubs)`() {
        val region = FlowRegion(flow = Flow.OfferPresented, pendingOffer = offer("offer-A"))

        val next = flowStepper.step(region, loopback(evalForHash = null))

        assertNotNull(next.pendingOffer?.evaluation)
    }

    // ---- 2. task closure on a non-flow observation ----

    @Test
    fun `task-retire committed by a routed timeout emits DELIVERY_CONFIRMED`() {
        val dropoff = Task(
            taskId = "t1", jobId = "j1", phase = TaskPhase.DROPOFF,
            storeName = "HEB", startedAt = 300L,
        )
        val region = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("sess-1", startedAt = 100L),
            activeJob = Job("j1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
            activeTask = dropoff,
            pendingDestructive = PendingDestructive(
                DestructiveKind.TASK_RETIRE, since = 800L, deadline = 1_000L,
            ),
        )
        val prev = AppState(
            regions = Regions(platforms = mapOf(Platform.DoorDash to region)),
            timestamp = 900L,
        )
        val obs = Observation.Timeout(
            timestamp = 2_000L,
            type = TimeoutType.SETTLE_UI,
            targetPlatform = Platform.DoorDash,
        )

        val transition = machine.step(prev, obs)

        assertNull(transition.newState.regions.platforms.getValue(Platform.DoorDash).activeTask)
        val confirmed = transition.effects
            .filterIsInstance<AppEffect.LogEvent>()
            .any { it.event.type == AppEventType.DELIVERY_CONFIRMED }
        assertTrue("DELIVERY_CONFIRMED must be emitted for a timeout-committed retire", confirmed)
    }

    // ---- 3. gates fail closed ----

    private fun screenWithGatedEffect(gate: ParsedFieldsGate, parsed: ParsedFields) =
        Observation.Screen(
            timestamp = 1_500L,
            captureId = null,
            ruleId = "doordash.screen.test",
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = parsed,
            effects = listOf(
                RequestedEffect(
                    verb = EffectVerb.BUBBLE,
                    args = mapOf("text" to "gated"),
                    onlyIf = gate,
                    ruleId = "doordash.screen.test",
                ),
            ),
        )

    private fun ruleEffectsFor(obs: Observation): List<AppEffect.RequestEffect> {
        val state = AppState(regions = Regions(), timestamp = 1_000L)
        return effectMap.diff(state, state, obs).filterIsInstance<AppEffect.RequestEffect>()
    }

    @Test
    fun `FieldNotEquals does not fire when the field is absent`() {
        val obs = screenWithGatedEffect(
            gate = ParsedFieldsGate.FieldNotEquals("someField", "someValue"),
            parsed = ParsedFields.None,
        )

        assertTrue(ruleEffectsFor(obs).isEmpty())
    }

    @Test
    fun `gates still fire on a present satisfying field`() {
        val parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(offerHash = "h", payAmount = 7.5),
        )
        val obs = screenWithGatedEffect(
            gate = ParsedFieldsGate.FieldNotNull("parsedOffer"),
            parsed = parsed,
        )

        assertFalse(ruleEffectsFor(obs).isEmpty())
    }
}
