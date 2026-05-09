package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ModeConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealingPolicyTest {

    private val policy = HealingPolicy()

    // =========================================================================
    // inferAndCheck — mode inference
    // =========================================================================

    @Test
    fun `inferAndCheck — same mode returns NoChange`() {
        val result = policy.inferAndCheck(Mode.Online, Flow.OfferPresented, null)
        assertEquals(Verdict.NoChange, result.verdict)
        assertEquals(Mode.Online, result.impliedMode)
    }

    @Test
    fun `inferAndCheck — null flow and null modeHint returns NoChange`() {
        val result = policy.inferAndCheck(Mode.Online, null, null)
        assertEquals(Verdict.NoChange, result.verdict)
        assertNull(result.impliedMode)
    }

    @Test
    fun `inferAndCheck — Idle flow with no hint returns NoChange`() {
        val result = policy.inferAndCheck(Mode.Online, Flow.Idle, null)
        assertEquals(Verdict.NoChange, result.verdict)
        assertNull(result.impliedMode)
    }

    @Test
    fun `inferAndCheck — modeHint overrides flow inference`() {
        // Flow says Online (OfferPresented), but hint says Paused
        val result = policy.inferAndCheck(Mode.Online, Flow.OfferPresented, Mode.Paused)
        assertEquals(Verdict.PlausibleApply, result.verdict)
        assertEquals(Mode.Paused, result.impliedMode)
    }

    // =========================================================================
    // inferAndCheck — plausible transitions
    // =========================================================================

    @Test
    fun `inferAndCheck — Online to Paused is plausible`() {
        val result = policy.inferAndCheck(Mode.Online, null, Mode.Paused)
        assertEquals(Verdict.PlausibleApply, result.verdict)
    }

    @Test
    fun `inferAndCheck — Online to Offline via SessionEnded is plausible`() {
        val result = policy.inferAndCheck(Mode.Online, Flow.SessionEnded, null)
        assertEquals(Verdict.PlausibleApply, result.verdict)
        assertEquals(Mode.Offline, result.impliedMode)
    }

    @Test
    fun `inferAndCheck — Paused to Online is plausible`() {
        val result = policy.inferAndCheck(Mode.Paused, Flow.OfferPresented, null)
        assertEquals(Verdict.PlausibleApply, result.verdict)
        assertEquals(Mode.Online, result.impliedMode)
    }

    @Test
    fun `inferAndCheck — Paused to Offline is plausible`() {
        val result = policy.inferAndCheck(Mode.Paused, Flow.SessionEnded, null)
        assertEquals(Verdict.PlausibleApply, result.verdict)
        assertEquals(Mode.Offline, result.impliedMode)
    }

    // =========================================================================
    // inferAndCheck — implausible transitions
    // =========================================================================

    @Test
    fun `inferAndCheck — Offline to Online is implausible`() {
        val result = policy.inferAndCheck(Mode.Offline, Flow.OfferPresented, null)
        assertEquals(Verdict.Implausible, result.verdict)
        assertEquals(Mode.Online, result.impliedMode)
    }

    @Test
    fun `inferAndCheck — Offline to Paused is implausible`() {
        val result = policy.inferAndCheck(Mode.Offline, null, Mode.Paused)
        assertEquals(Verdict.Implausible, result.verdict)
        assertEquals(Mode.Paused, result.impliedMode)
    }

    @Test
    fun `inferAndCheck — Online to Offline without SessionEnded is implausible`() {
        // modeHint says Offline, but flow is not SessionEnded
        val result = policy.inferAndCheck(Mode.Online, Flow.Idle, Mode.Offline)
        assertEquals(Verdict.Implausible, result.verdict)
        assertEquals(Mode.Offline, result.impliedMode)
    }

    // =========================================================================
    // inferAndCheck — flow-to-mode inference
    // =========================================================================

    @Test
    fun `inferAndCheck — task flows imply Online`() {
        val taskFlows = listOf(
            Flow.TaskPickupNavigation,
            Flow.TaskPickupArrived,
            Flow.TaskDropoffNavigation,
            Flow.TaskDropoffArrived,
            Flow.PostTask,
        )
        for (flow in taskFlows) {
            val result = policy.inferAndCheck(Mode.Offline, flow, null)
            assertEquals("$flow should imply Online", Mode.Online, result.impliedMode)
        }
    }

    @Test
    fun `inferAndCheck — SessionEnded implies Offline`() {
        val result = policy.inferAndCheck(Mode.Online, Flow.SessionEnded, null)
        assertEquals(Mode.Offline, result.impliedMode)
    }

    // =========================================================================
    // shouldHeal — threshold behavior
    // =========================================================================

    @Test
    fun `shouldHeal — returns false with no firstSeen`() {
        val confidence = ModeConfidence(
            pendingMode = Mode.Online,
            supportingObservations = 5,
            firstSeenAt = null,
        )
        assertFalse(policy.shouldHeal(confidence, now = 1000L))
    }

    @Test
    fun `shouldHeal — returns false with only 1 observation`() {
        val now = 5000L
        val confidence = ModeConfidence(
            pendingMode = Mode.Online,
            supportingObservations = 1,
            firstSeenAt = now,
        )
        assertFalse(policy.shouldHeal(confidence, now))
    }

    @Test
    fun `shouldHeal — returns true with 2 observations within window`() {
        val firstSeen = 1000L
        val now = firstSeen + 5000L // 5s later, within 10s window
        val confidence = ModeConfidence(
            pendingMode = Mode.Online,
            supportingObservations = 2,
            firstSeenAt = firstSeen,
        )
        assertTrue(policy.shouldHeal(confidence, now))
    }

    @Test
    fun `shouldHeal — returns true at exactly threshold observations`() {
        val firstSeen = 1000L
        val now = firstSeen + 9999L // just inside 10s window
        val confidence = ModeConfidence(
            pendingMode = Mode.Online,
            supportingObservations = ModeConfidence.DEFAULT_OBSERVATION_THRESHOLD,
            firstSeenAt = firstSeen,
        )
        assertTrue(policy.shouldHeal(confidence, now))
    }

    @Test
    fun `shouldHeal — returns false when stale (outside time window)`() {
        val firstSeen = 1000L
        val now = firstSeen + 11_000L // 11s later, outside 10s window
        val confidence = ModeConfidence(
            pendingMode = Mode.Online,
            supportingObservations = 5, // plenty of observations but stale
            firstSeenAt = firstSeen,
        )
        assertFalse(policy.shouldHeal(confidence, now))
    }

    @Test
    fun `shouldHeal — returns false at exactly window boundary`() {
        val firstSeen = 1000L
        val now = firstSeen + ModeConfidence.DEFAULT_TIME_WINDOW_MS + 1 // just past boundary
        val confidence = ModeConfidence(
            pendingMode = Mode.Online,
            supportingObservations = 10,
            firstSeenAt = firstSeen,
        )
        assertFalse(policy.shouldHeal(confidence, now))
    }

    @Test
    fun `shouldHeal — returns true at exactly window boundary`() {
        val firstSeen = 1000L
        val now = firstSeen + ModeConfidence.DEFAULT_TIME_WINDOW_MS // exactly at boundary
        val confidence = ModeConfidence(
            pendingMode = Mode.Online,
            supportingObservations = 2,
            firstSeenAt = firstSeen,
        )
        assertTrue(policy.shouldHeal(confidence, now))
    }
}
