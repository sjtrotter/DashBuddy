package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #962 — the points-based ratings hub (DoorDash 0.230.0) parses on the ID-LESS
 * redesign layout, and the LEGACY layout is untouched by the re-anchor.
 *
 * The corpus-wide [ParseOutputGoldenTest] already pins every one of these values,
 * but it pins them as one 6 000-line approval blob: a future edit that silently
 * broke the redesign anchors would show up there as a diff someone has to *read*
 * correctly. This test names the invariant instead, so the failure message says
 * which anchor broke.
 *
 * The two halves are the whole point of the `coalesce` ordering:
 *  - the redesign frame must parse every factor, plus the two new points-rating
 *    facts, off bare sibling TextViews;
 *  - the legacy frames must keep parsing off `textView_title` siblings and must
 *    NOT acquire a `tierLabel` — one of them renders a bare 'Silver' TextView in
 *    the SIDE-NAV drawer, which is exactly what the `$ratingsHubContent` scope
 *    exists to keep out of the parse.
 */
class PointsRatingParseTest {

    /** The 0.230.0 redesign hub: Overall rating 73 / Silver, six scored factors. */
    private val redesignFixture =
        "2026-07-29_15-19-05-861__doordash__accessibility.window__UNKNOWN__dd3695.json"

    /** A legacy hub whose SIDE-NAV drawer renders its own bare 'Silver' TextView. */
    private val legacyWithDrawerTier = "2026-02-14_20-42-32__RATINGS_VIEW__86bd7ffc.json"

    private fun ratingsFor(fileName: String): ParsedFields.RatingsFields {
        val node: UiNode = TestResourceLoader.loadSnapshots("snapshots/ratings")
            .firstOrNull { it.first == fileName }
            ?.second
            ?: error("Corpus fixture missing: ratings/$fileName")

        val result = TestRulesetFactory.screenRuleset.matchFirst(node)
        assertNotNull("ratings/$fileName no longer classifies at all", result)
        assertEquals(
            "ratings/$fileName must be claimed by the ratings hub rule",
            "doordash.screen.ratings",
            result!!.ruleId,
        )
        val parsed = ParsedFieldsFactory.create(result.shape, result.fields)
        assertTrue(
            "ratings/$fileName parsed as ${parsed::class.simpleName}, expected RatingsFields",
            parsed is ParsedFields.RatingsFields,
        )
        return parsed as ParsedFields.RatingsFields
    }

    @Test
    fun `the redesigned points hub parses every factor plus the points-rating facts`() {
        val r = ratingsFor(redesignFixture)

        // The two #962 facts. Points is a regex extraction off the single
        // 'Overall rating 73' node; the tier is scoped to the hub content.
        assertEquals("overallRatingPoints", 73, r.overallRatingPoints)
        assertEquals("tierLabel", "Silver", r.tierLabel)

        // Re-anchored factors. Rates are PERCENTAGES (0-100), the scale the
        // legacy parse has always emitted and RatingsUiState documents.
        assertEquals("acceptanceRate", 23.0, r.acceptanceRate!!, 0.0001)
        assertEquals("onTimeRate", 100.0, r.onTimeRate!!, 0.0001)
        assertEquals("completionRate", 99.0, r.completionRate!!, 0.0001)
        assertEquals("qualityRate", 100.0, r.qualityRate!!, 0.0001)
        assertEquals("customerRating", 5.0, r.customerRating!!, 0.0001)
        assertEquals("deliveriesLast30Days", 80, r.deliveriesLast30Days)

        // The redesign renders no lifetime counters and no shopping-quality rows,
        // so those stay null rather than picking up a neighbouring value.
        assertNull("lifetimeDeliveries", r.lifetimeDeliveries)
        assertNull("lifetimeShoppingOrders", r.lifetimeShoppingOrders)
        assertNull("originalItemsFoundRate", r.originalItemsFoundRate)
    }

    @Test
    fun `the legacy hub keeps its values and never reads the drawer's tier chip`() {
        val r = ratingsFor(legacyWithDrawerTier)

        // Legacy branch of every coalesce still wins.
        assertEquals("acceptanceRate", 50.0, r.acceptanceRate!!, 0.0001)
        assertEquals("completionRate", 100.0, r.completionRate!!, 0.0001)
        assertEquals("onTimeRate", 100.0, r.onTimeRate!!, 0.0001)
        assertEquals("customerRating", 4.97, r.customerRating!!, 0.0001)
        assertEquals("lifetimeDeliveries", 5679, r.lifetimeDeliveries)
        assertEquals("lifetimeShoppingOrders", 1144, r.lifetimeShoppingOrders)

        // The #962 additions are absent on the legacy layout. `tierLabel` is the
        // load-bearing one: this fixture DOES contain a bare 'Silver' TextView,
        // but it lives in `side_nav_container` (the drawer), outside the
        // `$ratingsHubContent` bind — so reading it here would mean the parse had
        // started depending on an unrelated overlay being open.
        assertNull("tierLabel must not be read off the side-nav drawer", r.tierLabel)
        assertNull("overallRatingPoints", r.overallRatingPoints)
        assertNull("qualityRate", r.qualityRate)
    }

    @Test
    fun `the points score separates two otherwise identical snapshots`() {
        // The redesigned hub renders no lifetimeDeliveries, so without the points
        // score the dedupe hash would collapse every snapshot of a dasher whose
        // star rating has not moved.
        val a = ParsedFields.RatingsFields(customerRating = 5.0, overallRatingPoints = 73)
        val b = ParsedFields.RatingsFields(customerRating = 5.0, overallRatingPoints = 78)
        assertTrue(
            "a points change must change the ratings dedupe hash",
            a.dedupeHash() != b.dedupeHash(),
        )
    }
}
