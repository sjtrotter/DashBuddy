package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
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
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1315: completion intent witnesses delivery, never arrival or an abandoned order. */
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

    private fun click(t: Long, platform: Platform = Platform.DoorDash, intent: String = TaskIntent.COMPLETE_DELIVERY) =
        Observation.Click(
            timestamp = t, captureId = null, ruleId = "${platform.wire}.click.complete_delivery",
            metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
            parsed = ParsedFields.ClickFields(intent = intent),
        )

    @Test
    fun `tap is set once and mirrored with no flow arrival or event change`() {
        val before = state()
        val first = machine.step(before, click(200L))
        val region = first.newState.regions.platforms.getValue(Platform.DoorDash)
        assertEquals(200L, region.activeTask!!.completionStepsAt)
        assertEquals(region.activeTask, region.activeJob!!.tasks.single())
        assertNull(region.activeTask!!.arrivedAt)
        assertNull(region.activeTask!!.completedAt)
        assertEquals(before.regions.flow.flow, first.newState.regions.flow.flow)
        // The synthetic initial state may reconcile ordinary effects; intent adds none.
        val neutral = machine.step(before, click(200L).copy(parsed = ParsedFields.None))
        assertEquals(neutral.effects, first.effects)
        assertTrue(first.effects.none { it is AppEffect.LogEvent })
        val repeated = machine.step(first.newState, click(300L))
        assertEquals(200L, repeated.newState.regions.platforms.getValue(Platform.DoorDash).activeTask!!.completionStepsAt)
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
        val snapshot = state(drop.copy(completionStepsAt = 200L))
        assertEquals(snapshot, StateJson.decodeFromString<AppState>(StateJson.encodeToString(snapshot)))
    }
}
