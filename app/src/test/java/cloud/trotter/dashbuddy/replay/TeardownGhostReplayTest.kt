package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [SessionReplay] regression for the #595 "teardown ghost": DoorDash's post-accept transitional
 * `offer_popup` render keeps the pay/distance/Accept/Decline chrome but drops the STORE rows
 * (only the 'Customer dropoff' `display_name` leg remains, plus a raw UUID in
 * `assignment_id_text`). It re-parsed as a NEW degenerate offer (`orders=[]`, `itemCount=0`,
 * different offerHash) that REPLACED the just-accepted pending offer — bogus TTS/chat
 * ("Accept. Unknown Store…"), then a user-visible "Offer Timed Out!" and an orphan
 * `OFFER_TIMEOUT` with no matching `OFFER_RECEIVED` (field week 06-25→30: seq 141/142 on 06-28,
 * seq 241/242 on 06-30). Fix: `offer_popup` now REQUIRES at least one store leg, so the teardown
 * frame classifies UNKNOWN and is never forwarded.
 *
 * Replays the real 2026-06-28 sequence (all frames are unmodified device captures; the accept
 * click is synthetic — click envelopes weren't persisted that week, #597):
 * `01` real H-E-B Shop & Deliver offer ($13.04, 15 units) → synthetic ACCEPT click →
 * `03` pickup_shopping (pops the offer → OFFER_ACCEPTED) → `04` THE TEARDOWN GHOST ($22.76
 * chrome, no store rows) → `05` navigation_generic (would pop any ghost-minted offer as a
 * TIMEOUT — the pre-fix red signal).
 */
class TeardownGhostReplayTest {

    private val session = "snapshots/sessions/teardown_ghost_2026_06_28"

    @Test
    fun `the real offer still recognizes with its scored fields (#595 does not over-reject)`() {
        val real = SessionReplay.replayRecognition(session).first()
        assertEquals("offer_popup", real.target)
        val offer = (real.parsed as ParsedFields.OfferFields).parsedOffer
        assertEquals(13.04, offer.payAmount!!, 0.001)
        assertEquals("H-E-B", offer.orders.single().storeName)
    }

    @Test
    fun `the teardown ghost is rejected at recognition (#595, Level A)`() {
        val obs = SessionReplay.replayRecognition(session)
        val ghost = obs[2] // 01_offer, 03_shopping, 04_ghost, 05_nav (time-ordered)
        println("ghost → target=${ghost.target} parsed=${ghost.parsed::class.simpleName}")
        assertFalse(
            "the store-less teardown frame must not parse to an offer",
            ghost.parsed is ParsedFields.OfferFields,
        )
        assertFalse("…and must not classify as offer_popup", ghost.target == "offer_popup")
    }

    @Test
    fun `accept survives the teardown frame - one offer, one accept, zero timeouts (#595, Level B)`() {
        val frames = SessionReplay.loadSession(session)
        val offerFrame = frames.first { it.file.contains("real_offer") }
        val inputs = listOf(
            SessionReplay.ScreenInput(offerFrame),
            // The dasher's accept — synthetic (no click envelope existed, #597), injected while
            // the offer card is up so the later pop records ACCEPTED, exactly like the field.
            SessionReplay.syntheticOfferClick(OfferIntent.ACCEPT, atMs = offerFrame.capturedAtMs + 2_000L),
        ) + frames.filterNot { it.file.contains("real_offer") }.map { SessionReplay.ScreenInput(it) }

        val steps = SessionReplay.reduceMixed(inputs.sortedBy { it.atMs })
        println(SessionReplay.trace(steps))
        val events = steps.flatMap { it.events }

        assertEquals(
            "exactly ONE offer received — the teardown frame must not re-mint",
            1, events.count { it.type == AppEventType.OFFER_RECEIVED },
        )
        assertEquals(
            "the real accept commits exactly once",
            1, events.count { it.type == AppEventType.OFFER_ACCEPTED },
        )
        assertEquals(
            "no orphan OFFER_TIMEOUT — the ghost's signature (field seq 142/242)",
            0, events.count { it.type == AppEventType.OFFER_TIMEOUT },
        )
    }
}
