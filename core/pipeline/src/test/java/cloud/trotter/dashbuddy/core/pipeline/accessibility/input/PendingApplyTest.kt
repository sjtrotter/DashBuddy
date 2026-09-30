package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1151 review MM9 — an apply that found no serviceInfo is loud once and retried, never dropped. */
class PendingApplyTest {

    private var available = true
    private val applied = mutableListOf<String>()
    private var deferredLogs = 0
    private val applier = PendingApply<String>(
        apply = { v -> if (available) { applied += v; true } else false },
        onDeferred = { deferredLogs++ },
    )

    @Test
    fun `an available serviceInfo applies immediately and parks nothing`() {
        applier.onConsent("ALLOWED")
        assertEquals(listOf("ALLOWED"), applied)
        assertFalse(applier.hasPending)
        assertEquals(0, deferredLogs)
    }

    @Test
    fun `a null serviceInfo parks the value, logs once, and the next retry applies it`() {
        available = false
        applier.onConsent("ALLOWED")
        assertTrue(applier.hasPending)
        assertEquals(1, deferredLogs)

        applier.retryPending() // still unavailable: silent, still parked
        assertTrue(applier.hasPending)
        assertEquals(1, deferredLogs)

        available = true
        applier.retryPending()
        assertEquals(listOf("ALLOWED"), applied)
        assertFalse(applier.hasPending)

        applier.retryPending() // nothing pending: no-op
        assertEquals(listOf("ALLOWED"), applied)
    }

    @Test
    fun `a newer value replaces a parked one`() {
        available = false
        applier.onConsent("ALLOWED")
        applier.onConsent("DECLINED")
        available = true
        applier.retryPending()
        assertEquals(listOf("DECLINED"), applied)
    }
}
