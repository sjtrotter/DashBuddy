package cloud.trotter.dashbuddy.ui.bubble.cards

import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.Flow
import com.google.gson.Gson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #353 wire-compatibility guard: EffectMap now ENCODES event payloads with
 * kotlinx.serialization, while FlowCardMapper still DECODES them with Gson
 * (until #354 moves the fold onto a domain AppEvent model). This pins the
 * cross-codec seam the HUD cards depend on at runtime.
 */
class CrossCodecWireTest {

    /** Mirrors core:state's StateJson configuration (internal there). */
    private val stateJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val gson = Gson()

    @Test
    fun `kotlinx-encoded OfferReceivedPayload decodes identically with Gson`() {
        val payload = OfferReceivedPayload(
            offerHash = "h1",
            parsedOffer = ParsedOffer(offerHash = "h1", payAmount = 7.5, distanceMiles = 3.2),
            presentedAt = 1_000L,
            platform = "DoorDash",
            returnFlow = Flow.Idle,
        )

        val wire = stateJson.encodeToString(payload)
        val decoded = gson.fromJson(wire, OfferReceivedPayload::class.java)

        assertEquals(payload.offerHash, decoded.offerHash)
        assertEquals(payload.parsedOffer.payAmount, decoded.parsedOffer.payAmount)
        assertEquals(payload.presentedAt, decoded.presentedAt)
        assertEquals(payload.returnFlow, decoded.returnFlow)
    }

    @Test
    fun `kotlinx-encoded SessionStopPayload decodes identically with Gson`() {
        val payload = SessionStopPayload(
            sessionId = "session-doordash-100-0",
            endedAt = 5_000L,
            source = "summary_screen",
            totalEarnings = 41.25,
            sessionDurationMillis = 3_600_000L,
            offersAccepted = 7,
            offersTotal = 9,
            weeklyEarnings = 120.50,
        )

        val wire = stateJson.encodeToString(payload)
        val decoded = gson.fromJson(wire, SessionStopPayload::class.java)

        assertEquals(payload, decoded)
    }
}
