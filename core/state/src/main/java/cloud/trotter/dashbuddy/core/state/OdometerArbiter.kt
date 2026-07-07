package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion

/**
 * #438 B5 (item 9) — the pure odometer arbitration predicate, the single source of truth
 * shared by [EffectMap] (the diff-level Start/Stop/Pause/Resume decision) and [StateManagerV2]
 * (the crash-recovery reconciliation). A function of region state only — no Android, no wall
 * clock, no DB read — so it composes into the pure diff and can be replayed deterministically.
 *
 * **Why it exists.** The odometer was a global GPS singleton + one session anchor commanded by
 * EACH platform's own session/task diff. Under concurrency that breaks three ways: a second
 * session's Start zeroed the first's accrued miles, the first session ending killed GPS under
 * the second, and one platform's store arrival paused GPS mid-drive on the other's leg. B5 lifts
 * the decision to the cross-platform tier:
 *  - GPS runs while ANY session is live (the live-session count, [Platform]-agnostic);
 *  - GPS pauses only when EVERY live region is stationary (parked at an arrived task) and resumes
 *    the moment ANY live region starts moving again.
 *
 * The attribution decision the layer above enforces (locked, dev 2026-07-06): task-execution
 * miles belong to that task's platform; dual-online idle miles are full-to-each with an overlap
 * flag; every aggregate/IRS/CSV read anchors to the real odometer total (never Σ per-session).
 */
object OdometerArbiter {

    /**
     * The arrived task sub-flows — a region is "stationary" (parked) in exactly these
     * ([Flow.TaskPickupArrived]/[Flow.TaskDropoffArrived]). Every nav/idle/offer flow — and
     * `PostTask` — is moving.
     */
    private val STATIONARY_FLOWS = setOf(Flow.TaskPickupArrived, Flow.TaskDropoffArrived)

    /**
     * A region's own stationary signal (vet M6): **stationary ⇔ its last acted flow is an arrived
     * task sub-flow and not PostTask.** Keyed off [PlatformRegion.lastActedFlow] (the flow THIS
     * region drove, #438 item 5) rather than `activeTask.subPhase == ARRIVED`, because the two
     * diverge at PostTask-under-retire-grace — the task is still ARRIVED but the flow is `PostTask`,
     * where today's per-edge logic already Resumed. Reading the flow makes `PostTask` moving (it is
     * not in [STATIONARY_FLOWS]), so the predicate matches today's Resume-on-PostTask-entry edge.
     * A null `lastActedFlow` (legacy pre-B1 snapshot, or a region that never acted on a screen)
     * reads moving.
     */
    fun isStationary(region: PlatformRegion): Boolean = region.lastActedFlow in STATIONARY_FLOWS

    /**
     * ALL live regions are stationary (and there is at least one). A live region is one holding a
     * session — the same definition [CrossPlatformRegionStepper] counts into `activeSessionCount`,
     * so "no live session" is vacuously **false** here: with nothing dashing there is nothing to
     * pause (the count crossing owns Start/Stop). Pause fires when this flips false→true across a
     * step, Resume when it flips true→false — the level-crossing the caller diffs.
     */
    fun allLiveStationary(platforms: Map<Platform, PlatformRegion>): Boolean {
        val live = platforms.values.filter { it.session != null }
        return live.isNotEmpty() && live.all { isStationary(it) }
    }

    /**
     * #438 B5 recovery reconciliation (vet M6): the odometer effects to (re-)fire on the first live
     * observation after a crash, given the [restored] state. Odometer effects are recovery-suppressed
     * externals, so GPS is dead after a restore — and a level-*crossing* Resume can't restart it (the
     * level is already "moving", so no crossing fires; the whole post-crash leg's miles would be lost).
     *
     *  - No live session → nothing (GPS correctly off).
     *  - A live session, moving → [AppEffect.StartOdometer] (re-establishes tracking + the
     *    notification; the per-session anchor persisted across the crash, so no reset).
     *  - A live session, ALL live regions parked → Start **then** [AppEffect.PauseOdometer], matching
     *    the restored "stationary at a drop" level (GPS re-registered but paused; the next Resume
     *    crossing restarts it on departure).
     *
     * Idempotent by construction: [OdometerEffectHandler]'s start/stop no-op if already in that state.
     */
    fun recoveryReconciliation(restored: AppState): List<AppEffect> {
        if (restored.regions.crossPlatform.activeSessionCount <= 0) return emptyList()
        return buildList {
            add(AppEffect.StartOdometer)
            if (allLiveStationary(restored.regions.platforms)) add(AppEffect.PauseOdometer)
        }
    }
}
