package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskIntent
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** #1315 Level B: hand-authored lifecycle invariants, never replay == captured database. */
class GiftDropOpenJobReplayTest {
    private val session = "snapshots/sessions/gift_drop_open_job_2026_10_11"
    private fun dd(step: SessionReplay.ReplayStep) = step.stateAfter.regions.platforms.getValue(Platform.DoorDash)

    @Test
    fun `gift completion closes its job before the next accept`() {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val clicks = File("src/test/resources/$session").listFiles()!!
            .filter { it.name.contains("_cli_") }.sortedBy { it.name }
            .map { SessionReplay.loadClickFrame("$session/${it.name}") }
        assertEquals("all supplied envelopes participate", 48, screens.size + clicks.size)
        // Deadline +1 s: a frame lapses grace strictly past its deadline, not at the deadline (CLAUDE.md §3(e)).
        val steps = SessionReplay.reduceMixed(screens + clicks + SessionReplay.graceCommit(1791738412182L))
        println(SessionReplay.trace(steps))

        val giftFiles = setOf("24_win_UNKNOWN.json", "31_win_UNKNOWN.json", "33_win_UNKNOWN.json", "35_win_UNKNOWN.json")
        steps.filter { it.frame?.file in giftFiles }.also { gifts ->
            assertEquals(4, gifts.size)
            gifts.forEach {
                assertEquals("#1314 gift recognition prerequisite", "doordash.screen.dropoff_pre_arrival",
                    (it.observation as Observation.Screen).ruleId)
            }
        }
        val first = steps.first { dd(it).activeJob != null }
        val jobId = dd(first).activeJob!!.jobId
        val dropId = dd(first).activeJob!!.tasks.single { it.phase == TaskPhase.DROPOFF }.taskId
        val commit = steps.single { (it.observation as? Observation.Timeout)?.type == TimeoutType.GRACE_COMMIT }
        val region = dd(commit)
        val drop = (region.recentTasks + listOfNotNull(region.activeTask)).single { it.taskId == dropId }
        println("#1315 gift drop: arrivedAt=${drop.arrivedAt}, completionStepsAt=${drop.completionStepsAt}, completedAt=${drop.completedAt}")
        // #1314 recognizes this sheet as NAVIGATION, so it supplies no arrival/dwell timestamp.
        assertNull("gift navigation does not manufacture arrival", drop.arrivedAt)
        val clickAt = SessionReplay.loadClickFrame("$session/34_cli_complete_delivery.json").atMs
        val click = steps.single { it.observation is Observation.Click && it.observation.timestamp == clickAt }
        assertEquals("completion click must survive the recognized gift screen context",
            TaskIntent.COMPLETE_DELIVERY, (click.observation as Observation.Click).let { (it.parsed as? ParsedFields.ClickFields)?.intent })
        assertEquals(clickAt, drop.completionStepsAt)
        assertNull("job closes on the supplied GRACE_COMMIT", region.activeJob)
        val retired = region.recentTasks.single { it.taskId == dropId }
        assertNotNull(retired.completedAt)
        assertNotNull(retired.completionStepsAt)

        val nextPickup = steps.single { it.frame?.file == "51_win_pickup_pre_arrival.json" }
        val fresh = dd(nextPickup).activeJob!!
        assertNotEquals(jobId, fresh.jobId)
        assertEquals(1, fresh.acceptedOffers.size)
        val dropEvents = steps.flatMap { it.events }.filter {
            (it.payload as? DeliveryPayload)?.let { p -> p.jobId == jobId && p.taskId == dropId } == true
        }
        assertEquals(1, dropEvents.count { it.type == AppEventType.DELIVERY_COMPLETED })
        assertEquals(1, dropEvents.count { it.type == AppEventType.DELIVERY_CONFIRMED })
        assertFalse(steps.filter { it.observation.timestamp < nextPickup.observation.timestamp }.flatMap { it.events }.any {
            it.type == AppEventType.PICKUP_ARRIVED && (it.payload as? PickupPayload)?.jobId == fresh.jobId
        })
    }

    @Test
    fun `second same-store accept creates a pickup without replaying the old arrival`() {
        fun raw(t: Long, flow: Flow, parsed: ParsedFields) = SessionReplay.RawInput(
            Observation.Screen(timestamp = t, captureId = null, ruleId = "doordash.screen.synthetic",
                metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = Mode.Online, parsed = parsed), t,
        )
        fun offer(hash: String) = ParsedFields.OfferFields(parsedOffer = ParsedOffer(
            offerHash = hash, quoteBasis = OfferQuoteBasis.TOTAL, payAmount = 10.0,
            orders = listOf(ParsedOrder(0, OrderType.PICKUP, "H-E-B", 1, false, emptySet())),
        ))
        fun pickup(sub: TaskSubFlow) = ParsedFields.TaskFields(
            phase = TaskPhase.PICKUP, subFlow = sub, storeName = "H-E-B", customerNameHash = "first-customer",
        )
        val inputs = listOf(
            raw(1_000L, Flow.OfferPresented, offer("first")),
            SessionReplay.syntheticOfferClick(OfferIntent.ACCEPT, 2_000L),
            raw(3_000L, Flow.TaskPickupNavigation, pickup(TaskSubFlow.NAVIGATION)),
            raw(4_000L, Flow.TaskPickupArrived, pickup(TaskSubFlow.ARRIVED)),
            raw(5_000L, Flow.TaskDropoffNavigation, ParsedFields.TaskFields(
                phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION, customerNameHash = "first-customer",
            )),
            raw(6_000L, Flow.OfferPresented, offer("second")),
            SessionReplay.syntheticOfferClick(OfferIntent.ACCEPT, 7_000L),
            raw(8_000L, Flow.TaskPickupNavigation, pickup(TaskSubFlow.NAVIGATION).copy(customerNameHash = "second-customer")),
        )
        val steps = SessionReplay.reduceMixed(inputs)
        println(SessionReplay.trace(steps))
        val before = dd(steps.single { it.observation.timestamp == 5_000L }).activeJob!!
        val oldPickup = before.tasks.single { it.phase == TaskPhase.PICKUP }
        assertNotNull(oldPickup.completedAt)
        assertEquals(4_000L, oldPickup.arrivedAt)
        val accepted = steps.last()
        val job = dd(accepted).activeJob!!
        assertEquals(before.jobId, job.jobId)
        assertEquals(2, job.acceptedOffers.size)
        val added = job.tasks.filter { task -> before.tasks.none { it.taskId == task.taskId } }
        assertEquals(1, added.count { it.phase == TaskPhase.DROPOFF })
        assertEquals("H-E-B", added.single { it.phase == TaskPhase.PICKUP }.expectedStoreHint)
        assertFalse("accept must not re-emit the confirmed pickup's stale arrival",
            steps.filter { it.observation.timestamp >= 7_000L }.flatMap { it.events }.any { it.type == AppEventType.PICKUP_ARRIVED })
    }
}
