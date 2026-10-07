package cloud.trotter.dashbuddy.replay

import android.util.Log
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.E2ESessionReplay
import cloud.trotter.dashbuddy.test.util.InfoPlusPiiGate
import cloud.trotter.dashbuddy.test.util.InterleavedPlatformsJourney
import cloud.trotter.dashbuddy.test.util.InterleavedPlatformsJourney.UBER_OFFER_MS
import cloud.trotter.dashbuddy.test.util.RecordingTree
import cloud.trotter.dashbuddy.test.util.ReplayApplication
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * #1271 scenario 3 — **two platforms live at once, end to end** ([E2ESessionReplay]): a DoorDash
 * dash with a replaced offer, a stale heads-up Accept, and an Uber offer that arrives while a DoorDash
 * offer is still presented (both platforms holding an `OFFER_EXPIRY` at once) and is left to expire
 * mid-pickup while the DoorDash job is open. The journey is composed explicitly in
 * [InterleavedPlatformsJourney] from three committed sessions plus the DoorDash full-dash journey's
 * receipt/summary splices: `sessions/doordash_offer_replace_2026_01_28`,
 * `sessions/two_pickup_stack_2026_07_05`, `sessions/uber_offer_churn_2026_07_21`, the 2026-08-23
 * collapsed/expanded receipt pair and `sessions/dash_end_race_2026_09_08/12_dash_summary.json`.
 *
 * Every assertion is a hand-authored correct-behaviour invariant; none compares the run to a
 * captured database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ReplayApplication::class)
class InterleavedPlatformsE2ETest {

