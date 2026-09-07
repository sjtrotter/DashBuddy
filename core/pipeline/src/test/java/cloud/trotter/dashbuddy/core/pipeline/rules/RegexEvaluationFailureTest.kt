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
 * ## The probe
 *
 * [OVERFLOWING_PATTERN] is 924 instructions — it **loads** under
 * [RegexSafety.MAX_PROGRAM_SIZE] = 1 000 — and overflows RE2J's `Machine.add` on a 192 KiB stack.
 * That it exists at all is the point of the cap's own KDoc: the ceiling **bounds exposure, it does
 * not prove safety**, because recursion depth grows with nullable nesting rather than with
 * instruction count. ART's coroutine threads are ~1 MiB, so this particular shape is not a device
 * risk — it is a faithful, cheap stand-in for one that would be.
 */
class RegexEvaluationFailureTest {

    private companion object {
        /** 924 instructions: loads under the cap, overflows a 192 KiB stack on empty input. */
        const val OVERFLOWING_PATTERN = "^((.?){11}){20}\$"

        /** Small enough to overflow the probe, large enough that the JVM will create the thread. */
        const val PROBE_STACK_BYTES = 192L * 1024

        const val INPUT = ""
    }

    /** Run [block] on a small-stack thread, rethrowing whatever it threw. */
    private fun <T> onSmallStack(block: () -> T): T {
        var result: Result<T>? = null
        val t = Thread(null, { result = runCatching(block) }, "regex-overflow-probe", PROBE_STACK_BYTES)
        t.start()
        t.join()
        return result!!.getOrThrow()
    }

