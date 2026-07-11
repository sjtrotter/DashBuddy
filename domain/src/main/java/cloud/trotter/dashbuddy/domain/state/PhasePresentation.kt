package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot

/**
 * Semantic brand color token for a flow-phase label (#audit-12).
 *
 * A plain Kotlin enum — NOT a Compose `Color` — so the presentation SSOT can
 * live in pure-Kotlin `:domain` while the UI layer resolves each token to the
 * matching `AppColors` family (`:core:designsystem` has no project deps, so the
 * concrete colors cannot be referenced here). Each value names a brand family;
 * the `*Bg` background variant of that family is derived by the resolver.
 */
enum class PhaseColor {
    /** good — the positive/on-track family. */
    GOOD,

    /** neutral — the muted/idle family. */
    NEUTRAL,

    /** stOffer — the offer (blue) family. */
    OFFER,

    /** stPickup — the pickup/dropoff (purple) family. */
    PICKUP,
}

/**
 * The single source of truth for how a canonical [Flow] phase is labelled and
 * colored in the bubble HUD (#audit-12). Previously two hand-maintained tables
 * — `statusBadge()` (the TopAppBar status badge) and `PhaseChip()` (the card-
 * stack chip) — drifted behind only a code comment ("the chip now agrees with
 * statusBadge"). Both now derive from this one entry per phase.
 *
 * The long status badge and the short card chip legitimately differ — both in
 * wording (e.g. "AT STORE"/"AT DOOR" vs "PICKUP"/"DROPOFF", "DELIVERED" vs
 * "PAID") and in color (e.g. pickup is `good` on the badge but `stPickup` on
 * the chip) — so both forms are encoded here as distinct fields; no distinction
 * is lost.
 *
 * @param longLabel  the status-badge wording (TopAppBar).
 * @param longColor  the status-badge color token.
 * @param shortLabel the card-chip wording (card stack header).
 * @param shortColor the card-chip color token (its `*Bg` is derived in the UI).
 */
data class PhasePresentation(
    val longLabel: String,
    val longColor: PhaseColor,
    val shortLabel: String,
    val shortColor: PhaseColor,
)

/**
 * The phase → presentation table. Keyed on the canonical [Flow] phase so both
 * the badge (keyed directly on [Flow]) and the chip (keyed on a card snapshot
 * that resolves to a [Flow] via [FlowCardSnapshot.flowPhase]) read the same row.
 *
 * Values preserve the exact labels/colors that the two former tables produced.
 */
val Flow.presentation: PhasePresentation
    get() = when (this) {
        Flow.Idle -> PhasePresentation(
            longLabel = "WAITING", longColor = PhaseColor.GOOD,
            shortLabel = "AWAIT", shortColor = PhaseColor.NEUTRAL,
        )
        Flow.OfferPresented -> PhasePresentation(
            longLabel = "OFFER", longColor = PhaseColor.OFFER,
            shortLabel = "OFFER", shortColor = PhaseColor.OFFER,
        )
        Flow.TaskPickupNavigation -> PhasePresentation(
            longLabel = "PICKUP", longColor = PhaseColor.GOOD,
            shortLabel = "PICKUP", shortColor = PhaseColor.PICKUP,
        )
        Flow.TaskPickupArrived -> PhasePresentation(
            longLabel = "AT STORE", longColor = PhaseColor.GOOD,
            shortLabel = "PICKUP", shortColor = PhaseColor.PICKUP,
        )
        Flow.TaskDropoffNavigation -> PhasePresentation(
            longLabel = "DELIVERING", longColor = PhaseColor.GOOD,
            shortLabel = "DROPOFF", shortColor = PhaseColor.PICKUP,
        )
        Flow.TaskDropoffArrived -> PhasePresentation(
            longLabel = "AT DOOR", longColor = PhaseColor.GOOD,
            shortLabel = "DROPOFF", shortColor = PhaseColor.PICKUP,
        )
        Flow.PostTask -> PhasePresentation(
            longLabel = "DELIVERED", longColor = PhaseColor.GOOD,
            shortLabel = "PAID", shortColor = PhaseColor.GOOD,
        )
        // #736: a transient teardown after the dasher unassigned the order — presented as the
        // Idle-equivalent (neutral "waiting/await"); the machine closes the job on this frame and
        // the next real frame is Idle proper.
        Flow.TaskUnassigned -> PhasePresentation(
            longLabel = "WAITING", longColor = PhaseColor.GOOD,
            shortLabel = "AWAIT", shortColor = PhaseColor.NEUTRAL,
        )
        Flow.SessionEnded -> PhasePresentation(
            longLabel = "DONE", longColor = PhaseColor.NEUTRAL,
            shortLabel = "DONE", shortColor = PhaseColor.NEUTRAL,
        )
    }

/**
 * The canonical [Flow] phase a card snapshot represents — the chip's key into
 * [presentation]. Pickup/Delivery cards collapse the nav/arrived sub-states to
 * the navigation phase because the chip wording is identical across both (only
 * the status badge distinguishes "PICKUP"/"AT STORE", "DELIVERING"/"AT DOOR").
 */
fun FlowCardSnapshot.flowPhase(): Flow = when (this) {
    is FlowCardSnapshot.Awaiting -> Flow.Idle
    is FlowCardSnapshot.Offer -> Flow.OfferPresented
    is FlowCardSnapshot.Pickup -> Flow.TaskPickupNavigation
    is FlowCardSnapshot.Delivery -> Flow.TaskDropoffNavigation
    is FlowCardSnapshot.PostTask -> Flow.PostTask
}
