package cloud.trotter.log.gig.replay

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.test.util.SessionReplay
import cloud.trotter.log.gig.test.util.SessionReplay.ReplayFrame
import cloud.trotter.log.gig.test.util.SessionReplay.ReplayInput
import cloud.trotter.log.gig.test.util.SessionReplay.ReplayStep
import cloud.trotter.log.gig.test.util.SessionReplay.ScreenInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 S2 Level B: committed frames through the production rules and the real `StateMachine`. A card that leaves
 * with no click waits in the offer pool; the first pickup frame that names its store links it. Hand-written oracles,
 * never `replay == db`.
 *
 * Committed pickup fixtures carry their store redacted, so each pickup frame gets the card's store written back
 * (`SessionReplay.withPickupStore`); the store is read from the card's own parse at test time.
 *
 * Non-vacuity: on master `77c089dac` cases 1, 2, 3a, 4 and 4b (the lost accepts) fail — the card leaving through an
 * idle frame or the expiry timer logs `OFFER_TIMEOUT` and the pickup mints a job with no offer, so the
 * `OFFER_ACCEPTED` assertion fails; case 5 fails on the click-less inference linking B (the phantom). Case 3b is the
 * control: today's direct edge links there too.
 */
class OfferPoolReplayTest {

    private val cardA = "snapshots/offer_popup/2026-09-19_09-45-38-229__doordash__accessibility.window__offer_popup__822a68.json"
    private val cardB = "snapshots/sessions/unassign_two_order_job_2026_10_09/37_win_offer_popup.json"
    private val idle = "snapshots/sessions/unassign_two_order_job_2026_10_09/36_win_waiting_for_offer.json"
    private val pickup = "snapshots/sessions/gift_drop_open_job_2026_10_11/05_win_pickup_pre_arrival.json"
    private val summary = "snapshots/sessions/dash_end_race_2026_09_08/12_dash_summary.json"

    private fun frame(path: String, at: Long): ReplayFrame = SessionReplay.loadScreenFrame(path, T0 + at)
    private fun screen(path: String, at: Long): ScreenInput = ScreenInput(frame(path, at))

    private fun storeOf(frame: ReplayFrame): String {
        val parsed = SessionReplay.replayRecognition(listOf(frame)).single().parsed as ParsedFields.OfferFields
        return parsed.parsedOffer.orders.first().storeName
    }

    private val storeA by lazy { storeOf(frame(cardA, 0)) }
    private val storeB by lazy { storeOf(frame(cardB, 0)) }

    private fun pickupAt(at: Long, store: String = storeA): ScreenInput =
        ScreenInput(SessionReplay.withPickupStore(frame(pickup, at), store))

    /** B's card, its store rewritten to [store] (the card's own merchant text is a parsed field, not personal data). */
    private fun cardBAt(at: Long, store: String): ScreenInput {
        val rewritten = SessionReplay.withText(frame(cardB, at), storeB, store)
        require(storeOf(rewritten) == store) { "the rewrite must reach B's parsed store" }
        return ScreenInput(rewritten)
    }

    /** The dash summary, then the wake that commits its authoritative grace. */
    private fun dashEnd(at: Long): List<ReplayInput> = listOf(screen(summary, at), SessionReplay.graceCommit(T0 + at + 3_000))

    private fun run(inputs: List<ReplayInput>, keepInputOrder: Boolean = false): List<ReplayStep> =
        SessionReplay.reduceMixed(inputs, keepInputOrder).also { println(SessionReplay.trace(it)) }

    private fun dd(step: ReplayStep): PlatformRegion = step.stateAfter.regions.platforms.getValue(Platform.DoorDash)
    private fun events(steps: List<ReplayStep>): List<AppEvent> = steps.flatMap { it.events }
    private fun outcomes(steps: List<ReplayStep>, type: AppEventType) =
        events(steps).filter { it.type == type }.map { it.payload as OfferPayload }

    /** The shared lost-accept oracle: exactly one link, at [decidedAt], onto a job holding A's one-drop card. */
    private fun assertLinked(steps: List<ReplayStep>, decidedAt: Long) {
        val accepted = outcomes(steps, AppEventType.OFFER_ACCEPTED).single()
        assertEquals(decidedAt, accepted.decidedAt)
        val job = dd(steps.last()).activeJob!!
        assertEquals(accepted.linkedJobId, job.jobId)
        val economics = job.acceptedOffers.single()
        assertEquals(accepted.offerHash, economics.offerHash)
        assertEquals(1, economics.cardDropoffCount)
        assertTrue(events(steps).none { it.type == AppEventType.OFFER_TIMEOUT || it.type == AppEventType.OFFER_NOT_TAKEN })
    }

    @Test fun `case 1 - a transient idle frame between the card and the pickup`() {
        assertLinked(run(listOf(screen(cardA, 0), screen(idle, 35_100), pickupAt(36_100))), T0 + 35_100)
    }

