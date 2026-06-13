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
     * @param contentHash content-bearing hash for UNKNOWN frames (null = no
     *   content available; the frame is then admitted — never silently lose a
     *   triage-able capture for want of a hash).
     * @return true if the frame should be captured (and, for known targets,
     *   forwarded onward).
     */
    fun admit(obs: Observation, contentHash: Int?): Boolean {
        // Null identity = never dedup (#366: clicks). It still CLEARS
        // lastIdentity below, preserving the old behavior where a click's
        // unique hash reset screen dedup (same screen re-forwards after it).
        val identity = obs.identity()
        if (identity != null && identity == lastIdentity) return false

        val target = (obs as? Observation.FlowObservation)?.target
        if (target != UNKNOWN_TARGET) {
            lastIdentity = identity
            return true
        }
        if (contentHash == null) return true
        return unknownSuppressor.shouldCapture(contentHash)
    }
}

/**
 * Rolling LRU seen-set + per-process cap for UNKNOWN captures (#360).
 * Re-seeing a recent hash refreshes its recency, so an animation alternating
 * between two frames suppresses both after the first sighting of each.
 */
internal class UnknownSuppressor(
    private val capacity: Int = 32,
    private val processCap: Int = 200,
) {
    private val seen = object : LinkedHashMap<Int, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>): Boolean =
            size > capacity
    }
    private var captured = 0
    private var capLogged = false

    @Synchronized
    fun shouldCapture(contentHash: Int): Boolean {
        if (seen.containsKey(contentHash)) {
            seen[contentHash] = Unit // refresh recency
            return false
        }
        if (captured >= processCap) {
            // No silent truncation: say so, once.
            if (!capLogged) {
                capLogged = true
                Timber.w(
                    "UNKNOWN capture cap (%d) reached — suppressing further unknown captures this process",
                    processCap,
                )
            }
            return false
        }
        seen[contentHash] = Unit
        captured++
        return true
    }
}
