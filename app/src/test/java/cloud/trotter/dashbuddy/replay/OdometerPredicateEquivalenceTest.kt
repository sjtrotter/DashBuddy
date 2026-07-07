package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.core.state.OdometerArbiter
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #438 B5 (item 9) — checks the cross-platform odometer **stationary predicate against today's
 * per-edge odometer emissions** on the single-platform replay fixtures (the vet's acceptance bar).
 *
 * The comparison is over the **GPS on/off timeline** each approach produces, folded per step. Rather
 * than assert bit-identical (the predicate deliberately elides today's redundant Resume-while-moving
 * no-ops, and it FIXES one latent today-bug — see below), it proves three properties:
 *
 *  1. **No regression** — the predicate never leaves GPS off (paused/stopped) at a step where today's
 *     edge-based code had it on. A lost Resume = lost IRS miles; there are zero.
 *  2. **Semantic correctness** — at every step the predicate's GPS level equals the ground truth
 *     `activeSessionCount > 0 && !allLiveStationary` ([OdometerArbiter], the SSOT the diff uses).
 *  3. **The divergences are all improvements** — every step where the predicate and today's edges
 *     differ is one where the predicate turns GPS **on during an active drive** (a moving,
 *     non-arrived flow) that today left paused, because today's odometer keys on the **active task**
 *     and the task lagged in an ARRIVED state while the *flow* moved on. There are exactly THREE such
 *     steps across the corpus today, all the same shape:
 *       - `two_pickup_stack` final frame: the mamamargies drop's *navigation* screen, but the active
 *         task still shows the just-completed billmiller drop (ARRIVED) — today's odometer stays
 *         paused through the whole drive to mamamargies.
 *       - `receipt_skip` `waiting_for_offer` + `offer_popup` frames: the driver returns to
 *         waiting/an offer while the delivered drop's task is still ARRIVED (the skipped receipt
 *         never retired it) — today stays paused while idle/repositioning.
 *     All are predicate=ON / today=OFF (never the reverse). This is precisely the task-subphase lag
 *     the vet chose a **flow**-based predicate to fix — a strict improvement, not a regression.
 *     Documented + reported for adversarial ratification; the count is pinned so a NEW divergence
 *     (or a regression) trips this test.
 */
class OdometerPredicateEquivalenceTest {

    private enum class Gps { ON, OFF }

    // The FULL session corpus — every fixture under snapshots/sessions/ (adversarial-review MED:
    // the first cut listed 5 and silently dodged an oracle-ordering artifact on teardown_ghost;
    // the corpus-wide claim needs the corpus).
    private val fixtures = listOf(
        "snapshots/sessions/addon_phantom_2026_06_21",
        "snapshots/sessions/decline_race_2026_06_30",
        "snapshots/sessions/ghost_offer_2026_06_14",
        "snapshots/sessions/receipt_skip_2026_06_29",
        "snapshots/sessions/single_delivery_2026_06_16",
        "snapshots/sessions/teardown_ghost_2026_06_28",
        "snapshots/sessions/two_pickup_stack_2026_07_05",
        "snapshots/sessions/walgreens_placeholder_2026_06_21",
    )

    /** The NEW arbitration's GPS timeline — fold the emitted odometer effects, one state per step. */
    private fun actualTimeline(steps: List<SessionReplay.ReplayStep>): List<Gps> {
        var gps = Gps.OFF
        return steps.map { step ->
            for (e in step.effects) gps = when (e) {
                is AppEffect.StartOdometer, is AppEffect.ResumeOdometer -> Gps.ON
                is AppEffect.StopOdometer, is AppEffect.PauseOdometer -> Gps.OFF
                else -> gps
            }
            gps
        }
    }

    /** TODAY'S per-edge rules, reconstructed independently from the region state trace (see class doc). */
    private fun oracleTimeline(steps: List<SessionReplay.ReplayStep>): List<Gps> {
        var gps = Gps.OFF
        var prev = AppState()
        return steps.map { step ->
            val next = step.stateAfter
            val platforms = prev.regions.platforms.keys + next.regions.platforms.keys
            for (p in platforms) for (op in oldEdges(prev.regions.platforms[p], next.regions.platforms[p])) gps = op
            prev = next
            gps
        }
    }

