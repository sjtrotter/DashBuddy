package cloud.trotter.log.gig.state

import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.feature.bubble.session.LiveSession

/**
 * The one owner of "which shifts are running" (#1515): every platform whose region holds a session, oldest shift
 * first, then by platform name, so the order never reshuffles on an unrelated state emission (Map iteration order is
 * not a contract). The bubble's switcher and Recap's "a shift is running" both read it.
 */
fun liveSessions(state: AppState): List<LiveSession> =
    state.regions.platforms
        .mapNotNull { (platform, region) -> region.session?.let { LiveSession(platform, it.sessionId, it.startedAt) } }
        .sortedWith(compareBy({ it.startedAt }, { it.platform.name }))

/** True while any shift runs; Recap's comparison waits on it. */
fun shiftRunning(state: AppState): Boolean = liveSessions(state).isNotEmpty()
