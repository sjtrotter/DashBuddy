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

    /**
     * What would be PERSISTED for [tree]: the winning rule's redact, or the UNKNOWN-path backstops when
     * nothing wins — so a test asserts on the envelope whichever path the frame takes.
     */
    private fun persisted(tree: UiNode): String {
        val winnerId = winner(tree)
        val masked = if (winnerId != null) {
            ruleset.ruleById(winnerId)!!.redact.apply(tree)
        } else {
            CustomerTextMarkers.scrubUnknown(CustomerTextMarkers.scrub(tree))
        }
        return serialize(masked)
    }

    private fun assertSheetSecretsMasked(what: String, out: String) {
        assertFalse("$what: name must not persist", out.contains("Avery K"))
        assertFalse("$what: street must not persist", out.contains("Sample Ridge"))
        assertFalse("$what: ZIP must not persist", out.contains("78200"))
        assertFalse("$what: note must not persist", out.contains("sample porch"))
        assertFalse("$what: code must not persist", Regex(""""text"\s*:\s*"000"""").containsMatchIn(out))
    }

    private fun sheetBody(vararg extra: UiNode) = arrayOf(
        text("Deliver to Avery K"),
        addressBlock(),
        text("Leave it at my door"),
        text("\"Leave it on the sample porch\""),
        text("000"),
        *extra,
    )

    @Test
    fun `incidental chrome or a stray id never sends the sheet back to UNKNOWN (r2 P1)`() {
        val variants = mapOf(
            "pick-up-by line" to frame(*sheetBody(text("Pick up by 12:28 PM"))),
            "stray id" to frame(*sheetBody(UiNode(viewIdResourceName = id("some_new_container")))),
            "id on the option row" to frame(*sheetBody(text("Order includes", "unrelated_label"))),
        )
        for ((what, tree) in variants) {
            assertEquals("$what: still this sheet", TASK_DETAIL, winner(tree))
            assertSheetSecretsMasked(what, persisted(tree))
        }
    }

    @Test
    fun `an id-bearing variant is claimed and every id-borne and id-less secret is masked (r1 P1, r2 P1)`() {
        for (suffix in listOf(
            "customer_name",
            "address_subpremise_line",
            "dasher_instruction_content_collapsed",
            "description_text_view",
        )) {
            val tree = frame(*sheetBody(text("Jordan T 4417", suffix)))
            assertEquals("$suffix variant is claimed", TASK_DETAIL, winner(tree))
            val out = persisted(tree)
            assertFalse("$suffix: id-borne value must not persist", out.contains("Jordan T"))
            assertSheetSecretsMasked(suffix, out)
        }
    }

    @Test
    fun `a 4-character subpremise value plain-masks exactly (r2 P5)`() {
        val masked = taskDetail.redact.apply(frame(text("4417", "address_subpremise_line")))
        val node = masked.findNode { it.viewIdResourceName == id("address_subpremise_line") }!!
        assertEquals("[redacted]", node.text)
    }

    @Test
    fun `content-id masks outrank the task-prefix hash, here and in the timeline belt (r2 P4)`() {
        val timeline = ruleset.ruleById("doordash.screen.timeline")!!
        for (rule in listOf(taskDetail, timeline)) {
            for (suffix in listOf("dasher_instruction_content_collapsed", "description_text_view", "tvLastMessage")) {
                val masked = rule.redact.apply(frame(text("Return 4417", suffix)))
                val node = masked.findNode { it.viewIdResourceName == id(suffix) }!!
                assertEquals("${rule.id} / $suffix: plain, never hashed", "[redacted]", node.text)
            }
        }
    }

    @Test
    fun `recognition and redaction share one prefix vocabulary (r2 P3)`() {
        val tabbed = frame(text("Return\tAvery K to Sample Store"), addressBlock(), text("Leave it at my door"))
        assertNotEquals("a tab-separated Return line is not this rule's task line", TASK_DETAIL, winner(tabbed))
    }

    @Test
    fun `the timeline wins a combined frame and masks the sheet's id-borne values (r2 P2)`() {
        val tree = frame(
            text("Current dash"),
            text("Current task"),
            *sheetBody(
                text("Jordan T", "customer_name"),
                text("Gate 4417", "dasher_instruction_content_collapsed"),
            ),
        )
        assertEquals("doordash.screen.timeline", winner(tree))
        val out = persisted(tree)
        assertFalse("customer_name must not persist", out.contains("Jordan T"))
        assertFalse("instruction body must not persist", out.contains("Gate 4417"))
        assertSheetSecretsMasked("combined frame", out)
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
