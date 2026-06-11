package cloud.trotter.dashbuddy.domain.state

/**
 * Platform-agnostic representation of what the worker is currently doing
 * in the gig flow. Each value maps to a family of [ParsedFields] subtypes.
 *
 * Wire values are the canonical strings used in JSON rulesets and the
 * observation log. The enum is the Kotlin-side source of truth; any rule
 * emitting a wire value not in this set is rejected at load time.
 *
 * NAMING (#366, decided): this enum collides with `kotlinx.coroutines.flow.Flow`
 * and stays that way. The handful of files needing both use a fully-qualified
 * coroutines import; renaming (~21 consumers + the wire-format mental model)
 * buys nothing behavioral. Revisit only if the collision starts causing real
 * bugs rather than import friction.
 *
 * @see Mode for the orthogonal availability axis.
 */
enum class Flow(val wire: String) {
    Idle("idle"),
    OfferPresented("offer:presented"),
    TaskPickupNavigation("task:pickup:navigation"),
    TaskPickupArrived("task:pickup:arrived"),
    TaskDropoffNavigation("task:dropoff:navigation"),
    TaskDropoffArrived("task:dropoff:arrived"),
    PostTask("post:task"),
    SessionEnded("session:ended"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** Resolve a wire-format string to a [Flow], or null if unknown. */
        fun fromWire(wire: String): Flow? = byWire[wire]
    }
}
