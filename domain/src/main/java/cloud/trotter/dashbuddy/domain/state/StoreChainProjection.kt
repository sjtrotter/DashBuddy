package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay

/**
 * One store entity's appearance across the surfaces of a job — the offer order, its pickup, its
 * dropoff(s), and the payout line — correlated by brand tokens ([StoreNameMatch]). This is the
 * runtime, in-memory shape of the #159 store entity: the **chain** that links DoorDash's different
 * representations of the same physical store, so a later post-session projector can resolve and
 * accumulate per-store statistics. All customer data stays hashed (privacy: store names are not PII).
 */
data class StoreChainLink(
    /** The authoritative store name from the pickup screen (the anchor / canonical brand form). */
    val canonicalStore: String,
    /** The offer-card form of this store (`Target`), if the offer named it. */
    val offerName: String?,
    /** The dropoff-card form, if any dropoff resolved to this store. */
    val dropoffName: String?,
    /** The payout-line form — the running-key representation (`Target (02426)`), the #159 doordash_key carrier. */
    val payoutName: String?,
    /** The running key teased out of [payoutName]: the store number (`02426`) or area (`Alamo Ranch`). */
    val runningKey: String?,
    /** Hashed customers delivered to this store on this job (dedup-safe; never raw PII). */
    val customerHashes: List<String>,
    /** Realized tip attributed to this store from the payout, when present. */
    val realizedTip: Double?,
)

/** The full store chain for one job — every order/store linked across offer → pickup → dropoff → payout. */
data class JobStoreChain(
    val jobId: String,
    val links: List<StoreChainLink>,
)

/**
 * Projects a completed (or in-flight) [Job] + its payout into the per-store [JobStoreChain] (#159 /
 * #526 Step 3 — order attribution). Pure and side-effect-free: it is *shadow* projection input —
 * the caller logs it for verification / corpus and does NOT mutate authoritative state during a dash.
 *
 * Anchors on the job's **pickups** (the authoritative store names) and attaches each other surface
 * form to the pickup whose brand tokens it best matches, so the offer/dropoff/payout running-key
 * forms (which don't string-match) line up with the right order. A surface form that matches no
 * pickup is dropped rather than mis-attributed.
 */
object StoreChainProjector {

    fun project(job: Job, payout: ParsedPay?): JobStoreChain {
        // The pickups are the anchors — one store-entity link per distinct pickup store.
        val pickupStores = job.tasks
            .filter { it.phase == TaskPhase.PICKUP && !it.storeName.isNullOrBlank() }
            .map { it.storeName!! }
            .distinct()

        val offerHints = job.offerStoreHint.filter { it.isNotBlank() }
        val dropoffStores = job.tasks
            .filter { it.phase == TaskPhase.DROPOFF && !it.storeName.isNullOrBlank() }
            .map { it.storeName!! }
        val payoutItems = payout?.customerTips.orEmpty().filter { it.type.isNotBlank() }

        val links = pickupStores.map { canonical ->
            val offerName = StoreNameMatch.bestMatch(offerHints, canonical)
            val dropoffName = StoreNameMatch.bestMatch(dropoffStores, canonical)
            val payoutItem = payoutItems
                .map { it to StoreNameMatch.sharedLeadingTokens(it.type, canonical) }
                .filter { it.second >= 1 }
                .maxByOrNull { it.second }
                ?.first
            val customerHashes = job.tasks
                .filter { it.phase == TaskPhase.DROPOFF && it.storeName == canonical && it.customerNameHash != null }
                .mapNotNull { it.customerNameHash }
                .distinct()
            StoreChainLink(
                canonicalStore = canonical,
                offerName = offerName,
                dropoffName = dropoffName,
                payoutName = payoutItem?.type,
                runningKey = payoutItem?.type?.let { extractRunningKey(it) },
                customerHashes = customerHashes,
                realizedTip = payoutItem?.amount,
            )
        }
        return JobStoreChain(jobId = job.jobId, links = links)
    }

    /** Tease the DoorDash running key out of a payout store form: the `(02426)` number or the ` - Area` suffix. */
    private fun extractRunningKey(payoutName: String): String? {
        Regex("""\((\d{2,6})\)\s*$""").find(payoutName)?.let { return it.groupValues[1] }
        val dash = payoutName.indexOf(" - ")
        if (dash >= 0) return payoutName.substring(dash + 3).trim().ifEmpty { null }
        return null
    }
}
