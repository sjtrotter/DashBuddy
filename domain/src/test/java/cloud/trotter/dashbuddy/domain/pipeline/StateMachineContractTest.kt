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

    /**
     * A typo'd flow key in [StateMachineContract.REQUIRED_FIELDS_BY_FLOW] would silently no-op
     * (the compiler looks keys up by the rule's resolved flow, so an unknown key never matches
     * anything) — exactly the fail-open drift class the contract exists to prevent (#762 D6,
     * adversarial-review finding). Every key must be a real Flow wire value.
     */
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
