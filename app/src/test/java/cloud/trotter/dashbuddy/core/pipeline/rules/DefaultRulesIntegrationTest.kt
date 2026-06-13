package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests that compile the production rule JSON files and run
 * the resulting rulesets against known inputs.
 *
 * These tests load the files directly from source (no Android Context needed),
 * bypassing [JsonRuleInterpreter] so no dependency injection is required.
 */
class DefaultRulesIntegrationTest {

    private lateinit var screenRuleset: Ruleset<UiNode>
    private lateinit var clickRuleset: Ruleset<UiNode>
    private lateinit var notificationRuleset: Ruleset<RawNotificationData>

    @Before
    fun loadRules() {
        val dir = File(TestRulesetFactory.rulesDir)
        val allScreens = mutableListOf<CompiledRule<UiNode>>()
        val allClicks = mutableListOf<CompiledRule<UiNode>>()
        val allNotifications = mutableListOf<CompiledRule<RawNotificationData>>()

        dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["screens"]?.jsonArray
                ?.let { allScreens += RuleCompiler.compileRules<UiNode>(it, RuleContext.SCREEN) }
            root["clicks"]?.jsonArray
                ?.let { allClicks += RuleCompiler.compileRules<UiNode>(it, RuleContext.CLICK) }
            root["notifications"]?.jsonArray
                ?.let { allNotifications += RuleCompiler.compileRules<RawNotificationData>(it, RuleContext.NOTIFICATION) }
        }

