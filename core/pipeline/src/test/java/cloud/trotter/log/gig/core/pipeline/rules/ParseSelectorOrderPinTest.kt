package cloud.trotter.log.gig.core.pipeline.rules

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1568: [RemoteRuleAdmission] judges a customer field's hashing on the expression the compiler
 * selects, through [ParseExpressionCompiler.SCREEN_SELECTORS]. This pins that list to the order the
 * screen parse-expression dispatch actually tests its keys, so a new or reordered arm fails here
 * instead of opening a gap between what admission checks and what the compiler builds.
 */
class ParseSelectorOrderPinTest {

    @Test
    fun `the selector list matches the dispatch order`() {
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("could not locate the repo root")
        val source = File(dir, "core/pipeline/src/main/java/cloud/trotter/log/gig/core/pipeline/rules/ParseExpressionCompiler.kt")
            .readText()
        val dispatch = source.substringAfter("return failNullOnEvaluationFailure(")
            .substringBefore("else -> throw RuleCompileException(\"Unknown parse expression keys")
        val arms = Regex("""^\s*"(\w+)" in obj\b""", RegexOption.MULTILINE).findAll(dispatch).map { it.groupValues[1] }.toList()
        assertEquals(ParseExpressionCompiler.SCREEN_SELECTORS, arms)
    }
}
