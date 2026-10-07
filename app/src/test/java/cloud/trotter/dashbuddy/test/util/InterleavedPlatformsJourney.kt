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
 * 1. `sessions/doordash_offer_replace_2026_01_28` — `01_offer_cvs.json` at [CVS_MS] then, the
 *    capture's own 3 000 ms later, `02_offer_peter_piper.json`: a DoorDash offer REPLACED by another.
 *    The CVS heads-up's Accept intent is kept while it is posted and delivered at [STALE_TAP_MS] —
 *    after the replacement, with Peter Piper's card (and its live `accept_button`) in the foreground
 *    and Peter Piper's own heads-up up: the stale tap.
 * 2. `sessions/uber_offer_churn_2026_07_21` — the three Sonic re-quote frames (A $6.44 → B $6.42 →
 *    C $6.44), keeping their captured 3 212 / 10 573 ms gaps, starting [UBER_OFFER_MS]: an Uber offer
 *    overlay arrives while Peter Piper's DoorDash offer is still presented. Both platforms now hold an
 *    `OFFER_EXPIRY` timer AT ONCE (Uber's re-quotes even re-arm theirs meanwhile). Nobody acts on
 *    either: Peter Piper's card countdown runs out with no further frame, and DoorDash's OWN timer
 *    resolves it while Uber's keeps running.
 * 3. [DoorDashFullDashJourney] from its offer on: the stacked Bill Miller BBQ + Mama Margies offer
 *    (whose own OFFER_EXPIRY is armed, then CANCELLED by the accept — while Uber's is still pending),
 *    accepted from its own heads-up; both pickups. Uber's offer then expires on UBER's own timer
 *    between pickup frames 05 and 06, with DoorDash foreground and its job open.
 * 4. The rest of [DoorDashFullDashJourney] unchanged (06 → the receipt pair → the dash summary, with
 *    the journey's scalar substitutions).
 */
object InterleavedPlatformsJourney {

    const val REPLACE = "snapshots/sessions/doordash_offer_replace_2026_01_28"
    const val UBER = "snapshots/sessions/uber_offer_churn_2026_07_21"

    /** The DoorDash replacement pair, a minute before the journey's own offer. */
    const val CVS_MS = DoorDashFullDashJourney.OFFER_MS - 60_000L
    const val PETER_PIPER_MS = CVS_MS + 3_000L

    /** CVS's heads-up is up (the engine posts it 750 ms after the evaluation lands). */
    const val CVS_BANNER_MS = CVS_MS + 2_000L

    /** The stale CVS Accept lands once Peter Piper's own heads-up is up (2 s after the replacement). */
    const val STALE_TAP_MS = PETER_PIPER_MS + 2_000L

    /** Uber's first Sonic frame: while Peter Piper is still presented (its countdown runs ~35 s). */
    const val UBER_OFFER_MS = CVS_MS + 20_000L
    const val UBER_LAST_GAP_MS = 13_785L

    /** The journey offer's own heads-up Accept ([DoorDashFullDashJourney.ACCEPT_TAP_MS]). */
    const val JOURNEY_ACCEPT_TAP_MS = DoorDashFullDashJourney.ACCEPT_TAP_MS

    const val FRAME_05_MS = 1_783_283_894_971L
    const val FRAME_06_MS = 1_783_283_997_334L

    /** One platform's timer firing: the whole state and the shade just before (1 ms) and after (1 s). */
    data class Fire(
        val atMs: Long,
        val before: AppState,
        val after: AppState,
        val notificationsBefore: Set<Int>,
        val notificationsAfter: Set<Int>,
    )

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
        /** The state after Uber's last re-quote (Peter Piper still presented on DoorDash). */
        val atUberOffer: AppState,
        /** DoorDash's own OFFER_EXPIRY (Peter Piper) firing while Uber's is pending. */
        val peterPiperExpiry: Fire,
        /** Uber's OFFER_EXPIRY firing with DoorDash foreground and its job open. */
        val uberExpiry: Fire,
        /** The odometer's per-session miles for DoorDash just before Uber's session started. */
        val doorDashMilesBeforeUber: Double,
    )

    fun run(replay: E2ESessionReplay): Checkpoints {
        val replace = SessionReplay.loadSession(REPLACE).sortedBy { it.capturedAtMs }
        val stack = SessionReplay.loadSession(DoorDashFullDashJourney.STACK).associateBy { it.file.substringBefore('_') }
        val uber = SessionReplay.loadSession(UBER).sortedBy { it.capturedAtMs }
        check(replace.size == 2 && uber.size == 3) { "fixture sessions changed shape" }
        fun frame(n: String) = stack.getValue(n)
        fun hashOf(o: Observation.Screen) = (o.parsed as ParsedFields.OfferFields).parsedOffer.offerHash

        /** The deadline of the LAST `OFFER_EXPIRY` the engine was handed for [platform]'s [offerHash]. */
        fun expiryOf(platform: Platform, offerHash: String): Long = replay.executor.trace.mapNotNull { e ->
            (e.effect as? AppEffect.ScheduleTimeout)
                ?.takeIf { it.platform == platform && (it.payload as? ObservationPayload.OfferExpiry)?.offerHash == offerHash }
                ?.let { e.atMs + it.durationMs }
        }.last()

        fun serve(atMs: Long): Fire {
            replay.advanceTo(atMs - 1)
            val before = replay.manager.state.value
            val notesBefore = replay.activeNotificationIds()
            replay.advanceTo(atMs + 1_000L)
            return Fire(atMs, before, replay.manager.state.value, notesBefore, replay.activeNotificationIds())
        }

        // 1. DoorDash: CVS, replaced by Peter Piper; the CVS Accept is tapped late.
        val cvs = hashOf(replay.screen(replace[0], atMs = CVS_MS))
        replay.advanceTo(CVS_BANNER_MS)
        val staleAccept = replay.offerActionIntent(OfferIntent.ACCEPT, cvs)
        val peterPiper = hashOf(replay.screen(replace[1], atMs = PETER_PIPER_MS))
        replay.advanceTo(STALE_TAP_MS)
        val clicksBefore = replay.accessibility.clicks.size
        val notesBefore = replay.activeNotificationIds()
        replay.deliverOfferAction(staleAccept, STALE_TAP_MS, "stale tap Accept (CVS)")
        val clicksAfter = replay.accessibility.clicks.size
        val notesAfter = replay.activeNotificationIds()

        // 2. Uber's offer overlay, with Peter Piper still presented: two OFFER_EXPIRY timers at once.
        replay.advanceTo(UBER_OFFER_MS)
        val ddSession = checkNotNull(replay.manager.state.value.regions.platforms[Platform.DoorDash]?.session).sessionId
        val ddMilesBeforeUber = replay.odometer.getCurrentSessionMiles(ddSession)
        val shift = UBER_OFFER_MS - uber[0].capturedAtMs
        val uberHashes = uber.map { hashOf(replay.screen(it, atMs = it.capturedAtMs + shift)) }
        val atUber = replay.manager.state.value
        val ppExpiry = expiryOf(Platform.DoorDash, peterPiper)
        check(ppExpiry > UBER_OFFER_MS + UBER_LAST_GAP_MS && ppExpiry < DoorDashFullDashJourney.OFFER_MS) {
            "Peter Piper's expiry ($ppExpiry) must fall after Uber's last re-quote and before the journey offer"
        }
        val peterPiperFire = serve(ppExpiry)

        // 3. The journey's offer, accepted from its own heads-up (its expiry cancelled); the pickups.
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
        for (n in listOf("03", "04", "05")) replay.screen(frame(n))
        val uberExpiry = expiryOf(Platform.Uber, uberHashes.last())
        check(uberExpiry > FRAME_05_MS && uberExpiry < FRAME_06_MS) {
            "Uber's offer expiry ($uberExpiry) must fall while DoorDash is foreground, between frames 05 and 06"
        }
        val uberFire = serve(uberExpiry)

        // 4. The rest of the DoorDash journey, unchanged.
        for (n in listOf("06", "07", "08", "09", "10", "12")) replay.screen(frame(n))
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
            atUberOffer = atUber, peterPiperExpiry = peterPiperFire, uberExpiry = uberFire,
            doorDashMilesBeforeUber = ddMilesBeforeUber,
        )
    }
}
