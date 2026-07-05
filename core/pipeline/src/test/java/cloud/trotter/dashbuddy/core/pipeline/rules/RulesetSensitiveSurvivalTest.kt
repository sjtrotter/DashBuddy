package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #419 (2b) — structural sensitive-rule survival across a ruleset replacement.
 *
 * Before this change the sensitive block worked ONLY via priority-0-first +
 * unique-priority + first-match; the parsed `overrideable` flag was inert. A
 * replacement bundle (or a future multi-source merge, #192) that games priorities
 * — giving a non-sensitive rule a LOWER priority number than a non-overrideable
 * sensitive rule — could pre-empt the sensitive classification.
 *
 * [Ruleset] now evaluates the NON-overrideable partition FIRST (priority-ordered),
 * then the overrideable partition — so no priority value in any later/other source
 * can pre-empt a non-overrideable classification. These tests pin that property;
 * the priority-gaming test is RED on the old sort-only [Ruleset.matchFirst].
 */
class RulesetSensitiveSurvivalTest {

    private fun screenRule(
        id: String,
        priority: Int,
        overrideable: Boolean,
        shape: String,
        matches: (UiNode) -> Boolean,
    ) = CompiledRule<UiNode>(
        id = id,
        priority = priority,
        overrideable = overrideable,
        branches = listOf(
            CompiledBranch(
                predicate = matches,
                shape = shape,
                intent = shape,
            ),
        ),
    )

    /**
     * ADVERSARIAL priority-gaming: a non-sensitive OVERRIDEABLE rule carries a
     * LOWER priority number (5) than the non-overrideable sensitive rule (10), so
     * a pure-priority sort evaluates it FIRST and would classify the screen as the
     * attacker's shape. The non-overrideable partition must still win.
     *
     * RED on master (sort-only): matchFirst returns "offer". GREEN with the
     * partition: it returns "sensitive".
     */
    @Test
    fun `non-overrideable sensitive rule wins even when a non-sensitive rule has a lower priority number`() {
        val screen = UiNode(text = "Available Balance")
        val ruleset = Ruleset(
            listOf(
                // Attacker rule: lower priority number, greedily matches everything.
                screenRule("evil.screen.greedy", priority = 5, overrideable = true, shape = "offer") { true },
                // The real sensitive block, non-overrideable, HIGHER priority number.
                screenRule("dd.screen.sensitive.known", priority = 10, overrideable = false, shape = "sensitive") {
                    it.text == "Available Balance"
                },
            ),
        )

        val result = ruleset.matchFirst(screen)
        assertEquals(
            "the non-overrideable sensitive rule must win regardless of a lower attacker priority",
            "sensitive",
            result?.shape,
        )
    }

    /**
     * Byte-inertness within a partition: today's assets already put the sensitive
     * block at priority 0, so ordering must be unchanged. Two overrideable rules
     * still resolve by priority number (lower first).
     */
    @Test
    fun `within a partition, lower priority number still wins`() {
        val screen = UiNode(text = "x")
        val ruleset = Ruleset(
            listOf(
                screenRule("dd.screen.b", priority = 20, overrideable = true, shape = "b") { true },
                screenRule("dd.screen.a", priority = 10, overrideable = true, shape = "a") { true },
            ),
        )
        assertEquals("a", ruleset.matchFirst(screen)?.shape)
    }

    /**
     * A high-priority-NUMBER non-overrideable "catchall" fallback (the shape of
     * `sensitive.catchall`, priority 999) must NOT jump ahead of specific
     * recognition — that would reverse its deliberate last-resort design. This is
     * why the shipped catchall is `overrideable: true`: only `overrideable: false`
     * rules join the protected front partition.
     */
    @Test
    fun `an overrideable high-number fallback stays last, letting specific recognition win`() {
        val screen = UiNode(text = "Withdraw available")
        val ruleset = Ruleset(
            listOf(
                // Overrideable broad fallback at a high priority number (catchall shape).
                screenRule("dd.screen.sensitive.catchall", priority = 999, overrideable = true, shape = "sensitive") {
                    it.text?.contains("Withdraw") == true
                },
                // Specific recognition of the same screen at a normal priority.
                screenRule("dd.screen.some_offer", priority = 10, overrideable = true, shape = "offer") {
                    it.text?.contains("Withdraw") == true
                },
            ),
        )
        assertEquals(
            "specific recognition (priority 10) must override the overrideable catchall (priority 999)",
            "offer",
            ruleset.matchFirst(screen)?.shape,
        )
    }
}
