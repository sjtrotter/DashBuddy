package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #691 — the write-side offer-pay fallback stamp at both `DELIVERY_COMPLETED` mint sites (PostTask
 * exit + #596 close-out). When the whole job was receipt-less, each owed drop's completion carries
 * an equal-split share of the accepted-offer pay ([DeliveryPayload.offerPayShare]) so the fold can
 * produce a real net row instead of a $0-unattributed one. The guards under test are the VET
 * amendments: W1-a (job-scoped eligibility — a collapsed bare-total receipt does NOT stamp
 * siblings), W1-c (denominator is the job's OWN owed dropoff set — a mid-stack mint takes its share,
 * not the full total), and W1-b (a pay-less offer stamps nothing).
 */
class OfferPayFallbackEffectMapTest {

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
        cust: String?,
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

    /** A job carrying an accepted-offer pay total and its owed dropoff mirror ([Job.tasks], W1-c). */
    private fun job(offerPay: Double?, tasks: List<Task>) = Job(
        jobId = "J",
        offerStoreHint = emptyList(),
        parentOfferHash = null,
        acceptedOffers = listOf(
            AcceptedOfferEconomics(offerHash = "h", payAmount = offerPay, acceptedAt = 50L),
        ),
        tasks = tasks,
        startedAt = 50L,
    )

    private fun completedRows(prev: AppState, next: AppState, obs: Observation): List<DeliveryPayload> =
        effectMap.diff(prev, next, obs)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
            .map { it.event.payload as DeliveryPayload }

    private fun cents(v: Double): Long = Math.round(v * 100.0)

    @Test
    fun `receipt-less close-out stamps every drop an equal-split offer share`() {
        // A wholly receipt-less two-drop shop job (offer $12.95, no post-delivery receipt) closes.
        val dropA = dropoff("d1", "H-E-B", "cA", completedAt = 400L)
        val dropB = dropoff("d2", "H-E-B", "cB", completedAt = 410L)
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = job(offerPay = 12.95, tasks = listOf(dropA, dropB)),
            recentTasks = listOf(dropA, dropB),
            lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals("both drops complete", 2, rows.size)
        val a = rows.single { it.taskId == "d1" }
        val b = rows.single { it.taskId == "d2" }
        assertEquals("12.95 / 2, remainder to last", 6.48, a.offerPayShare!!, 0.0001)
        assertEquals(6.47, b.offerPayShare!!, 0.0001)
        assertEquals("shares sum EXACTLY to the offer total", 1295L, rows.sumOf { cents(it.offerPayShare!!) })
        assertNull("no receipt → no dropRealizedPay", a.dropRealizedPay)
        assertNull(a.totalPay)
    }

    @Test
    fun `W1-a — a collapsed bare-total receipt pinned to one drop leaves siblings UNSTAMPED`() {
        // The whole-job receipt state is NOT empty (a bare totalPay landed, parsedPay null → the
        // apportioner returns an empty map). Receipt fields are pinned to d1; d2 is receipt-less BY
        // DESIGN. Without the job-scoped W1-a guard, d2 would get an offer share on top of d1's
        // receipt → ~1.9× the real pay. It must stay unstamped.
        val dropA = dropoff("d1", "H-E-B", "cA", completedAt = 400L)
        val dropB = dropoff("d2", "H-E-B", "cB", completedAt = 410L)
        val postFields = ParsedFields.PostTaskFields(totalPay = 10.0, parsedPay = null, sessionEarnings = 40.0)
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = job(offerPay = 12.95, tasks = listOf(dropA, dropB)),
            recentTasks = listOf(dropA, dropB),
            lastPostTaskFields = postFields,
            lastAnnouncedPostTaskTaskId = "d1",
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals(2, rows.size)
        rows.forEach { assertNull("job showed a receipt → no offer-pay estimate on any drop", it.offerPayShare) }
        // The pinned drop still carries its bare receipt total; the sibling stays null (as today).
        assertEquals(10.0, rows.single { it.taskId == "d1" }.totalPay!!, 0.0001)
        assertNull(rows.single { it.taskId == "d2" }.totalPay)
    }

    @Test
    fun `W1-c — a mid-stack PostTask exit takes the drop's share, not the full offer total`() {
        // A receipt-less mid-stack completion: d1 completes while its sibling d2 is still owed (an
        // unresolved, identity-less placeholder). The identity-firewall mint denominator would shrink
        // to just d1 → d1 would take the WHOLE $12.95 (1.5×+ over-count). W1-c splits over the job's
        // OWN owed dropoff set (Job.tasks, 2 drops) → d1 gets its 6.48 share; d2's 6.47 mints when it
        // resolves. The job stays open (no close-out this step).
        val d1 = dropoff("d1", "H-E-B", "cA", completedAt = null)
        val placeholder = dropoff("d2", null, cust = null, completedAt = null) // identity-less, owed
        val activeJob = job(offerPay = 12.95, tasks = listOf(d1, placeholder))
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = activeJob,
            recentTasks = listOf(d1),
            lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy() // job stays open (mid-stack)

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals("only d1 completes at the mid-stack exit", 1, rows.size)
        assertEquals(
            "d1 takes ITS 6.48 share (offer/2), NOT the full 12.95 — W1-c denominator is the owed set",
            6.48, rows.single().offerPayShare!!, 0.0001,
        )
        // The sibling's remainder is deterministic by taskId order: equalSplit gives d2 → 6.47, so
        // the two eventual rows sum to 12.95 (never 1.5× = 19.42).
        val d2Share = cloud.trotter.dashbuddy.domain.state.DropPayApportioner
            .equalSplit(12.95, listOf(d1, placeholder))["d2"]!!
        assertEquals(6.47, d2Share, 0.0001)
        assertEquals("Σ of both eventual rows == offer total", 1295L, cents(6.48) + cents(d2Share))
    }

    @Test
    fun `W1-b — a pay-less offer stamps no offer share`() {
        val dropA = dropoff("d1", "H-E-B", "cA", completedAt = 400L)
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 0.0),
            activeJob = job(offerPay = null, tasks = listOf(dropA)),
            recentTasks = listOf(dropA),
            lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals(1, rows.size)
        assertNull("no offer pay → no estimate row", rows.single().offerPayShare)
        assertNull(rows.single().dropRealizedPay)
    }

    @Test
    fun `an accepted-never-delivered job mints no completion and no offer dollars`() {
        // The seq-53 counter-fixture shape: an accepted offer whose job never completes a dropoff.
        // No mint fires → no offerPayShare is ever stamped. Here the job is open with a PICKUP only
        // and no flow transition that could complete a delivery.
        val pickup = Task(
            taskId = "p1", jobId = "J", phase = TaskPhase.PICKUP,
            storeName = "H-E-B", customerNameHash = null, startedAt = 300L, completedAt = null,
        )
        val region = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 0.0),
            activeJob = job(offerPay = 24.25, tasks = listOf(pickup)),
            recentTasks = emptyList(),
            lastPostTaskFields = null,
        )
        // Same state prev→next (no completion, no close-out).
        val prev = appState(FlowRegion(flow = Flow.TaskPickupNavigation), mapOf(Platform.DoorDash to region))
        val next = appState(FlowRegion(flow = Flow.TaskPickupNavigation), mapOf(Platform.DoorDash to region))

        val rows = completedRows(prev, next, screenObs(Flow.TaskPickupNavigation, timestamp = 3000L))
        assertTrue("no completion mint → no offer dollars", rows.isEmpty())
    }
}
