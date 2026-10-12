package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.IdleSurface
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1312 Level B: a two-order DoorDash job (Panera Bread + Panera - Corporate, 2026-10-09) that the
 * dasher unassigned order by order, then went back to Idle and accepted an H-E-B offer.
 *
 * Field outcome (build 0.230.0+88dee351, encoded by the captured db, so never asserted as an oracle):
 * the first unassign abandoned the one active pickup (both orders had run on it), the second
 * `task:unassigned` confirmation found nothing to abandon and was swallowed, the Idle screen did not
 * close the job, and the H-E-B accept was folded into the dead Panera job as an add-on — its drop then
 * resolved onto a Panera dropoff placeholder.
 *
 * Correct behaviour (hand-authored invariants): the `TaskUnassigned → Idle` edge closes the job as
 * ABANDONED — every still-open task is marked `unassignedAt` (never `completedAt`), one
 * `TASK_UNASSIGNED` per task — and the H-E-B accept mints a fresh job.
 *
 * Fixture `sessions/unassign_two_order_job_2026_10_09/` is the 12:08:35–12:25:48 capture run plus
 * four spliced frames (DoorDashFullDashJourney style — the field device captured none of these):
 *  - 33/34: the U2 unassign survey + confirmation. The second pass was journaled (12:25:20.577 /
 *    12:25:25.161) but FrameGate's identity dedupe skipped its capture, so the U1 envelopes (25/26)
 *    are re-stamped to those instants; the survey's (unparsed) `For` mask names the second order.
 *  - 36: Idle `waiting_for_offer` — the same dash's 12:08:18.557 frame re-stamped to 12:25:30.311
 *    (the journaled Idle frames at 12:25:30/:34/:37 were not captured).
 *  - 39: the H-E-B `pickup_pre_arrival` — the only 10-09 H-E-B pre-arrival (17:54:57.160) re-stamped
 *    to 12:25:48.846, where the journal shows the uncaptured task frame that consumed the accept.
 * No click capture exists for either accept; both are inferred from the task surface (returnFlow
 * non-task). Customer PII is masked to `[redacted]`/`[redacted:xxxx]`; provenance is placeholdered.
 */
class TwoOrderUnassignReplayTest {
    private val session = "snapshots/sessions/unassign_two_order_job_2026_10_09"

    private val u1Confirmation = "26_win_pickup_unassigned_confirmation.json"
    private val u2Confirmation = "34_win_pickup_unassigned_confirmation.json"
    private val idle = "36_win_waiting_for_offer.json"
    private val hebOffer = "37_win_offer_popup.json"
    private val hebPickup = "39_win_pickup_pre_arrival.json"

    private fun dd(step: SessionReplay.ReplayStep): PlatformRegion =
        step.stateAfter.regions.platforms.getValue(Platform.DoorDash)

    private fun run(): List<SessionReplay.ReplayStep> {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val clicks = File("src/test/resources/$session").listFiles()!!
            .filter { it.name.contains("_cli_") }.sortedBy { it.name }
            .map { SessionReplay.loadClickFrame("$session/${it.name}") }
        assertEquals("all supplied envelopes participate", 39, screens.size + clicks.size)
        return SessionReplay.reduceMixed(screens + clicks).also { println(SessionReplay.trace(it)) }
    }

    /** Every task snapshot a region exposes; later entries are fresher (lineage wins over the job mirror). */
    private fun tasksOf(region: PlatformRegion): List<Task> =
        region.activeJob?.tasks.orEmpty() + listOfNotNull(region.activeTask) + region.recentTasks

    @Test
    fun `both unassign confirmations recognize as task unassigned and the Idle frame as idle`() {
        val frames = SessionReplay.loadSession(session)
        val byFile = frames.zip(SessionReplay.replayRecognition(frames)).associate { (f, o) -> f.file to o }
        listOf(u1Confirmation, u2Confirmation).forEach { file ->
            val obs = byFile.getValue(file)
            assertEquals("$file rule", "doordash.screen.pickup_unassigned_confirmation", obs.ruleId)
            assertEquals("$file flow — recognition is not the #1312 defect", Flow.TaskUnassigned, obs.flow)
        }
        assertEquals("the Idle splice recognizes as Idle", Flow.Idle, byFile.getValue(idle).flow)
        assertEquals("frame 36 is the rule-declared no-orders surface",
            IdleSurface.NO_ORDERS, byFile.getValue(idle).idleSurface)
        assertEquals("the H-E-B splice is a pickup task frame",
            Flow.TaskPickupNavigation, byFile.getValue(hebPickup).flow)
    }

