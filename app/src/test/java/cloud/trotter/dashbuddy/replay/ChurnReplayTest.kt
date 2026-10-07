package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #830: the three real Sonic frames re-quote economics A→B→A while retaining one presentationKey.
 * The full trace must retain the first presentation epoch and accept latch, refresh notifications
 * and expiry hashes, and speak only once. A distinct DoorDash offer must still replace its predecessor.
 * Fixtures: uber_offer_churn_2026_07_21 (seq 603/604/606), doordash_offer_replace_2026_01_28.
 */
class ChurnReplayTest {

    private val session = "snapshots/sessions/uber_offer_churn_2026_07_21"
    private val ddSession = "snapshots/sessions/doordash_offer_replace_2026_01_28"

    /** A minimal but valid landed evaluation for [offerHash], routed to [platform] via a loopback. */
    private fun evalLoopback(offerHash: String, platform: Platform, atMs: Long): SessionReplay.RawInput {
        val eval = OfferEvaluation(
            action = OfferAction.ACCEPT,
            score = 1.0,
            qualityLevel = OfferQuality.GOOD,
            payAmount = 6.44,
            fuelCostEstimate = 0.5,
            netPayAmount = 5.9,
            distanceMiles = 8.5,
            dollarsPerMile = 0.69,
            dollarsPerHour = 14.0,
            estimatedTimeMinutes = 26.0,
            itemCount = 1.0,
            merchantName = "Sonic",
        )
        return SessionReplay.RawInput(
            Observation.Loopback(
                timestamp = atMs,
                effect = Observation.Loopback.EFFECT_OFFER_EVALUATED,
                targetPlatform = platform,
                payload = ObservationPayload.EvaluationResult(
                    action = OfferAction.ACCEPT.name,
                    offerHash = offerHash,
                    evaluation = eval,
                ),
            ),
            atMs = atMs,
        )
    }

    /** A synthetic accept click on the Uber presented offer. */
    private fun uberAcceptClick(atMs: Long): SessionReplay.RawInput = SessionReplay.RawInput(
        Observation.Click(
            timestamp = atMs,
            captureId = null,
            ruleId = "uber.click.${OfferIntent.ACCEPT}",
            metadata = cloud.trotter.dashbuddy.domain.capture.ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
            screenTarget = "offer",
        ),
        atMs = atMs,
    )

    private fun counts(steps: List<SessionReplay.ReplayStep>): Map<AppEventType, Int> =
        steps.flatMap { it.events }.groupingBy { it.type }.eachCount()

    private fun effects(steps: List<SessionReplay.ReplayStep>): List<AppEffect> =
        steps.flatMap { it.effects }

