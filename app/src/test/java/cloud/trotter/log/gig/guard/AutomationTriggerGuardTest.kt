package cloud.trotter.log.gig.guard

import cloud.trotter.log.gig.domain.action.RuleAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1457 — every app-initiated tap is scheduled from an action that has an automatic trigger.
 *
 * AUTOMATION fires come from exactly one place: `EffectMap.diffSettleUiTimeout` turns a
 * `ObservationPayload.DeferredAction` into a `PerformRuleAction(trigger = AUTOMATION)`. So the
 * actions that can fire by themselves are the ones a `DeferredAction` is built for, which today are
 * `diffExpandAction` (EXPAND_EARNINGS) and the confirm-decline arm (CONFIRM_DECLINE). This scan
 * pins both: each `DeferredAction(` site reads its action from a `val action = RuleAction.X` in the
 * same function, and X carries [RuleAction.hasAutomaticTrigger]. `SideEffectEngine` refuses the
 * rest at fire time; this keeps the flag and the code that schedules taps in step.
 */
class AutomationTriggerGuardTest {

    private val root = RepoRoot.locate()

    private fun mainSources(module: String): List<File> =
        File(root, "$module/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `every deferred action site schedules an action with an automatic trigger`() {
        val effectMap = File(root, "core/state/src/main/java/cloud/trotter/log/gig/core/state/EffectMap.kt").readText()
        val site = Regex("""ObservationPayload\.DeferredAction\(""")
        val functions = Regex("""\n    (?:private |internal )?fun """).split(effectMap)
        val scheduled = functions.filter { site.containsMatchIn(it) }.map { body ->
            val name = Regex("""val action = RuleAction\.([A-Z_]+)""").find(body)?.groupValues?.get(1)
            checkNotNull(name) { "a DeferredAction site without `val action = RuleAction.X`:\n${body.take(400)}" }
            RuleAction.valueOf(name)
        }

        assertEquals(
            "the scheduled automatic taps are the expand arrow and the confirm-decline arm",
            setOf(RuleAction.EXPAND_EARNINGS, RuleAction.CONFIRM_DECLINE),
            scheduled.toSet(),
        )
        assertEquals("one DeferredAction per scheduling function", site.findAll(effectMap).count(), scheduled.size)
        scheduled.forEach { assertTrue("$it is scheduled automatically but has no automatic trigger", it.hasAutomaticTrigger) }
    }

    @Test
    fun `AUTOMATION fires are built only from the deferred-action timeout`() {
        val use = Regex("""ActionTrigger\.AUTOMATION\b""")
        val builders = listOf("core/state", "core/pipeline", "domain", "app").flatMap(::mainSources)
            .filter { file ->
                val text = file.readText()
                use.containsMatchIn(text) && !file.name.endsWith("SideEffectEngine.kt")
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }

        assertEquals(
            listOf("core/state/src/main/java/cloud/trotter/log/gig/core/state/EffectMap.kt"),
            builders,
        )
    }
}
