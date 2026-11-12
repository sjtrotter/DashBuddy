package cloud.trotter.log.gig.domain.state

import kotlinx.serialization.Serializable

/** How a presentation left the screen before it entered the pool (#1119 S2). Names the shift-end description. */
@Serializable
enum class PoolEntryVia { FRAME, REPLACED, SUPERSEDED_BY_DIRECT, EXPIRY_TIMER }

/**
 * #1119 S2: an offer that left presentation with no confirmed decline and no accept click, waiting for work at its
 * store. Lives in [PlatformRegion.offerPool] until a link (`OFFER_ACCEPTED`) or the shift end (`OFFER_NOT_TAKEN`, or
 * `OFFER_DECLINED` when [demoted]): exactly one outcome per instance.
 */
@Serializable
data class PooledOffer(
    /** The presentation as it left, `targets` emptied (no tap can reach it any more). */
    val offer: PendingOffer,
    /** `obs.timestamp` of the observation that took it off the screen. */
    val leftAt: Long,
    val via: PoolEntryVia,
    /** The session it was presented in; null when none was live. */
    val sessionId: String?,
    /**
     * `StoreKeys.normalizedChain` of the stores this offer can be matched on: every order's store for a STANDALONE
     * card; for an ADD_ON card only the stores new to the job it was presented over (`AddOnStops.newStores`), all of
     * them when no job was live.
     */
    val candidateStoreKeys: Set<String>,
    /** Jobs this entry tied on (two or more equal candidates); carried onto its shift-end outcome. */
    val ambiguousJobIds: List<String> = emptyList(),
    /**
     * The confirm-decline sheet was seen shortly before this offer left by a frame with time on its countdown, or
     * stood until the expiry timer fired (#1610), and no card frame came after the sighting (#1119 council round 6).
     * A demoted entry is never a match candidate; it can only turn a same-store link into a tie, and it ends the
     * shift `OFFER_DECLINED` marked as inferred.
     */
    val demoted: Boolean = false,
) {
    val instanceKey: String get() = offer.instanceKey
}

/**
 * #1119 S2 (council round 6): a store-less first work frame held while the pool waits for the frame that names the
 * store. Recorded instead of minting a bare job; released into a link by the first store-bearing task frame inside
 * the hold, or into today's bare mint (replayed from this frame) at the cap or on any non-task frame.
 */
@Serializable
data class HeldWorkFrame(
    /** The held frame's `obs.timestamp`; the linked task starts here. */
    val at: Long,
    /** The held frame's flow and the region's acted flow before it, so a bare release replays the frame exactly. */
    val flow: Flow,
    val prevFlow: Flow,
    val ruleId: String?,
    val parsed: ParsedFields,
    /** Task-flow frames seen in the hold, the held frame included. */
    val frames: Int = 1,
    /** `at` plus the platform's hold cap: the hold's `STORELESS_HOLD` wake fires here; recovery drops a hold past it. */
    override val deadline: Long,
    /** The generation of this hold's wake ([PlatformRegion.mintWakeId], installed by `heldWorkWindow`). */
    override val wakeId: WakeId,
) : WakeBearing
