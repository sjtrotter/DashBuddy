package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.state.model.Transition
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.settings.GraceConfigProvider
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskIntent
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1315: a completion tap is evidence until navigation away from its screen on the same drop. */
class CompletionStepsEvidenceTest {
    private val drop = Task("drop", "job", TaskPhase.DROPOFF, startedAt = 100L, customerNameHash = "customer")
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
    )

    private fun state(task: Task = drop): AppState = AppState(regions = Regions(
        flow = FlowRegion(flow = Flow.TaskDropoffNavigation, activePlatform = Platform.DoorDash),
        platforms = mapOf(Platform.DoorDash to PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online, session = Session("dash", startedAt = 0L),
            activeJob = Job("job", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L, tasks = listOf(task)), activeTask = task,
        )),
    ))

    private fun click(
        t: Long, platform: Platform = Platform.DoorDash, intent: String = TaskIntent.COMPLETE_DELIVERY,
        screenRuleId: String? = "doordash.screen.tapped",
    ) =
        Observation.Click(
            timestamp = t, captureId = null, ruleId = "${platform.wire}.click.complete_delivery",
            metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
            parsed = ParsedFields.ClickFields(intent = intent), screenRuleId = screenRuleId,
        )

    private fun screen(t: Long, flow: Flow, customer: String = "customer") = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.synthetic", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = Mode.Online,
        parsed = if (flow == Flow.Idle) ParsedFields.None else ParsedFields.TaskFields(
            phase = TaskPhase.DROPOFF,
            subFlow = if (flow == Flow.TaskDropoffArrived) TaskSubFlow.ARRIVED else TaskSubFlow.NAVIGATION,
            customerNameHash = customer, customerAddressHash = "$customer-address",
        ),
    )

    @Test
    fun `same tapped screen keeps witness but another navigation screen clears both fields`() {
        for (screenId in listOf("doordash.screen.tapped", "doordash.screen.other")) {
            val tapped = machine.step(state(), click(200L)).newState
            val nav = screen(300L, Flow.TaskDropoffNavigation).copy(ruleId = screenId)
            val after = machine.step(tapped, nav).newState.regions.platforms.getValue(Platform.DoorDash)
            val task = after.activeTask!!
            val kept = screenId == "doordash.screen.tapped"
            assertEquals(drop.taskId, task.taskId)
            assertEquals(if (kept) 200L else null, task.completionStepsAt)
            assertEquals(if (kept) screenId else null, task.completionStepsScreen)
            assertEquals(task, after.activeJob!!.tasks.single())
        }
    }

    @Test
    fun `arrived and idle frames keep both witness fields`() {
        for (flow in listOf(Flow.TaskDropoffArrived, Flow.Idle)) {
            val tapped = machine.step(state(), click(200L)).newState
            val after = machine.step(tapped, screen(300L, flow)).newState.regions.platforms.getValue(Platform.DoorDash)
            val task = requireNotNull(after.activeTask) { "Expected active task" }
            assertEquals(200L, task.completionStepsAt)
            assertEquals("doordash.screen.tapped", task.completionStepsScreen)
            assertEquals(task, after.activeJob!!.tasks.single())
        }
    }

    @Test
    fun `tap without screen context clears on any navigation frame`() {
        val tapped = machine.step(state(), click(200L, screenRuleId = null)).newState
        assertEquals(200L, tapped.regions.platforms.getValue(Platform.DoorDash).activeTask!!.completionStepsAt)
        val after = machine.step(tapped, screen(300L, Flow.TaskDropoffNavigation))
            .newState.regions.platforms.getValue(Platform.DoorDash)
        val task = requireNotNull(after.activeTask) { "Expected active task" }
        assertNull(task.completionStepsAt)
        assertNull(task.completionStepsScreen)
        assertEquals(task, after.activeJob!!.tasks.single())
    }

    @Test
    fun `arrival-less gift tap completes through grace or a session end inside the retire window`() {
        val policy = TransitionPolicy(GraceConfigProvider {
            mapOf(Platform.DoorDash to GraceConfig(gracePeriodMs = 8_000L))
        })
        val reducer = StateMachine(
            FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), policy, EffectMap(),
        )
        val tapped = reducer.step(state(), click(200L)).newState
        val idle = reducer.step(tapped, screen(1_000L, Flow.Idle)).newState
        val retire = idle.regions.platforms.getValue(Platform.DoorDash).pendingDestructive!!
        assertEquals(DestructiveKind.TASK_RETIRE, retire.kind)
        assertEquals(9_000L, retire.deadline)
        for (endSession in listOf(false, true)) {
            val before = if (endSession) reducer.step(idle,
                screen(2_000L, Flow.SessionEnded).copy(parsed = ParsedFields.SessionEndedFields()),
            ).newState else idle
            val pending = before.regions.platforms.getValue(Platform.DoorDash).pendingDestructive!!
            if (endSession) {
                assertEquals(DestructiveKind.SESSION_END, pending.kind)
                assertEquals(retire.since, pending.absorbedRetireSince)
                assertEquals(retire.deadline, pending.absorbedRetireDeadline)
                assertEquals(retire.deadline, pending.deadline)
            }
            val result = reducer.step(before, Observation.Timeout(
                timestamp = pending.deadline + 1L, type = TimeoutType.GRACE_COMMIT,
                targetPlatform = Platform.DoorDash, payload = ObservationPayload.GraceWake(pending.wakeId),
            ))
            val after = result.newState.regions.platforms.getValue(Platform.DoorDash)
            val retired = after.recentTasks.single { it.taskId == drop.taskId }
            assertNull(retired.arrivedAt)
            assertEquals(200L, retired.completionStepsAt)
            assertEquals("doordash.screen.tapped", retired.completionStepsScreen)
            assertEquals(retire.since, retired.completedAt)
            assertEquals(if (endSession) retire.since else null, retired.honoredRetireAt)
            assertNull(after.activeJob)
            if (endSession) assertNull(after.session)
            assertEquals(1, result.effects.filterIsInstance<AppEffect.LogEvent>()
                .count { it.event.type == AppEventType.DELIVERY_COMPLETED })
        }
    }

    @Test
    fun `flowless recognition retains tap until an actual navigation frame`() {
        val tapped = machine.step(state(), click(200L)).newState
        val warning = screen(250L, Flow.TaskDropoffNavigation).copy(flow = null, parsed = ParsedFields.None)
        val warned = machine.step(tapped, warning).newState
        assertEquals(200L, warned.regions.platforms.getValue(Platform.DoorDash).activeTask!!.completionStepsAt)
        val navigating = machine.step(warned, screen(300L, Flow.TaskDropoffNavigation)).newState
        assertNull(navigating.regions.platforms.getValue(Platform.DoorDash).activeTask!!.completionStepsAt)
    }

    @Test
    fun `geofence refusal clears witness and cannot complete the drop or job through grace`() {
        val tapped = machine.step(state(), click(200L)).newState
        val warning = screen(300L, Flow.TaskDropoffNavigation).copy(
            ruleId = "doordash.screen.dropoff_geofence_warning", modeHint = null, parsed = ParsedFields.None,
        )
        val refused = machine.step(tapped, warning)
        val region = refused.newState.regions.platforms.getValue(Platform.DoorDash)
        val task = requireNotNull(region.activeTask) { "Refusal keeps the drop active" }
        assertNull(task.completionStepsAt)
        assertNull(task.completionStepsScreen)
        assertNull(task.completedAt)
        assertEquals(task, requireNotNull(region.activeJob).tasks.single())
        val idle = machine.step(refused.newState, screen(1_000L, Flow.Idle))
        val result = commitGrace(idle.newState)
        val after = result.newState.regions.platforms.getValue(Platform.DoorDash)
        val job = requireNotNull(after.activeJob) { "A refused delivery must leave the job open" }
        val retired = after.recentTasks.single { it.taskId == drop.taskId }
        assertFalse(retired.isDeliveredEvidenced())
        assertFalse(isJobPhysicallyComplete(job, after.recentTasks, after.activeTask))
        assertTrue((refused.effects + idle.effects + result.effects).filterIsInstance<AppEffect.LogEvent>()
            .none { it.event.type == AppEventType.DELIVERY_COMPLETED })
    }

    @Test
    fun `honored retire cannot predate its only witness but preserves arrived retire time`() {
        for (arrival in listOf(null, 150L)) for (summary in listOf(false, true)) {
            val idle = machine.step(state(drop.copy(arrivedAt = arrival)), screen(1_000L, Flow.Idle)).newState
            val tapped = machine.step(idle, click(2_000L)).newState
            val end = if (summary) {
                screen(3_000L, Flow.SessionEnded).copy(parsed = ParsedFields.SessionEndedFields())
            } else {
                screen(3_000L, Flow.Idle).copy(modeHint = Mode.Offline)
            }
            val ending = machine.step(tapped, end).newState
            val pending = requireNotNull(ending.regions.platforms.getValue(Platform.DoorDash).pendingDestructive)
            assertEquals(DestructiveKind.SESSION_END, pending.kind)
            assertEquals(1_000L, pending.absorbedRetireSince)
            val result = commitGrace(ending)
            val retired = result.newState.regions.platforms.getValue(Platform.DoorDash).recentTasks
                .single { it.taskId == drop.taskId }
            val expected = if (arrival == null) 2_000L else 1_000L
            assertEquals(expected, retired.completedAt)
            assertEquals(expected, retired.honoredRetireAt)
            assertEquals(1, result.effects.filterIsInstance<AppEffect.LogEvent>()
                .count { it.event.type == AppEventType.DELIVERY_COMPLETED })
        }
    }

    @Test
    fun `resuming retired A clears witness on a different navigation screen only`() {
        for (screenId in listOf("doordash.screen.tapped", "doordash.screen.other")) {
            val before = state()
            val region = before.regions.platforms.getValue(Platform.DoorDash)
            val job = requireNotNull(region.activeJob)
            // An outstanding sibling keeps the job open so the retired A can resume.
            val open = before.copy(regions = before.regions.copy(platforms = mapOf(
                Platform.DoorDash to region.copy(activeJob = job.copy(tasks = job.tasks +
                    drop.copy(taskId = "sibling", customerNameHash = "sibling"))),
            )))
            val tapped = machine.step(open, click(200L)).newState
            val idle = machine.step(tapped, screen(1_000L, Flow.Idle)).newState
            val retired = commitGrace(idle).newState
            val retiredRegion = retired.regions.platforms.getValue(Platform.DoorDash)
            assertNull(retiredRegion.activeTask)
            assertEquals(200L, retiredRegion.recentTasks.single { it.taskId == drop.taskId }.completionStepsAt)
            val after = machine.step(retired, screen(requireNotNull(idle.regions.platforms.getValue(Platform.DoorDash).pendingDestructive).deadline + 2L, Flow.TaskDropoffNavigation)
                .copy(ruleId = screenId)).newState.regions.platforms.getValue(Platform.DoorDash)
            val task = requireNotNull(after.activeTask) { "Expected A to resume" }
            assertEquals(drop.taskId, task.taskId)
            assertNull(task.completedAt)
            val kept = screenId == "doordash.screen.tapped"
            assertEquals(if (kept) 200L else null, task.completionStepsAt)
            assertEquals(if (kept) screenId else null, task.completionStepsScreen)
            assertEquals(task, requireNotNull(after.activeJob).tasks.single { it.taskId == drop.taskId })
        }
    }

    private fun commitGrace(state: AppState): Transition {
        val pending = requireNotNull(state.regions.platforms.getValue(Platform.DoorDash).pendingDestructive)
        return machine.step(state, Observation.Timeout(
            timestamp = pending.deadline + 1L, type = TimeoutType.GRACE_COMMIT,
            targetPlatform = Platform.DoorDash, payload = ObservationPayload.GraceWake(pending.wakeId),
        ))
    }

    @Test
    fun `tapped unarrived A stays distinct from navigating sibling B`() {
        val a = drop.copy(customerAddressHash = "customer-address")
        val tapped = machine.step(state(a), click(200L)).newState
        val after = machine.step(tapped, screen(300L, Flow.TaskDropoffNavigation, "sibling"))
            .newState.regions.platforms.getValue(Platform.DoorDash)
        assertEquals(2, after.activeJob!!.tasks.count { it.phase == TaskPhase.DROPOFF })
        val retiredA = after.recentTasks.single { it.taskId == a.taskId }
        assertEquals("customer", retiredA.customerNameHash)
        assertEquals("customer-address", retiredA.customerAddressHash)
        assertEquals(200L, retiredA.completionStepsAt)
        assertNull(retiredA.arrivedAt)
        val b = after.activeTask!!
        assertFalse(a.taskId == b.taskId)
        assertEquals("sibling", b.customerNameHash)
        assertEquals("sibling-address", b.customerAddressHash)
        assertNull(b.completionStepsAt)
        assertNull(b.arrivedAt)
    }

    @Test
    fun `tap on unresolved customer preserves first-resolution guard`() {
        val tapped = machine.step(state(drop.copy(customerNameHash = null)), click(200L)).newState
        val after = machine.step(tapped, screen(300L, Flow.TaskDropoffNavigation))
            .newState.regions.platforms.getValue(Platform.DoorDash)
        val task = requireNotNull(after.activeTask) { "Expected active task" }
        assertEquals(drop.taskId, task.taskId)
        assertEquals(1, after.activeJob!!.tasks.size)
        assertNull(task.completionStepsAt)
    }

    @Test
    fun `tap is set once and mirrored with no flow arrival or event change`() {
        val before = state()
        val first = machine.step(before, click(200L))
        val region = first.newState.regions.platforms.getValue(Platform.DoorDash)
        val task = requireNotNull(region.activeTask) { "Expected active task" }
        assertEquals(200L, task.completionStepsAt)
        assertEquals("doordash.screen.tapped", task.completionStepsScreen)
        assertEquals(task, region.activeJob!!.tasks.single())
        assertNull(task.arrivedAt)
        assertNull(task.completedAt)
        assertEquals(before.regions.flow.flow, first.newState.regions.flow.flow)
        // The synthetic initial state may reconcile ordinary effects; intent adds none.
        val neutral = machine.step(before, click(200L).copy(parsed = ParsedFields.None))
        assertEquals(neutral.effects, first.effects)
        assertTrue(first.effects.none { it is AppEffect.LogEvent })
        val repeated = machine.step(first.newState, click(300L, screenRuleId = "doordash.screen.other"))
        assertEquals(200L, repeated.newState.regions.platforms.getValue(Platform.DoorDash).activeTask!!.completionStepsAt)
        assertEquals("doordash.screen.tapped", repeated.newState.regions.platforms.getValue(Platform.DoorDash).activeTask!!.completionStepsScreen)
        val neutralRepeat = machine.step(first.newState, click(300L).copy(parsed = ParsedFields.None))
        assertEquals(neutralRepeat.effects, repeated.effects)
        assertTrue(repeated.effects.none { it is AppEffect.LogEvent })
    }

    @Test
    fun `other platform other intent pickup completed task and missing active task cannot latch`() {
        val inputs = listOf(
            state() to click(200L, platform = Platform.Uber),
            state() to click(200L, intent = OfferIntent.ACCEPT),
            state(drop.copy(phase = TaskPhase.PICKUP)) to click(200L),
            state(drop.copy(completedAt = 150L)) to click(200L),
        )
        inputs.forEach { (before, obs) ->
            val after = machine.step(before, obs).newState.regions.platforms.getValue(Platform.DoorDash)
            assertNull(after.activeTask!!.completionStepsAt)
        }
        val before = state()
        val region = before.regions.platforms.getValue(Platform.DoorDash).copy(activeTask = null)
        val after = machine.step(before.copy(regions = before.regions.copy(platforms = mapOf(Platform.DoorDash to region))), click(200L))
        assertNull(after.newState.regions.platforms.getValue(Platform.DoorDash).activeTask)
    }

    @Test
    fun `strict and inline completeness require finished unassigned-free positive evidence`() {
        val job = Job("job", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L, tasks = listOf(drop))
        for (arrived in listOf(null, 150L)) for (tap in listOf(null, 200L)) {
            for (completed in listOf(null, 300L)) for (unassigned in listOf(null, 250L)) {
                val task = drop.copy(arrivedAt = arrived, completionStepsAt = tap, completedAt = completed, unassignedAt = unassigned)
                val expected = completed != null && unassigned == null && (arrived != null || tap != null)
                assertEquals(expected, isJobPhysicallyComplete(job, listOf(task), null))
                assertEquals(expected, isJobPhysicallyComplete(job, emptyList(), task))
            }
        }
    }

    @Test
    fun `coverage accepts a witnessed drop but rejects a grace stamp or unassigned witness`() {
        val pickups = listOf("store-a", "store-b").mapIndexed { i, store ->
            Task("pickup-$i", "job", TaskPhase.PICKUP, storeName = store, startedAt = 100L,
                completedAt = 150L, customerNameHash = "customer")
        }
        val placeholder = Task("extra-drop", "job", TaskPhase.DROPOFF, startedAt = 100L)
        val job = Job("job", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L, tasks = pickups + drop + placeholder)
        val witnessed = drop.copy(completedAt = 300L, completionStepsAt = 200L)
        assertTrue(isJobPhysicallyComplete(job, pickups + witnessed, null))
        assertTrue(isJobPhysicallyComplete(job, pickups, witnessed))
        assertFalse(isJobPhysicallyComplete(job, pickups + witnessed.copy(completionStepsAt = null), null))
        assertFalse(isJobPhysicallyComplete(job, pickups + witnessed.copy(unassignedAt = 250L), null))
    }

    @Test
    fun `legacy task JSON defaults to no witness and new snapshot preserves it`() {
        val legacy = """{"taskId":"drop","jobId":"job","phase":"DROPOFF","startedAt":100,"arrivedAt":null}"""
        assertNull(StateJson.decodeFromString<Task>(legacy).completionStepsAt)
        assertNull(StateJson.decodeFromString<Task>(legacy).completionStepsScreen)
        assertNull(StateJson.decodeFromString<Task>(legacy).confirmedAt)
        val snapshot = state(drop.copy(completionStepsAt = 200L, completionStepsScreen = "doordash.screen.tapped"))
        assertEquals(snapshot, StateJson.decodeFromString<AppState>(StateJson.encodeToString(snapshot)))
    }
}
