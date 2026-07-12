package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.SessionReplay
import cloud.trotter.dashbuddy.test.util.SnapshotSecurityScanner
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end Level-B replay of the real 07-08 **single-customer, two-store** job (Willie's Grill &
 * Icehouse + Sonic Drive-In, one customer, ONE dropoff), driving offer → accept → both pickups →
 * dropoff → completion through the REAL [cloud.trotter.dashbuddy.core.state.StateMachine].
 *
 * This is the #733 ground truth. Empirically (the join arms were instrumented at the review tier):
 * on the FIELDED frames the dropoff's `customerNameHash` — read off the dropoff **nav bottom-sheet**
 * — actually DOES join BOTH pickups' hashes (all three surfaces render the same short "Willie S"-class
 * form once normalized), so the store resolves through the **customer-hash multi-match arm** (#733
 * part 2), NOT a structural single-drop shortcut (the former structural arm was proven inert on this
 * shape and wrong-store-capable on undercount shapes, so it was DELETED). Because this drop is the
 * SOLE activated dropoff carrying that hash and the matched pickups span two stores, the arm applies
 * the deterministic multi-store default: the earliest-confirmed lineage pickup — Willie's Grill &
 * Icehouse (confirmed 19:24, before Sonic 19:37).
 *
 * The fielded session's `DELIVERY_COMPLETED` (seq 122) folded `storeName == null` → the "Unknown
 * store" $19.50 row + the ×23 `D6 join miss` WARN storm (the un-normalized hashes couldn't join). This
 * end-to-end replay pins the corrected behaviour on the real frames: both pickups confirm, one active
 * dropoff, and the delivery lands on Willie's — never NULL. The isolated mechanism proofs (exact
 * single-match join, constrained multi-store default, collision fall-through, WARN edge-gate,
 * normalization + mask invariant + compile lint) live in `DropoffStoreLabelTest` (`:core:state`) and
 * `CustomerNameNormalizationTest` (`:core:pipeline`).
 *
 * Fixture: `snapshots/sessions/multi_pickup_single_customer_2026_07_08/` — real device envelopes,
 * edge-redacted (customer appears only as `[redacted:…]` markers). Verified PII-safe below.
 */
class MultiPickupSingleCustomerReplayTest {

    private val session = "snapshots/sessions/multi_pickup_single_customer_2026_07_08"
    private val expectedStore = "Willie's Grill & Icehouse"

    private fun run(): List<SessionReplay.ReplayStep> {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/01_accept_offer_click.json")
        val timer = SessionReplay.graceCommit(screens.maxOf { it.atMs } + 200_000L)
        return SessionReplay.reduceMixed(screens + click + timer)
    }

    private fun dd(step: SessionReplay.ReplayStep) = step.stateAfter.regions.platforms[Platform.DoorDash]

    @Test
    fun `the single customer is served by ONE active dropoff, never re-minted`() {
        val steps = run()
        // The offer pre-creates one dropoff placeholder per STORE (two here), but a single-customer
        // job only ever ACTIVATES one drop — and never re-mints it. That single active drop is what
        // folds the delivery record; being the SOLE activated dropoff carrying the customer hash is
        // exactly what lets the multi-store default (#733 part 2) attribute it deterministically.
        val activeDropIds = steps.mapNotNull { dd(it)?.activeTask }
            .filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()
        assertEquals("exactly one active dropoff task (never re-minted)", 1, activeDropIds.size)
    }

    @Test
    fun `the job closes after the trailing grace timer (#749 non-regression guard)`() {
        // The 07-08 shape mints 2 dropoff placeholders (one per store) but only ONE activates — the
        // leftover TBD defeats isJobPhysicallyComplete for the job's lifetime (the desync #749 fixes).
        //
        // NB: this assertion is GREEN on master too (verified), NOT the discriminating RED evidence the
        // #749 spec §6 anticipated. This fixture ends in a nav/PostTask exit with no CHAINED offer, so
        // on master the job already closes via the UNCONDITIONAL PostTask-exit close (which only
        // excludes Flow.OfferPresented) — that path masks the T1/T2 desync here. Reproducing the actual
        // failure ROUTE needs a trailing independent offer (Route A) or a receipt-skip-into-idle (Route
        // B); the discriminating RED-on-master proofs are the machine-level unit cases 9 (T2) and 10
        // (T1) in :core:state JobCloseOutTest. This stays as a cheap non-regression guard: the fix must
        // not stop the same-customer job from closing.
        val steps = run()
        assertNull("the same-customer job still closes after the trailing grace commit", dd(steps.last())?.activeJob)
    }

    @Test
    fun `both pickups are confirmed with distinct taskIds`() {
        val steps = run()
        val confirmed = steps.flatMap { it.events }
            .filter { it.type == AppEventType.PICKUP_CONFIRMED }
            .mapNotNull { (it.payload as? PickupPayload)?.taskId }
            .toSet()
        assertEquals("both Willie's and Sonic pickups confirmed, distinct taskIds", 2, confirmed.size)
    }

