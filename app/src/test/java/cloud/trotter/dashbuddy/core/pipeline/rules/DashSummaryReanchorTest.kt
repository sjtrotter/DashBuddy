package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Pins the #1032 re-anchor of `doordash.screen.dash_summary` — the dash-end earnings sheet,
 * which is the SOLE source of `session_records.reportedEarnings`.
 *
 * DoorDash **8.93.7** re-rendered the sheet with every text node id-LESS under
 * `bottomsheet_content_container`: `textView_prism_button_title` (the require's Done anchor),
 * `header_pay`, and the `name`/`value` stat pairs all evaporated, so the surface fell to
 * UNKNOWN — recognized once in the 15 days to 2026-08-24, and with `FrameGate`'s rolling
 * suppression of repeat UNKNOWN frames there was not even an envelope to diagnose it with.
 *
 * Six properties, none of which the golden alone states in one place:
 *
 * 1. **The 8.93.7 layout recognizes and parses**, off the rendered labels rather than ids.
 * 2. **The id-anchored layout still parses byte-identically** — the `siblingOf(<label>)` reads
 *    resolve the same nodes the retired `hasIdSuffix: name` arms did.
 * 3. **A parse MISS yields null, never a fabricated 0.** The rule used to declare
 *    `fallback: 0` on the duration and both offer counts, so a sheet whose stat rows did not
 *    render still produced a "0-minute dash, 0 offers" — exactly the #936/#1030 shape the
 *    money side reads as a measurement. `SessionEndedFields` is nullable for this reason.
 * 4. **The stat-label conjuncts in the text-only require arm are load-bearing** — an id-less
 *    sheet carrying only 'Dash summary' + 'Done' is not this surface. Delete either label
 *    conjunct and this goes red.
 * 5. **`totalEarnings` is label-ANCHORED, never positional.** The arm this replaced read the
 *    first `^\$…$` node in document order, which on a partial render resolves the WEEKLY GOAL
 *    figure as the dash total — and `RecordFolds.reportedEarningsOf` keeps any summary-screen
 *    value unconditionally, so that is fail-WRONG on the one input the money side trusts. The
 *    label anchor fails NULL instead: the goal figure is preceded in DFS text order by its own
 *    'Weekly goal' label, so a total-less render yields that label, which `parseCurrency`
 *    rejects.
 * 6. **The mid-dash sheet is NOT this surface.** 8.93.7 also ships a "This dash so far" sheet
 *    carrying the SAME two stat labels; it must stay UNKNOWN, because a `session:ended` flow
 *    claimed mid-dash would end a live dash. Guarded by the rule-level `reject` (asserted here
 *    against a synthetic that is otherwise a valid summary) and by the committed negative
 *    corpus —
 *    [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.NegativeCorpusStaysUnknownTest].
 *
 * **Corpus note (#929/#1032):** `snapshots/dash_summary/` holds **16** distinct content
 * variants, one over `SnapshotLibrarian.RETENTION_LIMIT` (15). The pruner never evicts an
 * incumbent to admit a newcomer, so the Inbox path will silently DROP the next dash_summary
 * capture it sorts in — the 8.93.7 specimen below was placed by hand as a documented cap
 * override (the PR #1038 precedent), and a second 8.93.7 variant would have to be too.
 */
class DashSummaryReanchorTest {

    private fun snapshot(folder: String, filename: String): UiNode {
        val file = File("src/test/resources/$folder/$filename")
        if (!file.isFile) {
            error("fixture $folder/$filename is missing — this test's subject moved or was pruned")
        }
        return TestResourceLoader.loadNode(file)
    }

    private fun classify(node: UiNode) =
        TestRulesetFactory.screenRuleset.matchFirst(node, platformWire = PLATFORM)

    // `org.junit.Assert.fail` returns void, so it cannot stand on the right of an elvis in
    // Kotlin; throwing the same AssertionError it would is the honest equivalent and keeps
    // the null out of the assertions below (no `assertNotNull` + `!!` pair).
    private fun parse(node: UiNode): Map<String, Any?> {
        val result = classify(node)
            ?: throw AssertionError(
                "frame classified UNKNOWN — the dash_summary re-anchor regressed",
            )
        assertEquals(RULE_ID, result.ruleId)
        return result.fields
    }

    @Test
    fun `the 8_93_7 id-less sheet recognizes and parses off its labels`() {
        val fields = parse(snapshot(DASH_SUMMARY, ID_LESS_8_93_7))
        assertEquals(48.34, fields["totalEarnings"] as Double, 0.001)
        // "1 hr 58 min" — read as the sibling of the 'Total online time' label, not off an id.
        assertEquals(7_080_000L, fields["sessionDurationMillis"])
        assertEquals(2, fields["offersAccepted"])
        assertEquals(2, fields["offersTotal"])
    }

    @Test
    fun `the id-anchored layout still parses byte-identically`() {
        val fields = parse(snapshot(DASH_SUMMARY, ID_ANCHORED))
        assertEquals(35.47, fields["totalEarnings"] as Double, 0.001)
        assertEquals(6_660_000L, fields["sessionDurationMillis"])
        assertEquals(2, fields["offersAccepted"])
        assertEquals(2, fields["offersTotal"])
    }

    @Test
    fun `a stat row that does not render parses null, never a fabricated zero`() {
        // This capture carries the sheet's headline total but no 'Total online time' /
        // 'Offers accepted' rows at all. Pre-#1032 it reported a 0 ms dash and 0 of 0 offers.
        val fields = parse(snapshot(DASH_SUMMARY, NO_STAT_ROWS))
        assertEquals(15.44, fields["totalEarnings"] as Double, 0.001)
        assertNull(fields["sessionDurationMillis"])
        assertNull(fields["offersAccepted"])
        assertNull(fields["offersTotal"])
    }

    @Test
    fun `the mid-dash 'This dash so far' sheet is not a dash summary`() {
        val result = classify(snapshot(NEGATIVE, MID_DASH_SHEET))
        assertNull(
            "the mid-dash sheet classified '${result?.intent}' — it shares the summary's stat " +
                "labels but carries neither 'Dash summary' nor 'Done', and a session:ended flow " +
                "claimed mid-dash would end a live dash",
            result,
        )
    }

    // ------------------------------------------------------------------
    //  Synthetic guards. These state properties the corpus cannot: every
    //  committed fixture is a WHOLE sheet, so no fixture isolates a single
    //  conjunct or a single parse arm.
    // ------------------------------------------------------------------

    @Test
    fun `an id-less sheet without the stat labels is not a dash summary`() {
        // 'Dash summary' + 'Done' under the sheet container, and nothing else. If the two
        // label conjuncts in the text-only require arm are ever dropped as "redundant",
        // this frame starts claiming a session:ended flow.
        val tree = sheet(
            row(text("Dash summary"), text("$48.34")),
            row(text("Done")),
        )
        assertNotEquals(
            "an id-less sheet carrying only 'Dash summary' + 'Done' claimed the dash summary — " +
                "the 'Total online time' / 'Offers accepted' require conjuncts are load-bearing",
            RULE_ID,
            classify(tree)?.ruleId,
        )
    }

    @Test
    fun `an otherwise-valid sheet carrying 'Continue dashing' is rejected`() {
        // Same tree, twice: the control recognizes, the variant carrying the mid-dash sheet's
        // own CTA does not. That pair is what proves the rule-level `reject` is doing the work
        // rather than some incidental difference in the negative-corpus capture.
        assertEquals(RULE_ID, classify(idLessSheet())?.ruleId)
        val withCta = sheet(
            row(text("Dash summary"), text("$48.34")),
            row(text("Total online time"), text("1 hr 58 min")),
            row(text("Offers accepted"), text("2 out of 2")),
            row(text("Continue dashing")),
            row(text("Done")),
        )
        val result = classify(withCta)
        assertNull(
            "a frame carrying 'Continue dashing' classified '${result?.intent}' — " +
                "the mid-dash sheet must never claim session:ended",
            result,
        )
    }

    @Test
    fun `totalEarnings is null, not the weekly-goal figure, when the header carries no money`() {
        // The executable form of the finding that retired the positional `^\$…$` arm: the
        // first currency node in document order here is the WEEKLY GOAL, not the dash total.
        val tree = sheet(
            row(text("Dash summary"), text("Thu, Aug 14")),
            row(text("Weekly goal"), text("$21.45"), text("/$380")),
            row(text("Total online time"), text("1 hr 58 min")),
            row(text("Offers accepted"), text("2 out of 2")),
            row(text("Done")),
        )
        val fields = parse(tree)
        assertNull(
            "totalEarnings resolved '${fields["totalEarnings"]}' from a header that renders no " +
                "money — a positional first-currency read would have reported the weekly goal " +
                "as the dash total, and reportedEarningsOf keeps a summary-screen value " +
                "unconditionally",
            fields["totalEarnings"],
        )
        // The label-anchored stat reads are unaffected — only the money arm went null.
        assertEquals(7_080_000L, fields["sessionDurationMillis"])
        assertEquals(2, fields["offersAccepted"])
    }

    // ------------------------------------------------------------------
    //  Synthetic tree builders — the 8.93.7 shape: bare TextViews under a
    //  `bottomsheet_content_container`, values as the label's next sibling.
    // ------------------------------------------------------------------

    private fun idLessSheet(): UiNode = sheet(
        row(text("Dash summary"), text("$48.34")),
        row(text("Total online time"), text("1 hr 58 min")),
        row(text("Offers accepted"), text("2 out of 2")),
        row(text("Done")),
    )

    private fun text(value: String) =
        UiNode(text = value, className = "android.widget.TextView")

    private fun row(vararg children: UiNode) =
        UiNode(className = "android.widget.LinearLayout", children = children.toList())

    private fun sheet(vararg rows: UiNode): UiNode = UiNode(
        className = "android.widget.FrameLayout",
        viewIdResourceName = "com.doordash.driverapp:id/bottomsheet_content_container",
        children = rows.toList(),
    ).restoreParents()

    private companion object {
        const val PLATFORM = "doordash"
        const val RULE_ID = "doordash.screen.dash_summary"
        const val DASH_SUMMARY = "snapshots/dash_summary"
        const val NEGATIVE = "snapshots/UNKNOWN/negative"

        const val ID_LESS_8_93_7 =
            "2026-08-14_19-02-29-006__doordash__accessibility.window__dash_summary__646487.json"
        const val ID_ANCHORED =
            "2026-07-17_20-00-37-390__doordash__accessibility.window__dash_summary__8347fc.json"
        const val NO_STAT_ROWS = "2026-02-07_17-30-17__DASH_SUMMARY_SCREEN__182b139b.json"
        const val MID_DASH_SHEET =
            "2026-08-23_17-35-16-707__doordash__accessibility.window__UNKNOWN__71e5dc.json"
    }
}
