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
    private fun ruleRedactRegexes(ruleId: String): List<String> {
        val root = Json.parseToJsonElement(File(rulesDir, "doordash.json").readText()).jsonObject
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
        // FIX 3 defense-in-depth: the two earlier-priority rules that could win a
        // modal-over-handoff combined frame carry the SAME pattern.
        for (id in listOf("doordash.screen.dropoff_navigation", "doordash.screen.dropoff_handoff")) {
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
        val folders = listOf("dropoff_multi_order_confirm", "dropoff_navigation", "dropoff_handoff")
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

    private fun walkText(node: UiNode, visit: (String) -> Unit) {
        node.text?.let(visit)
        node.contentDescription?.let(visit)
        node.children.forEach { walkText(it, visit) }
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

    private fun jsonUsesSha256(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is kotlinx.serialization.json.JsonPrimitive -> element.isString && element.content == "sha256"
        is kotlinx.serialization.json.JsonObject -> element.values.any { jsonUsesSha256(it) }
        is kotlinx.serialization.json.JsonArray -> element.any { jsonUsesSha256(it) }
    }
}
