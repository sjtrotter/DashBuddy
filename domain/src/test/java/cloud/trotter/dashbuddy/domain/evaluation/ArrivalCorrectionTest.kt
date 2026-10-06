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
        assertNull(corrected.quotedItems) // Units are not a quoted item count.
        assertEquals(20 + 64 / 0.79, accepted.estMinutes!!, 0.000001)
    }

    @Test fun `small shop is floored and non-shop legs retain their base`() {
        val withLegs = accepted.copy(nonShopLegs = 2)
        val corrected = ArrivalCorrection.compute(withLegs, "pickup", 1, economy, 2L)!!
        assertEquals(3 * economy.basePickupMinutes, corrected.correctedHandlingMinutes, 0.000001)
        assertEquals(20 + 3 * economy.basePickupMinutes, corrected.correctedEstMinutes, 0.000001)
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
        val accepted = this.accepted.copy(estMinutes = priced.estimatedTimeMinutes, handlingMinutes = priced.handlingMinutes)
        val corrected = ArrivalCorrection.compute(accepted, "pickup", 30, economy, 2L)!!
        assertEquals(32 / 0.79, priced.handlingMinutes!!, 0.000001)
        assertEquals(30 / 0.79, corrected.correctedHandlingMinutes, 0.000001)
        assertEquals(8 * economy.avgMinutesPerMile + 30 / 0.79, corrected.correctedEstMinutes, 0.000001)
    }
}
