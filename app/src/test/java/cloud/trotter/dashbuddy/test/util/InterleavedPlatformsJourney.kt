package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.DoorDashFullDashJourney.substituted

/**
 * #1271 scenario 3 — DoorDash AND Uber live at once, composed EXPLICITLY from committed captures
 * (no new capture, no synthetic observation). It is [DoorDashFullDashJourney] with two committed
 * sessions spliced in, re-timed onto the journey's clock; every committed file is read, never
 * modified:
 *
 * 1. `sessions/doordash_offer_replace_2026_01_28` — `01_offer_cvs.json` then, [REPLACE_GAP_MS] later
 *    (the capture's own 3 000 ms gap), `02_offer_peter_piper.json`: a DoorDash offer REPLACED by
 *    another before the journey's own offer. The CVS heads-up's Accept intent is kept while it is
 *    posted, and delivered at [STALE_TAP_MS] — after the replacement, with Peter Piper's card (and
 *    its live `accept_button`) in the foreground: the stale tap.
 * 2. [DoorDashFullDashJourney] frames 01–08 (the stacked Bill Miller BBQ + Mama Margies offer,
 *    accepted from its own heads-up, both pickups, Bill Miller's first dropoff surface).
 * 3. `sessions/uber_offer_churn_2026_07_21` — the three Sonic re-quote frames (A $6.44 → B $6.42 →
 *    C $6.44), keeping their captured 3 212 / 10 573 ms gaps, starting [UBER_OFFER_MS]: the dasher
 *    switches to Uber mid-delivery. Nobody acts on it.
 * 4. Back to DoorDash: frame 08 re-shown at [BACK_TO_DOORDASH_MS] (the Uber overlay vanished with
 *    no closing frame — on-device that is the common case). Uber's offer must then be resolved by
 *    UBER's own `OFFER_EXPIRY` timer while DoorDash is foreground.
 * 5. The rest of [DoorDashFullDashJourney] unchanged (09 → the receipt pair → the dash summary).
 *    The summary keeps the journey's scalar substitutions.
 */
object InterleavedPlatformsJourney {

    const val REPLACE = "snapshots/sessions/doordash_offer_replace_2026_01_28"
    const val UBER = "snapshots/sessions/uber_offer_churn_2026_07_21"

    /** The DoorDash replacement pair, half a minute before the journey's own offer. */
    const val CVS_MS = DoorDashFullDashJourney.OFFER_MS - 30_000L
    const val REPLACE_GAP_MS = 3_000L
    const val PETER_PIPER_MS = CVS_MS + REPLACE_GAP_MS

    /** CVS's heads-up is up (the engine posts it 750 ms after the evaluation lands). */
    const val CVS_BANNER_MS = CVS_MS + 2_000L

    /** The stale CVS Accept lands once Peter Piper's own heads-up is up (2 s after the replacement). */
    const val STALE_TAP_MS = PETER_PIPER_MS + 2_000L

    /** The journey offer's own heads-up Accept ([DoorDashFullDashJourney.ACCEPT_TAP_MS]). */
    const val JOURNEY_ACCEPT_TAP_MS = DoorDashFullDashJourney.ACCEPT_TAP_MS

    /** Uber's first Sonic frame: a minute after Bill Miller's first dropoff surface (frame 08). */
    const val FRAME_08_MS = 1_783_284_476_286L
    const val FRAME_09_MS = 1_783_285_101_270L
    const val UBER_OFFER_MS = FRAME_08_MS + 60_000L

    /** Back in DoorDash 20 s after Uber's last re-quote — Uber's offer is still pending there. */
    const val UBER_LAST_GAP_MS = 13_785L
    const val BACK_TO_DOORDASH_MS = UBER_OFFER_MS + UBER_LAST_GAP_MS + 20_000L

    /** Everything observed only DURING the run. */
    data class Checkpoints(
        val cvsHash: String,
        val peterPiperHash: String,
        val journeyOfferHash: String,
        val uberHashes: List<String>,
        /** Clicks recorded just before / after the stale tap. */
        val clicksBeforeStaleTap: Int,
        val clicksAfterStaleTap: Int,
        /** Notification ids posted just before the stale tap, and the ids just after it. */
        val notificationsBeforeStaleTap: Set<Int>,
        val notificationsAfterStaleTap: Set<Int>,
        /** The state after Uber's last re-quote, and as DoorDash comes back. */
        val atUberOffer: AppState,
        val atBackToDoorDash: AppState,
        /** The shade as DoorDash comes back (Uber's offer still pending). */
        val notificationsAtBackToDoorDash: Set<Int>,
        /** The whole state 1 ms before Uber's OFFER_EXPIRY fires, and 1 s after (DoorDash foreground). */
        val beforeUberExpiry: AppState,
        val afterUberExpiry: AppState,
        val notificationsAfterUberExpiry: Set<Int>,
        val uberExpiryAtMs: Long,
        /** The odometer's per-session miles just before Uber's session started (DoorDash's). */
        val doorDashMilesBeforeUber: Double,
    )

