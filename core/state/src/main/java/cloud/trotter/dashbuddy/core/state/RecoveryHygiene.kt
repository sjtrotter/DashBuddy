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
 */
fun AppState.droppingSessionPayParks(): AppState = copy(
    regions = regions.copy(
        platforms = regions.platforms.mapValues { (_, region) ->
            region.copy(pendingSessionPay = null)
        },
    ),
)
