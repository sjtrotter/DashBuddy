package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the #594 decline-commit latch — since #438 B3 it lives in
 * [OfferLifecycle]'s `latchOfferClick` on the platform-owned `pendingOffers` (was
 * `FlowRegionStepper.handleOfferClick`).
 *
 * A DECLINE-intent click can only come from the confirm sheet (the `decline_offer` click rule is
 * screen-scoped to `offer_popup_confirm_decline`), i.e. the server-side commit. Once observed, the
 * offer's outcome is DECLINED for good — a later "Review offer"→Accept race must not un-decline it.
 * `lastClickIntent` keeps recording the literal last click (forensics); `declineCommittedAt` is the
 * latch that decides the outcome (resolved in [EffectMap.resolveOfferOutcome]).
 */
class FlowRegionDeclineLatchTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()

    private val pendingOffer = PendingOffer(
        offerHash = "hash-bk",
        offerFields = ParsedFields.OfferFields(parsedOffer = ParsedOffer(offerHash = "hash-bk", payAmount = 6.25)),
        presentedAt = 1_000L,
        returnFlow = Flow.Idle,
    )

    private val offerRegion = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("s", startedAt = 100L),
        pendingOffers = listOf(pendingOffer),
        lastActedFlow = Flow.OfferPresented,
    )

    private val offerFlow = FlowRegion(flow = Flow.OfferPresented, activePlatform = Platform.DoorDash)

    private fun click(intent: String, timestamp: Long) = Observation.Click(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.click.$intent",
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.ClickFields(intent = intent),
    )

    private fun step(region: PlatformRegion, click: Observation.Click): PlatformRegion =
        stepper.step(region, offerFlow, offerFlow, click, policy)

    @Test
    fun `a DECLINE-intent click sets declineCommittedAt to the click timestamp`() {
        val next = step(offerRegion, click(OfferIntent.DECLINE, timestamp = 2_000L))
        assertEquals(2_000L, next.presentedOffer()?.declineCommittedAt)
        assertEquals(OfferIntent.DECLINE, next.presentedOffer()?.lastClickIntent)
    }

    @Test
    fun `a repeat DECLINE click does not move the latch (first commit wins)`() {
        val first = step(offerRegion, click(OfferIntent.DECLINE, timestamp = 2_000L))
        val second = step(first, click(OfferIntent.DECLINE, timestamp = 2_500L))
        assertEquals("the latch keeps the first commit timestamp", 2_000L, second.presentedOffer()?.declineCommittedAt)
    }

    @Test
    fun `an ACCEPT click after a committed decline moves lastClickIntent but not the latch`() {
        val declined = step(offerRegion, click(OfferIntent.DECLINE, timestamp = 2_000L))
        val raced = step(declined, click(OfferIntent.ACCEPT, timestamp = 3_000L))
        assertEquals("the literal last click is recorded honestly", OfferIntent.ACCEPT, raced.presentedOffer()?.lastClickIntent)
        assertEquals("the decline latch is unchanged by the later Accept", 2_000L, raced.presentedOffer()?.declineCommittedAt)
    }

    @Test
    fun `an initial_decline click (the offer screen's first decline) sets neither`() {
        val next = step(offerRegion, click("initial_decline", timestamp = 2_000L))
        assertNull("initial_decline does not commit the decline", next.presentedOffer()?.declineCommittedAt)
        assertEquals("lastClickIntent still records the literal click", "initial_decline", next.presentedOffer()?.lastClickIntent)
    }

    @Test
    fun `an ACCEPT click with no prior decline leaves the latch null`() {
        val next = step(offerRegion, click(OfferIntent.ACCEPT, timestamp = 2_000L))
        assertNull("a plain accept never sets the decline latch", next.presentedOffer()?.declineCommittedAt)
        assertEquals(OfferIntent.ACCEPT, next.presentedOffer()?.lastClickIntent)
    }
}
