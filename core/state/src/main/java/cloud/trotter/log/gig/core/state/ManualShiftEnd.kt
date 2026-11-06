package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.TaskPhase

/**
 * #1533: the dasher ends a live shift by hand from Home. The command is a journaled
 * [Observation.UiInput] ([Observation.UiInput.ACTION_END_SHIFT]) naming the shift and the chosen end, so
 * crash replay reproduces it. Handled by [PlatformRegionStepper] before lazy expiry; `diffMode` emits its
 * one `DASH_STOP` (source `manual`) and the close-out sweep skips the pickup it force-stamps.
 *
 * Backdating the end does not repair miles the open shift already accrued, nor split later dashes it
 * absorbed (design D7).
 */
internal object ManualShiftEnd {

    /** True when [obs] is an end-shift command (matching or not). */
    fun isCommand(obs: Observation): Boolean =
        obs is Observation.UiInput && obs.action == Observation.UiInput.ACTION_END_SHIFT

    /**
     * Apply [cmd] to [region]. Acts only on the exact live session the command names; a mismatch, no
     * session, or a replayed duplicate returns [region] unchanged. [endSession] is the stepper's own
     * teardown, called with (region, end instant).
     */
    fun apply(
        region: PlatformRegion,
        cmd: Observation.UiInput,
        endSession: (PlatformRegion, Long) -> PlatformRegion,
    ): PlatformRegion {
        val session = region.session ?: return region
        if (cmd.sessionId == null || cmd.sessionId != session.sessionId) return region
        val endAt = (cmd.endAt ?: cmd.timestamp).coerceAtMost(cmd.timestamp).coerceAtLeast(session.startedAt)
        val ended = endSession(region.copy(pendingDestructive = null, pendingModeResume = null), endAt)
        // endSession keeps Paused and an Offline-origin pending start; a manual end leaves neither.
        return ended.copy(mode = Mode.Offline, pauseSafety = null, pendingModeResume = null)
    }

    /**
     * D6: the active pickup a manual end force-stamped on this step. It was never confirmed, so the
     * close-out sweep must not emit `PICKUP_CONFIRMED` (or a shop-rate sample) for it.
     */
    fun forcedPickupId(prev: PlatformRegion, next: PlatformRegion, obs: Observation): String? {
        if (!isCommand(obs) || prev.session == null || next.session != null) return null
        return prev.activeTask?.takeIf { it.phase == TaskPhase.PICKUP }?.taskId
    }
}
