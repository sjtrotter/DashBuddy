package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.NotifTextField
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What each boundary does when a rule regex cannot be **evaluated** (#1053 rounds 5–6).
 *
 * The defect these pin: `BoundedRegex` used to convert a match-time `StackOverflowError` into the
 * operation's default. For a positive `require` predicate `false` is fail-closed and fine; for a
 * `redact[].find` selector it reads as "no entry matched" and `CompiledRedact.maskNode` ships the
 * node **raw**. One default cannot serve a positive predicate, a negated predicate and a redaction
 * selector at once, so the failure is now a distinguishable `RegexEvaluationFailed` and each
 * boundary answers it.
 *
 * ## Why these are real tests and not a rehearsal
 *
 * Every case below goes through the **production** compile path (`RuleCompiler`/`RegexSafety`) with
 * a pattern that genuinely loads, and provokes a **genuine** `StackOverflowError` by running the
 * match on a thread with a small stack. Nothing here supplies its own `try`/`catch` — round 5's
 * version wrapped the call in a test-side `runCatching`, which would have stayed green with the
 * production catches deleted. Each test is mutation-checked: removing the catch it names turns it
 * red.
 *
 * ## The probe, and why it is built two ways
 *
 * Provoking a real `StackOverflowError` needs a pattern deep enough to exhaust a thread's stack,
 * and how deep that is depends on the JVM's frame size — which differs by version and
 * architecture. Round 6's first attempt used one 924-instruction pattern on a 192 KiB thread; it
 * overflowed on the development machine (JDK 25) and **did not** on CI (JDK 21), turning five
 * tests red. A test whose premise is "this overflows here" is not a test, it is a local
 * observation.
 *
 * So there are two probes:
 *
 *  - [DEEP] — `^((.?){200}){100}$`, 80 204 instructions, constructed **directly** from RE2J and
 *    deliberately bypassing [RegexSafety]'s gates (at [RegexSafety.MAX_PROGRAM_SIZE] = 1 000 it no
 *    longer loads, which is the first half of the round-5 fix). It overflows even an **8 MiB**
 *    stack, so on the small probe thread used here the margin is about 50× and no plausible frame
 *    size escapes it. Everything that can be assembled from compiled types — recognition, screen
 *    redaction, notification redaction — uses this one.
 *  - [LOADABLE] — `^((.?){11}){20}$`, 924 instructions, which really does compile through the
 *    production gates, for the cases that must go through **rule JSON** (the screen parse path and
 *    the sibling scan). Those cannot use [DEEP] because a rule carrying it would be rejected at
 *    load. Its overflow is the environment-dependent one, so those tests search a ladder of stack
 *    sizes and, if the JVM's frames are small enough that none of them overflow, [assumeTrue] out
 *    with a clear message rather than reporting a green they did not earn.
 *
 * Both paths are mutation-checked (removing each production catch turns the corresponding test
 * red); the ladder only decides whether the two rule-JSON cases can run in a given environment.
 */
class RegexEvaluationFailureTest {

    private companion object {
        /**
         * 80 204 instructions — far past [RegexSafety.MAX_PROGRAM_SIZE], so it is built straight
         * from RE2J rather than through the production gates.
         *
         * Deliberately far deeper than it needs to be: it overflows even an **8 MiB** stack, so on
         * the small probe thread used here the margin is ~50×. That is the point — round 6's first
         * attempt used a shape that overflowed on the development JVM (25) and not on CI's (21),
         * because frame size varies by JVM and architecture. No plausible frame size escapes this
         * one.
         */
        val DEEP: BoundedRegex = BoundedRegex(
            com.google.re2j.Pattern.compile("^((.?){200}){100}$", com.google.re2j.Pattern.CASE_INSENSITIVE),
        )

        /** 924 instructions: LOADS through the production gates, for the rule-JSON cases. */
        const val LOADABLE = "^((.?){11}){20}\$"

        /**
         * Stack sizes to try for [LOADABLE], ascending. Below ~136 KiB the JVM refuses the thread.
         *
         * The LARGEST size that still overflows is the one used, not the smallest: the probe thread
         * also runs `matchFirst`, the parse and the redaction walk, and on a stack tuned to barely
         * overflow the regex those ordinary frames overflow too — producing a raw
         * `StackOverflowError` outside any of the catches under test. Maximum headroom that still
         * provokes the failure is the right operating point.
         */
        val STACK_LADDER = listOf(136L, 160L, 192L, 224L, 256L, 320L, 448L).map { it * 1024 }

        /**
         * [DEEP] overflows even an 8 MiB stack, so its probe thread gets a roomy 1 MiB: ample for
         * everything else the test does on that thread, and still an ~8× margin on the overflow.
         */
        const val DEEP_STACK_BYTES = 1024L * 1024

        const val INPUT = ""
    }

