package cloud.trotter.dashbuddy.domain.state

/**
 * Economics captured from one accepted offer, at accept time. A [Job] accumulates one of
 * these per accepted offer, so an **add-on** accepted mid-delivery adds to (not replaces) the
 * job's pay/time/distance.
 *
 * Fields are nullable because the offer's [cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation]
 * may not have completed by the moment of accept; we capture the richest available source
 * (evaluation first, then the parsed offer).
 */
data class AcceptedOfferEconomics(
    val offerHash: String?,
    /** Gross pay shown on the offer screen. */
    val payAmount: Double? = null,
    /** Net pay after operating costs (from the offer evaluation), when available. */
    val netPay: Double? = null,
    /** Estimated minutes for this offer's route. */
    val estMinutes: Double? = null,
    /** Quoted distance in miles. */
    val distanceMiles: Double? = null,
    val acceptedAt: Long,
)

/**
 * Accumulation of related [Task]s. A DoorDash batched delivery is one Job
 * (2–3 pickup Tasks + 2–3 dropoff Tasks). An Uber ride is one Job (1 pickup
 * + 1 dropoff). An **add-on** offer accepted mid-delivery appends to the active
 * Job (same [jobId]) rather than starting a new one — see [acceptedOffers].
 *
 * The offer's store names are **hints**, not sources of truth. The actual
 * authoritative store name comes from Task observations when the driver
 * enters `task:pickup:navigation`.
 */
data class Job(
    val jobId: String,
    val offerStoreHint: List<String>,
    val parentOfferHash: String?,
    /** Economics of every offer accepted into this job (≥1 once started; >1 for stacked/add-ons). */
    val acceptedOffers: List<AcceptedOfferEconomics> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val startedAt: Long,
) {
    /** Total accepted gross pay across all offers in this job. */
    val totalPayAmount: Double get() = acceptedOffers.sumOf { it.payAmount ?: 0.0 }

    /** Total accepted net pay (after operating costs) across all offers. */
    val totalNetPay: Double get() = acceptedOffers.sumOf { it.netPay ?: 0.0 }

    /** Total estimated minutes across all offers — the denominator for the job's blended $/hr. */
    val totalEstMinutes: Double get() = acceptedOffers.sumOf { it.estMinutes ?: 0.0 }

    /** Total quoted distance in miles across all offers. */
    val totalDistanceMiles: Double get() = acceptedOffers.sumOf { it.distanceMiles ?: 0.0 }

    /** Every offer hash that contributed to this job (the add-on chain). */
    val parentOfferHashes: List<String> get() = acceptedOffers.mapNotNull { it.offerHash }
}
