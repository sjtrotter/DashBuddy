package cloud.trotter.dashbuddy.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectVerbTest {

    // =========================================================================
    // fromWire — round-trip
    // =========================================================================

    @Test
    fun `fromWire round-trips every verb`() {
        for (verb in EffectVerb.entries) {
            val resolved = EffectVerb.fromWire(verb.wire)
            assertEquals("fromWire(${verb.wire}) should return $verb", verb, resolved)
        }
    }

    @Test
    fun `fromWire returns null for unknown verb`() {
        assertNull(EffectVerb.fromWire("explode"))
        assertNull(EffectVerb.fromWire(""))
        // Actuation left the rule vocabulary entirely (#425) — the compiler
        // rejects "click" with a migration error before fromWire is consulted,
        // and the verb must never silently round-trip.
        assertNull(EffectVerb.fromWire("click"))
    }

    // =========================================================================
    // Wire name uniqueness
    // =========================================================================

    @Test
    fun `all wire names are unique`() {
        val wires = EffectVerb.entries.map { it.wire }
        assertEquals("Duplicate wire names detected", wires.size, wires.toSet().size)
    }

    // =========================================================================
    // Verb properties
    // =========================================================================

    @Test
    fun `observation-driven verbs have hasDefault = false`() {
        val observationDriven = listOf(
            EffectVerb.SCREENSHOT,
            EffectVerb.BUBBLE,
            EffectVerb.LOG,
            EffectVerb.EVALUATE_OFFER,
            EffectVerb.SPEAK,
        )
        for (verb in observationDriven) {
            assertEquals("$verb should not have a default", false, verb.hasDefault)
        }
    }

    @Test
    fun `lifecycle verbs have hasDefault = true`() {
        val lifecycle = listOf(
            EffectVerb.SESSION_START,
            EffectVerb.SESSION_END,
            EffectVerb.ODOMETER_START,
            EffectVerb.ODOMETER_STOP,
            EffectVerb.ODOMETER_PAUSE,
            EffectVerb.ODOMETER_RESUME,
            EffectVerb.SCHEDULE_TIMEOUT,
            EffectVerb.CANCEL_TIMEOUT,
        )
        for (verb in lifecycle) {
            assertEquals("$verb should have a default", true, verb.hasDefault)
        }
    }

    // =========================================================================
    // Permission tiers
    // =========================================================================

    @Test
    fun `accessibility verbs require ACCESSIBILITY tier`() {
        assertEquals(PermissionTier.ACCESSIBILITY, EffectVerb.SCREENSHOT.tier)
    }

    @Test
    fun `odometer verbs require LOCATION tier`() {
        val odometerVerbs = listOf(
            EffectVerb.ODOMETER_START,
            EffectVerb.ODOMETER_STOP,
            EffectVerb.ODOMETER_PAUSE,
            EffectVerb.ODOMETER_RESUME,
        )
        for (verb in odometerVerbs) {
            assertEquals("$verb should require LOCATION", PermissionTier.LOCATION, verb.tier)
        }
    }

    @Test
    fun `SPEAK requires AUDIO tier`() {
        assertEquals(PermissionTier.AUDIO, EffectVerb.SPEAK.tier)
    }

    @Test
    fun `remaining verbs require NONE tier`() {
        val noneVerbs = EffectVerb.entries.filter {
            it.tier == PermissionTier.NONE
        }
        // BUBBLE, LOG, EVALUATE_OFFER, SESSION_START, SESSION_END, SCHEDULE_TIMEOUT, CANCEL_TIMEOUT
        assertTrue("Expected multiple NONE-tier verbs", noneVerbs.size >= 7)
    }

    // =========================================================================
    // Completeness guard
    // =========================================================================

    @Test
    fun `verb count matches expected total`() {
        // Update this if verbs are added or removed — forces a conscious review
        // (13 = 14 minus CLICK, which left the rule vocabulary in #425).
        assertEquals("Verb count changed — update this test", 13, EffectVerb.entries.size)
    }
}
