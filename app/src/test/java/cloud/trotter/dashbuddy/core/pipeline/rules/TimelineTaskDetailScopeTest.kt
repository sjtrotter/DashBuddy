package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1116 — the scope of `timeline_task_detail`'s second branch (the Close-sheet-only render), pinned
 * on the adversarial review's synthetic trees.
 *
 * - **P1 (ownership):** a neighbouring sheet that ALSO shows `Close sheet` + a task line + a dropoff
 *   option keeps its own winner and its own mask; an id-bearing variant of this sheet stays UNKNOWN,
 *   where the `ID_MARKERS` backstop runs, and the rule's redact still covers those ids if a branch
 *   ever wins one.
 * - **P2 (replay):** a frame the rule wins must still win after the rule's own redact (a saved
 *   capture must replay as the same intent) — including the whole-remainder-masked `Return` line.
 * - **P3 (first-match order):** a bare code or quoted note directly above a city/ST/ZIP line plain-masks
 *   on this rule AND on the `timeline` combined-frame belt; the generic venue selector is last.
 */
class TimelineTaskDetailScopeTest {

    private val ruleset = TestRulesetFactory.screenRuleset
    private val taskDetail = ruleset.ruleById(TASK_DETAIL)!!

    private fun id(suffix: String) = "com.doordash.driverapp:id/$suffix"

    private fun text(value: String, idSuffix: String? = null) =
        UiNode(text = value, viewIdResourceName = idSuffix?.let { id(it) }, className = "android.widget.TextView")

    /** The fielded shape: everything id-less below `android:id/content`. */
    private fun frame(vararg body: UiNode): UiNode = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(
                viewIdResourceName = "android:id/content",
                className = "android.widget.FrameLayout",
                children = listOf(
                    UiNode(contentDescription = "Close sheet", className = "android.view.View", isClickable = true),
                    UiNode(className = "android.view.View", children = body.toList()),
                ),
            ),
        ),
    ).restoreParents()

    private fun addressBlock() = UiNode(
        className = "android.view.View",
        children = listOf(text("1234 Sample Ridge Dr"), text("San Antonio, TX 78200")),
    )

    private fun winner(tree: UiNode) = ruleset.matchFirst(tree)?.ruleId

    private fun serialize(tree: UiNode) = UiNodeSchema.serialize(tree)

    @Test
    fun `the plain Close-sheet-only render is claimed and survives its own redact (P2)`() {
        for (taskLine in listOf("Deliver to Avery K", "Return Avery K to Sample Store")) {
            val tree = frame(text(taskLine), addressBlock(), text("Leave it at my door"), text("000"))
            assertEquals("'$taskLine' render must be claimed", TASK_DETAIL, winner(tree))
            val masked = taskDetail.redact.apply(tree).restoreParents()
            val out = serialize(masked)
            assertFalse("name must not persist", out.contains("Avery K"))
            assertFalse("street must not persist", out.contains("Sample Ridge"))
            assertFalse("ZIP must not persist", out.contains("78200"))
            assertEquals("the masked capture must replay as the same intent", TASK_DETAIL, winner(masked))
        }
    }

    @Test
    fun `every committed fixture replays as the same intent after redaction (P2)`() {
        val snapshots = TestResourceLoader.loadSnapshots("snapshots/timeline_task_detail")
        assertEquals(4, snapshots.size)
        for ((filename, node, _) in snapshots) {
            assertEquals("$filename recognizes", TASK_DETAIL, winner(node))
            val masked = taskDetail.redact.apply(node).restoreParents()
            assertEquals("$filename replays after its own redact", TASK_DETAIL, winner(masked))
        }
    }

    @Test
    fun `a neighbouring sheet with Close sheet, a task line and a dropoff option keeps its own winner (P1)`() {
        val shared = arrayOf(text("Pickup for Avery K"), text("Leave it at my door"))

        val picker = frame(
            *shared,
            text("Select an Order", "header"),
            text("For Jordan T by 12:28", "instructions"),
        )
        assertEquals("doordash.screen.pickup_order_picker", winner(picker))
        val pickerMasked = serialize(ruleset.ruleById("doordash.screen.pickup_order_picker")!!.redact.apply(picker))
        assertFalse("the picker's own mask still covers the instructions line", pickerMasked.contains("Jordan T"))

        val goingToStore = frame(
            *shared,
            text("Sample instructions", "address_instructions_view"),
            text("Order includes", "lblOrderIncludes"),
            UiNode(viewIdResourceName = id("going_to_store_map")),
        )
        assertEquals("doordash.screen.pickup_going_to_store_sheet", winner(goingToStore))

        val workflow = frame(
            *shared,
            UiNode(
                viewIdResourceName = id("drop_off_workflow_host_fragment"),
                children = listOf(text("Continue", "textView_prism_button_title"), addressBlock()),
            ),
        )
        assertEquals("doordash.screen.dropoff_workflow_sheet", winner(workflow))
    }

    @Test
    fun `an id-bearing variant stays UNKNOWN for the ID backstop, and the rule's redact covers the ids anyway (P1)`() {
        for (suffix in listOf("customer_name", "address_subpremise_line", "dasher_instruction_content_collapsed")) {
            val tree = frame(
                text("Deliver to Avery K"),
                text("Jordan T 4417", suffix),
                addressBlock(),
                text("Leave it at my door"),
            )
            assertNotEquals("$suffix variant must not be claimed by the task-detail sheet", TASK_DETAIL, winner(tree))
            if (winner(tree) == null) {
                assertFalse(
                    "$suffix: the UNKNOWN-path backstop masks the id-borne value",
                    serialize(CustomerTextMarkers.scrubUnknown(tree)).contains("Jordan T"),
                )
            }
            assertFalse(
                "$suffix: the rule's redact covers the id-borne value too",
                serialize(taskDetail.redact.apply(tree)).contains("Jordan T"),
            )
        }
        val plain = taskDetail.redact.apply(frame(text("4B", "address_subpremise_line")))
        assertTrue("a subpremise value plain-masks", serialize(plain).contains("\"[redacted]\""))
    }

    @Test
    fun `a bare code or quoted note above a city line plain-masks on the sheet and the timeline belt (P3)`() {
        val timeline = ruleset.ruleById("doordash.screen.timeline")!!
        for (rule in listOf(taskDetail, timeline)) {
            for (secret in listOf("4417", "\"4417\"")) {
                val block = UiNode(children = listOf(UiNode(text = secret), UiNode(text = "San Antonio, TX 78200")))
                    .restoreParents()
                val masked = rule.redact.apply(block)
                assertEquals("${rule.id}: '$secret' plain-masks", "[redacted]", masked.children[0].text)
                assertEquals("${rule.id}: the city line plain-masks", "[redacted]", masked.children[1].text)
            }
            // The venue selector still hashes a genuine venue line (it is ordered last, not removed).
            val venue = UiNode(children = listOf(UiNode(text = "Sample Ridge Apartments"), UiNode(text = "San Antonio, TX 78200")))
                .restoreParents()
            assertTrue(
                "${rule.id}: a venue line still hash-masks",
                Regex("""^\[redacted:[0-9a-f]{4}]$""").matches(rule.redact.apply(venue).children[0].text!!),
            )
        }
    }

    private companion object {
        const val TASK_DETAIL = "doordash.screen.timeline_task_detail"
    }
}
