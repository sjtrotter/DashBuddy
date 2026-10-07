package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.core.state.OdometerArbiter
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fold emitted odometer effects across the session corpus and compare the GPS level at every step
 * with the current arbiter's semantic ground truth: an active session that is not stationary.
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

    /** Fold the emitted odometer effects, one GPS state per step. */
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

    /** The GPS ground truth per step, straight from the arbiter (the SSOT). */
    private fun semanticTimeline(steps: List<SessionReplay.ReplayStep>): List<Gps> = steps.map { step ->
        val s = step.stateAfter
        val live = s.regions.crossPlatform.activeSessionCount > 0
        if (live && !OdometerArbiter.allLiveStationary(s.regions.platforms)) Gps.ON else Gps.OFF
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
}
