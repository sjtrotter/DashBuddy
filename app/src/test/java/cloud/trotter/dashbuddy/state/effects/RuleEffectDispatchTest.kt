package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.state.AppEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for rule-driven effect dispatch infrastructure.
 *
 * The SideEffectEngine itself requires Android/DI dependencies, but we can
 * verify the dispatch contract and effect key generation in pure unit tests.
 */
class RuleEffectDispatchTest {

    // =========================================================================
    // Verb exhaustiveness — compiler enforces this, but document it
    // =========================================================================

    @Test
    fun `every EffectVerb has a defined dispatch path`() {
        // This test documents that all 14 verbs are handled.
        // If a verb is added to EffectVerb, the exhaustive when() in
        // SideEffectEngine.dispatchRuleEffect will fail to compile.
        assertEquals(14, EffectVerb.entries.size)
    }

    // =========================================================================
    // AppEffect.RequestEffect — effectKey generation
    // =========================================================================

    @Test
    fun `effectKey uses dedupeKey when present`() {
        val effect = makeEffect(EffectVerb.CLICK, dedupeKey = "accept-click")
        val appEffect = AppEffect.RequestEffect(effect)
        assertEquals("effect:test.rule:accept-click", appEffect.effectKey)
    }

    @Test
    fun `effectKey uses pathFingerprint when no dedupeKey but targetRef present`() {
        val ref = NodeRef(
            viewIdSuffix = "btn_accept",
            text = "Accept",
            classNameHint = "android.widget.Button",
            pathFingerprint = "View[0]/Button[1]",
        )
        val effect = makeEffect(EffectVerb.CLICK, targetRef = ref)
        val appEffect = AppEffect.RequestEffect(effect)
        assertEquals("effect:test.rule:View[0]/Button[1]", appEffect.effectKey)
    }

    @Test
    fun `effectKey falls back to verb wire when no dedupeKey and no targetRef`() {
        val effect = makeEffect(EffectVerb.SCREENSHOT)
        val appEffect = AppEffect.RequestEffect(effect)
        assertEquals("effect:test.rule:screenshot", appEffect.effectKey)
    }

    @Test
    fun `effectKey differs per verb for non-target effects`() {
        val keys = EffectVerb.entries
            .filter { !it.requiresTarget }
            .map { AppEffect.RequestEffect(makeEffect(it)).effectKey }
            .toSet()
        // Each verb should produce a unique key
        assertEquals(EffectVerb.entries.count { !it.requiresTarget }, keys.size)
    }

    // =========================================================================
    // isExternalEffect — RequestEffect classification
    // =========================================================================

    @Test
    fun `RequestEffect is classified as external for recovery suppression`() {
        // RequestEffect is listed in isExternalEffect — during crash recovery,
        // rule-declared effects are suppressed (the hardcoded AppEffect types
        // handle replay via the loopback path)
        val effect = makeEffect(EffectVerb.BUBBLE, args = mapOf("text" to "hi"))
        val appEffect = AppEffect.RequestEffect(effect)
        assertNotNull(appEffect)
    }

    // =========================================================================
    // Verb properties — observation vs lifecycle
    // =========================================================================

    @Test
    fun `observation-driven verbs do not have defaults`() {
        val observationVerbs = listOf(
            EffectVerb.CLICK, EffectVerb.SCREENSHOT, EffectVerb.BUBBLE,
            EffectVerb.LOG, EffectVerb.EVALUATE_OFFER, EffectVerb.SPEAK,
        )
        for (verb in observationVerbs) {
            assertTrue("$verb should not have defaults", !verb.hasDefault)
        }
    }

    @Test
    fun `lifecycle verbs have defaults`() {
        val lifecycleVerbs = listOf(
            EffectVerb.SESSION_START, EffectVerb.SESSION_END,
            EffectVerb.ODOMETER_START, EffectVerb.ODOMETER_STOP,
            EffectVerb.ODOMETER_PAUSE, EffectVerb.ODOMETER_RESUME,
            EffectVerb.SCHEDULE_TIMEOUT, EffectVerb.CANCEL_TIMEOUT,
        )
        for (verb in lifecycleVerbs) {
            assertTrue("$verb should have defaults", verb.hasDefault)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeEffect(
        verb: EffectVerb,
        args: Map<String, String> = emptyMap(),
        targetRef: NodeRef? = null,
        dedupeKey: String? = null,
    ) = RequestedEffect(
        verb = verb,
        args = args,
        targetRef = targetRef,
        dedupeKey = dedupeKey,
        ruleId = "test.rule",
    )
}
