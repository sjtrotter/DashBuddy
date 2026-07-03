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

    private fun notification(target: String, ruleId: String? = "doordash.notification.$target", t: Long = 1_000L) =
        Observation.Notification(
            timestamp = t,
            captureId = null,
            ruleId = ruleId,
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.Idle,
            modeHint = Mode.Online,
            parsed = ParsedFields.None,
            target = target,
        )

    // #619: parse-less notification rules (e.g. `new_order`) have a CONSTANT
    // identity (target + fieldsHash(null fields) + modeHint never change), so
    // two observably-distinct arrivals back-to-back collapsed into one at the
    // identity-dedup layer. FrameGate now mixes the notification content hash
    // into the comparison — but ONLY for notifications (V1), never screens
    // (V2), and callers can opt a specific notification out of the mixing
    // entirely (V3, e.g. an ongoing heartbeat notification whose body may
    // churn per repost).

    @Test
    fun `recognized notifications with same identity but different content BOTH admit (#619 fix)`() {
        val gate = FrameGate()
        assertTrue(gate.admit(notification("new_order"), contentHash = 111))
        assertTrue(gate.admit(notification("new_order"), contentHash = 222))
    }

    @Test
    fun `recognized notifications with same identity and same content still dedup (re-render preserved)`() {
        val gate = FrameGate()
        assertTrue(gate.admit(notification("new_order"), contentHash = 111))
        assertFalse(gate.admit(notification("new_order"), contentHash = 111))
    }

    @Test
    fun `a notification with no content hash falls back to pure identity dedup`() {
        val gate = FrameGate()
        assertTrue(gate.admit(notification("new_order"), contentHash = null))
        assertFalse(gate.admit(notification("new_order"), contentHash = null))
    }

    @Test
    fun `V2 - recognized SCREEN with same identity but different content STILL dedups (screens unaffected)`() {
        val gate = FrameGate()
        assertTrue(gate.admit(screen("main_map_idle"), contentHash = 111))
        assertFalse(gate.admit(screen("main_map_idle"), contentHash = 222))
    }

    @Test
    fun `V3 - a notification opted out of content-mixing keeps pure identity dedup, e-g an ongoing heartbeat`() {
        val gate = FrameGate()
        assertTrue(
            gate.admit(notification("dash_status_ongoing"), contentHash = 111, mixNotificationContent = false),
        )
        assertFalse(
            gate.admit(notification("dash_status_ongoing"), contentHash = 222, mixNotificationContent = false),
        )
    }

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
        val suppressor = UnknownSuppressor(capacity = 2, burstCap = 1_000)
        assertTrue(suppressor.shouldCapture(1, nowMs = 1_000L))
        assertTrue(suppressor.shouldCapture(2, nowMs = 2_000L))
        assertTrue(suppressor.shouldCapture(3, nowMs = 3_000L)) // evicts 1
        assertTrue(suppressor.shouldCapture(1, nowMs = 4_000L)) // 1 was evicted → captures again
        assertFalse(suppressor.shouldCapture(3, nowMs = 5_000L)) // still resident
    }

    @Test
    fun `re-seeing a hash refreshes its recency`() {
        val suppressor = UnknownSuppressor(capacity = 2, burstCap = 1_000)
        assertTrue(suppressor.shouldCapture(1, nowMs = 1_000L))
        assertTrue(suppressor.shouldCapture(2, nowMs = 2_000L))
        assertFalse(suppressor.shouldCapture(1, nowMs = 3_000L)) // touch 1 → 2 is now eldest
        assertTrue(suppressor.shouldCapture(3, nowMs = 4_000L))  // evicts 2, not 1
        assertFalse(suppressor.shouldCapture(1, nowMs = 5_000L))
        assertTrue(suppressor.shouldCapture(2, nowMs = 6_000L))  // 2 was evicted
    }

    @Test
    fun `burst cap stops captures but suppression of seen hashes continues`() {
        val suppressor = UnknownSuppressor(capacity = 8, burstCap = 2)
        assertTrue(suppressor.shouldCapture(1, nowMs = 1_000L))
        assertTrue(suppressor.shouldCapture(2, nowMs = 2_000L))
        assertFalse(suppressor.shouldCapture(3, nowMs = 3_000L)) // cap reached
        assertFalse(suppressor.shouldCapture(1, nowMs = 4_000L)) // seen — still suppressed
        var capped = 0
        var t = 5_000L
        for (h in 10..20) { if (!suppressor.shouldCapture(h, nowMs = t)) capped++; t += 1_000L }
        assertEquals(11, capped)
    }

    // #597: the cap is per burst-window, re-armed by a quiet gap — the a11y
    // process lives for days, so a lifetime cap meant days of triage blindness.

    @Test
    fun `cap re-arms after a quiet gap - a later dash gets a fresh budget`() {
        val gapMs = 30 * 60 * 1000L
        val suppressor = UnknownSuppressor(capacity = 8, burstCap = 2, quietGapMs = gapMs)
        assertTrue(suppressor.shouldCapture(1, nowMs = 1_000L))
        assertTrue(suppressor.shouldCapture(2, nowMs = 2_000L))
        assertFalse(suppressor.shouldCapture(3, nowMs = 3_000L)) // cap reached mid-burst
        // 31 minutes of silence — flood over; new distinct unknowns capture again.
        val later = 3_000L + gapMs + 60_000L
        assertTrue(suppressor.shouldCapture(4, nowMs = later))
        assertTrue(suppressor.shouldCapture(5, nowMs = later + 1_000L))
        assertFalse(suppressor.shouldCapture(6, nowMs = later + 2_000L)) // fresh cap holds
    }

    @Test
    fun `no re-arm during a continuous flood`() {
        val gapMs = 30 * 60 * 1000L
        val suppressor = UnknownSuppressor(capacity = 8, burstCap = 2, quietGapMs = gapMs)
        assertTrue(suppressor.shouldCapture(1, nowMs = 0L))
        assertTrue(suppressor.shouldCapture(2, nowMs = 1_000L))
        // A flood ticking every minute for hours: gap never exceeds quietGapMs.
        var t = 2_000L
        for (h in 100..400) {
            assertFalse(suppressor.shouldCapture(h, nowMs = t))
            t += 60_000L
        }
    }

    @Test
    fun `an out-of-order timestamp cannot fake a quiet gap mid-flood`() {
        // Notification postTime is not monotonic (reposts/group summaries). A
        // backwards frame must not regress the anchor and re-arm the cap
        // (adversarial F1 on #597).
        val gapMs = 30 * 60 * 1000L
        val suppressor = UnknownSuppressor(capacity = 8, burstCap = 2, quietGapMs = gapMs)
        assertTrue(suppressor.shouldCapture(1, nowMs = 1_000L))
        assertTrue(suppressor.shouldCapture(2, nowMs = 2_000L))
        assertFalse(suppressor.shouldCapture(3, nowMs = 3_000L)) // capped
        // A frame stamped 45 min in the PAST arrives out of order…
        assertFalse(suppressor.shouldCapture(4, nowMs = 3_000L - 45 * 60 * 1000L))
        // …the next in-order frame must still be capped (no fake quiet gap).
        assertFalse(suppressor.shouldCapture(5, nowMs = 4_000L))
    }

    @Test
    fun `re-arm resets the cap, not the seen-set - known hashes stay suppressed`() {
        val gapMs = 30 * 60 * 1000L
        val suppressor = UnknownSuppressor(capacity = 8, burstCap = 2, quietGapMs = gapMs)
        assertTrue(suppressor.shouldCapture(1, nowMs = 1_000L))
        assertTrue(suppressor.shouldCapture(2, nowMs = 2_000L))
        val later = 2_000L + gapMs + 60_000L
        assertFalse(suppressor.shouldCapture(1, nowMs = later)) // seen — still deduped
        assertTrue(suppressor.shouldCapture(3, nowMs = later + 1_000L)) // new hash captures
    }
}
