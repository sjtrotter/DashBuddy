package cloud.trotter.dashbuddy.domain.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineContractTest {

    @Test
    fun `REQUIRED_FIELDS_BY_FLOW keys are all valid Flow wire values`() {
        for (key in StateMachineContract.REQUIRED_FIELDS_BY_FLOW.keys) {
            assertTrue(
                "'$key' in REQUIRED_FIELDS_BY_FLOW is not a SUPPORTED_FLOWS wire value — " +
                    "a typo'd key silently no-ops instead of enforcing",
                key in StateMachineContract.SUPPORTED_FLOWS,
            )
        }
    }
}
