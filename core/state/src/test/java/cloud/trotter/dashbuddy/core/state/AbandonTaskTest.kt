package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #736 — the unassign-abandon vocabulary. Covers the stepper's inline `abandonActiveTask`
 * (a `Flow.TaskUnassigned` frame) and the EffectMap guards that stop a fabricated
 * `PICKUP_CONFIRMED`/`DELIVERY_CONFIRMED` for an abandoned task.
 */
class AbandonTaskTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()
    private val effectMap = EffectMap()
    private val platform = Platform.DoorDash
    private val session = Session("sess-1", startedAt = 100L)

    // ---- helpers ------------------------------------------------------------

    private fun pickup(
        taskId: String,
        store: String = "H-E-B",
        arrivedAt: Long? = 400L,
        activity: String? = PickupActivity.SHOPPING,
        itemsShopped: Int? = 6,
        customerNameHash: String? = null,
        unassignedAt: Long? = null,
        completedAt: Long? = null,
    ) = Task(
        taskId = taskId, jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = store, arrivedAt = arrivedAt, activity = activity, itemsShopped = itemsShopped,
        customerNameHash = customerNameHash, startedAt = 300L,
        unassignedAt = unassignedAt, completedAt = completedAt,
    )

    private fun dropoffPlaceholder(taskId: String, customerNameHash: String? = null) = Task(
        taskId = taskId, jobId = "job-1", phase = TaskPhase.DROPOFF,
        customerNameHash = customerNameHash, startedAt = 300L,
    )

    private fun job(tasks: List<Task>) =
        Job(jobId = "job-1", offerStoreHint = listOf("H-E-B"), parentOfferHash = null, startedAt = 200L, tasks = tasks)

    private fun region(
        activeTask: Task?,
        activeJob: Job?,
        recentTasks: List<Task> = emptyList(),
        pending: PendingDestructive? = null,
    ) = PlatformRegion(
        platform = platform, mode = Mode.Online, session = session,
        activeJob = activeJob, activeTask = activeTask, recentTasks = recentTasks,
        pendingDestructive = pending, lastActedFlow = Flow.TaskPickupArrived,
    )

    private fun unassignObs(ts: Long = 1_000L) = Observation.Screen(
        timestamp = ts, captureId = "cap-$ts", ruleId = "doordash.screen.pickup_unassigned_confirmation",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskUnassigned, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun step(r: PlatformRegion, obs: Observation.Screen, prevFlow: Flow = Flow.TaskPickupArrived) =
        stepper.step(r, FlowRegion(flow = prevFlow), FlowRegion(flow = Flow.TaskUnassigned), obs, policy)

    // ---- stepper cases ------------------------------------------------------

    @Test
    fun `abandon of an arrived pickup marks unassignedAt, leaves completedAt null, closes the single-order job`() {
        val active = pickup("pk")
        val next = step(region(active, job(listOf(dropoffPlaceholder("dO")))), unassignObs())

        assertNull("activeTask cleared", next.activeTask)
        val abandoned = next.recentTasks.single { it.taskId == "pk" }
        assertEquals("marker set to the frame time", 1_000L, abandoned.unassignedAt)
        assertNull("an abandoned task is NEVER completed", abandoned.completedAt)
        assertNull("single-order job closed on the confirmation frame", next.activeJob)
    }

    @Test
    fun `the second confirmation frame is a no-op (activeTask and job already gone)`() {
        val prior = region(activeTask = null, activeJob = null, recentTasks = listOf(pickup("pk", unassignedAt = 1_000L)))
        val next = step(prior, unassignObs(ts = 1_100L))

        assertNull(next.activeTask)
        assertNull(next.activeJob)
        assertEquals("recentTasks unchanged", listOf("pk"), next.recentTasks.map { it.taskId })
        assertEquals("the existing marker is untouched", 1_000L, next.recentTasks.single().unassignedAt)
    }

    @Test
    fun `a pending TASK_RETIRE is superseded by the abandon`() {
        val pend = PendingDestructive(DestructiveKind.TASK_RETIRE, since = 900L, deadline = 3_000L)
        val next = step(region(pickup("pk"), job(listOf(dropoffPlaceholder("dO"))), pending = pend), unassignObs())
        assertNull("TASK_RETIRE cleared", next.pendingDestructive)
    }

    @Test
    fun `a pending SESSION_END survives the abandon`() {
        val pend = PendingDestructive(DestructiveKind.SESSION_END, since = 900L, deadline = 3_000L)
        val next = step(region(pickup("pk"), job(listOf(dropoffPlaceholder("dO"))), pending = pend), unassignObs())
        assertEquals("SESSION_END preserved (the graced-offline state)", DestructiveKind.SESSION_END, next.pendingDestructive?.kind)
    }

    @Test
    fun `multi-dropoff abandon with a matching hash removes only the matching placeholder and the job survives`() {
        val active = pickup("pk", customerNameHash = "cust-B")
        val j = job(listOf(dropoffPlaceholder("dA", "cust-A"), dropoffPlaceholder("dB", "cust-B")))
        val next = step(region(active, j), unassignObs())

        val dropIds = next.activeJob!!.tasks.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertEquals("only the abandoned order's drop (dB) is removed", setOf("dA"), dropIds)
        assertTrue("job stays open (dA still outstanding)", next.activeJob != null)
    }

    @Test
    fun `multi-dropoff abandon with no customer hash removes nothing and the job survives`() {
        val active = pickup("pk", customerNameHash = null)
        val j = job(listOf(dropoffPlaceholder("dA"), dropoffPlaceholder("dB")))
        val next = step(region(active, j), unassignObs())

        val dropIds = next.activeJob!!.tasks.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertEquals("no drop removed (absorption over fabrication)", setOf("dA", "dB"), dropIds)
    }

    @Test
    fun `resume self-heal clears unassignedAt when a genuine same-order frame reactivates the task`() {
        val abandoned = pickup("pk", unassignedAt = 500L)
        val r = region(activeTask = null, activeJob = job(emptyList()), recentTasks = listOf(abandoned))
        val navObs = Observation.Screen(
            timestamp = 2_000L, captureId = "c", ruleId = "doordash.screen.pickup_navigation",
            metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(storeName = "H-E-B", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION),
        )
        val next = stepper.step(r, FlowRegion(flow = Flow.TaskUnassigned), FlowRegion(flow = Flow.TaskPickupNavigation), navObs, policy)

        assertEquals("resumed the same task identity", "pk", next.activeTask?.taskId)
        assertNull("the unassign marker is healed away", next.activeTask?.unassignedAt)
    }

    // ---- effect cases -------------------------------------------------------

    private fun state(region: PlatformRegion, flow: Flow) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow, activePlatform = platform),
            platforms = mapOf(platform to region),
        ),
    )

    private fun List<AppEffect>.logTypes() = filterIsInstance<AppEffect.LogEvent>().map { it.event.type }

    @Test
    fun `the close-out sweep never confirms an abandoned arrived pickup, but DOES emit one TASK_UNASSIGNED`() {
        val prevRegion = region(pickup("pk"), job(listOf(dropoffPlaceholder("dO")))).copy(lastActedFlow = Flow.TaskPickupArrived)
        val nextRegion = region(null, null, recentTasks = listOf(pickup("pk", unassignedAt = 1_000L))).copy(lastActedFlow = Flow.TaskUnassigned)

        val effects = effectMap.diff(state(prevRegion, Flow.TaskPickupArrived), state(nextRegion, Flow.TaskUnassigned), unassignObs())

        assertFalse("no fabricated PICKUP_CONFIRMED (the seq-71 bug)", effects.logTypes().contains(AppEventType.PICKUP_CONFIRMED))
        assertFalse("no RecordShopRate for the abandoned shop", effects.any { it is AppEffect.RecordShopRate })
        assertEquals("exactly one TASK_UNASSIGNED", 1, effects.logTypes().count { it == AppEventType.TASK_UNASSIGNED })
        val payload = effects.filterIsInstance<AppEffect.LogEvent>().first { it.event.type == AppEventType.TASK_UNASSIGNED }.event.payload as TaskUnassignedPayload
        assertEquals("job-1", payload.jobId)
        assertEquals("pk", payload.taskId)
        assertEquals(TaskPhase.PICKUP, payload.phase)
        assertTrue("an 'Unassigned:' bubble fires", effects.filterIsInstance<AppEffect.UpdateBubble>().any { it.text.startsWith("Unassigned:") })
    }

    @Test
    fun `CONTROL - the very same close-out DOES confirm the pickup when it was NOT unassigned`() {
        val prevRegion = region(pickup("pk"), job(listOf(dropoffPlaceholder("dO")))).copy(lastActedFlow = Flow.TaskPickupArrived)
        // Same shape, but the pickup is a normal completed arrival (no unassign marker).
        val nextRegion = region(null, null, recentTasks = listOf(pickup("pk", completedAt = 900L))).copy(lastActedFlow = Flow.Idle)

        val effects = effectMap.diff(state(prevRegion, Flow.TaskPickupArrived), state(nextRegion, Flow.Idle), unassignObs())
        assertTrue("without the marker the sweep still confirms — proving the filter is load-bearing", effects.logTypes().contains(AppEventType.PICKUP_CONFIRMED))
    }

    @Test
    fun `a dropoff-phase abandon suppresses DELIVERY_CONFIRMED`() {
        val activeDrop = Task(taskId = "dr", jobId = "job-1", phase = TaskPhase.DROPOFF, customerNameHash = "cust", arrivedAt = 500L, startedAt = 300L)
        val prevRegion = region(activeDrop, job(listOf(activeDrop))).copy(lastActedFlow = Flow.TaskDropoffArrived)
        val nextRegion = region(null, null, recentTasks = listOf(activeDrop.copy(unassignedAt = 1_000L))).copy(lastActedFlow = Flow.TaskUnassigned)

        val effects = effectMap.diff(state(prevRegion, Flow.TaskDropoffArrived), state(nextRegion, Flow.TaskUnassigned), unassignObs())
        assertFalse("an unassigned dropoff must not mint DELIVERY_CONFIRMED", effects.logTypes().contains(AppEventType.DELIVERY_CONFIRMED))
    }

    @Test
    fun `a dropoff-phase abandon removes only its OWN placeholder, not a hash-colliding sibling`() {
        // Fix 4: two sibling drops share a normalized customer hash ("cust"); the dasher unassigns dA.
        // The old hash-join would remove BOTH (dA and dB) and close the job, dropping the still-owed dB.
        // A dropoff-phase abandon must retire only its OWN placeholder by taskId.
        val activeDrop = Task(
            taskId = "dA", jobId = "job-1", phase = TaskPhase.DROPOFF,
            customerNameHash = "cust", arrivedAt = 500L, startedAt = 300L,
        )
        val sibling = dropoffPlaceholder("dB", customerNameHash = "cust")
        val next = step(region(activeDrop, job(listOf(activeDrop, sibling))), unassignObs(), prevFlow = Flow.TaskDropoffArrived)

        assertTrue("job stays open — the sibling drop dB is still owed", next.activeJob != null)
        val dbOutstanding = next.activeJob!!.tasks.any {
            it.phase == TaskPhase.DROPOFF && it.taskId == "dB" &&
                it.completedAt == null && it.unassignedAt == null
        }
        assertTrue("sibling dB survives as an outstanding drop despite the shared hash", dbOutstanding)
    }

    @Test
    fun `same-frame supersession - an overdue TASK_RETIRE lapsing on the unassign frame yields to the abandon`() {
        // Fix 3(a): a help-flow retire grace whose deadline lapses exactly as the confirmation frame
        // arrives must NOT commit a completedAt first (the seq-71 fabrication hole) — the abandon wins.
        val pend = PendingDestructive(DestructiveKind.TASK_RETIRE, since = 900L, deadline = 950L)
        val prevRegion = region(pickup("pk"), job(listOf(dropoffPlaceholder("dO"))), pending = pend)
        val nextRegion = step(prevRegion, unassignObs(ts = 1_000L))

        val marked = nextRegion.recentTasks.single { it.taskId == "pk" }
        assertEquals("the still-active pickup is abandon-marked at the frame", 1_000L, marked.unassignedAt)
        assertNull("the retire did NOT commit a completedAt — the abandon superseded it", marked.completedAt)

        val effects = effectMap.diff(
            state(prevRegion, Flow.TaskPickupArrived), state(nextRegion, Flow.TaskUnassigned), unassignObs(ts = 1_000L),
        )
        assertFalse("no fabricated PICKUP_CONFIRMED", effects.logTypes().contains(AppEventType.PICKUP_CONFIRMED))
    }

    @Test
    fun `cross-frame retro-mark - a grace-retired pickup yields no confirm, one TASK_UNASSIGNED, job closed`() {
        // Fix 3(b)+(c): the retire grace committed on a PRIOR frame (pickup already in recentTasks with
        // completedAt set, job still open). The confirmation frame must retro-mark that pickup so the
        // #596 close-out sweep can't fabricate a PICKUP_CONFIRMED, and still emit exactly one
        // TASK_UNASSIGNED for it.
        val retired = pickup("pk", arrivedAt = 400L, completedAt = 900L)
        val prevRegion = region(activeTask = null, activeJob = job(listOf(retired)), recentTasks = listOf(retired))
        val nextRegion = step(prevRegion, unassignObs(ts = 1_000L))

        val marked = nextRegion.recentTasks.single { it.taskId == "pk" }
        assertEquals("retro-marked at the confirmation frame", 1_000L, marked.unassignedAt)
        assertEquals("completedAt preserved (not nulled)", 900L, marked.completedAt)
        assertNull("pickup-only job closed on the confirmation frame", nextRegion.activeJob)

        val effects = effectMap.diff(
            state(prevRegion, Flow.TaskPickupArrived), state(nextRegion, Flow.TaskUnassigned), unassignObs(ts = 1_000L),
        )
        assertFalse(
            "the retire→close-out chain fabricates no PICKUP_CONFIRMED",
            effects.logTypes().contains(AppEventType.PICKUP_CONFIRMED),
        )
        assertEquals(
            "exactly one TASK_UNASSIGNED for the retro-marked pickup",
            1, effects.logTypes().count { it == AppEventType.TASK_UNASSIGNED },
        )
        val payload = effects.filterIsInstance<AppEffect.LogEvent>()
            .first { it.event.type == AppEventType.TASK_UNASSIGNED }.event.payload as TaskUnassignedPayload
        assertEquals("pk", payload.taskId)
    }

    // ---- #752: cross-frame retro-mark widened to ANY phase (the DROPOFF analog) ----------

    @Test
    fun `cross-frame retro-mark - a grace-retired DROPOFF yields no fabricated completion, one TASK_UNASSIGNED (dropoff), job closed`() {
        // #752: the retire grace committed a `completedAt` on a DROPOFF on a PRIOR frame while the job
        // stayed open (the drop was retired EN ROUTE — arrivedAt null — so isJobPhysicallyComplete did
        // not close it). The confirmation frame must retro-mark THAT DROP (not a sibling pickup) so the
        // close-out belt suppresses a fabricated DELIVERY_COMPLETED, and emit exactly one
        // TASK_UNASSIGNED carrying phase DROPOFF. Pre-#752 (PICKUP-only retro-mark) this drop was never
        // marked → the close-out fabricated a completion for a never-delivered order.
        val retiredDrop = Task(
            taskId = "dr", jobId = "job-1", phase = TaskPhase.DROPOFF,
            customerNameHash = "cust", arrivedAt = null, completedAt = 900L, startedAt = 300L,
        )
        val prevRegion = region(
            activeTask = null, activeJob = job(listOf(retiredDrop)), recentTasks = listOf(retiredDrop),
        )
        val nextRegion = step(prevRegion, unassignObs(ts = 1_000L), prevFlow = Flow.TaskDropoffNavigation)

        val marked = nextRegion.recentTasks.single { it.taskId == "dr" }
        assertEquals("retro-marked the DROPOFF at the confirmation frame", 1_000L, marked.unassignedAt)
        assertEquals("completedAt preserved (not nulled)", 900L, marked.completedAt)
        assertNull("single-order job closed on the confirmation frame", nextRegion.activeJob)

        val effects = effectMap.diff(
            state(prevRegion, Flow.TaskDropoffNavigation), state(nextRegion, Flow.TaskUnassigned), unassignObs(ts = 1_000L),
        )
        assertFalse(
            "the retire→close-out chain fabricates no DELIVERY_COMPLETED for the abandoned drop",
            effects.logTypes().contains(AppEventType.DELIVERY_COMPLETED),
        )
        assertEquals(
            "exactly one TASK_UNASSIGNED for the retro-marked dropoff",
            1, effects.logTypes().count { it == AppEventType.TASK_UNASSIGNED },
        )
        val payload = effects.filterIsInstance<AppEffect.LogEvent>()
            .first { it.event.type == AppEventType.TASK_UNASSIGNED }.event.payload as TaskUnassignedPayload
        assertEquals("dr", payload.taskId)
        assertEquals("the payload carries phase DROPOFF so LegFolds purges the right leg map", TaskPhase.DROPOFF, payload.phase)
    }

    @Test
    fun `cross-frame retro-mark still targets the pickup when the job has no retired dropoff (regression)`() {
        // The widening must not disturb the original #736 pickup-only shape: with only a retired pickup
        // in the lineage, `maxByOrNull { completedAt }` still selects it.
        val retiredPickup = pickup("pk", arrivedAt = 400L, completedAt = 900L)
        val prevRegion = region(
            activeTask = null, activeJob = job(listOf(retiredPickup)), recentTasks = listOf(retiredPickup),
        )
        val next = step(prevRegion, unassignObs(ts = 1_000L))
        assertEquals("the retired pickup is retro-marked", 1_000L, next.recentTasks.single { it.taskId == "pk" }.unassignedAt)
    }

    @Test
    fun `cross-frame retro-mark - a job with a retired pickup and a LATER retired dropoff marks the most recent (the dropoff)`() {
        // Mixed shape: the abandon is about the leg most recently retired. maxByOrNull { completedAt }
        // picks the dropoff (900 > 800), leaving the earlier-retired pickup untouched.
        val retiredPickup = pickup("pk", arrivedAt = 400L, completedAt = 800L)
        val retiredDrop = Task(
            taskId = "dr", jobId = "job-1", phase = TaskPhase.DROPOFF,
            customerNameHash = "cust", arrivedAt = 600L, completedAt = 900L, startedAt = 300L,
        )
        val prevRegion = region(
            activeTask = null,
            activeJob = job(listOf(retiredPickup, retiredDrop)),
            recentTasks = listOf(retiredPickup, retiredDrop),
        )
        val next = step(prevRegion, unassignObs(ts = 1_000L), prevFlow = Flow.TaskDropoffNavigation)

        assertEquals("the dropoff (most recently retired) is marked", 1_000L, next.recentTasks.single { it.taskId == "dr" }.unassignedAt)
        assertNull("the earlier-retired pickup is left untouched", next.recentTasks.single { it.taskId == "pk" }.unassignedAt)
    }

    @Test
    fun `isJobPhysicallyComplete does NOT read a retro-marked (unassigned) arrived dropoff as delivered`() {
        // #752 belt: the arrived-then-unassigned shape carries completedAt AND arrivedAt AND
        // unassignedAt. Without the new `unassignedAt == null` guard this reads as accounted+finished
        // → a false job-complete → a fabricated mint. It must read as NOT complete (absorption).
        val unassignedDrop = Task(
            taskId = "dr", jobId = "job-1", phase = TaskPhase.DROPOFF,
            customerNameHash = "cust", arrivedAt = 600L, completedAt = 900L, unassignedAt = 1_000L, startedAt = 300L,
        )
        val j = job(listOf(unassignedDrop))
        assertFalse(
            "the only drop was unassigned — the job is not physically complete",
            isJobPhysicallyComplete(j, recentTasks = listOf(unassignedDrop), justRetired = null),
        )
    }

    @Test
    fun `CONTROL - isJobPhysicallyComplete still counts a genuinely delivered (not unassigned) dropoff`() {
        // Proves the #752 guard is not over-broad: a delivered drop keeps `unassignedAt` null and
        // still completes the job.
        val deliveredDrop = Task(
            taskId = "dr", jobId = "job-1", phase = TaskPhase.DROPOFF,
            customerNameHash = "cust", arrivedAt = 600L, completedAt = 900L, startedAt = 300L,
        )
        val j = job(listOf(deliveredDrop))
        assertTrue(
            "a genuinely delivered drop still reads as physically complete",
            isJobPhysicallyComplete(j, recentTasks = listOf(deliveredDrop), justRetired = null),
        )
    }

    @Test
    fun `delivered-A then unassign-B still mints A's completion only`() {
        val a = Task(taskId = "dA", jobId = "job-1", phase = TaskPhase.DROPOFF, customerNameHash = "cust-A", arrivedAt = 400L, completedAt = 900L, startedAt = 300L)
        val b = Task(taskId = "dB", jobId = "job-1", phase = TaskPhase.DROPOFF, customerNameHash = "cust-B", arrivedAt = 500L, unassignedAt = 1_000L, startedAt = 300L)
        val prevRegion = PlatformRegion(platform = platform, mode = Mode.Online, session = session, activeJob = job(listOf(a, b)), activeTask = null, recentTasks = listOf(a), lastActedFlow = Flow.TaskDropoffArrived)
        val nextRegion = PlatformRegion(platform = platform, mode = Mode.Online, session = session, activeJob = null, activeTask = null, recentTasks = listOf(a, b), lastActedFlow = Flow.TaskUnassigned)

        val effects = effectMap.diff(state(prevRegion, Flow.TaskDropoffArrived), state(nextRegion, Flow.TaskUnassigned), unassignObs())
        val completed = effects.filterIsInstance<AppEffect.LogEvent>().filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
        assertEquals("only A completes (B was abandoned, never completed)", 1, completed.size)
    }
}
