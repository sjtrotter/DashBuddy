package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.state.PlatformRegion

/**
 * The HUD's received-offer count while a shift runs (#1119 S2): the folded closed outcomes plus the shift's pooled
 * offers. A pooled offer has no outcome event yet, so the folded count alone would miss it; it counts once, because
 * it moves to accepted on a link and to not-taken (or declined) at the shift end. The one owner; both HUD readers
 * call it.
 */
fun liveOffersReceived(record: SessionRecord?, region: PlatformRegion?): Int? =
    record?.let { it.offersReceived + (region?.offerPool?.count { e -> e.sessionId == it.sessionId } ?: 0) }
