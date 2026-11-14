package cloud.trotter.log.gig.domain.pipeline

/**
 * Version constants for the rule engine (ADR-0003 Layer 3).
 */
object RuleEngineConstants {
    const val VERSION = 1
    const val MAX_SUPPORTED_FORMAT_VERSION = 2

    /**
     * The rules feed's `contract` number (#1568, ADR-0013): the version of the vocabulary the rule
     * compiler accepts. A rules manifest whose contract this app does not support is refused and
     * the last good rules stay. `RulesContractPinTest` (`:core:pipeline`) fails when the
     * vocabulary changes, so a change bumps this number or re-pins it on purpose.
     */
    const val RULES_CONTRACT = 1
}
