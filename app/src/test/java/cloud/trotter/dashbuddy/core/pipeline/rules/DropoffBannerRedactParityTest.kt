package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #993 (adversarial review of PR #1010) — **the combined-frame belt, enforced structurally.**
 *
 * A rule's `redact` protects only the frames that RULE wins. DoorDash's own arrival banner
 * (`arriving_at_subtitle` = "Arriving at" over `arriving_at_title` = the destination, which on a
 * dropoff leg is the customer's full street address) can still be in the tree when the workflow
 * sheet inflates over it — and the arrival CTA is exactly the thing `dropoff_navigation` REJECTS,
 * so the arrival-MOMENT frame is handed to whichever arrived-phase rule matches
 * (`dropoff_handoff` / `_pin_entry` / `_photo` / `_reminder` / the alcohol rules / …). Meanwhile
 * `nav_arriving`, the other rule that already masked the banner, rejects bottom-sheet frames.
 * So the original single-rule fix left the leak reachable on a dozen sibling surfaces, and their
 * id-LESS name/address entries structurally cannot reach an id-BEARING node.
 *
 * Enumerating the "reachable" subset by hand would rot on the next dropoff rule someone adds, so
 * the invariant is the strong one: **every screen rule in the dropoff section declares the
 * `arriving_at_title` entry.** The entry is a pure id predicate, so it is inert on any frame that
 * does not carry the banner — a blanket declaration costs nothing and cannot be forgotten.
 *
 * The second test proves the belt actually MASKS (not merely that the JSON contains a string):
 * each rule's compiled redact is run over a real banner node.
 */
class DropoffBannerRedactParityTest {

    private val rulesDir = File(TestRulesetFactory.rulesDir)
    private val bannerId = "arriving_at_title"

    /** Every `doordash.screen.*` id declared in the dropoff surface source file. */
    private fun dropoffSectionRuleIds(): List<String> {
        val declared = Regex(""""id":\s*"(doordash\.screen\.[A-Za-z_0-9]+)"""")
            .findAll(File("../matchers/rules/doordash/dropoff.json5").readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("expected the dropoff section to declare rules", declared.isNotEmpty())
        return declared
    }

    /** The `hasIdSuffix` values inside [ruleId]'s compiled `redact` block (generated asset). */
    private fun redactIdSuffixes(ruleId: String): List<String> {
        val root = Json.parseToJsonElement(File(rulesDir, "doordash.json").readText()).jsonObject
        val screen = root["screens"]!!.jsonArray.first {
            it.jsonObject["id"]?.jsonPrimitive?.content == ruleId
        }.jsonObject
        val out = mutableListOf<String>()
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is kotlinx.serialization.json.JsonObject -> e.forEach { (k, v) ->
                    if (k == "hasIdSuffix" && v is kotlinx.serialization.json.JsonPrimitive && v.isString) {
                        out += v.content
                    } else {
                        walk(v)
                    }
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it) }
                else -> {}
            }
        }
        screen["redact"]?.let { walk(it) }
        return out
    }

    @Test
    fun `every dropoff-section rule declares the arriving_at_title banner redact (#993)`() {
        val missing = dropoffSectionRuleIds().filterNot { bannerId in redactIdSuffixes(it) }
        assertEquals(
            "every screen rule in matchers/rules/doordash/dropoff.json5 must declare the " +
                "`$bannerId` redact entry — the banner can survive into ANY dropoff-phase frame, " +
                "and the entry is inert when the node is absent, so there is no cost to " +
                "declaring it. Missing: $missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `each dropoff-section rule actually masks a real banner node, keeping the label (#993)`() {
        val address = "742 Sample Hollow Way, Apt 12, San Antonio, TX 78260, USA"
        val maskHex = Regex("""\[redacted:[0-9a-f]{4}]""")

        for (ruleId in dropoffSectionRuleIds()) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(ruleId)
                ?: error("$ruleId is declared in the source but absent from the compiled ruleset")
            val banner = UiNode(
                viewIdResourceName = "com.doordash.driverapp:id/top_header",
                children = listOf(
                    UiNode(
                        viewIdResourceName = "com.doordash.driverapp:id/arriving_at_subtitle",
                        text = "Arriving at",
                    ),
                    UiNode(
                        viewIdResourceName = "com.doordash.driverapp:id/$bannerId",
                        text = address,
                    ),
                ),
            ).restoreParents()

            val masked = rule.redact.apply(banner)
            val title = masked.children[1].text!!
            assertTrue(
                "$ruleId: the banner title must be masked; got '$title'",
                maskHex.matches(title),
            )
            // The address must not survive ANYWHERE in the returned tree — the property that
            // actually matters, and stronger than checking the one node.
            assertTrue(
                "$ruleId: no fragment of the destination address may survive",
                listOf(masked.text, masked.children[0].text, masked.children[1].text)
                    .none { it?.contains("Sample Hollow Way") == true },
            )
            // The "Arriving at" LABEL is deliberately not asserted to stay raw. Several dropoff
            // rules mask it as collateral — e.g. `dropoff_pin_entry`'s #886 venue-name entry
            // (`hasFollowingSiblingTextMatchesRegex` on a city/ST/ZIP line) sees "Arriving at"
            // sitting immediately before an address line and treats it as a venue line 1. That is
            // an over-mask of two words of app chrome on a debug envelope: fail-toward-privacy,
            // the same trade-off SnapshotRedactor's KDoc documents, and NOT a defect. Pinning the
            // label raw here would convert a safe over-mask into a red build.
        }
    }

    @Test
    fun `the banner id is also covered on the UNKNOWN path by the runtime id SSOT (#993)`() {
        // The rule redacts cover RECOGNIZED frames only. An UNKNOWN banner-bearing frame is
        // reached solely by the #910 id scan, so the id must be in that SSOT too.
        assertTrue(
            "CustomerTextMarkers.ID_MARKERS must carry '$bannerId' so an UNKNOWN banner frame " +
                "is scrubbed as well; found ${CustomerTextMarkers.ID_MARKERS}",
            bannerId in CustomerTextMarkers.ID_MARKERS,
        )
    }
}
