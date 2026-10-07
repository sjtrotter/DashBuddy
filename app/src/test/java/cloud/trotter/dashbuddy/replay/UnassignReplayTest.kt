package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.SessionReplay
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #736: the real H-E-B unassign-via-help session previously fabricated PICKUP_CONFIRMED and a
 * shop-rate sample at close (captured db seq 71 encodes the bug). Correct behavior abandons the
 * pickup, emits TASK_UNASSIGNED at confirmation, and closes without delivery or shop-rate effects.
 * The capture starts mid-flow: the first arrived SHOPPING frame forms a bare-fallback pickup-only
 * job. A late GRACE_COMMIT also checks that the trailing idle frame cannot fabricate completion.
 * Fixture: unassign_help_2026_07_07; the survey's customer line is manually redacted to Sample C.
 * The independent PII test below checks all fixture envelopes.
 */
class UnassignReplayTest {

    private val session = "snapshots/sessions/unassign_help_2026_07_07"

    private fun dd(step: SessionReplay.ReplayStep) = step.stateAfter.regions.platforms[Platform.DoorDash]

    private fun run(): List<SessionReplay.ReplayStep> {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/01_arrived_at_store_click.json")
        // A late GRACE_COMMIT commits any retire grace the trailing idle frame armed — proving no
        // LATE fabrication path survives either.
        val timer = SessionReplay.graceCommit(screens.maxOf { it.atMs } + 200_000L)
        return SessionReplay.reduceMixed(screens + click + timer)
    }

    @Test
    fun `recognized unassignment abandons the pickup and closes immediately without completion or shop rate`() {
        val steps = run()
        val obs = steps.filter { it.frame != null }.map { it.observation as Observation.Screen }
        val byRule = obs.filter { it.ruleId != null }.groupingBy { it.ruleId }.eachCount()
        assertTrue(
            "the survey frame recognizes as pickup_unassign_survey",
            byRule.keys.any { it == "doordash.screen.pickup_unassign_survey" },
        )
        assertTrue(
            "the confirmation frame(s) recognize as pickup_unassigned_confirmation",
            byRule.keys.any { it == "doordash.screen.pickup_unassigned_confirmation" },
        )
        assertTrue(
            "the …957d56 resolution variant recognizes via the broadened pickup_resolution_options",
            byRule.keys.any { it == "doordash.screen.pickup_resolution_options" },
        )
        // Every screen frame in this session now recognizes — the four 15:43 unassign frames were the
        // only UNKNOWNs, and the two new rules + the broadened resolution cover them all.
        val unknown = obs.count { it.ruleId == null }
        assertEquals("no screen frame in the session falls to UNKNOWN", 0, unknown)

        val confirms = steps.flatMap { it.events }.count { it.type == AppEventType.PICKUP_CONFIRMED }
        assertEquals("the seq-71 fabrication is gone — no pickup is ever confirmed", 0, confirms)

        val unassigned = steps.flatMap { it.events }.filter { it.type == AppEventType.TASK_UNASSIGNED }
        assertEquals("exactly one TASK_UNASSIGNED", 1, unassigned.size)
        val payload = unassigned.single().payload as TaskUnassignedPayload
        assertEquals("carries the H-E-B pickup phase", TaskPhase.PICKUP, payload.phase)
        assertNotNull("carries the job id", payload.jobId)
        assertNotNull("carries the task id", payload.taskId)
        // occurredAt ≈ 15:43:12 (the confirmation frame time), not the trailing idle/grace time.
        val confirmFrameTs = SessionReplay.loadSession(session)
            .first { it.file.contains("unassign_confirm") || it.file.contains("unassign_combined") }.capturedAtMs
        assertEquals("stamped at the confirmation frame", confirmFrameTs, unassigned.single().occurredAt)

        val types = steps.flatMap { it.events }.map { it.type }
        assertFalse("no delivery is completed", types.contains(AppEventType.DELIVERY_COMPLETED))
        assertFalse("no delivery is confirmed", types.contains(AppEventType.DELIVERY_CONFIRMED))

        assertFalse(
            "the abandoned shop never feeds the #556 learned rate",
            steps.flatMap { it.effects }.any { it is AppEffect.RecordShopRate },
        )

        val confirmStep = steps.last {
            (it.observation as? Observation.FlowObservation)?.ruleId == "doordash.screen.pickup_unassigned_confirmation"
        }
        assertNull("activeJob is null right after the unassign confirmation", dd(confirmStep)?.activeJob)
    }

    // ---- fixture is PII-safe ------------------------------------------------

    @Test
    fun `the fixture carries no raw customer PII`() {
        val dir = File("src/test/resources/$session")
        val files = dir.listFiles { _, name -> name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("fixture is non-empty", files.isNotEmpty())
        files.forEach { file ->
            // Any surviving "For <name>" customer line must be the redacted placeholder, never a real
            // customer name — the generic placeholder-allowlist walk below subsumes any name-specific check.
            val node = if (file.name.contains("click")) SessionReplay.loadClickFrame("$session/${file.name}").node
            else TestResourceLoader.loadNode(file)
            val texts = mutableListOf<String>()
            fun walk(n: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode) {
                n.text?.let { texts.add(it) }; n.children.forEach { walk(it) }
            }
            walk(node)
            texts.filter { it.startsWith("For ") && it.contains("•") }.forEach { t ->
                val name = t.removePrefix("For ").substringBefore("•").trim()
                assertTrue(
                    "${file.name}: customer line '$t' is not a raw name (redacted placeholder or 'Sample C.')",
                    name == "Sample C." || name.startsWith("[redacted"),
                )
            }
        }
    }
}
