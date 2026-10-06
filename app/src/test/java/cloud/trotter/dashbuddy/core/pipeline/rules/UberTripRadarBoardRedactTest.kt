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

    /** A card dropoff line: ends in ", <City>" and is not a "<Store> (<location>)" line. */
    private val dropoffLine = Regex("""^[^()]+,\s[A-Z][A-Za-z.'-]*(\s[A-Z][A-Za-z.'-]*)*$""")
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
}
