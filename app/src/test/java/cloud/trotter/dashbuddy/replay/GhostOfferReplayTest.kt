package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionReplay] regression for the #498 "ghost offer": a blank `offer_popup` frame (only
 * Accept/Decline chrome, no store/pay/distance) was recognized as a real offer and logged an
 * `OFFER_RECEIVED` with `offerHash == sha256("null|null|null|")` (db seq 152) before an immediate
 * `OFFER_TIMEOUT`. The fix: the `offer_popup` rule now `validate`s `payAmount != null`, so a frame
 * with no scored fields is not recognized as an offer.
 *
 * Replays two real 2026-06-14 captures: `01` = a genuine H-E-B Shop & Deliver offer, `02` = the
 * blank ghost. They sort by capture time (01 @ 16:30:55, 02 @ 16:31:52), so `first()` is the real
 * offer and `last()` is the ghost.
 */
class GhostOfferReplayTest {

    private val session = "snapshots/sessions/ghost_offer_2026_06_14"

    @Test
    fun `the real offer is still recognized with its scored fields (#498 does not over-reject)`() {
        val real = SessionReplay.replayRecognition(session).first()
        assertEquals("offer_popup", real.target)
        val offer = (real.parsed as ParsedFields.OfferFields).parsedOffer
        assertEquals(18.25, offer.payAmount!!, 0.001)
        assertEquals(11.3, offer.distanceMiles!!, 0.001)
    }

    @Test
    fun `the blank ghost offer is rejected at recognition (#498, Level A)`() {
        val ghost = SessionReplay.replayRecognition(session).last()
        println("ghost → target=${ghost.target} parsed=${ghost.parsed::class.simpleName}")
        assertFalse(
            "a blank offer_popup with no pay must not parse to an offer",
            ghost.parsed is ParsedFields.OfferFields,
        )
        assertFalse("…and must not be classified as offer_popup", ghost.target == "offer_popup")
    }

    @Test
    fun `the blank ghost offer emits no OFFER_RECEIVED through the state machine (#498, Level B)`() {
        val ghostFrame = SessionReplay.loadSession(session).first { it.file.contains("ghost") }
        val steps = SessionReplay.reduce(listOf(ghostFrame))
        println(SessionReplay.trace(steps))
        assertTrue(
            "a blank offer must not emit a phantom OFFER_RECEIVED",
            steps.flatMap { it.events }.none { it.type == AppEventType.OFFER_RECEIVED },
        )
    }
}
