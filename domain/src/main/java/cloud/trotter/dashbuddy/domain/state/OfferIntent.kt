package cloud.trotter.dashbuddy.domain.state

/**
 * Wire identifiers for the offer **accept / decline** intents. One source of truth shared by:
 * the ruleset click classification (`ClickFields.intent`), the HUD + notification action
 * dispatch ([cloud.trotter.dashbuddy.domain.pipeline.Observation.UiInput]), and the EffectMap
 * routing. Keep them here so a UI-dispatched intent and a rule-parsed intent never drift.
 */
object OfferIntent {
    const val ACCEPT = "accept_offer"
    const val DECLINE = "decline_offer"
}
