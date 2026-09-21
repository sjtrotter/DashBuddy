package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.MASK_HEX
import cloud.trotter.dashbuddy.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.PLAIN_MASK
import cloud.trotter.dashbuddy.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.WHOLE_MASK_HEX
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1122 / #1123 — the two 8.98.5 drop-off sheet leaks fielded 2026-09-20 (both the #1116 class:
 * a recognized rule that forgets a slot has NO backstop, and a render nothing recognizes ships whole).
 *
 *  - **#1123** — the header-bearing render of the sheet is won by `dropoff_pre_arrival` (priority 73,
 *    ahead of `dropoff_workflow_sheet` at 143), whose redact anchored the customer name ONLY on the
 *    id-bearing `user_name` node. The 8.98.5 render puts an id-LESS first-name + last-initial node in
 *    the bottom bar; one of 14 envelopes rendered it and shipped it raw while every other slot masked.
 *    Fix: the sheet's id-less name entry copied VERBATIM into `dropoff_pre_arrival` (the #992/#1031
 *    sibling doctrine — same bytes, same 4 hex).
 *  - **#1122** — the sheet's SECOND render drops the `Deliver to` header AND the `Continue` CTA, so the
 *    old `require` (Continue AND Directions) missed it and the frame fell UNKNOWN with the street,
 *    city/ST/ZIP, unit, the quoted note (a gate code) and the ALL-CAPS customer name raw — as did its
 *    twin with the Call/Message row scrolled off, and a partial render carrying only the `Apt/Suite`
 *    row. Fix: the anchor is the host fragment plus ANY stable sheet row (either prism CTA title, or
 *    the id-less `Apt/Suite` label); the redact block is unchanged.
 *
 * Every VALUE below is invented — the fielded name/address/code are not reproduced. The corpus-level
 * regex SSOT pin (byte-identical to `SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN`) lives in
 * `CaptureRedactionCorpusTest` (FIX 1c), which now lists `dropoff_pre_arrival`; this file proves the
 * masks actually FIRE on the fielded shapes, and that the widened anchor claims every fielded render
 * and nothing without a stable sheet row.
 */
class DropoffSheetRedactionParityTest {

    private val screens = TestRulesetFactory.screenRuleset
    private val preArrival = screens.ruleById("doordash.screen.dropoff_pre_arrival")!!
    private val sheet = screens.ruleById("doordash.screen.dropoff_workflow_sheet")!!

    private fun serialize(tree: UiNode): String = UiNodeSchema.serialize(tree)
    private fun tv(text: String, id: String? = null) =
        UiNode(className = "android.widget.TextView", text = text, viewIdResourceName = id)
    private fun icon(desc: String) = UiNode(className = "android.view.View", contentDescription = desc)

    /** The 8.98.5 bottom bar: Settings · <customer name, id-less> · Safety · Help. */
    private fun bottomBar(name: String) = UiNode(
        className = "android.view.View",
        children = listOf(icon("Settings"), tv(name), icon("Safety"), icon("Help")),
    )

    /**
     * The header-BEARING render (fielded 12:35, envelope 8cc2d1): `Deliver to …` + Continue + Directions
     * under the host fragment, so `dropoff_pre_arrival` wins it by priority.
     */
    private fun headerRender(name: String, street: String) = UiNode(
        className = "android.widget.FrameLayout",
        viewIdResourceName = "com.doordash.driverapp:id/drop_off_workflow_host_fragment",
        children = listOf(
            UiNode(
                className = "android.view.View",
                children = listOf(
                    tv("Deliver to $name"),
                    tv("by 12:46 PM"),
                    tv("Call"),
                    tv("Message"),
                    tv(street),
                    tv("Sampleville, TX 75001"),
                    tv("Apt/Suite"),
                    tv("4B"),
                    tv("Leave it at the door"),
                    tv("Continue", id = "com.doordash.driverapp:id/textView_prism_button_title"),
                    tv("Directions", id = "com.doordash.driverapp:id/textView_prism_button_title"),
                ),
            ),
            bottomBar(name),
        ),
    ).restoreParents()

