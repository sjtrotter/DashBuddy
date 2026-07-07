package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
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
    // gracePeriodMs
    // =========================================================================

    @Test
    fun `grace period defaults to 10 seconds`() {
        assertEquals(10_000L, policy.gracePeriodMs(Platform.DoorDash))
    }
}
