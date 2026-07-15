package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #762 D10 — asset-derived coverage drift test for [SensitiveTextMarkers].
 *
 * [SensitiveTextMarkers.KEYWORDS] is a rules-INDEPENDENT backstop (by design, per its own
 * KDoc): it must keep working even when a ruleset fails to load. That independence is exactly
 * why it can silently drift out of sync with what the (trusted, in-tree) rulesets actually
 * consider sensitive — a platform's `sensitive`-parsed rule can ship a NEW text anchor with no
 * keyword overlap and nothing fails until a real screen carrying only that phrase (no
 * co-occurring covered wording) leaks to UNKNOWN capture in the field. This test closes that
 * loop: it reads the SAME canonicalized rule assets [TestRulesetFactory] compiles from
 * (the per-platform `matchers/rules` JSON5 sources → `:core:pipeline:importMatchersRules` →
 * generated `assets/rules` JSON), walks every `parse.as == "sensitive"` rule's `require` tree across
 * BOTH `screens` and `notifications` sections (doordash's Crimson-balance push is a sensitive
 * NOTIFICATION rule, not a screen), and asserts every OR-alternative that can independently
 * fire the rule carries at least one text anchor [SensitiveTextMarkers.KEYWORDS] would catch.
 *
 * ### Coverage semantics (why this isn't a flat "any anchor in the rule" check)
 *
 * A `require` tree is a boolean predicate. Naively checking "does ANY text anchor anywhere in
 * this rule overlap a keyword" is unsound: it lets an `any` (OR) alternative that fires the
 * rule ALONE — with none of its sibling alternatives' text — hide behind a covered SIBLING
 * that never actually appears on that screen. (Uber's own pre-fix state proved this: `Instant
 * Pay` already covered the `wallet` branch's OR, so a naive check passed even though the
 * `Uber Pro Card`-only alternative was a real, uncaught gap — the exact class of bug D10
 * fixes.) So this test converts each unit's `require` into the [Req] shape and evaluates it to
 * a tri-state [Coverage] (COVERED / GAP / EXEMPT), not a plain boolean — a bare boolean can't
 * distinguish "no text anchor overlaps a keyword" (a real gap) from "this alternative has no
 * text at all" (an id-based predicate, structurally unfixable by a text backstop, per the
 * task's own example) without conflating the two inside an `any`/OR group:
 *  - [Req.Leaf] — a literal text predicate (`hasText*`, `hasDesc*`, `hasStateDescription*`,
 *    `allTextContains`, notification `title/text/bigText/tickerText/anyField*Contains`,
 *    `hasAction`) — COVERED iff [SensitiveTextMarkers.findMarker] fires on it (the production
 *    matcher itself, not a mirrored `contains` — so the guard tracks any future semantics
 *    change; `contentDescription` is included because [UiNode.allText] folds it in too),
 *    else GAP.
 *  - [Req.NonText] — a predicate with no literal text (`hasId*`, `hasClassName*`, boolean
 *    flags, `channelId*`, `categoryEquals`, and `hasTextMatchesRegex`/`*MatchesRegex` since a
 *    regex source isn't the literal on-screen text) — always EXEMPT: this branch of the
 *    predicate tree cannot be verified by ANY text marker, by construction, so it neither
 *    passes nor fails coverage on its own.
 *  - [Req.AnyOf] (an `any`/OR list, incl. `allTextContainsAny`/`anyFieldContainsAny`) — the
 *    rule can fire via just ONE child alone, so: GAP if any child is GAP (a real,
 *    independently-firing, uncovered text alternative); else EXEMPT if every child is EXEMPT
 *    (no text anywhere in this OR — the id-based-only case); else COVERED (every
 *    text-bearing alternative was independently covered; EXEMPT siblings are unprovable and
 *    don't block).
 *  - [Req.AllOf] (an `all`/AND list, incl. `allTextContainsAll`/`anyFieldContainsAll`) — every
 *    child's text is guaranteed present together when the rule fires, so ONE covered sibling
 *    is enough: COVERED if any child is COVERED; else EXEMPT if every child is EXEMPT; else GAP
 *    (real text exists in the group and none of it is covered).
 *
 * ### The [KNOWN_GAPS] ledger
 *
 * #762 D10 is scoped to Uber (see [SensitiveTextMarkers.KEYWORDS]'s `uber.screen.sensitive.*`
 * group) — Uber's units are all fully covered by this test with zero exclusions. Building this
 * test surfaced pre-existing DoorDash gaps that are real but out of D10's declared scope; they
 * are named here rather than silently passed over, mirroring the `timber-tag-guard-allowlist`
 * pattern (CLAUDE.md "Semantic, PII-safe logging"): a visible, shrink-only debt list. The test
 * also fails if a listed entry stops being a real gap (fixed but not removed here) — same
 * ratchet discipline as the Timber guard.
 */
class SensitiveMarkerAssetCoverageTest {

    /** A boolean-normalized `require` predicate, reduced to what matters for coverage. */
    private sealed class Req {
        data class Leaf(val text: String) : Req()
        data class NonText(val kind: String) : Req()
        data class AnyOf(val children: List<Req>) : Req()
        data class AllOf(val children: List<Req>) : Req()
    }

    /** Tri-state coverage verdict — see the class KDoc for why a plain boolean is insufficient. */
    private enum class Coverage { COVERED, GAP, EXEMPT }

    /** A single independently-evaluable sensitive-rule unit: a named branch, or a whole
     *  branchless rule (its `require` IS the unit). */
    private data class Unit(val key: String, val req: Req)

    private fun parseReq(json: JsonElement): Req {
        val obj = json as? JsonObject ?: return Req.NonText("non-object")
        val key = obj.keys.firstOrNull() ?: return Req.NonText("empty")
        val value = obj.getValue(key)
        return when (key) {
            in TEXT_LEAF_KEYS -> Req.Leaf((value as JsonPrimitive).content)
            "allTextContains" -> Req.Leaf((value as JsonPrimitive).content)
            in OR_ARRAY_KEYS -> Req.AnyOf(textArray(value).map { Req.Leaf(it) })
            in AND_ARRAY_KEYS -> Req.AllOf(textArray(value).map { Req.Leaf(it) })
            in PASSTHROUGH_KEYS -> parseReq(value)
            "all" -> Req.AllOf((value as JsonArray).map { parseReq(it) })
            "any" -> Req.AnyOf((value as JsonArray).map { parseReq(it) })
            else -> Req.NonText(key) // hasId*, hasClassName*, bool flags, *MatchesRegex, channelId*, categoryEquals, ...
        }
    }

    private fun textArray(value: JsonElement): List<String> =
        (value as JsonArray).map { it.jsonPrimitive.content }

    private fun evaluate(req: Req): Coverage = when (req) {
        is Req.Leaf ->
            // Delegate to the production matcher itself (P5 — adversarial-review finding 2):
            // if findMarker's semantics ever change (word boundaries, normalization), the
            // guard tracks them instead of silently diverging from a hand-mirrored contains().
            if (SensitiveTextMarkers.findMarker(req.text) != null) Coverage.COVERED
            else Coverage.GAP
        is Req.NonText -> Coverage.EXEMPT
        is Req.AnyOf -> {
            val results = req.children.map { evaluate(it) }
            when {
                results.any { it == Coverage.GAP } -> Coverage.GAP
                results.all { it == Coverage.EXEMPT } -> Coverage.EXEMPT
                else -> Coverage.COVERED
            }
        }
        is Req.AllOf -> {
            val results = req.children.map { evaluate(it) }
            when {
                results.any { it == Coverage.COVERED } -> Coverage.COVERED
                results.all { it == Coverage.EXEMPT } -> Coverage.EXEMPT
                else -> Coverage.GAP
            }
        }
    }

    private fun leafTexts(req: Req): List<String> = when (req) {
        is Req.Leaf -> listOf(req.text)
        is Req.NonText -> emptyList()
        is Req.AnyOf -> req.children.flatMap { leafTexts(it) }
        is Req.AllOf -> req.children.flatMap { leafTexts(it) }
    }

    private fun extractSensitiveUnits(): List<Unit> {
        val dir = File(TestRulesetFactory.rulesDir)
        val units = mutableListOf<Unit>()
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                for (section in listOf("screens", "notifications")) {
                    val rules = root[section]?.jsonArray ?: continue
                    for (ruleEl in rules) {
                        val rule = ruleEl.jsonObject
                        val parseAs = rule["parse"]?.jsonObject?.get("as")?.jsonPrimitive?.contentOrNull
                        if (parseAs != "sensitive") continue
                        val id = rule["id"]?.jsonPrimitive?.contentOrNull ?: "<unknown-id>"
                        val branches = rule["branches"]?.jsonArray
                        if (branches != null) {
                            branches.forEachIndexed { i, branchEl ->
                                val branch = branchEl.jsonObject
                                val intent = branch["intent"]?.jsonPrimitive?.contentOrNull ?: "branch$i"
                                val requireJson = branch["require"] ?: return@forEachIndexed
                                units += Unit("$id::$intent", parseReq(requireJson))
                            }
                        } else {
                            val requireJson = rule["require"] ?: continue
                            units += Unit(id, parseReq(requireJson))
                        }
                    }
                }
            }
        return units
    }

    @Test
    fun `every sensitive-rule text anchor across all platforms is covered by SensitiveTextMarkers`() {
        val units = extractSensitiveUnits()
        assertTrue("expected to find sensitive rules in the generated assets", units.isNotEmpty())

        val uncovered = units.filter { evaluate(it.req) == Coverage.GAP }
        val newGaps = uncovered.filterNot { it.key in KNOWN_GAPS }
        assertTrue(
            "New sensitive-rule coverage gap(s) — a real screen/notification could fire this " +
                "rule via text SensitiveTextMarkers.KEYWORDS would NOT catch on its own. Add a " +
                "marker, or if genuinely out of scope for this change, add a justified entry " +
                "to KNOWN_GAPS:\n" +
                newGaps.joinToString("\n") { "  ${it.key} — anchors=${leafTexts(it.req)}" },
            newGaps.isEmpty(),
        )

        // Ratchet (mirrors the Timber-tag-guard allowlist): a KNOWN_GAPS entry that no longer
        // names a real gap (fixed, or the rule/branch was renamed/removed) must be deleted here.
        val staleGaps = KNOWN_GAPS.filter { key -> units.none { it.key == key && evaluate(it.req) == Coverage.GAP } }
        assertTrue(
            "KNOWN_GAPS entries no longer represent a real, still-uncovered unit — remove them " +
                "(the list only shrinks): $staleGaps",
            staleGaps.isEmpty(),
        )
    }

    companion object {
        private val TEXT_LEAF_KEYS = setOf(
            // node text predicates (screens)
            "hasText", "hasTextCaseSensitive", "hasTextContaining", "hasTextStartsWith", "hasAnyText",
            "hasDesc", "hasDescContaining", "hasStateDescription", "hasStateDescriptionContaining",
            // notification text predicates
            "titleEquals", "titleContains", "textEquals", "textContains",
            "bigTextContains", "tickerTextContains", "anyFieldContains", "hasAction",
        )

        /** Array-of-strings predicates with OR semantics (any one alone can fire the rule). */
        private val OR_ARRAY_KEYS = setOf("allTextContainsAny", "anyFieldContainsAny")

        /** Array-of-strings predicates with AND semantics (all present together when it fires). */
        private val AND_ARRAY_KEYS = setOf("allTextContainsAll", "anyFieldContainsAll")

        /**
         * Tree→node bridges: transparent to text-anchor extraction. Negation (`not`/`notExists`)
         * is deliberately NOT here — a negated text predicate guarantees its text is ABSENT when
         * the rule fires, so treating a covered leaf under it as COVERED would be a false pass;
         * negation falls to the [Req.NonText]/EXEMPT arm instead (adversarial-review finding 1).
         */
        private val PASSTHROUGH_KEYS = setOf("exists")

        /**
         * Pre-existing coverage gaps NOT fixed by #762 D10 (D10 is scoped to Uber — see the
         * `uber.screen.sensitive.*` KEYWORDS group). Every Uber unit passes with zero
         * exclusions; everything below is DoorDash debt this test surfaced as a side effect of
         * being asset-derived rather than Uber-hardcoded. Each entry is `<ruleId>::<branchIntent>`
         * (or a bare `<ruleId>` for a branchless rule/notification), with the specific
         * uncovered alternative noted so a future fix can grep straight to it.
         */
        private val KNOWN_GAPS = setOf(
            // "Withdraw" + "available" (an AND pair) — neither term overlaps a keyword.
            "doordash.screen.sensitive.known::sensitive.withdraw",
            // The third OR-alternative, all["Transfer in", "available"], has no covered term
            // ("Savings jar" and "You transferred" ARE keywords, but this alternative can fire alone).
            "doordash.screen.sensitive.known::sensitive.savings",
            // "ready to start verification" is an OR-alternative with no keyword (the other four
            // alternatives of this branch are all keywords).
            "doordash.screen.sensitive.known::sensitive.id_verification",
            // "transfer was initiated" — no keyword overlap.
            "doordash.screen.sensitive.known::sensitive.transfer",
            // "Payout details" / "Change payout method" / "Recent payout history" — none covered.
            "doordash.screen.sensitive.known::sensitive.payout_settings",
            // "Fingerprint sensor" / "Touch the fingerprint" / "to secure your account" / "Use PIN"
            // — none contain the "Biometric" keyword or any other; that keyword covers a
            // DIFFERENT literal string ("Biometric") than what this branch's own leaves say.
            "doordash.screen.sensitive.known::sensitive.biometric",
            // "Personal information"+"First name" / "Manage Account"+"Marketing Choices" /
            // "Request account data" / "Request data archive" — none covered.
            "doordash.screen.sensitive.known::sensitive.account_pii",
            // The low-confidence allTextContainsAny net: "Account number", "Verify Identity",
            // "Debit card", "Card status", "Lock card", "View card details", "Withdraw",
            // "transfer was initiated" are each, standalone, uncovered OR-alternatives in this list.
            "doordash.screen.sensitive.catchall",
            // any["savings jar"(covered), "building momentum"(NOT covered)] — the second
            // alternative alone would leak.
            "doordash.notification.crimson_balance",
        )
    }
}
