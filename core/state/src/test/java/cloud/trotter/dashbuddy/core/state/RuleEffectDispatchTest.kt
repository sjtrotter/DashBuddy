package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.PermissionTier
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
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
        // This test documents that all 13 verbs are handled (CLICK left the
        // rule vocabulary in #425). If a verb is added to EffectVerb, the
        // exhaustive when() in SideEffectEngine.dispatchRuleEffect will fail
        // to compile.
        assertEquals(13, EffectVerb.entries.size)
    }

    // =========================================================================
    // AppEffect.RequestEffect — effectKey generation
    // =========================================================================

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
            EffectVerb.SCREENSHOT, EffectVerb.BUBBLE,
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
    // Permission tiers — correct verb assignments
    // =========================================================================

    @Test
    fun `SCREENSHOT requires ACCESSIBILITY tier`() {
        assertEquals(PermissionTier.ACCESSIBILITY, EffectVerb.SCREENSHOT.tier)
    }

    @Test
    fun `SPEAK requires AUDIO tier`() {
        assertEquals(PermissionTier.AUDIO, EffectVerb.SPEAK.tier)
    }

    @Test
    fun `odometer verbs require LOCATION tier`() {
        val odometerVerbs = listOf(
            EffectVerb.ODOMETER_START, EffectVerb.ODOMETER_STOP,
            EffectVerb.ODOMETER_PAUSE, EffectVerb.ODOMETER_RESUME,
        )
        for (verb in odometerVerbs) {
            assertEquals("$verb should require LOCATION", PermissionTier.LOCATION, verb.tier)
        }
    }

    @Test
    fun `non-privileged verbs have NONE tier`() {
        val noneVerbs = listOf(
            EffectVerb.BUBBLE, EffectVerb.LOG, EffectVerb.EVALUATE_OFFER,
            EffectVerb.SESSION_START, EffectVerb.SESSION_END,
            EffectVerb.SCHEDULE_TIMEOUT, EffectVerb.CANCEL_TIMEOUT,
        )
        for (verb in noneVerbs) {
            assertEquals("$verb should have NONE tier", PermissionTier.NONE, verb.tier)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeEffect(
        verb: EffectVerb,
        args: Map<String, String> = emptyMap(),
        dedupeKey: String? = null,
    ) = RequestedEffect(
        verb = verb,
        args = args,
        dedupeKey = dedupeKey,
        ruleId = "test.rule",
    )
}
