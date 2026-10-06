package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #856 — the Trip Radar board's redact is the ONLY control for its card dropoff lines
 * ("<StreetA> & <StreetB>, <City>" / "<Street>, <City>"): they are id-less and prefix-less,
 * so the `CustomerTextMarkers` backstop cannot own them, and before the rule existed every
 * board frame shipped them raw on the UNKNOWN capture path.
 *
 * Runs the PRODUCTION rule over the committed board fixtures (whose dropoff lines carry
 * pseudonymized streets in the real rendered shape) and pins: each frame recognizes as the
 * board, no dropoff line survives the redact, each one becomes a `[redacted:<4hex>]` mask, and
 * the driver-owned text (store line, pay, trip time) stays raw — the over-match guard.
 */
class UberTripRadarBoardRedactTest {

    private val ruleId = "uber.screen.trip_radar_board"
    private val frames = TestResourceLoader.loadSnapshots("snapshots/trip_radar_board")

    /**
     * A card dropoff line — detected on SHAPE, not on the rule's own selector (Astra review of PR #1247:
     * a detector copied from the redact cannot catch what the redact misses): any ` & ` cross-street
     * pair followed by `, <tail>`, or a `<Street>, <Tail>` line whose tail may carry a digit, an accented
     * letter, a `, ST 12345` suffix or trailing whitespace; a "<Store> (<location>)" line is not one.
     */
    private val dropoffLine = Regex("""^(?:[^()&,]+\s&\s[^(),]+,\s.+|[^()]+,\s\p{Lu}.*)\s*$""")
    private val mask = Regex("""^\[redacted:[0-9a-f]{4}]$""")

    private fun texts(node: UiNode): List<String> =
        listOfNotNull(node.text) + node.children.flatMap(::texts)

    @Test
    fun `every committed board frame classifies as the board`() {
        assertTrue("the trip_radar_board corpus must not be empty", frames.isNotEmpty())
        for ((file, node, _) in frames) {
            assertEquals(file, ruleId, TestRulesetFactory.screenRuleset.matchFirst(node, platformWire = "uber")?.ruleId)
        }
    }

    @Test
    fun `the board redact masks every card dropoff line and keeps store, pay and time raw`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById(ruleId)!!
        assertFalse("the board rule must declare a redact block", rule.redact.isEmpty())
        var masked = 0
        for ((file, node, _) in frames) {
            val before = texts(node)
            val after = texts(rule.redact.apply(node))
            assertTrue("$file: a dropoff line survived: ${after.filter(dropoffLine::matches)}", after.none(dropoffLine::matches))
            masked += after.count(mask::matches)
            // Over-match guard: every non-dropoff text is byte-identical after the redact.
            assertEquals("$file: driver-owned text must stay raw", before.filterNot(dropoffLine::matches), after.filterNot(mask::matches))
        }
        assertTrue("the corpus must exercise the redact (no dropoff line was masked)", masked > 0)
    }

    /**
     * Astra review of PR #1247 (HIGH): a synthetic board whose dropoff lines carry the shapes the two
     * original city-tail selectors missed — a `, TX 78209` state+ZIP suffix, a trailing space, an accented
     * city, a digit in the city — must mask EVERY one of them while the store line stays raw.
     */
    @Test
    fun `adversarial dropoff tails are masked on a synthetic board frame`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById(ruleId)!!
        val dropoffs = listOf(
            "Anonvale Ln & Decoy Rd, San Antonio, TX 78209",
            "Anonvale Ln & Decoy Rd, San Antonio ",
            "Anonvale Ln & Decoy Rd, La Cañada Flintridge",
            "Anonvale Ln & Decoy Rd, Rio Grande City 2",
            "Placeholder Rdg, St. Louis Park",
            "Placeholder Rdg, San Antonio, TX",
        )
        val store = "Sample Store (Anonvale Plaza)"
        fun tv(id: String, text: String) = UiNode(className = "android.widget.TextView", viewIdResourceName = "com.ubercab.driver:id/$id", text = text)
        val board = UiNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.ubercab.driver:id/driver_offers_job_board_content_container",
            children = listOf(tv("driver_offers_job_board_toolbar", "Trip Radar"), tv("store_line", store)) +
                dropoffs.map { UiNode(className = "android.widget.TextView", text = it) },
        )
        assertEquals(ruleId, TestRulesetFactory.screenRuleset.matchFirst(board, platformWire = "uber")?.ruleId)
        val after = texts(rule.redact.apply(board))
        for (line in dropoffs) assertFalse("survived: '$line'", after.contains(line))
        assertEquals("every dropoff masked, nothing else touched", dropoffs.size, after.count(mask::matches))
        assertTrue("store line stays raw", after.contains(store))
        assertTrue("toolbar chrome stays raw", after.contains("Trip Radar"))
    }
}