        screenRuleset = Ruleset(allScreens)
        clickRuleset = Ruleset(allClicks)
        notificationRuleset = Ruleset(allNotifications)
    }

    // =========================================================================
    // Sanity — rule counts are non-zero
    // =========================================================================

    @Test
    fun `screen ruleset has rules`() {
        assertTrue("Expected screen rules, got 0", screenRuleset.ruleCount > 0)
    }

    @Test
    fun `click ruleset has rules`() {
        assertTrue("Expected click rules, got 0", clickRuleset.ruleCount > 0)
    }

    @Test
    fun `notification ruleset has rules`() {
        assertTrue("Expected notification rules, got 0", notificationRuleset.ruleCount > 0)
    }

    // =========================================================================
    // Click classification — spot checks
    // =========================================================================

    @Test
    fun `accept_button id classifies as accept_offer`() {
        val node = UiNode(viewIdResourceName = "com.doordash.driverapp:id/accept_button")
        assertEquals("accept_offer", clickRuleset.matchFirst(node, screenTarget = "offer_popup")?.intent)
    }

    @Test
    fun `'Decline offer' text classifies as decline_offer`() {
        val node = UiNode(text = "Decline offer")
        assertEquals("decline_offer", clickRuleset.matchFirst(node, screenTarget = "offer_popup_confirm_decline")?.intent)
    }

    @Test
    fun `primary_action_button + Arrived at store classifies as arrived_at_store`() {
        val node = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/primary_action_button",
            text = "Arrived at store",
        )
        assertEquals("arrived_at_store", clickRuleset.matchFirst(node, screenTarget = "pickup_arrival")?.intent)
    }

    @Test
    fun `unrecognized click node returns null`() {
        val node = UiNode(viewIdResourceName = "com.doordash.driverapp:id/some_unknown_btn")
        assertNull(clickRuleset.matchFirst(node))
    }

    // =========================================================================
    // Notification classification — spot checks
    // =========================================================================

    @Test
    fun `New Order title classifies as new_order`() {
        val raw = raw(title = "New Order")
        assertEquals("new_order", notificationRuleset.matchFirst(raw)?.intent)
    }

    @Test
    fun `Scheduled dash expired notification classifies correctly`() {
        val raw = raw(text = "Your scheduled dash has expired")
        assertEquals("scheduled_dash_expired", notificationRuleset.matchFirst(raw)?.intent)
    }

    @Test
    fun `AdditionalTip notification extracts fields correctly`() {
        val raw = raw(bigText = "added \$5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM")
        val result = notificationRuleset.matchFirst(raw)
        assertNotNull("Expected AdditionalTip result", result)
        assertEquals("additional_tip", result!!.intent)
        assertEquals(5.00, result.fields["amount"] as Double, 0.001)
        assertEquals("H-E-B", result.fields["storeName"])
        assertEquals("4/26, 3:15 PM", result.fields["deliveredAt"])
    }

    @Test
    fun `unrecognized notification returns null`() {
        val raw = raw(title = "DoorDash", text = "Something not yet classified")
        // None of the rules should match — Unknown falls back to the caller
        assertNull(notificationRuleset.matchFirst(raw))
    }

    // =========================================================================
    // File loading edge cases
    // =========================================================================

    @Test
    fun `file compiles without RuleCompileException`() {
        // @Before would have thrown if compilation failed; reaching here means success
        assertTrue(screenRuleset.ruleCount > 0)
        assertTrue(clickRuleset.ruleCount > 0)
        assertTrue(notificationRuleset.ruleCount > 0)
    }

    // =========================================================================
    // Sensitive coverage (#432)
    // =========================================================================

    @Test
    fun `every platform shipping screen rules ships a sensitive rule`() {
        // Recompile the production files and assert no platform is uncovered —
        // the load-time check in JsonRuleInterpreter excludes uncovered
        // platforms (fail closed); this pins that the SHIPPED bundles never
        // trip it.
        val dir = File(TestRulesetFactory.rulesDir)
        val allScreens = mutableListOf<CompiledRule<UiNode>>()
        dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["screens"]?.jsonArray
                ?.let { allScreens += RuleCompiler.compileRules<UiNode>(it, RuleContext.SCREEN) }
        }
        assertTrue(
            "platforms missing sensitive rules: ${missingSensitivePlatforms(allScreens)}",
            missingSensitivePlatforms(allScreens).isEmpty(),
        )
    }

    @Test
    fun `uber wallet and cashout screens classify as sensitive`() {
        val wallet = UiNode(children = listOf(UiNode(text = "Available balance"), UiNode(text = "Set up Instant Pay")))
            .restoreParents()
        val walletResult = screenRuleset.matchFirst(wallet, platformWire = "uber")
        assertTrue(
            "wallet screen must hit a sensitive rule, got ${walletResult?.ruleId}",
            walletResult?.ruleId?.contains("sensitive") == true,
        )

        val cashout = UiNode(children = listOf(UiNode(text = "Cash out"), UiNode(text = "Transfer to bank account")))
            .restoreParents()
        val cashoutResult = screenRuleset.matchFirst(cashout, platformWire = "uber")
        assertTrue(
            "cashout screen must hit a sensitive rule, got ${cashoutResult?.ruleId}",
            cashoutResult?.ruleId?.contains("sensitive") == true,
        )
    }

    @Test
    fun `DasherDirect savings transfer screens classify as sensitive (#463)`() {
        // The three frames that LEAKED plaintext balances to UNKNOWN capture on
        // the 2026-06-12 dash — each must now hit the priority-0 sensitive rule.
        fun screen(vararg texts: String) =
            UiNode(children = texts.map { UiNode(text = it) }).restoreParents()

        // Savings landing: "Savings jar" + "$99.08" + "Transfer $9.06".
        val landing = screen("Transfer in", "Savings jar", "$99.08", "Transfer $9.06")
        // Transfer-confirmation: "You transferred $9.06" + "Savings jar".
        val confirm = screen("You transferred $9.06", "Your transfer should now appear in your Savings jar", "Got it")
        // Transfer-entry (no "Savings jar"): "Transfer in" + "$9.06 available".
        val entry = screen("Transfer in", "$9.06 available", "$0", "Continue")

        for ((name, node) in listOf("landing" to landing, "confirm" to confirm, "entry" to entry)) {
            val r = screenRuleset.matchFirst(node, platformWire = "doordash")
            assertTrue(
                "DasherDirect savings $name screen must hit a sensitive rule, got ${r?.ruleId}",
                r?.ruleId?.contains("sensitive") == true,
            )
        }
    }

    @Test
    fun `alcohol document-CAPTURE surfaces stay sensitive (#463)`() {
        // We block only the document-CAPTURE surfaces: the license-scan camera
        // (an image of a government ID) and the signature pad/handoff. These
        // carry an actual ID image / signature, so they're blocked regardless of
        // whose. (The instruction + arrival screens are recognized instead — see
        // the next test.)
        fun screen(vararg texts: String) =
            UiNode(children = texts.map { UiNode(text = it) }).restoreParents()

        val licenseScan = screen("Driver's License", "Scan barcode on the back of license", "HELP")
        val signatureHandoff = screen("Hand your phone to the customer so they can provide their signature.", "Got it")
        val scanResult = screen("Scan Successful", "Now you're ready to start verification.", "Continue")
        val signatureCanvas = screen("3 of 4", "A recipient signature is required for this order", "I have received this order", "Clear")

        for ((name, node) in listOf(
            "license-scan" to licenseScan, "signature-handoff" to signatureHandoff,
            "scan-result" to scanResult, "signature-canvas" to signatureCanvas,
        )) {
            val r = screenRuleset.matchFirst(node, platformWire = "doordash")
            assertTrue(
                "alcohol capture surface '$name' must hit a sensitive rule, got ${r?.ruleId}",
                r?.ruleId?.contains("sensitive") == true,
            )
        }
    }

    @Test
    fun `alcohol ID-CHECK instruction + arrival card are recognized, not blocked (#463 reversal)`() {
        // We block the dasher's own sensitive data + document-image captures, but
        // we RECOGNIZE the alcohol delivery flow: the ID-check instruction (no
        // PII) and the arrival card (customer name/address, which the dropoff
        // parse hashes). Customers are hashed, not blocked.
        fun screen(vararg texts: String) =
            UiNode(children = texts.map { UiNode(text = it) }).restoreParents()

        val idCheck = screen("Identity verification", "2 of 4", "Verify that the ID matches the recipient and they aren't intoxicated.", "Verify", "Can't verify")
        val idCheckResult = screenRuleset.matchFirst(idCheck, platformWire = "doordash")
        assertEquals(
            "the ID-check instruction must recognize as alcohol_id_check (got ${idCheckResult?.ruleId})",
            "doordash.screen.alcohol_id_check", idCheckResult?.ruleId,
        )

        // The alcohol arrival card (same "Delivery for" + "Hand it to recipient"
        // layout as a normal dropoff, plus the alcohol banner) must recognize as
        // a dropoff, NOT block — the name/address get hashed by the parse.
        val arrival = screen(
            "Deliver by 20:04", "Delivery for", "Sample C",
            "100 Sample St, San Antonio, TX 78000, USA", "Directions", "Hand it to recipient",
            "Verify the recipient's identity and collect a signature at dropoff",
            "Remember, you’re required by law to confirm the recipient's identity before handing over the order.",
        )
        val arrivalResult = screenRuleset.matchFirst(arrival, platformWire = "doordash")
        assertEquals(
            "the alcohol arrival card must recognize as dropoff_pre_arrival (got ${arrivalResult?.ruleId})",
            "doordash.screen.dropoff_pre_arrival", arrivalResult?.ruleId,
        )
    }

    @Test
    fun `alcohol delivery instruction CHECKLIST does NOT classify as sensitive — recognize-vs-block boundary (#463 vs #462)`() {
        // The step-CHECKLIST (instructions, no recipient PII, no scanning) must
        // stay non-sensitive so #462 can recognize it as a dropoff flow step.
        // Only the actual capture surfaces are blocked.
        val checklist = UiNode(
            children = listOf(
                UiNode(text = "Follow all of the steps below to complete this delivery."),
                UiNode(text = "Do NOT hand over items until ID has been verified"),
                UiNode(text = "Verify recipient's identity"),
                UiNode(text = "Complete delivery"),
            ),
        ).restoreParents()

        val r = screenRuleset.matchFirst(checklist, platformWire = "doordash")
        assertTrue(
            "the instruction checklist must NOT be blocked sensitive (got ${r?.ruleId}) — it's a recognizable flow step (#462)",
            r?.ruleId?.contains("sensitive") != true,
        )
    }

    @Test
    fun `7-Eleven 'Delivery for' dropoff arrival card branches into dropoff_pre_arrival — alcohol variant too (#462 + #463 reversal)`() {
        // The arrival detail card uses "Delivery for" (name in a sibling node) +
        // "Hand it to recipient", not the "Deliver to <name>" form the rule
        // originally keyed on, so it fell to UNKNOWN. It must now branch into
        // dropoff_pre_arrival.
        val arrivalCard = UiNode(
            children = listOf(
                UiNode(text = "Deliver by 20:04"),
                UiNode(text = "Delivery for"),
                UiNode(text = "Sample C"),
                UiNode(text = "100 Sample St, San Antonio, TX 78000, USA"),
                UiNode(text = "Directions"),
                UiNode(text = "Hand it to recipient"),
            ),
        ).restoreParents()
        val card = screenRuleset.matchFirst(arrivalCard, platformWire = "doordash")
        assertEquals(
            "the 'Delivery for' dropoff arrival card must branch into dropoff_pre_arrival (got ${card?.ruleId})",
            "doordash.screen.dropoff_pre_arrival", card?.ruleId,
        )

        // The ALCOHOL variant of the same card carries the ID/signature banner but
        // no document image — it now ALSO recognizes as a dropoff (name/address
        // hashed by the parse), rather than being blocked (#463 reversal). Only
        // the literal scanner + signature-pad surfaces stay sensitive.
        val alcoholVariant = UiNode(
            children = listOf(
                UiNode(text = "Deliver by 20:04"),
                UiNode(text = "Delivery for"),
                UiNode(text = "Sample C"),
                UiNode(text = "Hand it to recipient"),
                UiNode(text = "Verify the recipient's identity and collect a signature at dropoff"),
                UiNode(text = "Remember, you’re required by law to confirm the recipient's identity before handing over the order."),
            ),
        ).restoreParents()
        val alcohol = screenRuleset.matchFirst(alcoholVariant, platformWire = "doordash")
        assertEquals(
            "the alcohol arrival variant must now recognize as dropoff_pre_arrival, not block (got ${alcohol?.ruleId})",
            "doordash.screen.dropoff_pre_arrival", alcohol?.ruleId,
        )
    }

    // =========================================================================
    // Parse-output regression on real captures (#433)
    // =========================================================================

    @Test
    fun `timeline task rows parse storeHint and deadline from the real bullet separator`() {
        // The rule's extractBefore/extractAfter separator was double-encoded
        // mojibake (" ï¿½ ") instead of the real bullet (" • ") the captures
        // contain — extractAfter returned null, so storeHint silently parsed
        // null on EVERY timeline screen while the intent-only golden guard
        // stayed green (#433). This pins the parse OUTPUT on real snapshots.
        val snapshots = TestResourceLoader.loadSnapshots("snapshots/timeline")
        assertTrue("timeline corpus must not be empty", snapshots.isNotEmpty())

        val taskEntries = snapshots.mapNotNull { (_, tree, _) ->
            val result = screenRuleset.matchFirst(tree, platformWire = "doordash")
                ?: return@mapNotNull null
            if (result.intent != "timeline") return@mapNotNull null
            val parsed = ParsedFieldsFactory.create(result.shape, result.fields)
                as? cloud.trotter.dashbuddy.domain.state.ParsedFields.TimelineFields
            parsed?.tasks
        }.flatten()

        assertTrue("expected parsed timeline task entries across the corpus", taskEntries.isNotEmpty())
        assertTrue(
            "at least one task row must parse a storeHint (null on every row before #433)",
            taskEntries.any { it.storeHint != null },
        )
        assertTrue(
            "at least one task row must parse a deadline",
            taskEntries.any { it.deadline != null },
        )
    }

    // =========================================================================
    // Per-offer dedupe keys (#427)
    // =========================================================================

    @Test
    fun `distinct offers resolve distinct dedupe keys via the parsedHash token`() {
        // The old 'offer-ss-{offerHash}' template stayed literal (offerHash is
        // computed in ParsedFieldsFactory, not a raw parse field), so every
        // offer shared ONE dedupe key and the second offer inside the 60s
        // throttle window was silently swallowed. The reserved {parsedHash}
        // token resolves post-factory (DedupeTokens — same call the classifier
        // makes) to the typed parse's content identity.
        val snapshots = TestResourceLoader.loadSnapshots("snapshots/offer_popup")
        assertTrue("offer corpus must not be empty", snapshots.isNotEmpty())

        val keysByFile = snapshots.mapNotNull { (fn, tree, _) ->
            val result = screenRuleset.matchFirst(tree, platformWire = "doordash")
                ?: return@mapNotNull null
            if (result.intent != "offer_popup") return@mapNotNull null
            val parsed = ParsedFieldsFactory.create(result.shape, result.fields)
            val resolved = DedupeTokens.resolve(result.effects, parsed)
            val key = resolved.firstNotNullOfOrNull { e ->
                e.dedupeKey?.takeIf { it.startsWith("offer-ss-") }
            } ?: return@mapNotNull null
            fn to key
        }

        assertTrue("expected offer screenshots with dedupe keys", keysByFile.size >= 2)
        assertTrue(
            "no unresolved template tokens may survive resolution: $keysByFile",
            keysByFile.none { (_, k) -> k.contains('{') },
        )
        assertTrue(
            "distinct offers must resolve DISTINCT dedupe keys (was one literal for all): $keysByFile",
            keysByFile.map { it.second }.toSet().size > 1,
        )
    }

    @Test
    fun `the same offer resolves the same dedupe key on re-observation`() {
        val (_, tree, _) = TestResourceLoader.loadSnapshots("snapshots/offer_popup").first()
        fun resolveOnce(): String? {
            val result = screenRuleset.matchFirst(tree, platformWire = "doordash") ?: return null
            val parsed = ParsedFieldsFactory.create(result.shape, result.fields)
            return DedupeTokens.resolve(result.effects, parsed)
                .firstNotNullOfOrNull { e -> e.dedupeKey?.takeIf { it.startsWith("offer-ss-") } }
        }
        val first = resolveOnce()
        assertNotNull("offer snapshot must resolve a screenshot dedupe key", first)
        assertEquals("re-observation must dedupe against the same key", first, resolveOnce())
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun raw(title: String? = null, text: String? = null, bigText: String? = null) =
        RawNotificationData(
            title = title, text = text, bigText = bigText, tickerText = null,
            packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
        )
}
