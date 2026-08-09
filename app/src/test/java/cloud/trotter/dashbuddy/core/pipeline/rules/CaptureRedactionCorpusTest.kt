package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.test.util.SnapshotRedactor
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
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
            Regex("""Deliver to \[redacted:[0-9a-f]{4}\]""").containsMatchIn(redactedJson),
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
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(redactedJson),
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
        val suffix = Regex("""\[redacted:([0-9a-f]{4})\]""")
        fun hexOf(masked: String): String =
            suffix.find(masked)!!.groupValues[1]

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
            Regex("""Deliver to door of \[redacted:[0-9a-f]{4}\]""").containsMatchIn(masked),
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
                Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
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
                return Regex("""^\[redacted:([0-9a-f]{4})]$""").find(value)?.groupValues?.get(1)
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
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(doubleSpaced),
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
        val hex = Regex("""\[redacted:([0-9a-f]{4})]""")
        fun cardHex(name: String): String {
            val masked = rule.redact.apply(
                UiNode(className = "android.view.View", children = listOf(UiNode(text = name))).restoreParents(),
            ).children[0].text!!
            return hex.find(masked)?.groupValues?.get(1)
                ?: error("name node was not masked; got '$masked'")
        }
        val navRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_navigation")!!
        val navHex = hex.find(
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
                Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
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
                Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(venue),
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
                return Regex("""^\[redacted:([0-9a-f]{4})]$""").find(value)?.groupValues?.get(1)
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
        // #809: the four pickup "For <customer> • <store>" surfaces each ship a
        // `redact` with keepPrefix ["For "] + normalize customerName. Nothing else
        // pinned them — the #598 sha256 compile gate needs a sha256 parse (these are
        // recognize-only), and CustomerTextMarkers has no bare "For " marker — so a
        // deleted redact block on any of them went unnoticed (mutation-verified: the
        // full suite stayed green). This is the teeth: an injected fused header node
        // must mask to "For [redacted:<4hex>]" with no name residue. This is a PIN for
        // these four explicit ids, NOT a discovery gate — a FIFTH "For "-surface rule
        // added without redact won't appear in this list; that gap is caught at
        // author/review time, not here.
        val forFamily = listOf(
            "doordash.screen.pickup_select_issue",
            "doordash.screen.pickup_resolution_options",
            "doordash.screen.pickup_unassigned_confirmation",
            "doordash.screen.pickup_unassign_survey",
            "doordash.screen.dropoff_issue_resolution",
        )
        val maskShape = Regex("""^For \[redacted:[0-9a-f]{4}\]$""")
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
        for (id in listOf(
            "doordash.screen.dropoff_navigation",
            "doordash.screen.dropoff_handoff",
            "doordash.screen.dropoff_pin_entry",
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
        val maskShape = Regex("""\[redacted:[0-9a-f]{4}\]""")
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

    @Test
    fun `committed corpus carries no un-masked bare customer-name node (FIX 4)`() {
        val nameShape = Regex(SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN, RegexOption.IGNORE_CASE)
        // Folders whose owning rule declares the name-shape redact (FIX 3 included).
        // #825 added the uber trip surfaces (active_trip/splash/customer_chat/
        // pickup_verification_items) to the set — each now carries the id-less
        // name-shape redact, so a future un-masked bare name in their corpus is a
        // test failure, not a permanent git leak.
        val folders = listOf(
            "dropoff_multi_order_confirm", "dropoff_navigation", "dropoff_handoff",
            "active_trip", "splash", "customer_chat", "pickup_verification_items",
        )
        val leaks = mutableListOf<String>()
        for (folder in folders) {
            for ((filename, node, _) in TestResourceLoader.loadSnapshots("snapshots/$folder")) {
                walkText(node) { text ->
                    if (!text.contains("[redacted") && nameShape.matches(text)) {
                        leaks += "$folder/$filename: \"$text\""
                    }
                }
            }
        }
        assertTrue(
            "committed corpus leaks an un-masked bare customer name (the masker's blind spot " +
                "is now a test failure, not a permanent git leak): $leaks",
            leaks.isEmpty(),
        )
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
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(completedMasked),
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
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
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
            Regex("""\[redacted]""").findAll(masked).count() >= 5,
        )
        // The plain constant is present but the reversible `<4hex>` distinctness form is
        // NOT — this gives the `plainMask: true` flag itself mutation teeth (a 4-digit
        // PIN is recoverable from 4 hex, so the hashed form would leak it).
        assertFalse(
            "PIN must not carry a reversible distinctness hash",
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
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
     */
    @Test
    fun `every Apt keepPrefix redact entry declares plainMask on both platforms (#895)`() {
        val ruleIds = listOf(
            "uber.screen.splash",
            "uber.screen.pickup_verification_items",
            "uber.screen.customer_chat",
            "uber.screen.active_trip",
            "doordash.screen.dropoff_pin_entry",
            "doordash.screen.dropoff_handoff",
            "doordash.screen.dropoff_pre_arrival",
            "doordash.screen.dropoff_pre_arrival_completion",
        )
        for (id in ruleIds) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)
            org.junit.Assert.assertNotNull("rule $id must exist", rule)
            val aptEntries = rule!!.redact.entries.filter { it.keepPrefix.contains("Apt ") }
            assertFalse("$id must carry an 'Apt ' keepPrefix entry", aptEntries.isEmpty())
            assertTrue(
                "$id: every fused-Apt entry must declare plainMask (#895 — bounded alphabet over the length floor)",
                aptEntries.all { it.plainMask },
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
        assertTrue(Regex("""Message from \[redacted:[0-9a-f]{4}\]""").containsMatchIn(masked.title!!))
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
        assertTrue(Regex("""^\[redacted:[0-9a-f]{4}\]$""").matches(masked.title!!))
    }

    @Test
    fun `production uber trip_at_dropoff masks the address or name, keeps the lead-in (#620 F1)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("uber.notification.trip_at_dropoff")!!
        assertTrue("trip_at_dropoff must carry a notif redact block", !rule.notifRedact.isEmpty())
        val leave = rule.notifRedact.apply(notif(title = "Leave the order at 1600 Amphitheatre Pkwy"))
        assertFalse("address gone", leave.title!!.contains("Amphitheatre"))
        assertTrue(Regex("""^Leave the order at \[redacted:[0-9a-f]{4}\]$""").matches(leave.title!!))
        val meet = rule.notifRedact.apply(notif(title = "Meet at door for Jane Doe"))
        assertFalse("customer name gone", meet.title!!.contains("Jane"))
        assertTrue(Regex("""^Meet at door for \[redacted:[0-9a-f]{4}\]$""").matches(meet.title!!))
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
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
        )
        // The label sibling is app vocabulary — kept, so a replayed frame keeps its shape.
        assertTrue("the 'Delivery for' label is chrome and stays raw", masked.contains("Delivery for"))
        assertTrue("Apt/Suite lead-in kept", masked.contains("Apt/Suite: "))
        // Over-match guards.
        assertTrue("merchant kept (driver-owned)", masked.contains("Sample Pizza Co (41709)"))
        assertTrue("catalog item kept", masked.contains("Large Pepperoni"))
        assertTrue("instruction LABEL kept", masked.contains("Dropoff instructions"))
        assertTrue("pay kept", masked.contains("$8.25"))
        assertTrue("CTA kept", masked.contains("Complete delivery"))

        // #733 cross-surface stability: the name's hex must equal the SAME customer's hex on
        // the "Deliver to <name>" nav title — one customer, one mask, every surface.
        val hex = Regex("""\[redacted:([0-9a-f]{4})]""")
        val nameHex = hex.find(rule.redact.apply(dd("user_name", "Testname Q")).text!!)!!.groupValues[1]
        val navRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_navigation")!!
        val navHex = hex.find(
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
        val hex = Regex("""\[redacted:([0-9a-f]{4})]""")

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
            return hex.find(masked)?.groupValues?.get(1)
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
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
        )
        // Over-match guards: the label is chrome, the merchant is driver-owned, the
        // survey copy is platform vocabulary — all three stay raw.
        assertTrue("label kept (app chrome)", masked.contains("Order for"))
        assertTrue("merchant kept (driver-owned)", masked.contains("Sample Bowls Co"))
        assertTrue("survey copy kept", masked.contains("Tell us what's causing your wait"))

        // #733: one customer, one hex — this surface must agree with the single-order
        // pickup card, which is what `normalize: customerName` on the entry buys.
        val hex = Regex("""\[redacted:([0-9a-f]{4})]""")
        fun nameHex(r: CompiledRedact): String {
            val out = r.apply(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Testname Q"),
            ).text!!
            return hex.find(out)?.groupValues?.get(1) ?: error("customer_name was not masked; got '$out'")
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
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
        )
        // Over-match guards: the label and the sheet header are app chrome.
        assertTrue("subtitle kept (app chrome)", masked.contains("Arriving at"))
        assertTrue("sheet header kept (app chrome)", masked.contains("Arriving soon"))

        // Distinctness (#623): two destinations must not collide on one mask.
        val hex = Regex("""\[redacted:([0-9a-f]{4})]""")
        fun titleHex(text: String): String {
            val out = dropoffRule.redact.apply(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/arriving_at_title", text = text),
            ).text!!
            return hex.find(out)?.groupValues?.get(1) ?: error("arriving_at_title was not masked; got '$out'")
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
            Regex("""Return \[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
        )
        // The whole-remainder mask swallows the store tail — documented, not incidental.
        assertFalse("the masked remainder covers the ' to <store>' tail", masked.contains("Sample Grocery Co"))

        // #623/#733 cross-conjugation invariance: one customer, one hex, whichever verb
        // the platform used — this is what the shared `normalize: customerName` buys.
        val hex = Regex("""\[redacted:([0-9a-f]{4})]""")
        fun lineHex(text: String): String {
            val out = rule.redact.apply(UiNode(text = text).restoreParents()).text!!
            return hex.find(out)?.groupValues?.get(1) ?: error("line was not masked; got '$out'")
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
            Regex("""Pickup from \[redacted:[0-9a-f]{4}]""").containsMatchIn(
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
            Regex("""Focus on \[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
        )
        assertTrue(
            "the bare name node masks WHOLE to the hash family [redacted:<4hex>], " +
                "got '${applied.children[2].text}'",
            Regex("""^\[redacted:[0-9a-f]{4}]$""").matches(applied.children[2].text!!),
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
        val hex = Regex("""\[redacted:([0-9a-f]{4})]""")
        val focusHex = hex.find(applied.children[0].text!!)!!.groupValues[1]
        val bareHex = hex.find(applied.children[2].text!!)!!.groupValues[1]
        assertEquals("both name nodes on one frame mask to one hex", focusHex, bareHex)
        val arrivalHex = hex.find(
            TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_arrival")!!.redact.apply(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Testname Q"),
            ).text!!,
        )!!.groupValues[1]
        assertEquals("and to the customer's hex on the pickup card", arrivalHex, focusHex)
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
            Regex("""\[redacted:[0-9a-f]{4}]""").containsMatchIn(masked),
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

    private fun jsonUsesSha256(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is kotlinx.serialization.json.JsonPrimitive -> element.isString && element.content == "sha256"
        is kotlinx.serialization.json.JsonObject -> element.values.any { jsonUsesSha256(it) }
        is kotlinx.serialization.json.JsonArray -> element.any { jsonUsesSha256(it) }
    }
}
