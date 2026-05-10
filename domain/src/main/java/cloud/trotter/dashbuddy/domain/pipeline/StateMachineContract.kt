package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode

/**
 * The contract the state machine advertises to the rule loader. Rules that
 * emit flow/mode values outside [SUPPORTED_FLOWS]/[SUPPORTED_MODES] are
 * rejected at load time. Rules that use effect verbs outside
 * [SUPPORTED_VERBS] or transition triggers outside [SUPPORTED_TRIGGERS]
 * are likewise rejected.
 */
object StateMachineContract {
    const val API_VERSION_MAJOR = 1
    const val API_VERSION_MINOR = 0

    val SUPPORTED_FLOWS: Set<String> = Flow.entries.map { it.wire }.toSet()
    val SUPPORTED_MODES: Set<String> = Mode.entries.map { it.wire }.toSet()
    val SUPPORTED_VERBS: Set<String> = EffectVerb.entries.map { it.wire }.toSet()
    val SUPPORTED_TRIGGERS: Set<String> = TransitionTrigger.entries.map { it.wire }.toSet()
}
