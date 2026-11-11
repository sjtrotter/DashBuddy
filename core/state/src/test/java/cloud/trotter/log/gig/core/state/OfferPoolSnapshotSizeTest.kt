package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.evaluation.OfferAction
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluation
import cloud.trotter.log.gig.domain.evaluation.OfferQuality
import cloud.trotter.log.gig.domain.model.offer.OfferBadge
import cloud.trotter.log.gig.domain.model.offer.OfferKind
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.PooledOffer
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 S2 (OQ8): every offer of the shift that is not linked rides every snapshot, so the pool must stay small. A
 * region holding 60 pool entries — each a two-order card with every field set at a realistic length (invented stores
 * and addresses) and a fully populated evaluation — encodes under 256 KB. `:core:state` cannot parse a committed card,
 * so the offer is synthetic. The measured size is printed for the field item.
 */
class OfferPoolSnapshotSizeTest {

    private fun order(i: Int, store: String) = ParsedOrder(
        orderIndex = i, orderType = OrderType.PICKUP, storeName = store, itemCount = 4, isItemCountEstimated = false,
        badges = emptySet(),
    )

    private fun entry(n: Int): PooledOffer {
        val hash = "%064x".format(n.toLong() * 7_919L)
        val parsed = ParsedOffer(
            offerHash = hash,
            presentationKey = "%064x".format(n.toLong() * 104_729L),
            assignmentIdHash = "%064x".format(n.toLong() * 1_299_709L),
            itemCount = 8,
            payAmount = 14.75,
            payTextRaw = "$14.75",
            distanceMiles = 8.4,
            distanceTextRaw = "8.4 mi",
            dueByTimeText = "Deliver by 7:42 PM",
            dueByTimeMillis = 1_700_000_000_000L + n,
            timeToCompleteMinutes = 38L,
            stopCount = 4,
            dropoffCount = 2,
            badges = setOf(OfferBadge.entries.first()),
            initialCountdownSeconds = 60,
            orders = listOf(order(0, "Invented Burger Barn (Loop 410)"), order(1, "Sample Noodle House #212")),
            offerKind = OfferKind.DIRECT,
            displayStoreName = "Invented Burger Barn (Loop 410) & Sample Noodle House #212",
            rawExtractedTexts = "Invented Burger Barn (Loop 410) | Sample Noodle House #212 | 1234 Example Parkway | " +
                "5678 Placeholder Avenue | Deliver by 7:42 PM | 8.4 mi | $14.75 | Accept | Decline",
            offerScope = OfferScope.STANDALONE,
        )
        val evaluation = OfferEvaluation(
            action = OfferAction.ACCEPT, score = 71.5, qualityLevel = OfferQuality.GOOD, payAmount = 14.75,
            fuelCostEstimate = 1.42, nonFuelCostEstimate = 1.18, totalOperatingCost = 2.60, operatingCostPerMile = 0.31,
            netPayAmount = 12.15, distanceMiles = 8.4, dollarsPerMile = 1.45, dollarsPerHour = 19.18,
            estimatedTimeMinutes = 38.0, itemCount = 8.0,
            merchantName = "Invented Burger Barn (Loop 410) & Sample Noodle House #212",
            warnings = listOf("Two pickups: the second store adds a leg", "Shop items counted as units"),
        )
        val offer = PendingOffer(
            offerHash = hash, offerFields = ParsedFields.OfferFields(parsedOffer = parsed), presentedAt = 1_000L * n,
            evaluation = evaluation, returnFlow = Flow.Idle, sourceRuleId = "doordash.screen.offer_popup",
            declineSheetSeenAt = 1_000L * n + 5, countdownExpiresAt = 1_000L * n + 60_000, instanceId = "doordash:${1_000L * n}",
            lastCardFrameAt = 1_000L * n + 3, firstEvalLandedAt = 1_000L * n + 1,
        )
        return PooledOffer(offer, 1_000L * n + 9, PoolEntryVia.FRAME, "session-doordash-1700000000000-0",
            setOf("invented burger barn", "sample noodle house"), ambiguousJobIds = listOf("job-doordash-1700000000000-12"))
    }

    @Test fun `a 60-entry pool encodes under 256 KB`() {
        val region = PF.online().regions.platforms.getValue(PF.platform).copy(offerPool = (1..60).map(::entry))
        val state = PF.online(region)
        val bytes = StateJson.encodeToString(state).toByteArray(Charsets.UTF_8).size
        println("#1119 OfferPoolSnapshotSizeTest: 60 pool entries encode to $bytes bytes")
        assertTrue("$bytes bytes", bytes < 256 * 1024)
    }
}
