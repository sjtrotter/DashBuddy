package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #528 Slice A — per-drop realized pay reaches the `DELIVERY_COMPLETED` payload at both mint sites
 * (the PostTask exit and the #596 close-out). The invariant under test: the `dropRealizedPay`
 * across a stacked job's completion rows sums EXACTLY to the receipt total (`ParsedPay.total`) in
 * integer cents — instead of one drop absorbing the whole combined receipt while the others carry
 * null pay.
 */
class EffectMapDropPayTest {

    private val effectMap = EffectMap()

    private fun appState(
        flow: FlowRegion = FlowRegion(),
        platforms: Map<Platform, PlatformRegion> = emptyMap(),
    ) = AppState(
        regions = Regions(flow = flow, platforms = platforms, crossPlatform = CrossPlatformRegion()),
    )

    private fun screenObs(flow: Flow?, timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "test.rule",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = null,
        parsed = ParsedFields.None,
    )

    private fun dropoff(
        id: String,
        store: String?,
        cust: String,
        completedAt: Long? = 400L,
    ) = Task(
        taskId = id,
        jobId = "J",
        phase = TaskPhase.DROPOFF,
        storeName = store,
        customerNameHash = cust,
        startedAt = 300L,
        completedAt = completedAt,
    )

    private fun completedRows(prev: AppState, next: AppState, obs: Observation): List<DeliveryPayload> =
        effectMap.diff(prev, next, obs)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
            .map { it.event.payload as DeliveryPayload }

    private fun cents(v: Double): Long = Math.round(v * 100.0)

    @Test
    fun `stacked close-out — per-drop shares sum to the receipt total`() {
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 8.0)),
            customerTips = listOf(
                ParsedPayItem("Target (02426)", 6.0),
                ParsedPayItem("Maple Street Biscuit - Alamo Ranch", 2.0),
            ),
        )
        val postFields = ParsedFields.PostTaskFields(
            totalPay = 16.0,
            parsedPay = receipt,
            sessionEarnings = 60.0,
        )
        val dropA = dropoff("d-target", "Target", "cA", completedAt = 400L)
        val dropB = dropoff("d-maple", "Maple Street Biscuit Company", "cB", completedAt = 410L)

        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 60.0),
            activeJob = Job("J", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 50L),
            recentTasks = listOf(dropA, dropB),
            lastPostTaskFields = postFields,
            lastAnnouncedPostTaskTaskId = "d-target",
        )
        // Job closes this step (activeJob → null) → #596 close-out mints a completion per drop.
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))
        val obs = screenObs(Flow.Idle, timestamp = 3000L)

        val rows = completedRows(prev, next, obs)
        assertEquals("both drops complete", 2, rows.size)

        val a = rows.single { it.taskId == "d-target" }
        val b = rows.single { it.taskId == "d-maple" }
        assertEquals("exact tip \$6 + base \$8/2", 10.0, a.dropRealizedPay!!, 0.001)
        assertEquals("exact tip \$2 + base \$4", 6.0, b.dropRealizedPay!!, 0.001)

        // The no-double-count invariant, in integer cents.
        assertEquals(cents(receipt.total), rows.sumOf { cents(it.dropRealizedPay!!) })
    }

    @Test
    fun `single-drop close-out — dropRealizedPay is the whole receipt total`() {
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 4.5)),
            customerTips = listOf(ParsedPayItem("Wendy's", 3.0)),
        )
        val postFields = ParsedFields.PostTaskFields(totalPay = 7.5, parsedPay = receipt, sessionEarnings = 47.5)
        val drop = dropoff("d1", "Wendy's", "cW", completedAt = 400L)

        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 47.5),
            activeJob = Job("J", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 50L),
            recentTasks = listOf(drop),
            lastPostTaskFields = postFields,
            lastAnnouncedPostTaskTaskId = "d1",
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals(1, rows.size)
        assertEquals(7.5, rows.single().dropRealizedPay!!, 0.001)
    }

    @Test
    fun `receipt-less close-out — dropRealizedPay is null (nothing to attribute)`() {
        // #596 routine: the next offer chained straight over the drop, no post-delivery receipt.
        val drop = dropoff("d1", "Chipotle", "cC", completedAt = 400L)
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 20.0),
            activeJob = Job("J", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 50L),
            recentTasks = listOf(drop),
            lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals(1, rows.size)
        assertNull("no receipt → null realized pay", rows.single().dropRealizedPay)
    }

    @Test
    fun `PostTask exit — single delivery carries dropRealizedPay`() {
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 4.5)),
            customerTips = listOf(ParsedPayItem("Wendy's", 3.0)),
        )
        val postFields = ParsedFields.PostTaskFields(totalPay = 7.5, parsedPay = receipt, sessionEarnings = 47.5)
        val drop = dropoff("T6", "Wendy's", "cW", completedAt = null)

        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 47.5),
            activeJob = Job("J", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 50L),
            recentTasks = listOf(drop),
            lastPostTaskFields = postFields,
        )
        val regionNext = regionPrev.copy()

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals(1, rows.size)
        assertNotNull(rows.single().parsedPay)
        assertEquals(7.5, rows.single().dropRealizedPay!!, 0.001)
    }
}
