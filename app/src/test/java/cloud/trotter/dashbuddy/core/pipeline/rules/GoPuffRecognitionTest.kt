package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * #501 — the GoPuff (DoorDash Drive) warehouse batch-pickup screens, recognized as BRANCHES of the
 * existing pickup rules, run against the real (redacted) 2026-06-14 capture frames.
 *
 * The load-bearing assertion: the **bin-scan steps** screen mints the batch's single
 * `PICKUP_ARRIVED`. The whole warehouse leg was otherwise all-UNKNOWN, so no arrival ever fired and
 * the state machine jumped nav→confirmed (the #501 root). The wait-survey and barcode-failure
 * screens are recognize-only — they keep the bin-scan stretch out of UNKNOWN without perturbing the
 * pickup phase.
 */
class GoPuffRecognitionTest {

    private val rules = TestRulesetFactory.screenRuleset

    private fun load(path: String): UiNode =
        TestResourceLoader.loadNode(File("src/test/resources/snapshots/$path"))

    @Test
    fun `the GoPuff bin-scan steps screen is the pickup-arrival anchor (#501)`() {
        val r = rules.matchFirst(load("pickup_steps/gopuff_bin_scan_steps.json"))
        assertEquals("recognized as pickup_steps (the GoPuff branch)", "pickup_steps", r?.intent)
        assertEquals(
            "the bin-scan steps screen declares task:pickup:arrived — the batch's one PICKUP_ARRIVED",
            Flow.TaskPickupArrived,
            r?.flow,
        )
    }

    @Test
    fun `the GoPuff at-store wait survey recognizes without changing phase (#501)`() {
        val r = rules.matchFirst(load("pickup_wait_survey/gopuff_wait_survey.json"))
        assertEquals("pickup_wait_survey", r?.intent)
        assertNull("the at-store wait survey is recognize-only — it must not perturb the pickup phase", r?.flow)
    }

    @Test
    fun `the GoPuff barcode-scan failure recognizes without changing phase (#501)`() {
        val r = rules.matchFirst(load("pickup_barcode_scan_issue/gopuff_barcode_scan_issue.json"))
        assertEquals("pickup_barcode_scan_issue", r?.intent)
        assertNull("a mid-pickup sub-screen must not change phase", r?.flow)
    }
}