    /**
     * The header-LESS render (fielded 15:09, envelopes 510649/9775b7): no `Deliver to`, no Continue —
     * Call/Message, the address block, the quoted note, and Directions alone.
     */
    private fun headerlessRender(name: String, street: String, note: String) = UiNode(
        className = "android.widget.FrameLayout",
        viewIdResourceName = "com.doordash.driverapp:id/drop_off_workflow_host_fragment",
        children = listOf(
            UiNode(
                className = "android.view.View",
                children = listOf(
                    tv("Call"),
                    tv("Message"),
                    tv(street),
                    tv("Sampleville, TX 75001"),
                    tv("Apt/Suite"),
                    tv("4B"),
                    tv("Leave it at the door"),
                    tv("\"$note\""),
                    tv("Directions", id = "com.doordash.driverapp:id/textView_prism_button_title"),
                ),
            ),
            bottomBar(name),
        ),
    ).restoreParents()

    private fun intentOf(node: UiNode): String =
        screens.matchFirst(node, platformWire = "doordash")?.intent ?: "UNKNOWN"

    // ------------------------------------------------------------------ #1123

    @Test
    fun `dropoff_pre_arrival wins the header-bearing render and masks the id-less bottom-bar name (#1123)`() {
        val tree = headerRender(name = "Jane Q", street = "123 Sample St")
        assertEquals("the header-bearing render is dropoff_pre_arrival's by priority", "dropoff_pre_arrival", intentOf(tree))

        val masked = preArrival.redact.apply(tree)
        val json = serialize(masked)
        assertFalse("the customer name must not persist anywhere on the envelope", json.contains("Jane"))
        assertFalse("the street must not persist", json.contains("Sample St"))
        assertTrue("Deliver to lead-in kept, name masked", CaptureRedactionCorpusTest.maskAfter("Deliver to ").containsMatchIn(json))

        val bar = masked.children[1].children[1].text!!
        assertTrue("the bottom-bar name node masks whole to [redacted:<4hex>]; got '$bar'", WHOLE_MASK_HEX.matches(bar))
        // Over-match guards: the app chrome around it stays raw.
        for (chrome in listOf("Call", "Message", "Leave it at the door", "Continue", "Directions", "by 12:46 PM", "Settings", "Safety", "Help")) {
            assertTrue("'$chrome' is app chrome and stays raw", json.contains(chrome))
        }
    }

    @Test
    fun `the bottom-bar name masks to the SAME 4hex on both sheet rules, ALL-CAPS included (#1123 parity)`() {
        // Mask parity with the sibling the entry was copied from (#623/#733): the same customer must
        // redact to the same token whichever rule wins the frame — the header-bearing render goes to
        // dropoff_pre_arrival, the header-less one to dropoff_workflow_sheet.
        fun hexOn(rule: CompiledRule<UiNode>, name: String): String {
            val node = bottomBar(name).restoreParents()
            val value = rule.redact.apply(node).children[1].text!!
            return WHOLE_MASK_HEX.find(value)?.groupValues?.get(1)
                ?: error("${rule.id}: bottom-bar name '$name' was not masked; got '$value'")
        }
        for (name in listOf("Jane Q", "JANE Q", "Jane  Q.", "Mary-Ann O'Neil P")) {
            assertEquals("'$name' must mask to one token on both rules", hexOn(sheet, name), hexOn(preArrival, name))
        }
        // `normalize: customerName` — the ALL-CAPS and mixed-case forms of one customer share a token
        // (the desk analysis of the 09-20 leak assumed the shape missed ALL-CAPS; it does not).
        assertEquals("case is canonicalized away", hexOn(preArrival, "Jane Q"), hexOn(preArrival, "JANE Q"))
        assertFalse("distinct customers get distinct tokens", hexOn(preArrival, "Jane Q") == hexOn(preArrival, "Kate R"))
    }

    // ------------------------------------------------------------------ #1122

