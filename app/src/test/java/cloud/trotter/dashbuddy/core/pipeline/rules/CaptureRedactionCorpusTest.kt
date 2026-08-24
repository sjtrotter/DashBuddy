package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.test.util.CorpusDecoys
import cloud.trotter.dashbuddy.test.util.SnapshotRedactor
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #598 corpus-level guard — the teeth. Runs the PRODUCTION redact predicates
 * over a real (already-redacted) dropoff fixture with a synthetic customer name
 * injected, and pins the fail-closed invariant that every screen rule which
 * hashes PII (`sha256`) also declares a `redact` block. Redaction is
 * capture-only, so this asserts on the SERIALIZED envelope payload, never on
 * recognition (the goldens stay byte-identical).
 */
class CaptureRedactionCorpusTest {

    private val rulesDir = File(TestRulesetFactory.rulesDir)

    private fun serialize(tree: UiNode): String = UiNodeSchema.serialize(tree)

    companion object {
        /**
         * The `[redacted:<4hex>]` distinctness-mask token — **ONE spelling** for the whole file.
         *
         * This used to be hand-copied at ~35 sites in two divergent spellings (`…{4}\]` and
         * `…{4}]`) with three separate local hex extractors. That is a live hazard, not just
         * duplication: several assertions here are NEGATIVE (`assertFalse(MASK_HEX…)` — #920 pins
         * that a customer note must NOT carry the hash suffix), and a negative assertion backed by
         * a subtly-wrong private regex passes **vacuously**. With one constant shared by the
         * positive and negative assertions, every negative is guarded by the positives that prove
         * the same regex matches a real mask.
         *
         * Group 1 is the hex, so the same constant serves matching and extraction.
         */
        val MASK_HEX = Regex("""\[redacted:([0-9a-f]{4})]""")

        /** [MASK_HEX] anchored to the WHOLE value — the node was masked entirely, no lead-in kept. */
        val WHOLE_MASK_HEX = Regex("""^\[redacted:([0-9a-f]{4})]$""")

        /** The plain, hash-less `[redacted]` mask (#795 `plainMask` / the #362 fail-closed form). */
        val PLAIN_MASK = Regex("""\[redacted]""")

        /** [MASK_HEX] preceded by a kept `keepPrefix` lead-in, e.g. `Deliver to [redacted:ab12]`. */
        fun maskAfter(prefix: String) = Regex(Regex.escape(prefix) + MASK_HEX.pattern)

        /** [maskAfter] anchored to the whole value — lead-in kept and nothing else survives. */
        fun wholeMaskAfter(prefix: String) = Regex("^" + Regex.escape(prefix) + MASK_HEX.pattern + "$")

        /**
         * The 4hex [MASK_HEX] carries in [value], or a loud failure naming [what]. The single
         * extractor the per-test `hexOf` helpers now delegate to, so "was it masked at all" and
         * "which customer is it" can never be answered by two different patterns.
         */
        fun hexIn(value: String, what: String): String =
            MASK_HEX.find(value)?.groupValues?.get(1)
                ?: error("$what was not masked; got '$value'")
    }

    @Test
    fun `dropoff redact masks an injected customer name but keeps the Deliver to marker`() {
        // A real, already-redacted en-route dropoff card from the committed corpus.
        val fixture = File("src/test/resources/snapshots/dropoff_pre_arrival")
            .listFiles { _, n -> n.endsWith(".json") }!!
            .sorted().first()
        val real = TestResourceLoader.loadNode(fixture)

        // Inject a synthetic PII node the way a live (un-redacted) capture would carry it.
        val injected = UiNode(
            text = "Deliver to Testname Q",
            children = real.children,
        ).restoreParents()

        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_pre_arrival")!!
        val redactedJson = serialize(rule.redact.apply(injected))

        assertFalse("injected customer name must not persist", redactedJson.contains("Testname Q"))
        // #623: the masked portion carries a `[redacted:<4hex>]` distinctness suffix.
        assertTrue(
            "marker kept, name masked",
            maskAfter("Deliver to ").containsMatchIn(redactedJson),
        )
    }

    /**
     * #813 — the uber.screen.offer redact is a PRIMARY privacy control for the
     * offer card's dropoff intersection line (a prefix-less address line the
     * `CustomerTextMarkers` backstop structurally can't own), and it had no
     * automated coverage: `CaptureRedactionCorpusTest`'s other cases never touch
     * this rule, and the marker-based backstop test is blind to it by
     * construction. This is the teeth — a later predicate edit or Uber shape
     * change that silently un-masks every offer dropoff line goes RED here.
     *
     * Runs the PRODUCTION redact over a real committed offer frame and asserts
     * the dropoff line masks while the store name, pay, and the fused time/mi
     * node stay raw (the over-match guard — store names never carry a ", ", the
     * same discriminator the storeName parse uses).
     */
    @Test
    fun `uber offer redact masks the dropoff intersection line, keeps store pay and time`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("uber.screen.offer")!!
        assertFalse("offer rule must declare a redact block", rule.redact.isEmpty())

        // A real committed offer frame: store "Costco Wholesale (Sonterra Park)",
        // dropoff "Sample St & Sample Ave, San Antonio", pay "$15.01", fused
        // "40 min (17.5 mi) total". (The intersection is already anonymized in
        // the corpus; the redact predicate matches the ", <City>" shape, not any
        // literal street.)
        val fixture = File("src/test/resources/snapshots/offer")
            .listFiles { _, n -> n.contains("uber") && n.endsWith(".json") }!!
            .first { it.readText().contains("Costco Wholesale") }
        val real = TestResourceLoader.loadNode(fixture)

        val redactedJson = serialize(rule.redact.apply(real))

