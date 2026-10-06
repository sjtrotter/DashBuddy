package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArrivalCorrectionTest {
    private val economy = UserEconomy(learnedShopItemsPerMinute = 0.79, shopRateSampleCount = 10)
    private val accepted = AcceptedOfferEconomics(
        offerHash = "offer", netPay = 30.0, estMinutes = 20.0 + 64 / 0.79,
        handlingMinutes = 64 / 0.79, offerUnitCount = 64, isShop = true, acceptedAt = 1L,
        quotedItemCount = 64,
        pricedShopItemsPerMinute = economy.effectiveShopItemsPerMinute,
        pricedBasePickupMinutes = economy.basePickupMinutes,
    )
    private val pickup = Task(
        taskId = "pickup", jobId = "job", phase = TaskPhase.PICKUP,
        activity = PickupActivity.SHOPPING, startedAt = 1L,
    )

    @Test fun `64 quoted units to 30 observed items swaps handling and preserves drive`() {
        val corrected = ArrivalCorrection.compute(accepted, "pickup", 30, economy, 42L)!!
        assertEquals(30 / 0.79, corrected.correctedHandlingMinutes, 0.000001)
        assertEquals(20 + 30 / 0.79, corrected.correctedEstMinutes, 0.000001)
        assertEquals(30 / (corrected.correctedEstMinutes / 60), corrected.correctedDollarsPerHour!!, 0.000001)
        assertEquals(42L, corrected.computedAt)
        assertEquals("offer", corrected.offerHash)
        assertEquals("pickup", corrected.taskId)
        assertEquals(accepted.quotedItemCount, corrected.quotedItems)
        assertEquals(20 + 64 / 0.79, accepted.estMinutes!!, 0.000001)
    }

    @Test fun `small shop is floored and non-shop legs retain their base`() {
        val withLegs = accepted.copy(nonShopLegs = 2)
        val corrected = ArrivalCorrection.compute(withLegs, "pickup", 1, economy, 2L)!!
        assertEquals(3 * economy.basePickupMinutes, corrected.correctedHandlingMinutes, 0.000001)
        assertEquals(20 + 3 * economy.basePickupMinutes, corrected.correctedEstMinutes, 0.000001)
    }

    @Test fun `accept-time pace and base win over later economy changes`() {
        val priced = accepted.copy(pricedShopItemsPerMinute = 2.0, pricedBasePickupMinutes = 7.0, nonShopLegs = 2)
        val changed = economy.copy(learnedShopItemsPerMinute = 0.5, basePickupMinutes = 25.0)
        for (observed in listOf(1, 30)) {
            val original = ArrivalCorrection.compute(priced, "pickup", observed, economy, 2L)!!
            val corrected = ArrivalCorrection.compute(priced, "pickup", observed, changed, 2L)!!
            assertEquals(maxOf(observed / 2.0, 7.0) + 2 * 7.0, corrected.correctedHandlingMinutes, 0.000001)
            assertEquals(original, corrected)
        }
    }

    @Test fun `pre-Phase-2 null pricing inputs fall back to the economy`() {
        val legacy = accepted.copy(pricedShopItemsPerMinute = null, pricedBasePickupMinutes = null, nonShopLegs = 2)
        val changed = economy.copy(learnedShopItemsPerMinute = 0.5, basePickupMinutes = 12.0)
        for (observed in listOf(1, 30)) {
            val corrected = ArrivalCorrection.compute(legacy, "pickup", observed, changed, 2L)!!
            val handling = maxOf(observed / changed.effectiveShopItemsPerMinute, changed.basePickupMinutes) +
                2 * changed.basePickupMinutes
            assertEquals(handling, corrected.correctedHandlingMinutes, 0.000001)
            assertEquals(20 + handling, corrected.correctedEstMinutes, 0.000001)
        }
    }

    @Test fun `quoted count follows the accepted card including an unknown count`() {
        for (count in listOf(64, 30, null)) {
            val quote = accepted.copy(quotedItemCount = count)
            assertEquals(quote.quotedItemCount, ArrivalCorrection.compute(quote, "pickup", 30, economy, 2L)!!.quotedItems)
        }
    }

    @Test fun `legacy estimates and invalid totals produce no correction`() {
        assertNull(ArrivalCorrection.compute(accepted.copy(handlingMinutes = null), "pickup", 30, economy, 2L))
        assertNull(ArrivalCorrection.compute(accepted.copy(estMinutes = null), "pickup", 30, economy, 2L))
        assertNull(ArrivalCorrection.compute(accepted.copy(estMinutes = 1.0, handlingMinutes = 100.0), "pickup", 1, economy, 2L))
        assertNull(ArrivalCorrection.compute(accepted, "pickup", 0, economy, 2L))
        assertNull(ArrivalCorrection.compute(accepted.copy(netPay = null), "pickup", 30, economy, 2L)!!.correctedDollarsPerHour)
    }

    @Test fun `counts require a coherent nonnegative pair with a positive bounded sum`() {
        fun pair(remaining: Int?, shopped: Int?) = ParsedFields.TaskFields(
            phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.ARRIVED,
            itemsRemaining = remaining, itemsShopped = shopped,
        )
        assertEquals(30, ArrivalCorrection.observedItems(pair(28, 2)))
        assertEquals(30, ArrivalCorrection.observedItems(pair(0, 30)))
        for (fields in listOf(pair(30, null), pair(null, 30), pair(0, 0), pair(-1, 30), pair(30, -1), pair(Int.MAX_VALUE, 1))) {
            assertNull(ArrivalCorrection.observedItems(fields))
        }
    }

    @Test fun `only one accepted offer and one shopping pickup is eligible`() {
        val job = Job("job", emptyList(), "offer", listOf(accepted), listOf(pickup), startedAt = 1L)
        assertTrue(ArrivalCorrection.isEligible(job))
        assertFalse(ArrivalCorrection.isEligible(job.copy(acceptedOffers = listOf(accepted, accepted.copy(offerHash = "addon")))))
        assertFalse(ArrivalCorrection.isEligible(job.copy(tasks = listOf(pickup, pickup.copy(taskId = "other")))))
        assertFalse(ArrivalCorrection.isEligible(job.copy(tasks = listOf(pickup.copy(phase = TaskPhase.DROPOFF)))))
        assertTrue(ArrivalCorrection.isEligible(job.copy(tasks = listOf(pickup.copy(activity = PickupActivity.CONFIRMED)))))
        assertFalse(ArrivalCorrection.isEligible(job.copy(acceptedOffers = listOf(accepted.copy(isShop = false)))))
        assertFalse(ArrivalCorrection.isEligible(job.copy(tasks = listOf(pickup, pickup.copy(taskId = "unresolved", activity = null)))))
        assertFalse(ArrivalCorrection.isEligible(job.copy(acceptedOffers = emptyList())))
    }

    @Test fun `arrival payload serializes through the journal sealed hierarchy`() {
        val payload: ObservationPayload = ObservationPayload.ArrivalEstimated(
            "job", ArrivalCorrection.compute(accepted, "pickup", 30, economy, 42L)!!,
        )
        val encoded = Json.encodeToString(payload)
        assertTrue(encoded.contains("arrivalEstimated"))
        assertEquals(payload, Json.decodeFromString<ObservationPayload>(encoded))
    }

    @Test fun `Phase 1 units conversion is not reapplied to observed items`() {
        val economy = this.economy.copy(itemsPerUnitRatioSeed = 0.5)
        val parsed = ParsedOffer(
            offerHash = "units", payAmount = 30.0, distanceMiles = 8.0,
            itemCount = 64, itemCountIsUnits = true,
            orders = listOf(ParsedOrder(
                orderIndex = 0, orderType = OrderType.SHOP_FOR_ITEMS, storeName = "Store",
                itemCount = 64, isItemCountEstimated = false, badges = emptySet(),
            )),
        )
        val priced = OfferEvaluator().evaluate(parsed, EvaluationConfig(userEconomy = economy))
        val accepted = this.accepted.copy(
            estMinutes = priced.estimatedTimeMinutes, handlingMinutes = priced.handlingMinutes,
            pricedShopItemsPerMinute = priced.pricedShopItemsPerMinute,
            pricedBasePickupMinutes = priced.pricedBasePickupMinutes,
        )
        val corrected = ArrivalCorrection.compute(accepted, "pickup", 30, economy, 2L)!!
        assertEquals(32 / 0.79, priced.handlingMinutes!!, 0.000001)
        assertEquals(30 / 0.79, corrected.correctedHandlingMinutes, 0.000001)
        assertEquals(8 * economy.avgMinutesPerMile + 30 / 0.79, corrected.correctedEstMinutes, 0.000001)
    }

    @Test fun `accepted overhead survives later time learning while legacy uses effective overhead`() {
        val learned = economy.copy(learnedMinutesPerMile = 2.0, learnedStopOverheadMinutes = 20.0,
            timeConstantSampleCount = 10)
        val frozen = accepted.copy(pricedBasePickupMinutes = 7.5, nonShopLegs = 1)
        assertEquals(15.0, ArrivalCorrection.compute(frozen, "pickup", 1, learned, 2L)!!.correctedHandlingMinutes, 0.0)
        val legacy = frozen.copy(pricedBasePickupMinutes = null)
        assertEquals(2 * learned.effectiveBasePickupMinutes,
            ArrivalCorrection.compute(legacy, "pickup", 1, learned, 2L)!!.correctedHandlingMinutes, 0.0)
    }

}
