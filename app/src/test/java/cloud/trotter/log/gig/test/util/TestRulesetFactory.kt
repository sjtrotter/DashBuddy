package cloud.trotter.log.gig.test.util

import android.content.Context
import android.content.res.AssetManager
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
import cloud.trotter.log.gig.core.pipeline.rules.Ruleset
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Shared test helper that loads and compiles all production rule files
 * from the `:core:pipeline` module's generated assets without a device.
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

    /**
     * An [AssetManager] serving the generated rule files at `rules/`, the path the production loader
     * reads. [transform] rewrites a file's text by name (default: unchanged).
     */
    fun generatedAssets(transform: (file: String, json: String) -> String = { _, json -> json }): AssetManager {
        val dir = File(rulesDir)
        val assets = mock<AssetManager>()
        whenever(assets.list("rules")).thenReturn(dir.list())
        whenever(assets.open(any<String>())).thenAnswer {
            val file = File(dir, it.getArgument<String>(0).removePrefix("rules/"))
            transform(file.name, file.readText()).byteInputStream()
        }
        return assets
    }

    /**
     * The shipped rules as the app loads them (#1568): the real [JsonRuleInterpreter.loadDefaults]
     * over the generated files, so the corpus suites read the generation the batch loader builds.
     */
    val generation: RuleGeneration by lazy {
        val assets = generatedAssets()
        val context = mock<Context> { on { this.assets } doReturn assets }
        val interpreter = JsonRuleInterpreter(context, mock<RuleCapabilityGrants>())
        runBlocking { interpreter.loadDefaults() }
        interpreter.generation ?: error("the bundled rules did not load")
    }

    val screenRuleset: Ruleset<UiNode> get() = generation.screens

    val clickRuleset: Ruleset<UiNode> get() = generation.clicks

    val notificationRuleset: Ruleset<RawNotificationData> get() = generation.notifications
}
