package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * Crash-recovery hygiene on a restored [AppState] (#1029) — pure, total, and platform-agnostic.
 *
 * A snapshot preserves state faithfully, which is exactly the problem for state whose meaning is
 * "this is provisional and something is about to challenge it". Anything of that shape has to be
 * re-examined at restore rather than trusted, because the machinery that would have resolved it
 * did not survive the process.
 */

/**
 * Drop every platform region's parked dash running-total read (#1029, the settle gate).
 *
 * A park is a read waiting out a settle window on the surface it came from — evidence, not a
 * countdown. After a crash it is evidence from BEFORE the crash: the surface it was read on is
 * long gone, and nothing on the restore path re-arms its `SESSION_PAY_SETTLE` wake timer (a timer
 * is an `AppEffect` the recovery fold suppresses, and an identical read after the restore keeps
 * the deadline without scheduling anything). So a restored park either sits forever or is
 * committed by the first frame that happens past its deadline, minting a figure nothing can
 * contradict. Neither is acceptable for a number the dasher reads as their earnings.
 *
 * Dropping it costs at most one settle window: the committed total stands until the next idle
 * frame re-parks the live one. Fail-null beats fail-wrong (#745).
 *
 * **Applied at the LIVE boundary — to the FINAL restored state, after the tail fold, never to the
 * snapshot it replays from** (#1052). The tail is a faithful replay of what already happened, so it
 * has to run against the snapshot exactly as recorded: a park whose commit timer sits IN the tail
 * committed live, and scrubbing the base first would replay a different history. Running here
 * instead also covers the park a TAIL frame re-created — its `ScheduleTimeout` is not an external
 * effect, so the recovery fold really does arm it — leaving that timer to find nothing and no-op.
 *
 * **And the drop has to be DURABLE, not just installed** (#1052 round 2): the snapshot on disk still
 * carries the park, so a second restart with no ordinary snapshot written in between (neither the
 * cadence nor a major transition need fire) replays that same snapshot over a journal tail that has
 * since grown — and a live frame past the park's deadline commits pre-crash evidence after all.
 * `StateManagerV2.restoreState` therefore CHECKPOINTS the cleaned state ([SnapshotStore.checkpoint],
 * at the restored correlation version, where snapshot rows REPLACE by key) before installing it:
 * dropping the park is only durable if the cleaned state is the next replay base.
 */
fun AppState.droppingSessionPayParks(): AppState = copy(
    regions = regions.copy(
        platforms = regions.platforms.mapValues { (_, region) ->
            region.copy(pendingSessionPay = null)
        },
    ),
)

/**
 * A deadline-bearing pending that a restored [AppState] is still holding, and the wake timer it
 * needs (#1054).
 *
 * Deliberately carries no duration: how long is left is a WALL-CLOCK question, and a wall clock has
 * no business in `:core:state`'s pure half (Principle 1 — the steppers see only `obs.timestamp`).
 * The enumerator states the deadline; `StateManagerV2`, which is the effect boundary, is where
 * "now" is read.
 */
internal data class PendingDeadline(
    val type: TimeoutType,
    val platform: Platform,
    val deadline: Long,
)

/**
 * Every deadline-bearing pending a restored [AppState] carries that must be RE-ARMED (#1054) —
 * one owner, so a fourth pending with a wake timer cannot be added without this list noticing.
 *
 * `StateManagerV2.restoreState` installs `pendingDestructive` / `pendingModeResume` straight from
 * the snapshot and emits no timer for them: the tail fold executes only the `ScheduleTimeout`s the
 * tail ITSELF produced (and those are based on the replayed frame's timestamp, so they fire late by
 * the whole replay lag), and an empty or deadline-neutral tail produces none at all. A `SESSION_END`
 * grace armed by a dash-summary frame therefore stays live until some later admitted observation —
 * which, for an offline dash with the app backgrounded, may never come.
 *
 * This is the deliberate COMPLEMENT of [droppingSessionPayParks], and the distinction is the whole
 * design: a park is stale EVIDENCE — a read of a surface that has been gone since the process died,
 * so it is dropped — while a destructive grace or a graced resume is a COMMITMENT already in
 * flight, decided before the crash and merely waiting out its window. A commitment is re-armed;
 * evidence is not. `SESSION_PAY_SETTLE` is therefore absent here BY DESIGN — see
 * [cloud.trotter.dashbuddy.domain.state.PlatformRegion.pendingSessionPay].
 *
 * **A graced resume is re-armed only for a LIVE session** (#1054 round 2). `commitModeResume` runs
 * through `applyModeTransition(…, Mode.Online)`, which MINTS a session when the region has none — so
 * a resume standing on a session-less region is not a commitment about an existing dash, it is an
 * intent to start one, and waking it from a restore would mint a phantom dash with no screen behind
 * it. The live path can no longer produce that shape (`endSession` clears the resume since round 2),
 * but a snapshot written before this fix can, and the restore must be safe against its own history.
 * The cost is that the genuine cold-start case — Paused with no session, an online frame arming the
 * resume, then a crash — merely loses its auto-mint across the restart, and the next Online frame
 * mints screen-driven instead. Fail-null, and cheap (#745).
 *
 * `GRACE_COMMIT` carries no such condition: a `SESSION_END` on a session-less region is already a
 * no-op at commit, so re-arming it costs one inert fire at worst.
 *
 * Pure, total, and platform-agnostic: the platform comes from the region itself, never a literal
 * (Principle 8).
 */
internal fun AppState.pendingDeadlineTimers(): List<PendingDeadline> =
    regions.platforms.values.flatMap { region ->
        buildList {
            region.pendingDestructive?.let {
                add(PendingDeadline(TimeoutType.GRACE_COMMIT, region.platform, it.deadline))
            }
            region.pendingModeResume?.takeIf { region.session != null }?.let {
                add(PendingDeadline(TimeoutType.MODE_RESUME_COMMIT, region.platform, it.deadline))
            }
        }
    }
