package cloud.trotter.dashbuddy.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #937 — the pure windowed recognition-health decision.
 *
 * The properties that matter in the field: a partial window never fires, a healthy dash never
 * fires (the 2026-07-28/29 pulls sit near 16 % UNKNOWN on the admitted stream), a collapse
 * fires exactly once, platforms are independent, and the window forgets — a platform that was
 * healthy an hour ago must still be able to alarm now.
 */
class RecognitionHealthTest {

    private val size = RecognitionHealth.WINDOW_SIZE

    private fun RecognitionHealth.feed(wire: String, unknown: Boolean, times: Int): RecognitionHealth.Alarm? {
        var alarm: RecognitionHealth.Alarm? = null
        repeat(times) { alarm = record(wire, unknown) ?: alarm }
        return alarm
    }

    @Test
    fun `a partial window never alarms, however bad it looks`() {
        val health = RecognitionHealth()

        assertNull(health.feed("doordash", unknown = true, times = size - 1))
        assertFalse(health.hasAlarmed("doordash"))
    }

    @Test
    fun `a full window of UNKNOWN alarms on the frame that fills it`() {
        val health = RecognitionHealth()

        health.feed("doordash", unknown = true, times = size - 1)
        val alarm = health.record("doordash", unknown = true)

        assertNotNull("the window-filling frame must trip", alarm)
        assertEquals("doordash", alarm!!.platformWire)
        assertEquals(100, alarm.unknownPercent)
    }

    @Test
    fun `a healthy dash never alarms`() {
        val health = RecognitionHealth()

        // ~16 % UNKNOWN — the measured field baseline — over four full windows.
        repeat(size * 4) { i ->
            assertNull(health.record("doordash", unknown = i % 6 == 0))
        }
    }

    @Test
    fun `a ratio just under the threshold does not alarm`() {
        val health = RecognitionHealth(windowSize = 10, threshold = 0.80)

        repeat(7) { assertNull(health.record("doordash", unknown = true)) }
        repeat(3) { assertNull(health.record("doordash", unknown = false)) }
    }

    @Test
    fun `a ratio at the threshold alarms`() {
        val health = RecognitionHealth(windowSize = 10, threshold = 0.80)

        repeat(8) { health.record("doordash", unknown = true) }
        val alarm = health.record("doordash", unknown = false) ?: health.record("doordash", unknown = false)

        assertEquals(80, alarm!!.unknownPercent)
    }

    @Test
    fun `a platform alarms at most once - recovery deliberately does not re-arm`() {
        val health = RecognitionHealth()

        assertNotNull(health.feed("doordash", unknown = true, times = size))
        // Recover completely, then break again.
        assertNull(health.feed("doordash", unknown = false, times = size))
        assertNull(health.feed("doordash", unknown = true, times = size * 2))
    }

    @Test
    fun `platforms are independent - one breaking never implicates the other`() {
        val health = RecognitionHealth()

        // Interleaved, as two concurrent platforms would actually arrive.
        repeat(size) {
            health.record("doordash", unknown = true)
            health.record("uber", unknown = false)
        }

        assertTrue(health.hasAlarmed("doordash"))
        assertFalse("a healthy uber must not inherit doordash's alarm", health.hasAlarmed("uber"))
    }

    @Test
    fun `the window forgets - a long healthy stretch does not immunise a later collapse`() {
        val health = RecognitionHealth()

        repeat(size * 10) { health.record("doordash", unknown = false) }
        assertFalse(health.hasAlarmed("doordash"))

        // A lifetime ratio would still read ~9 % here; the rolling window reads 100 %.
        assertNotNull(health.feed("doordash", unknown = true, times = size))
    }
}
