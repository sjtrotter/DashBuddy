package cloud.trotter.dashbuddy.core.pipeline.rules

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #590 🔴 Invariant 2 — an ACCEPTED rule regex has BOUNDED match time.
 *
 * [RegexSafety] rejects the nested-unbounded ReDoS family at COMPILE time, but
 * its own KDoc admits Kotlin [Regex] has no match timeout and the structural
 * heuristic is not sound on every catastrophic shape. Any pattern that evades it
 * runs unbounded on the per-event classification thread.
 *
 * Red-first observation (pre-[BoundedRegex] probe): the compile heuristic
 * ACCEPTS all four soundness-catalog patterns — `(a|aa)+$`, `(\w+)\1+`,
 * `(a?)*b`, `(.*a){20}` — yet running the RAW `Regex` of `(a|aa)+$` and `(a?)*b`
 * against a ~3 000-char pumping input threw `java.lang.StackOverflowError` (Java
 * regex recurses per repetition) — an Error escaping every downstream
 * Exception-only catch = a fail-OPEN crash of the classification thread. The fix
 * routes every rule-authored match through [BoundedRegex] (interrupt watchdog +
 * StackOverflowError catch, fail-closed no-match); the same patterns then return
 * in single-digit ms.
 *
 * A failing property prints its seed; pin it with `PropTestConfig(seed = ...)`.
 */
class RegexBudgetPropertyTest {

    private val catalog = listOf("(a|aa)+$", "(\\w+)\\1+", "(a?)*b", "(.*a){20}")

    /** A pumping amplifier: a long run of the class + a non-matching tail. */
    private fun pumpingInput(): String = "a".repeat(4_000) + "!"

    /**
     * Run [regex] against [input] on a watchdog thread; true iff it finished
     * within [budgetMs]. [BoundedRegex]'s own 200 ms budget should always beat
     * this 1 s outer net — a timeout here means the runtime guard failed.
     */
    private fun matchWithinBudget(regex: BoundedRegex, input: CharSequence, budgetMs: Long = 1_000): Boolean {
        val exec = Executors.newSingleThreadExecutor()
        return try {
            val f = exec.submit<Boolean> { regex.containsMatchIn(input) }
            try {
                f.get(budgetMs, TimeUnit.MILLISECONDS)
                true
            } catch (e: TimeoutException) {
                f.cancel(true)
                false
            }
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun `soundness catalog - each pattern is rejected or provably bounded`() {
        for (pattern in catalog) {
            val bounded = try {
                RegexSafety.compileRegex(pattern)
            } catch (e: RuleCompileException) {
                continue // rejected at compile — the other acceptable arm
            }
            // Accepted by the heuristic ⇒ the runtime budget must bound it.
            assertTrue(
                "accepted catalog pattern <$pattern> did not bound its match",
                matchWithinBudget(bounded, pumpingInput()),
            )
        }
    }

    // --- Pattern grammar: well-formed regexes biased toward catastrophic shapes
    //     (ambiguous alternation + outer quantifier, optional-inside-star). Most
    //     nested-unbounded shapes are rejected by RegexSafety; the survivors are
    //     what the property must prove bounded. --------------------------------

    private val atom = Arb.element("a", "b", "\\w", "\\d", ".")
    private val quant = Arb.element("", "*", "+", "?", "{2}", "{2,}")
    private val unit = Arb.bindPair(atom, quant) { a, q -> "$a$q" }

    private val group = Arb.bind3(unit, unit, quant) { u1, u2, q -> "($u1|$u2)$q" }

    @Test
    fun `property - every accepted generated pattern bounds its match`() = runTest {
        val piece = Arb.element(0, 1) // 0 = unit, 1 = group
        checkAll(
            200,
            Arb.list(piece, 1..3),
            unit, group, Arb.element("", "$"),
        ) { shape, u, g, anchor ->
            val body = shape.joinToString("") { if (it == 0) u else g }
            val pat = body + anchor
            val bounded = try {
                RegexSafety.compileRegex(pat)
            } catch (e: RuleCompileException) {
                return@checkAll // rejected/invalid — nothing to bound
            }
            assertTrue(
                "accepted pattern <$pat> did not bound its match within the watchdog",
                matchWithinBudget(bounded, pumpingInput()),
            )
        }
    }
}

// --- Tiny Arb combinators (kept local; kotest-property has no 2/3-arg map) ---

private fun <A, B> Arb.Companion.bindPair(a: Arb<A>, b: Arb<B>, f: (A, B) -> String): Arb<String> =
    io.kotest.property.arbitrary.arbitrary { rs -> f(a.sample(rs).value, b.sample(rs).value) }

private fun <A, B, C> Arb.Companion.bind3(a: Arb<A>, b: Arb<B>, c: Arb<C>, f: (A, B, C) -> String): Arb<String> =
    io.kotest.property.arbitrary.arbitrary { rs -> f(a.sample(rs).value, b.sample(rs).value, c.sample(rs).value) }
