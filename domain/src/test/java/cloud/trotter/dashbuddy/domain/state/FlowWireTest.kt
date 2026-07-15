package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.pipeline.StateMachineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format round-trip + contract membership for [Flow] — pins the #762 D2 `task:active` token
 * (the phase-less coarse-in-job flow) as a first-class, load-validated vocabulary value.
 */
class FlowWireTest {

    @Test
    fun `task active round-trips through fromWire`() {
        assertEquals("task:active", Flow.TaskActive.wire)
        assertEquals(Flow.TaskActive, Flow.fromWire("task:active"))
    }

    @Test
    fun `SUPPORTED_FLOWS contains task active`() {
        assertTrue(
            "task:active must be a supported flow (auto-derived from Flow.entries)",
            "task:active" in StateMachineContract.SUPPORTED_FLOWS,
        )
    }

    @Test
    fun `every Flow wire round-trips`() {
        for (flow in Flow.entries) {
            assertEquals("round-trip for ${flow.wire}", flow, Flow.fromWire(flow.wire))
            assertTrue("${flow.wire} in SUPPORTED_FLOWS", flow.wire in StateMachineContract.SUPPORTED_FLOWS)
        }
    }
}
