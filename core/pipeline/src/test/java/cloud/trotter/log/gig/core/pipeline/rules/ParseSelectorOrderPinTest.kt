package cloud.trotter.log.gig.core.pipeline.rules

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1568: [RemoteRuleAdmission] judges a customer field's hashing on the expression the compiler
 * builds, through [ParseExpressionCompiler.SCREEN_SELECTORS] and
 * [ParseExpressionCompiler.NOTIFICATION_KEYS]. This pins both to the compiler's own dispatch, so a
 * new or reordered arm fails here instead of opening a gap between admission and compilation.
 */
class ParseSelectorOrderPinTest {

    private val source: String by lazy {
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("could not locate the repo root")
        File(dir, "core/pipeline/src/main/java/cloud/trotter/log/gig/core/pipeline/rules/ParseExpressionCompiler.kt")
            .readText()
    }

    @Test
    fun `the notification key set is every key the notification compiler reads`() {
        val body = source.substringAfter("private fun compileNotifParseExpression(").substringBefore("private fun ")
        val read = Regex(""""(\w+)" in obj\b|obj\["(\w+)"]""").findAll(body)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
        assertEquals(ParseExpressionCompiler.NOTIFICATION_KEYS, read)
    }

    @Test
    fun `the selector list matches the dispatch order`() {
        val dispatch = source.substringAfter("return failNullOnEvaluationFailure(")
            .substringBefore("else -> throw RuleCompileException(\"Unknown parse expression keys")
        val arms = Regex("""^\s*"(\w+)" in obj\b""", RegexOption.MULTILINE).findAll(dispatch).map { it.groupValues[1] }.toList()
        assertEquals(ParseExpressionCompiler.SCREEN_SELECTORS, arms)
    }
}
