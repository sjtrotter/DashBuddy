package cloud.trotter.dashbuddy.domain.state

/**
 * Wire identifiers for task intents. One source of truth shared by the ruleset click
 * classification (`ClickFields.intent`) and the stepper, so parsed intent and state never drift.
 * load validation of click intents is #1322.
 */
object TaskIntent {
    const val COMPLETE_DELIVERY = "complete_delivery"
}
