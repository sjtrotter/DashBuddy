package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationIdentity
import cloud.trotter.dashbuddy.domain.pipeline.identity
import timber.log.Timber
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET

/**
 * Per-pipeline frame admission (#360). Combines the existing identity dedup
 * with content-bearing suppression for UNKNOWN frames.
 *
 * The two layers solve different problems:
 *  - **Identity dedup** (`lastIdentity`) suppresses the same RECOGNIZED screen
 *    re-observed back-to-back. It is deliberately NOT updated by UNKNOWN
 *    frames, so an UNKNOWN interlude does not reset identity dedup: an
 *    *unchanged* known screen STAYS suppressed across it (an UNKNOWN between two
 *    identical sightings isn't a change), while a known screen with a *new*
 *    identity still forwards. (A null-identity click clears `lastIdentity`, so
 *    the same screen forwards again after it.)
 *  - **UNKNOWN suppression** ([UnknownSuppressor]): UNKNOWN identity is
 *    contentless (constant target + ParsedFields.None), so consecutive
 *    *distinct* unknown screens were indistinguishable and every noisy frame
 *    re-captured — ~66% of May's capture volume. UNKNOWN frames are now keyed
 *    by a content hash (tree `stableHash` / notification `contentHash`) in a
 *    rolling seen-set, separate from `lastIdentity`.
 */
internal class FrameGate(
    private val unknownSuppressor: UnknownSuppressor = UnknownSuppressor(),
) {
    private var lastIdentity: ObservationIdentity? = null

    /**
     * Content hash of the last admitted *notification* sharing [lastIdentity]
     * (#619). Only ever set/read for [Observation.Notification] — screens
     * never touch it, so their dedup stays pure-identity.
     */
    private var lastNotificationContentHash: Int? = null

    /**
     * @param contentHash content-bearing hash for UNKNOWN frames (null = no
     *   content available; the frame is then admitted — never silently lose a
     *   triage-able capture for want of a hash) AND, for a recognized
     *   notification, the identity-dedup content discriminator (#619, see
     *   [mixNotificationContent]).
     * @param mixNotificationContent (#619) Parse-less notification rules
     *   (e.g. `new_order`) have a CONSTANT [ObservationIdentity] — target +
     *   `fieldsHash` (a hash of always-null fields) + modeHint never change —
     *   so two observably-distinct arrivals back-to-back (e.g. two
     *   different-store `new_order` pushes with nothing recognized between)
     *   collapsed into one at this layer. When true (the default) and [obs]
     *   is an [Observation.Notification], [contentHash] is mixed into the
     *   identity comparison so a distinct content hash still admits even
     *   when the identity is unchanged; an identical repost (same
     *   [contentHash]) still dedups. Screens are NEVER mixed — their
     *   identity is already content-bearing via the tree `stableHash` path
     *   upstream, so pure-identity dedup is unchanged for them. Callers can
     *   pass false to opt a specific notification out entirely (e.g. an
     *   ongoing heartbeat notification whose body may churn on every
     *   repost, where mixing would turn a benign repost into per-repost
     *   spam) — it then keeps the old pure-identity dedup.
     * @return true if the frame should be captured (and, for known targets,
     *   forwarded onward).
     */
    fun admit(obs: Observation, contentHash: Int?, mixNotificationContent: Boolean = true): Boolean {
        // Null identity = never dedup (#366: clicks). It still CLEARS
        // lastIdentity below, preserving the old behavior where a click's
        // unique hash reset screen dedup (same screen re-forwards after it).
        val identity = obs.identity()
        val isMixableNotification = mixNotificationContent && obs is Observation.Notification
        if (identity != null && identity == lastIdentity) {
            val contentChanged = isMixableNotification &&
                contentHash != null &&
                contentHash != lastNotificationContentHash
            if (!contentChanged) return false
        }

        val target = (obs as? Observation.FlowObservation)?.target
        if (target != UNKNOWN_TARGET) {
            lastIdentity = identity
            lastNotificationContentHash = if (isMixableNotification) contentHash else null
            return true
        }
        if (contentHash == null) return true
        return unknownSuppressor.shouldCapture(contentHash, obs.timestamp)
    }
}

/**
 * Rolling LRU seen-set + burst-window cap for UNKNOWN captures (#360/#597).
 * Re-seeing a recent hash refreshes its recency, so an animation alternating
 * between two frames suppresses both after the first sighting of each.
 *
 * The cap defends against a *flood* (a pathological screen producing endless
 * distinct hashes) — it is NOT a lifetime budget. The a11y process lives for
 * days, so a per-process cap quietly became "blind for the rest of the week"
 * (#597: hit 25 min into a dash, suppressed triage ~4 h incl. a whole support
 * flow). A flood by definition has no quiet gaps, so a [quietGapMs] stretch
 * with no UNKNOWN frames means the flood is over and the cap re-arms. Driven
 * by `obs.timestamp` (event time), never a wall clock.
 */
internal class UnknownSuppressor(
    private val capacity: Int = 32,
    private val burstCap: Int = 200,
    private val quietGapMs: Long = 30 * 60 * 1000L,
) {
    private val seen = object : LinkedHashMap<Int, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>): Boolean =
            size > capacity
    }
    private var captured = 0
    private var capLogged = false
    private var lastAttemptMs = 0L

    @Synchronized
    fun shouldCapture(contentHash: Int, nowMs: Long): Boolean {
        if (lastAttemptMs != 0L && nowMs - lastAttemptMs > quietGapMs && captured > 0) {
            Timber.d(
                "UNKNOWN capture cap re-armed after %d min quiet (%d captured last burst)",
                (nowMs - lastAttemptMs) / 60_000, captured,
            )
            captured = 0
            capLogged = false
        }
        // Never regress the anchor: notification postTime is not monotonic
        // (reposts/group summaries), and an out-of-order frame that pulled
        // lastAttemptMs backwards would fake a quiet gap mid-flood (adversarial
        // F1 on #597).
        lastAttemptMs = maxOf(lastAttemptMs, nowMs)
        if (seen.containsKey(contentHash)) {
            seen[contentHash] = Unit // refresh recency
            return false
        }
        if (captured >= burstCap) {
            // No silent truncation: say so, once per burst.
            if (!capLogged) {
                capLogged = true
                Timber.w(
                    "UNKNOWN capture cap (%d) reached — suppressing until a %d-min quiet gap",
                    burstCap, quietGapMs / 60_000,
                )
            }
            return false
        }
        seen[contentHash] = Unit
        captured++
        return true
    }
}
