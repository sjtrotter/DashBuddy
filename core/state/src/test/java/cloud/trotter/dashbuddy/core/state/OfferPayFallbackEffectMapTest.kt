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
    fun `FIX1 — a mid-stack PostTask exit does NOT stamp (not the last open owed drop)`() {
        // A receipt-less mid-stack completion: d1 completes while its sibling d2 is still owed. FIX 1's
        // final-shape gate stamps a PostTask-exit drop ONLY when it is the LAST OPEN owed dropoff. d2 is
        // an identity-RESOLVED, undelivered drop (customer hash present, completedAt == null) → it is an
        // ACCOUNTABLE sibling → it holds the shape non-final → d1 is mid-stack → NO stamp (the traded
        // class: its dollars ride unattributed; it got nothing pre-#691 too). This kills the
        // estimate-then-late-receipt / cents-drift-across-mints over-count.
        // NOTE (PR #754): the blocker MUST now be identity-RESOLVED — an identity-less placeholder no
        // longer holds the gate shut (see the companion test below); that narrowing is the intended
        // #630-review side effect on the #691 estimate gate.
        val d1 = dropoff("d1", "H-E-B", "cA", completedAt = null)
        val d2 = dropoff("d2", "H-E-B", "cB", completedAt = null) // identity-resolved, still owed
        val activeJob = job(offerPay = 12.95, tasks = listOf(d1, d2))
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = activeJob,
            activeTask = d1,
            recentTasks = emptyList(),
            lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy() // job stays open (mid-stack)

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals("only d1 completes at the mid-stack exit", 1, rows.size)
        assertNull("mid-stack exit → no offer stamp (final-shape gate)", rows.single().offerPayShare)
    }

    @Test
    fun `FIX1 companion (PR #754) — an identity-less placeholder sibling no longer blocks the estimate`() {
        // The #630-review narrowing: an unresolved, customer-TBD placeholder (both identity hashes null)
        // can NEVER mint, so it must not wedge the final-shape gate shut. d1 completes at a receipt-less
        // PostTask exit while only such a placeholder is "owed" → d1 IS the last open ACCOUNTABLE drop →
        // it gets its equal-split offer share (pre-#754 the placeholder wrongly held the shape non-final
        // → d1 rode unattributed forever). The split denominator still KEEPS the placeholder (per-owed-
        // order), so d1's share is total/2, not the whole offer.
        val d1 = dropoff("d1", "H-E-B", "cA", completedAt = null)
        val placeholder = dropoff("d2", null, cust = null, completedAt = null) // identity-less, owed
        val activeJob = job(offerPay = 12.95, tasks = listOf(d1, placeholder))
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = activeJob,
            activeTask = d1,
            recentTasks = emptyList(),
            lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy() // job stays open

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals("only d1 completes", 1, rows.size)
        assertEquals(
            "the placeholder no longer wedges the gate → d1 gets its equal split (12.95 / 2 owed orders)",
            6.48, rows.single().offerPayShare!!, 0.001,
        )
    }

    @Test
    fun `FIX1 — the LAST open owed drop completing at a PostTask exit IS stamped`() {
        // d1 already delivered earlier (completedAt set). d2 is the final owed drop and completes at
        // this PostTask exit → final-shape holds → d2 is stamped its share (no over-count: d1 was
        // stamped on its own earlier exit).
        val d1 = dropoff("d1", "H-E-B", "cA", completedAt = 380L)
        val d2 = dropoff("d2", "H-E-B", "cB", completedAt = null) // completes now (active)
        val activeJob = job(offerPay = 12.95, tasks = listOf(d1, d2))
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = activeJob,
            activeTask = d2,
            recentTasks = listOf(d1),
            lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy(activeTask = d2.copy(completedAt = 3000L))

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals(1, rows.size)
        assertEquals("last open owed drop → stamped its 6.47 share", 6.47, rows.single().offerPayShare!!, 0.0001)
    }

    @Test
    fun `FIX1 — a solo receipt-less drop at a PostTask exit is stamped (seq-10 shape)`() {
        // The 07-05 seq-10 shape: a single-drop receipt-less job completes at its PostTask exit. It is
        // trivially the last open owed drop → stamped the whole offer total.
        val d1 = dropoff("d1", "H-E-B", "cA", completedAt = null)
        val activeJob = job(offerPay = 7.50, tasks = listOf(d1))
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 20.0),
            activeJob = activeJob,
            activeTask = d1,
            recentTasks = listOf(d1),
            lastPostTaskFields = null,
        )
        val regionNext = regionPrev.copy(activeTask = d1.copy(completedAt = 3000L))

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals(1, rows.size)
        assertEquals("solo final drop stamped the whole offer", 7.50, rows.single().offerPayShare!!, 0.0001)
    }

    @Test
    fun `FIX2a — a 0-coerced pseudo-receipt does NOT suppress the offer stamp`() {
        // A transient delivery_summary_collapsed frame coerces totalPay to 0.0 (buildPostTask ?: 0.0)
        // AND is pinned to d1 as lastPostTaskFields. It is NOT pay-bearing (0.0, no parsedPay), so it
        // must NOT suppress the estimate — the solo receipt-less drop is still stamped.
        val d1 = dropoff("d1", "H-E-B", "cA", completedAt = 400L)
        val zeroReceipt = ParsedFields.PostTaskFields(totalPay = 0.0, parsedPay = null, sessionEarnings = 20.0)
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 20.0),
            activeJob = job(offerPay = 7.50, tasks = listOf(d1)),
            recentTasks = listOf(d1),
            lastPostTaskFields = zeroReceipt,
            lastAnnouncedPostTaskTaskId = "d1",
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals(1, rows.size)
        assertEquals("0-dollar pseudo-receipt is not pay-bearing → estimate stands", 7.50, rows.single().offerPayShare!!, 0.0001)
    }

    @Test
    fun `FIX2b — a stale PREVIOUS-job receipt (announce id = another job's task) does NOT suppress`() {
        // completeActiveJob cleared lastPostTaskFields but NOT lastAnnouncedPostTaskTaskId. A flickered
        // receipt from a PRIOR job re-set a pay-bearing lastPostTaskFields whose announce id ("old")
        // provably belongs to a DIFFERENT job's task (in recentTasks). It must NOT suppress THIS
        // receipt-less job's estimate.
        val oldDrop = Task(taskId = "old", jobId = "JPREV", phase = TaskPhase.DROPOFF, customerNameHash = "cx", startedAt = 10L, completedAt = 50L)
        val d1 = dropoff("d1", "H-E-B", "cA", completedAt = 400L)
        val stalePayBearing = ParsedFields.PostTaskFields(totalPay = 11.0, parsedPay = null, sessionEarnings = 40.0)
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = job(offerPay = 7.50, tasks = listOf(d1)),
            recentTasks = listOf(oldDrop, d1),
            lastPostTaskFields = stalePayBearing,
            lastAnnouncedPostTaskTaskId = "old", // provably JPREV's task
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        val d = rows.single { it.taskId == "d1" }
        assertEquals("stale foreign-job receipt does not suppress → estimate stands", 7.50, d.offerPayShare!!, 0.0001)
    }

    @Test
    fun `FIX2b — an unattributable pay-bearing receipt (announce id null) SUPPRESSES (fail-closed)`() {
        // A pay-bearing lastPostTaskFields with a NULL announce id cannot be attributed to any task.
        // Fail-closed against over-count: suppress the estimate (a receipt might belong to this job).
        val d1 = dropoff("d1", "H-E-B", "cA", completedAt = 400L)
        val unattributable = ParsedFields.PostTaskFields(totalPay = 11.0, parsedPay = null, sessionEarnings = 40.0)
        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = job(offerPay = 7.50, tasks = listOf(d1)),
            recentTasks = listOf(d1),
            lastPostTaskFields = unattributable,
            lastAnnouncedPostTaskTaskId = null,
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertNull("unattributable pay-bearing receipt → suppress (fail-closed)", rows.single().offerPayShare)
    }

    @Test
    fun `FIX6 — an eligible drop with a pay-less offer logs one PII-safe WARN`() {
        val logged = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { logged += message }
        }
        timber.log.Timber.plant(tree)
        try {
            // Receipt-less, final-shape (close-out) job with a PAY-LESS offer → eligible but the split
            // yields nothing → one WARN, no share.
            val d1 = dropoff("d1", "H-E-B", "cA", completedAt = 400L)
            val regionPrev = PlatformRegion(
                platform = Platform.DoorDash,
                mode = Mode.Online,
                session = Session("s1", startedAt = 100L, runningEarnings = 0.0),
                activeJob = job(offerPay = null, tasks = listOf(d1)),
                recentTasks = listOf(d1),
                lastPostTaskFields = null,
            )
            val regionNext = regionPrev.copy(activeJob = null)
            val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
            val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))
            val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
            assertNull(rows.single().offerPayShare)
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertEquals(
            "one eligible-but-unsplit WARN surfaced", 1,
            logged.count { it.contains("offer-pay estimate eligible but unsplit") },
        )
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
