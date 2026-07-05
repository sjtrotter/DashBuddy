package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import io.kotest.property.Arb
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
 * #590 fill-in — `each`/`extract` MATCH-TIME amplification is BOUNDED (not 50^K).
 *
 * `each` caps its own node harvest at `RuleCompiler.MAX_EACH_SIZE` (50) via
 * `findNodes(...).take(MAX_EACH_SIZE)`. But `extract` fields are themselves parse
 * expressions, so a NESTED `each`-inside-`extract` multiplies per level. The worry
 * is a rule that turns a wide accessibility tree into a `tree_width ^ depth`
 * extraction on the classification thread. This proves each nesting level is capped
 * at 50 independently, so the output is bounded by `50 ^ depth` (and, on any real
 * bounded tree, by the tree itself) — never `tree_width ^ depth` — and completes
 * well within a wall-clock budget.
 *
 * A failing property prints its seed; pin it with `PropTestConfig(seed = ...)`.
 */
class EachAmplificationBoundedTest {

    /** Mirror of the private RuleCompiler.MAX_EACH_SIZE, referenced in the bound. */
    private val maxEach = 50

    /** A bushy tree: [width] children, each with [width] grandchildren, all id-less. */
    private fun bushyTree(width: Int): UiNode = UiNode(
        text = "root",
        children = (0 until width).map { i ->
            UiNode(
                text = "n$i",
                children = (0 until width).map { j -> UiNode(text = "n$i-$j") },
            )
        },
    )

    /** parse.fields.items = a [depth]-deep nested `each`/`extract` over all id-less nodes. */
    private fun nestedEachRule(depth: Int): JsonArray {
        // innermost extract is a leaf text read
        var expr: JsonElement = JsonPrimitive("text")
        repeat(depth) {
            expr = buildJsonObject {
                put("each", buildJsonObject { put("hasNoId", JsonPrimitive(true)) })
                put("extract", buildJsonObject { put("v", expr) })
            }
        }
        val rule = buildJsonObject {
            put("id", JsonPrimitive("doordash.screen.probe"))
            put("priority", JsonPrimitive(1))
            // require: matches any tree (root has no id)
            put("require", buildJsonObject { put("exists", buildJsonObject { put("hasNoId", JsonPrimitive(true)) }) })
            put("parse", buildJsonObject {
                put("as", JsonPrimitive("idle"))
                put("fields", buildJsonObject { put("items", expr) })
            })
        }
        return JsonArray(listOf(rule))
    }

    /** Count every extracted leaf across the nested List<Map<...>> `each` output. */
    private fun countLeaves(value: Any?): Int = when (value) {
        is List<*> -> value.sumOf { countLeaves(it) }
        is Map<*, *> -> value.values.sumOf { countLeaves(it) }
        null -> 0
        else -> 1 // a terminal extracted value (String, etc.)
    }

    /** The theoretical cap on total extractions for a [depth]-nested each: sum_{k=1..depth} 50^k. */
    private fun theoreticalBound(depth: Int): Long {
        var total = 0L
        var level = 1L
        repeat(depth) {
            level *= maxEach
            total += level
        }
        return total
    }

    @Test
    fun `property - nested each on a wide tree stays within the MAX_EACH_SIZE-derived bound and time budget`() = runTest {
        // width up to 40 → up to ~1600 id-less nodes; depth 1..3. Naive tree_width^depth
        // would be up to 40^3 = 64 000 *per outer node*; the cap must hold it far below.
        checkAll(120, Arb.int(2, 40), Arb.int(1, 3)) { width, depth ->
            val ruleset = Ruleset(RuleCompiler.compileRules<UiNode>(nestedEachRule(depth), RuleContext.SCREEN))
            val tree = bushyTree(width)

            val start = System.nanoTime()
            val result = ruleset.matchFirst(tree, "doordash")
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            requireNotNull(result) { "probe rule must match the id-less tree" }
            val leaves = countLeaves(result.fields["items"])

            assertTrue(
                "leaf count $leaves exceeded the 50^depth bound ${theoreticalBound(depth)} " +
                    "(width=$width depth=$depth) — amplification is NOT capped per level",
                leaves <= theoreticalBound(depth),
            )
            assertTrue(
                "nested-each match took ${elapsedMs}ms (width=$width depth=$depth) — not time-bounded",
                elapsedMs < 2_000,
            )
        }
    }

    @Test
    fun `a very wide tree does not amplify past 50 outer x 50 inner for depth 2`() = runTest {
        // 200x200 = ~40 000 id-less nodes. Depth-2 naive would extract 40 000 * 40 000;
        // the cap holds the leaf count at <= 50 (outer) * 50 (inner) + 50 (the outer maps).
        val ruleset = Ruleset(RuleCompiler.compileRules<UiNode>(nestedEachRule(2), RuleContext.SCREEN))
        val tree = bushyTree(200)
        val start = System.nanoTime()
        val result = requireNotNull(ruleset.matchFirst(tree, "doordash"))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val leaves = countLeaves(result.fields["items"])
        assertTrue("leaf count $leaves must be <= 50*50", leaves <= maxEach.toLong() * maxEach)
        assertTrue("depth-2 match on a 40k-node tree took ${elapsedMs}ms", elapsedMs < 2_000)
    }
}
