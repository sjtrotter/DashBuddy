package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #360 acceptance: identical UNKNOWN frames capture once; distinct unknowns
 * each capture; the recognized-screen-after-UNKNOWN re-forward behavior
 * (lastIdentity is never reset by UNKNOWN) is preserved; the rolling set and
 * the process cap bound capture volume.
 */
class FrameGateTest {

    private fun screen(target: String, ruleId: String? = "doordash.screen.$target", t: Long = 1_000L) =
        Observation.Screen(
            timestamp = t,
            captureId = null,
            ruleId = ruleId,
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.Idle,
            modeHint = Mode.Online,
            parsed = ParsedFields.None,
            target = target,
        )

    private fun unknown(t: Long = 1_000L) =
        screen(target = "UNKNOWN", ruleId = null, t = t)

    @Test
    fun `identical UNKNOWN frames capture exactly once`() {
        val gate = FrameGate()
        assertTrue(gate.admit(unknown(), contentHash = 111))
        assertFalse(gate.admit(unknown(), contentHash = 111))
        assertFalse(gate.admit(unknown(), contentHash = 111))
    }

    @Test
    fun `alternating distinct unknowns each capture once, then suppress`() {
        val gate = FrameGate()
        assertTrue(gate.admit(unknown(), contentHash = 1))
        assertTrue(gate.admit(unknown(), contentHash = 2))
        // The animation loop: A-B-A-B never re-captures.
        assertFalse(gate.admit(unknown(), contentHash = 1))
        assertFalse(gate.admit(unknown(), contentHash = 2))
    }

    @Test
    fun `a genuinely new unknown still captures after suppression`() {
        val gate = FrameGate()
        assertTrue(gate.admit(unknown(), contentHash = 1))
        assertFalse(gate.admit(unknown(), contentHash = 1))
        assertTrue(gate.admit(unknown(), contentHash = 99))
    }

    @Test
    fun `lastIdentity semantics survive an UNKNOWN interlude`() {
        val gate = FrameGate()
        assertTrue(gate.admit(screen("main_map_idle"), contentHash = null))
        // Same recognized screen again — identity dedup suppresses.
        assertFalse(gate.admit(screen("main_map_idle"), contentHash = null))
        // UNKNOWN interlude (captured) must NOT reset identity state ...
        assertTrue(gate.admit(unknown(), contentHash = 7))
        // ... so the UNCHANGED known screen stays suppressed (the original
        // design: an UNKNOWN between two identical sightings is not a change) ...
        assertFalse(gate.admit(screen("main_map_idle"), contentHash = null))
        // ... while a recognized screen with a NEW identity still forwards.
        assertTrue(gate.admit(screen("offer_popup"), contentHash = null))
    }

    @Test
    fun `UNKNOWN with no content hash is admitted - never silently lost`() {
        val gate = FrameGate()
        assertTrue(gate.admit(unknown(), contentHash = null))
        assertTrue(gate.admit(unknown(), contentHash = null))
    }

    @Test
    fun `rolling capacity evicts oldest - an evicted hash can capture again`() {
        val suppressor = UnknownSuppressor(capacity = 2, processCap = 1_000)
        assertTrue(suppressor.shouldCapture(1))
        assertTrue(suppressor.shouldCapture(2))
        assertTrue(suppressor.shouldCapture(3)) // evicts 1
        assertTrue(suppressor.shouldCapture(1)) // 1 was evicted → captures again
        assertFalse(suppressor.shouldCapture(3)) // still resident
    }

    @Test
    fun `re-seeing a hash refreshes its recency`() {
        val suppressor = UnknownSuppressor(capacity = 2, processCap = 1_000)
        assertTrue(suppressor.shouldCapture(1))
        assertTrue(suppressor.shouldCapture(2))
        assertFalse(suppressor.shouldCapture(1)) // touch 1 → 2 is now eldest
        assertTrue(suppressor.shouldCapture(3))  // evicts 2, not 1
        assertFalse(suppressor.shouldCapture(1))
        assertTrue(suppressor.shouldCapture(2))  // 2 was evicted
    }

    @Test
    fun `process cap stops captures but suppression of seen hashes continues`() {
        val suppressor = UnknownSuppressor(capacity = 8, processCap = 2)
        assertTrue(suppressor.shouldCapture(1))
        assertTrue(suppressor.shouldCapture(2))
        assertFalse(suppressor.shouldCapture(3)) // cap reached
        assertFalse(suppressor.shouldCapture(1)) // seen — still suppressed
        var capped = 0
        for (h in 10..20) if (!suppressor.shouldCapture(h)) capped++
        assertEquals(11, capped)
    }
}
