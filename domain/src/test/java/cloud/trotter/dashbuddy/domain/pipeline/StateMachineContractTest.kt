package cloud.trotter.dashbuddy.domain.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineContractTest {

    @Test
    fun `SUPPORTED_VERBS contains all EffectVerb wire names`() {
        for (verb in EffectVerb.entries) {
            assertTrue(
                "${verb.wire} missing from SUPPORTED_VERBS",
                verb.wire in StateMachineContract.SUPPORTED_VERBS,
            )
        }
    }

    @Test
    fun `SUPPORTED_TRIGGERS contains all TransitionTrigger wire names`() {
        for (trigger in TransitionTrigger.entries) {
            assertTrue(
                "${trigger.wire} missing from SUPPORTED_TRIGGERS",
                trigger.wire in StateMachineContract.SUPPORTED_TRIGGERS,
            )
        }
    }
}
