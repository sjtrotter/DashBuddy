package cloud.trotter.dashbuddy.core.pipeline

import android.util.Log
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleContext
import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import timber.log.Timber

/**
 * #1036 — a rule that MATCHED while its declared parse yielded all-null must be LOUD.
 *
 * The failure this pins: DoorDash 8.93.7 removed the view ids every money parse anchored on. The
 * rules kept matching (their `require` anchors are text), every parse died, and nothing in the
 * pipeline could tell that apart from "matched, and there was nothing to parse" — so it ran for
 * weeks and only a desk analysis found it. The frozen golden corpus structurally cannot see it
 * (#1029): it is still the old UI, where the ids resolve.
 *
 * Two grains are asserted separately, because they are deliberately different: the
 * [PipelineStats] counter is a per-frame census, the WARN is once per rule per process.
 */
class ParseAllNullLoudnessTest {

    private val doordash = "com.doordash.driverapp"

    private class Recorder : Timber.Tree() {
        data class Line(val priority: Int, val tag: String?, val message: String)

        val lines = mutableListOf<Line>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += Line(priority, tag, message)
        }

        fun warns(): List<Line> = lines.filter { it.priority == Log.WARN }
    }

    private val recorder = Recorder()

    @Before fun plant() = Timber.plant(recorder)

    @After fun uproot() = Timber.uproot(recorder)

    // ── Fixtures ────────────────────────────────────────────────────────

    private fun screenRuleset(vararg ruleJson: String): Ruleset<UiNode> =
        Ruleset(
            RuleCompiler.compileRules(
                Json.parseToJsonElement("[${ruleJson.joinToString(",")}]").jsonArray,
                RuleContext.SCREEN,
            ),
        )

    private fun classifier(
        stats: PipelineStats,
        screens: Ruleset<UiNode>? = null,
        notifications: Ruleset<RawNotificationData>? = null,
    ) = ObservationClassifier(
        mock<JsonRuleInterpreter> {
            on { screenRuleset } doReturn screens
            on { notificationRuleset } doReturn notifications
        },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
        stats,
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

    // ── The counter + the WARN ──────────────────────────────────────────

    @Test
    fun `an all-null parse counts every frame and WARNs once per process`() {
        val stats = PipelineStats()
        val classifier = classifier(stats, screens = screenRuleset(rottedRule))

        repeat(3) { classifier.classify(screenEvent()) }

        assertEquals(
            "every matched frame is counted — the summary line is a census",
            3L,
            stats.parseAllNullCount("doordash.screen.delivery_summary_expanded"),
        )
        val warns = recorder.warns().filter { it.message.contains("all-null parse") }
        assertEquals("the WARN is edge-gated once per rule per process", 1, warns.size)
        assertEquals("Classifier", warns.single().tag)
        assertTrue(
            "the line names the rule and the declared-field count, and nothing else",
            warns.single().message.contains("doordash.screen.delivery_summary_expanded") &&
                warns.single().message.contains("(2 declared fields)"),
        )
    }

    @Test
    fun `recognition itself is untouched — the frame still classifies and parses as before`() {
        val stats = PipelineStats()
        val obs = classifier(stats, screens = screenRuleset(rottedRule)).classify(screenEvent())

        assertEquals("delivery_summary_expanded", obs.target)
        assertEquals("doordash.screen.delivery_summary_expanded", obs.ruleId)
    }

    // ── The three shapes that must NEVER trip ───────────────────────────

    @Test
    fun `a parse that resolves never trips`() {
        val stats = PipelineStats()
        val rule = """{
            "id": "doordash.screen.summary_ok",
            "priority": 11,
            "require": { "allTextContains": "Dash summary" },
            "parse": { "fields": {
                "totalPay": { "find": { "hasTextStartsWith": "Total" }, "read": "text" }
            } }
        }"""

        val obs = classifier(stats, screens = screenRuleset(rule)).classify(screenEvent())
        assertEquals(
            "guards against a vacuous pass — the rule really did match this frame",
            "doordash.screen.summary_ok",
            obs.ruleId,
        )

        assertEquals(0L, stats.parseAllNullCount("doordash.screen.summary_ok"))
        assertTrue(recorder.warns().none { it.message.contains("all-null parse") })
    }

    @Test
    fun `a rule with no parse block never trips`() {
        val stats = PipelineStats()
        val rule = """{
            "id": "doordash.screen.summary_bare",
            "priority": 12,
            "require": { "allTextContains": "Dash summary" }
        }"""

        val obs = classifier(stats, screens = screenRuleset(rule)).classify(screenEvent())
        assertEquals(
            "guards against a vacuous pass — the rule really did match this frame",
            "doordash.screen.summary_bare",
            obs.ruleId,
        )

        assertEquals(
            "no declared field means nothing was claimed, so nothing can be missing",
            0L,
            stats.parseAllNullCount("doordash.screen.summary_bare"),
        )
        assertTrue(recorder.warns().none { it.message.contains("all-null parse") })
    }

    @Test
    fun `a rule whose only fields are literals never trips`() {
        val stats = PipelineStats()
        // Both constant shapes: the `{literal:}` object and a bare primitive spec. Neither says
        // anything about the frame, so an all-constant rule can never evidence anchor rot.
        val rule = """{
            "id": "doordash.screen.summary_constants",
            "priority": 13,
            "require": { "allTextContains": "Dash summary" },
            "parse": { "fields": {
                "isExpanded": { "literal": true },
                "kind": "summary"
            } }
        }"""

        val obs = classifier(stats, screens = screenRuleset(rule)).classify(screenEvent())
        assertEquals(
            "guards against a vacuous pass — the rule really did match this frame",
            "doordash.screen.summary_constants",
            obs.ruleId,
        )

        assertEquals(0L, stats.parseAllNullCount("doordash.screen.summary_constants"))
        assertTrue(recorder.warns().none { it.message.contains("all-null parse") })
    }

    @Test
    fun `a constant beside a dead extraction cannot mask the rot`() {
        // The reason constants are excluded from the declared set rather than merely tolerated:
        // one always-non-null field would otherwise keep "every declared field is null" false
        // forever, and the rot would stay exactly as silent as it was before #1036.
        val stats = PipelineStats()
        val rule = """{
            "id": "doordash.screen.summary_mixed",
            "priority": 14,
            "require": { "allTextContains": "Dash summary" },
            "parse": { "fields": {
                "isExpanded": { "literal": true },
                "totalPay": { "find": { "hasIdExact": "summary_total_pay" }, "read": "text" }
            } }
        }"""

        val obs = classifier(stats, screens = screenRuleset(rule)).classify(screenEvent())
        assertEquals(
            "guards against a vacuous pass — the rule really did match this frame",
            "doordash.screen.summary_mixed",
            obs.ruleId,
        )

        assertEquals(1L, stats.parseAllNullCount("doordash.screen.summary_mixed"))
        assertTrue(
            recorder.warns().single { it.message.contains("all-null parse") }
                .message.contains("(1 declared fields)"),
        )
    }

    // ── The second parse-bearing path ───────────────────────────────────

    @Test
    fun `a notification rule whose wording moved trips the same signal`() {
        val stats = PipelineStats()
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

        classifier(stats, notifications = ruleset).classify(PipelineEvent.Notification(raw.postTime, raw))

        assertEquals(
            "push wording rots the same way a view id does — same rule-id-keyed signal",
            1L,
            stats.parseAllNullCount("doordash.notification.order_ready"),
        )
    }
}
