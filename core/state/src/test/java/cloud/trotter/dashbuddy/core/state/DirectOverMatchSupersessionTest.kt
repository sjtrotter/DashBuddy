package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.offer.OfferKind
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #881 — a DIRECT offer landing over a MATCH display is an EXPECTED supersession.
 *
 * Uber presents Trip-Radar trips as focused cards that look like ordinary offers with a "Match"
 * CTA instead of "Accept", and the platform will send a real (direct) offer straight over the top
 * of one. Before this, that arrived as the generic #830 replace: an `OFFER_TIMEOUT("Replaced by
 * new offer")` plus a "(offer replaced)" chat card, i.e. the app narrating an anomaly for
 * something the platform does by design, while the dasher is mid-read of the card that is being
 * upgraded.
 *
 * The contract this pins:
 *  - SAME event type and SAME emission edge (the projector/replay oracles must not see a new
 *    OFFER_* shape) — only the payload `description` and the chat card change;
 *  - the quiet path is keyed on the KINDS, never on [Platform] (P8): any ruleset that parses
 *    `offerKind` gets it, and a null kind on either side keeps the pre-#881 behaviour verbatim;
 *  - every other replace shape (match→match, direct→direct, direct→match, unknown kinds) still
 *    emits the loud "Replaced by new offer" + bubble.
 *
 * Mutation-meaningful: dropping the kind test makes the quiet assertions fail; widening it to
 * "any kind change" makes the direct→match case fail.
 */
class DirectOverMatchSupersessionTest {

    private val effectMap = EffectMap()
    private val platform = Platform.Uber

    private fun parsedOffer(hash: String, key: String?, kind: OfferKind?) = ParsedOffer(
        offerHash = hash,
        presentationKey = key,
        offerKind = kind,
        payAmount = 6.44,
        distanceMiles = 8.5,
        timeToCompleteMinutes = 26L,
        orders = listOf(
            ParsedOrder(
                orderIndex = 0, orderType = OrderType.PICKUP, storeName = "Sonic",
                itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
            ),
        ),
    )

    private fun pending(hash: String, key: String?, kind: OfferKind?, presentedAt: Long = 1_000L) =
        PendingOffer(
            offerHash = hash,
            offerFields = ParsedFields.OfferFields(parsedOffer = parsedOffer(hash, key, kind)),
            presentedAt = presentedAt,
            evaluation = evalOf(),
            returnFlow = Flow.Idle,
            sourceRuleId = "uber.screen.offer",
        )

    private fun evalOf() = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 1.0, qualityLevel = OfferQuality.GOOD,
        payAmount = 6.44, fuelCostEstimate = 0.5, netPayAmount = 5.94, distanceMiles = 8.5,
        dollarsPerMile = 0.7, dollarsPerHour = 14.0, estimatedTimeMinutes = 26.0,
        itemCount = 1.0, merchantName = "Sonic",
    )

    private fun region(offer: PendingOffer) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        pendingOffers = listOf(offer),
    )

    private fun obs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "uber.screen.offer",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(parsedOffer = parsedOffer("x", "x", OfferKind.DIRECT)),
    )

    /** Diff one replace step: presented offer [from] is superseded by [to]. */
    private fun replaceEffects(from: OfferKind?, to: OfferKind?): List<AppEffect> = effectMap
        .diffOfferLifecycle(
            region(pending("H1", "P1", from, presentedAt = 1_000L)),
            region(pending("H2", "P2", to, presentedAt = 2_000L)),
            obs(2_000L),
            "sess-1",
        )

    private fun descriptionOf(fx: List<AppEffect>): String? = fx
        .filterIsInstance<AppEffect.LogEvent>()
        .single { it.event.type == AppEventType.OFFER_TIMEOUT }
        .let { (it.event.payload as OfferPayload).description }

    private fun hasReplacedBubble(fx: List<AppEffect>): Boolean =
        fx.any { it is AppEffect.UpdateBubble && it.text.contains("offer replaced") }

    @Test
    fun `direct over match resolves quietly - same event, softer description, no replaced bubble`() {
        val fx = replaceEffects(from = OfferKind.MATCH, to = OfferKind.DIRECT)

        assertTrue(
            "the match still RESOLVES — the ledger must not silently drop it",
            fx.any { it is AppEffect.LogEvent && it.event.type == AppEventType.OFFER_TIMEOUT },
        )
        assertEquals(
            "the expected direct-preempts-match description, not the anomalous replace copy",
            "Superseded by direct offer",
            descriptionOf(fx),
        )
        assertFalse("no '(offer replaced)' chat noise for an expected supersession", hasReplacedBubble(fx))
        // Everything else about the replace edge is untouched.
        assertEquals(
            "the superseded hash's heads-up is still cancelled",
            listOf("H1"),
            fx.filterIsInstance<AppEffect.CancelOfferNotification>().map { it.offerHash },
        )
        assertTrue("the direct offer is still evaluated", fx.any { it is AppEffect.EvaluateOffer })
        assertTrue("its expiry timer is still armed", fx.any { it is AppEffect.ScheduleTimeout })
    }

    @Test
    fun `match over match keeps the loud replace (a radar card genuinely replaced)`() {
        val fx = replaceEffects(from = OfferKind.MATCH, to = OfferKind.MATCH)
        assertEquals("Replaced by new offer", descriptionOf(fx))
        assertTrue(hasReplacedBubble(fx))
    }

    @Test
    fun `direct over direct keeps the loud replace (the pre-881 DoorDash-shaped path)`() {
        val fx = replaceEffects(from = OfferKind.DIRECT, to = OfferKind.DIRECT)
        assertEquals("Replaced by new offer", descriptionOf(fx))
        assertTrue(hasReplacedBubble(fx))
    }

    @Test
    fun `match over direct is NOT quiet - only the direct-preempts-match direction is expected`() {
        val fx = replaceEffects(from = OfferKind.DIRECT, to = OfferKind.MATCH)
        assertEquals("Replaced by new offer", descriptionOf(fx))
        assertTrue(hasReplacedBubble(fx))
    }

    @Test
    fun `a platform with no offerKind concept is byte-identical to pre-881`() {
        val fx = replaceEffects(from = null, to = null)
        assertEquals("Replaced by new offer", descriptionOf(fx))
        assertTrue(hasReplacedBubble(fx))
    }

    @Test
    fun `a half-known pair stays loud (fail toward the pre-881 narration)`() {
        assertEquals("Replaced by new offer", descriptionOf(replaceEffects(from = OfferKind.MATCH, to = null)))
        assertEquals("Replaced by new offer", descriptionOf(replaceEffects(from = null, to = OfferKind.DIRECT)))
    }
}
