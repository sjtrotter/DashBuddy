package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.rules.ClickRuleset
import cloud.trotter.dashbuddy.rules.NotificationRuleset
import cloud.trotter.dashbuddy.rules.RuleCompiler
import cloud.trotter.dashbuddy.rules.ScreenRuleset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Shared test helper that loads and compiles the production rules file
 * without requiring an Android Context.
 *
 * Replaces the deleted TestMatcherFactory / TestParserFactory — JSON rules
 * are now the single source of truth.
 */
object TestRulesetFactory {

    private val root by lazy {
        val json = File("src/main/assets/rules.default.json").readText()
        Json.parseToJsonElement(json).jsonObject
    }

    val screenRuleset: ScreenRuleset by lazy {
        val screens = root["screens"]?.jsonArray
            ?.let { RuleCompiler.compileScreenRules(it) } ?: emptyList()
        ScreenRuleset(screens)
    }

    val clickRuleset: ClickRuleset by lazy {
        val clicks = root["clicks"]?.jsonArray
            ?.let { RuleCompiler.compileClickRules(it) } ?: emptyList()
        ClickRuleset(clicks)
    }

    val notificationRuleset: NotificationRuleset by lazy {
        val notifications = root["notifications"]?.jsonArray
            ?.let { RuleCompiler.compileNotificationRules(it) } ?: emptyList()
        NotificationRuleset(notifications)
    }
}
