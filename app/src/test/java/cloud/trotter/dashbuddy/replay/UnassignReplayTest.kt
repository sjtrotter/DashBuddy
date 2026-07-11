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
 * #736 — end-to-end replay of the real 07-07 **unassign-via-help** session (H-E-B shop order,
 * db session `…656-19`, seq 71). The dasher shopped, then unassigned the order through the help
 * flow; on master the retire grace stamped `completedAt` on the abandoned pickup and the #596
 * close-out sweep converted that into a **fabricated `PICKUP_CONFIRMED`** (seq 71) — plus a garbage
 * #556 shop-rate sample. The db `app_events` for this session ENCODES that bug, so these are
 * hand-authored correct-behaviour invariants, never `replay == db`.
 *
 * The capture window opens mid-flow (the offer/accept predate it), so the job forms via the
 * bare-fallback job-start path on the first pickup frame — a pickup-only job with an arrived,
 * SHOPPING H-E-B pickup. That is exactly the shape the fabrication needs (arrived pickup + job
 * close → close-out sweep), so it is faithful to the defect.
 *
 * Fixture: `snapshots/sessions/unassign_help_2026_07_07/` — real device envelopes. The recognized
 * pickup/resolution frames were edge-redacted on capture; the one raw UNKNOWN survey frame's
 * `For Kelly S. • H-E-B` customer line was MANUALLY redacted to `For Sample C. • H-E-B` (the store
 * is merchant data, kept). Verified PII-safe below.
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

    // ---- Level A: recognition ----------------------------------------------

    @Test
    fun `the unassign frames recognize to the new rule ids, none UNKNOWN`() {
        val obs = SessionReplay.replayRecognition(session)
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
    }

    // ---- Level B: state machine invariants ---------------------------------

    @Test
    fun `invariant 1 - zero PICKUP_CONFIRMED across the whole session`() {
        val steps = run()
        val confirms = steps.flatMap { it.events }.count { it.type == AppEventType.PICKUP_CONFIRMED }
        assertEquals("the seq-71 fabrication is gone — no pickup is ever confirmed", 0, confirms)
    }

    @Test
    fun `invariant 2 - exactly one TASK_UNASSIGNED for the H-E-B task, at the confirmation frame`() {
        val steps = run()
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
    }

    @Test
    fun `invariant 3 - no DELIVERY_COMPLETED or DELIVERY_CONFIRMED for the abandoned job`() {
        val steps = run()
        val types = steps.flatMap { it.events }.map { it.type }
        assertFalse("no delivery is completed", types.contains(AppEventType.DELIVERY_COMPLETED))
        assertFalse("no delivery is confirmed", types.contains(AppEventType.DELIVERY_CONFIRMED))
    }

    @Test
    fun `invariant 4 - no RecordShopRate effect (the polluted shop sample never folds)`() {
        val steps = run()
        assertFalse(
            "the abandoned shop never feeds the #556 learned rate",
            steps.flatMap { it.effects }.any { it is AppEffect.RecordShopRate },
        )
    }

    @Test
    fun `invariant 5 - the job is closed immediately after the confirmation frame`() {
        val steps = run()
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
            val raw = file.readText()
            assertFalse("${file.name} must contain no raw customer name", raw.contains("Kelly", ignoreCase = true))
            // Any surviving "For <name>" customer line must be the redacted placeholder, never a real name.
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