    /** Run [block] on a thread with [stackBytes] of stack, rethrowing whatever it threw. */
    private fun <T> onStack(stackBytes: Long, block: () -> T): T {
        var result: Result<T>? = null
        val t = Thread(null, { result = runCatching(block) }, "regex-overflow-probe", stackBytes)
        t.start()
        t.join()
        return result!!.getOrThrow()
    }

    /** Run [block] where [DEEP] is certain to overflow, with room to spare for everything else. */
    private fun <T> onSmallStack(block: () -> T): T = onStack(DEEP_STACK_BYTES, block)

    /**
     * Run [block] on a stack where [LOADABLE] is **demonstrated, on that very thread and at that
     * very moment**, to overflow — or skip the test.
     *
     * The demonstration has to happen inline, immediately before the block, and it took two
     * attempts to learn why. A 924-instruction pattern is by construction *marginal*: at
     * [RegexSafety.MAX_PROGRAM_SIZE] = 1 000 the cap exists precisely to keep patterns far away from
     * overflowing, so anything that still loads sits within a hair of the boundary. Two consequences
     * bit in turn:
     *
     *  - a stack tuned to *barely* overflow the regex also overflows the ordinary frames of
     *    `matchFirst`, the parse and the redaction walk, throwing a raw `StackOverflowError` outside
     *    every catch under test — hence [STACK_LADDER] takes the LARGEST size that still overflows,
     *    not the smallest;
     *  - and JIT state moves *between* a separate probe call and the assertion, so a ladder result
     *    computed earlier could be stale by the time the real call ran, which is how a run produced
     *    the real sibling value where the fallback was expected.
     *
     * So: one thread, probe first, block second, and if the probe does not overflow this test does
     * not pretend to have proved anything. The compiled-type tests above use [DEEP] and have no
     * such fragility — these three are the ones that must go through rule JSON.
     */
    /**
     * Run [block] on progressively smaller stacks until it comes back showing that the overflow
     * happened — [provoked] says how to recognise that — and return that result. If no stack size
     * provokes it, **skip**.
     *
     * The block IS the probe. A separate probe call does not work here, and learning that took two
     * attempts: a 924-instruction pattern is *marginal by construction* — at
     * [RegexSafety.MAX_PROGRAM_SIZE] = 1 000 the cap exists precisely to keep patterns far from
     * overflowing, so anything that still loads sits within a hair of the boundary, and JIT
     * compilation moved that boundary **between a probe call and the assertion microseconds later**.
     * A run produced the real sibling value where the fallback was expected, from a thread that had
     * just demonstrated the overflow.
     *
     * So the outcomes are: the block shows the failure path → assert; the block completes normally →
     * the overflow could not be provoked here, skip rather than claim a proof; the block throws a
     * raw `StackOverflowError` → the production catch is missing and the test **fails**, which is
     * what makes these mutation-sensitive despite the skip.
     *
     * The compiled-type tests above have none of this fragility — [DEEP] carries ~8× margin. These
     * three are the ones that must go through rule JSON, where only a loadable pattern is possible.
     */
    private fun <T> whereOverflowProvoked(provoked: (T) -> Boolean, block: () -> T): T {
        for (bytes in STACK_LADDER.reversed()) {
            val value = try {
                onStack(bytes) { block() }
            } catch (e: OutOfMemoryError) {
                continue // the JVM refused a stack this small
            }
            if (provoked(value)) return value
        }
        assumeTrue(
            "on this JVM a ${RegexSafety.MAX_PROGRAM_SIZE}-instruction pattern could not be driven " +
                "to overflow at any stack size the JVM will allocate " +
                "(${STACK_LADDER.map { it / 1024 }} KiB) — frame size and JIT state both move that " +
                "boundary, and the cap's whole job is to keep loadable patterns away from it. The " +
                "compiled-type cases, which use a pattern with ~8x margin, still run.",
            false,
        )
        error("unreachable")
    }

