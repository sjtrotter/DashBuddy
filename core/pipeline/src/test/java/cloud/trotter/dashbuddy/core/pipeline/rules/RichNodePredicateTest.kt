package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #1147 — the node predicates over the TalkBack-study fields. Each is enumerated in
 * [PredicateCompiler.compileNodePred] (and `docs/rules.schema.json`); exact string forms are
 * case-insensitive, boolean forms honour `false`; no predicate exists for `uniqueId`, tooltip,
 * error, live region or collection indices — naming one is the ordinary unknown-key reject.
 */
class RichNodePredicateTest {

    private fun pred(json: String) = RuleCompiler.compileNodePred(Json.parseToJsonElement(json))

    private val rich = UiNode(
        text = "Accept",
        paneTitle = "Offer Details",
        roleDescription = "Button",
        hintText = "Search stores",
        clickActionLabel = "Accept offer",
        uniqueId = "uid-7",
        tooltipText = "tip",
        errorText = "err",
        isVisibleToUser = false,
        isSelected = true,
        isCheckable = true,
        isHeading = true,
        hasClickAction = true,
    )
    private val bare = UiNode(text = "Accept")

    private fun assertMatchesOnlyRich(json: String) {
        val p = pred(json)
        assertTrue("$json must match the rich node", p(rich))
        assertFalse("$json must not match the bare node", p(bare))
    }

    @Test
    fun `string predicates match their own field case-insensitively`() {
        assertMatchesOnlyRich("""{ "hasPaneTitle": "offer details" }""")
        assertMatchesOnlyRich("""{ "hasPaneTitleContaining": "DETAIL" }""")
        assertMatchesOnlyRich("""{ "hasRoleDescription": "button" }""")
        assertMatchesOnlyRich("""{ "hasClickActionLabel": "ACCEPT OFFER" }""")
        assertMatchesOnlyRich("""{ "hasClickActionLabelContaining": "offer" }""")
        assertMatchesOnlyRich("""{ "hasHintText": "search STORES" }""")
        assertMatchesOnlyRich("""{ "hasHintTextContaining": "stores" }""")
    }

    @Test
    fun `exact forms do not match a substring`() {
        assertFalse(pred("""{ "hasPaneTitle": "Offer" }""")(rich))
        assertFalse(pred("""{ "hasClickActionLabel": "Accept" }""")(rich))
        assertFalse(pred("""{ "hasHintText": "Search" }""")(rich))
    }

    @Test
    fun `boolean predicates honour both values`() {
        assertMatchesOnlyRich("""{ "isVisibleToUser": false }""")
        assertMatchesOnlyRich("""{ "isSelected": true }""")
        assertMatchesOnlyRich("""{ "isCheckable": true }""")
        assertMatchesOnlyRich("""{ "isHeading": true }""")
        assertMatchesOnlyRich("""{ "hasClickAction": true }""")
        assertTrue(pred("""{ "isVisibleToUser": true }""")(bare))
        assertTrue(pred("""{ "hasClickAction": false }""")(bare))
    }

    @Test
    fun `a non-boolean flag value is a typed compile error`() {
        expectFails("""{ "isSelected": "yes" }""")
        expectFails("""{ "hasPaneTitle": { "nested": true } }""")
    }

    /** #1147 review X3: the shared helpers accept only the schema's declared JSON type. */
    @Test
    fun `null, numbers and quoted booleans are compile errors for every predicate`() {
        expectFails("""{ "hasPaneTitle": null }""", contains = "requires a string value")
        expectFails("""{ "hasHintText": 123 }""", contains = "requires a string value")
        expectFails("""{ "hasText": true }""", contains = "requires a string value")
        expectFails("""{ "isSelected": "true" }""", contains = "requires a boolean value")
        expectFails("""{ "isClickable": null }""", contains = "requires a boolean value")
        expectFails("""{ "isClickable": 1 }""", contains = "requires a boolean value")
    }

    @Test
    fun `the new fields stay out of the subtree allText predicates`() {
        // A rule must opt in through the dedicated predicate — widening the node model moves no
        // classification that reads allText (#835 doctrine).
        assertFalse(pred("""{ "hasAnyText": "Offer Details" }""")(rich))
        assertFalse(pred("""{ "hasAnyText": "Accept offer" }""")(rich))
        assertFalse(pred("""{ "hasAnyTextStartsWith": "Search" }""")(rich))
    }

    @Test
    fun `fields deliberately left without a predicate are unknown keys`() {
        for (key in listOf("hasUniqueId", "uniqueId", "hasTooltipText", "hasErrorText", "liveRegion", "itemRow", "hasCollectionInfo")) {
            expectFails("""{ "$key": "x" }""", contains = "Unknown node predicate key")
        }
    }

    private fun expectFails(json: String, contains: String? = null) {
        try {
            pred(json)
            fail("expected RuleCompileException for $json")
        } catch (e: RuleCompileException) {
            contains?.let { assertTrue("'${e.message}' should contain '$it'", e.message?.contains(it) == true) }
        }
    }
}
