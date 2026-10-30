package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PendingModeResume
import cloud.trotter.log.gig.domain.state.PendingSessionPay
import cloud.trotter.log.gig.domain.state.PendingWake
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.WakeBearing
import cloud.trotter.log.gig.domain.state.WakeId
import cloud.trotter.log.gig.domain.state.mintWakeId

/** Builders are pure: a changed window is rebuilt with its freshly minted identity. */
private fun <T : WakeBearing> PlatformRegion.install(
    prev: T?,
    same: (T, T) -> Boolean,
    build: (WakeId) -> T,
): Pair<PlatformRegion, T> {
    if (prev != null) {
        val candidate = build(prev.wakeId)
        if (same(prev, candidate)) return this to candidate
    }
    val (withId, wakeId) = mintWakeId()
    return withId to build(wakeId)
}

internal sealed interface WindowStart {
    data class Live(val at: Long) : WindowStart
    data class Restored(val at: Long) : WindowStart
}

/**
 * Live tightens keep identity and accounting only while both kind and deadline stand.
 * A replacement/move starts a new live window; `since` remains its historical domain time.
 * Recovery always renews identity, even at an equal deadline, preserving the live window's
 * original anchor and setting the restore anchor so crash-loop accounting stays a fixed point.
 */
internal fun PlatformRegion.withWakeIdIfDeadlineMoved(
    prev: PendingDestructive?,
    start: WindowStart,
    build: (WakeId) -> PendingDestructive,
): PlatformRegion {
    val (region, pending) = install(
        prev = prev.takeIf { start is WindowStart.Live },
        same = { old, next -> old.kind == next.kind && old.deadline == next.deadline },
        build = build,
    )
    val installed = when (start) {
        is WindowStart.Restored -> pending.copy(servedFrom = start.at)
        is WindowStart.Live -> if (prev != null && region === this) {
            pending.copy(windowFrom = prev.windowFrom, servedFrom = prev.servedFrom)
        } else {
            pending.copy(windowFrom = start.at, servedFrom = null)
        }
    }
    return region.copy(pendingDestructive = installed)
}

internal fun PlatformRegion.modeResumeWindow(
    prev: PendingModeResume?,
    build: (WakeId) -> PendingModeResume,
): Pair<PlatformRegion, PendingModeResume> =
    install(prev, same = { old, next -> old.deadline == next.deadline }, build = build)

// Return the pending to the gate: SessionPaySettleGate remains the owner of its slot.
internal fun PlatformRegion.sessionPayWindow(
    build: (WakeId) -> PendingSessionPay,
): Pair<PlatformRegion, PendingSessionPay> =
    install(prev = null, same = { _, _ -> false }, build = build)

internal fun PlatformRegion.pauseSafetyWindow(deadline: Long): Pair<PlatformRegion, PendingWake> =
    install(prev = null, same = { _, _ -> false }) { wakeId -> PendingWake(deadline, wakeId) }
