package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleContext
import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.ParseShortfall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * #1036 — a rule that MATCHED while its declared parse yielded nothing must be LOUD.
 *
 * The failure this pins: DoorDash 8.93.7 removed the view ids every money parse anchored on. The
 * rules kept matching (their `require` anchors are text), every parse died, and nothing in the
 * pipeline could tell that apart from "matched, and there was nothing to parse" — so it ran for
 * weeks and only a desk analysis found it. The frozen golden corpus structurally cannot see it
 * (#1029): it is still the old UI, where the ids resolve.
 *
 * These cases pin the DETECTION (which shapes count as unresolved, which never trip) on the
 * classifier, which is where the evidence is gathered. The counting/WARN grain lives in
 * [PipelineStatsTest] because the census is taken post-admission, in the pipelines.
 */
class ParseShortfallLoudnessTest {

    private val doordash = "com.doordash.driverapp"

    // ── Fixtures ────────────────────────────────────────────────────────

    private fun screenRuleset(vararg ruleJson: String): Ruleset<UiNode> =
        Ruleset(
            RuleCompiler.compileRules(
                Json.parseToJsonElement("[${ruleJson.joinToString(",")}]").jsonArray,
                RuleContext.SCREEN,
            ),
        )

    private fun classifier(
        screens: Ruleset<UiNode>? = null,
        notifications: Ruleset<RawNotificationData>? = null,
    ) = ObservationClassifier(
        mock<JsonRuleInterpreter> {
            on { screenRuleset } doReturn screens
            on { notificationRuleset } doReturn notifications
        },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
    )

    /** The surface the rules below match on: a summary with no id-bearing money nodes. */
    private fun summaryTree(): UiNode {
        val tree = UiNode(
            text = null,
            children = mutableListOf(UiNode(text = "Dash summary"), UiNode(text = "Total: \$42.00")),
        )
        tree.restoreParents()
        return tree
    }

    private fun screenEvent(tree: UiNode = summaryTree()) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(tree = tree, packageName = doordash),
        packageName = doordash,
    )

    private fun shortfallsFor(vararg ruleJson: String): List<ParseShortfall> =
        classifier(screens = screenRuleset(*ruleJson)).classify(screenEvent()).parseShortfalls

    /** A rule whose text anchor still matches while every id-anchored field is gone — the rot. */
    private val rottedRule = """{
        "id": "doordash.screen.delivery_summary_expanded",
        "priority": 10,
        "require": { "allTextContains": "Dash summary" },
        "parse": {
            "fields": {
                "totalPay": { "find": { "hasIdExact": "summary_total_pay" }, "read": "text" },
                "basePay": { "find": { "hasIdExact": "summary_base_pay" }, "read": "text" }
            }
        }
    }"""

    // ── The total-rot trigger ───────────────────────────────────────────

    @Test
    fun `a matched rule whose every evidence field is unresolved reports a shortfall`() {
        val shortfalls = shortfallsFor(rottedRule)

        assertEquals(1, shortfalls.size)
        assertEquals("doordash.screen.delivery_summary_expanded", shortfalls.single().ruleId)
        assertEquals(2, shortfalls.single().allNullFieldCount)
        assertTrue(shortfalls.single().nullRequiredFields.isEmpty())
    }

    @Test
    fun `recognition itself is untouched — the frame still classifies exactly as before`() {
        val obs = classifier(screens = screenRuleset(rottedRule)).classify(screenEvent())

        assertEquals("delivery_summary_expanded", obs.target)
        assertEquals("doordash.screen.delivery_summary_expanded", obs.ruleId)
    }

    // ── R1: the never-null shapes the first cut was blind to ────────────

    @Test
    fun `an each field that finds nothing counts as unresolved, not as a value`() {
        // The finding that made this signal matter: `each`/`findAll` return an EMPTY LIST, not
        // null, so a null check read them as "parsed fine" — and they are exactly the fields the
        // money surfaces carry (`payLineItems`, `orders`). One such field pinned the whole rule
        // silent forever.
        val rule = """{
            "id": "doordash.screen.summary_line_items",
            "priority": 11,
            "require": { "allTextContains": "Dash summary" },
            "parse": { "fields": {
                "payLineItems": {
                    "each": { "hasIdExact": "line_item_row" },
                    "extract": { "label": { "find": { "hasIdExact": "label" }, "read": "text" } }
                }
            } }
        }"""

        val shortfalls = shortfallsFor(rule)

        assertEquals(1, shortfalls.size)
        assertEquals(1, shortfalls.single().allNullFieldCount)
    }

    @Test
    fun `a constant beside a dead extraction cannot mask the rot`() {
        // Why constants are EXCLUDED from the evidence set rather than merely tolerated: one
        // always-non-null field would otherwise keep "every field is unresolved" false forever.
        val rule = """{
            "id": "doordash.screen.summary_mixed",
            "priority": 12,
            "require": { "allTextContains": "Dash summary" },
            "parse": { "fields": {
                "isExpanded": { "literal": true },
                "kind": "summary",
                "seenTotals": { "presence": { "exists": { "hasIdExact": "totals_container" } } },
                "totalPay": { "find": { "hasIdExact": "summary_total_pay" }, "read": "text" }
            } }
        }"""

        val shortfalls = shortfallsFor(rule)

        assertEquals(1, shortfalls.size)
        assertEquals("only the one extractable field counts", 1, shortfalls.single().allNullFieldCount)
    }

    @Test
    fun `a rule whose fields all always resolve never trips`() {
        // Every never-null shape in one rule: a literal, a bare primitive, a presence check, an
        // `else`-bearing conditionalEnum, and a fallback-bearing extraction. None of them can
        // evidence anything about the frame, so this rule must be structurally unable to trip.
        val rule = """{
            "id": "doordash.screen.summary_constants",
            "priority": 13,
            "require": { "allTextContains": "Dash summary" },
            "parse": { "fields": {
                "isExpanded": { "literal": true },
                "kind": "summary",
                "seenTotals": { "presence": { "exists": { "hasIdExact": "totals_container" } } },
                "mode": { "conditionalEnum": [
                    { "if": { "exists": { "hasIdExact": "time_mode_on" } }, "then": "ByTime" },
                    { "else": "PerOffer" }
                ] },
                "zone": { "find": { "hasIdExact": "zone_name" }, "read": "text", "fallback": "unknown" }
            } }
        }"""

        val obs = classifier(screens = screenRuleset(rule)).classify(screenEvent())

        assertEquals(
            "guards against a vacuous pass — the rule really did match this frame",
            "doordash.screen.summary_constants",
            obs.ruleId,
        )
        assertTrue(obs.parseShortfalls.isEmpty())
    }

    @Test
    fun `a parse that resolves never trips`() {
        val rule = """{
            "id": "doordash.screen.summary_ok",
            "priority": 14,
            "require": { "allTextContains": "Dash summary" },
            "parse": { "fields": {
                "totalPay": { "find": { "hasTextStartsWith": "Total" }, "read": "text" }
            } }
        }"""

        val obs = classifier(screens = screenRuleset(rule)).classify(screenEvent())

        assertEquals("doordash.screen.summary_ok", obs.ruleId)
        assertTrue(obs.parseShortfalls.isEmpty())
    }

    @Test
    fun `a rule with no parse block never trips`() {
        val rule = """{
            "id": "doordash.screen.summary_bare",
            "priority": 15,
            "require": { "allTextContains": "Dash summary" }
        }"""

        val obs = classifier(screens = screenRuleset(rule)).classify(screenEvent())

        assertEquals("doordash.screen.summary_bare", obs.ruleId)
        assertTrue(
            "no declared evidence means nothing was claimed, so nothing can be missing",
            obs.parseShortfalls.isEmpty(),
        )
    }

    // ── R2: partial rot on a shape-required field ───────────────────────

    @Test
    fun `a shape-required field left null trips even while other fields parse`() {
        // The 8.93.7 receipt looked like this ONE RELEASE before it went totally dead: the sheet
        // still parsed its store, and only the money was gone.
        val rule = """{
            "id": "doordash.screen.delivery_receipt",
            "priority": 16,
            "require": { "allTextContains": "Dash summary" },
            "parse": {
                "as": "post_task",
                "fields": {
                    "totalPay": { "find": { "hasIdExact": "receipt_total" }, "read": "text" },
                    "storeName": { "find": { "hasTextStartsWith": "Total" }, "read": "text" }
                }
            }
        }"""

        val shortfalls = shortfallsFor(rule)

        assertEquals(1, shortfalls.size)
        assertEquals(
            "the total-rot arm did NOT fire — storeName parsed",
            0,
            shortfalls.single().allNullFieldCount,
        )
        assertEquals(listOf("totalPay"), shortfalls.single().nullRequiredFields)
    }

    // ── R5: a matched-then-skipped branch still reports ─────────────────

    @Test
    fun `a branch discarded by its own Skip validator still reports its shortfall`() {
        // The nastiest shape: the rotted branch matches, parses nothing, its validator skips it,
        // and a lower-priority text rule claims the frame — so without this the rot leaves no
        // trace anywhere, and the frame looks perfectly recognized.
        val rotted = """{
            "id": "doordash.screen.summary_skipped",
            "priority": 17,
            "require": { "allTextContains": "Dash summary" },
            "parse": { "fields": {
                "totalPay": { "find": { "hasIdExact": "summary_total_pay" }, "read": "text" }
            } },
            "validate": [ { "assert": "fieldNotNull", "field": "totalPay", "onFail": "skip" } ]
        }"""
        val fallbackRule = """{
            "id": "doordash.screen.summary_text_only",
            "priority": 18,
            "require": { "allTextContains": "Dash summary" }
        }"""

        val obs = classifier(screens = screenRuleset(rotted, fallbackRule)).classify(screenEvent())

        assertEquals(
            "the frame looks perfectly recognized — by the OTHER rule",
            "doordash.screen.summary_text_only",
            obs.ruleId,
        )
        assertEquals(listOf("doordash.screen.summary_skipped"), obs.parseShortfalls.map { it.ruleId })
    }

    // ── The second parse-bearing path ───────────────────────────────────

    @Test
    fun `a notification rule whose wording moved trips the same signal`() {
        val rule = """{
            "id": "doordash.notification.order_ready",
            "priority": 10,
            "require": { "titleContains": "Order" },
            "parse": { "fields": {
                "storeName": { "from": "text", "find": "ready at (.+)$", "group": 1 }
            } }
        }"""
        val ruleset = Ruleset<RawNotificationData>(
            RuleCompiler.compileRules(
                Json.parseToJsonElement("[$rule]").jsonArray,
                RuleContext.NOTIFICATION,
            ),
        )
        val raw = RawNotificationData(
            title = "Order update",
            text = "Your pickup is waiting",
            bigText = null,
            tickerText = null,
            packageName = doordash,
            postTime = 5_000L,
            isClearable = true,
        )

        val obs = classifier(notifications = ruleset)
            .classify(PipelineEvent.Notification(raw.postTime, raw))

        assertEquals(
            "push wording rots the same way a view id does — same rule-id-keyed signal",
            listOf("doordash.notification.order_ready"),
            obs.parseShortfalls.map { it.ruleId },
        )
    }
}
