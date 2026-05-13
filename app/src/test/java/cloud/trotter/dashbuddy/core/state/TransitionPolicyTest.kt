package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitionPolicyTest {

    private val policy = TransitionPolicy()

    // =========================================================================
    // resolveMode — mode inference from flow + modeHint
    // =========================================================================

    @Test
    fun `resolveMode — modeHint overrides flow`() {
        // Flow implies Online, but hint says Paused
        assertEquals(Mode.Paused, policy.resolveMode(Flow.OfferPresented, Mode.Paused))
    }

    @Test
    fun `resolveMode — null hint falls back to flow inference`() {
        assertEquals(Mode.Online, policy.resolveMode(Flow.OfferPresented, null))
    }

    @Test
    fun `resolveMode — null flow and null hint returns null`() {
        assertNull(policy.resolveMode(null, null))
    }

    @Test
    fun `resolveMode — Idle returns null (ambiguous)`() {
        assertNull(policy.resolveMode(Flow.Idle, null))
    }

    @Test
    fun `resolveMode — SessionEnded implies Offline`() {
        assertEquals(Mode.Offline, policy.resolveMode(Flow.SessionEnded, null))
    }

    @Test
    fun `resolveMode — task flows imply Online`() {
        val taskFlows = listOf(
            Flow.OfferPresented,
            Flow.TaskPickupNavigation,
            Flow.TaskPickupArrived,
            Flow.TaskDropoffNavigation,
            Flow.TaskDropoffArrived,
            Flow.PostTask,
        )
        for (flow in taskFlows) {
            assertEquals("$flow should imply Online", Mode.Online, policy.resolveMode(flow, null))
        }
    }

    // =========================================================================
    // classify — transition kind determination
    // =========================================================================

    private fun screenObs(
        flow: Flow? = null,
        modeHint: Mode? = null,
        expectedOutcomes: Set<Flow>? = null,
    ) = Observation.Screen(
        timestamp = 1000L,
        captureId = null,
        ruleId = "test.screen.test",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = ParsedFields.None,
        expectedOutcomes = expectedOutcomes,
    )

    @Test
    fun `classify — null impliedMode returns NoSignal`() {
        val kind = policy.classify(
            Mode.Online, null, null, screenObs(flow = Flow.Idle),
        )
        assertEquals(TransitionKind.NoSignal, kind)
    }

    @Test
    fun `classify — same mode returns Confirmed`() {
        val kind = policy.classify(
            Mode.Online, Mode.Online, null, screenObs(flow = Flow.OfferPresented),
        )
        assertEquals(TransitionKind.Confirmed, kind)
    }

    @Test
    fun `classify — mode change with no outcomes defaults to Expected`() {
        val kind = policy.classify(
            Mode.Online, Mode.Offline, null, screenObs(flow = Flow.SessionEnded),
        )
        assertEquals(TransitionKind.Expected, kind)
    }

    @Test
    fun `classify — mode change with flow in outcomes is Expected`() {
        val outcomes = setOf(Flow.SessionEnded, Flow.Idle)
        val kind = policy.classify(
            Mode.Online, Mode.Offline, outcomes, screenObs(flow = Flow.SessionEnded),
        )
        assertEquals(TransitionKind.Expected, kind)
    }

    @Test
    fun `classify — mode change with flow NOT in outcomes is Unexpected`() {
        val outcomes = setOf(Flow.OfferPresented, Flow.Idle)
        val kind = policy.classify(
            Mode.Online, Mode.Offline, outcomes,
            screenObs(flow = Flow.SessionEnded),
        )
        assertEquals(TransitionKind.Unexpected, kind)
    }

    @Test
    fun `classify — mode change with null flow defaults to Expected`() {
        val outcomes = setOf(Flow.Idle)
        val kind = policy.classify(
            Mode.Offline, Mode.Online, outcomes, screenObs(flow = null),
        )
        assertEquals(TransitionKind.Expected, kind)
    }

    // =========================================================================
    // gracePeriodMs
    // =========================================================================

    @Test
    fun `grace period defaults to 10 seconds`() {
        assertEquals(10_000L, policy.gracePeriodMs)
    }
}
