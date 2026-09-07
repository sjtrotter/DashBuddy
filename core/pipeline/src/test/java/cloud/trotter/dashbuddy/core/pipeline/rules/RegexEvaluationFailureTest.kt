package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each boundary does when a rule regex cannot be **evaluated** (#1053 round 5).
 *
 * The defect this pins: `BoundedRegex` used to convert a match-time `StackOverflowError` into the
 * operation's default. `^((.?){100}){40}$` is seventeen characters, passed both load-time gates
 * (estimate 28 244, program 16 084) and overflows RE2J's `Machine.add` matching a five-character
 * input on 256 KiB, 512 KiB **and 1 MiB** stacks — ART's coroutine threads are about 1 MiB. Put it
 * in a screen rule's `redact[].find` and the `false` default reads as "no entry matched", so
 * `CompiledRedact.maskNode` ships the node **RAW**. A default that is fail-closed for a positive
 * predicate is fail-OPEN for a redaction selector.
 *
 * [RegexSafety.MAX_PROGRAM_SIZE] = 2 000 stops that specific pattern loading at all. These tests
 * cover the *residue* — any other deep-nullable pattern, on any smaller stack — by driving the
 * failure through a pattern that really does overflow, on a thread with a small stack, and
 * asserting each boundary's own safe answer.
 *
 * The overflow is provoked on a **dedicated 256 KiB thread** rather than the test thread: it is the
 * real failure mode (an ART coroutine thread), it keeps the blast radius inside this file, and it
 * does not depend on the JUnit runner's stack size.
 */
class RegexEvaluationFailureTest {

    private companion object {
        /**
         * Compiles to 16 084 instructions and overflows the match stack well under 1 MiB. Built
         * through the RE2J compiler directly, bypassing [RegexSafety]'s gates on purpose: the point
         * is what happens to a pattern the gates would now REJECT, so that the boundary behaviour
         * is pinned independently of whether any given ceiling lets it through.
         */
        val OVERFLOWING: BoundedRegex = BoundedRegex(
            com.google.re2j.Pattern.compile("^((.?){100}){40}$", com.google.re2j.Pattern.CASE_INSENSITIVE),
        )

        const val INPUT = "Alice"
    }

    /** Run [block] on a 256 KiB thread, rethrowing whatever it threw. */
    private fun <T> onSmallStack(block: () -> T): T {
        var result: Result<T>? = null
        val t = Thread(null, { result = runCatching(block) }, "regex-overflow-probe", 256L * 1024)
        t.start()
        t.join()
        return result!!.getOrThrow()
    }

    @Test
    fun `the probe really does overflow - otherwise every assertion below is vacuous`() {
        assertEquals(16_084, OVERFLOWING.programSize())
        try {
            onSmallStack { OVERFLOWING.containsMatchIn(INPUT) }
            // If a future RE2J makes its matcher iterative this stops overflowing, and these tests
            // stop proving anything — fail loudly rather than pass quietly.
            throw AssertionError(
                "the probe no longer overflows a 256 KiB stack — this whole file is now vacuous " +
                    "and needs a new probe (or the exposure is genuinely gone)",
            )
        } catch (e: RegexEvaluationFailed) {
            assertEquals("carries the pattern length only", "^((.?){100}){40}$".length, e.patternLength)
        }
    }

    // =========================================================================
    // Recognition — an unevaluable rule does not match
    // =========================================================================

    /**
     * A rule whose `require` predicate evaluates [OVERFLOWING].
     *
     * Built as a [CompiledRule] rather than compiled from rule JSON, because at
     * [RegexSafety.MAX_PROGRAM_SIZE] = 2 000 the overflowing pattern no longer LOADS — which is
     * exactly the first half of the round-5 fix. What is under test here is the second half: what
     * `Ruleset.matchFirst` and `CompiledRedact` do about the residue, so the predicate is handed to
     * the real production types directly.
     */
    private fun unevaluableRule(id: String, priority: Int) = CompiledRule<UiNode>(
        id = id,
        priority = priority,
        branches = listOf(
            CompiledBranch(predicate = { node -> OVERFLOWING.containsMatchIn(node.allText.joinToString(" ")) }),
        ),
    )

    private fun healthyRule(id: String, priority: Int, text: String) = CompiledRule<UiNode>(
        id = id,
        priority = priority,
        branches = listOf(CompiledBranch(predicate = { node -> node.allText.any { it == text } })),
    )

    @Test
    fun `recognition treats an unevaluable rule as no-match and moves on`() {
        // Two rules over the same frame: the first cannot be evaluated, the second can. The frame
        // must be claimed by the SECOND — not by the first, and not by a crash that takes the
        // classification coroutine with it.
        val ruleset = Ruleset(
            listOf(unevaluableRule("doordash.screen.unevaluable", 1), healthyRule("doordash.screen.healthy", 2, INPUT)),
        )
        val tree = UiNode(children = listOf(UiNode(text = INPUT))).restoreParents()
        assertEquals("doordash.screen.healthy", onSmallStack { ruleset.matchFirst(tree)?.ruleId })
    }

    @Test
    fun `a frame whose only rule is unevaluable classifies as nothing`() {
        // Fail-closed: no rule claims it, so the frame falls to UNKNOWN (captured and scrubbed),
        // which is the documented safe outcome for recognition.
        val ruleset = Ruleset(listOf(unevaluableRule("doordash.screen.unevaluable", 1)))
        val tree = UiNode(children = listOf(UiNode(text = INPUT))).restoreParents()
        assertNull(onSmallStack { ruleset.matchFirst(tree) })
    }

    // =========================================================================
    // Redaction — an unevaluable selector masks the WHOLE node
    // =========================================================================

    private fun overflowingRedact(keepPrefix: List<String> = emptyList()) = CompiledRedact(
        listOf(
            CompiledRedactEntry(
                find = { node -> OVERFLOWING.containsMatchIn(node.text.orEmpty()) },
                keepPrefix = keepPrefix,
            ),
        ),
    )

    @Test
    fun `an unevaluable redact selector masks the whole node instead of shipping it raw`() {
        // THE round-5 defect, stated as an assertion: this used to return the node UNCHANGED,
        // because BoundedRegex turned the overflow into `false` and `firstOrNull` read that as
        // "no entry matched".
        val tree = UiNode(
            children = listOf(UiNode(text = "Deliver to Alice Smith", viewIdResourceName = "customer_name")),
        ).restoreParents()

        val masked = onSmallStack { overflowingRedact(listOf("Deliver to ")).apply(tree) }
        val text = masked.children.single().text!!

        assertEquals(
            "an entry that cannot be evaluated masks the whole node — no keepPrefix, because we do " +
                "not know which entry would have matched",
            CompiledRedact.REDACTED,
            text,
        )
        assertTrue("the customer's name must not survive", !text.contains("Alice"))
        assertTrue("nor their surname", !text.contains("Smith"))
    }

    @Test
    fun `the whole subtree is still walked when a selector fails`() {
        // The failing node is masked AND its children are still visited, so PII deeper in the tree
        // is not skipped as collateral damage.
        val tree = UiNode(
            text = "root",
            children = listOf(UiNode(text = "Alice Smith", children = listOf(UiNode(text = "123 Main St")))),
        ).restoreParents()

        val masked = onSmallStack { overflowingRedact().apply(tree) }
        assertEquals(CompiledRedact.REDACTED, masked.text)
        assertEquals(CompiledRedact.REDACTED, masked.children.single().text)
        assertEquals(CompiledRedact.REDACTED, masked.children.single().children.single().text)
    }

    // =========================================================================
    // Parse — fail-null, never fail-wrong
    // =========================================================================

    @Test
    fun `an unevaluable parse read yields null rather than a wrong value`() {
        // Fail-null beats fail-wrong (#745). Driven through the same seam the parse compiler uses.
        assertNull(onSmallStack { runCatching { OVERFLOWING.find(INPUT) }.getOrNull() })
        // And the exception really is what came back, not a quiet false.
        try {
            onSmallStack { OVERFLOWING.find(INPUT) }
            throw AssertionError("expected RegexEvaluationFailed")
        } catch (e: RegexEvaluationFailed) {
            assertEquals("^((.?){100}){40}$".length, e.patternLength)
        }
    }
}
