package cloud.trotter.dashbuddy.domain.state

/**
 * Which surface of an OFFER presentation a screen rule renders (#1104/#1114) — rule-declared under
 * `state.offerSurface`, load-validated through [cloud.trotter.dashbuddy.domain.pipeline.StateMachineContract],
 * never a Kotlin literal on a platform screen name. A `decline_confirm` frame observed over a presented
 * offer marks `PendingOffer.declineSheetSeenAt`, the transition evidence the outcome resolver uses when
 * no click envelope can arrive (a Compose control emits no `TYPE_VIEW_CLICKED` for a human tap).
 */
enum class OfferSurface(val wire: String) {
    /** The offer card itself (the default when a rule declares nothing). */
    CARD("card"),

    /** The "are you sure you want to decline" sheet the platform raises over the card. */
    DECLINE_CONFIRM("decline_confirm"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(wire: String): OfferSurface? = byWire[wire]
    }
}
