package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.PendingOffer

/**
 * #526 D1b — the ONE shared "this pending offer was accepted" predicate. Used by both the accept
 * stash arming ([PlatformRegionStepper]) and offer-outcome resolution ([EffectMap.resolveOfferOutcome]
 * steps 0-1); keeping it in one place stops the #594 decline-latch logic from drifting between the
 * two `:core:state` files.
 *
 * True ⇔ an ACCEPT intent is latched on the offer AND no committed decline has overridden it. The
 * decline-commit latch (#594) is final: once a decline is committed server-side, a later
 * "Review offer" → Accept race cannot un-decline it, so a committed decline makes this false even
 * with a trailing ACCEPT click.
 */
internal fun PendingOffer.isAcceptLatched(): Boolean =
    declineCommittedAt == null && lastClickIntent == OfferIntent.ACCEPT