    @Test
    fun `the header-less sheet render is claimed by dropoff_workflow_sheet and masks every PII slot (#1122)`() {
        val tree = headerlessRender(name = "JANE Q", street = "123 Sample St", note = "Gate code is 4417, side door")
        assertEquals("the header-less render must be recognized", "dropoff_workflow_sheet", intentOf(tree))

        val masked = sheet.redact.apply(tree)
        val json = serialize(masked)
        val body = masked.children[0].children
        assertFalse("name", json.contains("JANE"))
        assertFalse("street", json.contains("Sample St"))
        assertFalse("city/ST/ZIP", json.contains("75001"))
        assertFalse("note", json.contains("4417"))
        assertFalse("unit", json.contains("\"4B\""))

        assertTrue("street line hash-masks (a join key); got '${body[2].text}'", WHOLE_MASK_HEX.matches(body[2].text!!))
        assertEquals("city/ST/ZIP plain-masks (#889 F1)", "[redacted]", body[3].text)
        assertEquals("the split unit value plain-masks (#1039)", "[redacted]", body[5].text)
        assertEquals("the quoted note plain-masks whole (#920)", "[redacted]", body[7].text)
        assertTrue("the bottom-bar ALL-CAPS name masks with the hash family", WHOLE_MASK_HEX.matches(masked.children[1].children[1].text!!))
        for (chrome in listOf("Call", "Message", "Leave it at the door", "Directions", "Settings", "Safety", "Help")) {
            assertTrue("'$chrome' is app chrome and stays raw", json.contains(chrome))
        }
        assertTrue("sanity — the mask regexes fire on this envelope", MASK_HEX.containsMatchIn(json) && PLAIN_MASK.containsMatchIn(json))
    }

    @Test
    fun `the widened anchor claims any stable sheet row under the host fragment, and nothing without one (#1122)`() {
        fun render(vararg rows: UiNode) = UiNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.doordash.driverapp:id/drop_off_workflow_host_fragment",
            children = listOf(UiNode(className = "android.view.View", children = rows.toList())),
        ).restoreParents()
        val directions = tv("Directions", id = "com.doordash.driverapp:id/textView_prism_button_title")
        val cont = tv("Continue", id = "com.doordash.driverapp:id/textView_prism_button_title")
        // Chrome-only rows under the host with NO stable sheet row: not claimed.
        assertEquals("UNKNOWN", intentOf(render(tv("Call"), tv("Message"), tv("Leave it at the door"))))
        // The label must be the id-LESS split-row label, not an id-bearing chrome node.
        assertEquals("UNKNOWN", intentOf(render(tv("Apt/Suite", id = "com.doordash.driverapp:id/some_label"), tv("4B"))))
        // Each fielded render shape is claimed: 15:09:33.393 (Directions), 15:09:33.614 (Directions, Call/Message
        // scrolled off), 15:27:58 (the Apt/Suite row alone), and the 08-30 shape (Continue + Directions).
        assertEquals("dropoff_workflow_sheet", intentOf(render(tv("Call"), tv("Message"), tv("Apt/Suite"), tv("4B"), directions)))
        assertEquals("dropoff_workflow_sheet", intentOf(render(tv("Apt/Suite"), tv("4B"), tv("Leave it at the door"), directions)))
        assertEquals("dropoff_workflow_sheet", intentOf(render(tv("Apt/Suite"), tv("4B"))))
        assertEquals("dropoff_workflow_sheet", intentOf(render(cont, directions)))
        // The rejects still hand the id-bearing card and the Timeline sheet to their own rules.
        assertEquals("UNKNOWN", intentOf(render(tv("Copy address"), tv("Apt/Suite"), tv("4B"), directions)))
    }

    @Test
    fun `the committed 09-20 fixtures classify as their folders say`() {
        val cases = listOf(
            "dropoff_pre_arrival/2026-09-20_12-35-05-218__doordash__accessibility.window__dropoff_pre_arrival__8cc2d1.json" to "dropoff_pre_arrival",
            "dropoff_workflow_sheet/2026-09-20_15-09-33-393__doordash__accessibility.window__dropoff_workflow_sheet__510649.json" to "dropoff_workflow_sheet",
            "dropoff_workflow_sheet/2026-09-20_15-09-33-614__doordash__accessibility.window__dropoff_workflow_sheet__9775b7.json" to "dropoff_workflow_sheet",
            "dropoff_workflow_sheet/2026-09-20_15-27-58-025__doordash__accessibility.window__dropoff_workflow_sheet__97d592.json" to "dropoff_workflow_sheet",
        )
        for ((path, expected) in cases) {
            val node = TestResourceLoader.loadNode(File("src/test/resources/snapshots/$path"))
            assertEquals(path, expected, intentOf(node))
        }
    }
}
