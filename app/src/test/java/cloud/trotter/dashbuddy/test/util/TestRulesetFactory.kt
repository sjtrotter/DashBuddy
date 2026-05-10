package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.rules.CompiledRule
import cloud.trotter.dashbuddy.rules.RuleCompiler
import cloud.trotter.dashbuddy.rules.RuleContext
import cloud.trotter.dashbuddy.rules.Ruleset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Shared test helper that loads and compiles all production rule files
 * from `src/main/assets/rules/` without requiring an Android Context.
 *
 * Replaces the deleted TestMatcherFactory / TestParserFactory — JSON rules
 * are now the single source of truth.
 */
object TestRulesetFactory {

    private const val RULES_DIR = "src/main/assets/rules"

    private val allRules by lazy {
        val dir = File(RULES_DIR)
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

        Triple(allScreens, allClicks, allNotifications)
    }

    val screenRuleset: Ruleset<UiNode> by lazy {
        Ruleset(allRules.first)
    }

    val clickRuleset: Ruleset<UiNode> by lazy {
        Ruleset(allRules.second)
    }

    val notificationRuleset: Ruleset<RawNotificationData> by lazy {
        Ruleset(allRules.third)
    }
}
