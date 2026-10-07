package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the rule-driven effect key contract used for deduplication. */
class RuleEffectDispatchTest {

    @Test
    fun `effectKey uses dedupeKey when present`() {
        val effect = makeEffect(EffectVerb.SCREENSHOT, dedupeKey = "offer-ss")
        val appEffect = AppEffect.RequestEffect(effect)
        assertEquals("effect:test.rule:offer-ss", appEffect.effectKey)
    }

    @Test
    fun `effectKey falls back to verb wire when no dedupeKey`() {
        val effect = makeEffect(EffectVerb.SCREENSHOT)
        val appEffect = AppEffect.RequestEffect(effect)
        assertEquals("effect:test.rule:screenshot", appEffect.effectKey)
    }

    @Test
    fun `effectKey differs per verb`() {
        val keys = EffectVerb.entries
            .map { AppEffect.RequestEffect(makeEffect(it)).effectKey }
            .toSet()
        // Each verb should produce a unique key
        assertEquals(EffectVerb.entries.size, keys.size)
    }

    private fun makeEffect(
        verb: EffectVerb,
        dedupeKey: String? = null,
    ) = RequestedEffect(
        verb = verb,
        args = emptyMap(),
        dedupeKey = dedupeKey,
        ruleId = "test.rule",
    )
}
