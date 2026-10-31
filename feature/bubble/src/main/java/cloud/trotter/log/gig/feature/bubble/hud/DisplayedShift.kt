package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.ShiftEarnings
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Session

/**
 * #1443: the one shift the hero, its cash mark, the platform chip, the resting card and the cash-entry target
 * describe. [sessionId] is null while no acceptable row exists (the shift that just ended has not been projected
 * yet): the hero shows the em dash, with no cash mark and no cash-entry target.
 */
data class DisplayedShift(
    val platform: Platform,
    val sessionId: String?,
    val live: Boolean,
    val earnings: Double?,
    val cashTips: Double?,
    /** The shift's row: the resting card's span, deliveries and offers, and its Earned/hr span. */
    val record: SessionRecord?,
)

/** The focused platform's latest-row read, before and after its first emission. */
sealed interface LatestShiftRead {
    data object Loading : LatestShiftRead
    data class Loaded(val row: SessionRecord?) : LatestShiftRead
}

/** The displayed shift's identity, before its detail arrives: the live session, else an accepted row. */
data class ShiftTarget(val platform: Platform, val session: Session?, val row: SessionRecord?) {
    val sessionId: String? get() = session?.sessionId ?: row?.sessionId
}

/** One `sessionDetail` emission, tagged with the id it was read for. */
data class ShiftDetailRead(val sessionId: String?, val detail: SessionDetail?)

object DisplayedShifts {
    /**
     * A platform region holding a session is live, even in an end grace while the HUD rests. Otherwise the
     * platform's latest row is accepted only when it is the shift that ended last: a row read for another
     * platform is still loading; an open row not in state sits between leaving state and its `DASH_STOP` fold;
     * an ended row that ended before the region's last session end belongs to an older shift (the one that just
     * ended has no row yet). Each shows the em dash rather than an older shift.
     */
    fun target(platform: Platform, region: PlatformRegion?, read: LatestShiftRead): ShiftTarget {
        region?.session?.let { return ShiftTarget(platform, it, null) }
        val row = (read as? LatestShiftRead.Loaded)?.row?.takeIf { accepted(platform, region, it) }
        return ShiftTarget(platform, null, row)
    }

    // The region's anchor and the row's endedAt are the same instant: both are the end's honest time
    // (the grace's `since`, else the ending frame), stamped by the state machine and DASH_STOP alike.
    private fun accepted(platform: Platform, region: PlatformRegion?, row: SessionRecord): Boolean {
        if (row.platform != platform) return false
        val endedAt = row.endedAt ?: return false
        val anchor = region?.lastSessionEndedAt ?: return true
        return endedAt >= anchor
    }

    /** A figure and a cash mark appear only from the detail read for the displayed id. */
    fun resolve(target: ShiftTarget, read: ShiftDetailRead): DisplayedShift {
        val id = target.sessionId
        val matched = id != null && read.sessionId == id
        val detail = read.detail?.takeIf { matched && it.session.sessionId == id }
        val session = target.session
        val earnings = when {
            !matched -> null
            session != null -> ShiftEarnings.live(session.runningEarnings, detail?.recordedPay)
            else -> target.row?.let { ShiftEarnings.rested(it.reportedEarnings, detail?.recordedPay) }
        }
        return DisplayedShift(
            platform = target.platform,
            sessionId = id,
            live = session != null,
            earnings = earnings,
            cashTips = detail?.cashTips,
            record = target.row ?: detail?.session,
        )
    }
}