        // Dropoff intersection line is masked (customer-area PII).
        assertFalse(
            "dropoff intersection line must not persist raw",
            redactedJson.contains("Sample St & Sample Ave, San Antonio"),
        )
        assertTrue(
            "the masked dropoff line carries a [redacted:<4hex>] token",
            MASK_HEX.containsMatchIn(redactedJson),
        )
        // Over-match guard: store name, pay, and the fused time/mi node stay RAW.
        assertTrue(
            "store name kept (no ', ' → not matched)",
            redactedJson.contains("Costco Wholesale (Sonterra Park)"),
        )
        assertTrue("pay kept", redactedJson.contains("\$15.01"))
        assertTrue("fused time/mi node kept", redactedJson.contains("40 min (17.5 mi) total"))
    }

    /**
     * #623 frame-invariance ACROSS RULES (VET V5): the SAME customer token, seen
     * under three different production redact blocks (each with a different marker
     * prefix / node predicate), must redact to the SAME 4hex distinctness suffix —
     * and a DIFFERENT customer to a DIFFERENT suffix. This is what makes
     * multi-customer replay possible without persisting raw PII: the suffix is a
     * stable 16-bit fingerprint of the customer token, aligned with the parse's
     * strip+trim+sha256, not a per-frame value.
     */
    @Test
    fun `same customer redacts to the same 4hex across dropoff_navigation, timeline, and pickup_verify_items`() {
        fun hexOf(masked: String): String = hexIn(masked, "value")

        // dropoff_navigation: id-keyed title node, keepPrefix "Deliver to ".
        val navRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_navigation")!!
        val navHex = hexOf(
            navRule.redact.apply(
                UiNode(
                    viewIdResourceName = "com.dd:id/bottom_sheet_task_title",
                    text = "Deliver to Jane Q Doe",
                ),
            ).text!!,
        )

        // timeline: text-keyed node, keepPrefix "Deliver to " (also "Pickup for ").
        val timelineRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.timeline")!!
        val timelineHex = hexOf(
            timelineRule.redact.apply(UiNode(text = "Deliver to Jane Q Doe")).text!!,
        )

        // pickup_verify_items: different marker ("Verify items for ") + different id.
        val verifyRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_verify_items")!!
        val verifyHex = hexOf(
            verifyRule.redact.apply(
                UiNode(
                    viewIdResourceName = "com.dd:id/order_verification_checklist_title",
                    text = "Verify items for Jane Q Doe",
                ),
            ).text!!,
        )

        assertEquals("same customer must redact to the same 4hex across rules", navHex, timelineHex)
        assertEquals("same customer must redact to the same 4hex across rules", navHex, verifyHex)

        // #733: the suffix equals the first 4 hex of sha256 of the CANONICAL customer-name KEY
        // (normalizeCustomerName: first token + second-token initial → "jane q"), the same hash the
        // parse now persists — so the mask cross-references the persisted customerNameHash, and the
        // three surface forms of one customer redact identically.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("jane q".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(4)
        assertEquals("suffix must be first 4 hex of sha256(canonical customer-name key)", expected, navHex)

        // A DIFFERENT customer must produce a DIFFERENT suffix (distinctness): "john s" != "jane q".
        val otherHex = hexOf(
            timelineRule.redact.apply(UiNode(text = "Deliver to John Smith")).text!!,
        )
        assertFalse("different customers must redact to different 4hex", otherHex == navHex)
    }

    @Test
    fun `dropoff_reminder redact masks the address after the marker (#624)`() {
        // The live leak #624 fixes: "Deliver to door of <address>" had no hash and
        // no redact, so the #598 sha256 gate never fired and the raw address shipped.
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_reminder")!!
        assertTrue("dropoff_reminder must now carry a redact block", !rule.redact.isEmpty())

        val node = UiNode(text = "Deliver to door of 4B, 123 Real Street").restoreParents()
        val masked = serialize(rule.redact.apply(node))

        assertFalse("street address must not persist", masked.contains("123 Real Street"))
        assertFalse("apt must not persist", masked.contains("4B"))
        assertTrue(
            "marker kept, address masked",
            maskAfter("Deliver to door of ").containsMatchIn(masked),
        )
    }

    /**
     * #860 — the dropoff address block's `Building Name` VALUE (an apartment-complex
     * name) shipped RAW in the recognized envelope. Every sibling field in that block
     * was already covered (street/city/zip by the id-less numeric shapes, `Entry code`
     * / `Apt/Suite` values by the `^#?\s?\d{1,4}[A-Za-z]?$` entry, the instruction body
     * by the `dasher_instruction_*` ids) but a building name has no id, no digits and
     * no stable text shape, so no predicate named it — and `CustomerTextMarkers` has no
     * marker prefix here, so the backstop is structurally blind to it too. The fix
     * anchors on the LABEL sibling (`hasPrecedingSiblingText`).
     *
     * Node shape is ground truth from a real 2026-07-23 device capture: a parent
     * `android.view.View` whose children are six id-less TextViews in
     * label/value/label/value/label/value order, `Building Name` last. NOTHING from
     * that capture is reproduced here — the label is app chrome, and every value below
     * is invented.
     *
     * Covers BOTH rules that render this workflow sheet: `dropoff_pre_arrival` (the
     * fielded ground truth) and `dropoff_pre_arrival_completion`, which is the SAME
     * sheet one CTA-state later and carries the same id-less label/value rows. No
     * committed corpus fixture carries a `Building Name` row for either, so the shape
     * is asserted directly rather than through a fixture; the completion rule's entry
     * is shape-inherited from the pre-arrival capture.
     *
     * Teeth: deleting either rule's Building-Name redact entry turns this RED.
     */
    @Test
    fun `dropoff workflow-sheet redacts mask the Building Name value, keep the label (#860)`() {
        val ruleIds = listOf(
            "doordash.screen.dropoff_pre_arrival",
            "doordash.screen.dropoff_pre_arrival_completion",
        )

        fun addressBlock(buildingName: String) = UiNode(
            className = "android.view.View",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Entry code"),
                UiNode(className = "android.widget.TextView", text = "#1234"),
                UiNode(className = "android.widget.TextView", text = "Apt/Suite"),
                UiNode(className = "android.widget.TextView", text = "704"),
                UiNode(className = "android.widget.TextView", text = "Building Name"),
                UiNode(className = "android.widget.TextView", text = buildingName),
                // Over-match guard: an id-less free-text node that is NOT preceded by
                // the label must survive untouched.
                UiNode(className = "android.widget.TextView", text = "Leave it at the door"),
            ),
        ).restoreParents()

        for (id in ruleIds) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)!!
            val masked = serialize(rule.redact.apply(addressBlock("Maple Court Apartments ")))

            assertFalse("$id: building name must not persist", masked.contains("Maple Court Apartments"))
            assertTrue(
                "$id: the label is app chrome and stays raw",
                masked.contains("Building Name"),
            )
            assertTrue(
                "$id: the building-name value masks to the hash family [redacted:<4hex>]",
                MASK_HEX.containsMatchIn(masked),
            )
            assertTrue(
                "$id: over-match guard — an unlabeled sibling stays raw",
                masked.contains("Leave it at the door"),
            )

            // Distinctness (#623): two different complexes must not collide on one mask.
            // Isolated label/value pair so the only masked node in the tree IS the value.
            fun hexOf(name: String): String {
                val pair = UiNode(
                    className = "android.view.View",
                    children = listOf(
                        UiNode(className = "android.widget.TextView", text = "Building Name"),
                        UiNode(className = "android.widget.TextView", text = name),
                    ),
                ).restoreParents()
                val value = rule.redact.apply(pair).children[1].text!!
                return WHOLE_MASK_HEX.find(value)?.groupValues?.get(1)
                    ?: error("$id: Building Name value was not masked; got '$value'")
            }
            assertFalse(
                "$id: different building names must redact to different suffixes",
                hexOf("Maple Court Apartments") == hexOf("Cedar Ridge Villas"),
            )
        }
    }

    /**
     * #885 — `dropoff_multi_order_confirm` shipped a RAW customer name on a RECOGNIZED
     * surface. The rule's name-shape redact entry existed; it just never fired, because
     * DoorDash renders the line with a **double space** ("Firstname␣␣L.") and the shared
     * name-shape SSOT used a LITERAL single space between tokens. The separator is now
     * `\s{1,4}`, so the layout's whitespace can't decide whether PII is masked.
     *
     * Node shape is ground truth from the 07-26 device capture (id-less TextViews: the
     * two copy lines, the name, the store form, the item count, an id-bearing CTA).
     * Every VALUE is invented — the fielded name/store are not reproduced.
     *
     * Teeth: revert the separator to a literal space, or delete the entry, and the
     * double-space assertion goes RED. The over-match guards pin that the store form,
     * the count, and the instruction copy still ship raw (merchant data is driver-owned).
     */
    @Test
    fun `dropoff_multi_order_confirm masks a double-spaced customer name, keeps the store (#885)`() {
        val rule = TestRulesetFactory.screenRuleset
            .ruleById("doordash.screen.dropoff_multi_order_confirm")!!

        fun card(name: String) = UiNode(
            className = "android.view.View",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Confirm you have the correct order before drop-off."),
                UiNode(
                    className = "android.widget.TextView",
                    text = "Mix-ups frequently occur at drop-off when there are multiple orders in a Dash.",
                ),
                UiNode(className = "android.widget.TextView", text = name),
                UiNode(className = "android.widget.TextView", text = "Sample Pizza Co (41709)"),
                UiNode(className = "android.widget.TextView", text = "2 items"),
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/textView_prism_button_title",
                    className = "android.widget.TextView",
                    text = "Continue",
                ),
            ),
        ).restoreParents()

        // THE #885 SHAPE: two spaces between the first name and the last initial.
        val doubleSpaced = serialize(rule.redact.apply(card("Testname  Q.")))
        assertFalse("double-spaced customer name must not persist", doubleSpaced.contains("Testname"))
        assertTrue(
            "the name masks to the hash family [redacted:<4hex>]",
            MASK_HEX.containsMatchIn(doubleSpaced),
        )
        // Over-match guards: merchant + structural copy stay raw.
        assertTrue("store form kept (driver-owned)", doubleSpaced.contains("Sample Pizza Co (41709)"))
        assertTrue("item count kept", doubleSpaced.contains("2 items"))
        assertTrue("instruction copy kept", doubleSpaced.contains("Mix-ups frequently occur at drop-off"))
        assertTrue("CTA kept", doubleSpaced.contains("Continue"))

        // The single-space form (the pre-#885 assumption) still masks — no regression.
        assertFalse(
            "single-spaced customer name must not persist",
            serialize(rule.redact.apply(card("Testname Q."))).contains("Testname"),
        )

        // #733/#885 cross-surface stability: the mask hex must equal the SAME customer's hex
        // on the "Deliver to <name>" nav title — that is what `normalize: customerName` on this
        // entry buys, and it holds across BOTH the double-space render and the fuller name form.
        fun cardHex(name: String): String {
            val masked = rule.redact.apply(
                UiNode(className = "android.view.View", children = listOf(UiNode(text = name))).restoreParents(),
            ).children[0].text!!
            return MASK_HEX.find(masked)?.groupValues?.get(1)
                ?: error("name node was not masked; got '$masked'")
        }
        val navRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_navigation")!!
        val navHex = MASK_HEX.find(
            navRule.redact.apply(
                UiNode(
                    viewIdResourceName = "com.dd:id/bottom_sheet_task_title",
                    text = "Deliver to Testname Quill",
                ),
            ).text!!,
        )!!.groupValues[1]
        assertEquals("double-spaced name masks to the customer's canonical hex", navHex, cardHex("Testname  Q."))
        assertEquals("single-spaced name masks to the same hex", navHex, cardHex("Testname Q."))
    }

    /**
     * #886 — the drop-off navigation screen embeds Google Nav, and its final "arriving"
     * maneuver restates the customer's FULL street address in nav copy
     * ("<house#> <Street>, <City>, <ST> <zip>, USA will be on the right"). The bottom
     * sheet's own address lines were masked by id; the maneuver node was not, so a
     * recognized dropoff envelope still carried the destination raw.
     *
     * Covers both rules that can render a customer-destination maneuver:
     * `dropoff_navigation` (the fielded one) and the store-AMBIGUOUS full-screen
     * `navigation_generic` catch-all, which has no task sheet to tell a merchant leg from
     * a customer leg (the #745 nav_arriving posture: fail toward privacy). `pickup_navigation`
     * restates the MERCHANT address — driver-owned — and is asserted to stay RAW.
     *
     * Teeth: delete either maneuver entry and the address assertion goes RED.
     */
    @Test
    fun `dropoff-phase nav maneuver text is masked, pickup nav is left raw (#886)`() {
        val arrivalManeuver = "742 Sample Hollow Way, San Antonio, TX 78260, USA will be on the right "

        fun navScreen() = UiNode(
            className = "android.view.View",
            children = listOf(
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/stepDistance",
                    className = "android.widget.TextView",
                    text = "400 ft",
                ),
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/primaryManeuverText",
                    className = "android.widget.TextView",
                    text = arrivalManeuver,
                ),
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/subManeuverText",
                    className = "android.widget.TextView",
                    text = "Sample Hollow Way ",
                ),
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/roadNameView",
                    className = "android.widget.TextView",
                    text = "Sample Golf Road ",
                ),
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/textView_prism_button_title",
                    className = "android.widget.TextView",
                    text = "Exit",
                ),
            ),
        ).restoreParents()

        for (id in listOf("doordash.screen.dropoff_navigation", "doordash.screen.navigation_generic")) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)!!
            assertFalse("$id must declare a redact block", rule.redact.isEmpty())
            val masked = serialize(rule.redact.apply(navScreen()))

            assertFalse("$id: the arrival maneuver address must not persist", masked.contains("Sample Hollow Way"))
            assertFalse("$id: the current-road node must not persist", masked.contains("Sample Golf Road"))
            assertTrue(
                "$id: the maneuver masks to the hash family [redacted:<4hex>]",
                MASK_HEX.containsMatchIn(masked),
            )
            // Over-match guard: nav chrome the recognition anchors read stays raw.
            assertTrue("$id: step distance kept", masked.contains("400 ft"))
            assertTrue("$id: Exit CTA kept", masked.contains("Exit"))
        }

        // The MERCHANT-side sibling is deliberately untouched (driver-owned data).
        val pickup = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_navigation")!!
        assertTrue(
            "pickup_navigation restates the merchant address — kept by design",
            serialize(pickup.redact.apply(navScreen())).contains("Sample Hollow Way"),
        )
    }

    /**
     * #886 commit-path parity — the runtime redact above masks the whole nav maneuver cluster, so
     * `SnapshotRedactor` (which scrubs a fixture on its way INTO the committed corpus) must know the
     * same ids. It already knew `primaryManeuverText`; the siblings were the gap, and no text shape
     * covers them: a destination street with no house number ("Canyon Golf Road ") carries no digits,
     * so STREET / FULL_ADDRESS / CITY_STATE_ZIP / BARE_STREET all structurally miss it and a future
     * committed nav fixture would ship the customer's street raw.
     *
     * Teeth: drop any of the four ids from `PII_ID_SUFFIXES` and this goes RED.
     */
    @Test
    fun `the commit-path scrubber masks the whole nav maneuver cluster by id (#886)`() {
        // A number-less street name — the shape passes cannot see it; only the id can.
        val street = "Sample Golf Road "
        for (id in listOf("primaryManeuverText", "subManeuverText", "secondaryManeuverText", "roadNameView")) {
            val scrubbed = SnapshotRedactor.redact(
                """{"id":"com.doordash.driverapp:id/$id","text":"$street"}""",
            )
            assertFalse("$id: a digit-less destination street must not survive -> $scrubbed", scrubbed.contains("Sample Golf Road"))
            assertTrue("$id: masked", scrubbed.contains(SnapshotRedactor.MASK))
        }
        // Control: the SAME text under a non-PII id survives, proving the id set (not a shape) did it.
        assertTrue(
            "a digit-less street under a non-PII id is invisible to every shape pass",
            SnapshotRedactor.redact("""{"id":"com.doordash.driverapp:id/stepDistance","text":"$street"}""")
                .contains("Sample Golf Road"),
        )
    }

    /**
     * #886 (venue variant) — when the drop-off destination is a business/venue, its NAME
     * renders in the card's address line-1 slot. That block is id-less and label-less, so
     * line 1 was only ever named by the numeric street shape (`^\d{1,5}\s+\S`): the city/
     * ST/ZIP line beneath it masked correctly via the `\d{5}` entry while the venue name —
     * no id, no digits, no shape, and no preceding sibling (it is child 0) — shipped RAW.
     * The fix anchors FORWARD, on the address line beneath it
     * (`hasFollowingSiblingTextMatchesRegex`), which covers BOTH forms of line 1.
     *
     * Node shape is ground truth from the 07-26 capture (a container whose children are
     * line 1, line 2, and a full-block Button); all values are invented.
     *
     * Teeth: delete the entry from any of the four workflow-sheet rules and that rule's
     * venue assertion goes RED.
     */
    @Test
    fun `dropoff workflow-sheet redacts mask a venue name in the address line-1 slot (#886)`() {
        val ruleIds = listOf(
            "doordash.screen.dropoff_pre_arrival",
            "doordash.screen.dropoff_pre_arrival_completion",
            "doordash.screen.dropoff_handoff",
            "doordash.screen.dropoff_pin_entry",
        )

        fun addressBlock(lineOne: String) = UiNode(
            className = "android.view.View",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = lineOne),
                UiNode(className = "android.widget.TextView", text = "San Antonio, TX 78260, USA"),
                UiNode(className = "android.widget.Button"),
                // Over-match guard: an instruction line whose next sibling is NOT an
                // address must survive untouched.
                UiNode(className = "android.widget.TextView", text = "Leave it at the door"),
                UiNode(className = "android.widget.TextView", text = "Directions"),
            ),
        ).restoreParents()

        for (id in ruleIds) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)!!
            val venue = serialize(rule.redact.apply(addressBlock("Sample Family Medical and Urgent Care")))

            assertFalse("$id: venue name must not persist", venue.contains("Sample Family Medical"))
            assertTrue(
                "$id: the venue masks to the hash family [redacted:<4hex>]",
                MASK_HEX.containsMatchIn(venue),
            )
            assertTrue("$id: over-match guard — instruction line stays raw", venue.contains("Leave it at the door"))
            assertTrue("$id: over-match guard — CTA stays raw", venue.contains("Directions"))

            // The numeric street form is covered too (defense in depth with the `^\d{1,5}\s+\S` entry).
            assertFalse(
                "$id: street line must not persist",
                serialize(rule.redact.apply(addressBlock("742 Sample Hollow Way"))).contains("Sample Hollow Way"),
            )

            // Distinctness (#623): two venues must not collide on one mask.
            fun hexOf(line: String): String {
                val value = rule.redact.apply(
                    UiNode(
                        className = "android.view.View",
                        children = listOf(
                            UiNode(className = "android.widget.TextView", text = line),
                            UiNode(className = "android.widget.TextView", text = "San Antonio, TX 78260, USA"),
                        ),
                    ).restoreParents(),
                ).children[0].text!!
                return WHOLE_MASK_HEX.find(value)?.groupValues?.get(1)
                    ?: error("$id: address line 1 was not masked; got '$value'")
            }
            assertFalse(
                "$id: different venues must redact to different suffixes",
                hexOf("Sample Family Medical and Urgent Care") == hexOf("Sample Ridge Dental Group"),
            )
        }
    }

    @Test
    fun `every For-family redact masks the fused customer header, keeps the For marker (#809)`() {
        // #809: the "For <customer> • <store>" surfaces each ship a `redact` with
        // keepPrefix ["For "] + normalize customerName. Nothing else pinned them —
        // the #598 sha256 compile gate needs a sha256 parse (these are recognize-only),
        // and CustomerTextMarkers has no bare "For " marker — so a deleted redact block
        // on any of them went unnoticed (mutation-verified: the full suite stayed green).
        // This is the teeth: an injected fused header node must mask to
        // "For [redacted:<4hex>]" with no name residue.
        //
        // #1031 (round 2) makes this ONE loop the owner of the class's mask shape. The
        // original comment noted that a FIFTH "For " surface added without redact would
        // not appear here — which is exactly what happened: `pickup_issue_menu` shipped
        // with no redact block at all and leaked a fielded customer name. Its two dropoff
        // twins (`dropoff_issue_menu` / `dropoff_help_menu`) carried the same
        // "menu frames have no customer PII" reasoning and are now in the family too.
        // Still a PIN over explicit ids, not a discovery gate — a SIXTH surface is caught
        // at author/review time, not here.
        val forFamily = listOf(
            "doordash.screen.pickup_select_issue",
            "doordash.screen.pickup_resolution_options",
            "doordash.screen.pickup_unassigned_confirmation",
            "doordash.screen.pickup_unassign_survey",
            "doordash.screen.pickup_issue_menu",
            "doordash.screen.dropoff_issue_resolution",
            "doordash.screen.dropoff_issue_menu",
            "doordash.screen.dropoff_help_menu",
        )
        val maskShape = wholeMaskAfter("For ")
        for (id in forFamily) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)!!
            assertFalse("$id must carry a non-empty redact block (#809)", rule.redact.isEmpty())
            val masked = rule.redact
                .apply(UiNode(text = "For Jane Q Doe • STORENAME").restoreParents())
                .text!!
            assertFalse("$id: customer first name must not persist", masked.contains("Jane"))
            assertFalse("$id: customer last name must not persist", masked.contains("Doe"))
            assertTrue(
                "$id: fused header must mask to 'For [redacted:<4hex>]' (store tail dropped); got '$masked'",
                maskShape.matches(masked),
            )
        }
    }

    /**
     * #1031 — the FIFTH "For "-surface the family loop above could not discover.
     * `pickup_issue_menu` shipped with no `redact` block at all while its four sub-flow siblings
     * all masked the same fused header, so a recognized envelope carried the customer's
     * first-name + last-initial raw (fielded 2026-08-18).
     *
     * The MASK SHAPE is owned by the family loop (round 2 added this id and its two dropoff twins
     * to it, so one loop owns the class). What stays here is what only this rule needs: the
     * require anchor is chrome and must survive the mask, and the produced token must be
     * BYTE-EQUAL to its sibling's — a divergent copy would break the #623/#733 cross-surface
     * mask↔`customerNameHash` invariant, which is the whole reason the entry was copied verbatim
     * rather than re-derived.
     */
    @Test
    fun `pickup_issue_menu masks the fused For header its siblings already masked (#1031)`() {
        val rule = TestRulesetFactory.screenRuleset
            .ruleById("doordash.screen.pickup_issue_menu")!!
        assertFalse("pickup_issue_menu must carry a non-empty redact block (#1031)", rule.redact.isEmpty())

        // Node shape from the fielded frame: the require anchor beside the fused header.
        // Both VALUES are invented — the fielded name/store are not reproduced.
        val tree = UiNode(
            className = "android.view.View",
            children = listOf(
                UiNode(
                    className = "android.widget.TextView",
                    text = "What pickup issues can we help with?",
                ),
                UiNode(className = "android.widget.TextView", text = "For Jane Q • Sample Thai Kitchen"),
            ),
        ).restoreParents()
        val maskedTree = rule.redact.apply(tree)
        val serialized = serialize(maskedTree)
        assertFalse("customer name must not persist", serialized.contains("Jane"))
        assertTrue(
            "the require anchor is app chrome and stays raw",
            serialized.contains("What pickup issues can we help with?"),
        )

        val header = maskedTree.children[1].text!!
        assertTrue(
            "fused header must mask to 'For [redacted:<4hex>]' — the store tail rides the " +
                "remainder into the hash by design; got '$header'",
            wholeMaskAfter("For ").matches(header),
        )
        assertFalse("store tail must not survive the whole-remainder mask", header.contains("Sample Thai"))

        // Mask parity with the sibling it was copied from (#623/#733): the same customer must
        // redact to the same 4hex on every surface of the issue sub-flow.
        val sibling = TestRulesetFactory.screenRuleset
            .ruleById("doordash.screen.pickup_select_issue")!!
        assertEquals(
            "pickup_issue_menu must mask a customer to the same token its sibling does",
            sibling.redact.apply(UiNode(text = "For Jane Q • Sample Thai Kitchen").restoreParents()).text,
            header,
        )
    }

    // =========================================================================
    // #501 — the id-less first-name + last-initial customer-name shape. The
    // adversarial-review pledge finding: the runtime redact fail-OPEN on common
    // name shapes (accents, interior caps, hyphen/apostrophe, ALL-CAPS, trailing
    // space), and the corpus was scrubbed by a DIVERGENT (case-sensitive, trimmed)
    // masker. FIX 1c (SSOT parity), FIX 2 (injected-synthetic masking), FIX 4
    // (committed-corpus bare-name guard).
    // =========================================================================

    /** Every `hasTextMatchesRegex` string value anywhere inside [element]. */
    private fun collectRegexes(
        element: kotlinx.serialization.json.JsonElement,
        out: MutableList<String>,
    ) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> element.forEach { (k, v) ->
                if (k == "hasTextMatchesRegex" && v is kotlinx.serialization.json.JsonPrimitive && v.isString) {
                    out += v.content
                } else {
                    collectRegexes(v, out)
                }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { collectRegexes(it, out) }
            else -> {}
        }
    }

    /** The `hasTextMatchesRegex` strings in a screen rule's `redact` block (generated asset). */
    private fun ruleRedactRegexes(ruleId: String, platformFile: String = "doordash.json"): List<String> {
        val root = Json.parseToJsonElement(File(rulesDir, platformFile).readText()).jsonObject
        val screen = root["screens"]!!.jsonArray.first {
            it.jsonObject["id"]?.jsonPrimitive?.content == ruleId
        }.jsonObject
        val out = mutableListOf<String>()
        screen["redact"]?.jsonArray?.forEach { collectRegexes(it, out) }
        return out
    }

    @Test
    fun `the id-less name-shape redact pattern is SSOT with SnapshotRedactor (FIX 1c, #362 class)`() {
        // The canonical name-shape regex on the multi-order-confirm rule must be
        // byte-identical to SnapshotRedactor's constant — the two copies scrub the
        // same customer token (runtime envelope vs committed corpus) and must not
        // drift silently (the #362 duplicated-sha256 class of bug).
        assertTrue(
            "multi_order_confirm must carry the canonical name-shape regex identical to " +
                "SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN; found " +
                ruleRedactRegexes("doordash.screen.dropoff_multi_order_confirm"),
            ruleRedactRegexes("doordash.screen.dropoff_multi_order_confirm")
                .contains(SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN),
        )
        // FIX 3 defense-in-depth + #803: the earlier-priority rules that could win a
        // modal-over-handoff combined frame, AND the pin_entry surface whose id-less
        // body carries the same name shape, all carry the SAME canonical pattern.
        // #995 added three more copies, each of which MUST be pinned here — an unpinned
        // hand-copy is exactly the drift this test exists to stop, and the receipt-scan
        // copy shipped unpinned in the first cut of PR #1010:
        //  - pickup_receipt_scan   the new rule's bare-name entry (the primary control);
        //  - camera_capture        priority 86, so it BEATS the scan rule (141) whenever the
        //                          frame also exposes camera_preview/image_capture_view — its
        //                          old private `^[A-Za-z]{2,}( [A-Z]\.?){1,2}$` was a strict
        //                          SUBSET (literal single space → #885's double-space render
        //                          evaded it; ASCII-only → accented names evaded it);
        //  - pickup_wait_survey    priority 88, so it beats the scan rule on the transition
        //                          frame where survey anchors and scan nodes coexist.
        for (id in listOf(
            "doordash.screen.dropoff_navigation",
            "doordash.screen.dropoff_handoff",
            "doordash.screen.dropoff_pin_entry",
            "doordash.screen.pickup_receipt_scan",
            "doordash.screen.camera_capture",
            "doordash.screen.pickup_wait_survey",
        )) {
            assertTrue(
                "$id must carry the canonical name-shape regex (FIX 3 defense-in-depth)",
                ruleRedactRegexes(id).contains(SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN),
            )
        }
    }

    @Test
    fun `multi_order_confirm redact masks injected name shapes, keeps the require anchors (FIX 2)`() {
        val rule = TestRulesetFactory.screenRuleset
            .ruleById("doordash.screen.dropoff_multi_order_confirm")!!
        // Accents, interior caps, hyphen/apostrophe, ALL-CAPS (runtime IGNORE_CASE),
        // and a trailing space (the raw-text alignment) — every shape the pre-fix
        // masker fell open on.
        val names = listOf("Brandon C", "José R", "O'Brien M", "Mary-Jo K", "JOSE G", "Brandon C ", "McKenna B")
        val maskShape = MASK_HEX
        for (name in names) {
            val tree = UiNode(
                children = listOf(
                    UiNode(text = "Confirm you have the correct order before drop-off."),
                    UiNode(text = "Mix-ups frequently occur at drop-off when there are multiple orders in a Dash."),
                    UiNode(text = name), // the id-less customer-name line
                    UiNode(text = "SPROUTS FARMERS MARKET #118"),
                    UiNode(text = "2 items"),
                ),
            ).restoreParents()
            val masked = serialize(rule.redact.apply(tree))

            assertFalse("injected name '$name' must not persist", masked.contains(name.trim()))
            assertTrue("name '$name' masked to [redacted:<4hex>]", maskShape.containsMatchIn(masked))
            // Negative: the require anchors + merchant + count must survive untouched.
            assertTrue("require anchor kept", masked.contains("correct order before drop-off"))
            assertTrue("mix-ups anchor kept", masked.contains("Mix-ups frequently occur at drop-off"))
            assertTrue("merchant (driver-owned) kept", masked.contains("SPROUTS FARMERS MARKET #118"))
            assertTrue("count kept", masked.contains("2 items"))
        }
    }

    /**
     * FIX 4 — the committed-corpus PII guard. A leak that reaches git is permanent, so this is
     * the gate that makes a future intake of a REAL field pull fail rather than commit green.
     *
     * It runs THREE checks over an enumerated folder set, all reusing the intake scrubber's own
     * SSOTs so a widening there strengthens this guard automatically (#1010 review):
     *  1. the whole-value id-less name shape ([SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN]);
     *  2. a node whose view id is in [CustomerTextMarkers.ID_MARKERS] carrying a raw value —
     *     this is what catches an `arriving_at_title` street address (#993), which is one fused
     *     line the shape passes structurally cannot own. It reuses the RUNTIME id SSOT rather
     *     than [SnapshotRedactor.PII_ID_SUFFIXES] deliberately: `ID_MARKERS` is the enumeration
     *     of ids whose value is customer PII **by construction**, whereas the intake set is
     *     wider by design and includes ids that legitimately carry chrome (`subManeuverText`
     *     holds "Turn right " on seven committed legacy nav fixtures — masked at intake because
     *     it CAN carry a street, but not a leak when it doesn't). Flagging those here would be
     *     a false positive of exactly the kind this suite exists to keep out;
     *  3. a value opening with a [SnapshotRedactor.NAME_PREFIXES] lead-in and a non-masked tail —
     *     this is what catches `Return <real name> to <store>` (#994).
     *
     * **Decoys.** The #992–#995 fixtures deliberately carry hand-written pseudonyms in RAW,
     * PII-SHAPED form, because a fixture already masked to `[redacted:…]` cannot prove the
     * production redact fires on the tree DoorDash actually renders. Those exact strings are
     * enumerated in [CorpusDecoys] and skipped BY VALUE. Nothing is loosened: every other
     * name-shaped, PII-id-borne or prefix-led value in these folders still fails, so a real
     * customer name arriving in a future intake fails exactly as it should.
     */
    @Test
    fun `committed corpus carries no un-masked customer name, address, or lead-in (FIX 4)`() {
        val nameShape = Regex(SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN, RegexOption.IGNORE_CASE)
        // Folders whose owning rule declares one of the customer-PII redacts guarded here.
        // #825 added the uber trip surfaces (active_trip/splash/customer_chat/
        // pickup_verification_items) — each carries the id-less name-shape redact.
        // #992–#995 added the four surfaces this PR fixed; without them the guard did not
        // cover the very folders whose rules were just given a redact, which is where the
        // next real pull will land.
        val folders = listOf(
            "dropoff_multi_order_confirm", "dropoff_navigation", "dropoff_handoff",
            "active_trip", "splash", "customer_chat", "pickup_verification_items",
            "pickup_receipt_scan", "pickup_wait_survey", "timeline", "camera_capture",
            // #985 added the Timeline order-detail sheet: an entirely id-less surface whose
            // whole redact block is shape-anchored, so the committed-corpus guard is the only
            // structural check that its fixtures don't carry a real customer.
            "timeline_task_detail",
            // #1031/#1039 (round 2) add the surfaces THIS PR gave a redact to. Same reasoning as
            // the #992–#995 batch: the next real pull lands in exactly the folders whose rules
            // were just changed, so leaving them outside the guard is where a leak would commit
            // green. `pickup_issue_menu` is the #1031 fused `For <customer>` header; the three
            // dropoff folders are where the label-split subpremise row renders. (There is no
            // committed `dropoff_pre_arrival_completion` folder — nothing to guard yet.)
            "pickup_issue_menu", "dropoff_pre_arrival", "dropoff_pin_entry",
            "dropoff_issue_menu", "dropoff_help_menu",
        )
        val leaks = mutableListOf<String>()
        val decoysSeen = mutableSetOf<String>()

        for (folder in folders) {
            for ((filename, node, _) in TestResourceLoader.loadSnapshots("snapshots/$folder")) {
                walkNodes(node) { n ->
                    val idIsPii = CustomerTextMarkers.ID_MARKERS.any {
                        n.viewIdResourceName?.endsWith(it, ignoreCase = true) == true
                    }
                    val idSuffix = n.viewIdResourceName?.substringAfterLast('/')
                    n.scrubbableStrings().forEach { (_, raw) ->
                        val text = raw ?: return@forEach
                        if (text.isBlank() || text.contains("[redacted")) return@forEach
                        if (CorpusDecoys.isDecoy(text)) {
                            decoysSeen += text
                            return@forEach
                        }
                        val why = when {
                            idIsPii -> "PII view id '$idSuffix' carries a raw value"
                            nameShape.matches(text) -> "whole-value customer-name shape"
                            SnapshotRedactor.NAME_PREFIXES.any {
                                text.startsWith(it, ignoreCase = true) && text.length > it.length
                            } -> "customer lead-in prefix with a raw tail"
                            else -> null
                        }
                        if (why != null) leaks += "$folder/$filename: $why — \"$text\""
                    }
                }
            }
        }
        assertTrue(
            "committed corpus leaks un-masked customer PII (a leak in git is permanent; this is " +
                "the gate that makes a real field pull fail instead of committing green): $leaks",
            leaks.isEmpty(),
        )
        // A decoy that no longer appears anywhere is a stale exemption: it would silently widen
        // the hole for whatever string later happens to equal it. Deleting a fixture must delete
        // its CorpusDecoys entry.
        assertEquals(
            "every CorpusDecoys entry must still be reachable in the corpus; unreachable: " +
                (CorpusDecoys.ALLOWED.keys - decoysSeen),
            CorpusDecoys.ALLOWED.keys,
            decoysSeen,
        )
    }

    /** Visit every node in the tree (the guard needs the node, not just its strings). */
    private fun walkNodes(node: UiNode, visit: (UiNode) -> Unit) {
        visit(node)
        node.children.forEach { walkNodes(it, visit) }
    }

    /** Visit every serialized string field of every node (the #835 SSOT). */
    private fun walkText(node: UiNode, visit: (String) -> Unit) {
        node.scrubbableStrings().forEach { (_, value) -> value?.let(visit) }
        node.children.forEach { walkText(it, visit) }
    }

    // =========================================================================
    // #803 — the id-less delivery-instructions-body blind class. The rule redact
    // is the PRIMARY (runtime-edge) control: it masks the WHOLE offending node, so
    // a gate-code / PIN fragment takes the embedded customer name with it. These
    // build a synthetic live (un-redacted) capture, confirm recognition still lands
    // on the surface, then assert its redact masks the body while the require
    // anchors survive — redaction is capture-only, never touching parse (both rules
    // are parse-less; the goldens are byte-identical).
    // =========================================================================

    @Test
    fun `dropoff_pin_entry redact masks the id-less instructions body PIN, gate code, and embedded name (#803)`() {
        val tree = UiNode(
            viewIdResourceName = "com.dd:id/drop_off_workflow_host_fragment",
            children = listOf(
                UiNode(text = "Hand it to customer"),
                // The id-less free-text instructions body — the blind class: no viewId,
                // an embedded full name, plus the gate code + PIN that reached disk raw.
                UiNode(text = "John Smith gate code 8834 pin 4821"),
                UiNode(viewIdResourceName = "com.dd:id/step_title", text = "Collect PIN from customer"),
                UiNode(viewIdResourceName = "com.dd:id/step_description", text = "Ask John for the entry code"),
            ),
        ).restoreParents()

        val match = TestRulesetFactory.screenRuleset.matchFirst(tree)
        assertEquals("doordash.screen.dropoff_pin_entry", match?.ruleId)
        val rule = TestRulesetFactory.screenRuleset.ruleById(match!!.ruleId)!!
        assertFalse("pin_entry must now carry a redact block", rule.redact.isEmpty())

        val masked = serialize(rule.redact.apply(tree))
        assertFalse("PIN must not persist", masked.contains("4821"))
        assertFalse("gate code must not persist", masked.contains("8834"))
        assertFalse("embedded customer name must not persist", masked.contains("John Smith"))
        assertFalse("step_description instruction must not persist", masked.contains("Ask John"))
        assertTrue("require anchor (step_title) kept", masked.contains("Collect PIN from customer"))
    }

    @Test
    fun `dropoff_pin_entry single-glyph keypad echo masks plain, not a brute-forceable hash (#889)`() {
        // THE FIELDED DEFECT: this surface's id-less unit/entry-code entry
        // (`^#?\s?\d{1,4}[A-Za-z]?$`) also matches a 22x36px ONE-CHARACTER keypad echo,
        // which shipped as `[redacted:5fec]` — a 1-glyph space inverts from 4 hex in
        // ~100 guesses. The #889 engine floor degrades any sub-4-char token to the plain
        // constant, rule-independently, so no `plainMask` declaration is required here.
        val tree = UiNode(
            viewIdResourceName = "com.dd:id/drop_off_workflow_host_fragment",
            children = listOf(
                UiNode(text = "5"), // the id-less single-glyph keypad echo
                UiNode(text = "#7"), // a 2-char unit shape — same class
                UiNode(viewIdResourceName = "com.dd:id/step_title", text = "Collect PIN from customer"),
            ),
        ).restoreParents()

        // #889 F1: the COMPLETED 4-digit PIN clears the engine's length floor, so the
        // rule's own `plainMask` is what covers it — a 10^4 space against 65 536 4-hex
        // buckets is ~85% injective. pin_entry (priority 63) wins the frame over
        // dropoff_pin_keypad (109), so pin_entry's own digit entry must declare it.
        val completed = UiNode(
            viewIdResourceName = "com.dd:id/drop_off_workflow_host_fragment",
            children = listOf(
                UiNode(text = "9315"), // the whole-PIN echo, id-less
                UiNode(viewIdResourceName = "com.dd:id/step_title", text = "Collect PIN from customer"),
            ),
        ).restoreParents()
        val completedMatch = TestRulesetFactory.screenRuleset.matchFirst(completed)
        assertEquals(
            "the completed-PIN frame is claimed by pin_entry, not pin_keypad",
            "doordash.screen.dropoff_pin_entry",
            completedMatch?.ruleId,
        )
        val completedMasked = serialize(
            TestRulesetFactory.screenRuleset.ruleById(completedMatch!!.ruleId)!!.redact.apply(completed),
        )
        assertFalse("the completed PIN must not persist", completedMasked.contains("9315"))
        assertFalse(
            "a 4-digit PIN must not carry a reversible distinctness hash",
            MASK_HEX.containsMatchIn(completedMasked),
        )

        // Drive the PRODUCTION rule (mutation teeth: severing the require anchors or the
        // redact entry breaks this case, not just the floor).
        val match = TestRulesetFactory.screenRuleset.matchFirst(tree)
        assertEquals("doordash.screen.dropoff_pin_entry", match?.ruleId)
        val rule = TestRulesetFactory.screenRuleset.ruleById(match!!.ruleId)!!

        val redacted = rule.redact.apply(tree)
        assertEquals("single glyph masked plain", "[redacted]", redacted.children[0].text)
        assertEquals("2-char unit masked plain", "[redacted]", redacted.children[1].text)
        val masked = serialize(redacted)
        assertFalse(
            "a short token must carry NO reversible distinctness hash",
            MASK_HEX.containsMatchIn(masked),
        )
        assertTrue("require anchor (step_title) kept", masked.contains("Collect PIN from customer"))
    }

    @Test
    fun `dropoff_pin_entry redact masks pin colon-fused variants and a bare gate code (#803 F1-F3)`() {
        // Each body is the id-less instructions node; the token variant must mask the
        // WHOLE body (embedded name "Casey Doe" included). Covers the colon / fused /
        // bare-gate shapes the first-cut regex missed.
        val bodies = listOf(
            "Casey Doe PIN: 4821 at the back",
            "Casey Doe pin:4821",
            "Casey Doe Pin4821",
            "Casey Doe gate 4821 then knock", // bare gate, no "code" token
            "Casey Doe Gate: 4821",
        )
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_pin_entry")!!
        for (body in bodies) {
            val tree = UiNode(
                viewIdResourceName = "com.dd:id/drop_off_workflow_host_fragment",
                children = listOf(
                    UiNode(text = "Hand it to customer"),
                    UiNode(text = body),
                    UiNode(viewIdResourceName = "com.dd:id/step_title", text = "Collect PIN from customer"),
                ),
            ).restoreParents()
            val masked = serialize(rule.redact.apply(tree))
            assertFalse("'$body' -> code must not persist: $masked", masked.contains("4821"))
            assertFalse("'$body' -> embedded name must not persist: $masked", masked.contains("Casey Doe"))
        }
    }

    @Test
    fun `dropoff_pin_keypad plain-masks the entered PIN with no reversible distinctness hash (#795)`() {
        // The "Enter PIN" keypad renders the entered PIN as raw node text: an id-less
        // EditText carrying the whole PIN plus one id-less TextView per pressed digit.
        val tree = UiNode(
            viewIdResourceName = "com.dd:id/drop_off_workflow_host_fragment",
            children = listOf(
                UiNode(text = "Enter PIN"),
                UiNode(
                    text = "Before dropping off the order, enter a 4-digit PIN provided by " +
                        "the customer to confirm you've successfully delivered their order.",
                ),
                UiNode(text = "1234"), // the whole-PIN EditText echo
                UiNode(text = "1"),
                UiNode(text = "2"),
                UiNode(text = "3"),
                UiNode(text = "4"),
                UiNode(viewIdResourceName = "com.dd:id/textView_prism_button_title", text = "Submit"),
            ),
        ).restoreParents()

        // matchFirst on the production ruleset must land on THIS rule — gives the
        // require anchors mutation teeth (sever the redact and this whole case still
        // exercises the live rule, not a hand-built entry).
        val match = TestRulesetFactory.screenRuleset.matchFirst(tree)
        assertEquals("doordash.screen.dropoff_pin_keypad", match?.ruleId)
        val rule = TestRulesetFactory.screenRuleset.ruleById(match!!.ruleId)!!
        assertFalse("pin_keypad must carry a redact block", rule.redact.isEmpty())

        val masked = serialize(rule.redact.apply(tree))
        // Every PIN digit node is gone: the whole-PIN EditText echo + all four single
        // digits. Five id-less pure-digit nodes → at least five plain masks.
        assertFalse("whole-PIN node must not persist", masked.contains("1234"))
        assertTrue(
            "every id-less digit node plain-masked (EditText + 4 digits)",
            PLAIN_MASK.findAll(masked).count() >= 5,
        )
        // The plain constant is present but the reversible `<4hex>` distinctness form is
        // NOT — this gives the `plainMask: true` flag itself mutation teeth (a 4-digit
        // PIN is recoverable from 4 hex, so the hashed form would leak it).
        assertFalse(
            "PIN must not carry a reversible distinctness hash",
            MASK_HEX.containsMatchIn(masked),
        )
        // The require/anchor texts survive unmasked.
        assertTrue("Enter PIN anchor kept", masked.contains("Enter PIN"))
        assertTrue("Submit anchor kept", masked.contains("Submit"))
    }

    @Test
    fun `dropoff_handoff redact masks the id-less instructions body gate code and PIN (#803)`() {
        val tree = UiNode(
            viewIdResourceName = "com.dd:id/drop_off_workflow_host_fragment",
            children = listOf(
                UiNode(text = "hand it to customer"),
                UiNode(text = "Meet me at the back. gate code 5567 pin 9032"),
                UiNode(viewIdResourceName = "com.dd:id/step_description", text = "Text Jane on arrival"),
                UiNode(text = "Complete Delivery"), // arrival CTA discriminator
            ),
        ).restoreParents()

        val match = TestRulesetFactory.screenRuleset.matchFirst(tree)
        assertEquals("doordash.screen.dropoff_handoff", match?.ruleId)
        val rule = TestRulesetFactory.screenRuleset.ruleById(match!!.ruleId)!!

        val masked = serialize(rule.redact.apply(tree))
        assertFalse("gate code must not persist", masked.contains("5567"))
        assertFalse("PIN must not persist", masked.contains("9032"))
        assertFalse("step_description instruction must not persist", masked.contains("Text Jane"))
        assertTrue("arrival CTA anchor kept", masked.contains("Complete Delivery"))
    }

    // =========================================================================
    // #825 — the uber `active_trip` bare-node customer-PII Pledge leak. The
    // fielded on-job card renders the customer first-name+last-initial, street,
    // gate code and unit as id-less nodes with NO lead-in marker, so the
    // CustomerTextMarkers prefix backstop can't scrub them and the rule declared
    // no `redact` — raw customer PII shipped to 4 recognized capture envelopes on
    // 07-21. These pin the fix at the runtime capture edge: an injected synthetic
    // live (un-redacted) capture must mask every PII node while the recognition
    // anchors survive. Redaction is capture-only (these rules are parse-less; the
    // goldens are byte-identical). Same cross-platform content-shape SSOT as #803.
    // =========================================================================

    private val uberNameHex = java.security.MessageDigest.getInstance("SHA-256")
        .digest("casey t".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(4)

    @Test
    fun `uber active_trip redact masks the bare name, street, gate code, unit, and id-anchored address (#825)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("uber.screen.active_trip")!!
        assertFalse("active_trip must now carry a redact block", rule.redact.isEmpty())

        val tree = UiNode(
            viewIdResourceName = "com.ubercab.driver:id/on_job_view", // recognition anchor
            children = listOf(
                UiNode(text = "Dropoff • 1 order"), // benign — must survive
                UiNode(text = "Casey T."), // bare id-less customer name
                UiNode(text = "42 Nowhere Ln"), // bare id-less street (fabricated)
                UiNode(text = "Gate code 8888"), // customer gate code (fabricated)
                UiNode(text = "Expected by 7:17 PM"), // benign — must survive
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/ub__nav_address_view_poi_text",
                    text = "42 Nowhere Ln",
                ),
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/ub__nav_address_view_address_text",
                    text = "Springfield, ZZ 00000",
                ),
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/key_info_label",
                    text = "Casey T.",
                ),
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/map_marker_title",
                    text = "Apt 8888",
                ),
                // #825 review HIGH-2: the map pin carries the address in contentDescription ONLY.
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/map_marker_pin_head",
                    contentDescription = "42 Nowhere Ln, Springfield ZZ",
                ),
            ),
        ).restoreParents()

        val masked = serialize(rule.redact.apply(tree))
        assertFalse("customer first name must not persist", masked.contains("Casey"))
        assertFalse("street name must not persist", masked.contains("Nowhere"))
        assertFalse("gate/unit number must not persist", masked.contains("8888"))
        assertFalse("zip must not persist", masked.contains("00000"))
        // Recognition anchors + benign chrome survive untouched (replay-safe).
        assertTrue("on_job_view anchor kept", masked.contains("on_job_view"))
        assertTrue("benign order line kept", masked.contains("Dropoff • 1 order"))
        assertTrue("benign ETA kept", masked.contains("Expected by 7:17 PM"))
        // #895: the fused unit number is a bounded ~10^4 alphabet over the length floor —
        // the rule declares plainMask, so the marker keeps NO 4-hex suffix (a revert to the
        // brute-recoverable hash form goes red here).
        assertTrue("apt marker kept, plain-masked", masked.contains("Apt [redacted]"))
        assertFalse("apt mask must not carry a distinctness hex", masked.contains("Apt [redacted:"))
    }

    /**
     * #895 — every fused-`Apt ` keepPrefix entry across BOTH platforms' surface
     * families must declare `plainMask`: the post-strip unit token is a bounded
     * ~10^4 alphabet over the #889 length floor, so a 4-hex suffix would be
     * brute-recoverable. The 8 entries are byte-identical SSOT siblings today;
     * this ratchet keeps a future edit from silently splitting one back to the
     * hash form (only `active_trip`'s entry has direct mask-output teeth above).
     *
     * #986/#934 extend it to the **`Apt/Suite` half of the family**, which the original
     * enumeration structurally could not see: `dropoff_photo`'s entry keeps the FUSED
     * `"Apt/Suite "` spelling (so `keepPrefix.contains("Apt ")` was false) and the
     * `address_subpremise_line` / `bottom_sheet_subpremise_line` entries are id-anchored
     * with a `"Apt/Suite: "` label (or, on `dropoff_geofence_warning`, no label at all).
     * Both spellings are now enumerated per rule here; the exhaustive scan below is what
     * makes the enumeration un-rottable.
     */
    @Test
    fun `every Apt and Apt-Suite redact entry declares plainMask on both platforms (#895, #986, #934)`() {
        // Family A — the fused `Apt <n>` line (#895 / PR #927).
        val fusedAptRules = listOf(
            "uber.screen.splash",
            "uber.screen.pickup_verification_items",
            "uber.screen.customer_chat",
            "uber.screen.active_trip",
            "doordash.screen.dropoff_pin_entry",
            "doordash.screen.dropoff_handoff",
            "doordash.screen.dropoff_pre_arrival",
            "doordash.screen.dropoff_pre_arrival_completion",
        )
        for (id in fusedAptRules) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)
            org.junit.Assert.assertNotNull("rule $id must exist", rule)
            val aptEntries = rule!!.redact.entries.filter { it.keepPrefix.contains("Apt ") }
            assertFalse("$id must carry an 'Apt ' keepPrefix entry", aptEntries.isEmpty())
            assertTrue(
                "$id: every fused-Apt entry must declare plainMask (#895 — bounded alphabet over the length floor)",
                aptEntries.all { it.plainMask },
            )
        }

        // Family B — the `Apt/Suite` subpremise line (#986 fused-text, #934 id-anchored).
        // `dropoff_pre_arrival` appears in BOTH lists on purpose: it fielded the plain form
        // (08-02, family A) and the hex form (08-01, family B) on different dashes, which is
        // the whole reason this had to be fixed as a family rather than an entry.
        // #1039 (round 2) extends the list with the rules the blanket declaration newly covers
        // and that the review named as the ones most likely to WIN an address-block frame: the
        // two surfaces that FIELDED the label-split row (`dropoff_pin_entry` /
        // `dropoff_handoff` — `dropoff_pre_arrival` was already here), the fourth member of the
        // fused-`Apt ` set (`dropoff_pre_arrival_completion`), and the nav catch-all
        // (`navigation_generic`, which lives in nav-comms.json5, so it is outside the dropoff
        // parity test's own source scan). Exhaustiveness is NOT this list's job — the structural
        // scan below and the #1039 parity test own that; this stays the named-rules pin.
        val subpremiseRules = listOf(
            "doordash.screen.dropoff_navigation",
            "doordash.screen.dropoff_photo",
            "doordash.screen.dropoff_pre_arrival",
            "doordash.screen.dropoff_geofence_warning",
            "doordash.screen.delivery_summary_collapsed",
            "doordash.screen.dropoff_pin_entry",
            "doordash.screen.dropoff_handoff",
            "doordash.screen.dropoff_pre_arrival_completion",
            "doordash.screen.navigation_generic",
        )
        for (id in subpremiseRules) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)
            org.junit.Assert.assertNotNull("rule $id must exist", rule)
            // Identified from the generated asset, because the compiled `find` is an opaque
            // lambda: an entry is a unit-number entry when its predicate names a subpremise id
            // or its keepPrefix carries an "Apt/Suite" label.
            val entries = unitNumberRedactEntries(id, platformFileFor(id))
            assertFalse("$id must carry an Apt/Suite subpremise redact entry", entries.isEmpty())
            assertTrue(
                "$id: every Apt/Suite subpremise entry must declare plainMask (#986/#934 — a unit " +
                    "number is the same bounded ~10^4 alphabet the fused `Apt ` entries carry): $entries",
                entries.all { it.plainMask },
            )
        }
    }

    /**
     * #986/#934 — the **exhaustive** half of the ratchet above, and the part that cannot rot.
     *
     * The enumerated lists say "these known rules are fixed"; #986 existed precisely because an
     * enumeration missed a 9th surface whose prefix happened to be spelled differently. So this
     * scans EVERY screen rule's redact block across BOTH generated platform assets and requires
     * `plainMask` on any entry that can mask a bare unit number — identified structurally, by the
     * entry's own predicate/keepPrefix strings mentioning `Apt` or a `subpremise_line` id, never
     * by a rule list. A new surface that copies the address block in gets covered the day it
     * lands, whichever spelling it uses.
     *
     * `comment` values are excluded from the scan: they discuss `Apt` freely (e.g.
     * `camera_capture`'s `bottom_instruction` entry, which masks a whole fused name+apt line and
     * is correctly NOT in this family — its token carries a customer name, so the alphabet is
     * unbounded and the hash form is right there).
     */
    @Test
    fun `no redact entry can mask a bare unit number with a brute-forceable hash (#986, #934)`() {
        val offenders = mutableListOf<String>()
        var found = 0
        for (platformFile in listOf("doordash.json", "uber.json")) {
            val screens = Json.parseToJsonElement(File(rulesDir, platformFile).readText())
                .jsonObject["screens"]!!.jsonArray
            for (screen in screens) {
                val obj = screen.jsonObject
                val ruleId = obj["id"]?.jsonPrimitive?.content ?: continue
                val redact = obj["redact"] as? kotlinx.serialization.json.JsonArray ?: continue
                redact.forEachIndexed { i, element ->
                    val entry = element.jsonObject
                    if (!isUnitNumberEntry(entry)) return@forEachIndexed
                    found++
                    val plain = (entry["plainMask"] as? JsonPrimitive)?.content == "true"
                    if (!plain) offenders += "$platformFile $ruleId redact[$i]"
                }
            }
        }
        assertTrue(
            "a unit-number redact entry without plainMask ships a `[redacted:<4hex>]` suffix over " +
                "a ~10^4 alphabet — brute-recoverable (#895/#892/#795): $offenders",
            offenders.isEmpty(),
        )
        // Vacuity floor. If a refactor stops the scan from SEEING the family, this fails instead
        // of passing green over nothing. Post-#1039-round-2 breakdown (62):
        //   •  4 — uber fused `Apt ` entries (splash / pickup_verification_items /
        //          customer_chat / active_trip)
        //   •  4 — doordash fused `Apt ` entries (dropoff_pin_entry / _handoff / _pre_arrival /
        //          _pre_arrival_completion)
        //   •  4 — doordash id-anchored subpremise entries (`bottom_sheet_subpremise_line` on
        //          dropoff_navigation; `address_subpremise_line` on dropoff_pre_arrival /
        //          dropoff_geofence_warning / delivery_summary_collapsed)
        //   • 25 — the fused `Apt/Suite` entry, now blanket-declared on all 24 screen rules in
        //          dropoff.json5 plus navigation_generic (#993 combined-frame doctrine)
        //   • 25 — the `Apt/Suite` label-sibling entry, on the same 25 rules
        assertTrue(
            "expected at least 62 unit-number redact entries across both platforms, saw $found — " +
                "the scan is no longer finding the family it is meant to police",
            found >= 62,
        )
    }

    /** True when [entry]'s own predicate/keepPrefix strings mark it as a unit-number mask. */
    private fun isUnitNumberEntry(entry: kotlinx.serialization.json.JsonObject): Boolean {
        val strings = mutableListOf<String>()
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is kotlinx.serialization.json.JsonObject -> e.forEach { (k, v) ->
                    if (k != "comment") walk(v)
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it) }
                is JsonPrimitive -> if (e.isString) strings += e.content
            }
        }
        walk(entry)
        return strings.any {
            Regex("""\bApt\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) ||
                it.endsWith("subpremise_line")
        }
    }

    /** The compiled unit-number entries of [ruleId] (matched via the generated-asset shape). */
    private fun unitNumberRedactEntries(ruleId: String, platformFile: String): List<CompiledRedactEntry> {
        val declared = ruleJson(ruleId, platformFile)["redact"] as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        val compiled = TestRulesetFactory.screenRuleset.ruleById(ruleId)!!.redact.entries
        assertEquals(
            "$ruleId: declared and compiled redact entries must correspond 1:1",
            declared.size,
            compiled.size,
        )
        return declared.mapIndexedNotNull { i, e ->
            compiled[i].takeIf { isUnitNumberEntry(e.jsonObject) }
        }
    }

    private fun platformFileFor(ruleId: String): String =
        if (ruleId.startsWith("uber.")) "uber.json" else "doordash.json"

    /**
     * #986/#934 teeth — the ratchets above read declarations; this runs the compiled redacts
     * over a real subpremise node and asserts the OUTPUT carries no distinctness suffix. Reverting
     * any one `plainMask` turns this red with the recoverable mask in the failure message.
     */
    @Test
    fun `every Apt-Suite subpremise redact masks plain, with no distinctness hex (#986, #934)`() {
        // id-anchored (#934) — the label is app vocabulary and stays; the unit number does not.
        val byId = mapOf(
            "doordash.screen.dropoff_navigation" to "bottom_sheet_subpremise_line",
            "doordash.screen.dropoff_pre_arrival" to "address_subpremise_line",
            "doordash.screen.dropoff_geofence_warning" to "address_subpremise_line",
            "doordash.screen.delivery_summary_collapsed" to "address_subpremise_line",
        )
        for ((ruleId, idSuffix) in byId) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(ruleId)!!
            val masked = rule.redact.apply(
                UiNode(viewIdResourceName = "com.dd:id/$idSuffix", text = "Apt/Suite: 8888"),
            ).text!!
            assertFalse("$ruleId: unit number must not persist", masked.contains("8888"))
            assertFalse(
                "$ruleId: '$idSuffix' must not carry a brute-forceable distinctness hex — got '$masked'",
                MASK_HEX.containsMatchIn(masked),
            )
            assertTrue("$ruleId: plain mask expected — got '$masked'", PLAIN_MASK.containsMatchIn(masked))
        }
        // `dropoff_geofence_warning` declares no keepPrefix, so it masks the WHOLE line; the other
        // three keep the label. Both shapes are correct, and both are pinned.
        assertTrue(
            "dropoff_navigation keeps the Apt/Suite label",
            TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_navigation")!!
                .redact.apply(
                    UiNode(viewIdResourceName = "com.dd:id/bottom_sheet_subpremise_line", text = "Apt/Suite: 8888"),
                ).text!!.startsWith("Apt/Suite: "),
        )

        // #986 as filed — the fused-text entry on `dropoff_photo`, whose prefix is `Apt/Suite `.
        val photo = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_photo")!!
        for (raw in listOf("Apt/Suite 8888", "Apt/Suite: 8888")) {
            val masked = photo.redact.apply(UiNode(text = raw)).text!!
            assertFalse("dropoff_photo: unit number must not persist from '$raw'", masked.contains("8888"))
            assertFalse(
                "dropoff_photo: '$raw' must not carry a distinctness hex — got '$masked'",
                MASK_HEX.containsMatchIn(masked),
            )
            assertTrue("dropoff_photo: label kept for '$raw' — got '$masked'", masked.startsWith("Apt/Suite"))
        }
    }

    /**
     * The screen rules that must declare the #1039 subpremise pair, DERIVED — never hand-listed.
     *
     * Round 1 of PR #1042 pinned four ids, which is the enumeration failure #986 already paid for
     * once: a redact protects only the frames its OWN rule wins (the #993 combined-frame
     * doctrine), and the rules most likely to claim an address-block frame — `dropoff_navigation`
     * (61), `delivery_summary_collapsed` (31), `dropoff_geofence_warning` (84), `dropoff_photo`
     * (62) — all OUT-RANK the four that were patched. So the invariant is the strong one, exactly
     * as [DropoffBannerRedactParityTest] states it for the arrival banner: **every screen rule in
     * the dropoff section declares both entries**, read out of the source file so a new rule is in
     * scope the day it lands.
     *
     * `navigation_generic` is appended because it is in the same class and lives in a different
     * source file: the store-ambiguous nav catch-all can win a frame carrying the dropoff address
     * block, which is why #886 and #993 already declare entries on it for the same reason.
     */
    private fun subpremiseParityRuleIds(): List<String> {
        val declared = Regex(""""id":\s*"(doordash\.screen\.[A-Za-z_0-9]+)"""")
            .findAll(File("../matchers/rules/doordash/dropoff.json5").readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("expected the dropoff section to declare screen rules", declared.isNotEmpty())
        return declared + "doordash.screen.navigation_generic"
    }

    /**
     * True when [entry] names its node by TEXT SHAPE rather than by id or label — the entries that
     * can claim a subpremise VALUE out from under the #1039 label anchor. `maskNode` is
     * first-match-wins per node, so a digit-leading value like `101 B` matches the id-less street
     * shape `^\d{1,5}\s+\S` and gets HASHED, which is the brute-recoverable token the #1039 entry
     * exists to remove. `comment` values are skipped (they discuss these predicates in prose).
     */
    private fun isGenericShapeEntry(entry: kotlinx.serialization.json.JsonObject): Boolean {
        var hit = false
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is kotlinx.serialization.json.JsonObject -> e.forEach { (k, v) ->
                    if (k == "comment") return@forEach
                    if (k == "hasTextMatchesRegex" || k == "hasNoId" ||
                        k == "hasFollowingSiblingTextMatchesRegex"
                    ) {
                        hit = true
                    }
                    if (k == "hasTextStartsWith" && (v as? JsonPrimitive)?.content == "\"") hit = true
                    walk(v)
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it) }
                else -> {}
            }
        }
        walk(entry)
        return hit
    }

    /**
     * #1039 — the family's remaining hole was the value SPELLING, not the entry set. A
     * label-split subpremise row renders `<"Apt/Suite" TextView><"Unit 4202" TextView>`, and that
     * value is invisible to both older masks: the fused entry wants a value LEADING with `Apt `,
     * the id-less shape entry wants a value that IS bare digits. Two recognized envelopes shipped
     * it raw (dropoff_handoff + dropoff_pre_arrival, 2026-08-14) two days after the #986/#934
     * install, and the #986 structural scan cannot see the class at all — it ratchets entries that
     * EXIST, so a missing spelling is invisible to it.
     *
     * This is the DECLARATION half of the ratchet (the mask itself is pinned below). Three things
     * are asserted per rule, all read from the generated asset:
     *  1. the fused `Apt/Suite` entry exists (the committed `dropoff_pre_arrival` fixtures prove
     *     that render is real, and it is id-LESS);
     *  2. the label-sibling entry exists AND enumerates BOTH label spellings — the predicate
     *     compiles to an exact, UN-TRIMMED `equals(ignoreCase)`, and this family has already
     *     fielded the colon render, so `Apt/Suite:` alone would silently miss;
     *  3. both sit AHEAD of every generic shape entry in their block (see [isGenericShapeEntry]) —
     *     ordering is behaviour here, not style.
     */
    @Test
    fun `every dropoff-section rule declares both Apt-Suite subpremise redacts, ordered first (#1039)`() {
        val missingFused = mutableListOf<String>()
        val missingLabel = mutableListOf<String>()
        val misordered = mutableListOf<String>()

        for (ruleId in subpremiseParityRuleIds()) {
            val entries = ruleJson(ruleId)["redact"] as? kotlinx.serialization.json.JsonArray
            if (entries == null) {
                missingFused += ruleId
                missingLabel += ruleId
                continue
            }
            var fusedAt = -1
            var labelAt = -1
            var firstGeneric = -1
            entries.forEachIndexed { i, element ->
                val entry = element.jsonObject
                val starts = mutableListOf<String>()
                collectStringsUnder(entry, "hasTextStartsWith", starts)
                val siblings = mutableListOf<String>()
                collectStringsUnder(entry, "hasPrecedingSiblingText", siblings)
                if (fusedAt < 0 && "Apt/Suite" in starts) fusedAt = i
                if (labelAt < 0 && siblings.containsAll(listOf("Apt/Suite", "Apt/Suite:"))) labelAt = i
                if (firstGeneric < 0 && isGenericShapeEntry(entry)) firstGeneric = i
            }
            if (fusedAt < 0) missingFused += ruleId
            if (labelAt < 0) missingLabel += ruleId
            if (firstGeneric >= 0 && fusedAt > firstGeneric) {
                misordered += "$ruleId: fused entry at $fusedAt, first generic shape at $firstGeneric"
            }
            if (firstGeneric >= 0 && labelAt > firstGeneric) {
                misordered += "$ruleId: label entry at $labelAt, first generic shape at $firstGeneric"
            }
        }

        assertEquals(
            "every screen rule in matchers/rules/doordash/dropoff.json5 (plus navigation_generic) " +
                "must declare the FUSED `Apt/Suite` subpremise redact — the banner-class argument " +
                "(#993) applies verbatim: a redact protects only the frames its own rule wins, and " +
                "the entry is inert when the line is absent. Missing: $missingFused",
            emptyList<String>(),
            missingFused,
        )
        assertEquals(
            "every screen rule in matchers/rules/doordash/dropoff.json5 (plus navigation_generic) " +
                "must declare the `Apt/Suite` LABEL-SIBLING redact with BOTH label spellings " +
                "(`hasPrecedingSiblingText` is an exact, un-trimmed equality). Missing or " +
                "single-spelling: $missingLabel",
            emptyList<String>(),
            missingLabel,
        )
        assertEquals(
            "the #1039 entries must precede every generic SHAPE entry in their own block — " +
                "`maskNode` is first-match-wins, so a digit-leading subpremise value ('101 B') " +
                "placed after the id-less street shape gets a brute-recoverable `[redacted:<4hex>]` " +
                "instead of a plain mask: $misordered",
            emptyList<String>(),
            misordered,
        )
    }

    /**
     * #1039 teeth — the parity test above reads declarations; this runs the compiled redacts over
     * the fielded split row on the two surfaces that actually shipped it raw (2026-08-14).
     * Mutation checks: delete the entry and (a)/(d) go red; drop its `plainMask` and the no-hex
     * assertions go red; move it after the street-shape entry and (c) goes red. (e) is the
     * over-match guard — the anchor must name the subpremise value and nothing else in the block.
     */
    @Test
    fun `the Apt-Suite label sibling plain-masks any subpremise spelling, Unit included (#1039)`() {
        val fielded = listOf(
            "doordash.screen.dropoff_handoff",
            "doordash.screen.dropoff_pre_arrival",
        )

        /** The fielded split row: an id-less label TextView followed by its id-less value. */
        fun labelledRow(label: String, value: String) = UiNode(
            className = "android.view.View",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = label),
                UiNode(className = "android.widget.TextView", text = value),
            ),
        ).restoreParents()

        for (id in fielded) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)!!

            fun maskedValue(label: String, value: String): String =
                rule.redact.apply(labelledRow(label, value)).children[1].text!!

            fun assertPlainMasked(what: String, masked: String, secret: String) {
                assertFalse("$id: $what must not persist; got '$masked'", masked.contains(secret))
                assertFalse(
                    "$id: $what must not carry a brute-forceable distinctness hex " +
                        "(#895/#986 bounded alphabet) — got '$masked'",
                    MASK_HEX.containsMatchIn(masked),
                )
                assertTrue("$id: plain mask expected for $what — got '$masked'", PLAIN_MASK.containsMatchIn(masked))
            }

            // (a) the fielded evasion — the `Unit <n>` spelling.
            assertPlainMasked("the 'Unit 4202' value", maskedValue("Apt/Suite", "Unit 4202"), "4202")

            // (b) the `Apt <n>` spelling in the same split row still masks. Either entry may win —
            // the pin is on the OUTCOME, not on which one fired: no digits, no distinctness hex.
            assertPlainMasked("the 'Apt 4202' value", maskedValue("Apt/Suite", "Apt 4202"), "4202")

            // (c) the ORDERING ratchet, in behaviour: a digit-LEADING value also matches the
            // id-less street shape `^\d{1,5}\s+\S`, which hashes. First match wins, so this is
            // plain only while the label entry sits ahead of that one.
            assertPlainMasked("the digit-leading '101 B' value", maskedValue("Apt/Suite", "101 B"), "101")

            // (d) the COLON label render. `hasPrecedingSiblingText` is an exact, un-trimmed
            // equality, so the second enumerated spelling is what covers this.
            assertPlainMasked("the colon-labelled value", maskedValue("Apt/Suite:", "Unit 4202"), "4202")

            // (e) over-match guard — a non-subpremise label/value pair is untouched by the new
            // entry (the anchor is the label string, not "any second child").
            assertEquals(
                "$id: a non-subpremise labelled value must not be masked by the label anchor",
                "Sunset Ridge",
                maskedValue("Business name", "Sunset Ridge"),
            )

            // The bare `Apt/Suite` LABEL node is itself an exact prefix match for the fused entry
            // and has no keepPrefix to keep, so it masks whole to plain `[redacted]`. That is
            // ACCEPTED collateral, pinned here so it stays deliberate: two words of app chrome
            // lost on a debug envelope, the same fail-toward-privacy trade
            // `DropoffBannerRedactParityTest` documents for the `Arriving at` label — and it
            // cannot break the anchor above, because redact predicates are evaluated against the
            // ORIGINAL tree while the mask lands on a copy (which (a)/(c)/(d) prove).
            assertEquals(
                "$id: the bare label masks as documented collateral of the fused entry",
                CompiledRedact.REDACTED,
                rule.redact.apply(labelledRow("Apt/Suite", "Unit 4202")).children[0].text,
            )
        }
    }

    @Test
    fun `uber active_trip bare-name redact carries the normalize customerName distinctness hex (#825)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("uber.screen.active_trip")!!
        // The bare id-less name node masks via the normalize:customerName entry, so
        // its 4hex equals sha256 of the canonical key ("casey t") — per-customer
        // stable across the surface forms the name renders in.
        val masked = rule.redact.apply(UiNode(text = "Casey T.")).text!!
        assertFalse("name must not persist", masked.contains("Casey"))
        assertEquals("normalize:customerName distinctness hex", "[redacted:$uberNameHex]", masked)
    }

    @Test
    fun `every uber trip-surface name redact is SSOT with SnapshotRedactor FIRST_LAST_INITIAL (#825, MED-5)`() {
        // All four surfaces copy the id-less name-shape regex; pin every copy to the SSOT so a
        // silent drift on any one (the #362 duplicated-regex class) turns the build red.
        for (id in listOf(
            "uber.screen.active_trip",
            "uber.screen.splash",
            "uber.screen.customer_chat",
            "uber.screen.pickup_verification_items",
        )) {
            assertTrue(
                "$id must carry the canonical id-less name-shape regex byte-identical to " +
                    "SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN; found " +
                    ruleRedactRegexes(id, "uber.json"),
                ruleRedactRegexes(id, "uber.json")
                    .contains(SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN),
            )
        }
    }

    @Test
    fun `uber splash redact masks trip-underlay street, address, and bare name (#813 via #825)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("uber.screen.splash")!!
        assertFalse("splash must now carry a redact block", rule.redact.isEmpty())
        val tree = UiNode(
            viewIdResourceName = "com.ubercab.driver:id/rootSplashBackground", // recognition anchor
            children = listOf(
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/ub__nav_maneuver_text",
                    text = "Turn right onto Nowhere Canyon Drive",
                ),
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/ub__current_street_name_tv",
                    text = "Nowhere Canyon Drive",
                ),
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/ub__nav_address_view_address_text",
                    text = "Springfield, ZZ 00000",
                ),
                UiNode(text = "Casey T."), // bare id-less name
                UiNode(text = "42 Nowhere Ln"), // bare id-less street (fabricated)
            ),
        ).restoreParents()
        val masked = serialize(rule.redact.apply(tree))
        assertFalse("customer name must not persist", masked.contains("Casey"))
        assertFalse("street name must not persist", masked.contains("Nowhere"))
        assertFalse("zip must not persist", masked.contains("00000"))
        assertTrue("rootSplashBackground anchor kept", masked.contains("rootSplashBackground"))
    }

    @Test
    fun `uber customer_chat redact masks the chat header name and gate-code instructions (#825)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("uber.screen.customer_chat")!!
        assertFalse("customer_chat must now carry a redact block", rule.redact.isEmpty())
        val tree = UiNode(
            viewIdResourceName = "com.ubercab.driver:id/on_job_view",
            children = listOf(
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/ub__chat_header_title",
                    text = "Casey T.",
                ),
                // HIGH-1: the chat header also renders as "<Name I.> says:" in headline_text.
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/headline_text",
                    text = "Casey T. says:",
                ),
                UiNode(text = "• Leave at door\n• Gate code 8888"), // delivery instructions
                // MED-3: chat wins over active_trip, so the trip underlay's id-bearing address
                // must be scrubbed here too.
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/ub__nav_address_view_poi_text",
                    text = "42 Nowhere Ln",
                ),
                // HIGH-2: the map pin carries the address in contentDescription only.
                UiNode(
                    viewIdResourceName = "com.ubercab.driver:id/map_marker_pin_head",
                    contentDescription = "42 Nowhere Ln, Springfield ZZ",
                ),
                UiNode(text = "Verify order"), // benign — survives
            ),
        ).restoreParents()
        val masked = serialize(rule.redact.apply(tree))
        assertFalse("chat header customer name must not persist", masked.contains("Casey"))
        assertFalse("headline_text customer name must not persist (HIGH-1)", masked.contains("Casey"))
        assertFalse("gate code must not persist", masked.contains("8888"))
        assertFalse("trip-underlay address must not persist (MED-3/HIGH-2)", masked.contains("Nowhere"))
        assertTrue("benign chrome kept", masked.contains("Verify order"))

        // HIGH-1 normalize proof: "<Name> says:" must mask to the SAME distinctness hex as the bare
        // name — i.e. customerNameKey strips the " says:" tail (→ "casey t"), so the chat header and
        // its "says:" echo are ONE customer, not two.
        val headline = rule.redact.apply(
            UiNode(viewIdResourceName = "com.ubercab.driver:id/headline_text", text = "Casey T. says:"),
        ).text!!
        assertEquals("headline_text normalizes the ' says:' tail to the canonical key", "[redacted:$uberNameHex]", headline)
    }

    @Test
    fun `uber pickup_verification_items redact masks the bare customer name (#825)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("uber.screen.pickup_verification_items")!!
        assertFalse("pickup_verification_items must now carry a redact block", rule.redact.isEmpty())
        val tree = UiNode(
            viewIdResourceName = "com.ubercab.driver:id/on_job_view",
            children = listOf(
                UiNode(text = "Verify order"), // recognition anchor — survives
                UiNode(text = "Casey T."), // bare id-less customer name
            ),
        ).restoreParents()
        val masked = serialize(rule.redact.apply(tree))
        assertFalse("customer name must not persist", masked.contains("Casey"))
        assertTrue("Verify order anchor kept", masked.contains("Verify order"))
    }

    @Test
    fun `every screen rule that hashes PII declares a redact block`() {
        var checked = 0
        rulesDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["screens"]?.jsonArray?.forEach { screen ->
                val obj = screen.jsonObject
                if (jsonUsesSha256(obj)) {
                    checked++
                    val redact = obj["redact"]?.jsonArray
                    assertTrue(
                        "screen rule '${obj["id"]?.jsonPrimitive?.content}' hashes PII (sha256) " +
                            "but declares no non-empty redact block (#598)",
                        redact != null && redact.isNotEmpty(),
                    )
                }
            }
        }
        // Guard the guard: the 4 known sha256 dropoff/timeline rules must be seen.
        assertTrue("expected at least the 4 known sha256 screen rules", checked >= 4)
    }

    /**
     * #745 mask↔hash REVERSE-consistency: for every screen rule that parses `customerNameHash`, any
     * `redact` entry whose `find` targets the SAME node as a parse-field `find` MUST carry
     * `normalize: customerName`. Otherwise the capture mask hex (derived from the raw token) would
     * diverge from the persisted hash (derived from the canonical key) — the #623 invariant breaks and
     * a customer masks differently on the capture than they hash in the record. Structural: keyed on
     * exact `find`-JSON equality (the reliable proxy for "same node"). Fails with rule id + entry index.
     */
    @Test
    fun `every redact entry over a hashed customer-name node carries normalize customerName (#745)`() {
        var checked = 0
        rulesDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["screens"]?.jsonArray?.forEach { screen ->
                val rule = screen.jsonObject
                val id = rule["id"]?.jsonPrimitive?.content ?: "<no-id>"
                // Collect every `find` predicate used ANYWHERE under this rule's customerNameHash parse.
                val parseFinds = mutableSetOf<kotlinx.serialization.json.JsonElement>()
                collectNameHashFinds(rule["parse"], parseFinds)
                if (parseFinds.isEmpty()) return@forEach
                val redact = rule["redact"]?.jsonArray ?: return@forEach
                redact.forEachIndexed { i, entryEl ->
                    val entry = entryEl.jsonObject
                    val find = entry["find"] ?: return@forEachIndexed
                    if (find in parseFinds) {
                        checked++
                        val normalize = entry["normalize"]?.jsonPrimitive?.content
                        assertEquals(
                            "$id redact[$i] masks the SAME node its customerNameHash parse hashes, so " +
                                "it MUST carry \"normalize\": \"customerName\" (mask↔hash invariant, #745)",
                            "customerName", normalize,
                        )
                    }
                }
            }
        }
        assertTrue("expected at least one hashed-customer-name redact entry to verify", checked >= 1)
    }

    /** Recursively collect every `find` JSON element that appears under a `customerNameHash` field. */
    private fun collectNameHashFinds(
        el: kotlinx.serialization.json.JsonElement?,
        out: MutableSet<kotlinx.serialization.json.JsonElement>,
    ) {
        when (el) {
            is kotlinx.serialization.json.JsonObject -> {
                el["customerNameHash"]?.let { collectFinds(it, out) }
                el.values.forEach { collectNameHashFinds(it, out) }
            }
            is kotlinx.serialization.json.JsonArray -> el.forEach { collectNameHashFinds(it, out) }
            else -> {}
        }
    }

    /** Collect every `find` value within a subtree (a customerNameHash field's own + coalesce branches). */
    private fun collectFinds(
        el: kotlinx.serialization.json.JsonElement,
        out: MutableSet<kotlinx.serialization.json.JsonElement>,
    ) {
        when (el) {
            is kotlinx.serialization.json.JsonObject -> {
                el["find"]?.let { out.add(it) }
                el.forEach { (k, v) -> if (k != "find") collectFinds(v, out) }
            }
            is kotlinx.serialization.json.JsonArray -> el.forEach { collectFinds(it, out) }
            else -> {}
        }
    }

    @Test
    fun `the four known sha256 rules expose a redact block via ruleById`() {
        val ids = listOf(
            "doordash.screen.timeline",
            "doordash.screen.dropoff_navigation",
            "doordash.screen.dropoff_pre_arrival",
            "doordash.screen.dropoff_pre_arrival_completion",
        )
        for (id in ids) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)!!
            assertFalse("$id must carry a redact block", rule.redact.isEmpty())
        }
    }

    // =========================================================================
    // #620 — production notification redact blocks
    // =========================================================================

    private fun notif(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        tickerText: String? = null,
        channelId: String? = null,
    ) = RawNotificationData(
        title = title, text = text, bigText = bigText, tickerText = tickerText,
        packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
        channelId = channelId,
    )

    @Test
    fun `production customer_message redact masks the sender and body (#620)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("doordash.notification.customer_message")!!
        assertTrue("customer_message must carry a notif redact block", !rule.notifRedact.isEmpty())
        val masked = rule.notifRedact.apply(
            notif(
                title = "Message from Jennifer",
                text = "The gate code is 4412",
                tickerText = "The gate code is 4412",
                channelId = "dasher-notification-channel-inapp-chat",
            ),
        )
        assertFalse("sender name gone", masked.title!!.contains("Jennifer"))
        assertTrue(maskAfter("Message from ").containsMatchIn(masked.title!!))
        assertFalse("body gone (text)", masked.text!!.contains("4412"))
        assertFalse("body gone (tickerText)", masked.tickerText!!.contains("4412"))
    }

    @Test
    fun `production order_ready redact masks the customer name but keeps the store (#620)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("doordash.notification.order_ready")!!
        assertTrue("order_ready must carry a notif redact block", !rule.notifRedact.isEmpty())
        val masked = rule.notifRedact.apply(
            notif(
                title = "Delivery Update",
                text = "Adam's order is ready for pickup at 7-Eleven.",
                channelId = "dasher-notification-channel-delivery-update",
            ),
        )
        assertFalse("customer name gone", masked.text!!.contains("Adam"))
        assertTrue("store kept — merchants are not PII", masked.text!!.contains("7-Eleven"))
        assertEquals("title (non-PII) untouched", "Delivery Update", masked.title)
    }

    @Test
    fun `a benign notification rule declares no notif redact (#620)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("doordash.notification.new_order")!!
        assertTrue("new_order carries no customer PII → no redact", rule.notifRedact.isEmpty())
    }

    @Test
    fun `production uber trip_en_route_dropoff masks the address title (#620 F1)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("uber.notification.trip_en_route_dropoff")!!
        assertTrue("trip_en_route_dropoff must carry a notif redact block", !rule.notifRedact.isEmpty())
        // The title IS the customer address ("Going to <address>"); mask the whole field.
        val masked = rule.notifRedact.apply(notif(title = "Going to 1600 Amphitheatre Pkwy"))
        assertFalse("street name gone", masked.title!!.contains("Amphitheatre"))
        assertFalse("house number gone", masked.title!!.contains("1600"))
        assertTrue(WHOLE_MASK_HEX.matches(masked.title!!))
    }

    @Test
    fun `production uber trip_at_dropoff masks the address or name, keeps the lead-in (#620 F1)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("uber.notification.trip_at_dropoff")!!
        assertTrue("trip_at_dropoff must carry a notif redact block", !rule.notifRedact.isEmpty())
        val leave = rule.notifRedact.apply(notif(title = "Leave the order at 1600 Amphitheatre Pkwy"))
        assertFalse("address gone", leave.title!!.contains("Amphitheatre"))
        assertTrue(wholeMaskAfter("Leave the order at ").matches(leave.title!!))
        val meet = rule.notifRedact.apply(notif(title = "Meet at door for Jane Doe"))
        assertFalse("customer name gone", meet.title!!.contains("Jane"))
        assertTrue(wholeMaskAfter("Meet at door for ").matches(meet.title!!))
    }

    /**
     * #910 V1 — `delivery_summary_collapsed` shipped with NO redact block, and it is
     * not only the pay-breakdown card: its require is a text-only
     * `allTextContainsAny:['this offer','delivery complete']`, which a LIVE DROP-OFF
     * sheet satisfies, so 4 of the 8 fielded 07-28 envelopes under this classification
     * carried the customer block whole — name, house-number street line, city/ST/ZIP,
     * the dasher instruction body, and the customer's per-item note. `CustomerTextMarkers`
     * cannot own any of it: the "Delivery for" lead-in is a SEPARATE `user_name_label`
     * sibling, so the value nodes are bare and a prefix scan is structurally blind.
     *
     * Node shape is ground truth from that capture (the id set only); every VALUE below
     * is invented. Teeth: delete any entry from the rule's redact block and its
     * assertion here goes RED.
     */
    @Test
    fun `delivery_summary_collapsed masks the customer block, keeps merchant and pay (#910)`() {
        val rule = TestRulesetFactory.screenRuleset
            .ruleById("doordash.screen.delivery_summary_collapsed")!!
        assertFalse("delivery_summary_collapsed must declare a redact block", rule.redact.isEmpty())

        fun dd(id: String, text: String) = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/$id",
            className = "android.widget.TextView",
            text = text,
        )

        val card = UiNode(
            className = "android.view.View",
            children = listOf(
                // The customer block (split nodes: chrome label, then bare value).
                dd("user_name_label", "Delivery for"),
                dd("user_name", "Testname Q"),
                dd("address_line_1", "1234 Testfixture Dr"),
                dd("address_line_2", "San Antonio, TX 78299, USA"),
                dd("address_subpremise_line", "Apt/Suite: 704"),
                dd("dasher_instruction_content_collapsed", "Leave at my door: gate 4417"),
                dd("dasher_instruction_content_expanded", "Leave at my door: gate 4417, call on arrival"),
                dd("order_item_instructions", "test gate code 1234, ring the bell twice"),
                // Over-match guards: merchant, catalog, chrome and pay are driver-owned.
                dd("instructions_title", "Dropoff instructions"),
                dd("order_contents_header_title", "Sample Pizza Co (41709)"),
                dd("order_item_name", "Large Pepperoni"),
                dd("order_item_count", "1x"),
                dd("current_order_pay", "$8.25"),
                dd("final_value", "$8.25"),
                dd("textView_prism_button_title", "Complete delivery"),
            ),
        ).restoreParents()

        val masked = serialize(rule.redact.apply(card))

        assertFalse("customer name must not persist", masked.contains("Testname Q"))
        assertFalse("street line must not persist", masked.contains("1234 Testfixture Dr"))
        assertFalse("city/ST/ZIP must not persist", masked.contains("78299"))
        assertFalse("apt/suite value must not persist", masked.contains("704"))
        assertFalse("gate code in the instruction body must not persist", masked.contains("gate 4417"))
        assertFalse("customer item note must not persist", masked.contains("ring the bell twice"))
        assertTrue(
            "the masked nodes carry the hash family [redacted:<4hex>]",
            MASK_HEX.containsMatchIn(masked),
        )
        // The label sibling is app vocabulary — kept, so a replayed frame keeps its shape.
        assertTrue("the 'Delivery for' label is chrome and stays raw", masked.contains("Delivery for"))
        assertTrue("Apt/Suite lead-in kept", masked.contains("Apt/Suite: "))
        // #934: and the unit number behind that lead-in plain-masks — a bounded ~10^4 alphabet
        // must not ship the brute-recoverable `<4hex>` distinctness suffix.
        assertTrue("Apt/Suite value plain-masked", masked.contains("Apt/Suite: [redacted]"))
        assertFalse("Apt/Suite value must carry no distinctness hex", masked.contains("Apt/Suite: [redacted:"))
        // Over-match guards.
        assertTrue("merchant kept (driver-owned)", masked.contains("Sample Pizza Co (41709)"))
        assertTrue("catalog item kept", masked.contains("Large Pepperoni"))
        assertTrue("instruction LABEL kept", masked.contains("Dropoff instructions"))
        assertTrue("pay kept", masked.contains("$8.25"))
        assertTrue("CTA kept", masked.contains("Complete delivery"))

        // #733 cross-surface stability: the name's hex must equal the SAME customer's hex on
        // the "Deliver to <name>" nav title — one customer, one mask, every surface.
        val nameHex = MASK_HEX.find(rule.redact.apply(dd("user_name", "Testname Q")).text!!)!!.groupValues[1]
        val navRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_navigation")!!
        val navHex = MASK_HEX.find(
            navRule.redact.apply(
                UiNode(
                    viewIdResourceName = "com.dd:id/bottom_sheet_task_title",
                    text = "Deliver to Testname Quill",
                ),
            ).text!!,
        )!!.groupValues[1]
        assertEquals("user_name masks to the customer's canonical hex", navHex, nameHex)
    }

    /**
     * #910 V2/V3 — the multi-order pickup list renders one row per order, each with a
     * BARE `customer_name` node (no in-node lead-in: "Order for" is a separate sibling
     * on the single-order card, the #526 D6a shape). Both `pickup_post_arrival_multi`
     * and `pickup_pre_arrival_multi` shipped with no redact block, so every row's
     * customer name reached the recognized envelope raw (three of them on the fielded
     * 07-28 frame).
     *
     * The post-arrival case runs over the COMMITTED corpus fixture with its two
     * already-masked names replaced by distinct synthetic ones, so it also pins that
     * two customers on one screen get two DIFFERENT suffixes (per-customer replay
     * fidelity, #623). The pre-arrival twin has no committed fixture, so it asserts the
     * same shape directly.
     *
     * Teeth: delete either rule's redact entry and that rule's assertion goes RED.
     */
    @Test
    fun `multi-order pickup rules mask every customer_name row, keep the merchant (#910)`() {

        // --- post_arrival: the committed fixture, re-seeded with synthetic names ---
        val postRule = TestRulesetFactory.screenRuleset
            .ruleById("doordash.screen.pickup_post_arrival_multi")!!
        assertFalse("pickup_post_arrival_multi must declare a redact block", postRule.redact.isEmpty())

        val fixture = File("src/test/resources/snapshots/pickup_post_arrival_multi")
            .listFiles { _, n -> n.endsWith(".json") }!!
            .sorted().first()
        val names = ArrayDeque(listOf("Testname Q", "Othername R"))
        fun reseed(node: UiNode): UiNode = node.copy(
            text = if (node.viewIdResourceName?.endsWith("customer_name") == true && names.isNotEmpty()) {
                names.removeFirst()
            } else {
                node.text
            },
            children = node.children.map { reseed(it) },
        )
        val seeded = reseed(TestResourceLoader.loadNode(fixture)).restoreParents()
        assertTrue("fixture must carry the two customer_name rows", names.isEmpty())

        val maskedPost = serialize(postRule.redact.apply(seeded))
        assertFalse("first customer name must not persist", maskedPost.contains("Testname Q"))
        assertFalse("second customer name must not persist", maskedPost.contains("Othername R"))
        assertTrue("merchant kept (driver-owned)", maskedPost.contains("Pei Wei"))
        assertTrue("row item count kept", maskedPost.contains("1 item"))

        fun rowHex(rule: cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact, name: String): String {
            val masked = rule.apply(
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/customer_name",
                    text = name,
                ),
            ).text!!
            return MASK_HEX.find(masked)?.groupValues?.get(1)
                ?: error("customer_name was not masked; got '$masked'")
        }
        assertFalse(
            "two customers on one screen must not collide on one mask",
            rowHex(postRule.redact, "Testname Q") == rowHex(postRule.redact, "Othername R"),
        )

        // --- pre_arrival twin: same list, one CTA state earlier; no committed fixture ---
        val preRule = TestRulesetFactory.screenRuleset
            .ruleById("doordash.screen.pickup_pre_arrival_multi")!!
        assertFalse("pickup_pre_arrival_multi must declare a redact block", preRule.redact.isEmpty())

        val row = UiNode(
            className = "android.view.View",
            children = listOf(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Testname Q"),
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/merchant_name", text = "Sample Pizza Co"),
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/num_items", text = "2 items"),
            ),
        ).restoreParents()
        val maskedPre = serialize(preRule.redact.apply(row))
        assertFalse("customer name must not persist", maskedPre.contains("Testname Q"))
        assertTrue("merchant kept (driver-owned)", maskedPre.contains("Sample Pizza Co"))
        assertTrue("item count kept", maskedPre.contains("2 items"))

        // #733: one customer, one hex — across BOTH multi surfaces and the single-order card.
        val arrivalRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_arrival")!!
        assertEquals(
            "the pre/post multi rows mask to the same customer hex",
            rowHex(postRule.redact, "Testname Q"),
            rowHex(preRule.redact, "Testname Q"),
        )
        assertEquals(
            "and to the same hex the single-order pickup card produces",
            rowHex(arrivalRule.redact, "Testname Q"),
            rowHex(postRule.redact, "Testname Q"),
        )
    }

    /**
     * #992 — `pickup_wait_survey` shipped with NO redact block at all while its
     * at-store contact card carries a bare `customer_name` node, so a RECOGNIZED
     * surface persisted the customer's name verbatim (2 fielded 08-07 envelopes).
     * `CustomerTextMarkers.ID_MARKERS` lists `customer_name`, but that scan is
     * UNKNOWN-envelope-only by design (#910) and the text-marker scan is blind to a
     * bare node ("Order for" is a separate `customer_name_label` sibling), so nothing
     * backstopped it.
     *
     * Node shape mirrors the fielded `at_store_contact_view` block; every value is
     * invented. Teeth: delete the rule's redact entry and the name assertion goes RED.
     */
    @Test
    fun `pickup_wait_survey masks the customer_name node, keeps merchant and chrome (#992)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_wait_survey")!!
        assertFalse("pickup_wait_survey must declare a redact block", rule.redact.isEmpty())

        fun card(name: String) = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/at_store_contact_view",
            className = "android.view.View",
            children = listOf(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name_label", text = "Order for"),
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = name),
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/instructions_title", text = "Sample Bowls Co"),
                UiNode(text = "Tell us what's causing your wait"),
            ),
        ).restoreParents()

        val masked = serialize(rule.redact.apply(card("Testname Q")))
        assertFalse("customer name must not persist", masked.contains("Testname Q"))
        assertTrue(
            "the name masks to the hash family [redacted:<4hex>]",
            MASK_HEX.containsMatchIn(masked),
        )
        // Over-match guards: the label is chrome, the merchant is driver-owned, the
        // survey copy is platform vocabulary — all three stay raw.
        assertTrue("label kept (app chrome)", masked.contains("Order for"))
        assertTrue("merchant kept (driver-owned)", masked.contains("Sample Bowls Co"))
        assertTrue("survey copy kept", masked.contains("Tell us what's causing your wait"))

        // #733: one customer, one hex — this surface must agree with the single-order
        // pickup card, which is what `normalize: customerName` on the entry buys.
        fun nameHex(r: CompiledRedact): String {
            val out = r.apply(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Testname Q"),
            ).text!!
            return hexIn(out, "customer_name")
        }
        assertEquals(
            "the wait survey masks to the same hex as pickup_arrival",
            nameHex(TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_arrival")!!.redact),
            nameHex(rule.redact),
        )
    }

    /**
     * #993 — DoorDash's own arrival banner (`arriving_at_title`) can inflate while the
     * dropoff workflow sheet is still on screen, and `dropoff_navigation` wins that
     * combined frame. Its redact covered the sheet's address lines and (since #886) the
     * embedded Google-Nav maneuver cluster, but NOT the banner — so the customer's full
     * street address shipped raw beside its own correctly-masked copy (fielded 08-02).
     * The mask already existed on the sibling `nav_arriving`; it just did not exist here.
     *
     * Teeth: delete the `arriving_at_title` entry and the banner assertion goes RED.
     * The pickup-side counterpart stays raw by design (#886, merchant address).
     */
    @Test
    fun `dropoff_navigation masks the arriving_at banner title, keeps its subtitle (#993)`() {
        val address = "742 Sample Hollow Way, Apt 12, San Antonio, TX 78260, USA"

        fun banner() = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/top_header",
            className = "android.view.View",
            children = listOf(
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/arriving_at_subtitle",
                    text = "Arriving at",
                ),
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/arriving_at_title",
                    text = address,
                ),
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/bottom_sheet_arrived_header_v2",
                    text = "Arriving soon",
                ),
            ),
        ).restoreParents()

        val dropoffRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_navigation")!!
        val masked = serialize(dropoffRule.redact.apply(banner()))
        assertFalse("banner street address must not persist", masked.contains("Sample Hollow Way"))
        assertFalse("banner apt must not persist", masked.contains("Apt 12"))
        assertTrue(
            "the banner title masks to the hash family [redacted:<4hex>]",
            MASK_HEX.containsMatchIn(masked),
        )
        // Over-match guards: the label and the sheet header are app chrome.
        assertTrue("subtitle kept (app chrome)", masked.contains("Arriving at"))
        assertTrue("sheet header kept (app chrome)", masked.contains("Arriving soon"))

        // Distinctness (#623): two destinations must not collide on one mask.
        fun titleHex(text: String): String {
            val out = dropoffRule.redact.apply(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/arriving_at_title", text = text),
            ).text!!
            return hexIn(out, "arriving_at_title")
        }
        assertFalse(
            "different destinations must redact to different suffixes",
            titleHex(address) == titleHex("15 Other Sample Rd, San Antonio, TX 78260, USA"),
        )

        // The pickup-side twin restates a MERCHANT address and is deliberately left raw (#886).
        val pickupRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_navigation")!!
        assertTrue(
            "pickup_navigation's merchant destination stays raw by design",
            serialize(pickupRule.redact.apply(banner())).contains("Sample Hollow Way"),
        )
    }

    /**
     * #994 — a RETURN order renders the timeline task line as
     * "Return <FirstName L> to <store>", a FOURTH conjugation that matched none of the
     * three enumerated prefixes, so the name shipped raw on a RECOGNIZED surface (3
     * fielded 08-01 envelopes). Same shape as #962's "Delivery to " miss.
     *
     * Two properties are load-bearing and both are pinned here. (1) The keepPrefix mask
     * is whole-remainder, so the trailing " to <store>" is masked along with the name —
     * accepted (screen redacts have no regex-capture form) and asserted so the behaviour
     * is deliberate, not incidental. (2) The #623 mask↔hash invariant SURVIVES that tail
     * because `normalize: customerName` canonicalizes to first-token + second-token
     * initial: customerNameKey("Riley P to H-E-B") == customerNameKey("Riley P").
     *
     * Teeth: drop "Return " from either the `find` or the `keepPrefix` list and this goes RED.
     */
    @Test
    fun `timeline masks the Return conjugation and keeps one hex per customer (#994)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.timeline")!!

        val masked = serialize(
            rule.redact.apply(UiNode(text = "Return Testname Q to Sample Grocery Co").restoreParents()),
        )
        assertFalse("customer name must not persist", masked.contains("Testname Q"))
        assertTrue(
            "the 'Return ' lead-in is kept so the line still reads as a return task",
            maskAfter("Return ").containsMatchIn(masked),
        )
        // The whole-remainder mask swallows the store tail — documented, not incidental.
        assertFalse("the masked remainder covers the ' to <store>' tail", masked.contains("Sample Grocery Co"))

        // #623/#733 cross-conjugation invariance: one customer, one hex, whichever verb
        // the platform used — this is what the shared `normalize: customerName` buys.
        fun lineHex(text: String): String {
            val out = rule.redact.apply(UiNode(text = text).restoreParents()).text!!
            return hexIn(out, "timeline line")
        }
        val deliverHex = lineHex("Deliver to Testname Q")
        assertEquals(
            "the return conjugation masks to the same hex as 'Deliver to' — the store tail is " +
                "canonicalized away by customerNameKey (first token + second-token initial)",
            deliverHex,
            lineHex("Return Testname Q to Sample Grocery Co"),
        )
        assertEquals(
            "and to the same hex as 'Pickup for'",
            deliverHex,
            lineHex("Pickup for Testname Q"),
        )
        // Over-match guard: the STORE line keeps its own no-normalize entry, so a
        // "Pickup from <store>" node must NOT be swept into the customer-name entry.
        assertTrue(
            "the store line keeps its 'Pickup from ' lead-in",
            maskAfter("Pickup from ").containsMatchIn(
                serialize(rule.redact.apply(UiNode(text = "Pickup from Sample Grocery Co").restoreParents())),
            ),
        )
    }

    /**
     * #995 — the receipt-scan camera surface persisted the customer's name TWICE per
     * frame on id-less nodes ("Focus on <FirstName L>" plus a bare "<FirstName L>"
     * sibling), and NOTHING reached it: no view id (`ID_MARKERS` misses), no enumerated
     * prefix (`MARKERS` misses — and "Focus on " was vetted and rejected as
     * chrome-ambiguous, see `CustomerTextMarkers`' KDoc), and no rule. The new
     * `pickup_receipt_scan` rule is the one control, so it carries all the teeth.
     *
     * Node shape is ground truth from the 6 fielded 08-07 envelopes (fully id-less below
     * `camera_fragment_container`); every value is invented.
     */
    @Test
    fun `pickup_receipt_scan masks both id-less name nodes, keeps the scan chrome (#995)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_receipt_scan")!!
        assertFalse("pickup_receipt_scan must declare a redact block", rule.redact.isEmpty())

        fun scanScreen(name: String) = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/camera_fragment_container",
            className = "android.view.View",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Focus on $name"),
                UiNode(className = "android.widget.TextView", text = "Scan customer name"),
                UiNode(className = "android.widget.TextView", text = name),
                UiNode(className = "android.widget.TextView", text = "#fddcf · DoorDash"),
                UiNode(className = "android.widget.TextView", text = "No receipt"),
                UiNode(className = "android.widget.TextView", text = "Scan receipt"),
                UiNode(
                    className = "android.widget.TextView",
                    text = "Scan the order details by focusing on the customer name. " +
                        "We'll share this scan as a photo with the customer.",
                ),
                UiNode(className = "android.widget.TextView", text = "Start scanning"),
                UiNode(className = "android.widget.TextView", text = "Pickup complete"),
                UiNode(className = "android.widget.TextView", text = "Continue"),
                UiNode(className = "android.widget.TextView", text = "0:05"),
            ),
        ).restoreParents()

        val applied = rule.redact.apply(scanScreen("Testname Q"))
        val masked = serialize(applied)
        // BOTH copies of the name are gone — the instruction line and the bare sibling.
        assertFalse("customer name must not persist anywhere", masked.contains("Testname Q"))
        assertTrue(
            "the instruction line keeps its 'Focus on ' lead-in",
            maskAfter("Focus on ").containsMatchIn(masked),
        )
        assertTrue(
            "the bare name node masks WHOLE to the hash family [redacted:<4hex>], " +
                "got '${applied.children[2].text}'",
            WHOLE_MASK_HEX.matches(applied.children[2].text!!),
        )
        // Over-match guards: every other node on this surface is platform chrome and
        // must survive — these are also the rule's own recognition anchors.
        for (kept in listOf(
            "Scan customer name",
            "Scan the order details by focusing on the customer name",
            "Scan receipt",
            "Start scanning",
            "No receipt",
            "Pickup complete",
            "Continue",
            "0:05",
            "#fddcf · DoorDash",
        )) {
            assertTrue("chrome kept: '$kept'", masked.contains(kept))
        }

        // #733: one customer, one hex — across BOTH nodes on this surface AND the pickup
        // card, so a receipt-scan capture correlates with the rest of the same job.
        val focusHex = hexIn(applied.children[0].text!!, "the Focus-on line")
        val bareHex = hexIn(applied.children[2].text!!, "the bare name node")
        assertEquals("both name nodes on one frame mask to one hex", focusHex, bareHex)
        val arrivalHex = MASK_HEX.find(
            TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_arrival")!!.redact.apply(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Testname Q"),
            ).text!!,
        )!!.groupValues[1]
        assertEquals("and to the customer's hex on the pickup card", arrivalHex, focusHex)
    }

    /**
     * #995 on the REAL trees (#1010 review F9). The synthetic case above proves the predicates
     * against a node shape the test author typed; this runs them over the two committed fixtures,
     * which still carry their (sanitized, but structurally faithful) name nodes RAW — so it proves
     * the entries match DoorDash's actual id-less render, and it is what would catch a layout
     * change that, say, gives the name node an id or splits the instruction line.
     *
     * The pseudonym is an enumerated [CorpusDecoys] entry, so the corpus PII guard skips it by
     * value while this test still gets a raw tree to mask.
     */
    @Test
    fun `pickup_receipt_scan redact masks both name nodes on the committed fixtures (#995)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_receipt_scan")!!
        val decoy = "Jordan T"
        val files = File("src/test/resources/snapshots/pickup_receipt_scan")
            .listFiles { _, n -> n.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("the receipt-scan corpus must not be empty", files.isNotEmpty())

        for (file in files) {
            val real = TestResourceLoader.loadNode(file)
            // Pre-condition: raw input, and it recognizes as this rule (no vacuous pass).
            assertTrue(
                "${file.name} must carry the raw name (else this test proves nothing)",
                serialize(real).contains(decoy),
            )
            assertEquals(
                "${file.name} must recognize as pickup_receipt_scan",
                "doordash.screen.pickup_receipt_scan",
                TestRulesetFactory.screenRuleset.matchFirst(real)?.ruleId,
            )

            val masked = serialize(rule.redact.apply(real))
            assertFalse("${file.name}: the name must not persist anywhere", masked.contains(decoy))
            assertTrue(
                "${file.name}: the instruction line keeps its 'Focus on ' lead-in",
                maskAfter("Focus on ").containsMatchIn(masked),
            )
            // Both copies masked to ONE hex — on the real tree, not a hand-built one.
            val hexes = MASK_HEX.findAll(masked).map { it.groupValues[1] }.toSet()
            assertEquals("${file.name}: both name nodes mask to one customer hex", 1, hexes.size)
            // Over-match guards: the rule's own recognition anchors must survive redaction, or a
            // replayed envelope would stop recognizing.
            assertTrue(
                "${file.name}: the scan chrome (and this rule's anchors) stay raw",
                masked.contains("Scan customer name") ||
                    masked.contains("Scan the order details by focusing on the customer name"),
            )
        }
    }

    /**
     * #920 — `shopping_item` renders the customer's own free-text note twice (a fused
     * `contentDescription` on the container and the value in a `body_text_view` child)
     * and declared no redact, so customer-authored text shipped raw on a RECOGNIZED
     * surface. The fielded note was benign, but the field is the #803 class: it can carry
     * a gate code, an apartment, a name.
     *
     * The mask is deliberately `plainMask` (#795), NOT the `<4hex>` distinctness family:
     * a note has an unbounded alphabet but no lower bound on LENGTH, so a short note that
     * IS the secret sits above #889's 4-char floor while its plaintext space stays far
     * below the suffix's 65 536 buckets. This test pins that choice — a hash-family mask
     * here would be a REGRESSION, so it asserts the plain form explicitly.
     *
     * Node shape is ground truth from the committed 07-18 fixture + the 07-29 pull; the
     * note values are invented.
     */
    @Test
    fun `shopping_item masks the Customer Notes value with a plain mask, keeps the label (#920)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.shopping_item")!!
        assertFalse("shopping_item must declare a redact block", rule.redact.isEmpty())

        fun itemCard(note: String) = UiNode(
            className = "android.view.View",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Sample Sliced Bread, 24 oz"),
                UiNode(className = "android.widget.TextView", text = "24 OUNCE｜\$4.03"),
                UiNode(
                    className = "android.view.View",
                    contentDescription = "Customer Notes: $note",
                    children = listOf(
                        UiNode(viewIdResourceName = "com.doordash.driverapp:id/start_icon_image_view"),
                        UiNode(
                            viewIdResourceName = "com.doordash.driverapp:id/label_text_view",
                            text = "Customer Notes",
                        ),
                        UiNode(
                            viewIdResourceName = "com.doordash.driverapp:id/body_text_view",
                            text = note,
                        ),
                    ),
                ),
                // Over-match guard: a `body_text_view` NOT preceded by the notes label
                // (the id is a generic LEGO-card id, also used on pickup_arrival).
                UiNode(
                    className = "android.view.View",
                    children = listOf(
                        UiNode(
                            viewIdResourceName = "com.doordash.driverapp:id/label_text_view",
                            text = "Aisle",
                        ),
                        UiNode(
                            viewIdResourceName = "com.doordash.driverapp:id/body_text_view",
                            text = "Aisle 4 - Section A13 - Shelf 2",
                        ),
                    ),
                ),
            ),
        ).restoreParents()

        // A note carrying exactly the #803 payload class this exists for.
        val note = "Gate code 4821, leave with Testname Q at the side door"
        val applied = rule.redact.apply(itemCard(note))
        val masked = serialize(applied)
        val notesBlock = applied.children[2]

        assertFalse("the note value must not persist", masked.contains("4821"))
        assertFalse("nor the name inside it", masked.contains("Testname Q"))
        assertFalse("nor the fused contentDescription copy", masked.contains("side door"))
        assertTrue(
            "the fused desc keeps its 'Customer Notes: ' label half",
            masked.contains("Customer Notes: [redacted]"),
        )
        // #795: PLAIN mask, no <4hex> suffix — a hash family here would be a regression.
        assertFalse(
            "a customer note must NOT carry the <4hex> distinctness suffix (#795)",
            MASK_HEX.containsMatchIn(masked),
        )
        // Over-match guards: label chrome, item, price and aisle are driver/merchant-owned.
        assertEquals(
            "the notes LABEL node is app chrome and stays raw",
            "Customer Notes",
            notesBlock.children[1].text,
        )
        assertEquals(
            "the notes VALUE node is masked plain",
            "[redacted]",
            notesBlock.children[2].text,
        )
        assertTrue("item name kept", masked.contains("Sample Sliced Bread, 24 oz"))
        assertTrue("price kept", masked.contains("24 OUNCE"))
        assertTrue(
            "an unlabeled body_text_view sibling stays raw",
            masked.contains("Aisle 4 - Section A13 - Shelf 2"),
        )
    }

    /**
     * #920 on the REAL tree (#1010 review F9). The synthetic case above proves the two predicates;
     * this proves they match what DoorDash actually renders — which is the whole point of the
     * #860 label-sibling anchor, whose correctness depends on the live child ORDER
     * (`…, start_icon_image_view, label_text_view, body_text_view`) rather than on anything the
     * test author chose. A layout change that inserts a node between the label and its value
     * silently un-masks the note, and only a committed-fixture assertion can see that.
     *
     * The fixture predates the redact, so its note ships RAW — exactly the un-redacted input the
     * production block has to handle.
     */
    @Test
    fun `shopping_item redact masks the Customer Notes on the committed fixture (#920)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.shopping_item")!!
        val fixture = File("src/test/resources/snapshots/shopping_item")
            .listFiles { _, n -> n.endsWith(".json") }!!
            .first { it.readText().contains("Customer Notes") }
        val real = TestResourceLoader.loadNode(fixture)

        // Pre-condition: the committed frame really does carry the note raw — otherwise the
        // assertions below would pass vacuously on an already-masked tree.
        val note = "Freshest available please."
        assertTrue(
            "${fixture.name} must carry the raw note (else this test proves nothing)",
            serialize(real).contains(note),
        )
        // And it must still RECOGNIZE as the rule whose redact we are about to apply.
        assertEquals(
            "doordash.screen.shopping_item",
            TestRulesetFactory.screenRuleset.matchFirst(real)?.ruleId,
        )

        val masked = serialize(rule.redact.apply(real))
        assertFalse("the real note value must not persist", masked.contains(note))
        assertTrue(
            "the fused contentDescription keeps only its label half",
            masked.contains("Customer Notes: [redacted]"),
        )
        assertFalse(
            "a customer note must NOT carry the <4hex> distinctness suffix (#795)",
            MASK_HEX.containsMatchIn(masked),
        )
        // Over-match guards on the real tree: merchant/catalog/location text is driver-owned.
        assertTrue("item name kept", masked.contains("Pepperidge Farm Farmhouse Hearty White Sliced Bread"))
        assertTrue("aisle kept", masked.contains("Aisle 4 - Section A13 - Shelf 2"))
        assertTrue("the notes label is chrome and stays raw", masked.contains("\"Customer Notes\""))
    }

    /**
     * #985 — the Timeline order-detail sheet, over the COMMITTED fixtures.
     *
     * Both fielded renders of the sheet are in the corpus with hand-written pseudonyms in the raw
     * shape DoorDash emits ([CorpusDecoys]): the DROPOFF render (task line · street+Apt · city/ST/ZIP
     * · dropoff option · quoted note · bare entry code) and the PICKUP render of the same sheet
     * (task line · store · store address · store). The point of running against the real trees
     * rather than a synthetic one is the #871/#885 "teeth" property — a synthetic tree proves the
     * predicate, only the fielded tree proves the predicate matches what DoorDash actually renders.
     */
    @Test
    fun `timeline_task_detail masks the address block, note and entry code (#985)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.timeline_task_detail")
        org.junit.Assert.assertNotNull("the #985 rule must exist", rule)
        assertFalse("timeline_task_detail must declare a redact block", rule!!.redact.isEmpty())

        val snapshots = TestResourceLoader.loadSnapshots("snapshots/timeline_task_detail")
        assertEquals("both fielded renders must be committed", 2, snapshots.size)

        for ((filename, node, _) in snapshots) {
            // The rule must actually WIN the frame — otherwise the redact assertions below are
            // testing a block that never runs in production.
            assertEquals(
                "$filename must classify as the new rule",
                "timeline_task_detail",
                TestRulesetFactory.screenRuleset.matchFirst(node)?.intent,
            )
            val masked = serialize(rule.redact.apply(node))

            assertFalse("$filename: the customer name must not persist", masked.contains("Avery K"))
            assertTrue(
                "$filename: the task lead-in survives with a distinctness hex",
                maskAfter("Deliver to ").containsMatchIn(masked) ||
                    maskAfter("Pickup for ").containsMatchIn(masked),
            )
            // The address block — street/venue line AND the city/ST/ZIP line beneath it.
            assertFalse("$filename: street line must not persist", masked.contains("Sample Hollow"))
            assertFalse("$filename: unit number must not persist", masked.contains("Apt 331"))
            assertFalse("$filename: ZIP must not persist", masked.contains("78299"))
            // Chrome — the require anchors MUST survive or replay recognition breaks.
            assertTrue("$filename: 'Copy address' anchor kept", masked.contains("Copy address"))
            assertTrue("$filename: 'Close sheet' anchor kept", masked.contains("Close sheet"))
        }

        val dropoff = snapshots.first { it.first.contains("f7dd84") }.second
        val dropoffMasked = serialize(rule.redact.apply(dropoff))
        assertFalse(
            "the customer's gate note must not persist",
            dropoffMasked.contains("open the gate"),
        )
        assertFalse("the entry code must not persist", dropoffMasked.contains("6413"))
        // #920/#889 posture: neither the note nor the entry code may carry a distinctness suffix
        // (a short note that IS the secret, and a 4-digit code, are both small enough spaces that
        // 4 hex over 65 536 buckets is an inversion oracle). The masked customer NAME is the one
        // hex on this frame, so a blanket "no hex anywhere" assertion would be wrong — assert per
        // node instead.
        fun maskOf(text: String) = rule.redact.apply(UiNode(text = text)).text!!
        assertEquals(
            "the quoted note plain-masks",
            "[redacted]",
            maskOf("\"leave it by the gate, code 4417, thanks!\""),
        )
        assertEquals("a 4-digit entry code plain-masks", "[redacted]", maskOf("6413"))
        assertEquals("the city/ST/ZIP line plain-masks", "[redacted]", maskOf("San Antonio, TX 78299"))
        // The dropoff option and the merchant name are platform/driver-owned and stay raw.
        assertTrue("the dropoff option is chrome", dropoffMasked.contains("Leave it at my door"))
        val pickupMasked = serialize(
            rule.redact.apply(snapshots.first { it.first.contains("c9b8d9") }.second),
        )
        // This pins only the committed ZIP-less pickup fixture; a city/ST/ZIP store-address line
        // can make the venue/following-sibling entry hash-mask the merchant name.
        assertTrue(
            "the merchant name stays raw in the committed ZIP-less pickup fixture",
            pickupMasked.contains("Uff, Quee Rico"),
        )
        assertFalse(
            "the address under the merchant is masked too — the sheet cannot tell a customer " +
                "address from a store address, so it fails toward privacy",
            pickupMasked.contains("Sample Loop"),
        )
    }

    /**
     * #985 — the shapes the committed fixtures do NOT carry, asserted synthetically: the VENUE
     * form of address line 1 (a proper noun with no digits, anchored only by the city line beneath
     * it — #886's mirror predicate), the STATE-LESS city render this surface fielded on 07-23
     * (which is why the pattern here is a deliberate superset of the `dropoff_pre_arrival` copy),
     * and #994's `Return <name> to <store>` conjugation.
     *
     * Plus the cross-surface invariant that matters most: one customer masks to ONE hex on the
     * timeline row and in the detail sheet it opens, because both entries normalize through
     * `CustomerNameKey` (#623/#733).
     */
    @Test
    fun `timeline_task_detail masks a venue line, a state-less city, and the Return conjugation (#985)`() {
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.timeline_task_detail")!!

        val block = UiNode(
            children = listOf(
                UiNode(text = "Sample Ridge Apartments"), // venue line 1 — no digits, no id
                UiNode(text = "San Antonio,  78200"), // the fielded state-less, double-spaced render
                UiNode(text = "Leave it at my door"),
            ),
        ).restoreParents()
        val masked = serialize(rule.redact.apply(block))
        assertFalse("a venue line 1 must be masked", masked.contains("Sample Ridge Apartments"))
        assertFalse("the state-less city line must be masked", masked.contains("78200"))
        assertTrue("the dropoff option survives", masked.contains("Leave it at my door"))

        // #994's fourth conjugation, with the store tail masked along with the name (the keepPrefix
        // mask is whole-remainder; the store is recoverable from the sheet's own store row).
        val ret = rule.redact.apply(UiNode(text = "Return Avery K to H-E-B")).text!!
        assertFalse("the returned-order customer name must not persist", ret.contains("Avery K"))
        assertTrue("the Return lead-in survives", wholeMaskAfter("Return ").matches(ret))

        // One customer, one hex — the sheet and the timeline row it was opened from.
        val timeline = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.timeline")!!
        val sheetHex = hexIn(rule.redact.apply(UiNode(text = "Deliver to Avery K")).text!!, "sheet name")
        val rowHex = hexIn(
            timeline.redact.apply(UiNode(text = "Deliver to Avery Kirkland")).text!!,
            "timeline row name",
        )
        assertEquals(
            "the detail sheet and its timeline row must mask one customer to one hex " +
                "(CustomerNameKey canonicalizes both to 'avery k')",
            rowHex,
            sheetHex,
        )
    }

    /**
     * #994 review F6 — **the redact and parse prefix enumerations must not silently diverge.**
     *
     * `doordash.screen.timeline` enumerates the same customer/store lead-ins twice: once in its
     * `redact` (`keepPrefix`, what gets MASKED) and once in its `parse.tasks` (`hasTextStartsWith`
     * + `stripPrefixes`, what gets UNDERSTOOD). #994 added the fourth conjugation `"Return "` to
     * the redact side only, which is the correct privacy fix but leaves the parse side blind: the
     * committed return-order fixture folds to `tasks: []`.
     *
     * Adding return-task parsing here would be smuggling a state change into a privacy PR — a
     * return leg's task-model semantics (is it a task? whose lineage? what closes it?) is #998's
     * design question, not this test's. So the divergence is allowed, but **only by explicit
     * enumeration**: a prefix on one side and not the other fails unless it is listed below with
     * the issue that owns the gap. A future prefix added to either side without a decision fails
     * loudly instead of quietly under-parsing (or, worse, quietly under-masking).
     */
    @Test
    fun `timeline redact and parse prefix enumerations agree, or the gap is documented (#994)`() {
        /**
         * Prefixes the redact masks that the parse deliberately does NOT understand yet.
         * Key = the prefix, value = the issue that owns the decision. Empty is the goal state.
         */
        val parseExclusions = mapOf(
            "Return " to
                "#998 — a RETURN order's task-model semantics are an open design question " +
                "(is a return leg a task? whose lineage does it join? what closes it?). #994 " +
                "masked the line for the Pledge and deliberately did NOT teach the parse to " +
                "read it; the committed return fixture folds to tasks: [] by design.",
        )

        val rule = ruleJson("doordash.screen.timeline")
        val redactPrefixes = mutableListOf<String>()
        rule["redact"]?.let { collectStringsUnder(it, "keepPrefix", redactPrefixes) }
        // Scoped to `parse.fields.tasks` — the ONE parse enumeration that mirrors the redact's
        // lead-in set. The rule's other `hasTextStartsWith` anchors ("Dash ends at") read session
        // chrome, carry no customer/store value, and are correctly absent from the redact.
        val parsePrefixes = mutableListOf<String>()
        rule["parse"]?.jsonObject?.get("fields")?.jsonObject?.get("tasks")?.let {
            collectStringsUnder(it, "hasTextStartsWith", parsePrefixes)
            collectStringsUnder(it, "stripPrefixes", parsePrefixes)
        }
        assertTrue("timeline must declare redact keepPrefixes", redactPrefixes.isNotEmpty())
        assertTrue("timeline must declare parse task prefixes", parsePrefixes.isNotEmpty())

        // Masked but not parsed — allowed ONLY when documented above.
        val maskedNotParsed = redactPrefixes.toSet() - parsePrefixes.toSet()
        assertEquals(
            "a prefix the timeline redact masks but the parse does not read must be listed in " +
                "parseExclusions with the issue that owns the gap (or taught to the parse); " +
                "undocumented: ${maskedNotParsed - parseExclusions.keys}",
            emptySet<String>(),
            maskedNotParsed - parseExclusions.keys,
        )
        // Parsed but not masked — NEVER allowed: the parse proves the line carries a
        // customer/store value, so an unmasked one is a live capture leak.
        assertEquals(
            "a prefix the timeline parse reads but the redact does not mask is a capture leak",
            emptySet<String>(),
            parsePrefixes.toSet() - redactPrefixes.toSet(),
        )
        // A stale exclusion (the parse caught up, nobody removed the entry) must also fail, so
        // this list can only shrink.
        assertEquals(
            "parseExclusions lists a prefix that is no longer divergent — delete it",
            emptySet<String>(),
            parseExclusions.keys - maskedNotParsed,
        )
    }

    /** The generated-asset JSON object for [ruleId] (screens section). */
    private fun ruleJson(
        ruleId: String,
        platformFile: String = "doordash.json",
    ): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(File(rulesDir, platformFile).readText()).jsonObject["screens"]!!
            .jsonArray.first { it.jsonObject["id"]?.jsonPrimitive?.content == ruleId }.jsonObject

    /** Every string value stored under [key] (scalar or array) anywhere inside [element]. */
    private fun collectStringsUnder(
        element: kotlinx.serialization.json.JsonElement,
        key: String,
        out: MutableList<String>,
    ) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> element.forEach { (k, v) ->
                if (k == key) {
                    when (v) {
                        is kotlinx.serialization.json.JsonPrimitive -> if (v.isString) out += v.content
                        is kotlinx.serialization.json.JsonArray ->
                            v.forEach { e ->
                                (e as? kotlinx.serialization.json.JsonPrimitive)
                                    ?.takeIf { it.isString }?.let { out += it.content }
                            }
                        else -> {}
                    }
                } else {
                    collectStringsUnder(v, key, out)
                }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { collectStringsUnder(it, key, out) }
            else -> {}
        }
    }

    private fun jsonUsesSha256(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is kotlinx.serialization.json.JsonPrimitive -> element.isString && element.content == "sha256"
        is kotlinx.serialization.json.JsonObject -> element.values.any { jsonUsesSha256(it) }
        is kotlinx.serialization.json.JsonArray -> element.any { jsonUsesSha256(it) }
    }
}