    /** No hybrid frame is committed; the idle rule parses no task field, so the state machine sees a hybrid exactly as this. */
    @Test fun `case 2 - a hybrid idle frame between the card and the pickup`() {
        assertLinked(run(listOf(screen(cardA, 0), screen(idle, 44_600), pickupAt(46_000))), T0 + 44_600)
    }

    @Test fun `case 3a - a de-duplicated leave frame, the same idle seen before the card`() {
        assertLinked(run(listOf(screen(idle, -10_000), screen(cardA, 0), screen(idle, 9_100), pickupAt(10_200))), T0 + 9_100)
    }

    @Test fun `case 3b - straight from the card to the pickup (today's direct edge, the control)`() {
        assertLinked(run(listOf(screen(cardA, 0), pickupAt(6_000))), T0 + 6_000)
    }

    private fun expiryAt(): Long {
        val afterCard = run(listOf(screen(cardA, 0))).last()
        val offer = dd(afterCard).presentedOffer()!!
        return offer.countdownExpiresAt ?: (offer.presentedAt + 120_000)
    }

    @Test fun `case 4 - the expiry timer fires first, the pickup follows`() {
        val t = expiryAt()
        val steps = run(listOf(screen(cardA, 0), SessionReplay.TimeoutInput(TimeoutType.OFFER_EXPIRY, Platform.DoorDash, t),
            pickupAt(t - T0 + 1_700)))
        assertLinked(steps, t)
    }

    @Test fun `case 4b - the 10-25 inversion - a pickup stamped before the timer it follows`() {
        val t = expiryAt()
        val steps = run(listOf(screen(cardA, 0), SessionReplay.TimeoutInput(TimeoutType.OFFER_EXPIRY, Platform.DoorDash, t),
            pickupAt(t - T0 - 19)), keepInputOrder = true)
        assertLinked(steps, t - 19)
        val job = dd(steps.last()).activeJob!!
        assertEquals(t - 19, job.acceptedOffers.single().acceptedAt)
        assertEquals(t - 19, job.startedAt)
    }

    /** Council round 6 test 1 / spec case 5: the 09-20 same-store sequence, no phantom link. */
    @Test fun `case 5 - two pooled cards at one store tie, link nothing, and end not taken with the job named`() {
        val steps = run(listOf(screen(cardA, 0), screen(idle, 5_000), cardBAt(8_000, storeA), pickupAt(20_000)) + dashEnd(200_000))
        val atPickup = steps.single { it.frame?.capturedAtMs == T0 + 20_000 }
        val job = dd(atPickup).activeJob!!
        assertTrue("a bare job", job.acceptedOffers.isEmpty())
        assertTrue("never a link to the newest card", outcomes(steps, AppEventType.OFFER_ACCEPTED).isEmpty())
        assertTrue("no countdown-less sighting can demote", dd(atPickup).offerPool.none { it.demoted })
        val notTaken = outcomes(steps, AppEventType.OFFER_NOT_TAKEN)
        assertEquals(2, notTaken.size)
        notTaken.forEach { assertEquals(listOf(job.jobId), it.ambiguousJobIds) }
    }

    @Test fun `case 5b - a committed decline on the newer card leaves the older one to link`() {
        val steps = run(listOf(screen(cardA, 0), screen(idle, 5_000), cardBAt(8_000, storeA),
            SessionReplay.syntheticOfferClick(OfferIntent.DECLINE, T0 + 15_000), pickupAt(20_000)))
        val declined = outcomes(steps, AppEventType.OFFER_DECLINED).single()
        val accepted = outcomes(steps, AppEventType.OFFER_ACCEPTED).single()
        assertNotEquals(declined.offerHash, accepted.offerHash)
        assertEquals(T0 + 5_000, accepted.decidedAt)
    }

    @Test fun `case 6 - a different store never ties`() {
        require(storeA != storeB) { "the two cards name different stores" }
        val split = run(listOf(screen(cardA, 0), screen(idle, 5_000), screen(cardB, 8_000), screen(idle, 12_000),
            pickupAt(20_000, storeB)) + dashEnd(200_000))
        val linked = outcomes(split, AppEventType.OFFER_ACCEPTED).single()
        val left = outcomes(split, AppEventType.OFFER_NOT_TAKEN).single()
        assertNotEquals(linked.offerHash, left.offerHash)
        assertTrue(left.ambiguousJobIds.isEmpty())

        val third = run(listOf(screen(cardA, 0), screen(idle, 5_000), cardBAt(8_000, storeA), screen(idle, 12_000),
            pickupAt(20_000, "Store Z (Invented)")) + dashEnd(200_000))
        assertTrue(outcomes(third, AppEventType.OFFER_ACCEPTED).isEmpty())
        val ended = outcomes(third, AppEventType.OFFER_NOT_TAKEN)
        assertEquals(2, ended.size)
        assertFalse(ended.any { it.ambiguousJobIds.isNotEmpty() })
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
