package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.OfferIntent

/**
 * #1271 scenario 1 — a DoorDash full dash, composed EXPLICITLY from committed captures (no new
 * capture, no synthetic observation). No single fixture covers a whole dash, so this is a declared
 * synthetic journey; every splice is listed here with its reason, and the committed files are read,
 * never modified.
 *
 * 1. `sessions/two_pickup_stack_2026_07_05` (Bill Miller BBQ + Mama Margies, one stacked offer):
 *    the offer, both pickups, both customers' dropoff surfaces. The captured accept CLICK is not
 *    injected as the accept: the offer heads-up's Accept action is TAPPED (production
 *    `OfferActionReceiver.onReceive` → `UiInput` → `PerformRuleAction` → `UiInteractionHandler`
 *    clicking the mirrored `accept_button`), and only after that physical click is recorded does
 *    `02_accept_offer_click.json` — the click event the device then reported — go through the
 *    classifier.
 * 2. The suppressed device frames `TwoPickupStackReplayTest` reconstructs with synthetic
 *    observations are supplied here by REAL frames instead (fact-checked against #700):
 *    - `03_waiting_for_offer_teardown.json` replayed at [BILL_RETIRE_ARM_MS] — the drive-away from
 *      Bill Miller's completed drop, which arms its TASK_RETIRE grace; the engine's own GRACE_COMMIT
 *      timer commits it.
 *    - after Mama Margies' frame 11, frame 12's customer-free "Complete delivery" surface replayed
 *      at [MAMA_ARRIVAL_MS] supplies her ARRIVAL (on the device that frame fell to UNKNOWN).
 * 3. At arrival + 1 s, the 2026-08-23 collapsed → expanded receipt pair from
 *    `ReceiptRepriceReplayTest`, keeping its REAL 3 860 ms expand latency; then the harness holds
 *    through the retire grace and the session-pay settle wake.
 * 4. `sessions/dash_end_race_2026_09_08/12_dash_summary.json` closes the dash, with two SCALAR
 *    substitutions so the summary describes THIS dash instead of the 2026-09-08 one:
 *    `$50.57 → $16.70` (the reported total = this dash's receipt) and `2 out of 2 → 1 out of 1`
 *    (offers accepted). Its SESSION_END grace is then served by the engine's own timer.
 */
object DoorDashFullDashJourney {

    const val STACK = "snapshots/sessions/two_pickup_stack_2026_07_05"
    const val SUMMARY = "snapshots/sessions/dash_end_race_2026_09_08/12_dash_summary.json"
    const val COLLAPSED =
        "snapshots/delivery_summary_collapsed/" +
            "2026-08-23_17-35-17-361__doordash__accessibility.window__delivery_summary_collapsed__c65d43.json"
    const val EXPANDED =
        "snapshots/delivery_summary_expanded/" +
            "2026-08-23_17-35-21-221__doordash__accessibility.window__delivery_summary_expanded__6b9dd1.json"

    /** The offer frame's capture instant — the journey's first input. */
    const val OFFER_MS = 1_783_283_820_495L

    /** The device reported the accept click here; the heads-up tap lands just before it. */
    const val ACCEPT_CLICK_MS = 1_783_283_829_127L
    const val ACCEPT_TAP_MS = ACCEPT_CLICK_MS - 300L

    /** Bill Miller's drive-away (the reused idle frame) — arms his TASK_RETIRE grace. */
    const val BILL_RETIRE_ARM_MS = 1_783_285_200_000L

    /** Mama Margies' arrival (frame 12's customer-free completion surface, replayed). */
    const val MAMA_ARRIVAL_MS = 1_783_285_660_000L

    /** The receipt pair: arrival + 1 s, and the fielded 3 860 ms expansion latency. */
    const val RECEIPT_COLLAPSED_MS = MAMA_ARRIVAL_MS + 1_000L
    const val RECEIPT_EXPANDED_MS = RECEIPT_COLLAPSED_MS + 3_860L

    /** Hold past the expanded receipt's 2.5 s retire grace and the 3 s session-pay settle. */
    const val RECEIPT_HELD_MS = RECEIPT_EXPANDED_MS + 10_000L

    const val SUMMARY_MS = RECEIPT_HELD_MS + 10_000L

    /** Past the summary's 2.5 s SESSION_END grace (the engine's own timer serves it). */
    const val END_MS = SUMMARY_MS + 10_000L

    /** The substituted figures (see the KDoc): this dash's receipt total and its one accepted offer. */
    val SUMMARY_SUBSTITUTIONS = mapOf("\$50.57" to "\$16.70", "2 out of 2" to "1 out of 1")

    /**
     * What could only be observed DURING the run — the heads-up's life around the tap. [run] returns
     * it; the end state alone cannot show that the receiver itself cancelled the banner.
     */
    data class Checkpoints(
        /** Active notification ids just before the Accept tap. */
        val notificationsBeforeTap: Set<Int>,
        /** …and just after it (the receiver's own immediate cancel has run). */
        val notificationsAfterTap: Set<Int>,
    )

    /** Drive the whole journey through [replay] (already [E2ESessionReplay.start]ed). */
    fun run(replay: E2ESessionReplay): Checkpoints {
        val frames = SessionReplay.loadSession(STACK).associateBy { it.file.substringBefore('_') }
        fun frame(n: String) = frames.getValue(n)

        replay.screen(frame("01"))
        replay.advanceTo(ACCEPT_TAP_MS)
        val beforeTap = replay.activeNotificationIds()
        replay.tapOfferNotification(OfferIntent.ACCEPT, ACCEPT_TAP_MS)
        val afterTap = replay.activeNotificationIds()
        check(replay.accessibility.clicks.isNotEmpty()) {
            "the Accept tap did not physically click — not feeding the click capture\n${replay.trace()}"
        }
        replay.click(SessionReplay.loadClickFrame("$STACK/02_accept_offer_click.json"))
        for (n in listOf("03", "04", "05", "06", "07", "08", "09", "10", "12")) replay.screen(frame(n))
        replay.screen(frame("03"), atMs = BILL_RETIRE_ARM_MS)
        for (n in listOf("13", "11")) replay.screen(frame(n))
        replay.screen(frame("12"), atMs = MAMA_ARRIVAL_MS)
        replay.screen(SessionReplay.loadScreenFrame(COLLAPSED, RECEIPT_COLLAPSED_MS))
        replay.screen(SessionReplay.loadScreenFrame(EXPANDED, RECEIPT_EXPANDED_MS))
        replay.advanceTo(RECEIPT_HELD_MS)
        val summary = SessionReplay.loadScreenFrame(SUMMARY, SUMMARY_MS)
        replay.screen(summary, node = summary.node.substituted(SUMMARY_SUBSTITUTIONS))
        replay.advanceTo(END_MS)
        return Checkpoints(beforeTap, afterTap)
    }

    /** [this] tree with each node text EQUAL to a key replaced by its value (parents re-linked). */
    fun UiNode.substituted(map: Map<String, String>): UiNode {
        fun walk(n: UiNode): UiNode = n.copy(text = n.text?.let { map[it] ?: it }, children = n.children.map(::walk))
        return walk(this).restoreParents()
    }
}