    @Test
    fun `DoorDash and Uber run side by side - a stale Accept clicks nothing and neither platform touches the other`() {
        val app = RuntimeEnvironment.getApplication() as ReplayApplication
        val tree = RecordingTree()
        Timber.plant(tree)
        val replay = E2ESessionReplay(app, InterleavedPlatformsJourney.CVS_MS - 10_000L)
        try {
            replay.start()
            val bootstrap = tree.records.size
            val cp = InterleavedPlatformsJourney.run(replay)
            replay.drain()
            val trace = replay.trace()
            val effects = replay.executor.trace.map { it.effect }

            assertTrue("every composed frame is recognized and forwarded\n$trace", replay.inputs.all { it.forwarded })
            assertEquals("the three Uber re-quotes are ONE presentation (A → B → A)", cp.uberHashes[0], cp.uberHashes[2])

            // ── The stale Accept: zero physical clicks, the replacement untouched ───────────────────
            assertEquals("the stale CVS Accept produced zero physical clicks", cp.clicksBeforeStaleTap, cp.clicksAfterStaleTap)
            val ppBanner = BubbleManager.offerNotificationId(cp.peterPiperHash)
            assertTrue("the replacement's (Peter Piper's) heads-up was up when the stale tap landed", ppBanner in cp.notificationsBeforeStaleTap)
            assertTrue("…and the stale tap did not dismiss it (the receiver cancels only the tapped offer's id)", ppBanner in cp.notificationsAfterStaleTap)
            assertFalse("the replaced CVS banner was already gone", BubbleManager.offerNotificationId(cp.cvsHash) in cp.notificationsBeforeStaleTap)
            assertTrue(
                "the stale tap was refused for its carried identity (abort to manual)",
                tree.records.any { it.priority == Log.WARN && it.message.contains("dropped (#438 B3)") && it.message.contains(cp.cvsHash) },
            )
            val accepts = replay.executor.trace.filter { (it.effect as? AppEffect.PerformRuleAction)?.action == RuleAction.ACCEPT_OFFER }
            assertEquals(
                "exactly one Accept action reached the engine — the journey offer's own tap, never the stale one",
                listOf(InterleavedPlatformsJourney.JOURNEY_ACCEPT_TAP_MS), accepts.map { it.atMs },
            )
            assertEquals(ActionTrigger.USER, (accepts.single().effect as AppEffect.PerformRuleAction).trigger)
            assertTrue("…and it clicked DoorDash's accept button", replay.accessibility.clicks.single().viewId!!.endsWith(":id/accept_button"))

            // ── Sessions: one per platform, each with its own lifecycle ───────────────────────────
            val starts = effects.filterIsInstance<AppEffect.StartSession>()
            assertEquals("one session start per platform", listOf("DoorDash", "Uber"), starts.map { it.platformName })
            val ddSession = starts[0].sessionId
            val uberSession = starts[1].sessionId
            assertEquals("both sessions live at Uber's offer", 2, cp.atUberOffer.regions.crossPlatform.activeSessionCount)
            val ddAtUber = cp.atUberOffer.regions.platforms[Platform.DoorDash]
            assertEquals("Uber's offer did not disturb DoorDash's session", ddSession, ddAtUber?.session?.sessionId)
            assertEquals("…nor DoorDash's presented offer", cp.peterPiperHash, ddAtUber?.presentedOffer()?.offerHash)
            assertNotNull("DoorDash's job was open when Uber's offer expired", cp.uberExpiry.before.regions.platforms[Platform.DoorDash]?.activeJob)
            val ends = effects.filterIsInstance<AppEffect.EndSession>()
            assertEquals("only DoorDash's dash ended", listOf(ddSession), ends.map { it.sessionId })
            val final = replay.manager.state.value.regions
            assertNull("DoorDash is off", final.platforms[Platform.DoorDash]?.session)
            assertEquals("Uber is still online", uberSession, final.platforms[Platform.Uber]?.session?.sessionId)
            assertEquals(Mode.Online, final.platforms[Platform.Uber]?.mode)

            // ── Timers: the SAME type on both platforms at once; each fires for its own offer ──────
            val pp = cp.peterPiperExpiry
            val ub = cp.uberExpiry
            assertNotNull("both offers were pending as DoorDash's timer came due", pp.before.regions.platforms[Platform.Uber]?.presentedOffer())
            assertEquals(cp.peterPiperHash, pp.before.regions.platforms[Platform.DoorDash]?.presentedOffer()?.offerHash)
            assertNull("DoorDash's OWN timer resolved Peter Piper at its deadline", pp.after.regions.platforms[Platform.DoorDash]?.presentedOffer())
            assertEquals(
                "…and Uber's pending offer — armed AFTER DoorDash's, re-armed twice by its re-quotes — was left exactly as it was",
                pp.before.regions.platforms[Platform.Uber], pp.after.regions.platforms[Platform.Uber],
            )
            val armsBetween = replay.executor.trace.filter { it.atMs in UBER_OFFER_MS until pp.atMs }
                .mapNotNull { it.effect as? AppEffect.ScheduleTimeout }.filter { it.type == TimeoutType.OFFER_EXPIRY }
            assertEquals(
                "Uber armed OFFER_EXPIRY three times while DoorDash's was pending (a type-keyed timer would have replaced it)",
                List(3) { Platform.Uber }, armsBetween.map { it.platform },
            )
            val ddCancel = replay.executor.trace.single {
                (it.effect as? AppEffect.CancelTimeout)?.let { c -> c.type == TimeoutType.OFFER_EXPIRY && c.platform == Platform.DoorDash } == true &&
                    it.atMs in (pp.atMs + 1) until ub.atMs
            }
            assertTrue(
                "DoorDash cancelled its journey offer's expiry on the accept, while Uber's was pending",
                ddCancel.atMs >= InterleavedPlatformsJourney.JOURNEY_ACCEPT_TAP_MS,
            )
            assertEquals(
                "Uber's expiry still fired at its FIRST presentation + the 120 s default (neither DoorDash's cancel nor its re-arms moved it)",
                UBER_OFFER_MS + 120_000L, ub.atMs,
            )
            assertNotNull("Uber's offer was pending just before", ub.before.regions.platforms[Platform.Uber]?.presentedOffer())
            assertNull("Uber's own timer resolved it", ub.after.regions.platforms[Platform.Uber]?.presentedOffer())
            assertEquals(
                "Uber's expiry, firing with DoorDash foreground, left DoorDash's region exactly as it was",
                ub.before.regions.platforms[Platform.DoorDash], ub.after.regions.platforms[Platform.DoorDash],
            )
            assertEquals("…and the screen-truth region too", ub.before.regions.flow, ub.after.regions.flow)
            val uberArms = effects.filterIsInstance<AppEffect.ScheduleTimeout>().filter { it.platform == Platform.Uber }
            assertTrue(
                "Uber armed only its offer expiry, for its own hashes",
                uberArms.all { it.type == TimeoutType.OFFER_EXPIRY && (it.payload as ObservationPayload.OfferExpiry).offerHash in cp.uberHashes },
            )
            val uberCancels = effects.filterIsInstance<AppEffect.CancelTimeout>().filter { it.platform == Platform.Uber }
            assertEquals("Uber cancelled only its own offer timer, once", listOf(TimeoutType.OFFER_EXPIRY), uberCancels.map { it.type })
            assertTrue(
                "every grace/settle timer was armed on DoorDash's own key",
                effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                    .filter { it.type != TimeoutType.OFFER_EXPIRY }.all { it.platform == Platform.DoorDash },
            )

            // ── Notifications: each banner is its own offer's; speech once per presentation ──────
            val uberBanner = BubbleManager.offerNotificationId(cp.uberHashes.last())
            assertTrue("Peter Piper's heads-up was up until its expiry", ppBanner in pp.notificationsBefore)
            assertFalse("…which dismissed it", ppBanner in pp.notificationsAfter)
            assertTrue("…and not Uber's", uberBanner in pp.notificationsBefore && uberBanner in pp.notificationsAfter)
            assertTrue("Uber's heads-up survived DoorDash's accept and pickups", uberBanner in ub.notificationsBefore)
            assertFalse("…until Uber's own expiry dismissed it", uberBanner in ub.notificationsAfter)
            val posts = replay.bubble.calls("postOfferNotification")
            assertEquals(
                "posts per platform: CVS, Peter Piper, the journey offer; Uber's three live re-quotes",
                mapOf(Platform.DoorDash to 3, Platform.Uber to 3),
                posts.groupingBy { it.args[2] as Platform }.eachCount(),
            )
            assertEquals("one utterance per physical presentation (3 DoorDash + 1 Uber)", 4, replay.tts.spoken.size)
            val bannerIds = (listOf(cp.cvsHash, cp.peterPiperHash, cp.journeyOfferHash) + cp.uberHashes)
                .map(BubbleManager::offerNotificationId).toSet()
            assertTrue("no offer banner outlives the run", replay.activeNotificationIds().none { it in bannerIds })

            // ── Odometer: one GPS feed, independent per-session anchors ─────────────────────────
            assertEquals(
                "one GPS start for the 0→1 live-session crossing — Uber's start does not restart it",
                1, effects.count { it is AppEffect.StartOdometer },
            )
            assertEquals("DoorDash's end does not stop GPS while Uber is still online", 0, effects.count { it is AppEffect.StopOdometer })
            assertTrue("…so it still collects", replay.location.activeCollectors > 0)
            val ddMiles = replay.odometer.getCurrentSessionMiles(ddSession)
            val uberMiles = replay.odometer.getCurrentSessionMiles(uberSession)
            assertTrue("DoorDash had driven before Uber came on", cp.doorDashMilesBeforeUber > 0.0)
            assertTrue("Uber's anchor is its own: it accrues from its start", uberMiles > 0.0)
            assertEquals(
                "Uber's start did not re-anchor DoorDash: the two differ by exactly DoorDash's miles at Uber's start",
                cp.doorDashMilesBeforeUber, ddMiles - uberMiles, 1e-6,
            )

            // ── Read model: no cross-platform contamination ─────────────────────────────────────
            val sessions = rows(replay, "session_records").associateBy { it.getValue("sessionId") }
            assertEquals("one session row per platform", setOf(ddSession, uberSession), sessions.keys)
            val ddRow = sessions.getValue(ddSession)
            val uberRow = sessions.getValue(uberSession)
            assertEquals("doordash", ddRow["platform"])
            assertEquals("uber", uberRow["platform"])
            assertEquals(
                "DoorDash: three offers (CVS replaced, Peter Piper expired, the stack accepted), two deliveries, one job, the summary's total",
                listOf("3", "1", "2", "2", "1", "summary_screen", "16.7"),
                listOf("offersReceived", "offersAccepted", "offersTimeout", "deliveries", "jobsCompleted", "endSource", "reportedEarnings").map { ddRow[it] },
            )
            assertNotNull("DoorDash's session ended", ddRow["endedAt"])
            assertEquals(
                "Uber: its one offer (expired), nothing delivered, still open, no reported total",
                listOf("1", "0", "1", "0", "0", null, null),
                listOf("offersReceived", "offersAccepted", "offersTimeout", "deliveries", "jobsCompleted", "endedAt", "reportedEarnings").map { uberRow[it] },
            )

            val deliveries = rows(replay, "delivery_records")
            assertEquals("two delivery rows", 2, deliveries.size)
            assertTrue("both are DoorDash's, in DoorDash's session", deliveries.all { it["platform"] == "doordash" && it["sessionId"] == ddSession })
            assertEquals(
                "each half of the \$16.70 receipt (DROP_SHARE)",
                listOf("DROP_SHARE" to 8.35, "DROP_SHARE" to 8.35),
                deliveries.map { it["payBasis"] to it.getValue("realizedPay")!!.toDouble() },
            )

            val offers = rows(replay, "offer_records")
            assertEquals("four offer rows", 4, offers.size)
            offers.forEach { o ->
                assertEquals(
                    "offer ${o["offerHash"]} sits in a session of its own platform",
                    o["platform"], sessions.getValue(o.getValue("sessionId"))["platform"],
                )
            }
            val uberOffer = offers.single { it["platform"] == "uber" }
            assertEquals(cp.uberHashes[0], uberOffer["offerHash"])
            assertEquals("Uber's offer timed out", "OFFER_TIMEOUT", uberOffer["outcome"])
            assertEquals("…decided at its own expiry", ub.atMs.toString(), uberOffer["decidedAt"])
            assertEquals(
                "Peter Piper was decided at ITS expiry — not replaced later by the journey offer",
                pp.atMs.toString(), offers.single { it["offerHash"] == cp.peterPiperHash }["decidedAt"],
            )
            assertEquals(
                "DoorDash's three outcomes — the stale tap accepted nothing",
                mapOf(cp.cvsHash to "OFFER_TIMEOUT", cp.peterPiperHash to "OFFER_TIMEOUT", cp.journeyOfferHash to "OFFER_ACCEPTED"),
                offers.filter { it["platform"] == "doordash" }.associate { it["offerHash"] to it["outcome"] },
            )

            // ── Privacy: the shareable stream the interleaved run wrote ─────────────────────────
            InfoPlusPiiGate.assertClean("interleaved platforms", replay, tree.records, bootstrap)
        } finally {
            replay.close()
            if (tree in Timber.forest()) Timber.uproot(tree)
        }
    }

    /** Every row of [table], as column → value. */
    private fun rows(replay: E2ESessionReplay, table: String): List<Map<String, String?>> =
        replay.db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).associate { c.getColumnName(it) to (if (c.isNull(it)) null else c.getString(it)) })
                }
            }
        }
}
