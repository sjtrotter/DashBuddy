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
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [SessionReplay] regression for the #830 "Uber offer identity churn": Uber presents ONE physical
 * offer, but its card live-re-quotes (pay/miles/minutes tick every few seconds), so each re-render
 * changes the parsed content → a new [cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer.offerHash]
 * → `OfferLifecycle` treated every re-render as a REPLACEMENT: the old pending resolved
 * `OFFER_TIMEOUT("Replaced by new offer")`, the new pending re-evaluated and **re-spoke**, and the
 * click latches were discarded. The same Sonic offer was read aloud 3× and its analytics inflated.
 *
 * The three real Sonic frames (2026-07-21 dash, `~/dashbuddy/logs/2026/07/22`, seq 603/604/606):
 * `01` @ 18:39:23 `$6.44 / 26 min / 8.5 mi`, `02` @ 18:39:26 `$6.42 / 25 min / 8.4 mi`,
 * `03` @ 18:39:36 `$6.44 / 26 min / 8.5 mi` — all `Sonic (2314 Thousand Oaks)`, one order. So the
 * ECONOMICS oscillate (offerHash A→B→A) while the STABLE subset (store + order count + type) is
 * byte-identical → one `presentationKey` across all three.
 *
 * The fix: a churned re-render whose `presentationKey` matches the offer already on screen is
 * ENRICHED-as-variant (keep presentedAt + latches, update the numbers, re-eval) instead of replaced.
 * No `OFFER_TIMEOUT`, one `SpeakOffer` per physical presentation, latches survive, and the expiry
 * deadline stays anchored on the FIRST frame's `presentedAt`.
 */
class ChurnReplayTest {

    private val session = "snapshots/sessions/uber_offer_churn_2026_07_21"
    private val ddSession = "snapshots/sessions/doordash_offer_replace_2026_01_28"

    // ---------------------------------------------------------------------------------------------
    // Injection helpers
    // ---------------------------------------------------------------------------------------------

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

    /**
     * Build the full churn injection stream: the three real Sonic frames plus a synthetic accept
     * click on variant 1 and a synthetic eval loopback after each frame (so `SpeakOffer` can fire —
     * the replay harness has no eval executor). Returns the ordered [SessionReplay.ReplayStep]s.
     */
    private fun churnSteps(): List<SessionReplay.ReplayStep> {
        val frames = SessionReplay.loadSession(session).sortedBy { it.capturedAtMs }
        assertEquals("three Sonic frames", 3, frames.size)
        // Real production hashes (A == C by economics; B differs).
        val obs = SessionReplay.replayRecognition(frames)
        fun hash(i: Int) = (obs[i].parsed as ParsedFields.OfferFields).parsedOffer.offerHash
        val f = frames
        val inputs = listOf(
            SessionReplay.ScreenInput(f[0]),
            uberAcceptClick(f[0].capturedAtMs + 1),
            evalLoopback(hash(0), Platform.Uber, f[0].capturedAtMs + 2),
            SessionReplay.ScreenInput(f[1]),
            evalLoopback(hash(1), Platform.Uber, f[1].capturedAtMs + 1),
            SessionReplay.ScreenInput(f[2]),
            evalLoopback(hash(2), Platform.Uber, f[2].capturedAtMs + 1),
        )
        return SessionReplay.reduceMixed(inputs)
    }

    private fun counts(steps: List<SessionReplay.ReplayStep>): Map<AppEventType, Int> =
        steps.flatMap { it.events }.groupingBy { it.type }.eachCount()

    private fun effects(steps: List<SessionReplay.ReplayStep>): List<AppEffect> =
        steps.flatMap { it.effects }

    // ---------------------------------------------------------------------------------------------
    // The three sanity checks the whole fix rests on
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the three Sonic frames share a presentationKey but churn the offerHash (#830 premise)`() {
        val obs = SessionReplay.replayRecognition(session)
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
    }

    @Test
    fun `one physical presentation - one OFFER_RECEIVED, zero OFFER_TIMEOUT (#830)`() {
        val steps = churnSteps()
        val c = counts(steps)
        assertEquals("exactly one OFFER_RECEIVED for the one physical offer", 1, c[AppEventType.OFFER_RECEIVED] ?: 0)
        assertEquals(
            "no 'Replaced by new offer' timeout between churned re-renders",
            0,
            c[AppEventType.OFFER_TIMEOUT] ?: 0,
        )
    }

    @Test
    fun `the offer is spoken exactly ONCE across the whole churn (#830 speak-once)`() {
        val steps = churnSteps()
        val speaks = effects(steps).count { it is AppEffect.SpeakOffer }
        assertEquals("SpeakOffer fires once per physical presentation, not once per re-quote", 1, speaks)
    }

    @Test
    fun `the heads-up notification live-updates on every eval landing, stale variant banners cleaned up (#830)`() {
        val steps = churnSteps()
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
    }

    @Test
    fun `an accept latch on variant 1 survives the churn to variant 2 and 3 (#830 latch survival)`() {
        val frames = SessionReplay.loadSession(session).sortedBy { it.capturedAtMs }
        val steps = churnSteps()

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
    }

    @Test
    fun `the expiry deadline stays anchored on the first frame's presentedAt (#830 - churn cannot extend TTL)`() {
        val frames = SessionReplay.loadSession(session).sortedBy { it.capturedAtMs }
        val steps = churnSteps()

        // presentedAt never moves off the FIRST frame across every enrich.
        val presentedFinal = steps.last().stateAfter.regions.platforms[Platform.Uber]
            ?.pendingOffers?.firstOrNull { it.acceptedAt == null }
        assertNotNull(presentedFinal)
        assertEquals(
            "presentedAt is pinned to the physical presentation epoch (frame 1)",
            frames[0].capturedAtMs,
            presentedFinal!!.presentedAt,
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
    }

    @Test
    fun `every OFFER_EXPIRY re-arm carries the CURRENT variant's hash (#830 - stale-hash timer can't strand)`() {
        val frames = SessionReplay.loadSession(session).sortedBy { it.capturedAtMs }
        val obs = SessionReplay.replayRecognition(frames)
        fun hash(i: Int) = (obs[i].parsed as ParsedFields.OfferFields).parsedOffer.offerHash
        val steps = churnSteps()

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

    // ---------------------------------------------------------------------------------------------
    // DoorDash regression — a GENUINELY different presentation still REPLACES (must not regress)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a distinct-store DoorDash offer still replaces the prior one - OFFER_TIMEOUT + re-speak (#830 no regression)`() {
        val frames = SessionReplay.loadSession(ddSession).sortedBy { it.capturedAtMs }
        assertEquals("two DoorDash offers (different stores)", 2, frames.size)
        val obs = SessionReplay.replayRecognition(frames)
        val offers = obs.map { (it.parsed as ParsedFields.OfferFields).parsedOffer }
        // Different stores → different presentationKey → the fix must NOT merge them.
        org.junit.Assert.assertNotEquals(
            "distinct-store offers have distinct presentation keys",
            offers[0].presentationKey,
            offers[1].presentationKey,
        )
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
        assertEquals("exactly one OFFER_RECEIVED (the replacement does not re-emit it, pre-#830)", 1, c[AppEventType.OFFER_RECEIVED] ?: 0)
        assertEquals(
            "each distinct presentation is spoken — the replacement re-speaks",
            2,
            effects(steps).count { it is AppEffect.SpeakOffer },
        )
    }
}
