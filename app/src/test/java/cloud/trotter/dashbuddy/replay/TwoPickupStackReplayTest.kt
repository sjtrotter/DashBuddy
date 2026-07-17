package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.test.util.SessionReplay
import cloud.trotter.dashbuddy.test.util.SnapshotSecurityScanner
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end Level-B replay of the real 07-05 **two-pickup stack** (Bill Miller BBQ + Mama Margies,
 * job `…-1783283831120-4`), driving accept → two pickups → dropoffs through the REAL
 * [cloud.trotter.dashbuddy.core.state.StateMachine] with the real accept click injected.
 *
 * This capture is the ground truth for the three #526 defects, and each assertion below pins one:
 *
 *  - **F3** (the accept branch never ran): a `waiting_for_offer` teardown frame (15:37:09.775) lands
 *    between the accept click (15:37:09.130) and the first `pickup_navigation` (15:37:11.233),
 *    popping `pendingOffer` before the OfferPresented→task edge — so on master the job is minted by
 *    the bare fallback with NO economics, NO placeholders, NO store hints. Assertion:
 *    `job.acceptedOffers` is non-empty (master: empty) — the D1 accept stash recovers it.
 *  - **F1** (Bug10a): the first pickup (Bill Miller) was displaced with NO PICKUP_CONFIRMED ever —
 *    only the LAST pickup was confirmed (at the first dropoff). Assertion: PICKUP_CONFIRMED fires for
 *    BOTH pickups, with distinct taskIds (master: 1). Bill Miller's fires at the pickup→pickup
 *    divergence edge (frame 06), not never.
 *  - **F2** (the "Unknown store" $0 row): the drops parse NO store and the offer's two stores gave
 *    the token-match fallback nothing to disambiguate, so on master the drop carried storeName NULL.
 *    Assertion: the drop bearing Bill Miller's customer hash carries "Bill Miller BBQ", and the drop
 *    bearing Mama Margies' hash carries "Mama Margies" — via the D6 customer-hash join.
 *
 * The drops resolve onto the accept-minted dropoff placeholders (F3's economics + placeholders are
 * what make that possible). Both drops are correctly store-attributed AT THEIR ACTIVE STEP.
 *
 * **#700 — driving to job close (`drives the two-drop stack to a clean job close`).** The per-frame
 * tests above use ONLY the captured `run()` sequence, which ends mid-dropoff. Reaching the two
 * `DELIVERY_COMPLETED` completions the on-device machine emitted (db `app_events` seq 28/29) needs
 * three device frames the capture layer **suppressed** and cannot be recovered from disk:
 *  1. the sustained navigation AWAY from Bill Miller (drove db seq 24 `DELIVERY_CONFIRMED` + seq 25
 *     `DELIVERY_NAV_STARTED` at 15:59:07) — no capture exists in the 8-minute drive gap (15:59:06 →
 *     16:07:13), the frames were identity/content deduped;
 *  2. Bill Miller's grace-committed retire (the `TASK_RETIRE` grace armed by (1));
 *  3. Mama Margies' ARRIVAL (drove db seq 26 `DELIVERY_ARRIVED` at 16:07:34) — that frame fell to
 *     `UNKNOWN` on-device ("Leave it at the door", no completion CTA the rules key on), never a
 *     recognized envelope.
 * The captured frames additionally parse **no `customerAddressHash`** anywhere, so the #565
 * address-keyed stacked-dropoff split cannot fire either — which is exactly why #700 targets the
 * `TASK_RETIRE` grace + customer-less placeholder-resolution path instead. So the close test
 * ([drivesTheTwoDropStackToACleanJobClose]) reduces the real captured frames + real accept click
 * PLUS a small, explicitly-labelled set of injections that stand in for those db-proven suppressed
 * frames: two synthetic `idle` observations (the two drive-away legs that arm each drop's retire),
 * one synthetic `dropoff_photo`-arrived observation carrying **frame 11's own real parsed Mama
 * Margies fields** (the suppressed arrival), and two `GRACE_COMMIT` timers. This is a hand-authored
 * correct-behaviour invariant (never `replay == db`): given the machine is driven the way the device
 * really was, the **strict #596/#615 arm** of `isJobPhysicallyComplete` (both placeholders resolved,
 * finished, and arrived → the final retire's T1 closes the job) plus the #596 close-out sweep mint
 * exactly two distinct `DELIVERY_COMPLETED`, both offer-minted placeholders consumed, both
 * (customer-hash, store) pairs intact — the assertion #700 restores. The #749 per-customer coverage
 * arm is deliberately NOT exercised here (it short-circuits away when the strict arm succeeds,
 * mutation-verified — severing it leaves this suite green); its own tests own that arm.
 *
 * Fixture: `snapshots/sessions/two_pickup_stack_2026_07_05/` — real device envelopes, edge-redacted
 * (customers appear only as `[redacted:4ee5]` / `[redacted:d290]` markers; the SAME marker on a
 * customer's pickup AND dropoff is exactly what the sha256 join keys on — frames 12/13 add Bill
 * Miller's "Complete delivery" screen and the Mama Margies `nav_arriving` overlay, both edge-redacted
 * with the `roadNameView` street label additionally masked). Verified PII-safe.
 */
class TwoPickupStackReplayTest {

    private val session = "snapshots/sessions/two_pickup_stack_2026_07_05"

    private fun run(): List<SessionReplay.ReplayStep> {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/02_accept_offer_click.json")
        val timer = SessionReplay.graceCommit(screens.maxOf { it.atMs } + 200_000L)
        return SessionReplay.reduceMixed(screens + click + timer)
    }

    private fun dd(step: SessionReplay.ReplayStep) = step.stateAfter.regions.platforms[Platform.DoorDash]

    // ---------------------------------------------------------------------------------------------
    // #700 — reconstruction of the db-proven suppressed device frames (see the class KDoc). Each
    // injection stands in for a real on-device frame the capture layer deduped or dropped to UNKNOWN,
    // so the CAPTURED-only `run()` sequence can be driven to the same job close the device reached.
    // ---------------------------------------------------------------------------------------------

    /** A drive leg away from the just-arrived drop — a real navigation screen the capture layer
     *  deduped. Labelled `navigation_generic`, the production rule that matches these suppressed
     *  mid-dash nav frames (its online branch is flow `idle` + modeHint `online` — a combination
     *  the classifier really emits; `idle_map` would be modeHint `offline`, impossible here). Its
     *  non-task `idle` flow is what arms the drop's `TASK_RETIRE` grace. */
    private fun driveAway(atMs: Long): SessionReplay.RawInput = SessionReplay.RawInput(
        Observation.Screen(
            timestamp = atMs, captureId = null, ruleId = "doordash.screen.navigation_generic",
            metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
            parsed = ParsedFields.IdleFields(), target = "navigation_generic",
        ),
        atMs = atMs,
    )

    /** Mama Margies' ARRIVAL — the frame that fell to UNKNOWN on-device (db seq 26). It carries
     *  frame 11's OWN real parsed Mama Margies fields (store + customer hash), re-stamped as the
     *  arrived sub-flow, so `arrivedAt` is set and the drop can count as delivered. */
    private fun mamaMargiesArrival(atMs: Long, realFields: ParsedFields.TaskFields): SessionReplay.RawInput =
        SessionReplay.RawInput(
            Observation.Screen(
                timestamp = atMs, captureId = null, ruleId = "doordash.screen.dropoff_photo",
                metadata = ReplayMetadata.EMPTY, flow = Flow.TaskDropoffArrived, modeHint = Mode.Online,
                parsed = realFields.copy(subFlow = TaskSubFlow.ARRIVED), target = "dropoff_photo",
            ),
            atMs = atMs,
        )

    /**
     * The full accept→two-pickup→two-dropoff→**close** drive: the real captured frames + the real
     * accept click, PLUS the five reconstruction injections at their real device timestamps.
     */
    private fun runToClose(): List<SessionReplay.ReplayStep> {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/02_accept_offer_click.json")
        // Mama Margies' real parsed fields, from frame 11's own production recognition.
        val f11 = screens.first { it.frame.file.startsWith("11_") }.frame
        val mmFields = SessionReplay.replayRecognition(listOf(f11)).first().parsed as ParsedFields.TaskFields
        val reconstruction = listOf(
            driveAway(1_783_285_200_000L),                    // ~16:00:00 — arms Bill Miller's retire
            SessionReplay.graceCommit(1_783_285_215_000L),    // ~16:00:15 — commits it (>10s grace)
            mamaMargiesArrival(1_783_285_660_000L, mmFields), // ~16:07:40 — Mama Margies arrives
            driveAway(1_783_285_690_000L),                    // ~16:08:10 — arms Mama Margies' retire
            SessionReplay.graceCommit(1_783_285_705_000L),    // ~16:08:25 — commits it + closes the job
        )
        return SessionReplay.reduceMixed(screens + click + reconstruction)
    }

    private fun completions(steps: List<SessionReplay.ReplayStep>): List<DeliveryPayload> =
        steps.flatMap { it.events }
            .filter { it.type == AppEventType.DELIVERY_COMPLETED }
            .mapNotNull { it.payload as? DeliveryPayload }

    @Test
    fun `F3 - the accept stash recovers the job's economics through the teardown race`() {
        val steps = run()
        val job = steps.mapNotNull { dd(it)?.activeJob }.last()
        assertTrue(
            "the job carries accepted-offer economics — the D1 stash survived the waiting_for_offer " +
                "teardown (master: bare fallback, acceptedOffers empty)",
            job.acceptedOffers.isNotEmpty(),
        )
        // Both stores were recovered as offer hints (bare fallback would carry at most one, from the
        // first pickup screen).
        assertTrue("both offer store hints recovered", job.offerStoreHint.any { it == "Bill Miller BBQ" })
    }

    @Test
    fun `F1 - both stacked pickups are confirmed, with distinct taskIds (Bug10a)`() {
        val steps = run()
        val confirmedTaskIds = steps.flatMap { it.events }
            .filter { it.type == AppEventType.PICKUP_CONFIRMED }
            .mapNotNull { (it.payload as? PickupPayload)?.taskId }
            .toSet()
        assertEquals(
            "both pickups (Bill Miller AND Mama Margies) are confirmed, distinct taskIds " +
                "(master: only the final pickup, 1)",
            2, confirmedTaskIds.size,
        )
    }

    @Test
    fun `both pickups resolve onto their offer-owned placeholders with distinct customer hashes`() {
        val steps = run()
        val pickups = steps.flatMap { s ->
            (dd(s)?.recentTasks.orEmpty() + dd(s)?.activeJob?.tasks.orEmpty() + listOfNotNull(dd(s)?.activeTask))
                .filter { it.phase == TaskPhase.PICKUP && it.storeName != null }
        }.associateBy { it.storeName }
        assertTrue("Bill Miller pickup resolved", pickups.containsKey("Bill Miller BBQ"))
        assertTrue("Mama Margies pickup resolved", pickups.containsKey("Mama Margies"))
        assertTrue(
            "the two pickups carry DISTINCT customer hashes (the join keys)",
            pickups["Bill Miller BBQ"]!!.customerNameHash != null &&
                pickups["Mama Margies"]!!.customerNameHash != null &&
                pickups["Bill Miller BBQ"]!!.customerNameHash != pickups["Mama Margies"]!!.customerNameHash,
        )
    }

    @Test
    fun `F2 - each dropoff is store-attributed via the customer-hash join to its pickup`() {
        val steps = run()
        // The two pickups' customer hashes are the join keys.
        val pickupHashByStore = steps.flatMap { s ->
            (dd(s)?.recentTasks.orEmpty() + listOfNotNull(dd(s)?.activeTask))
                .filter { it.phase == TaskPhase.PICKUP && it.storeName != null && it.customerNameHash != null }
        }.associate { it.storeName!! to it.customerNameHash!! }
        val billHash = pickupHashByStore["Bill Miller BBQ"]
        val mamaHash = pickupHashByStore["Mama Margies"]

        // Collect, per drop customer hash, the store the active DROPOFF task carried at any step.
        val dropStoreByHash = HashMap<String, String>()
        steps.forEach { s ->
            val t = dd(s)?.activeTask
            if (t?.phase == TaskPhase.DROPOFF && t.customerNameHash != null && t.storeName != null) {
                dropStoreByHash[t.customerNameHash!!] = t.storeName!!
            }
        }
        assertEquals(
            "the [redacted:4ee5] drop is joined to Bill Miller BBQ (master: null → Unknown store \$0 row)",
            "Bill Miller BBQ", dropStoreByHash[billHash],
        )
        assertEquals(
            "the [redacted:d290] drop is joined to Mama Margies",
            "Mama Margies", dropStoreByHash[mamaHash],
        )
    }

    @Test
    fun `the drops resolve onto accept-minted dropoff placeholders, not fresh mints`() {
        val steps = run()
        // The dropoff placeholder ids minted at accept (from the first step that has a job).
        val placeholderDropoffIds = steps.firstNotNullOf { dd(it)?.activeJob }
            .tasks.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertEquals("the offer minted two dropoff placeholders", 2, placeholderDropoffIds.size)

        val activeDropIds = steps.mapNotNull { dd(it)?.activeTask }
            .filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertTrue("active drops exist", activeDropIds.isNotEmpty())
        assertTrue(
            "every active dropoff carries an accept-minted placeholder id (F3 placeholders resolved, " +
                "no fresh mint)",
            placeholderDropoffIds.containsAll(activeDropIds),
        )
    }

    @Test
    fun `receipted jobs' dropRealizedPay shares sum to their receipt total (#630 mainline pin)`() {
        // The #630 no-regression invariant over the whole trace. Two honesty fixes vs the naive pin:
        //  (a) DEDUP-AWARE: SessionReplay has NO effects_fired dedup, so a taskId re-emitted (null at a
        //      non-final PostTask exit, then its share at close) appears TWICE in the raw trace. The
        //      live engine persists only the FIRST emission per taskId — mirror that here (first wins)
        //      so the summed Σ matches what actually lands in app_events.
        //  (b) OBSERVED-RECEIPT total: derive the expected total from the PostTask OBSERVATION's
        //      parsedPay (the receipt the dasher actually saw), NOT from the row payloads — a #630
        //      withheld receipt (mid-stack exit stamps parsedPay = null on the row) would otherwise
        //      silently drop the whole job from the assertion, hiding the exact shape #630 defends.
        // (With the CAPTURED-only `run()` sequence no DELIVERY_COMPLETED is reached — the assertion
        // is vacuous here — but it pins the mainline the moment a job closes cleanly. The #700 close
        // is driven by `runToClose()`; those completions are receipt-LESS shop drops (no per-drop
        // receipt on this job), so they carry no `dropRealizedPay` and are correctly out of scope for
        // this receipt-total pin.)
        val steps = run()

        // Per job, the receipt total the dasher observed on a PostTask frame (max across the trace —
        // the FINAL receipt is the largest; keyed on the active job at that step).
        val observedReceiptByJob = HashMap<String, Double>()
        steps.forEach { s ->
            val fields = (s.observation as? Observation.Screen)?.parsed as? ParsedFields.PostTaskFields
            val total = fields?.parsedPay?.total ?: return@forEach
            val jobId = dd(s)?.activeJob?.jobId ?: return@forEach
            observedReceiptByJob[jobId] = maxOf(observedReceiptByJob[jobId] ?: 0.0, total)
        }

        // DELIVERY_COMPLETED rows, deduped to the FIRST emission per taskId (live-engine persistence).
        val persistedRows = steps.flatMap { it.events }
            .filter { it.type == AppEventType.DELIVERY_COMPLETED }
            .mapNotNull { it.payload as? DeliveryPayload }
            .distinctBy { it.taskId }
        persistedRows.groupBy { it.jobId }.forEach { (jobId, rows) ->
            val receiptTotal = observedReceiptByJob[jobId] ?: return@forEach
            val summed = rows.mapNotNull { it.dropRealizedPay }.sumOf { Math.round(it * 100.0) }
            assertEquals(
                "job $jobId: Σ dropRealizedPay (first-emission-per-taskId) must equal the OBSERVED " +
                    "receipt total (cents-exact)",
                Math.round(receiptTotal * 100.0),
                summed,
            )
        }
    }

    // =============================================================================================
    // #700 — the two drops drive to a clean job close, both completions minted. The close fires via
    // the STRICT #596/#615 arm of `isJobPhysicallyComplete` (both placeholders resolved + finished +
    // arrived; the #749 coverage arm short-circuits away, deliberately unexercised here). See the
    // class KDoc for the reconstruction rationale (db-proven suppressed frames → `runToClose()`).
    // =============================================================================================

    @Test
    fun `drives the two-drop stack to a clean job close - exactly two DELIVERY_COMPLETED, distinct taskIds`() {
        val completed = completions(runToClose())
        assertEquals("both drops complete (master/captured-only: 0 — no close reached)", 2, completed.size)
        assertEquals(
            "the two completions carry DISTINCT taskIds (one per physical drop, not a doubled drop)",
            2, completed.map { it.taskId }.toSet().size,
        )
    }

    @Test
    fun `both accept-minted dropoff placeholders are consumed by the two drops`() {
        val steps = runToClose()
        // The two dropoff placeholders the accepted offer pre-created (first step that has a job).
        val placeholderDropoffIds = steps.firstNotNullOf { dd(it)?.activeJob }
            .tasks.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertEquals("the offer minted two dropoff placeholders", 2, placeholderDropoffIds.size)

        // Every distinct dropoff taskId that ever became active across the drive to close.
        val activeDropIds = steps.mapNotNull { dd(it)?.activeTask }
            .filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertEquals(
            "both offer-minted dropoff placeholders were activated (Bill Miller onto one, Mama " +
                "Margies onto the OTHER — not folded onto a single slot)",
            2, activeDropIds.size,
        )
        assertEquals(
            "both active drops are the accept-minted placeholder ids (resolved, never fresh-minted)",
            placeholderDropoffIds, activeDropIds,
        )
    }

    @Test
    fun `both customer-hash to store pairs survive into the closed job's task lineage`() {
        val completed = completions(runToClose())
        // The (customerHash, store) pair each completion carries — the join result frozen at close.
        val pairs = completed.associate { it.customerHash to it.storeName }
        // The two pickups' customer hashes are the join keys (parsed from the real pickup frames).
        val steps = runToClose()
        val pickupHashByStore = steps.flatMap { s ->
            (dd(s)?.recentTasks.orEmpty() + listOfNotNull(dd(s)?.activeTask))
                .filter { it.phase == TaskPhase.PICKUP && it.storeName != null && it.customerNameHash != null }
        }.associate { it.storeName!! to it.customerNameHash!! }
        val billHash = pickupHashByStore["Bill Miller BBQ"]
        val mamaHash = pickupHashByStore["Mama Margies"]
        assertTrue("both pickup customer hashes resolved", billHash != null && mamaHash != null)

        assertEquals(
            "the closed job completed exactly the two known customers",
            setOf(billHash, mamaHash), pairs.keys,
        )
        assertEquals("Bill Miller's customer joins to Bill Miller BBQ at close", "Bill Miller BBQ", pairs[billHash])
        assertEquals("Mama Margies' customer joins to Mama Margies at close", "Mama Margies", pairs[mamaHash])
    }

    @Test
    fun `the fixture envelopes are PII-safe - no sensitive markers, every customer is redacted`() {
        val dir = File("src/test/resources/$session")
        val files = dir.listFiles { _, name -> name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("fixture is non-empty", files.isNotEmpty())
        val customerPrefixes = listOf("Deliver to ", "Order for ", "Meet at door for ", "Message from ")
        files.forEach { file ->
            // The click envelope's payload is {node, screenTarget}; screens' payload is the node.
            val node = if (file.name.contains("click")) SessionReplay.loadClickFrame("$session/${file.name}").node
            else TestResourceLoader.loadNode(file)
            // 1. No banking/identity (sensitive) markers.
            val scan = SnapshotSecurityScanner.scan(node)
            SnapshotSecurityScanner.printReport(scan)
            assertTrue("${file.name} must carry no sensitive markers", !scan.isToxic)
            // 2. Every customer-name/address surface is edge-redacted (never raw).
            val texts = mutableListOf<String>()
            fun walk(n: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode) {
                n.text?.let { texts.add(it) }; n.children.forEach { walk(it) }
            }
            walk(node)
            texts.forEach { t ->
                customerPrefixes.forEach { p ->
                    if (t.startsWith(p)) {
                        val rest = t.removePrefix(p).trim()
                        assertTrue(
                            "${file.name}: customer text '$t' is not redacted",
                            rest.startsWith("[redacted"),
                        )
                    }
                }
            }
        }
    }
}
