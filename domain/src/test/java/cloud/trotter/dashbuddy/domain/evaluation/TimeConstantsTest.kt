package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.analytics.PayBasis
import cloud.trotter.dashbuddy.domain.analytics.RecordFolds
import cloud.trotter.dashbuddy.domain.analytics.SessionFoldContext
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.*
import org.junit.Test

class TimeConstantsTest {
    private val platform = Platform.entries.first { it != Platform.Unknown }
    private fun row(seq: Long = 1) = TimeConstantObservation(
        platform = platform, eventSequenceId = seq, phaseStartedAt = 9 * 60_000,
        arrivedAt = 17 * 60_000, completedAt = 20 * 60_000,
        sessionId = "session", sessionAssigned = 0, payBasis = PayBasis.NONE, originalPayBasis = null,
        realizedMinutes = 20.0, milesToStore = 2.0, milesToDropoff = 4.0,
        odometerAtArrival = 106.0, pickupOdometerAtConfirmation = 102.0,
        jobOfferCount = 1, soleOfferHash = "offer", orderCount = 1, orderCountProven = true, isShop = false,
        offerOutcomeResolved = null, deliveryCount = 1, pickupCount = 1,
        acceptedOfferCount = 1, matchingOfferCount = 1, offerDecidedAt = 60_000,
        pickupPhaseStartedAt = 2 * 60_000, pickupArrivedAt = 4 * 60_000,
        pickupConfirmedAt = 9 * 60_000, pickupActivity = null,
    )
    private fun measured(seq: Long, pace: Double, overhead: Double): TimeConstantObservation {
        val departure = 60_000L
        val arrival = departure + (pace * 4 * 60_000).toLong()
        val completed = arrival + (overhead * 60_000).toLong()
        return row(seq).copy(offerDecidedAt = 0, pickupPhaseStartedAt = 0,
            pickupArrivedAt = departure, pickupConfirmedAt = departure, phaseStartedAt = departure,
            arrivedAt = arrival, completedAt = completed, realizedMinutes = completed / 60_000.0)
    }

    @Test fun `chip multiplicity excludes missing sibling drops from counts and medians`() {
        fun foldedRow(platform: Platform, vararg stores: String): TimeConstantObservation {
            val parsed = ParsedOffer(offerHash = "offer", orders = stores.mapIndexed { index, store ->
                ParsedOrder(index, OrderType.PICKUP, store, 1, false, emptySet())
            })
            val event = SequencedAppEvent(1, AppEvent(AppEventType.OFFER_ACCEPTED, 60_000, "session",
                OfferPayload(offerHash = "offer", parsedOffer = parsed, evaluation = null,
                    outcome = AppEventType.OFFER_ACCEPTED, presentedAt = 0, decidedAt = 60_000,
                    returnFlow = Flow.Idle)))
            val offer = RecordFolds.foldEvent(event,
                SessionFoldContext("session", platform, 0, 0), null).offer!!
            // All observed child cardinalities are one; the sibling drop may never have been logged.
            return row().copy(platform = platform, orderCount = offer.orderCount,
                orderCountProven = offer.orderCountProven, isShop = offer.isShop)
        }
        val stackChip = foldedRow(Platform.Uber, "Delivery (2)")
        val singleChip = foldedRow(Platform.Uber, "Delivery (1)")
        val singleStore = foldedRow(Platform.DoorDash, "McDonald's")
        val twoStores = foldedRow(Platform.DoorDash, "McDonald's", "Taco Bell")
        assertNull(TimeConstants.sample(stackChip))
        assertEquals(TimeConstantPair(2.0, 8.0), TimeConstants.sample(singleChip))
        assertEquals(TimeConstantPair(2.0, 8.0), TimeConstants.sample(singleStore))
        assertNull(TimeConstants.sample(twoStores))
        val badMeasurement = stackChip.copy(eventSequenceId = 2, arrivedAt = 19 * 60_000)
        val learned = TimeConstants.estimate(listOf(singleChip, badMeasurement, twoStores))
        assertEquals(setOf(Platform.Uber), learned.keys)
        assertEquals(LearnedTimeConstants(TimeConstantPair(2.0, 8.0), 1), learned.getValue(Platform.Uber))
    }

