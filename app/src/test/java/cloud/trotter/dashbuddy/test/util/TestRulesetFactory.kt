package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRule
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleCompiler
import cloud.trotter.dashbuddy.core.pipeline.rules.RuleContext
import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Shared test helper that loads and compiles all production rule files
 * from the `:core:pipeline` module's assets without requiring an Android Context.
 *
 * Replaces the deleted TestMatcherFactory / TestParserFactory — JSON rules
 * are now the single source of truth.
 */
object TestRulesetFactory {

    /** Resolved at first access relative to the Gradle working directory. */
    val rulesDir: String by lazy {
        val candidates = listOf(
            "core/pipeline/src/main/assets/rules",       // project root
            "../core/pipeline/src/main/assets/rules",     // from app/
            "src/main/assets/rules",                      // from core/pipeline/
        )
        candidates.firstOrNull { File(it).isDirectory }
            ?: error(
                "Cannot find rules directory. Working dir: ${File(".").absolutePath}\n" +
                    "Searched: ${candidates.joinToString { File(it).absolutePath }}"
            )
    }

    private val allRules by lazy {
        val dir = File(rulesDir)
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
