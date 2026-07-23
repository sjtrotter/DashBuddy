package cloud.trotter.dashbuddy.domain.model.event

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.JobAcceptMismatchPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferOutcomeCorrectionPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferOutcomeResolution
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionPausedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality

/**
 * The event-payload wire codec (#354): every payload type round-trips through
 * its event type, the wire shape stays discriminator-free (rows written before
 * the codec existed decode identically), and malformed JSON THROWS — degrading
 * is the repo edge's decision, not the codec's (#353).
 *
 * Supersedes CrossCodecWireTest: with EffectMap emitting domain payloads and
 * the repo owning both encode and decode, there is no cross-codec seam left.
 */
class AppEventCodecTest {

    private fun roundTrip(type: AppEventType, payload: AppEventPayload): AppEventPayload? =
        AppEventCodec.decodePayload(type, AppEventCodec.encodePayload(payload))

    @Test
    fun `every payload class round-trips through its event types`() {
        val offer = ParsedOffer(offerHash = "h1", payAmount = 7.5, distanceMiles = 3.2)
        val evaluation = OfferEvaluation(
            action = OfferAction.ACCEPT,
            score = 80.0,
            qualityLevel = OfferQuality.GOOD,
            payAmount = 7.5,
            fuelCostEstimate = 0.5,
            nonFuelCostEstimate = 0.5,
            totalOperatingCost = 1.0,
            operatingCostPerMile = 0.24,
            netPayAmount = 6.5,
            distanceMiles = 3.2,
            dollarsPerMile = 2.34,
            dollarsPerHour = 22.0,
            estimatedTimeMinutes = 18.0,
            itemCount = 1.0,
            merchantName = "Wendy's",
        )

        val cases: List<Pair<AppEventType, AppEventPayload>> = listOf(
            AppEventType.OFFER_RECEIVED to OfferReceivedPayload(
                offerHash = "h1", parsedOffer = offer, presentedAt = 1_000L,
                platform = "DoorDash", returnFlow = Flow.Idle,
            ),
            AppEventType.OFFER_ACCEPTED to OfferPayload(
                offerHash = "h1", parsedOffer = offer, evaluation = evaluation,
                outcome = AppEventType.OFFER_ACCEPTED, presentedAt = 1_000L,
                decidedAt = 2_000L, returnFlow = Flow.Idle,
            ),
            AppEventType.PICKUP_ARRIVED to PickupPayload(
                jobId = "j1", taskId = "t1", storeName = "Wendy's",
                phaseStartedAt = 3_000L, arrivedAt = 4_000L, itemsRemaining = 2,
            ),
            AppEventType.DELIVERY_COMPLETED to DeliveryPayload(
                jobId = "j1", taskId = "t2", storeName = "Wendy's",
                customerHash = "c1", phaseStartedAt = 5_000L, completedAt = 6_000L,
                totalPay = 9.25, sessionEarningsAtCompletion = 41.0,
            ),
            AppEventType.DASH_START to SessionStartPayload(
                sessionId = "s1", platform = "DoorDash", startedAt = 500L,
                source = "interaction", startScreen = "WaitingForOffer",
            ),
            AppEventType.DASH_PAUSED to SessionPausedPayload(
                sessionId = "s1", pausedAt = 7_000L,
                remainingText = "34 min", remainingMillis = 2_040_000L,
            ),
            AppEventType.DASH_STOP to SessionStopPayload(
                sessionId = "s1", endedAt = 8_000L,
                source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 41.25,
                sessionDurationMillis = 3_600_000L, offersAccepted = 7, offersTotal = 9,
            ),
            AppEventType.TASK_UNASSIGNED to TaskUnassignedPayload(
                jobId = "j1", taskId = "t1", phase = TaskPhase.PICKUP, storeName = "Wendy's",
                arrivedAt = 4_000L, startedAt = 3_000L, unassignedAt = 5_000L,
                jobOfferHashes = listOf("h1"),
            ),
            AppEventType.JOB_ACCEPT_MISMATCH to JobAcceptMismatchPayload(
                jobId = "j1", acceptedCount = 2, accountedCount = 1,
                acceptedOfferHashes = listOf("hA", "hB"), deliveredCustomerHashes = listOf("c1"),
                leftoverTbdPlaceholders = 1, unassignedCount = 0,
            ),
            AppEventType.OFFER_OUTCOME_CORRECTION to OfferOutcomeCorrectionPayload(
                targetOfferEventSequenceId = 42L,
                resolvedOutcome = OfferOutcomeResolution.UNASSIGNED_ATTESTED, note = "chat unassign",
            ),
        )

        for ((type, payload) in cases) {
            assertEquals("round-trip for $type", payload, roundTrip(type, payload))
        }
    }

    @Test
    fun `wire shape is a plain field object with no polymorphic discriminator`() {
        val wire = AppEventCodec.encodePayload(
            SessionStartPayload(
                sessionId = "s1", platform = "DoorDash", startedAt = 500L,
                source = "interaction", startScreen = "WaitingForOffer",
            )
        )
        // Pre-#354 rows were written from the concrete class — new rows must
        // look identical so old and new decode through the same path.
        assertFalse(wire.contains("\"type\""))
        assertEquals(true, wire.startsWith("{\"sessionId\""))
    }

    @Test
    fun `empty and blank payloads decode to null for every type`() {
        for (type in AppEventType.entries) {
            assertNull(AppEventCodec.decodePayload(type, "{}"))
            assertNull(AppEventCodec.decodePayload(type, ""))
            assertNull(AppEventCodec.decodePayload(type, "  "))
        }
    }

    @Test
    fun `payload-less event types decode to null even when JSON is present`() {
        for (
        type in listOf(
            AppEventType.ZONE_SWITCH,
            AppEventType.NOTIFICATION_RECEIVED,
            AppEventType.SCREEN_VIEWED,
            AppEventType.ERROR_OCCURRED,
        )
        ) {
            assertNull(AppEventCodec.decodePayload(type, """{"anything":1}"""))
        }
    }

    @Test
    fun `malformed JSON throws instead of silently returning null`() {
        try {
            AppEventCodec.decodePayload(AppEventType.DASH_START, "{not json!!")
            fail("expected a serialization failure")
        } catch (_: Exception) {
            // expected — the repo edge logs and degrades, the codec never lies
        }
    }
}