    fun run(replay: E2ESessionReplay): Checkpoints {
        val replace = SessionReplay.loadSession(REPLACE).sortedBy { it.capturedAtMs }
        val stack = SessionReplay.loadSession(DoorDashFullDashJourney.STACK).associateBy { it.file.substringBefore('_') }
        val uber = SessionReplay.loadSession(UBER).sortedBy { it.capturedAtMs }
        check(replace.size == 2 && uber.size == 3) { "fixture sessions changed shape" }
        fun frame(n: String) = stack.getValue(n)
        fun hashOf(o: Observation.Screen) = (o.parsed as ParsedFields.OfferFields).parsedOffer.offerHash

        // 1. DoorDash: CVS, replaced by Peter Piper; the CVS Accept is tapped late.
        val cvs = hashOf(replay.screen(replace[0], atMs = CVS_MS))
        // The engine posts the heads-up after its screenshot settle (`OFFER_NOTIFICATION_DELAY_MS`).
        replay.advanceTo(CVS_BANNER_MS)
        val staleAccept = replay.offerActionIntent(OfferIntent.ACCEPT, cvs)
        val peterPiper = hashOf(replay.screen(replace[1], atMs = PETER_PIPER_MS))
        replay.advanceTo(STALE_TAP_MS)
        val clicksBefore = replay.accessibility.clicks.size
        val notesBefore = replay.activeNotificationIds()
        replay.deliverOfferAction(staleAccept, STALE_TAP_MS, "stale tap Accept (CVS)")
        val clicksAfter = replay.accessibility.clicks.size
        val notesAfter = replay.activeNotificationIds()

        // 2. The journey's offer, accepted from its own heads-up; pickups; Bill Miller's dropoff.
        val journeyOffer = hashOf(replay.screen(frame("01")))
        replay.advanceTo(JOURNEY_ACCEPT_TAP_MS)
        replay.deliverOfferAction(
            replay.offerActionIntent(OfferIntent.ACCEPT, journeyOffer),
            JOURNEY_ACCEPT_TAP_MS, "tap Accept (journey offer)",
        )
        check(replay.accessibility.clicks.size == clicksAfter + 1) {
            "the journey Accept did not physically click — not feeding the click capture\n${replay.trace()}"
        }
        replay.click(SessionReplay.loadClickFrame("${DoorDashFullDashJourney.STACK}/02_accept_offer_click.json"))
        for (n in listOf("03", "04", "05", "06", "07", "08")) replay.screen(frame(n))

        // 3. Uber, mid-delivery: the Sonic re-quotes at their captured spacing.
        val shift = UBER_OFFER_MS - uber[0].capturedAtMs
        replay.advanceTo(UBER_OFFER_MS)
        val ddSession = checkNotNull(replay.manager.state.value.regions.platforms[Platform.DoorDash]?.session).sessionId
        val ddMilesBeforeUber = replay.odometer.getCurrentSessionMiles(ddSession)
        val uberHashes = uber.map { hashOf(replay.screen(it, atMs = it.capturedAtMs + shift)) }
        val atUber = replay.manager.state.value

        // 4. Back to DoorDash with Uber's offer still pending; Uber's own timer resolves it.
        replay.screen(frame("08"), atMs = BACK_TO_DOORDASH_MS)
        val atBack = replay.manager.state.value
        val notesAtBack = replay.activeNotificationIds()
        // The engine's own arm (the last re-arm keeps the first presentation's deadline, #830).
        val expiryAt = replay.executor.trace.mapNotNull { e ->
            (e.effect as? AppEffect.ScheduleTimeout)
                ?.takeIf { it.platform == Platform.Uber && it.payload is ObservationPayload.OfferExpiry }
                ?.let { e.atMs + it.durationMs }
        }.max()
        check(expiryAt > BACK_TO_DOORDASH_MS && expiryAt < FRAME_09_MS) {
            "Uber's offer expiry ($expiryAt) must fall while DoorDash is foreground, before frame 09"
        }
        replay.advanceTo(expiryAt - 1)
        val beforeExpiry = replay.manager.state.value
        replay.advanceTo(expiryAt + 1_000L)
        val afterExpiry = replay.manager.state.value
        val notesAfterExpiry = replay.activeNotificationIds()

        // 5. The rest of the DoorDash journey, unchanged.
        for (n in listOf("09", "10", "12")) replay.screen(frame(n))
        replay.screen(frame("03"), atMs = DoorDashFullDashJourney.BILL_RETIRE_ARM_MS)
        replay.advanceTo(DoorDashFullDashJourney.BILL_RETIRED_MS)
        for (n in listOf("13", "11")) replay.screen(frame(n))
        replay.screen(frame("12"), atMs = DoorDashFullDashJourney.MAMA_ARRIVAL_MS)
        replay.screen(SessionReplay.loadScreenFrame(DoorDashFullDashJourney.COLLAPSED, DoorDashFullDashJourney.RECEIPT_COLLAPSED_MS))
        replay.screen(SessionReplay.loadScreenFrame(DoorDashFullDashJourney.EXPANDED, DoorDashFullDashJourney.RECEIPT_EXPANDED_MS))
        replay.advanceTo(DoorDashFullDashJourney.RECEIPT_HELD_MS)
        val summary = SessionReplay.loadScreenFrame(DoorDashFullDashJourney.SUMMARY, DoorDashFullDashJourney.SUMMARY_MS)
        replay.screen(summary, node = summary.node.substituted(DoorDashFullDashJourney.SUMMARY_SUBSTITUTIONS))
        replay.advanceTo(DoorDashFullDashJourney.END_MS)

        return Checkpoints(
            cvsHash = cvs, peterPiperHash = peterPiper, journeyOfferHash = journeyOffer, uberHashes = uberHashes,
            clicksBeforeStaleTap = clicksBefore, clicksAfterStaleTap = clicksAfter,
            notificationsBeforeStaleTap = notesBefore, notificationsAfterStaleTap = notesAfter,
            atUberOffer = atUber, atBackToDoorDash = atBack, notificationsAtBackToDoorDash = notesAtBack,
            beforeUberExpiry = beforeExpiry, afterUberExpiry = afterExpiry,
            notificationsAfterUberExpiry = notesAfterExpiry, uberExpiryAtMs = expiryAt,
            doorDashMilesBeforeUber = ddMilesBeforeUber,
        )
    }
}
