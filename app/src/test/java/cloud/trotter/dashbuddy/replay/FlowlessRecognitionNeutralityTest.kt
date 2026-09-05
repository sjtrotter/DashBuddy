package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #995 review F7 — **"recognize-only" does NOT mean "state-inert", and this pins what it does mean.**
 *
 * The claim originally written on `doordash.screen.pickup_receipt_scan` ("recognize-only, so it is
 * inert to the state machine") is mechanically FALSE, and it matters enough to get right:
 * `AccessibilityPipeline.output()` drops UNKNOWN frames *before* the state machine, so giving a
 * previously-UNKNOWN surface a rule genuinely changes behaviour — those frames now
 *  (a) reach the state machine as `Observation.Screen`s at all, and
 *  (b) update `FrameGate`'s `lastIdentity`, changing which *subsequent* frames are admitted.
 * "No `state` block" only means the rule contributes no flow and no `modeHint`; it does not mean
 * the frame is invisible. The #498 phantom-offer guards are the standing counterexample class —
 * state that keys off *what the previous frame was* can be moved by a frame that declares nothing.
 *
 * So the honest property is **neutrality, not invisibility**: a recognized frame carrying no flow
 * and no modeHint must leave the session/task lifecycle exactly as it found it. That is what the
 * two new receipt-scan surfaces need to be true, and it is cheap to assert end-to-end through the
 * REAL classifier + REAL [cloud.trotter.dashbuddy.core.state.StateMachine] rather than argued in a
 * comment.
 *
 * Deliberately NOT in `AllMatchersSuite`: that suite is the fast recognition-only loop (no state
 * machine, per the root CLAUDE.md), and this test folds through the state machine.
 */
class FlowlessRecognitionNeutralityTest {

    @Test
    fun `the receipt-scan frames recognize, carry no flow, and leave PlatformRegion untouched (#995)`() {
        assertFlowlessAndNeutral("snapshots/pickup_receipt_scan", "doordash.screen.pickup_receipt_scan")
    }

    /**
     * #985 — the same property for the Timeline order-detail sheet, the second recognize-only
     * surface added purely to give a leaking frame a `redact` block. It is asserted for the same
     * reason: the rule buys the privacy fix by making previously-UNKNOWN frames reach the state
     * machine, and "it declares no `state`" is a claim about the JSON, not about behaviour.
     */
    @Test
    fun `the timeline task-detail frames recognize, carry no flow, and leave PlatformRegion untouched (#985)`() {
        assertFlowlessAndNeutral(
            "snapshots/timeline_task_detail",
            "doordash.screen.timeline_task_detail",
        )
    }

    /**
     * #1058 — the third recognize-only surface, and the same reason again: DoorDash 8.93.7's
     * id-LESS drop-off workflow sheet shipped a full street line, a unit number and the
     * customer's quoted note (with a door code in it) to UNKNOWN captures, and the only control
     * for a fully id-less surface is a rule that recognizes it and declares a `redact`. The rule
     * declares no `state` deliberately — whether this sheet is 8.93.7's replacement for the
     * id-bearing arrival card, and should therefore carry `task:dropoff:*`, is an open design
     * question a privacy fix must not answer by accident. This is what pins that it did not.
     */
    @Test
    fun `the dropoff workflow-sheet frames recognize, carry no flow, and leave PlatformRegion untouched (#1058)`() {
        assertFlowlessAndNeutral(
            "snapshots/dropoff_workflow_sheet",
            "doordash.screen.dropoff_workflow_sheet",
        )
    }

    private fun assertFlowlessAndNeutral(corpus: String, expectedRuleId: String) {
        val frames = SessionReplay.loadSession(corpus)
        assertTrue("the $corpus corpus must not be empty", frames.isNotEmpty())
        val platforms = frames.map { frame ->
            requireNotNull(Platform.fromWire(frame.wire)) {
                "${frame.file}: unknown platform wire '${frame.wire}'"
            }
        }.distinct()
        require(platforms.size == 1) {
            "the $corpus corpus must contain exactly one platform, saw $platforms"
        }
        val platform = platforms.single()

        // (a) They RECOGNIZE — the rule is live, so this is not a vacuous pass over UNKNOWN frames.
        val observations = SessionReplay.replayRecognition(frames)
        assertEquals(
            "every committed frame must classify as $expectedRuleId",
            List(frames.size) { expectedRuleId },
            observations.map { it.ruleId },
        )

        // (b) They carry NO flow and NO modeHint — the "recognize-only" half of the claim.
        assertTrue(
            "a recognize-only rule must contribute no flow: ${observations.map { it.flow }}",
            observations.all { it.flow == null },
        )
        assertTrue(
            "a recognize-only rule must contribute no modeHint: ${observations.map { it.modeHint }}",
            observations.all { it.modeHint == null },
        )

        // (c) NEUTRALITY — and note what it is NOT. Folding these frames through the real state
        // machine DOES create the DoorDash `PlatformRegion` where none existed (default `Offline`,
        // `lastObservedAt` stamped), which is precisely why "state-inert" was the wrong word: the
        // observation reaches the machine and is accounted for. What must hold is that it carries
        // no LIFECYCLE content and moves none.
        val steps = SessionReplay.reduce(frames)
        assertEquals("every frame must produce a step", frames.size, steps.size)

        for (step in steps) {
            assertEquals(
                "${step.frame?.file}: a flow-less recognized frame must emit no events",
                emptyList<Any>(),
                step.events,
            )
            val region = step.stateAfter.regions.platforms[platform]
            requireNotNull(region) { "${step.frame?.file}: expected a ${platform.displayName} region" }
            assertEquals("${step.frame?.file}: no session", null, region.session)
            assertEquals("${step.frame?.file}: no active job", null, region.activeJob)
            assertEquals("${step.frame?.file}: no active task", null, region.activeTask)
            assertEquals("${step.frame?.file}: no retired tasks", emptyList<Any>(), region.recentTasks)
            assertEquals("${step.frame?.file}: no pending offers", emptyList<Any>(), region.pendingOffers)
            assertEquals("${step.frame?.file}: no graced commit", null, region.pendingDestructive)
            assertEquals("${step.frame?.file}: no graced mode resume", null, region.pendingModeResume)
            // The rule declares no modeHint, so the region must stay at its DEFAULT mode — a
            // recognize-only frame must never be able to assert Online/Offline (the #857/#874/#875
            // class: claiming a mode from absence forges state).
            assertEquals(
                "${step.frame?.file}: mode must stay at the default, never asserted from a " +
                    "modeHint-less frame",
                cloud.trotter.dashbuddy.domain.state.PlatformRegion(platform).mode,
                region.mode,
            )
        }

        // And nothing but the liveness stamp moves BETWEEN the frames: normalising
        // `lastObservedAt` (a "we saw the app" timestamp, not lifecycle) the regions are equal.
        val normalised = steps.map {
            it.stateAfter.regions.platforms[platform]!!.copy(lastObservedAt = 0L)
        }
        assertEquals(
            "consecutive $expectedRuleId frames must differ only by the liveness stamp",
            List(normalised.size) { normalised.first() },
            normalised,
        )
    }
}