    private fun oldEdges(prev: PlatformRegion?, next: PlatformRegion?): List<Gps> = buildList {
        val prevLive = prev?.session != null
        val nextLive = next?.session != null
        if (!prevLive && nextLive) add(Gps.ON)   // StartOdometer (session start)
        if (prevLive && !nextLive) add(Gps.OFF)  // StopOdometer (session end)
        if (!nextLive) return@buildList
        val pt = prev?.activeTask
        val nt = next?.activeTask
        // Emission ORDER is faithful to master's diffTask (adversarial-review MED): the task-change
        // Resumes fire BEFORE the arrival Pause, so a mint+arrive-in-one-frame step (teardown_ghost's
        // offer → pickup_shopping) folds to OFF — Resume then Pause — exactly as real master did.
        // The first-cut oracle applied the Pause first and manufactured phantom "regressions" there.
        if (nt != null && nt.phase == TaskPhase.PICKUP && pt?.taskId != nt.taskId) add(Gps.ON)    // Resume (new pickup nav)
        if (nt?.phase == TaskPhase.DROPOFF && nt.taskId != pt?.taskId) add(Gps.ON)                // Resume (new dropoff leg)
        if (nt?.arrivedAt != null && pt?.arrivedAt == null) add(Gps.OFF)                          // PauseOdometer (arrival)
        if (next?.lastActedFlow == Flow.PostTask && prev?.lastActedFlow != Flow.PostTask) add(Gps.ON) // Resume (PostTask entry)
    }

    /** The GPS ground truth per step, straight from the arbiter (property 2's SSOT). */
    private fun semanticTimeline(steps: List<SessionReplay.ReplayStep>): List<Gps> = steps.map { step ->
        val s = step.stateAfter
        val live = s.regions.crossPlatform.activeSessionCount > 0
        if (live && !OdometerArbiter.allLiveStationary(s.regions.platforms)) Gps.ON else Gps.OFF
    }

    /** A live region on a moving (non-arrived) flow — the shape of the today-bug the predicate fixes. */
    private fun anyLiveMoving(step: SessionReplay.ReplayStep): Boolean =
        step.stateAfter.regions.platforms.values.any {
            it.session != null && it.lastActedFlow != Flow.TaskPickupArrived && it.lastActedFlow != Flow.TaskDropoffArrived
        }

    @Test
    fun `predicate never regresses vs today's per-edge odometer (no lost Resume)`() {
        for (fixture in fixtures) {
            val steps = SessionReplay.reduce(fixture)
            val actual = actualTimeline(steps)
            val oracle = oracleTimeline(steps)
            actual.indices.forEach { i ->
                assertFalse(
                    "REGRESSION on $fixture step $i: predicate OFF while today had GPS ON",
                    actual[i] == Gps.OFF && oracle[i] == Gps.ON,
                )
            }
        }
    }

    @Test
    fun `predicate GPS level equals the semantic ground truth at every step`() {
        for (fixture in fixtures) {
            val steps = SessionReplay.reduce(fixture)
            assertEquals(
                "predicate GPS level diverges from ground truth on $fixture",
                semanticTimeline(steps),
                actualTimeline(steps),
            )
        }
    }

    @Test
    fun `every divergence from today's edges is GPS-on during an active drive (an improvement)`() {
        val divergences = mutableListOf<Pair<String, Int>>()
        for (fixture in fixtures) {
            val steps = SessionReplay.reduce(fixture)
            val actual = actualTimeline(steps)
            val oracle = oracleTimeline(steps)
            actual.indices.forEach { i ->
                if (actual[i] != oracle[i]) {
                    divergences += fixture.substringAfterLast('/') to i
                    assertTrue(
                        "$fixture step $i: a divergence must be predicate=ON, today=OFF",
                        actual[i] == Gps.ON && oracle[i] == Gps.OFF,
                    )
                    assertTrue(
                        "$fixture step $i: the improvement must be GPS-on during a live moving flow",
                        anyLiveMoving(steps[i]),
                    )
                }
            }
        }
        // EXACTLY these three known improvements across the whole corpus (see class doc):
        // two_pickup_stack's mamamargies-drive final frame + receipt_skip's waiting/offer frames,
        // all today-left-paused during a live moving flow. Pinned as (fixture, step) pairs —
        // not a bare count (adversarial-review LOW) — so a NEW divergence appearing while an old
        // one vanishes cannot pass silently.
        assertEquals(
            "exactly the three known task-lag GPS-during-drive improvements",
            listOf(
                "receipt_skip_2026_06_29" to 2,
                "receipt_skip_2026_06_29" to 3,
                "two_pickup_stack_2026_07_05" to 9,
            ),
            divergences.sortedWith(compareBy({ it.first }, { it.second })),
        )
    }
}