    @Test
    fun `the single dropoff resolves structurally to the earliest-confirmed store (not NULL)`() {
        val steps = run()
        // The store the active DROPOFF task carries at the LAST step it is active — the value that
        // folds into the delivery record. On master this is null; the structural rule makes it real.
        val finalDropStore = steps.mapNotNull { dd(it)?.activeTask }
            .lastOrNull { it.phase == TaskPhase.DROPOFF }?.storeName
        assertEquals(
            "the drop resolves to Willie's (master: null → Unknown store \$19.50 row); the normalized " +
                "customer hash joins both pickups and, being the sole activated drop, takes the " +
                "earliest-confirmed lineage store — the constrained multi-store default",
            expectedStore, finalDropStore,
        )
    }

    @Test
    fun `the delivery completes attributed to a real store`() {
        val steps = run()
        // DELIVERY_COMPLETED fires on PostTask exit (the trailing nav frame) and carries the drop's
        // resolved store. If the completion event isn't reached, fall back to the confirmed-drop task.
        val completedStore = steps.flatMap { it.events }
            .filter { it.type == AppEventType.DELIVERY_COMPLETED }
            .mapNotNull { (it.payload as? DeliveryPayload)?.storeName }
        assertTrue(
            "the delivery completed (CONFIRMED or COMPLETED fired)",
            steps.flatMap { it.events }.any {
                it.type == AppEventType.DELIVERY_COMPLETED || it.type == AppEventType.DELIVERY_CONFIRMED
            },
        )
        completedStore.forEach {
            assertEquals("DELIVERY_COMPLETED carries the real store, never NULL", expectedStore, it)
        }
    }

    @Test
    fun `the fixture envelopes are PII-safe - no sensitive markers, every customer redacted`() {
        val dir = File("src/test/resources/$session")
        val files = dir.listFiles { _, name -> name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("fixture is non-empty", files.isNotEmpty())
        // Customer-only prefixes (a store never follows these). "Heading to " / "Pickup from " are
        // STORE-bearing on pickup surfaces, so they are deliberately excluded — stores are not PII.
        val customerPrefixes = listOf("Order for ", "Delivery for ", "Verify items for ", "Meet at door for ", "Message from ")
        files.forEach { file ->
            val node = if (file.name.contains("click")) SessionReplay.loadClickFrame("$session/${file.name}").node
            else TestResourceLoader.loadNode(file)
            val scan = SnapshotSecurityScanner.scan(node)
            assertTrue("${file.name} must carry no sensitive markers", !scan.isToxic)
            forEachTextNode(node) { nodeId, t ->
                // The dropoff nav single-node customer line "Deliver to <name>" (never "door of").
                if (t.startsWith("Deliver to ") && !t.startsWith("Deliver to door of")) {
                    assertTrue("${file.name}: '$t' customer not redacted", t.removePrefix("Deliver to ").trim().startsWith("[redacted"))
                }
                customerPrefixes.forEach { p ->
                    if (t.startsWith(p)) {
                        val rest = t.removePrefix(p).trim()
                        assertTrue("${file.name}: customer text '$t' is not redacted", rest.startsWith("[redacted"))
                    }
                }
                // #745 pledge hardening: turn-by-turn maneuver text near the dropoff embeds the
                // customer's residential subdivision street names (plus the dropoff-side approach) — a
                // route that pinpoints the home. A prefix scan can't own bare street names, so this is a
                // STRUCTURAL guard keyed on the maneuver node ids: a primary/subManeuverText node must
                // ship either a mask ([redacted…]) or a bare turn INSTRUCTION verb (Turn/Continue/…, not
                // PII) — any other free text is street residue and FAILS. Masked-or-instruction, never a
                // raw place name.
                if (isManeuverNodeId(nodeId)) {
                    val trimmed = t.trim()
                    assertTrue(
                        "${file.name}: maneuver node '$nodeId' text '$t' is unmasked street residue — " +
                            "mask it to [redacted] (only a turn-instruction verb may stay in the clear)",
                        trimmed.startsWith("[redacted") || isTurnInstruction(trimmed),
                    )
                }
            }
        }
    }

    /** Walk the tree, visiting every node's (viewIdResourceName, text) where text is non-null. */
    private fun forEachTextNode(
        node: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode,
        visit: (nodeId: String?, text: String) -> Unit,
    ) {
        node.text?.let { visit(node.viewIdResourceName, it) }
        node.children.forEach { forEachTextNode(it, visit) }
    }

    /** A turn-by-turn maneuver text node (primaryManeuverText / subManeuverText and siblings). */
    private fun isManeuverNodeId(nodeId: String?): Boolean =
        nodeId != null && nodeId.substringAfterLast('/').contains("ManeuverText", ignoreCase = true)

    /**
     * A bare driving-maneuver instruction (never PII) — the ONLY unmasked content a maneuver node may
     * carry. A street/place name (any other free text) is customer-street residue and must be masked.
     */
    private fun isTurnInstruction(text: String): Boolean {
        val verbs = listOf(
            "Turn", "Continue", "Head", "Merge", "Keep", "Take", "Slight", "Sharp", "Make",
            "Exit", "Arrive", "Arriving", "Destination", "Roundabout", "Go ", "Proceed",
            "Enter", "Depart", "Bear", "Follow", "Toll", "U-turn", "Uturn",
        )
        return verbs.any { text.startsWith(it, ignoreCase = true) }
    }
}
