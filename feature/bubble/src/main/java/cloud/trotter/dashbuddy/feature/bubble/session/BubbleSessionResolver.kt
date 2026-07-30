package cloud.trotter.dashbuddy.feature.bubble.session

import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
import cloud.trotter.dashbuddy.domain.state.Platform

/** A dash that is live right now (its `PlatformRegion.session` is non-null) on one platform. */
data class LiveSession(
    val platform: Platform,
    val sessionId: String,
    val startedAt: Long,
)

/** One session feeding the card stack, tagged with the platform that owns it (null ⇒ unknown). */
data class CardSource(
    val sessionId: String,
    val platform: Platform?,
)

/**
 * The resolved answer to "what is the bubble showing right now" (#867) — pure data derived from
 * the mode, the switcher pick, and live state. Everything the HUD renders keys off exactly one of
 * these, so there is a single owner for the displayed identity (principle 5).
 *
 * @param displayedPlatform the platform the surface is attributed to (status badge, mode cards,
 *   session earnings). Null before anything has been recognized.
 * @param followPlatform the platform whose frame we last recognized — the one the LIVE card and
 *   the global `FlowRegion` describe. Equal to [displayedPlatform] unless a pin is in effect.
 * @param chatSessionId the session whose chat + live surfaces are shown. Null when nothing has
 *   ever dashed.
 * @param cardSources the sessions whose event logs fold into the card stack — one normally, N in
 *   [BubbleSessionMode.MERGED].
 * @param switchablePlatforms the manual switcher's chip row; EMPTY means "don't render it"
 *   (fewer than two live sessions, or merged mode where a pick would do nothing).
 * @param labelsVisible true when ≥2 sessions are live: platform chips MUST render on the cards and
 *   the header in EVERY mode, so a swap of identity can never be silent.
 * @param showLiveCard whether the live (active) card belongs to what we're displaying. The live
 *   card is built from the global `FlowRegion`, which only ever describes [followPlatform]; when a
 *   pin points somewhere else there is no live screen for that platform, so we render its completed
 *   timeline with NO live card rather than dressing another platform's frame in its name. This is
 *   also what keeps the offer Accept/Decline row (live-card-only) from ever aiming at the wrong
 *   platform while pinned.
 */
data class BubbleSessionPresentation(
    val displayedPlatform: Platform? = null,
    val followPlatform: Platform? = null,
    val chatSessionId: String? = null,
    val cardSources: List<CardSource> = emptyList(),
    val switchablePlatforms: List<Platform> = emptyList(),
    val labelsVisible: Boolean = false,
    val showLiveCard: Boolean = true,
) {
    companion object {
        val Empty = BubbleSessionPresentation()
    }
}

/**
 * Decides which dash session(s) the bubble HUD renders (#867).
 *
 * Pure, Android-free and clock-free — the same shape as [cloud.trotter.dashbuddy.feature.bubble
 * .cards.FlowCardMapper]: state in, presentation out. The mode semantics are documented on
 * [BubbleSessionMode]; the load-bearing details:
 *
 * - **The temporary pin.** In [BubbleSessionMode.FOLLOW_ACTIVE] a switcher pick is honored only
 *   while that platform's session is live; the moment it ends the pick stops applying and the HUD
 *   follows again. In [BubbleSessionMode.PINNED] the pick is *not* forgotten (the caller keeps it),
 *   but display still falls back to follow while the pinned platform is dark — so it re-takes
 *   effect on that platform's next dash.
 * - **Merged is card-stack-only.** Chat stays on the follow-active session. Merging the chat would
 *   interleave two dispatcher narratives whose per-line provenance the chat model doesn't carry
 *   (the #873 write-side fix files each line to its OWN session; nothing on `ChatMessage` says
 *   which). Rather than invent a per-line platform tag for a test-only mode, the merge covers the
 *   surface the issue is actually about — the card stack — and the header chip keeps the chat's
 *   identity explicit. If merged wins the field test, per-line chat labels are the follow-up.
 * - **Fail-toward-labels.** [BubbleSessionPresentation.labelsVisible] keys off liveness alone, not
 *   the mode, so no mode can turn labeling off.
 */
object BubbleSessionResolver {

    /**
     * @param mode the dev-settings experiment switch.
     * @param selection the switcher's current pick (null ⇒ never picked / released by the caller).
     * @param followPlatform [cloud.trotter.dashbuddy.domain.state.focusedPlatform] — the platform of
     *   the last recognized frame.
     * @param liveSessions every platform with a live session, caller-ordered (oldest dash first).
     * @param fallbackSessionId the durable most-recent session from the event log (#459) — what to
     *   show when nothing is live.
     */
    fun resolve(
        mode: BubbleSessionMode,
        selection: Platform?,
        followPlatform: Platform?,
        liveSessions: List<LiveSession>,
        fallbackSessionId: String?,
    ): BubbleSessionPresentation {
        // A pick only applies while its platform is actually dashing. FOLLOW_ACTIVE and PINNED
        // differ in whether the caller KEEPS the pick after that (see BubbleSessionMode), not in
        // what a dark platform displays. MERGED ignores the pick outright.
        val effectiveSelection = when (mode) {
            BubbleSessionMode.MERGED -> null
            else -> selection?.takeIf { picked -> liveSessions.any { it.platform == picked } }
        }

        val displayedPlatform = effectiveSelection ?: followPlatform

        // Preserves the pre-#867 resolution exactly when nothing is pinned: the displayed
        // platform's live session, else ANY live session, else the durable event-log fallback
        // (AppState.activeSessionId() ?: getMostRecentSessionId(), #437/#459).
        val chatSessionId = liveSessions.firstOrNull { it.platform == displayedPlatform }?.sessionId
            ?: liveSessions.firstOrNull()?.sessionId
            ?: fallbackSessionId

        val cardSources = when {
            mode == BubbleSessionMode.MERGED && liveSessions.isNotEmpty() ->
                liveSessions.map { CardSource(it.sessionId, it.platform) }

            chatSessionId != null -> listOf(
                CardSource(
                    sessionId = chatSessionId,
                    // Only claim a platform for the session we can actually attribute: a
                    // fallback (post-dash) session's platform is not knowable from live state.
                    platform = liveSessions.firstOrNull { it.sessionId == chatSessionId }?.platform,
                )
            )

            else -> emptyList()
        }

        val multiLive = liveSessions.size >= 2

        return BubbleSessionPresentation(
            displayedPlatform = displayedPlatform,
            followPlatform = followPlatform,
            chatSessionId = chatSessionId,
            cardSources = cardSources,
            switchablePlatforms = if (multiLive && mode != BubbleSessionMode.MERGED) {
                liveSessions.map { it.platform }
            } else {
                emptyList()
            },
            labelsVisible = multiLive,
            showLiveCard = mode == BubbleSessionMode.MERGED || displayedPlatform == followPlatform,
        )
    }
}
