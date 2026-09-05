package cloud.trotter.dashbuddy.domain.location

import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import kotlin.math.max

/**
 * The odometer's per-fix admission gate — the ONE owner of "is this displacement real motion?".
 *
 * Pure and platform-agnostic by construction (principle 8): it takes two location fixes and nothing
 * else — no gig platform, no clock, no Android type. The repository ([cloud.trotter.dashbuddy]
 * `.core.data.location.OdometerRepository`) is the only caller and owns every side effect
 * (accrual, persistence, logging) at the edge (principle 1).
 *
 * **Why it exists.** The odometer used to accept ANY inter-fix displacement over 5 m straight into
 * the persisted cumulative total, in both directions:
 *  - **#1057 (over-count, catastrophic).** On 2026-09-03 a single spurious fused fix ~1,457 km away
 *    added **905.37 mi in 18.4 min** — an implied ~1,320 m/s. It froze `realizedMiles = 907.41` /
 *    `netProfit = −302.73` on one $22.95 delivery and left every session since 905 mi high. It
 *    logged nothing at any level.
 *  - **#918 (under-the-radar over-count).** Indoor multipath bounces a parked device by more than
 *    5 m every few fixes, so hours of sitting still accrue miles the car never drove.
 *
 * Both are the same missing bound, so both are judged here.
 *
 * **The reference rule.** [Verdict.Reject] and [Verdict.Ignore] both leave the caller's reference fix
 * where it is; only [Verdict.Accept] and [Verdict.Reference] move it. That asymmetry is load-bearing
 * twice over: a single teleporting fix therefore adds nothing AND leaves nothing behind (the next
 * good fix measures from the last *accepted* position, not from the bad one), and slow real creep —
 * a drive-through line inching forward inside its own error radius — still accumulates, because each
 * ignored step's displacement is measured cumulatively from the same reference until it clears the
 * floor.
 */
object OdometerFixPolicy {

    /**
     * Displacements at or below this are never motion. Today's floor, kept unchanged — it is the
     * lower bound of the jitter floor, which widens to the fixes' own reported accuracy (#918).
     */
    const val MIN_DELTA_METERS = 5.0

    /**
     * A fix whose reported horizontal accuracy is worse than this says nothing usable about motion,
     * so it is rejected outright and is not even allowed to become the reference (fail-null: a long
     * poor-signal stretch accrues nothing rather than accruing garbage). 50 m is well outside the
     * 10–30 m an indoor/urban-canyon fused fix typically reports (#918) while leaving an ordinary
     * open-sky fix (3–15 m) untouched.
     */
    const val MAX_ACCURACY_METERS = 50.0

    /**
     * ~150 mph. Nothing a delivery car does between two fixes. The fielded #1057 jump implied
     * ~1,320 m/s (905 mi in 18.4 min); a highway leg is 150 m in 5 s = 30 m/s, and even an aggressive
     * interstate stretch stays under 45 m/s — so this rejects the teleport with two orders of
     * magnitude of headroom over real driving.
     */
    const val MAX_SPEED_MPS = 67.0

    /**
     * Fallback bound when either fix carries no timestamp and an implied speed cannot be computed.
     * The fused provider's cadence is 2–5 s ([cloud.trotter.dashbuddy]`.core.location`
     * `.FusedLocationDataSource`), and 2 km in 5 s is already 400 m/s — so anything past this is a
     * jump no cadence explains, while a legitimate long gap between fixes stays admissible.
     */
    const val MAX_DELTA_WITHOUT_TIME_METERS = 2_000.0

    /** What the caller should do with a fix. */
    sealed interface Verdict {
        /** Real motion: add [deltaMeters] and make this fix the new reference. */
        data class Accept(val deltaMeters: Double) : Verdict

        /** No prior reference — adopt this fix as the reference, accrue nothing. */
        data object Reference : Verdict

        /**
         * Inside the fixes' own error radius (#918 jitter): accrue nothing and **keep** the existing
         * reference, so genuine creep past [floorMeters] still lands as one [Accept].
         */
        data class Ignore(val deltaMeters: Double, val floorMeters: Double) : Verdict

        /**
         * Implausible fix: accrue nothing and **keep** the existing reference, so the bad position
         * can never anchor the next measurement.
         */
        data class Reject(
            val deltaMeters: Double,
            val dtMillis: Long?,
            val impliedMps: Double?,
            val accuracyMeters: Double?,
            val reason: Reason,
        ) : Verdict
    }

    /** Why a fix was rejected. Numbers and enum names only — never a coordinate (principle 7). */
    enum class Reason {
        /** The fix's own reported accuracy is worse than [MAX_ACCURACY_METERS]. */
        POOR_ACCURACY,

        /** Displacement ÷ elapsed time exceeds [MAX_SPEED_MPS]. */
        IMPLAUSIBLE_SPEED,

        /** No usable elapsed time and the displacement exceeds [MAX_DELTA_WITHOUT_TIME_METERS]. */
        IMPLAUSIBLE_JUMP,

        /** The fix is not newer than the reference — elapsed time is zero or negative. */
        NON_MONOTONIC_TIME,
    }

    /**
     * Judge [next] against the last **accepted** fix [lastAccepted] (null on the first fix of a
     * tracking run). Total and side-effect free.
     */
    fun judge(lastAccepted: Coordinates?, next: Coordinates): Verdict {
        val nextAccuracy = next.accuracyMeters
        if (nextAccuracy != null && nextAccuracy > MAX_ACCURACY_METERS) {
            return Verdict.Reject(
                deltaMeters = lastAccepted?.let { next.distanceTo(it) } ?: 0.0,
                dtMillis = elapsedMillis(lastAccepted, next),
                impliedMps = null,
                accuracyMeters = nextAccuracy,
                reason = Reason.POOR_ACCURACY,
            )
        }

        if (lastAccepted == null) return Verdict.Reference

        val delta = next.distanceTo(lastAccepted)
        val floor = max(
            MIN_DELTA_METERS,
            max(nextAccuracy ?: 0.0, lastAccepted.accuracyMeters ?: 0.0),
        )
        if (delta <= floor) return Verdict.Ignore(deltaMeters = delta, floorMeters = floor)

        val dt = elapsedMillis(lastAccepted, next)
        if (dt != null) {
            if (dt <= 0L) {
                return Verdict.Reject(
                    deltaMeters = delta,
                    dtMillis = dt,
                    impliedMps = null,
                    accuracyMeters = nextAccuracy,
                    reason = Reason.NON_MONOTONIC_TIME,
                )
            }
            val impliedMps = delta / (dt / 1000.0)
            if (impliedMps > MAX_SPEED_MPS) {
                return Verdict.Reject(
                    deltaMeters = delta,
                    dtMillis = dt,
                    impliedMps = impliedMps,
                    accuracyMeters = nextAccuracy,
                    reason = Reason.IMPLAUSIBLE_SPEED,
                )
            }
            return Verdict.Accept(delta)
        }

        if (delta > MAX_DELTA_WITHOUT_TIME_METERS) {
            return Verdict.Reject(
                deltaMeters = delta,
                dtMillis = null,
                impliedMps = null,
                accuracyMeters = nextAccuracy,
                reason = Reason.IMPLAUSIBLE_JUMP,
            )
        }
        return Verdict.Accept(delta)
    }

    /** Elapsed millis between the two fixes, or null when either carries no timestamp. */
    private fun elapsedMillis(lastAccepted: Coordinates?, next: Coordinates): Long? {
        val last = lastAccepted?.timestampMs ?: return null
        val now = next.timestampMs ?: return null
        return now - last
    }
}
