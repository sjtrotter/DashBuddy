package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the #594 decline-commit latch in [FlowRegionStepper.handleOfferClick].
 *
 * A DECLINE-intent click can only come from the confirm sheet (the `decline_offer` click rule is
 * screen-scoped to `offer_popup_confirm_decline`), i.e. the server-side commit. Once observed, the
 * offer's outcome is DECLINED for good — a later "Review offer"→Accept race must not un-decline it.
 * `lastClickIntent` keeps recording the literal last click (forensics); `declineCommittedAt` is the
 * latch that decides the outcome (resolved in [EffectMap.resolveOfferOutcome]).
 */
class FlowRegionDeclineLatchTest {

    private val stepper = FlowRegionStepper()

    private val pendingOffer = PendingOffer(
        offerHash = "hash-bk",
        offerFields = ParsedFields.OfferFields(parsedOffer = ParsedOffer(offerHash = "hash-bk", payAmount = 6.25)),
        presentedAt = 1_000L,
        returnFlow = Flow.Idle,
    )

    private val offerPresented = FlowRegion(
        flow = Flow.OfferPresented,
        pendingOffer = pendingOffer,
        lastObservedAt = 1_000L,
    )

    private fun click(intent: String, timestamp: Long) = Observation.Click(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.click.$intent",
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.ClickFields(intent = intent),
    )

    @Test
    fun `a DECLINE-intent click sets declineCommittedAt to the click timestamp`() {
        val next = stepper.step(offerPresented, click(OfferIntent.DECLINE, timestamp = 2_000L))
        assertEquals(2_000L, next.pendingOffer?.declineCommittedAt)
        assertEquals(OfferIntent.DECLINE, next.pendingOffer?.lastClickIntent)
    }

    @Test
    fun `a repeat DECLINE click does not move the latch (first commit wins)`() {
        val first = stepper.step(offerPresented, click(OfferIntent.DECLINE, timestamp = 2_000L))
        val second = stepper.step(first, click(OfferIntent.DECLINE, timestamp = 2_500L))
        assertEquals("the latch keeps the first commit timestamp", 2_000L, second.pendingOffer?.declineCommittedAt)
    }

    @Test
    fun `an ACCEPT click after a committed decline moves lastClickIntent but not the latch`() {
        val declined = stepper.step(offerPresented, click(OfferIntent.DECLINE, timestamp = 2_000L))
        val raced = stepper.step(declined, click(OfferIntent.ACCEPT, timestamp = 3_000L))
        assertEquals("the literal last click is recorded honestly", OfferIntent.ACCEPT, raced.pendingOffer?.lastClickIntent)
        assertEquals("the decline latch is unchanged by the later Accept", 2_000L, raced.pendingOffer?.declineCommittedAt)
    }

    @Test
    fun `an initial_decline click (the offer screen's first decline) sets neither`() {
        // The offer-screen first decline is a separate intent (not decline_offer / accept_offer);
        // it must not latch and must not record an accept/decline outcome — it falls through as today.
        val next = stepper.step(offerPresented, click("initial_decline", timestamp = 2_000L))
        assertNull("initial_decline does not commit the decline", next.pendingOffer?.declineCommittedAt)
        assertEquals("lastClickIntent still records the literal click", "initial_decline", next.pendingOffer?.lastClickIntent)
    }

    @Test
    fun `an ACCEPT click with no prior decline leaves the latch null`() {
        val next = stepper.step(offerPresented, click(OfferIntent.ACCEPT, timestamp = 2_000L))
        assertNull("a plain accept never sets the decline latch", next.pendingOffer?.declineCommittedAt)
        assertEquals(OfferIntent.ACCEPT, next.pendingOffer?.lastClickIntent)
    }
}
