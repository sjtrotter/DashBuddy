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
 *
 * **The rule the whole file turns on: evidence is DROPPED, a commitment is RE-ARMED** (#1054 round
 * 3). A pending whose meaning is "this is what I last saw, and I am waiting to be contradicted" is
 * an observation of a screen that has been gone since the process died — nothing after the restore
 * can contradict it, so waiting it out is not a wait, it is a rubber stamp. A pending whose meaning
 * is "this has been decided and is serving out its window" survives, because the decision was made
 * on evidence that was live at the time and the window is merely a courtesy.
 *
 * By that test: [AppState.recoveryHygiene] drops `pendingSessionPay` and `pendingModeResume`, while
 * `pendingDestructive` is kept and re-armed by [pendingDeadlineTimers]. The destructive grace is
 * additionally the only one of the three that fails toward the SAFE side — ending a dash that may
 * already be over — where the other two fail toward inventing something (a figure, a dash).
 */

/**
 * Scrub the restored state of every pending that is stale EVIDENCE (#1029, widened by #1054 round
 * 3) — the parked dash running-total read, and the graced screen-implied resume out of Paused.
 *
 * **The park** (#1029) is a read waiting out a settle window on the surface it came from. After a
 * crash it is evidence from BEFORE the crash: the surface is long gone, and nothing on the restore
 * path re-arms its `SESSION_PAY_SETTLE` wake timer (an identical read after the restore keeps the
 * deadline without scheduling anything). So a restored park either sits forever or is committed by
 * the first frame that happens past its deadline, minting a figure nothing can contradict. Neither
 * is acceptable for a number the dasher reads as their earnings. Dropping it costs at most one
 * settle window: the committed total stands until the next idle frame re-parks the live one.
 *
 * **The resume** (#605, dropped since #1054 round 3) is the same class of thing, which round 2 got
 * wrong by trying to keep it. Its window is 8 s of *un-contradicted observation* — a paused frame
 * inside it cancels — so committing one after a restart is asserting that 8 s of dead process time
 * were 8 s of nobody contradicting it. Worse, the commit is not inert: `applyModeTransition(…,
 * Mode.Online)` MINTS a session when the region has none (a phantom dash off any observation past
 * the deadline — a notification, a click — with no online screen behind it), and even with a live
 * session `EffectMap.diffMode`'s Paused→Online arm CANCELS the `SESSION_PAUSED_SAFETY` timer, a net
 * that is not reconstructible from state once dropped. Round 2's session-null guard suppressed only
 * the RE-ARM, which is not the same thing: the resume was still installed into live state, and the
 * tail path's own replayed `ScheduleTimeout` (not an external effect, so recovery really does arm
 * it) or any later observation past the deadline still committed it.
 *
 * Dropping fails toward **Paused**, which is the honest reading of a process that died while a
 * pause sheet was up — and it is cheap, because the next Online-implying frame arms a fresh resume
 * grace, screen-driven, exactly as the #605 design intends. Fail-null beats fail-wrong (#745).
 *
 * `pendingDestructive` is deliberately NOT touched — see [pendingDeadlineTimers].
 *
 * **Applied at the LIVE boundary — to the FINAL restored state, after the tail fold, never to the
 * snapshot it replays from** (#1052). The tail is a faithful replay of what already happened, so it
 * has to run against the snapshot exactly as recorded: a park whose commit timer sits IN the tail
 * committed live, and scrubbing the base first would replay a different history. Running here
 * instead also covers a pending a TAIL frame re-created — its `ScheduleTimeout` is not an external
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
fun AppState.recoveryHygiene(): AppState = copy(
    regions = regions.copy(
        platforms = regions.platforms.mapValues { (_, region) ->
            region.copy(pendingSessionPay = null, pendingModeResume = null)
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
 * "now" is read — and since round 3 it does not even do that: the absolute deadline rides the
 * effect and `SideEffectEngine` resolves the remainder when it actually schedules.
 */
internal data class PendingDeadline(
    val type: TimeoutType,
    val platform: Platform,
    val deadline: Long,
)

/**
 * What crash recovery RE-ARMS: the destructive grace, and nothing else (#1054).
 *
 * This is the one owner of that question — **not** of "every deadline-bearing pending", a claim
 * round 2 made and round 3 withdrew as untrue. The known omissions, by name:
 *
 * - **`MODE_RESUME_COMMIT`** — dropped by [recoveryHygiene] as stale evidence (round 3). There is
 *   nothing left to re-arm, and that is the point.
 * - **`SESSION_PAY_SETTLE`** — dropped by [recoveryHygiene] too, for the same reason (#1029).
 * - **`OFFER_EXPIRY`, `SESSION_PAUSED_SAFETY`, `SETTLE_UI`** — PRE-EXISTING gaps this issue does not
 *   close. A timer the tail replay re-arms is scheduled against a replayed frame's timestamp, so it
 *   fires late by the whole replay lag, and an offer restored with no `OFFER_EXPIRY` behind it can
 *   sit pending indefinitely. Tracked as #1076; nothing here regresses them.
 *
 * `pendingDestructive` earns its place because it is a DECISION already taken, not an observation
 * waiting to be contradicted: the destructive signal was on screen, the grace is only the courtesy
 * window before we believe it. Without a re-arm it stays live until some later admitted observation
 * — which, for the case `GRACE_COMMIT` exists for (#431, offline with the app backgrounded), may
 * never come; `restoreState` installs the pending straight from the snapshot and the tail fold emits
 * only the `ScheduleTimeout`s the tail ITSELF produced, so an empty or deadline-neutral tail emits
 * none at all. And it is the one pending whose commit fails toward the SAFE side: it ends a dash
 * that in all likelihood really did end, where a resume or a park would invent one.
 *
 * Pure, total, and platform-agnostic: the platform comes from the region itself, never a literal
 * (Principle 8).
 */
internal fun AppState.pendingDeadlineTimers(): List<PendingDeadline> =
    regions.platforms.values.mapNotNull { region ->
        region.pendingDestructive?.let {
            PendingDeadline(TimeoutType.GRACE_COMMIT, region.platform, it.deadline)
        }
    }
