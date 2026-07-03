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

    /**
     * Resolved at first access relative to the Gradle working directory.
     *
     * The rules are no longer committed under `assets/rules/` — they are
     * GENERATED (#635) by `:core:pipeline:importMatchersRules`, which
     * canonicalizes the matchers JSON5 rule sources into the module's build
     * output. Unit tests do NOT run AGP asset merge, so `:app:testDebugUnitTest`
     * declares `dependsOn(":core:pipeline:importMatchersRules")` (see
     * app/build.gradle.kts) to make sure this directory is populated first.
     * `addGeneratedSourceDirectory` names the output dir after the task, so the
     * path below is the stable AGP location for that generated assets root.
     */
    val rulesDir: String by lazy {
        val generated = "core/pipeline/build/generated/assets/importMatchersRules/rules"
        val candidates = listOf(
            "../$generated", // from app/ (the unit-test working dir)
            generated,       // from repo root
        )
        candidates.firstOrNull { File(it).isDirectory }
            ?: error(
                "Cannot find GENERATED rules directory. Working dir: ${File(".").absolutePath}\n" +
                    "Searched: ${candidates.joinToString { File(it).absolutePath }}\n" +
                    "Run ':core:pipeline:importMatchersRules' first (canonicalizes matchers/rules/*.json5); " +
                    "':app:testDebugUnitTest' declares this as a dependency."
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