    @Test
    fun `Uber churn preserves one presentation, accept latch and deadline while refreshing variant effects`() {
        val frames = SessionReplay.loadSession(session).sortedBy { it.capturedAtMs }
        assertEquals("three Sonic frames", 3, frames.size)
        val obs = SessionReplay.replayRecognition(frames)
        val offers = obs.map { (it.parsed as ParsedFields.OfferFields).parsedOffer }
        assertEquals("all three recognize as offers", 3, offers.size)
        // presentationKey identical across all three variants.
        assertEquals(
            "presentationKey is stable across the live re-quote",
            1,
            offers.mapNotNull { it.presentationKey }.toSet().size,
        )
        assertNotNull("presentationKey derived (not fail-closed null)", offers[0].presentationKey)
        // offerHash churns: A (6.44) → B (6.42) → A (6.44).
        assertEquals("first and third variant re-quote to the SAME economics", offers[0].offerHash, offers[2].offerHash)
        org.junit.Assert.assertNotEquals("the middle variant re-quoted differently", offers[0].offerHash, offers[1].offerHash)

        // Accept variant 1; each evaluation loopback can emit speech and notification effects.
        fun hash(i: Int) = offers[i].offerHash
        val inputs = listOf(
            SessionReplay.ScreenInput(frames[0]),
            uberAcceptClick(frames[0].capturedAtMs + 1),
            evalLoopback(hash(0), Platform.Uber, frames[0].capturedAtMs + 2),
            SessionReplay.ScreenInput(frames[1]),
            evalLoopback(hash(1), Platform.Uber, frames[1].capturedAtMs + 1),
            SessionReplay.ScreenInput(frames[2]),
            evalLoopback(hash(2), Platform.Uber, frames[2].capturedAtMs + 1),
        )
        val steps = SessionReplay.reduceMixed(inputs)

        val c = counts(steps)
        assertEquals("exactly one OFFER_RECEIVED for the one physical offer", 1, c[AppEventType.OFFER_RECEIVED] ?: 0)
        assertEquals(
            "no 'Replaced by new offer' timeout between churned re-renders",
            0,
            c[AppEventType.OFFER_TIMEOUT] ?: 0,
        )

        val speaks = effects(steps).count { it is AppEffect.SpeakOffer }
        assertEquals("SpeakOffer fires once per physical presentation, not once per re-quote", 1, speaks)

        val posts = effects(steps).count { it is AppEffect.PostOfferNotification }
        // Three eval loopbacks land (one per variant) → three live heads-up updates, one read aloud.
        assertEquals("PostOfferNotification fires on every eval landing (live re-quote)", 3, posts)
        // Each churn enriches (2 enrich steps) → the OLD hash's stale banner is cancelled so a
        // per-hash notification id can't strand a dead banner (no OFFER_TIMEOUT accompanies it).
        val cancels = effects(steps).count { it is AppEffect.CancelOfferNotification }
        assertEquals("the two churn enriches each dismiss the prior variant's stale banner", 2, cancels)
        assertEquals(
            "the churn cancels emit no OFFER_TIMEOUT (the offer never left presentation)",
            0,
            counts(steps)[AppEventType.OFFER_TIMEOUT] ?: 0,
        )

        // After variant 2's frame arrives, the presented offer must still carry the accept latch.
        val afterVariant2 = steps.first { it.frame?.capturedAtMs == frames[1].capturedAtMs }
        val presentedAfter2 = afterVariant2.stateAfter.regions.platforms[Platform.Uber]
            ?.pendingOffers?.firstOrNull { it.acceptedAt == null }
        assertNotNull("an offer is still presented after variant 2", presentedAfter2)
        assertNotNull(
            "the accept-click latch is NOT discarded by the variant-2 enrich",
            presentedAfter2!!.acceptClickAt,
        )

        // …and through the whole sequence.
        val presentedFinal = steps.last().stateAfter.regions.platforms[Platform.Uber]
            ?.pendingOffers?.firstOrNull { it.acceptedAt == null }
        assertNotNull("still presented at the end", presentedFinal)
        assertEquals(
            "the latch survived every variant",
            frames[0].capturedAtMs + 1,
            presentedFinal!!.acceptClickAt,
        )

        // presentedAt never moves off the FIRST frame across every enrich.
        assertNotNull(presentedFinal)
        assertEquals(
            "presentedAt is pinned to the physical presentation epoch (frame 1)",
            frames[0].capturedAtMs,
            presentedFinal.presentedAt,
        )

        // Every OFFER_EXPIRY re-arm resolves to the SAME absolute deadline: presentedAt + default TTL.
        val expectedDeadline = frames[0].capturedAtMs + 120_000L // EffectMap.OFFER_EXPIRY_DEFAULT_MS
        steps.forEach { step ->
            step.effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .filter { it.payload is ObservationPayload.OfferExpiry }
                .forEach { arm ->
                    val absoluteDeadline = step.observation.timestamp + arm.durationMs
                    assertEquals(
                        "a churn re-arm must not push the deadline out",
                        expectedDeadline,
                        absoluteDeadline,
                    )
                }
        }

        // The LAST re-arm (on the variant-3 frame) must carry variant 3's hash — not the original.
        val lastArm = steps
            .first { it.frame?.capturedAtMs == frames[2].capturedAtMs }
            .effects.filterIsInstance<AppEffect.ScheduleTimeout>()
            .last { it.payload is ObservationPayload.OfferExpiry }
        assertEquals(
            "the re-armed timer targets the current variant's hash so the offer can still time out",
            hash(2),
            (lastArm.payload as ObservationPayload.OfferExpiry).offerHash,
        )
    }

    @Test
    fun `a distinct-store DoorDash offer still replaces the prior one - OFFER_TIMEOUT + re-speak (#830 no regression)`() {
        val frames = SessionReplay.loadSession(ddSession).sortedBy { it.capturedAtMs }
        assertEquals("two DoorDash offers (different stores)", 2, frames.size)
        val obs = SessionReplay.replayRecognition(frames)
        val offers = obs.map { (it.parsed as ParsedFields.OfferFields).parsedOffer }
        // #1069: these legacy View cards render no assignment token and declare no fallback → NO key
        // (economics: replace on any hash change). Pinned exactly (fable review F4), not vacuously.
        assertNull("id-less legacy card 0 has no presentation key", offers[0].presentationKey)
        assertNull("id-less legacy card 1 has no presentation key", offers[1].presentationKey)
        assertNull(offers[0].assignmentIdHash)
        assertTrue("two different offers hash differently", offers[0].offerHash != offers[1].offerHash)
        fun hash(i: Int) = offers[i].offerHash

        val inputs = listOf(
            SessionReplay.ScreenInput(frames[0]),
            evalLoopback(hash(0), Platform.DoorDash, frames[0].capturedAtMs + 1),
            SessionReplay.ScreenInput(frames[1]),
            evalLoopback(hash(1), Platform.DoorDash, frames[1].capturedAtMs + 1),
        )
        val steps = SessionReplay.reduceMixed(inputs)
        val c = counts(steps)

        assertEquals("the first offer still resolves OFFER_TIMEOUT('Replaced by new offer')", 1, c[AppEventType.OFFER_TIMEOUT] ?: 0)
        // #1069 (fable F1): a REPLACE is a second offer — it logs its OWN OFFER_RECEIVED now.

        assertEquals(

            "two OFFER_RECEIVED rows — one per distinct offer (#1069)",

            2, c[AppEventType.OFFER_RECEIVED] ?: 0)
        assertEquals(
            "each distinct presentation is spoken — the replacement re-speaks",
            2,
            effects(steps).count { it is AppEffect.SpeakOffer },
        )
    }
}
