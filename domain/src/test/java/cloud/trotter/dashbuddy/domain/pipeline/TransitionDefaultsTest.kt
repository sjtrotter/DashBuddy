package cloud.trotter.dashbuddy.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionDefaultsTest {

    // =========================================================================
    // TransitionTrigger — fromWire round-trip
    // =========================================================================

    @Test
    fun `fromWire round-trips every trigger`() {
        for (trigger in TransitionTrigger.entries) {
            val resolved = TransitionTrigger.fromWire(trigger.wire)
            assertEquals("fromWire(${trigger.wire}) should return $trigger", trigger, resolved)
        }
    }

    @Test
    fun `fromWire returns null for unknown trigger`() {
        assertNull(TransitionTrigger.fromWire("mode:turbo"))
        assertNull(TransitionTrigger.fromWire(""))
        assertNull(TransitionTrigger.fromWire("MODE_TO_ONLINE")) // not the wire name
    }

    @Test
    fun `all trigger wire names are unique`() {
        val wires = TransitionTrigger.entries.map { it.wire }
        assertEquals("Duplicate trigger wire names", wires.size, wires.toSet().size)
    }

    // =========================================================================
    // TransitionDefaults — coverage
    // =========================================================================

    @Test
    fun `every trigger has at least one default verb`() {
        for (trigger in TransitionTrigger.entries) {
            val verbs = TransitionDefaults.defaults[trigger]
            assertNotNull("$trigger missing from defaults map", verbs)
            assertTrue("$trigger has empty default list", verbs!!.isNotEmpty())
        }
    }

    @Test
    fun `defaults map has no extra keys beyond TransitionTrigger entries`() {
        val triggerSet = TransitionTrigger.entries.toSet()
        for (key in TransitionDefaults.defaults.keys) {
            assertTrue("Unknown trigger in defaults: $key", key in triggerSet)
        }
    }

    // =========================================================================
    // TransitionDefaults — specific mappings
    // =========================================================================

    @Test
    fun `MODE_TO_ONLINE starts session and odometer`() {
        val verbs = TransitionDefaults.defaults[TransitionTrigger.MODE_TO_ONLINE]!!
        assertTrue(EffectVerb.SESSION_START in verbs)
        assertTrue(EffectVerb.ODOMETER_START in verbs)
        assertTrue(EffectVerb.LOG in verbs)
    }

    @Test
    fun `MODE_TO_OFFLINE ends session and odometer`() {
        val verbs = TransitionDefaults.defaults[TransitionTrigger.MODE_TO_OFFLINE]!!
        assertTrue(EffectVerb.SESSION_END in verbs)
        assertTrue(EffectVerb.ODOMETER_STOP in verbs)
        assertTrue(EffectVerb.LOG in verbs)
    }

    @Test
    fun `MODE_TO_PAUSED schedules timeout`() {
        val verbs = TransitionDefaults.defaults[TransitionTrigger.MODE_TO_PAUSED]!!
        assertTrue(EffectVerb.SCHEDULE_TIMEOUT in verbs)
    }

    @Test
    fun `TASK_START resumes odometer`() {
        val verbs = TransitionDefaults.defaults[TransitionTrigger.TASK_START]!!
        assertTrue(EffectVerb.ODOMETER_RESUME in verbs)
    }

    @Test
    fun `TASK_ARRIVED pauses odometer`() {
        val verbs = TransitionDefaults.defaults[TransitionTrigger.TASK_ARRIVED]!!
        assertTrue(EffectVerb.ODOMETER_PAUSE in verbs)
    }

    @Test
    fun `RESUME_FROM_PAUSE cancels timeout`() {
        val verbs = TransitionDefaults.defaults[TransitionTrigger.RESUME_FROM_PAUSE]!!
        assertTrue(EffectVerb.CANCEL_TIMEOUT in verbs)
    }

    @Test
    fun `JOB_START defaults to LOG only`() {
        val verbs = TransitionDefaults.defaults[TransitionTrigger.JOB_START]!!
        assertEquals(listOf(EffectVerb.LOG), verbs)
    }

    @Test
    fun `JOB_COMPLETED defaults to LOG only`() {
        val verbs = TransitionDefaults.defaults[TransitionTrigger.JOB_COMPLETED]!!
        assertEquals(listOf(EffectVerb.LOG), verbs)
    }

    // =========================================================================
    // Completeness guard
    // =========================================================================

    @Test
    fun `trigger count matches expected total`() {
        assertEquals("Trigger count changed — update this test", 9, TransitionTrigger.entries.size)
    }
}
