package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed payloads for the internal observation types (#366). Replaces the
 * `Map<String, Any?>` bags on Timeout/Loopback that bypassed both type safety
 * (live objects stuffed in, cast back out) and journal/replay fidelity
 * (#352's `InternalObsPayload` shim existed only to re-type them).
 *
 * Serializable as a sealed hierarchy, so the observation journal persists and
 * replays these losslessly with no per-key rebuilding.
 */
@Serializable
sealed interface ObservationPayload {

    /**
     * Context for an app-decided action deferred through a SETTLE_UI timeout
     * (#425) — e.g. EXPAND_EARNINGS waiting for the summary screen to settle
     * before tapping. Carries everything the timeout handler needs to emit
     * the immediate-fire `PerformRuleAction`.
     */
    @Serializable
    @SerialName("deferredAction")
    data class DeferredAction(
        /** `RuleAction` wire name. */
        val action: String,
        /** `Platform` wire name. */
        val platform: String,
        /** Rule that supplied the target binding — consent provenance (#422). */
        val ruleId: String? = null,
        val target: NodeRef,
    ) : ObservationPayload

    /**
     * Result of an async offer evaluation, looped back into the machine.
     * [offerHash] correlates the result to the offer it was computed FOR
     * (#345); [action] mirrors `evaluation.action` for stepper convenience.
     */
    @Serializable
    @SerialName("evaluationResult")
    data class EvaluationResult(
        val action: String,
        val offerHash: String? = null,
        val evaluation: OfferEvaluation? = null,
    ) : ObservationPayload

    /**
     * Identifies WHICH presented offer an [TimeoutType.OFFER_EXPIRY] timer belongs to (#438 B3 /
     * vet M5). The fire resolves BY [offerHash] within the owning region's `pendingOffers`, so
     * N>1 offers per platform each hold their own logical expiry even though the timer registry
     * slot is `(type, platform)`. Round-trips losslessly through the observation journal.
     */
    @Serializable
    @SerialName("offerExpiry")
    data class OfferExpiry(
        val offerHash: String,
    ) : ObservationPayload

    /**
     * WHICH arm of a region timer this fire is (#1054) — — [TimeoutType.GRACE_COMMIT],
     * [TimeoutType.MODE_RESUME_COMMIT], [TimeoutType.SESSION_PAY_SETTLE] and
     * [TimeoutType.SESSION_PAUSED_SAFETY]. The [OfferExpiry] idea applied to the pendings that
     * carry a deadline instead of a hash: **the fire resolves BY identity, not by arithmetic.**
     *
     * [wakeId] is a generation drawn from the owning region's
     * `PlatformRegion.wakeSeq`, minted fresh wherever a pending is created, replaced, re-based or
     * has its deadline moved. The lazy expiry matches it against the pending's CURRENT id, so a
     * fire is authoritative for exactly the pending that armed it, whatever its wall-clock stamp
     * says. Two consequences, and they are the reason this exists:
     *
     * - **A fire's own stamp no longer has to be trusted.** The timer is armed for
     *   `deadline - obs.timestamp` and fires stamped with `System.currentTimeMillis()`, so it lands
     *   ON the deadline in the ordinary case and BEFORE it after an NTP step-back. An NTP step
     *   between arm and fire changes the stamp, not the fact that the window elapsed.
     * - **A fire from a REPLACED pending is inert.** A stale timer cannot commit the pending that
     *   superseded it — which for the settle gate would be a mid-spin figure, and for a resume
     *   grace a mode flip nothing on screen supports. Round 4 carried the DEADLINE here and that
     *   was not enough: two successive pendings can hold the SAME deadline (after a clock step-back
     *   a replacement park computes the identical `now + settleWindow`), and the superseded wake
     *   then committed the replacement after essentially no time in its own window. A generation
     *   cannot collide.
     *
     * Before this, the expiries compared timestamps and every hole in the series (the strict `>`
     * that ignored an exact landing, the early-wake re-arm, the equality carve-out and its
     * narrowing to `(type, platform)`) was a patch on that one substitution of coincidence for
     * identity. Round-trips losslessly through the observation journal.
     */
    @Serializable
    @SerialName("graceWake")
    data class GraceWake(
        val wakeId: Long,
    ) : ObservationPayload
}
