package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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
 * Three properties, none of which the golden alone states in one place:
 *
 * 1. **The 8.93.7 layout recognizes and parses**, off the rendered labels rather than ids.
 * 2. **A parse MISS yields null, never a fabricated 0.** The rule used to declare
 *    `fallback: 0` on the duration and both offer counts, so a sheet whose stat rows did not
 *    render still produced a "0-minute dash, 0 offers" — exactly the #936/#1030 shape the
 *    money side reads as a measurement. `SessionEndedFields` is nullable for this reason.
 * 3. **The mid-dash sheet is NOT this surface.** 8.93.7 also ships a "This dash so far" sheet
 *    carrying the SAME two stat labels; it must stay UNKNOWN, because a `session:ended` flow
 *    claimed mid-dash would end a live dash. (The committed negative corpus guards it too —
 *    [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.NegativeCorpusStaysUnknownTest] —
 *    this states the reason at the rule.)
 */
class DashSummaryReanchorTest {

    private val screenRuleset: Ruleset<UiNode> by lazy { TestRulesetFactory.screenRuleset }

    private fun snapshot(folder: String, filename: String): UiNode =
        TestResourceLoader.loadSnapshots(folder)
            .firstOrNull { it.first == filename }?.second
            ?: error("fixture $folder/$filename is missing — this test's subject moved or was pruned")

    private fun parse(node: UiNode): Map<String, Any?> {
        val result = screenRuleset.matchFirst(node, platformWire = PLATFORM)
        assertNotNull("frame classified UNKNOWN — the dash_summary re-anchor regressed", result)
        assertEquals("doordash.screen.dash_summary", result!!.ruleId)
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
        val node = snapshot(NEGATIVE, MID_DASH_SHEET)
        val intent = screenRuleset.matchFirst(node, platformWire = PLATFORM)?.intent ?: "UNKNOWN"
        assertEquals(
            "the mid-dash sheet classified '$intent' — it shares the summary's stat labels but " +
                "carries neither 'Dash summary' nor 'Done', and a session:ended flow claimed " +
                "mid-dash would end a live dash",
            "UNKNOWN",
            intent,
        )
    }

    private companion object {
        const val PLATFORM = "doordash"
        const val DASH_SUMMARY = "snapshots/dash_summary"
        const val NEGATIVE = "snapshots/UNKNOWN/negative"

        const val ID_LESS_8_93_7 =
            "2026-08-14_19-02-29-006__doordash__accessibility.window__UNKNOWN__646487.json"
        const val ID_ANCHORED =
            "2026-07-17_20-00-37-390__doordash__accessibility.window__dash_summary__8347fc.json"
        const val NO_STAT_ROWS = "2026-02-07_17-30-17__DASH_SUMMARY_SCREEN__182b139b.json"
        const val MID_DASH_SHEET =
            "2026-08-23_17-35-16-707__doordash__accessibility.window__UNKNOWN__71e5dc.json"
    }
}
