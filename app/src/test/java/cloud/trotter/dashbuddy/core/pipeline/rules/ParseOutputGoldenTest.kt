package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Corpus-wide parse-OUTPUT golden guard (#433).
 *
 * The intent-only golden ([GoldenSnapshotRegressionTest]) proved insufficient:
 * the timeline rule's mojibake separator made `storeHint` parse null on every
 * real capture while the whole suite stayed green. This harness pins the
 * **typed fields** — the engine's actual product — for every golden snapshot:
 *
 * 1. Every snapshot under an intent folder is classified with the production
 *    rulesets and its `ParsedFields.toFieldMap()` projection is compared to
 *    the approved file (`snapshots/approved-parse-output.json`).
 * 2. A deliberate rule/parser change regenerates the approval:
 *    `./gradlew :app:testDebugUnitTest --tests "*ParseOutputGoldenTest*" -DupdateParseGolden=true`
 *    then REVIEW THE DIFF (that review is the regression gate) and commit.
 *
 * Determinism: time-of-day transforms are anchored to a fixed clock
 * ([TransformRegistry.withClock]) so `ParsedTime.time` is machine- and
 * zone-independent.
 *
 * Companion guards in this file:
 * - corpus coverage ratchet — every screen-rule intent should eventually have
 *   a golden folder; the known-missing set is pinned and may only shrink.
 * - dedupeKey lint — a `{field}` template in a matched rule's effect must
 *   reference a field that actually parses non-null somewhere in that rule's
 *   corpus (the #427 class of bug). Only rules with corpus coverage are
 *   linted — the ratchet above is what makes that blind spot visible.
 */
class ParseOutputGoldenTest {

    companion object {
        /** Not intent-named goldens (SENSITIVE is deliberately never parsed). */
        private val SKIP = setOf("INBOX", "UNKNOWN", "SENSITIVE")

        private const val APPROVED_PATH = "src/test/resources/snapshots/approved-parse-output.json"
        private const val UPDATE_FLAG = "updateParseGolden"

        /** Arbitrary but FIXED anchor (2026-06-01T00:00:00Z, UTC) — see kdoc. */
        private const val FIXED_NOW_MS = 1_780_272_000_000L
        private val FIXED_ZONE = ZoneId.of("UTC")

        private val json = Json { prettyPrint = true }
    }

    private val screenRuleset: Ruleset<UiNode> get() = TestRulesetFactory.screenRuleset

    // =========================================================================
    // 1. The approval guard
    // =========================================================================

    @Test
    fun `corpus parse output matches the approved golden file`() {
        val actual = JsonObject(generateParseOutput())
        val approvedFile = File(APPROVED_PATH)

        if (System.getProperty(UPDATE_FLAG) == "true" || !approvedFile.exists()) {
            approvedFile.writeText(json.encodeToString(JsonObject.serializer(), actual) + "\n")
            fail(
                "approved-parse-output.json (re)generated with ${actual.size} snapshots — " +
                    "review the diff (that review IS the regression gate) and commit it.",
            )
        }

        val approved = json.parseToJsonElement(approvedFile.readText()).jsonObject
        if (approved == actual) return

        val diffs = buildList {
            (approved.keys - actual.keys).take(5)
                .forEach { add("GONE (approved but no longer produced): $it") }
            (actual.keys - approved.keys).take(5)
                .forEach { add("NEW (produced but not approved): $it") }
            actual.keys.intersect(approved.keys)
                .filter { approved[it] != actual[it] }
                .take(10)
                .forEach {
                    add("CHANGED: $it\n  approved: ${approved[it]}\n  actual:   ${actual[it]}")
                }
        }
        fail(
            "Parse output drifted from the approved golden (#433). If the change is deliberate, " +
                "regenerate with -D$UPDATE_FLAG=true and review the diff.\n" +
                diffs.joinToString("\n"),
        )
    }

    /** snapshots/<folder>/<file> → {ruleId, intent, shape, fields} — sorted, normalized. */
    private fun generateParseOutput(): Map<String, JsonElement> {
        val base = File("src/test/resources/snapshots")
        val dirs = base.listFiles { f -> f.isDirectory && f.name !in SKIP }
            ?.sortedBy { it.name } ?: emptyList()
        assertTrue("golden corpus must not be empty", dirs.isNotEmpty())

        val out = LinkedHashMap<String, JsonElement>()
        for (dir in dirs) {
            for ((fn, node, _) in TestResourceLoader.loadSnapshots("snapshots/${dir.name}")) {
                out["${dir.name}/$fn"] = TransformRegistry.withClock(FIXED_NOW_MS, FIXED_ZONE) {
                    val result = screenRuleset.matchFirst(node)
                        ?: return@withClock JsonObject(mapOf("intent" to JsonPrimitive("UNKNOWN")))
                    val fields = ParsedFieldsFactory.create(result.shape, result.fields).toFieldMap()
                    JsonObject(
                        linkedMapOf<String, JsonElement>(
                            "ruleId" to JsonPrimitive(result.ruleId),
                            "intent" to JsonPrimitive(result.intent),
                            "shape" to (result.shape?.let(::JsonPrimitive) ?: JsonNull),
                            "fields" to JsonObject(
                                fields.entries.sortedBy { it.key }
                                    .associate { (k, v) -> k to normalize(v) },
                            ),
                        ),
                    )
                }
            }
        }
        return out
    }

    /**
     * Stable JSON projection of a parsed field value. [ParsedTime] is split
     * explicitly (its millis are fixed-clock-stable; embedding it via
     * toString would also work but this reads better in diffs). Unknown
     * complex types (small data classes over primitives) fall back to
     * toString — deterministic under the fixed clock.
     */
    private fun normalize(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is ParsedTime -> JsonObject(
            mapOf(
                "text" to JsonPrimitive(v.text),
                "time" to (v.time?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
        is Collection<*> -> JsonArray(v.map(::normalize))
        is Map<*, *> -> JsonObject(
            v.entries.sortedBy { it.key.toString() }
                .associate { it.key.toString() to normalize(it.value) },
        )
        else -> JsonPrimitive(v.toString())
    }

    // =========================================================================
    // 2. Corpus coverage ratchet
    // =========================================================================

    /**
     * Screen-rule intents with no golden corpus folder yet (#433 noted 13 DD
     * intents + all of Uber). This set may only SHRINK: when you add corpus
     * for one of these, delete it here; a NEW intent must ship with corpus
     * (or be added here deliberately, in review).
     */
    private val knownUncoveredIntents = setOf(
        // DoorDash — rules added from triage/synthetic fixtures, no capture yet
        "customer_chat",
        "delivery_confirmation",
        "dropoff_geofence_warning",
        "dropoff_pin_entry",
        "dropoff_pre_arrival_completion",
        "earnings",
        "nav_arriving",
        "offer",
        "photo_capture",
        "pickup_issue_menu",
        "pickup_picked_up",
        "pickup_post_arrival_multi",
        "pickup_pre_arrival_multi",
        "pickup_verification_info",
        "pickup_verification_items",
        "pickup_verification_pin",
        "pickup_verify",
        // #501 item 3 — GoPuff (Drive) zone-arrival, recognize-only (dev decision 2026-07-07).
        // Anchor strings are grounded (verbatim from issue #501's 2026-06-15 deep-dive over the
        // real 06-14 captures), but the raw capture JSON itself was never committed to
        // snapshots/INBOX before this build — only GoPuffRecognitionTest's hand-built UiNode
        // covers it today. NOTE the anchors are NOT GoPuff-unique (every regular
        // pickup_pre_arrival tree carries them); the rule leans on priority order + its rejects,
        // so a real capture matters doubly here. Shrink this when one lands in the corpus.
        "pickup_zone_arrival",
        "safety",
        "shop_and_pay_checkout",
        "shop_and_pay_list",
        "shopping_checkout",
        "splash",
        // Uber — zero snapshots in the corpus today (#433)
        "active_trip",
        "awaiting_offer",
        "earnings_activity",
        "home_dashboard",
        "post_trip",
        "session_summary",
    )

    @Test
    fun `screen-rule intent corpus coverage only ratchets up`() {
        val intents = compileProductionScreenRules()
            .flatMap { r -> r.branches.mapNotNull { it.intent } }
            .filterNot { it.startsWith("sensitive") } // blocked, never parsed — no goldens by design
            .toSet()
        val folders = File("src/test/resources/snapshots")
            .listFiles { f -> f.isDirectory && f.name !in SKIP }
            ?.map { it.name }?.toSet() ?: emptySet()

        assertEquals(
            "Corpus coverage gap changed. Intents without a golden folder must only shrink — " +
                "add corpus for closed gaps (and delete them from knownUncoveredIntents), and " +
                "ship corpus with new intents instead of growing the pin.",
            knownUncoveredIntents,
            intents - folders,
        )
    }

    // =========================================================================
    // 3. dedupeKey template lint (the #427 class)
    // =========================================================================

    /**
     * Dead dedupeKey templates already known (ratchet — may only shrink):
     * - `delivery_summary_expanded:sessionEarnings` — never parses non-null on
     *   any expanded-summary capture; either the screen stopped showing it or
     *   the extractor regressed. The expanded key still differentiates per
     *   delivery via its `{totalPay}` half, so dedupe works; clean up the dead
     *   half when the summary rules are next touched.
     * - `delivery_summary_collapsed:totalPay` — the mirror image: collapsed
     *   receipts parse sessionEarnings but never the per-delivery totalPay
     *   (it's behind the expand); the `{sessionEarnings}` half differentiates.
     * (The third original entry, `offer_popup:offerHash`, was the #427 bug —
     * fixed by the reserved `{parsedHash}` token, resolved post-factory by the
     * classifier via [DedupeTokens].)
     * Entries are "ruleId:field".
     */
    private val knownDeadDedupeTemplates = setOf(
        "doordash.screen.delivery_summary_expanded:sessionEarnings",
        "doordash.screen.delivery_summary_collapsed:totalPay",
    )

    @Test
    fun `dedupeKey templates reference fields the rule actually parses`() {
        val template = Regex("\\{(\\w+)}")
        // (ruleId, field) → did ANY corpus match of that rule parse it non-null?
        // Unsubstituted braces surviving in a matched effect's dedupeKey mean
        // the field was null on THAT match; only never-non-null-anywhere is a
        // violation (a sometimes-null field is legitimate).
        val seen = mutableMapOf<Pair<String, String>, Boolean>()
        val exampleKey = mutableMapOf<Pair<String, String>, String>()

        val base = File("src/test/resources/snapshots")
        val dirs = base.listFiles { f -> f.isDirectory && f.name !in SKIP }
            ?.sortedBy { it.name } ?: emptyList()
        for (dir in dirs) {
            for ((_, node, _) in TestResourceLoader.loadSnapshots("snapshots/${dir.name}")) {
                val result = TransformRegistry.withClock(FIXED_NOW_MS, FIXED_ZONE) {
                    screenRuleset.matchFirst(node)
                } ?: continue
                for (effect in result.effects) {
                    val dk = effect.dedupeKey ?: continue
                    for (m in template.findAll(dk)) {
                        val field = m.groupValues[1]
                        // Reserved tokens resolve post-factory in the
                        // classifier (DedupeTokens, #427) — never raw fields.
                        if (field in DedupeTokens.RESERVED_FIELD_NAMES) continue
                        val key = result.ruleId to field
                        seen[key] = (seen[key] ?: false) || (result.fields[field] != null)
                        exampleKey.putIfAbsent(key, dk)
                    }
                }
            }
        }

        val dead = seen.filterValues { !it }.keys
            .map { (rule, field) -> "$rule:$field" }
            .toSet()
        assertEquals(
            "Dead dedupeKey templates changed (fields that never parse non-null anywhere in " +
                "the rule's corpus — silently-dead dedupe, the #427/#433 class). Fixed one? " +
                "Remove it from knownDeadDedupeTemplates. Introduced one? Fix the rule.\n" +
                dead.joinToString("\n") { "  $it (e.g. '${exampleKey[it.split(":").let { p -> p[0] to p[1] }]}')" },
            knownDeadDedupeTemplates,
            dead,
        )
    }

    // =========================================================================
    // 4. Effect-arg {field} template lint (the #606 class)
    // =========================================================================

    /**
     * Effect-arg templates already known to leave a literal `{field}` in the
     * saved string on at least one corpus frame (ratchet — may only shrink,
     * same pattern as [knownDeadDedupeTemplates] above).
     *
     * Semantics (differs from the dedupeKey lint's wording): effect args are
     * scanned POST-resolution — a `{token}` survives in the value ONLY on a
     * frame where that field parsed null and so did not interpolate. So a key
     * lands here iff its template failed to interpolate on ≥1 corpus frame,
     * which is the right bar for a filename (a `Delivery - {totalPay}.png` on
     * ANY frame is a broken name on that frame).
     *
     * - `doordash.screen.delivery_summary_collapsed:totalPay` — the collapsed
     *   receipt parses `totalPay` non-null on most frames (it's usually behind
     *   the expand but present on the collapsed card too), yet a couple of
     *   corpus frames lack it, and each of THOSE saves a literal
     *   `Delivery - {totalPay}.png` at runtime — real #606-class instances.
     *   Pre-existing; out of scope for #606 (which only touches `offer_popup`'s
     *   prefix and the EffectMap double-fire). The `{totalPay}` parse is NOT
     *   dead — do not delete it as cleanup; fix by making the collapsed prefix
     *   use a field the rule parses on every frame, when the summary rules are
     *   next touched.
     * (`doordash.screen.offer_popup:storeName` was the #606 bug itself —
     * `storeName` lives inside the per-order `orders[]` array, never at the
     * rule's top level where template resolution reads from, so
     * `"Offer - {storeName}"` saved every offer screenshot as the literal
     * filename `Offer - {storeName}.png`. Fixed by switching to `{payAmount}`,
     * a field the rule guarantees non-null via its `fieldNotNull` validator.)
     * Entries are "ruleId:field".
     */
    private val knownDeadArgTemplates = setOf(
        "doordash.screen.delivery_summary_collapsed:totalPay",
    )

    @Test
    fun `effect arg templates reference fields the rule actually parses`() {
        val template = Regex("\\{(\\w+)}")
        // A (ruleId, field) whose {token} SURVIVED resolution in an effect arg
        // on ≥1 corpus frame — i.e. the template failed to interpolate there and
        // the saved string would carry a literal `{field}`. Args are scanned
        // post-resolution, so a surviving token IS the failure (no non-null
        // frame can leave the token behind). Mirrors the dedupeKey lint but
        // scans every effect ARG value (screenshot prefix, bubble text, log
        // payload, …) — the #606 bug (`"Offer - {storeName}"`) lived in a
        // `prefix` arg the dedupeKey-only lint never looked at.
        val dead = mutableSetOf<Pair<String, String>>()
        val exampleArg = mutableMapOf<Pair<String, String>, String>()

        val base = File("src/test/resources/snapshots")
        val dirs = base.listFiles { f -> f.isDirectory && f.name !in SKIP }
            ?.sortedBy { it.name } ?: emptyList()
        for (dir in dirs) {
            for ((_, node, _) in TestResourceLoader.loadSnapshots("snapshots/${dir.name}")) {
                val result = TransformRegistry.withClock(FIXED_NOW_MS, FIXED_ZONE) {
                    screenRuleset.matchFirst(node)
                } ?: continue
                for (effect in result.effects) {
                    for (argValue in effect.args.values) {
                        for (m in template.findAll(argValue)) {
                            // Any surviving token is a violation. Reserved tokens
                            // (DedupeTokens, #427) resolve for dedupeKey but NOT
                            // for args — a `{parsedHash}` in a prefix would ship
                            // literal, so it must be flagged here, not skipped.
                            val field = m.groupValues[1]
                            val key = result.ruleId to field
                            dead += key
                            exampleArg.putIfAbsent(key, argValue)
                        }
                    }
                }
            }
        }

        val deadStrings = dead
            .map { (rule, field) -> "$rule:$field" }
            .toSet()
        assertEquals(
            "Dead effect-arg {field} templates changed (a screenshot/bubble/log template that " +
                "left a literal {field} in the saved string on ≥1 corpus frame — i.e. failed to " +
                "interpolate, the #606 class). Fixed one? Remove it from " +
                "knownDeadArgTemplates. Introduced one? Fix the rule.\n" +
                deadStrings.joinToString("\n") { "  $it (e.g. '${exampleArg[it.split(":").let { p -> p[0] to p[1] }]}')" },
            knownDeadArgTemplates,
            deadStrings,
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun compileProductionScreenRules(): List<CompiledRule<UiNode>> {
        val dir = File(TestRulesetFactory.rulesDir)
        val all = mutableListOf<CompiledRule<UiNode>>()
        dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            (root["screens"] as? JsonArray)
                ?.let { all += RuleCompiler.compileRules<UiNode>(it, RuleContext.SCREEN) }
        }
        return all
    }
}
