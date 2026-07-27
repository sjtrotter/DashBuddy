package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.PropSeeds
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #590 🔴 Invariant 1 — rule-compile recursion is FAIL-CLOSED.
 *
 * Nested parse expressions (`each`/`extract`, `join`, `coalesce`) and raw
 * nested rule JSON must compile to success OR fail with the typed
 * [RuleCompileException] — NEVER a [StackOverflowError]/OOM/other [Throwable].
 * The loader ([JsonRuleInterpreter.loadSingle]) catches only [Exception]; a
 * [StackOverflowError] is an [Error] and would escape it = a fail-OPEN crash of
 * the rule-load / classification path.
 *
 * Red-first observation (pre-fix, kotest smoke reproduction): both
 * `RuleCompiler.compileRules` over a depth-100 000 nested `coalesce` and
 * `Json.parseToJsonElement` over depth-100 000 nested arrays threw
 * `java.lang.StackOverflowError` (both compiled fine at depth 1 000). The fix is
 * [RuleCompiler.MAX_PARSE_DEPTH] (parse-expression recursion) +
 * [RuleCompiler.parseBoundedJson]/[RuleCompiler.MAX_JSON_DEPTH] (raw-JSON depth
 * pre-scan before the recursive parser).
 *
 * **Determinism (#878).** The seeds below pin PR CI, so a failure is a reproducible
 * finding rather than a dice roll; bump one **deliberately** to explore new samples.
 * Unseeded breadth lives on the `-Ddashbuddy.propExplore=true` path ([PropSeeds]).
 */
class RuleCompileRecursionPropertyTest {

    private companion object {
        /** #878 pinned seed (parse-expression depth). Bump deliberately to explore. */
        const val SEED_PARSE_DEPTH = 0x0590_0003L

        /** #878 pinned seed (raw-JSON depth). Bump deliberately to explore. */
        const val SEED_JSON_DEPTH = 0x0590_0004L
    }

    /** parse.fields.x = <verb> -> <verb> -> ... -> "lit", [depth] levels deep. */
    private fun nestedParseRule(depth: Int, verb: String): JsonArray {
        var expr: JsonElement = JsonPrimitive("lit")
        repeat(depth) {
            expr = when (verb) {
                "coalesce" -> buildJsonObject { put("coalesce", buildJsonArray { add(expr) }) }
                "join" -> buildJsonObject { put("join", buildJsonArray { add(expr) }) }
                else -> buildJsonObject {
                    put("each", buildJsonObject { put("hasNoId", JsonPrimitive(true)) })
                    put("extract", buildJsonObject { put("v", expr) })
                }
            }
        }
        val rule = buildJsonObject {
            put("id", JsonPrimitive("doordash.screen.probe"))
            put("priority", JsonPrimitive(1))
            put("parse", buildJsonObject { put("fields", buildJsonObject { put("x", expr) }) })
        }
        return JsonArray(listOf(rule))
    }

    /** Compile; return true iff only success or [RuleCompileException] resulted. */
    private fun compileIsFailClosed(array: JsonArray): Boolean = try {
        RuleCompiler.compileRules<Any>(array, RuleContext.SCREEN)
        true
    } catch (e: RuleCompileException) {
        true
    } catch (t: Throwable) {
        System.err.println("parse-expr compile leaked ${t.javaClass.name}: ${t.message}")
        false
    }

    /** Parse via the bounded parser; true iff only success or RuleCompileException. */
    private fun parseIsFailClosed(json: String): Boolean = try {
        RuleCompiler.parseBoundedJson(json)
        true
    } catch (e: RuleCompileException) {
        true
    } catch (t: Throwable) {
        System.err.println("raw-JSON parse leaked ${t.javaClass.name}: ${t.message}")
        false
    }

    @Test
    fun `nested parse expressions at extreme depth never escape a raw Throwable`() {
        for (verb in listOf("coalesce", "join", "each")) {
            for (depth in listOf(21, 1_000, 100_000)) {
                assertTrue(
                    "verb=$verb depth=$depth must compile or throw RuleCompileException",
                    compileIsFailClosed(nestedParseRule(depth, verb)),
                )
            }
        }
    }

    @Test
    fun `raw nested JSON at extreme depth never escapes a raw Throwable`() {
        for (depth in listOf(21, 1_000, 100_000)) {
            val arrays = "[".repeat(depth) + "]".repeat(depth)
            val objects = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
            assertTrue("nested arrays depth=$depth", parseIsFailClosed(arrays))
            assertTrue("nested objects depth=$depth", parseIsFailClosed(objects))
        }
    }

    @Test
    fun `property - arbitrary parse-expression depth is always fail-closed`() = runTest {
        val verbs = Arb.element("coalesce", "join", "each")
        // Straddle MAX_PARSE_DEPTH (64) and go far past it into stack-overflow
        // territory pre-fix.
        checkAll(PropSeeds.samples(200), PropSeeds.config(SEED_PARSE_DEPTH), Arb.int(1, 5_000), verbs) { depth, verb ->
            assertTrue(compileIsFailClosed(nestedParseRule(depth, verb)))
        }
    }

    @Test
    fun `property - arbitrary raw-JSON depth is always fail-closed`() = runTest {
        checkAll(PropSeeds.samples(200), PropSeeds.config(SEED_JSON_DEPTH), Arb.int(1, 5_000)) { depth ->
            val json = "[".repeat(depth) + "]".repeat(depth)
            assertTrue(parseIsFailClosed(json))
        }
    }
}
