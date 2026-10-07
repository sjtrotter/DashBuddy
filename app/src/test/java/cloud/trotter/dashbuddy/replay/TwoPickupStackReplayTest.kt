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
 * #526: real Bill Miller BBQ + Mama Margies stack with the captured accept click. The captured
 * trace checks F3 (accept stash survives waiting_for_offer teardown with economics/placeholders),
 * F1 (both pickups confirm with distinct ids), and F2 (customer hashes join each drop to its store).
 *
 * #700: the captured sequence ends mid-dropoff. runToClose reconstructs suppressed device frames:
 * Bill Miller's drive-away and grace retire, Mama Margies' arrival, then her drive-away and retire.
 * Navigation frames were deduped; Mama Margies' arrival fell to UNKNOWN. The arrival injection uses
 * frame 11's real parsed fields; drive-away uses the production navigation_generic idle/online shape.
 * The timestamps reflect the device timeline, but assertions describe correct behavior, never db
 * equality. No captured frame has customerAddressHash, so the address-keyed split cannot fire.
 * Both placeholders must activate and complete with distinct ids and intact customer/store pairs.
 * This exercises the strict #596/#615 physical-completion arm; #749's per-customer coverage arm
 * short-circuits here and is tested elsewhere.
 *
 * Fixture: two_pickup_stack_2026_07_05, edge-redacted. Frames 12/13 supply Bill Miller's completion
 * screen and Mama Margies' nav_arriving overlay, with roadNameView masked. PII is checked separately.
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

    /** Suppressed drive-away navigation arms the arrived drop's TASK_RETIRE grace. */
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
    fun `captured stack retains economics and pickup identities and attributes placeholder drops`() {
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

        val confirmedTaskIds = steps.flatMap { it.events }
            .filter { it.type == AppEventType.PICKUP_CONFIRMED }
            .mapNotNull { (it.payload as? PickupPayload)?.taskId }
            .toSet()
        assertEquals(
            "both pickups (Bill Miller AND Mama Margies) are confirmed, distinct taskIds " +
                "(master: only the final pickup, 1)",
            2, confirmedTaskIds.size,
        )

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
    fun `reconstructed close completes both placeholder drops with customer and store lineage`() {
        val steps = runToClose()
        val completed = completions(steps)
        assertEquals("both drops complete (master/captured-only: 0 — no close reached)", 2, completed.size)
        assertEquals(
            "the two completions carry DISTINCT taskIds (one per physical drop, not a doubled drop)",
            2, completed.map { it.taskId }.toSet().size,
        )

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

        // The (customerHash, store) pair each completion carries — the join result frozen at close.
        val pairs = completed.associate { it.customerHash to it.storeName }
        // The two pickups' customer hashes are the join keys (parsed from the real pickup frames).
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
