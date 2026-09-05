package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.ParseShortfall
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The DoorDash **8.93.7** receipt sheet, read end to end through the production ruleset (#1029).
 *
 * That build shipped the sheet with **no money-bearing view ids at all** — the whole
 * `final_value` / `earnings_ticker` / `pay_line_item_value` family the parses anchored on is gone.
 * The rules kept MATCHING (their `require` predicates are text-anchored), so recognition logged
 * clean while every money field died. Worse, one of them died WRONG: the flattened breakdown row
 * is `'Customer tips', '799', '$7.00'`, and the old `sibling(1)` read reported a **$799.00 tip on a
 * $16.70 delivery**.
 *
 * The two fixtures below are the only fielded specimens — the 08-23 17:35 collapsed/expanded pair,
 * captured 4 s apart off one real receipt whose human-readable figures were
 * `Base $8.70 / Peak $1.00 / Tips $7.00 / $16.70`. Those numbers are the oracle here; the parse
 * golden pins the same values corpus-wide, but a golden diff is easy to wave through, and this
 * states what the receipt actually said.
 */
class DeliverySummaryReanchorTest {

    private val ruleset get() = TestRulesetFactory.screenRuleset

    /**
     * Load ONE fixture by name (the `DashSummaryReanchorTest` shape) rather than the whole folder:
     * these tests each need a single specimen, and re-reading + re-parsing 15 sibling JSON trees
     * per call is pure waste. Behaviour-neutral.
     */
    private fun matchOf(
        folder: String,
        fileMarker: String,
        onShortfall: ((ParseShortfall) -> Unit)? = null,
    ): RuleMatchResult {
        val dir = File("src/test/resources/snapshots/$folder")
        val file = dir.listFiles()?.sortedBy { it.name }?.firstOrNull { it.name.contains(fileMarker) }
        assertTrue(
            "the specimen '$fileMarker' must still be committed under $folder — the 8.93.7 pair " +
                "is the only fielded evidence of the id-less render",
            file != null,
        )
        val result = ruleset.matchFirst(
            TestResourceLoader.loadNode(file!!),
            "doordash",
            onParseShortfall = onShortfall,
        )
        assertTrue("the $folder frame must still be recognized", result != null)
        assertEquals("doordash.screen.$folder", result!!.ruleId)
        return result
    }

    /** A synthetic flat 8.93.7-shaped breakdown row, siblings in order under one container. */
    private fun row(vararg texts: String): UiNode =
        UiNode(children = texts.map { UiNode(text = it) }).restoreParents()

    private fun fieldsOf(folder: String, fileMarker: String): Map<String, Any?> =
        matchOf(folder, fileMarker).fields

    private fun shortfallOf(folder: String, fileMarker: String): ParseShortfall? {
        var reported: ParseShortfall? = null
        matchOf(folder, fileMarker) { reported = it }
        return reported
    }

    // =========================================================================
    // The expanded receipt — the full breakdown
    // =========================================================================

    @Test
    fun `the expanded 8_93_7 receipt reads the figures the dasher saw`() {
        val fields = fieldsOf("delivery_summary_expanded", "6b9dd1")

        assertEquals("the offer total, off the 'This offer' row", 16.70, fields["totalPay"])
        assertEquals("the tip, off the row's first currency-shaped sibling", 7.00, fields["customerTips"])
        assertEquals(
            "the dash running total, off the id-less digit wheel",
            16.70, fields["sessionEarnings"],
        )
    }

    @Test
    fun `the 799 tip fabrication is gone`() {
        val fields = fieldsOf("delivery_summary_expanded", "6b9dd1")
        assertNotEquals(
            "'799' is a DoorDash type CODE rendered in the tips row, not a $799 tip — the old " +
                "positional sibling(1) read it as money",
            799.0, fields["customerTips"],
        )
    }

    @Test
    fun `appPay stays null on 8_93_7 rather than claiming the base-pay line`() {
        // 'DoorDash pay' is a HEADER on this build — its value is the SUM of the 'Base pay' /
        // 'Peak pay' items beneath it — so there is nothing correct to read. A currency scan
        // here would return $8.70 and call it the whole app-pay side; null is the honest answer.
        assertNull(fieldsOf("delivery_summary_expanded", "6b9dd1")["appPay"])
    }

    @Test
    fun `a null appPay does not drop the rest of the parse`() {
        // The rule's `sumApproxEquals(appPay + customerTips ~ totalPay)` validator PASSES on a
        // null summand by design (ValidateRegistry returns Pass rather than judging an absent
        // field), which is what keeps the recovered totalPay/customerTips on the frame.
        val fields = fieldsOf("delivery_summary_expanded", "6b9dd1")
        assertNull(fields["appPay"])
        assertEquals(16.70, fields["totalPay"])
    }

    // =========================================================================
    // The collapsed receipt — same sheet, 4 s earlier, wheel still spinning
    // =========================================================================

    @Test
    fun `the collapsed 8_93_7 receipt reads its total`() {
        val fields = fieldsOf("delivery_summary_collapsed", "c65d43")
        assertEquals(16.70, fields["totalPay"])
    }

    @Test
    fun `a mid-spin dash total is left null, not guessed`() {
        // This capture caught the wheel mid-flight: its glyphs join to "$70103.030". The
        // expanded sibling 4 s later has settled at $16.70. Reporting a number here would be
        // strictly worse than reporting none.
        assertNull(fieldsOf("delivery_summary_collapsed", "c65d43")["sessionEarnings"])
    }

    // =========================================================================
    // The #1036 signal that found this
    // =========================================================================

    @Test
    fun `the parse-shortfall signal no longer names either summary rule`() {
        // #1036 is what made this rot visible: both rules kept matching while their parses died,
        // so the census named them on the committed corpus. Closing the loop here means a
        // re-break shows up as BOTH a golden diff and this signal coming back.
        assertNull(
            "the expanded 8.93.7 receipt must parse cleanly now",
            shortfallOf("delivery_summary_expanded", "6b9dd1"),
        )
        assertNull(
            "the collapsed 8.93.7 receipt must parse cleanly now — its null sessionEarnings is a " +
                "mid-spin wheel, not a dead anchor, and totalPay (the shape-required field) resolves",
            shortfallOf("delivery_summary_collapsed", "c65d43"),
        )
    }

    // =========================================================================
    // The scan cap — a missing value must read NULL, never the next row's money
    // =========================================================================

    @Test
    fun `a tips row whose VALUE is missing reads null, not the next row's figure`() {
        // The control the fielded pair cannot provide (#1029 E1): if the tips value simply is not
        // rendered, an uncapped shape scan walks out of the row and hands back Peak pay's $1.00 AS
        // the customer tip — fail-WRONG, and `sumApproxEquals` cannot catch it because `appPay` is
        // null on 8.93.7. The rule's `, 2` cap stops the scan at its own row.
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Delivery complete"),
                UiNode(text = "This offer"),
                UiNode(text = "\$16.70"),
                row("Customer tips", "799", "Peak pay", "\$1.00"),
            ),
        ).restoreParents()

        val result = ruleset.matchFirst(tree, "doordash")
        assertTrue("the synthetic must still be recognized as a summary", result != null)
        assertNull(
            "an absent tip must read null — reporting the next row's money is worse than nothing",
            result!!.fields["customerTips"],
        )
    }

    // =========================================================================
    // The pre-8.93.7 renders must be untouched
    // =========================================================================

    @Test
    fun `an id-bearing receipt still resolves through the id arm`() {
        val fields = fieldsOf("delivery_summary_expanded", "20260128_163017_537")
        assertEquals("final_value is still the first arm where it exists", 6.60, fields["totalPay"])
        assertEquals("earnings_ticker is still the first arm where it exists", 6.60, fields["sessionEarnings"])
    }
}
