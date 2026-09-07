package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.PendingWake
import cloud.trotter.dashbuddy.domain.state.mintWakeId
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * Crash-recovery hygiene on a restored [AppState] (#1029, widened by #1054) — pure, total, and
 * platform-agnostic.
 *
 * A snapshot preserves state faithfully, which is exactly the problem for state whose meaning is
 * "this is provisional and something is about to challenge it". Anything of that shape has to be
 * re-examined at restore rather than trusted, because the machinery that would have resolved it did
 * not survive the process. Three answers, one per class of pending:
 *
 * ## Dropped — stale EVIDENCE
 *
 * **The park** (`pendingSessionPay`, #1029) is a read waiting out a settle window on the surface it
 * came from. After a crash it is evidence from BEFORE the crash: the surface is long gone, and
 * nothing on the restore path re-arms its `SESSION_PAY_SETTLE` wake (an identical read after the
 * restore keeps the deadline without scheduling anything). So a restored park either sits forever
 * or is committed by the first frame past its deadline, minting a figure nothing can contradict —
 * unacceptable for a number the dasher reads as their earnings. Dropping costs at most one settle
 * window: the committed total stands until the next idle frame re-parks the live one.
 *
 * **The graced resume** (`pendingModeResume`, #605 — dropped since #1054 round 3) is the same class
 * of thing, which round 2 got wrong by trying to keep it. Its window is 8 s of *un-contradicted
 * observation* — a paused frame inside it cancels — so committing one after a restart asserts that
 * 8 s of dead process time were 8 s of nobody contradicting it. And the commit is not inert:
 * `applyModeTransition(…, Mode.Online)` MINTS a session when the region has none (a phantom dash off
 * any observation past the deadline — a notification, a click — with no online screen behind it),
 * and even with a live session `EffectMap.diffMode`'s Paused→Online arm CANCELS the
 * `SESSION_PAUSED_SAFETY` net. Round 2's session-null guard suppressed only the RE-ARM, which is not
 * the same thing: the resume stayed installed for the tail's own replayed `ScheduleTimeout` (not an
 * external effect, so recovery really arms it) or any later observation to commit. Dropping fails
 * toward **Paused**, the honest reading of a process that died under a pause sheet, and the next
 * Online-implying frame arms a fresh grace screen-driven, exactly as #605 intends.
 *
 * Neither drop needs a cancel (#1054 round 5): `SideEffectEngine` SKIPS arming a
 * [TimeoutType.REGION_TIMERS] member while `recovering == true`, so the replay never armed one to
 * begin with. Round 4 cancelled them instead, which could not work — the replayed arm had already
 * executed by the time the reconcile ran, so its `Timer Expired` WARN and its journalled fire were
 * beyond recall.
 *
 * ## Re-based — a DECISION in flight that has observed none of its window
 *
 * **The destructive grace** (`pendingDestructive`) is not an observation waiting to be contradicted:
 * the destructive signal was on screen and the window is only the courtesy before we believe it. It
 * survives — but not untouched. A grace observes nothing while the process is dead, and **dead time
 * is not un-contradicted time**: the collapsed receipt's expansion (#1033) and the misrecognized
 * summary's contradicting task frame (#431) can both still land. So the REMAINING window is served
 * LIVE from [nowMs] — `remaining = (deadline − since) − (lastSeen − since)`, re-based onto now —
 * while `since` is left exactly as it was, because #732 stamps the commit at `since`. Before this a
 * restored grace re-armed at the 1 ms floor and committed before any live frame could arrive; a
 * collapsed-receipt `TASK_RETIRE` lost its whole expansion window that way.
 *
 * `lastSeen` is `AppState.timestamp` — set by `StateMachine.step` on EVERY accepted observation
 * regardless of platform, so it is the honest "when did this state last see anything". A region's
 * own `lastObservedAt` only moves when that region is stepped with a mode signal, so it lags and
 * would overstate the remaining window.
 *
 * **The re-base is a FIXED POINT, which is why it can be applied more than once** (#1054 round 5).
 * Re-basing moves the deadline but NOT `AppState.timestamp`, so a naive second pass would measure
 * `observed` against a timestamp the first pass already accounted for and hand back the whole
 * elapsed interval as fresh window — a 2.5 s summary grace restored at 100 000 and again at 101 000
 * stretched to 193 500 ms, and a crash loop stretched it without bound. [PendingDestructive.servedFrom]
 * is the second anchor that closes it: once set, the window is measured from THERE, so re-running
 * the hygiene with no intervening observation returns the same remaining window it did the first
 * time (only the absolute deadline slides forward with `nowMs`).
 *
 * That idempotence is what makes a crash LOOP safe: each restart re-bases from the same anchor and
 * serves the same remaining window, however many times it happens.
 *
 * It is applied exactly ONCE per restore, though (#1054 round 6). Round 5 ran it twice so the served
 * window could start at the live boundary rather than before the snapshot load and tail replay — but
 * that made the durable checkpoint describe a DIFFERENT deadline from the state actually installed,
 * and an observation that was a no-op live then COMMITTED when the next restart replayed it from
 * that base. `StateManagerV2.finishRestore` therefore checkpoints and installs the SAME state, at
 * the cost of the checkpoint write's own latency; see its KDoc.
 *
 * **A live deadline MOVE re-anchors this** (rounds 6–7): `withWakeIdIfDeadlineMoved` clears
 * `servedFrom` and sets [PendingDestructive.windowFrom] to the moving observation, because a tighten
 * replaces the window and neither the old restore anchor nor `since` describes it — after a
 * wall-clock rollback a tighten can land BEHIND `since`, and measuring from there computes zero
 * remaining for a window that was never served.
 *
 * ## Re-armed as-is — a countdown that is not ours
 *
 * **The pause-safety deadline** (`pauseSafety`, #1054 round 4) is left alone here and
 * re-armed verbatim by [pendingDeadlineTimers]: it belongs to the PLATFORM and ran on the platform's
 * clock while we were dead. See the field's KDoc.
 *
 * ## Where and when
 *
 * **At the LIVE boundary — on the FINAL restored state, after the tail fold, never on the snapshot
 * it replays from** (#1052). The tail is a faithful replay of what already happened, so it must run
 * against the snapshot exactly as recorded: a park whose commit timer sits IN the tail committed
 * live, and scrubbing the base would replay a different history. Running here also covers a pending
 * a TAIL frame re-created. (Its `ScheduleTimeout` is never executed: since round 5
 * `SideEffectEngine` skips a [TimeoutType.REGION_TIMERS] arm while recovering, so the replay leaves
 * no coroutine behind at all.)
 *
 * **And the result has to be DURABLE, not just installed** (#1052 round 2): the snapshot on disk
 * still carries what was dropped, so a second restart with no ordinary snapshot in between (neither
 * the cadence nor a major transition need fire) replays it over a journal tail that has since grown.
 * `StateManagerV2.finishRestore` therefore CHECKPOINTS the cleaned state
 * ([SnapshotStore.checkpoint], at the restored correlation version, where snapshot rows REPLACE by
 * key) before installing it.
 *
 * @param nowMs the wall clock, read ONCE at the `StateManagerV2` edge and passed in so this stays
 *   pure (Principle 1). It is the only thing here that could not come from the state itself.
 */
fun AppState.recoveryHygiene(nowMs: Long): AppState {
    val lastSeen = timestamp
    return copy(
        regions = regions.copy(
            platforms = regions.platforms.mapValues { (_, region) ->
                val scrubbed = region.copy(pendingSessionPay = null, pendingModeResume = null)
                val pend = scrubbed.pendingDestructive ?: return@mapValues scrubbed
                // The window is measured from the restore anchor once one exists, else from the
                // observation that last MOVED the deadline, else from the arm. `since` is never an
                // anchor beyond that fallback and is never written (#732 stamps the commit at it) —
                // after a clock rollback a tighten can land BEHIND it, and measuring from `since`
                // then computes zero remaining for a window that was never served (round 7).
                val base = pend.servedFrom ?: pend.windowFrom ?: pend.since
                val observed = (lastSeen - base).coerceAtLeast(0L)
                val remaining = (pend.deadline - base - observed).coerceAtLeast(0L)
                val (withId, wakeId) = scrubbed.mintWakeId()
                withId.copy(
                    pendingDestructive = pend.copy(
                        deadline = nowMs + remaining,
                        servedFrom = nowMs,
                        wakeId = wakeId,
                    ),
                )
            },
        ),
    )
}

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
    val wake: PendingWake,
)

/**
 * What crash recovery RE-ARMS (#1054), and — just as importantly — what it does not.
 *
 * This is the one owner of that question, not of "every deadline-bearing pending" (a claim round 2
 * made and round 3 withdrew as untrue). The full ledger:
 *
 * - **`GRACE_COMMIT`** (`pendingDestructive`) — re-armed, at the deadline [recoveryHygiene] re-based
 *   so the grace serves its REMAINING window live. It is a decision already taken, and the one
 *   pending whose commit fails toward the SAFE side: it ends a dash that in all likelihood really
 *   ended, where the others would invent something (a figure, a dash).
 * - **`SESSION_PAUSED_SAFETY`** (`pauseSafety`) — re-armed AS-IS, dead time included,
 *   because it is the PLATFORM's countdown running on the platform's clock (see the field's KDoc). A
 *   deadline already past fires at once and ends the dash: the designed outcome, and the fix for a
 *   pocketed phone whose countdown ended overnight leaving the session live for the next morning's
 *   dash to RESUME. Before round 4 this deadline lived only in the engine's in-memory timer map, so
 *   a restore into Paused had no timer of any kind.
 * - **`MODE_RESUME_COMMIT`** and **`SESSION_PAY_SETTLE`** — NOT re-armed, because [recoveryHygiene]
 *   dropped the pendings as stale evidence. No cancel is needed either: the replay never armed one,
 *   because `SideEffectEngine` skips a region timer while recovering (round 5).
 * - **`OFFER_EXPIRY` / `SETTLE_UI`** — pre-existing gaps this issue does not close: a tail-replayed
 *   arm is scheduled against a replayed frame's timestamp and so fires late, and a restored pending
 *   offer gets no fresh expiry at all. Tracked as **#1076**; nothing here regresses them. (The three
 *   region timers no longer have the first half of that problem — their arms carry `deadlineMs`.)
 *
 * Pure, total, and platform-agnostic: the platform comes from the region itself, never a literal
 * (Principle 8).
 */
internal fun AppState.pendingDeadlineTimers(): List<PendingDeadline> =
    regions.platforms.values.flatMap { region ->
        buildList {
            region.pendingDestructive?.let {
                add(
                    PendingDeadline(
                        TimeoutType.GRACE_COMMIT,
                        region.platform,
                        PendingWake(it.deadline, it.wakeId),
                    ),
                )
            }
            region.pauseSafety?.let {
                add(PendingDeadline(TimeoutType.SESSION_PAUSED_SAFETY, region.platform, it))
            }
        }
    }
