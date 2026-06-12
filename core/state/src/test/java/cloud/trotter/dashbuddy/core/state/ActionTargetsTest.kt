package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.action.TargetExpectation
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #425 — action targets ride observations into [FlowRegion.pendingOffer], and
 * the app-owned [TargetExpectation] verification logic holds the line the
 * ruleset cannot cross.
 */
class ActionTargetsTest {

    private val stepper = FlowRegionStepper()

    private fun nodeRef(id: String, text: String? = null) = NodeRef(
        viewIdSuffix = id,
        text = text,
        classNameHint = "android.widget.Button",
        boundsInScreen = BoundingBox(0, 0, 100, 50),
        pathFingerprint = "fp/$id",
    )

    private fun offerObs(
        hash: String,
        targets: Map<String, NodeRef> = emptyMap(),
        ruleId: String = "doordash.screen.offer_popup",
        timestamp: Long = 1000L,
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.OfferPresented,
        modeHint = null,
        parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(offerHash = hash, payAmount = 7.5, distanceMiles = 3.2),
        ),
        targets = targets,
    )

    // =========================================================================
    // FlowRegionStepper — targets land on PendingOffer
    // =========================================================================

    @Test
    fun `offer creation copies the observation's targets and source rule onto PendingOffer`() {
        val targets = mapOf(
            "acceptButton" to nodeRef("accept_button"),
            "declineButton" to nodeRef("decline_button"),
        )
        val region = stepper.step(FlowRegion(), offerObs("h1", targets))
        val offer = region.pendingOffer!!
        assertEquals(targets, offer.targets)
        assertEquals("doordash.screen.offer_popup", offer.sourceRuleId)
    }

    @Test
    fun `same-hash re-observation refreshes targets with the newer fingerprints`() {
        val first = stepper.step(
            FlowRegion(),
            offerObs("h1", mapOf("acceptButton" to nodeRef("accept_button"))),
        )
        val moved = nodeRef("accept_button").copy(boundsInScreen = BoundingBox(0, 200, 100, 250))
        val second = stepper.step(
            first,
            offerObs("h1", mapOf("acceptButton" to moved), timestamp = 2000L),
        )
        assertEquals(moved, second.pendingOffer!!.targets["acceptButton"])
    }

    @Test
    fun `same-hash re-observation with NO targets keeps the previously bound ones`() {
        val first = stepper.step(
            FlowRegion(),
            offerObs("h1", mapOf("acceptButton" to nodeRef("accept_button"))),
        )
        val second = stepper.step(first, offerObs("h1", targets = emptyMap(), timestamp = 2000L))
        assertEquals("accept_button", second.pendingOffer!!.targets["acceptButton"]?.viewIdSuffix)
    }

    @Test
    fun `a replacing offer gets ITS OWN targets - never the old offer's`() {
        val first = stepper.step(
            FlowRegion(),
            offerObs("h1", mapOf("acceptButton" to nodeRef("old_accept"))),
        )
        val second = stepper.step(first, offerObs("h2", targets = emptyMap(), timestamp = 2000L))
        assertTrue(
            "Replaced offer must not inherit stale targets",
            second.pendingOffer!!.targets.isEmpty(),
        )
    }

    // =========================================================================
    // TargetExpectation — the app-owned tap-time anchor
    // =========================================================================

    @Test
    fun `ACCEPT expectation matches Accept and Add to route labels`() {
        val v = RuleAction.ACCEPT_OFFER.verification
        assertTrue(v.matchesLabels(listOf("Accept")))
        assertTrue(v.matchesLabels(listOf("32", "Accept"))) // countdown sibling text
        assertTrue(v.matchesLabels(listOf("Add to route")))
        assertFalse("Decline must never satisfy ACCEPT", v.matchesLabels(listOf("Decline")))
        assertFalse(v.matchesLabels(emptyList()))
    }

    @Test
    fun `DECLINE expectation matches Decline and rejects Accept`() {
        val v = RuleAction.DECLINE_OFFER.verification
        assertTrue(v.matchesLabels(listOf("Decline")))
        assertTrue(v.matchesLabels(listOf("Decline offer")))
        assertFalse("Accept must never satisfy DECLINE", v.matchesLabels(listOf("Accept")))
        // 'Accepted' contains no standalone 'decline' token — and crucially a
        // malicious bind pointing decline at the Accept button fails here.
        assertFalse(v.matchesLabels(listOf("Accepted")))
    }

    @Test
    fun `label-free EXPAND expectation passes anything - package scope is its bar`() {
        val v = RuleAction.EXPAND_EARNINGS.verification
        assertTrue(v.matchesLabels(emptyList()))
        assertTrue(v.matchesLabels(listOf("whatever")))
    }

    @Test
    fun `well-known bind names map one-to-one onto actions`() {
        assertEquals(RuleAction.ACCEPT_OFFER, RuleAction.byTargetBindName["acceptButton"])
        assertEquals(RuleAction.DECLINE_OFFER, RuleAction.byTargetBindName["declineButton"])
        assertEquals(RuleAction.EXPAND_EARNINGS, RuleAction.byTargetBindName["expandButton"])
        assertEquals(RuleAction.entries.size, RuleAction.byTargetBindName.size)
    }
}
