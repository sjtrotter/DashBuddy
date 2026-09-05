package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.state.AppState

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
