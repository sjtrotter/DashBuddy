package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.SnapshotSecurityScanner
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Guards for the Uber offer-expiry overlay (#858).
 *
 * When a request dies, Uber renders `message_content_overlay` / `image_message_text`
 * ("This request is no longer available") **over the still-present, dimmed card** —
 * inside `primary_touch_area`, so every arm of `uber.screen.offer`'s presence-only
 * `require` still held and the rule kept matching a dead offer. The message TextView
 * then won the `storeName` read (it is the first card TextView that is not a
 * price/badge/duration/dropoff line), which on 2026-07-23 shipped
 * `offer_records.merchantName = "This request is no longer available"` (seq 735),
 * spoke it over TTS, named an evidence screenshot with it, and — since
 * `presentationKey = sha256(storeNames|orders.size|orderTypes)` is built from
 * `orders[].storeName` — poisoned the #830 offer identity into a spurious REPLACE,
 * an `OFFER_TIMEOUT("Replaced by new offer")` on the live offer, and a phantom row.
 *
 * The fix is two layers of rule data, both in `matchers/rules/uber.json5`:
 *  1. **primary** — a `notExists` of `image_message_text` in the offer rule's
 *     `require`, so an overlay-bearing frame cannot match `uber.screen.offer`;
 *  2. **defense in depth** — the overlay's id *and* text added to both `storeName`
 *     negation lists (top-level and `orders[]`), so a future overlay variant that
 *     slips past layer 1 still cannot win the store read.
 *
 * These fixtures are the negative half. UNKNOWN and flow-less board recognition are
 * both correct fail-directions: the invariant is **no state, no offer**. UNKNOWN is
 * dropped before the state machine; the recognize-only board has no `state` block
 * and cannot mint `OFFER_RECEIVED` (also pinned by `FlowlessRecognitionNeutralityTest`).
 * The pending offer stays live until the next real frame or its own `OFFER_EXPIRY`
 * timer resolves it. Recognizing the
 * overlay as its own flow — the platform's explicit "this was not a decline" witness
 * — is #251's H3a and is deliberately deferred to the Trip Radar board design.
 *
 * The positive half lives in [CleanOfferStillRecognized] below (and, corpus-wide, in
 * [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.GoldenSnapshotRegressionTest]).
 */
@RunWith(Enclosed::class)
class UberOfferExpiryOverlayTest {

    /** Every overlay-bearing capture must be UNKNOWN or a flow-less board frame. */
    @RunWith(Parameterized::class)
    class OverlayFramesNeverOffer(
        private val filename: String,
        private val node: UiNode,
    ) {

        companion object {
            private const val FIXTURES = "fixtures/uber_offer_expiry_overlay"

            // CompiledRule flattens state into branches; retain the generated definition
            // too, so even an empty state block cannot silently weaken this guard.
            private val ruleDefinitions by lazy {
                requireNotNull(File(TestRulesetFactory.rulesDir).listFiles { file -> file.extension == "json" })
                    .sortedBy { it.name }
                    .flatMap { file ->
                        Json.parseToJsonElement(file.readText()).jsonObject["screens"]
                            ?.jsonArray.orEmpty().map { it.jsonObject }
                    }
                    .associateBy { it.getValue("id").jsonPrimitive.content }
            }

            @JvmStatic
            @Parameterized.Parameters(name = "{0}")
            fun data(): Collection<Array<Any>> {
                val loaded = TestResourceLoader.loadSnapshots(FIXTURES)
                assertTrue("fixtures/$FIXTURES must not be empty", loaded.isNotEmpty())
                return loaded.map { (filename, node, _) -> arrayOf(filename, node) }
            }

            private fun hasOverlay(node: UiNode): Boolean =
                node.viewIdResourceName?.endsWith("image_message_text", ignoreCase = true) == true ||
                    node.children.any(::hasOverlay)
        }

        /**
         * Pins that each fixture really is a #858 specimen — it carries the expiry
         * overlay node. Without this the no-offer assertion below could pass on any
         * unrelated tree and the regression would be untested.
         */
        @Test
        fun `fixture carries the expiry overlay node`() {
            assertTrue(
                "$filename no longer carries image_message_text — it is not a #858 specimen " +
                    "any more; replace it with a real expiring-card capture",
                hasOverlay(node),
            )
        }

        @Test
        fun `an expiring card is never a live offer — UNKNOWN or a flow-less board frame`() {
            val ruleset = TestRulesetFactory.screenRuleset
            val match = ruleset.matchFirst(node)
            assertFalse(
                "$filename matched '${match?.ruleId}' — the platform is saying this request is GONE, " +
                    "so nothing on this frame may mint OFFER_RECEIVED, name a screenshot, or " +
                    "re-identify the presentation (#858)",
                match?.ruleId == "uber.screen.offer",
            )
            if (match != null) {
                val rule = ruleset.ruleById(match.ruleId)
                assertNotNull("$filename: matched rule '${match.ruleId}' must exist", rule)
                rule!!.branches.forEach { branch ->
                    assertNull("$filename: an expiring card must have no flow (#858)", branch.flow)
                    assertNull("$filename: an expiring card must have no mode hint (#858)", branch.modeHint)
                    assertNull("$filename: an expiring card must have no offer surface (#858)", branch.offerSurface)
                }
                assertNull(
                    "$filename: an expiring card must have no state, no offer (#858)",
                    ruleDefinitions.getValue(rule.id)["state"],
                )
                assertEquals(
                    "$filename: only recognize-only board recognition is allowed",
                    "uber.screen.trip_radar_board",
                    match.ruleId,
                )
            }
        }

        /** These are committed captures; hold them to the same bar as the corpus. */
        @Test
        fun `fixture carries no dasher-sensitive content`() {
            assertFalse(
                "$filename tripped the security scanner — redact or drop it",
                SnapshotSecurityScanner.scan(node).isToxic,
            )
        }
    }

    /**
     * The positive counterweight: layer 1 is a `notExists`, and a `notExists` that is
     * too broad silently deletes recognition. A clean (non-expiring) Uber offer must
     * still classify `offer` AND still parse a real store name — the field the
     * overlay used to steal.
     */
    class CleanOfferStillRecognized {

        @Test
        fun `a clean uber offer still classifies offer and parses its store`() {
            val clean = TestResourceLoader.loadSnapshots("snapshots/offer")
                .filter { (name, _, _) -> name.contains("__uber__") }
            assertTrue(
                "no committed uber offer snapshots found — the over-rejection guard is vacuous",
                clean.isNotEmpty(),
            )
            clean.forEach { (filename, node, _) ->
                val match = TestRulesetFactory.screenRuleset.matchFirst(node)
                assertEquals(
                    "$filename no longer classifies 'offer' — the #858 notExists over-rejects",
                    "offer",
                    match?.intent,
                )
                val store = match?.fields?.get("storeName") as? String
                assertNotNull("$filename parsed no storeName", store)
                assertTrue(
                    "$filename parsed the expiry overlay as its store",
                    store!!.isNotBlank() && !store.contains("no longer available", ignoreCase = true),
                )
            }
        }
    }
}