    @Test
    fun `the unassign-then-Idle edge abandons the whole job and the next accept mints a fresh one`() {
        val steps = run()
        fun step(file: String) = steps.single { it.frame?.file == file }
        val events = steps.flatMap { it.events }

        val paneraJobId = dd(steps.first { dd(it).activeJob != null }).activeJob!!.jobId
        val hebOfferAt = step(hebOffer).observation.timestamp
        val paneraTaskIds = steps.filter { it.observation.timestamp < hebOfferAt }
            .flatMap { tasksOf(dd(it)) }.filter { it.jobId == paneraJobId }.mapTo(LinkedHashSet()) { it.taskId }
        val paneraTasks = steps.filter { it.observation.timestamp < hebOfferAt }
            .flatMap { tasksOf(dd(it)) }.filter { it.jobId == paneraJobId }
            .groupBy { it.taskId }.mapValues { (_, v) -> v.last() }
        println("#1312 Panera job $paneraJobId tasks: " + paneraTasks.values.joinToString { t ->
            "${t.taskId.takeLast(4)}:${t.phase}:store=${t.storeName}:started=${t.startedAt}:arrived=${t.arrivedAt}:completed=${t.completedAt}:unassigned=${t.unassignedAt}"
        })

        // U1 abandons exactly one task: the active pickup both orders ran on.
        val u1Unassigned = step(u1Confirmation).events.filter { it.type == AppEventType.TASK_UNASSIGNED }
        assertEquals("U1 abandons the one active task", 1, u1Unassigned.size)
        val u1TaskId = (u1Unassigned.single().payload as TaskUnassignedPayload).taskId
        val placeholders = paneraTaskIds - u1TaskId
        // The field db (build 88dee351) held 3 placeholders (2 dropoffs + the never-bound pickup); the
        // current reducer may mint more lineage from the same frames — the invariant is that EVERY one
        // of them ends unassigned and none completes, not the literal count.
        assertTrue("at least the two dropoff placeholders + the never-bound pickup placeholder", placeholders.size >= 3)

        // (a) After U2 and the Idle frame the dasher holds no order: the job is closed.
        val idleStep = step(idle)
        assertNull("TaskUnassigned → Idle closes the abandoned job", dd(idleStep).activeJob)
        assertNull("no active task after the Idle frame", dd(idleStep).activeTask)

        // (b) The H-E-B accept mints a FRESH job, not an add-on onto the dead one.
        val fresh = dd(step(hebPickup)).activeJob
        assertNotNull("the H-E-B accept forms a job", fresh)
        assertNotEquals("the H-E-B accept mints a fresh job", paneraJobId, fresh!!.jobId)
        assertEquals("the fresh job carries only the H-E-B offer", 1, fresh.acceptedOffers.size)
        assertTrue("no Panera task is carried into the fresh job",
            fresh.tasks.none { it.taskId in paneraTaskIds })

        // (c) No delivery/pickup-completion event lands on a Panera task.
        val deliveryOnPanera = events.filter { it.type.name.startsWith("DELIVERY_") }.filter {
            when (val p = it.payload) {
                is DeliveryPayload -> p.taskId in paneraTaskIds || p.jobId == paneraJobId
                is DeliveryReceiptRepricePayload -> p.taskId in paneraTaskIds || p.jobId == paneraJobId
                else -> false
            }
        }
        assertTrue("no DELIVERY_* event names a Panera task: ${deliveryOnPanera.map { it.type }}",
            deliveryOnPanera.isEmpty())
        assertFalse("an abandoned pickup is never confirmed (#736)", events.any {
            it.type == AppEventType.PICKUP_CONFIRMED && (it.payload as? PickupPayload)?.taskId in paneraTaskIds
        })

        // (d) Exactly one TASK_UNASSIGNED per task id over the whole run, and every Panera task gets one.
        val unassigned = events.filter { it.type == AppEventType.TASK_UNASSIGNED }
            .map { it.payload as TaskUnassignedPayload }
        val perTask = unassigned.groupingBy { it.taskId }.eachCount()
        assertTrue("one TASK_UNASSIGNED per task id: $perTask", perTask.values.all { it == 1 })
        assertEquals("every Panera task is unassigned exactly once", paneraTaskIds, perTask.keys)
        assertTrue("every TASK_UNASSIGNED names the Panera job", unassigned.all { it.jobId == paneraJobId })

        // (e) The three placeholders end abandoned, never completed.
        val u2At = step(u2Confirmation).observation.timestamp
        val idleAt = idleStep.observation.timestamp
        val lastSeen = LinkedHashMap<String, Task>()
        steps.forEach { s -> tasksOf(dd(s)).filter { it.taskId in placeholders }.forEach { lastSeen[it.taskId] = it } }
        assertEquals("every placeholder is visible in the trace", placeholders, lastSeen.keys)
        lastSeen.values.forEach { t ->
            println("#1312 placeholder ${t.taskId} ${t.phase}: unassignedAt=${t.unassignedAt}, completedAt=${t.completedAt}")
            assertNotNull("${t.taskId} ends unassigned", t.unassignedAt)
            // A dropoff is open until delivered; a pickup until CONFIRMED. The "Panera Bread" slot was displaced by
            // the "Panera - Corporate" relabel and carries a displacement completedAt but no confirmation — the
            // abandon marks it unassigned and leaves completedAt intact (the #736 retro-mark precedent).
            if (t.phase == TaskPhase.DROPOFF) assertNull("${t.taskId} is never delivered", t.completedAt)
            assertNull("${t.taskId} is never confirmed", t.confirmedAt)
            assertTrue("${t.taskId} is marked by the U2 → Idle close, not earlier",
                t.unassignedAt!! in u2At..idleAt)
        }
        unassigned.filter { it.taskId in placeholders }.forEach {
            assertTrue("${it.taskId} TASK_UNASSIGNED is stamped by the close", it.unassignedAt in u2At..idleAt)
        }
    }
}
