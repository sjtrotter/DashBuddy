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
import org.junit.Assert.assertTrue
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

    // =========================================================================
    // #630 — mid-stack / multi-receipt hardening
    // =========================================================================

    /** A job whose owed-dropoff mirror ([Job.tasks]) drives the #630 final-shape gate. */
    private fun jobWithTasks(tasks: List<Task>) = Job(
        jobId = "J",
        offerStoreHint = emptyList(),
        parentOfferHash = null,
        tasks = tasks,
        startedAt = 50L,
    )

    @Test
    fun `mid-stack non-final PostTask exit — no share of a partial receipt, receipt not attached (#630 R2)`() {
        // 3-drop stack: A delivered, B exiting PostTask NOW with the PARTIAL receipt R_AB visible,
        // C still owed. B's completion must carry NO dropRealizedPay and NO parsedPay/totalPay —
        // attaching the partial receipt would make the fold stamp RECEIPT_TOTAL with R_AB.total,
        // converting the under-attribution into an OVER-count once A/C mint their R_ABC shares.
        val receiptAB = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 6.0)),
            customerTips = listOf(ParsedPayItem("Target", 4.0)),
        ) // total $10 — covers A+B only
        val postFieldsAB = ParsedFields.PostTaskFields(totalPay = 10.0, parsedPay = receiptAB, sessionEarnings = 30.0)
        val dropA = dropoff("dA", "Target", "cA", completedAt = 350L)
        val dropB = dropoff("dB", "Maple", "cB", completedAt = null)
        val dropC = dropoff("dC", "Chili's", "cC", completedAt = null) // the still-owed sibling

        val logged = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { logged += message }
        }
        timber.log.Timber.plant(tree)
        try {
            val regionPrev = PlatformRegion(
                platform = Platform.DoorDash,
                mode = Mode.Online,
                session = Session("s1", startedAt = 100L, runningEarnings = 30.0),
                activeJob = jobWithTasks(listOf(dropA, dropB, dropC)),
                activeTask = dropB,
                recentTasks = listOf(dropA),
                lastPostTaskFields = postFieldsAB,
                lastAnnouncedPostTaskTaskId = "dB",
            )
            val regionNext = regionPrev.copy()

            val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
            val next = appState(FlowRegion(flow = Flow.TaskDropoffNavigation), mapOf(Platform.DoorDash to regionNext))

            val rows = completedRows(prev, next, screenObs(Flow.TaskDropoffNavigation, timestamp = 3000L))
            assertEquals("only B completes on this exit", 1, rows.size)
            val b = rows.single()
            assertEquals("dB", b.taskId)
            assertNull("no share of a partial receipt (R2)", b.dropRealizedPay)
            assertNull("the partial receipt must NOT ride the payload (fold RECEIPT_TOTAL over-count)", b.parsedPay)
            assertNull(b.totalPay)
            assertNull("estimate independently withheld (requireFinalShape + pay-bearing receipt)", b.offerPayShare)
            assertTrue(
                "the non-final receipt exit emits ONE observability WARN (R4)",
                logged.any { it.contains("#630 mid-stack non-final receipt exit") },
            )
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `mid-stack shape at close — final receipt splits over the FULL denominator, shortfall visible (#630 R4)`() {
        // The same stack later closes with the FINAL receipt R_ABC. The close-out splits R_ABC over
        // the full minted denominator INCLUDING the early-minted B (DD-2: per-row honesty) — B's
        // re-emission is swallowed live by effects_fired, so its computed share is intentionally NOT
        // redistributed to A/C: the persisted rows sum to (total − B's share) and the difference
        // surfaces read-side as unattributed.
        val receiptABC = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 9.0)),
            customerTips = listOf(
                ParsedPayItem("Target", 4.0),
                ParsedPayItem("Maple", 2.0),
                ParsedPayItem("Chili's", 3.0),
            ),
        ) // total $18
        val postFieldsABC = ParsedFields.PostTaskFields(totalPay = 18.0, parsedPay = receiptABC, sessionEarnings = 48.0)
        val dropA = dropoff("dA", "Target", "cA", completedAt = 350L)
        val dropB = dropoff("dB", "Maple", "cB", completedAt = 400L)
        val dropC = dropoff("dC", "Chili's", "cC", completedAt = 450L)

        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 48.0),
            activeJob = jobWithTasks(listOf(dropA, dropB, dropC)),
            recentTasks = listOf(dropA, dropB, dropC),
            lastPostTaskFields = postFieldsABC,
            lastAnnouncedPostTaskTaskId = "dC",
        )
        val regionNext = regionPrev.copy(activeJob = null)

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 5000L))
        assertEquals("close-out emits all three (B's re-emission dedups live)", 3, rows.size)

        // Tip-exact shares over the FULL 3-drop denominator: base $9/3 = $3 each.
        val a = rows.single { it.taskId == "dA" }
        val b = rows.single { it.taskId == "dB" }
        val c = rows.single { it.taskId == "dC" }
        assertEquals(7.0, a.dropRealizedPay!!, 0.001)
        assertEquals(5.0, b.dropRealizedPay!!, 0.001)
        assertEquals(6.0, c.dropRealizedPay!!, 0.001)
        assertEquals(
            "Σ(all computed shares) == final receipt total in cents",
            cents(receiptABC.total),
            rows.sumOf { cents(it.dropRealizedPay!!) },
        )
        // The persisted invariant: B minted null at the non-final exit (previous test), so the rows
        // that actually persist sum to total − share(B) — the visible, never-redistributed shortfall.
        assertEquals(
            cents(receiptABC.total) - cents(b.dropRealizedPay!!),
            cents(a.dropRealizedPay!!) + cents(c.dropRealizedPay!!),
        )
    }

    @Test
    fun `endSession force-stamp — the undelivered drop is excluded, shares sum to the receipt (#630 R1)`() {
        // 3-drop job with a receipt visible; drops 1-2 delivered, drop 3 UNDELIVERED when the dash
        // ends. endSession force-stamps drop 3's completedAt into recentTasks (T3), but the amdt#5
        // guard keeps it from MINTING — so the denominator must exclude it too, or its share would
        // evaporate (Σ < total). The receipt splits fully across the two rows that mint.
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 12.0)),
            customerTips = emptyList(),
        ) // total $12, no tips → equal split
        val postFields = ParsedFields.PostTaskFields(totalPay = 12.0, parsedPay = receipt, sessionEarnings = 40.0)
        val d1 = dropoff("d1", "Target", "c1", completedAt = 350L)
        val d2 = dropoff("d2", "Maple", "c2", completedAt = 400L)
        val d3 = dropoff("d3", "Chili's", "c3", completedAt = null) // active, undelivered

        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = jobWithTasks(listOf(d1, d2, d3)),
            activeTask = d3,
            recentTasks = listOf(d1, d2),
            lastPostTaskFields = postFields,
            lastAnnouncedPostTaskTaskId = "d2",
        )
        // The endSession shape: session/job/task cleared, d3 force-stamped into recentTasks.
        val regionNext = regionPrev.copy(
            session = null,
            activeJob = null,
            activeTask = null,
            recentTasks = listOf(d1, d2, d3.copy(completedAt = 5000L)),
            lastPostTaskFields = null,
            lastPostTaskPayHash = null,
        )

        val prev = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 5000L))
        assertEquals("exactly the two DELIVERED drops mint (amdt#5 excludes the force-stamp)", 2, rows.size)
        assertTrue(rows.none { it.taskId == "d3" })
        val r1 = rows.single { it.taskId == "d1" }
        val r2 = rows.single { it.taskId == "d2" }
        assertEquals("denominator = 2 (not 3): \$12 / 2", 6.0, r1.dropRealizedPay!!, 0.001)
        assertEquals(6.0, r2.dropRealizedPay!!, 0.001)
        assertEquals(
            "Σ minted shares == the receipt total to the cent (finding 3 fixed)",
            cents(receipt.total),
            rows.sumOf { cents(it.dropRealizedPay!!) },
        )
    }

    // =========================================================================
    // #630 review — the isFinalShape gate must not be WEDGED by an un-mintable sibling
    // (a never-activated customer-TBD placeholder, #749; or an unassigned drop, #736).
    // On cd348221 (pre-fix) both wedge the gate shut → the delivered drop mints
    // dropRealizedPay = null AND postTaskFields = null → the whole receipt folds NONE.
    // =========================================================================

    /** A never-activated dropoff placeholder — customer-TBD, both identity hashes null (#749). */
    private fun placeholderDrop(id: String) = Task(
        taskId = id,
        jobId = "J",
        phase = TaskPhase.DROPOFF,
        customerNameHash = null,
        customerAddressHash = null,
        startedAt = 300L,
        completedAt = null,
    )

    @Test
    fun `dangling placeholder sibling does not wedge the gate — delivered drop gets the FULL receipt (#630 review, #749)`() {
        // Job: one identity-RESOLVED delivered drop + one customer-TBD dangling placeholder that never
        // activated (both hashes null). The delivered drop exits PostTask with the receipt. The
        // placeholder can never mint, so it must NOT hold the final-shape gate shut — the delivered
        // drop is the sole accountable dropoff and takes the whole receipt.
        val receipt = ParsedPay(appPayComponents = listOf(ParsedPayItem("Base Pay", 12.0)), customerTips = emptyList())
        val postFields = ParsedFields.PostTaskFields(totalPay = 12.0, parsedPay = receipt, sessionEarnings = 40.0)
        val delivered = dropoff("dDelivered", "Wendy's", "cW", completedAt = 400L)
        val placeholder = placeholderDrop("dPlaceholder")

        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 40.0),
            activeJob = jobWithTasks(listOf(delivered, placeholder)),
            activeTask = delivered,
            recentTasks = listOf(delivered),
            lastPostTaskFields = postFields,
            lastAnnouncedPostTaskTaskId = "dDelivered",
        )
        val regionNext = regionPrev.copy() // job stays open → only the PostTask-exit mint fires

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals("only the delivered drop mints", 1, rows.size)
        val r = rows.single()
        assertEquals("dDelivered", r.taskId)
        assertEquals("the delivered drop takes the WHOLE receipt (placeholder didn't wedge the gate)", 12.0, r.dropRealizedPay!!, 0.001)
        assertNotNull("the receipt IS attached (final shape restored)", r.parsedPay)
        assertNotNull(r.totalPay)
    }

    @Test
    fun `unassigned sibling does not wedge the gate — survivor gets the FULL receipt (#630 review, #736)`() {
        // 2-order job: an identity-bearing sibling was UNASSIGNED (unassignedAt set, completedAt null —
        // the reconcile re-add shape) and sits in recentTasks + job.tasks. The survivor delivers with a
        // receipt. The abandoned drop can never mint, so it must NOT hold the gate shut — the survivor
        // is the sole accountable dropoff and takes the whole receipt.
        val receipt = ParsedPay(appPayComponents = listOf(ParsedPayItem("Base Pay", 9.0)), customerTips = emptyList())
        val postFields = ParsedFields.PostTaskFields(totalPay = 9.0, parsedPay = receipt, sessionEarnings = 30.0)
        val survivor = dropoff("dSurvivor", "Chipotle", "cS", completedAt = 400L)
        val abandoned = dropoff("dAbandoned", "Taco Bell", "cA", completedAt = null).copy(unassignedAt = 500L)

        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 30.0),
            activeJob = jobWithTasks(listOf(survivor, abandoned)),
            activeTask = survivor,
            recentTasks = listOf(abandoned),
            lastPostTaskFields = postFields,
            lastAnnouncedPostTaskTaskId = "dSurvivor",
        )
        val regionNext = regionPrev.copy() // job stays open → only the PostTask-exit mint fires

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertEquals("only the survivor mints (the abandoned drop is not a target)", 1, rows.size)
        val r = rows.single()
        assertEquals("dSurvivor", r.taskId)
        assertEquals("the survivor takes the WHOLE receipt (unassigned sibling didn't wedge the gate)", 9.0, r.dropRealizedPay!!, 0.001)
        assertNotNull("the receipt IS attached (final shape restored)", r.parsedPay)
        assertNotNull(r.totalPay)
    }

    @Test
    fun `an abandoned drop as the most-recent recentTask is never a PostTask-exit mint target (#630 review Fix B, #736)`() {
        // The `recentTasks.lastOrNull { jobId }` fallback selects an UNASSIGNED drop (identity-bearing,
        // completedAt null). The #736 belt must stop it from minting a fabricated DELIVERY_COMPLETED for
        // a never-delivered order. On cd348221 (no unassigned guard on this fallback) it mints one.
        val receipt = ParsedPay(appPayComponents = listOf(ParsedPayItem("Base Pay", 7.0)), customerTips = emptyList())
        val postFields = ParsedFields.PostTaskFields(totalPay = 7.0, parsedPay = receipt, sessionEarnings = 20.0)
        val abandoned = dropoff("dAbandoned", "Taco Bell", "cA", completedAt = null).copy(unassignedAt = 500L)

        val regionPrev = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("s1", startedAt = 100L, runningEarnings = 20.0),
            activeJob = jobWithTasks(listOf(abandoned)),
            activeTask = null, // force the recentTasks fallback to resolve the completed task
            recentTasks = listOf(abandoned),
            lastPostTaskFields = postFields,
            lastAnnouncedPostTaskTaskId = "dAbandoned",
        )
        val regionNext = regionPrev.copy() // job stays open → close-out doesn't fire; isolate the exit mint

        val prev = appState(FlowRegion(flow = Flow.PostTask), mapOf(Platform.DoorDash to regionPrev))
        val next = appState(FlowRegion(flow = Flow.Idle), mapOf(Platform.DoorDash to regionNext))

        val rows = completedRows(prev, next, screenObs(Flow.Idle, timestamp = 3000L))
        assertTrue("no DELIVERY_COMPLETED for an abandoned drop (master: one fabricated row)", rows.isEmpty())
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