    @Test fun `independent measurements exclude the entire pre-departure prefix`() {
        assertEquals(TimeConstantPair(2.0, 8.0), TimeConstants.sample(row()))
        assertEquals(TimeConstants.sample(row()), TimeConstants.sample(row().copy(realizedMinutes = 120.0)))
        assertEquals(TimeConstants.sample(row()), TimeConstants.sample(row().copy(milesToStore = 999.0)))
        assertNotNull(TimeConstants.sample(row().copy(milesToStore = 0.0)))
        assertNotNull(TimeConstants.sample(row().copy(pickupActivity = PickupActivity.CONFIRMED)))
        assertNotNull(TimeConstants.sample(row().copy(payBasis = PayBasis.USER_CORRECTED, originalPayBasis = PayBasis.OFFER_PAY)))
        for (basis in listOf(PayBasis.DROP_SHARE, PayBasis.RECEIPT_TOTAL, PayBasis.OFFER_PAY, PayBasis.NONE)) {
            assertNotNull(TimeConstants.sample(row().copy(payBasis = basis)))
        }
    }

    @Test fun `trust gate and cumulative seed blend`() {
        assertTrue(TimeConstants.estimate(emptyList()).isEmpty())
        assertEquals(TimeConstantSeeds.DEFAULT, TimeConstants.blend(TimeConstantSeeds.DEFAULT, null))
        for (n in listOf(9, 10, 40)) {
            val learned = TimeConstants.estimate((1..n).map { row(it.toLong()) }).getValue(platform)
            assertEquals(n, learned.sampleCount)
            assertEquals(TimeConstantPair(2.0, 8.0), learned.median)
            val blended = TimeConstants.blend(TimeConstantSeeds.DEFAULT, learned)
            val expected = if (n == 9) TimeConstantSeeds.DEFAULT else TimeConstantPair(
                (25.0 + 2 * n) / (n + 10), (70.0 + 8 * n) / (n + 10))
            assertEquals(expected.minutesPerMile, blended.minutesPerMile, 1e-9)
            assertEquals(expected.stopOverheadMinutes, blended.stopOverheadMinutes, 1e-9)
        }
        assertEquals(TimeConstantPair(2.25, 7.5), TimeConstants.blend(TimeConstantSeeds.DEFAULT,
            LearnedTimeConstants(TimeConstantPair(2.0, 8.0), 10)))
    }

    @Test fun `separate nearest rank medians resist independent extreme outliers and use lower middle`() {
        val rows = listOf(measured(1, 1.0, 1000.0), measured(2, 2.0, 8.0),
            measured(3, 3.0, 4.0), measured(4, 1000.0, 0.0))
        assertEquals(TimeConstantPair(2.0, 4.0), TimeConstants.estimate(rows).getValue(platform).median)
    }

    @Test fun `window follows sequence not timestamp filters first and deduplicates replay`() {
        val old = (1..20).map { measured(it.toLong(), 99.0, 99.0) }
        val latest = (21..50).map { row(it.toLong()) }
        val invalid = (51..100).map { row(it.toLong()).copy(arrivedAt = null) }
        val result = TimeConstants.estimate((old + latest + latest + invalid).reversed()).getValue(platform)
        assertEquals(50, result.sampleCount)
        assertEquals(TimeConstantPair(2.0, 8.0), result.median)
        assertEquals(TimeConstantPair(2.0, 8.0), TimeConstants.estimate(old.take(1) + latest).getValue(platform).median)
    }

    @Test fun `platforms are isolated including identical sequence ids`() {
        val other = Platform.entries.first { it != Platform.Unknown && it != platform }
        val result = TimeConstants.estimate((1..10).map { row(it.toLong()) } +
            measured(1, 4.0, 12.0).copy(platform = other))
        assertEquals(10, result.getValue(platform).sampleCount)
        assertEquals(1, result.getValue(other).sampleCount)
        assertEquals(TimeConstantPair(4.0, 12.0), result.getValue(other).median)
        assertFalse(result.containsKey(Platform.Unknown))
    }