    private fun screenRuleset(rulesJson: String) = Ruleset(
        RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(rulesJson).jsonArray,
            RuleContext.SCREEN,
        ),
    )

    // =========================================================================
    // The probe itself — if this stops holding, every test below is vacuous
    // =========================================================================

    @Test
    fun `the probe loads through the production gates and really does overflow`() {
        val regex = RuleCompiler.compileRegex(OVERFLOWING_PATTERN)
        assertEquals("it must LOAD — a rejected pattern would test nothing", 924, regex.programSize())
        assertTrue(
            "and it must sit under the production ceiling",
            regex.programSize() <= RegexSafety.MAX_PROGRAM_SIZE,
        )
        try {
            onSmallStack { regex.containsMatchIn(INPUT) }
            throw AssertionError(
                "the probe no longer overflows a ${PROBE_STACK_BYTES / 1024} KiB stack — every " +
                    "test in this file is now vacuous and needs a new probe (or RE2J made its " +
                    "matcher iterative and the exposure is genuinely gone)",
            )
        } catch (e: RegexEvaluationFailed) {
            assertEquals("carries the pattern LENGTH only", OVERFLOWING_PATTERN.length, e.patternLength)
            assertTrue(
                "and never the pattern text — a rule pattern can quote screen content (P7)",
                !e.message!!.contains("(.?)"),
            )
        }
    }

    // =========================================================================
    // Recognition — an unevaluable rule does not match
    // Mutation check: delete the catch in Ruleset.matchFirst → both tests red.
    // =========================================================================

    @Test
    fun `recognition treats an unevaluable rule as no-match and moves on`() {
        val ruleset = screenRuleset(
            """[
                {
                  "id": "doordash.screen.unevaluable",
                  "priority": 1,
                  "require": { "exists": { "hasTextMatchesRegex": "$OVERFLOWING_PATTERN" } }
                },
                {
                  "id": "doordash.screen.healthy",
                  "priority": 2,
                  "require": { "exists": { "hasText": "Total" } }
                }
            ]""",
        )
        val tree = UiNode(children = listOf(UiNode(text = "Total"))).restoreParents()
        assertEquals(
            "the frame is claimed by the rule that CAN be evaluated",
            "doordash.screen.healthy",
            onSmallStack { ruleset.matchFirst(tree, "doordash")?.ruleId },
        )
    }

    @Test
    fun `a frame whose only rule is unevaluable classifies as nothing`() {
        // Fail-closed: no rule claims it, so the frame falls to UNKNOWN (captured and scrubbed).
        val ruleset = screenRuleset(
            """[{
                "id": "doordash.screen.unevaluable",
                "priority": 1,
                "require": { "exists": { "hasTextMatchesRegex": "$OVERFLOWING_PATTERN" } }
            }]""",
        )
        val tree = UiNode(children = listOf(UiNode(text = "Total"))).restoreParents()
        assertNull(onSmallStack { ruleset.matchFirst(tree, "doordash") })
    }

    // =========================================================================
    // Screen redaction — an unevaluable selector masks the WHOLE node
    // Mutation check: delete the catch in CompiledRedact.maskNode → all three red.
    // =========================================================================

    private fun redactingRuleset() = screenRuleset(
        """[{
            "id": "doordash.screen.with_redact",
            "priority": 1,
            "require": { "exists": { "hasText": "Total" } },
            "redact": [
                { "find": { "hasTextMatchesRegex": "$OVERFLOWING_PATTERN" }, "keepPrefix": ["Deliver to "] }
            ]
        }]"""
    )

    private fun compiledRedact(): CompiledRedact =
        RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(
                """[{
                    "id": "doordash.screen.with_redact",
                    "priority": 1,
                    "require": { "exists": { "hasText": "Total" } },
                    "redact": [
                        { "find": { "hasTextMatchesRegex": "$OVERFLOWING_PATTERN" }, "keepPrefix": ["Deliver to "] }
                    ]
                }]""",
            ).jsonArray,
            RuleContext.SCREEN,
        ).single().redact

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

        val masked = onSmallStack { compiledRedact().apply(tree) }
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
        assertTrue("the customer's name must not survive anywhere", !node.text!!.contains("Alice"))
        assertTrue("nor in the description", !node.contentDescription!!.contains("Main St"))
    }

    @Test
    fun `the whole subtree is still walked when a selector fails`() {
        val tree = UiNode(
            text = "root",
            children = listOf(UiNode(text = "Alice Smith", children = listOf(UiNode(text = "123 Main St")))),
        ).restoreParents()

        val masked = onSmallStack { compiledRedact().apply(tree) }
        assertEquals(CompiledRedact.REDACTED, masked.text)
        assertEquals(CompiledRedact.REDACTED, masked.children.single().text)
        assertEquals(CompiledRedact.REDACTED, masked.children.single().children.single().text)
    }

    @Test
    fun `redaction does not mutate the original tree - the dedup hash still sees the raw node`() {
        // The capture dedup contentHash is computed on the ORIGINAL tree; `apply` returns a COPY.
        // Pinned here because a failure-path mask that mutated in place would silently change the
        // dedup identity of every frame it touched.
        val tree = UiNode(children = listOf(UiNode(text = "Deliver to Alice Smith"))).restoreParents()
        onSmallStack { compiledRedact().apply(tree) }
        assertEquals("Deliver to Alice Smith", tree.children.single().text)
    }

    // =========================================================================
    // Notification redaction — the WHOLE field, not just the capture group
    // Mutation check: delete the catch in CompiledNotifRedact.maskField → red.
    // =========================================================================

    @Test
    fun `an unevaluable notification capture masks the whole field and leaves the others alone`() {
        val redact = CompiledNotifRedact(
            mapOf(
                NotifTextField.TITLE to
                    NotifFieldMask.RegexGroup(RuleCompiler.compileRegex(OVERFLOWING_PATTERN), 1),
            ),
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
    // Screen parse — fail-null PER FIELD; the rest of the map survives
    // Mutation check: delete failNullOnEvaluationFailure in ParseExpressionCompiler → red.
    // =========================================================================

    @Test
    fun `a failing parse field is null and its siblings survive`() {
        // #1053 round 6 / F1. Before the per-field catch the exception reached `Ruleset`'s generic
        // parse catch, which replaces the ENTIRE map with emptyMap() — so a good `healthy` was lost
        // along with a failing `failing` — and logged "Parse error in rule" once per FRAME, drowning
        // the single per-pattern WARN BoundedRegex already emits.
        val ruleset = screenRuleset(
            """[{
                "id": "doordash.screen.two_fields",
                "priority": 1,
                "require": { "exists": { "hasText": "Total" } },
                "parse": {
                    "as": "idle",
                    "fields": {
                        "zoneName": { "find": { "hasText": "Total" }, "read": "text" },
                        "sessionType": { "find": { "hasTextMatchesRegex": "$OVERFLOWING_PATTERN" }, "read": "text" }
                    }
                }
            }]""",
        )
        val tree = UiNode(children = listOf(UiNode(text = "Total"))).restoreParents()

        val match = onSmallStack { ruleset.matchFirst(tree, "doordash") }
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
                        "sessionType": { "findAll": { "hasTextMatchesRegex": "$OVERFLOWING_PATTERN" }, "read": "text" }
                    }
                }
            }]""",
        )
        val tree = UiNode(children = listOf(UiNode(text = "Total"))).restoreParents()

        val match = onSmallStack { ruleset.matchFirst(tree, "doordash") }
        assertNotNull(match)
        assertEquals("Total", match!!.fields["zoneName"])
        assertNull(match.fields["sessionType"])
    }

    // =========================================================================
    // Parse transform + sibling navigation — fail-null
    // Mutation check: delete the catch in TransformRegistry / CompilerHelpers → red.
    // =========================================================================

    @Test
    fun `an unevaluable regex transform yields null`() {
        val spec = Json.parseToJsonElement(
            """{"regex":{"pattern":"$OVERFLOWING_PATTERN","group":0}}""",
        )
        assertNull(onSmallStack { TransformRegistry.applyAny(spec, INPUT) })
    }

    @Test
    fun `an unevaluable sibling scan resolves no sibling, and a declared fallback still applies`() {
        // The `fallback` is what makes this a real test of `CompilerHelpers`' OWN catch rather than
        // of the parse-expression wrapper above it. Both would leave the field non-fatal, but they
        // differ in what the field becomes: the inner catch resolves "no sibling", so the
        // expression continues and a declared `fallback` supplies its value; if only the outer
        // wrapper existed, the whole field would be null and the fallback would never be reached.
        // Mutation check: deleting CompilerHelpers' catch turns this assertion from "fallback" to
        // null.
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
                            "navigate": "nextSiblingMatchingRegex($OVERFLOWING_PATTERN)",
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

        val match = onSmallStack { ruleset.matchFirst(tree, "doordash") }
        assertNotNull("the branch still matches", match)
        assertEquals(
            "the scan resolves nothing rather than the wrong sibling, and the rule's own declared " +
                "fallback still gets to speak",
            "no-sibling",
            match!!.fields["zoneName"],
        )
    }
}
