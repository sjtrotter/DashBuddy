package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1078 — **the dash-end race, replayed from the frames that lost $21.00 in the field.**
 *
 * Real 2026-09-08 captures (build 8028691a, DoorDash 8.96.8, redacted on-device), from the accept
 * click through the delivery to the dash summary:
 *
 * ```
 * 16:51:59.157  accept_offer click
 * 16:52:01.500  pickup_navigation      → H-E-B
 * 17:14:36.939  pickup_pre_arrival
 * 17:14:44.188  pickup_shopping
 * 17:34:30.048  dropoff_pre_arrival
 * 17:34:33.108  dropoff_navigation
 * 17:41:25.509  dropoff_pre_arrival    → the ARRIVAL
 * 17:43:35.422  dropoff_photo
 * 17:43:43.157  dropoff_photo (Complete delivery)
 * 17:43:43.859  dash_along_the_way     → arms TASK_RETIRE, deadline 17:43:53.859
 * 17:43:48.709  end_dash_confirm
 * 17:43:52.265  dash_summary           → armed SESSION_END *over* the retire, 1.614 s early
 * 17:43:54.709  GRACE_COMMIT           → the teardown (the db's DASH_STOP occurredAt — the device's own
 *                                          timer carries a wake IDENTITY (#1054) and may fire a few ms
 *                                          shy of the deadline; the harness's payload-less timeout lapses
 *                                          a grace only strictly PAST it, so it is injected at deadline+1)
 * ```
 *
 * On master the last step force-stamped `completedAt` on the still-active drop and `mintQualified`'s
 * amdt-#5 T3 guard refused it: **ZERO `DELIVERY_COMPLETED`**, no row, no WARN. With #1078 the
 * `SESSION_END` ABSORBS the retire and `endSession` honors it, so the drop completes exactly once.
 *
 * Level-B assertions are hand-authored invariants, NEVER `replay == db` — the captured session's own
 * `app_events` log ENCODES the bug (its DELIVERY_COMPLETED count for this job is 0).
 *
 * **Fixture note — the offer overlay for this job was never captured.** The pull's `offer_popup/`
 * folder jumps from 15:54:06 to 17:53:16 while the accept CLICK at 16:51:59.157 is present, so the
 * job here forms from the pickup flow edge with no `PendingOffer` to latch. That costs this fixture
 * the `OFFER_ACCEPTED` outcome and the #691 offer-pay estimate (both are asserted at the unit level
 * in `:core:state`'s `DashEndHonorsTaskRetireTest`); it costs nothing on the completion itself,
 * which is what this replay exists to prove. A foreign offer frame was deliberately NOT spliced in —
 * it would carry another job's store hint and pay, forging exactly the kind of evidence #504 says
 * never to manufacture.
 */
class DashEndRaceReplayTest {

    private val session = "snapshots/sessions/dash_end_race_2026_09_08"

    /** The `dash_along_the_way` frame that armed the retire, and the summary that armed over it. */
    private val alongTheWayMs = 1_788_907_423_852L
    private val retireDeadlineMs = alongTheWayMs + 10_000L

    /** The authoritative summary grace (`AUTHORITATIVE_GRACE_MS`), armed by the `dash_summary` frame. */
    private val summaryGraceMs = 2_500L

    private fun counts(steps: List<SessionReplay.ReplayStep>): Map<AppEventType, Int> =
        steps.flatMap { it.events }.groupingBy { it.type }.eachCount()

    private fun distinctDropoffTaskIds(steps: List<SessionReplay.ReplayStep>): Set<String> =
        steps.flatMap { s ->
            val dd = s.stateAfter.regions.platforms[Platform.DoorDash]
            dd?.activeJob?.tasks.orEmpty() + dd?.recentTasks.orEmpty() + listOfNotNull(dd?.activeTask)
        }.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()

    @Test
    fun `the dash end honors the live task retire and records the drop exactly once (#1078)`() {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/01_accept_offer_click.json")
        val summaryMs = screens.single { it.frame.file.contains("dash_summary") }.atMs
        // Strictly past the SESSION_END deadline (see the class KDoc on why not the db's 54.709).
        val timer = SessionReplay.graceCommit(summaryMs + summaryGraceMs + 1L)
        val steps = SessionReplay.reduceMixed(screens + click + timer)
        val c = counts(steps)

        assertEquals("the pickup is confirmed once", 1, c[AppEventType.PICKUP_CONFIRMED] ?: 0)
        assertEquals("the drop is retired once", 1, c[AppEventType.DELIVERY_CONFIRMED] ?: 0)
        assertEquals("the dash ends once", 1, c[AppEventType.DASH_STOP] ?: 0)
        assertEquals("exactly one dropoff task ever exists", 1, distinctDropoffTaskIds(steps).size)
        assertEquals(
            "THE REGRESSION: on master this is 0 — the summary evicted the retire and the T3 guard " +
                "refused the force-stamp, losing the delivery silently (#1078)",
            1, c[AppEventType.DELIVERY_COMPLETED] ?: 0,
        )

        // The completion is stamped at the RETIRE's instant, not the teardown's — the absorbed value.
        val delivered = steps.last().stateAfter.regions.platforms[Platform.DoorDash]
            ?.recentTasks?.lastOrNull { it.phase == TaskPhase.DROPOFF }
        assertNotNull("the drop landed in recentTasks", delivered)
        assertEquals(
            "completedAt is the absorbed retire instant (the dash_along_the_way frame), " +
                "never the GRACE_COMMIT clock",
            alongTheWayMs, delivered!!.completedAt,
        )
        assertTrue("and it carries the customer identity it resolved", delivered.customerNameHash != null)
        assertEquals(
            "the honored teardown strands nothing — no #810 tripwire",
            0, c[AppEventType.JOB_ACCEPT_MISMATCH] ?: 0,
        )
    }

    @Test
    fun `a summary landing PAST the retire deadline records the same one delivery (timer parity)`() {
        // The other half of the race: had the dasher taken 2 s longer to reach the summary, the
        // retire's own lazy expiry would have committed first. Both orderings must record the drop
        // exactly once — the fix must not turn one path into a double-mint of the other.
        val screens = SessionReplay.loadSession(session)
            .filterNot { it.file.contains("dash_summary") }
            .map { SessionReplay.ScreenInput(it) }
        val lateSummary = SessionReplay.ScreenInput(
            SessionReplay.loadScreenFrame("$session/12_dash_summary.json", retireDeadlineMs + 2_000L),
        )
        val click = SessionReplay.loadClickFrame("$session/01_accept_offer_click.json")
        val steps = SessionReplay.reduceMixed(screens + lateSummary + click)
        val c = counts(steps)

        assertEquals("still exactly one dropoff", 1, distinctDropoffTaskIds(steps).size)
        assertEquals("the retire's own expiry confirms it once", 1, c[AppEventType.DELIVERY_CONFIRMED] ?: 0)
        assertEquals(
            "and the delivery is recorded exactly once on this path too",
            1, c[AppEventType.DELIVERY_COMPLETED] ?: 0,
        )
    }
}