    // =========================================================================
    // The probes — if these stop holding, the tests below are vacuous
    // =========================================================================

    @Test
    fun `the deep probe overflows regardless of this JVM's frame size`() {
        assertEquals(80_204, DEEP.programSize())
        try {
            onSmallStack { DEEP.containsMatchIn(INPUT) }
            throw AssertionError(
                "the deep probe no longer overflows a ${DEEP_STACK_BYTES / 1024} KiB stack — the " +
                    "compiled-type tests below are now vacuous and need a new probe (or RE2J made " +
                    "its matcher iterative and the exposure is genuinely gone)",
            )
        } catch (e: RegexEvaluationFailed) {
            assertEquals("carries the pattern LENGTH only", "^((.?){200}){100}\$".length, e.patternLength)
            assertTrue(
                "and never the pattern text — a rule pattern can quote screen content (P7)",
                !e.message!!.contains("(.?)"),
            )
        }
    }

    @Test
    fun `the loadable probe really does load through the production gates`() {
        // Environment-independent half: it must COMPILE under the cap, which is what lets the
        // rule-JSON cases below exercise the real path at all.
        val regex = RuleCompiler.compileRegex(LOADABLE)
        assertEquals(924, regex.programSize())
        assertTrue(
            "it must sit under the production ceiling — a rejected pattern would test nothing",
            regex.programSize() <= RegexSafety.MAX_PROGRAM_SIZE,
        )
    }

    // =========================================================================
    // Recognition — an unevaluable rule does not match
    // Mutation check: delete the catch in Ruleset.matchFirst -> both tests red.
    // =========================================================================

    private fun unevaluableRule(id: String, priority: Int) = CompiledRule<UiNode>(
        id = id,
        priority = priority,
        branches = listOf(
            CompiledBranch(predicate = { node -> DEEP.containsMatchIn(node.allText.joinToString(" ")) }),
        ),
    )

    private fun healthyRule(id: String, priority: Int, text: String) = CompiledRule<UiNode>(
        id = id,
        priority = priority,
        branches = listOf(CompiledBranch(predicate = { node -> node.allText.any { it == text } })),
    )

    @Test
    fun `recognition treats an unevaluable rule as no-match and moves on`() {
        val ruleset = Ruleset(
            listOf(
                unevaluableRule("doordash.screen.unevaluable", 1),
                healthyRule("doordash.screen.healthy", 2, "Total"),
            ),
        )
        val tree = UiNode(children = listOf(UiNode(text = "Total"))).restoreParents()
        assertEquals(
            "the frame is claimed by the rule that CAN be evaluated",
            "doordash.screen.healthy",
            onSmallStack { ruleset.matchFirst(tree)?.ruleId },
        )
    }

    @Test
    fun `a frame whose only rule is unevaluable classifies as nothing`() {
        // Fail-closed: no rule claims it, so the frame falls to UNKNOWN (captured and scrubbed).
        val ruleset = Ruleset(listOf(unevaluableRule("doordash.screen.unevaluable", 1)))
        val tree = UiNode(children = listOf(UiNode(text = "Total"))).restoreParents()
        assertNull(onSmallStack { ruleset.matchFirst(tree) })
    }

    // =========================================================================
    // Screen redaction — an unevaluable selector masks the WHOLE node
    // Mutation check: delete the catch in CompiledRedact.maskNode -> all three red.
    // =========================================================================

    private fun overflowingRedact() = CompiledRedact(
        listOf(
            CompiledRedactEntry(
                find = { node -> DEEP.containsMatchIn(node.text.orEmpty()) },
                keepPrefix = listOf("Deliver to "),
            ),
        ),
    )

