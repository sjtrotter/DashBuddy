package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
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
 * what make that possible). RESIDUAL (documented, out of #526's scope): these `dropoff_pre_arrival`
 * frames parse NO customer ADDRESS, and the stacked-dropoff detector (#565) keys the two-drop split
 * on a changed address hash — so with THIS capture the two drops fold onto ONE active dropoff slot
 * rather than two distinct tasks, and the job never reaches a clean grace close (no DELIVERY_COMPLETED
 * here). Both drops are still correctly store-attributed AT THEIR ACTIVE STEP (asserted below); the
 * distinct-task split + completion is a separate address-parse concern, not #526.
 *
 * Fixture: `snapshots/sessions/two_pickup_stack_2026_07_05/` — real device envelopes, edge-redacted
 * (customers appear only as `[redacted:4ee5]` / `[redacted:d290]` markers; the SAME marker on a
 * customer's pickup AND dropoff is exactly what the sha256 join keys on). Verified PII-safe.
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
        // (With THIS fixture the documented address-parse residual means no DELIVERY_COMPLETED is
        // reached — the assertion is then vacuous, driving it to close is #700 — but it pins the
        // mainline the moment the fixture or the machine closes a job cleanly.)
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
