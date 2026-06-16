package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * First [SessionReplay] regression: reproduces the field-observed "ghost offer" (#498) by
 * replaying two REAL captures from the 2026-06-14 dash through the production rules —
 *  1. `01_real_offer_heb_1630.json` — a genuine H-E-B Shop & Deliver offer.
 *  2. `02_ghost_offer_blank_1631.json` — a blank `offer_popup` frame (only Accept/Decline
 *     chrome, no store/pay/distance) captured 16:31:52, which the device logged as
 *     OFFER_RECEIVED + immediate OFFER_TIMEOUT (db `app_events` seq 152/153, session
 *     `session-doordash-1781470176806-43`).
 *
 * The all-null parse produces `offerHash == sha256("null|null|null|")`, the same fingerprint
 * recorded in the db — so this test grounds #498 in reproducible data instead of narration.
 */
class GhostOfferReplayTest {

    private val session = "snapshots/sessions/ghost_offer_2026_06_14"

    /** sha256("null|null|null|") — the deterministic fingerprint of an all-null offer parse. */
    private val nullOfferHash = "906a43f85fed65ed12efc8878df2ccf467e4171b7c288720e0d13137997c0382"

    private fun Observation.Screen.offer(): ParsedOffer =
        (parsed as ParsedFields.OfferFields).parsedOffer

    @Test
    fun `harness reproduces the ghost offer from real captures (characterization, #498)`() {
        val obs = SessionReplay.replayRecognition(session)
        obs.forEachIndexed { i, o ->
            println("frame[$i] target=${o.target} ruleId=${o.ruleId} flow=${o.flow} parsed=${o.parsed::class.simpleName} ts=${o.timestamp}")
        }
        assertEquals("expected 2 frames (real H-E-B offer, then blank ghost)", 2, obs.size)
        val real = obs[0]
        val ghost = obs[1]

        // Both the real and blank frames currently recognize as offer_popup.
        assertEquals("offer_popup", real.target)
        assertEquals("offer_popup", ghost.target)

        // The REAL offer parses its scored fields.
        val realOffer = real.offer()
        assertEquals(18.25, realOffer.payAmount!!, 0.001)
        assertEquals(11.3, realOffer.distanceMiles!!, 0.001)
        assertNotEquals(nullOfferHash, realOffer.offerHash)

        // The GHOST (blank chrome — no store/pay/distance) STILL recognizes as an offer and
        // parses to an all-null offer whose hash is sha256("null|null|null|") — the exact hash
        // the device logged. This is the #498 defect, reproduced from the real capture.
        val ghostOffer = ghost.offer()
        assertNull("ghost offer must have no pay", ghostOffer.payAmount)
        assertNull("ghost offer must have no distance", ghostOffer.distanceMiles)
        assertEquals(nullOfferHash, ghostOffer.offerHash)
    }

    @Ignore("RED until #498 — recognition must reject a blank offer_popup (require the scored fields).")
    @Test
    fun `after #498 a blank offer_popup must not become an offer`() {
        val obs = SessionReplay.replayRecognition(session)
        val ghost = obs[1]
        assertFalse(
            "blank offer frame should not parse to an all-null OfferFields once #498 lands",
            ghost.parsed is ParsedFields.OfferFields && ghost.offer().offerHash == nullOfferHash,
        )
    }

    @Test
    fun `Level B - the blank offer drives a phantom OFFER_RECEIVED through the state machine (#498)`() {
        // Isolate the blank ghost frame and reduce it from idle through the REAL state machine.
        // The defect: a blank frame becomes a logged OFFER_RECEIVED — the exact event the device
        // emitted (db seq 152). Level A proved the recognition; Level B proves the emitted EVENT.
        val ghostFrame = SessionReplay.loadSession(session).first { it.file.contains("ghost") }
        val steps = SessionReplay.reduce(listOf(ghostFrame))
        println(SessionReplay.trace(steps))

        val received = steps.flatMap { it.events }.filter { it.type == AppEventType.OFFER_RECEIVED }
        assertEquals("blank frame emits exactly one phantom OFFER_RECEIVED", 1, received.size)
        assertEquals(nullOfferHash, (received.single().payload as OfferReceivedPayload).offerHash)
    }

    @Ignore("RED until #498 — a blank offer_popup must emit no OFFER_RECEIVED.")
    @Test
    fun `after #498 the blank offer emits no OFFER_RECEIVED (Level B)`() {
        val ghostFrame = SessionReplay.loadSession(session).first { it.file.contains("ghost") }
        val events = SessionReplay.reduce(listOf(ghostFrame)).flatMap { it.events }
        assertTrue(events.none { it.type == AppEventType.OFFER_RECEIVED })
    }
}
