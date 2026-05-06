package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.rules.ClickRuleset
import cloud.trotter.dashbuddy.rules.CompiledClickRule
import cloud.trotter.dashbuddy.rules.CompiledNotificationRule
import cloud.trotter.dashbuddy.rules.CompiledScreenRule
import cloud.trotter.dashbuddy.rules.NotificationRuleset
import cloud.trotter.dashbuddy.rules.RuleCompiler
import cloud.trotter.dashbuddy.rules.ScreenRuleset
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
        val allScreens = mutableListOf<CompiledScreenRule>()
        val allClicks = mutableListOf<CompiledClickRule>()
        val allNotifications = mutableListOf<CompiledNotificationRule>()

        dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["screens"]?.jsonArray
                ?.let { allScreens += RuleCompiler.compileScreenRules(it) }
            root["clicks"]?.jsonArray
                ?.let { allClicks += RuleCompiler.compileClickRules(it) }
            root["notifications"]?.jsonArray
                ?.let { allNotifications += RuleCompiler.compileNotificationRules(it) }
        }

        Triple(allScreens, allClicks, allNotifications)
    }

    val screenRuleset: ScreenRuleset by lazy {
        ScreenRuleset(allRules.first)
    }

    val clickRuleset: ClickRuleset by lazy {
        ClickRuleset(allRules.second)
    }

    val notificationRuleset: NotificationRuleset by lazy {
        NotificationRuleset(allRules.third)
    }
}
