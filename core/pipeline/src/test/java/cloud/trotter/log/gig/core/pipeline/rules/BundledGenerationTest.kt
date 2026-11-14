package cloud.trotter.log.gig.core.pipeline.rules

import android.content.Context
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File

/**
 * #1568: the shipped rules through the batch loader. A bundled file that fails to compile, or a
 * bundled rule the compiler isolates, would quietly thin recognition on the device (bundled files
 * keep the per-file policy, since nothing older stands behind them), so CI holds the shipped set
 * to zero of either. The corpus suites in `:app` read this same path through `TestRulesetFactory`.
 */
class BundledGenerationTest {

    private val rulesDir: File by lazy {
        val relative = "core/pipeline/build/generated/assets/importMatchersRules/rules"
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not locate the repo root")
        }
        File(dir, relative).also { check(it.isDirectory) { "generated rules dir missing at ${it.path}" } }
    }

    private val shipped: List<Pair<String, String>> by lazy {
        rulesDir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
            .map { "rules/${it.name}" to it.readText() }
    }

    @Test
    fun `every shipped file compiles whole, with no isolated rule`() {
        assertTrue(shipped.isNotEmpty())
        for ((path, json) in shipped) {
            val compiled = compileRuleFile(json, path)
            assertNotNull("$path failed to compile", compiled)
            assertEquals("$path isolated rules", emptyList<String>(), compiled!!.isolatedRuleIds)
        }
    }

    @Test
    fun `the bundled generation carries every shipped rule`() {
        val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())
        val result = interpreter.prepareGeneration(shipped.map { (path, json) -> RuleFile.Bundled(path, json) })
        val generation = (result as GenerationResult.Prepared).generation

        fun count(section: String) = shipped.sumOf { (_, json) ->
            Json.parseToJsonElement(json).jsonObject[section]?.jsonArray?.size ?: 0
        }
        assertEquals(count("screens"), generation.screens.ruleCount)
        assertEquals(count("clicks"), generation.clicks.ruleCount)
        assertEquals(count("notifications"), generation.notifications.ruleCount)
        assertEquals(shipped.size, result.bundledFiles)
    }
}
