package cloud.trotter.log.gig.ui.main.dashboard

import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion

/**
 * #1533: one live shift Home can end by hand. Every region with a session is a candidate, whatever its
 * mode or day (design D5).
 */
data class EndShiftCandidate(
    val platform: Platform,
    val sessionId: String,
    val startedAt: Long,
    /** The `since` of a pending session end in the region, the strongest suggestion when present. */
    val pendingEndAt: Long? = null,
    /** The shift's last recorded activity in the read model (`lastEventAt`); a suggestion and a warning, never a bound. */
    val lastActivityAt: Long? = null,
    val hasOpenDelivery: Boolean = false,
) {
    /** The prefilled end: a pending end, else the last recorded activity, else the start. */
    val suggestedEnd: Long get() = (pendingEndAt ?: lastActivityAt ?: startedAt).coerceAtLeast(startedAt)

    /** The bound an end at [endAt] breaks, or null when it is inside `[startedAt, now]`. */
    fun errorFor(endAt: Long, now: Long): EndShiftError? = when {
        endAt < startedAt -> EndShiftError.BEFORE_START
        endAt > now -> EndShiftError.AFTER_NOW
        else -> null
    }

    /** True when activity was recorded after [endAt]; it stays on this shift (D4). */
    fun hasActivityAfter(endAt: Long): Boolean = lastActivityAt != null && endAt < lastActivityAt

    companion object {
        /** The region's live shift as a candidate, or null when it has none. */
        fun of(region: PlatformRegion): EndShiftCandidate? {
            val session = region.session ?: return null
            return EndShiftCandidate(
                platform = region.platform,
                sessionId = session.sessionId,
                startedAt = session.startedAt,
                pendingEndAt = region.pendingDestructive?.takeIf { it.kind == DestructiveKind.SESSION_END }?.since,
                hasOpenDelivery = region.activeTask != null || region.activeJob != null,
            )
        }
    }
}

enum class EndShiftError { BEFORE_START, AFTER_NOW }

enum class EndShiftPicker { DATE, TIME }

/**
 * The end-shift sheet's hoisted state. [sessionId] names the shift being ended, fixed when the sheet opens
 * so a shift that ends and is replaced while the sheet is open is never ended in its place (design D1).
 */
data class EndShiftForm(
    val sessionId: String? = null,
    val confirmed: Boolean = false,
    /** The chosen end; null = the candidate's suggested end. */
    val endAt: Long? = null,
    val picker: EndShiftPicker? = null,
) {
    fun endAtFor(candidate: EndShiftCandidate): Long = endAt ?: candidate.suggestedEnd

    companion object {
        /** A fresh form; with exactly one candidate it is already chosen. */
        fun opening(candidates: List<EndShiftCandidate>) = EndShiftForm(sessionId = candidates.singleOrNull()?.sessionId)
    }
}