    @Test fun `every incomplete ambiguous or inconsistent fact fails closed`() {
        val r = row()
        val invalid = listOf(
            r.copy(platform = Platform.Unknown), r.copy(sessionId = null), r.copy(sessionAssigned = 1),
            r.copy(payBasis = PayBasis.MANUAL), r.copy(payBasis = PayBasis.USER_CORRECTED),
            r.copy(payBasis = PayBasis.SUSPECT_FULL_RECEIPT),
            r.copy(payBasis = PayBasis.USER_CORRECTED, originalPayBasis = PayBasis.MANUAL),
            r.copy(originalPayBasis = PayBasis.SUSPECT_FULL_RECEIPT),
            r.copy(jobOfferCount = null), r.copy(jobOfferCount = 0), r.copy(jobOfferCount = 2),
            r.copy(soleOfferHash = null), r.copy(soleOfferHash = " "),
            r.copy(deliveryCount = 0), r.copy(deliveryCount = 2),
            r.copy(pickupCount = 0), r.copy(pickupCount = 2),
            r.copy(acceptedOfferCount = 0), r.copy(acceptedOfferCount = 2),
            r.copy(matchingOfferCount = 0), r.copy(matchingOfferCount = 2),
            r.copy(orderCount = null), r.copy(orderCount = 0), r.copy(orderCount = 2),
            r.copy(orderCountProven = null), r.copy(orderCountProven = false),
            r.copy(isShop = null), r.copy(isShop = true), r.copy(offerOutcomeResolved = "UNASSIGNED_INFERRED"),
            r.copy(pickupActivity = PickupActivity.SHOPPING),
            r.copy(realizedMinutes = null), r.copy(realizedMinutes = 0.0), r.copy(realizedMinutes = -1.0),
            r.copy(realizedMinutes = Double.NaN), r.copy(realizedMinutes = Double.POSITIVE_INFINITY),
            r.copy(milesToStore = null), r.copy(milesToStore = -1.0), r.copy(milesToStore = Double.NaN),
            r.copy(milesToStore = Double.POSITIVE_INFINITY), r.copy(milesToDropoff = null),
            r.copy(milesToDropoff = 0.0), r.copy(milesToStore = 0.0, milesToDropoff = 0.0),
            r.copy(milesToDropoff = -1.0), r.copy(milesToDropoff = Double.NaN),
            r.copy(milesToDropoff = Double.POSITIVE_INFINITY),
            r.copy(arrivedAt = null), r.copy(pickupArrivedAt = null), r.copy(pickupConfirmedAt = null),
            r.copy(offerDecidedAt = null), r.copy(pickupPhaseStartedAt = null),
            r.copy(offerDecidedAt = -2), r.copy(offerDecidedAt = 3 * 60_000),
            r.copy(pickupPhaseStartedAt = 5 * 60_000), r.copy(pickupArrivedAt = 10 * 60_000),
            r.copy(pickupConfirmedAt = 10 * 60_000), r.copy(phaseStartedAt = 18 * 60_000),
            r.copy(arrivedAt = 21 * 60_000), r.copy(arrivedAt = 9 * 60_000),
            r.copy(realizedMinutes = 15.0),
            r.copy(odometerAtArrival = null), r.copy(pickupOdometerAtConfirmation = null),
            r.copy(odometerAtArrival = Double.NaN), r.copy(pickupOdometerAtConfirmation = Double.NaN),
            r.copy(odometerAtArrival = Double.POSITIVE_INFINITY),
            r.copy(pickupOdometerAtConfirmation = Double.POSITIVE_INFINITY),
            r.copy(odometerAtArrival = -1.0), r.copy(pickupOdometerAtConfirmation = -1.0),
            r.copy(odometerAtArrival = 102.0), r.copy(odometerAtArrival = 101.0),
            r.copy(odometerAtArrival = 106.001), r.copy(milesToDropoff = 8.0),
            r.copy(completedAt = Long.MIN_VALUE),
        )
        invalid.forEachIndexed { index, bad -> assertNull("case $index: $bad", TimeConstants.sample(bad)) }
        assertTrue(TimeConstants.estimate(invalid).isEmpty())
    }

    @Test fun `one millisecond containment tolerance and odometer tolerance`() {
        assertNotNull(TimeConstants.sample(row().copy(realizedMinutes = 19.0 - 0.5 / 60_000)))
        assertNull(TimeConstants.sample(row().copy(realizedMinutes = 19.0 - 2.0 / 60_000)))
        assertNotNull(TimeConstants.sample(row().copy(odometerAtArrival = 106.0 + 0.5e-6)))
        assertEquals(TimeConstantPair(2.0, 0.0), TimeConstants.sample(measured(1, 2.0, 0.0)))
    }
}
