package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.PropSeeds
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #590 🔴 Invariant 2, re-based on #1053 — an ACCEPTED rule regex has BOUNDED match time.
 *
 * The invariant is unchanged; what changed is that it is now **true**. The original version proved
 * only that a 200 ms watchdog fired on the host JVM — and #1053 established the watchdog could
 * never fire on Android at all, because `Matcher.reset(CharSequence)` stringifies its input and
 * hands the match to native ICU, which exposes no timeout to `java.util.regex`. So the property was
 * being asserted in the one place it did not need to hold.
 *
 * Rule patterns now compile onto RE2J (see [BoundedRegex]), a non-backtracking engine, so the
 * property is structural and the assertion can be direct: **generated patterns match in bounded
 * wall-clock time**, measured on the calling thread with no watchdog to hide behind. The generator
 * is deliberately biased toward the catastrophic shapes — ambiguous alternation under an outer
 * quantifier, optional-inside-star — that a backtracking engine explodes on, and that the retired
 * compile-time heuristic could not detect.
 *
 * **Determinism (#878).** The seed below pins PR CI, so a failure is a reproducible finding rather
 * than a dice roll; bump it **deliberately** to explore new samples. Unseeded breadth lives on the
 * `-Ddashbuddy.propExplore=true` path ([PropSeeds]).
 */
class RegexBudgetPropertyTest {

    private companion object {
        /** #878 pinned seed — pins PR CI. Bump deliberately to explore new samples. */
        const val SEED = 0x0590_0002L

        /**
         * Wall-clock ceiling for a single match. Linear beats exponential by many orders of
         * magnitude on these inputs, so this is a cushion for a cold JIT on shared CI, not a fit.
         */
        const val BUDGET_MS = 50L
    }

    private val catalog = listOf("(a|aa)+$", "(a?)*b", "(.*a){20}", "(a+)+$", "(\\d+\\s*)+")

    /** A pumping amplifier: a long run of the class + a non-matching tail. */
    private fun pumpingInput(): String = "a".repeat(4_000) + "!"

    /**
     * Match [regex] against [input] on THIS thread and return the elapsed milliseconds. No worker,
     * no watchdog: a bound that needs a second thread to observe is exactly the bound #1053 found
     * to be unenforceable where it mattered. If the engine could backtrack, this call would simply
     * never return — which is a louder failure than a timeout, and an honest one.
     */
    private fun matchMillis(regex: BoundedRegex, input: CharSequence): Long {
        regex.containsMatchIn("a") // warm the engine; measure the match, not class loading
        val start = System.nanoTime()
        regex.containsMatchIn(input)
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `soundness catalog - every shape a heuristic cannot catch is provably bounded`() {
        for (pattern in catalog) {
            val bounded = RuleCompiler.compileRegex(pattern) // must COMPILE — nothing rejects these now
            val ms = matchMillis(bounded, pumpingInput())
            assertTrue(
                "catalog pattern <$pattern> took ${ms}ms, over the ${BUDGET_MS}ms bound",
                ms < BUDGET_MS,
            )
        }
    }

    // --- Pattern grammar: well-formed regexes biased toward catastrophic shapes
    //     (ambiguous alternation + outer quantifier, optional-inside-star). Under the old
    //     heuristic most of these were rejected at compile and the property never saw them;
    //     RE2J accepts them all, so every sample is now a real measurement. ------------------

    private val atom = Arb.element("a", "b", "\\w", "\\d", ".")
    private val quant = Arb.element("", "*", "+", "?", "{2}", "{2,}")
    private val unit = Arb.bindPair(atom, quant) { a, q -> "$a$q" }

    private val group = Arb.bind3(unit, unit, quant) { u1, u2, q -> "($u1|$u2)$q" }

    @Test
    fun `property - every accepted generated pattern bounds its match`() = runTest {
        val piece = Arb.element(0, 1) // 0 = unit, 1 = group
        checkAll(
            PropSeeds.samples(200),
            PropSeeds.config(SEED),
            Arb.list(piece, 1..3),
            unit, group, Arb.element("", "$"),
        ) { shape, u, g, anchor ->
            val body = shape.joinToString("") { if (it == 0) u else g }
            val pat = body + anchor
            val bounded = try {
                RuleCompiler.compileRegex(pat)
            } catch (e: RuleCompileException) {
                return@checkAll // an unparseable draw (e.g. a bare `{2,}` head) — nothing to bound
            }
            val ms = matchMillis(bounded, pumpingInput())
            assertTrue("accepted pattern <$pat> took ${ms}ms, over the ${BUDGET_MS}ms bound", ms < BUDGET_MS)
        }
    }
}

// --- Tiny Arb combinators (kept local; kotest-property has no 2/3-arg map) ---

private fun <A, B> Arb.Companion.bindPair(a: Arb<A>, b: Arb<B>, f: (A, B) -> String): Arb<String> =
    io.kotest.property.arbitrary.arbitrary { rs -> f(a.sample(rs).value, b.sample(rs).value) }

private fun <A, B, C> Arb.Companion.bind3(a: Arb<A>, b: Arb<B>, c: Arb<C>, f: (A, B, C) -> String): Arb<String> =
    io.kotest.property.arbitrary.arbitrary { rs -> f(a.sample(rs).value, b.sample(rs).value, c.sample(rs).value) }