    @Test
    fun `an unevaluable redact selector masks the whole node instead of shipping it raw`() {
        // THE round-5 defect, as an assertion: this used to return the node UNCHANGED.
        val tree = UiNode(
            children = listOf(
                UiNode(
                    text = "Deliver to Alice Smith",
                    contentDescription = "Alice Smith, 123 Main St",
                    viewIdResourceName = "customer_name",
                ),
            ),
        ).restoreParents()

        val masked = onSmallStack { overflowingRedact().apply(tree) }
        val node = masked.children.single()

        assertEquals(
            "the whole node — no keepPrefix, because we do not know which entry would have matched",
            CompiledRedact.REDACTED,
            node.text,
        )
        assertEquals(
            "contentDescription is masked too (#835 — every serialized string field)",
            CompiledRedact.REDACTED,
            node.contentDescription,
        )
        assertTrue("the customer's name must not survive", !node.text!!.contains("Alice"))
        assertTrue("nor their address in the description", !node.contentDescription!!.contains("Main St"))
    }

    @Test
    fun `the whole subtree is still walked when a selector fails`() {
        val tree = UiNode(
            text = "root",
            children = listOf(UiNode(text = "Alice Smith", children = listOf(UiNode(text = "123 Main St")))),
        ).restoreParents()

        val masked = onSmallStack { overflowingRedact().apply(tree) }
        assertEquals(CompiledRedact.REDACTED, masked.text)
        assertEquals(CompiledRedact.REDACTED, masked.children.single().text)
        assertEquals(CompiledRedact.REDACTED, masked.children.single().children.single().text)
    }

    @Test
    fun `redaction does not mutate the original tree - the dedup hash still sees the raw node`() {
        // The capture dedup contentHash is computed on the ORIGINAL tree; `apply` returns a COPY.
        // Pinned because a failure-path mask that mutated in place would silently change the dedup
        // identity of every frame it touched.
        val tree = UiNode(children = listOf(UiNode(text = "Deliver to Alice Smith"))).restoreParents()
        onSmallStack { overflowingRedact().apply(tree) }
        assertEquals("Deliver to Alice Smith", tree.children.single().text)
    }

    // =========================================================================
    // Notification redaction — the WHOLE field, not just the capture group
    // Mutation check: delete the catch in CompiledNotifRedact.maskField -> red.
    // =========================================================================

    @Test
    fun `an unevaluable notification capture masks the whole field and leaves the others alone`() {
        val redact = CompiledNotifRedact(
            mapOf(NotifTextField.TITLE to NotifFieldMask.RegexGroup(DEEP, 1)),
        )
        val raw = RawNotificationData(
            title = "Alice",
            text = "Your order is on the way",
            tickerText = null,
            bigText = null,
            packageName = "com.doordash.driverapp",
            postTime = 0L,
            isClearable = true,
        )

        val masked = onSmallStack { redact.apply(raw) }
        assertEquals(
            "the WHOLE title — not just the capture group, because we cannot tell what the group " +
                "would have been, so any merchant suffix goes with it",
            CompiledRedact.REDACTED,
            masked.title,
        )
        assertEquals("a field with no mask declared is untouched", raw.text, masked.text)
        assertNull("a null field stays null", masked.tickerText)
    }

    // =========================================================================
    // Screen parse — fail-null PER FIELD; the rest of the map survives.
    // These go through rule JSON, so they use LOADABLE + the stack ladder.
    // Mutation check: delete failNullOnEvaluationFailure -> both red.
    // =========================================================================

    private fun screenRuleset(rulesJson: String) = Ruleset(
        RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(rulesJson).jsonArray,
            RuleContext.SCREEN,
        ),
    )

    @Test
    fun `a failing parse field is null and its siblings survive`() {
        // #1053 round 6 / F1. Before the per-field catch the exception reached `Ruleset`'s generic
        // parse catch, which replaces the ENTIRE map with emptyMap() — so a good `zoneName` was lost
        // along with a failing `sessionType` — and logged "Parse error in rule" once per FRAME,
        // drowning the single per-pattern WARN BoundedRegex already emits.
        val ruleset = screenRuleset(
            """[{
                "id": "doordash.screen.two_fields",
                "priority": 1,
                "require": { "exists": { "hasText": "Total" } },
                "parse": {
                    "as": "idle",
                    "fields": {
                        "zoneName": { "find": { "hasText": "Total" }, "read": "text" },
                        "sessionType": { "find": { "hasTextMatchesRegex": "$LOADABLE" }, "read": "text" }
                    }
                }
            }]""",
        )
        val tree = UiNode(children = listOf(UiNode(text = "Total"))).restoreParents()

        val match = whereOverflowProvoked({ it?.fields?.get("sessionType") == null }) {
            ruleset.matchFirst(tree, "doordash")
        }
        assertNotNull("the branch still matches", match)
        assertEquals("doordash.screen.two_fields", match!!.ruleId)
        assertEquals("the healthy field survives", "Total", match.fields["zoneName"])
        assertNull("only the unevaluable field is null", match.fields["sessionType"])
    }

    @Test
    fun `an unevaluable parse field inside a collection is null rather than losing the map`() {
        // `each`/`findAll` evaluate node predicates too — the wrapper sits at the one dispatch, so
        // every arm is covered rather than the seven that happened to be listed.
        val ruleset = screenRuleset(
            """[{
                "id": "doordash.screen.collection",
                "priority": 1,
                "require": { "exists": { "hasText": "Total" } },
                "parse": {
                    "as": "idle",
                    "fields": {
                        "zoneName": { "find": { "hasText": "Total" }, "read": "text" },
                        "sessionType": { "findAll": { "hasTextMatchesRegex": "$LOADABLE" }, "read": "text" }
                    }
                }
            }]""",
        )
        val tree = UiNode(children = listOf(UiNode(text = "Total"))).restoreParents()

        val match = whereOverflowProvoked({ it?.fields?.get("sessionType") == null }) {
            ruleset.matchFirst(tree, "doordash")
        }
        assertNotNull(match)
        assertEquals("Total", match!!.fields["zoneName"])
        assertNull(match.fields["sessionType"])
    }

    // =========================================================================
    // Parse transform + sibling navigation — fail-null
    // Mutation check: delete the catch in TransformRegistry / CompilerHelpers -> red.
    // =========================================================================

    @Test
    fun `an unevaluable regex transform yields null`() {
        val spec = Json.parseToJsonElement("""{"regex":{"pattern":"$LOADABLE","group":0}}""")
        assertNull(whereOverflowProvoked({ it == null }) { TransformRegistry.applyAny(spec, INPUT) })
    }

    @Test
    fun `an unevaluable sibling scan resolves no sibling, and a declared fallback still applies`() {
        // The `fallback` is what makes this a real test of `CompilerHelpers`' OWN catch rather than
        // of the parse-expression wrapper above it. Both leave the field non-fatal, but they differ
        // in what it becomes: the inner catch resolves "no sibling", so the expression continues and
        // a declared `fallback` supplies its value; if only the outer wrapper existed the whole
        // field would be null and the fallback would never be reached. (The first mutation run
        // caught exactly that: without this assertion the test was green with the inner catch gone.)
        val ruleset = screenRuleset(
            """[{
                "id": "doordash.screen.sibling_scan",
                "priority": 1,
                "require": { "exists": { "hasText": "Customer tips" } },
                "parse": {
                    "as": "idle",
                    "fields": {
                        "zoneName": {
                            "find": { "hasText": "Customer tips" },
                            "navigate": "nextSiblingMatchingRegex($LOADABLE)",
                            "read": "text",
                            "fallback": "no-sibling"
                        }
                    }
                }
            }]""",
        )
        val tree = UiNode(
            children = listOf(UiNode(text = "Customer tips"), UiNode(text = "\$7.00")),
        ).restoreParents()

        // "Provoked" is "the scan did NOT return the real sibling" — deliberately wider than "the
        // fallback applied". If it were the narrower thing, deleting CompilerHelpers' catch would
        // make the field null, which is not the fallback, which would look like "could not provoke"
        // and SKIP instead of failing. Widening it means the removed catch lands as a real
        // assertion failure below. (The first mutation run caught exactly that.)
        val match = whereOverflowProvoked({ it?.fields?.get("zoneName") != "\$7.00" }) {
            ruleset.matchFirst(tree, "doordash")
        }
        assertNotNull("the branch still matches", match)
        assertEquals(
            "the scan resolves nothing rather than the wrong sibling, and the rule's own declared " +
                "fallback still gets to speak",
            "no-sibling",
            match!!.fields["zoneName"],
        )
    }
}
