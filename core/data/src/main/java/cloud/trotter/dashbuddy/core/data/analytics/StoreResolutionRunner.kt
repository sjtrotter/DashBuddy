package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.PickupRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.StoreEntity
import cloud.trotter.dashbuddy.domain.analytics.StoreResolution
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.StoreKeys
import cloud.trotter.dashbuddy.domain.state.StoreNameMatch
import cloud.trotter.dashbuddy.domain.state.StoreResolver
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The #159 read-model store-resolution runner — the **row adapter** over the pure
 * [StoreResolver.resolveAnchors] core (M5; the shadow `StoreChainProjector` is the sibling `Job`
 * adapter). Runs INSIDE the projector's batch transaction (resolve-from-rows, F1): for each triggered
 * job it reads the job's committed `pickup_records` (anchors + address) and `delivery_records`
 * (dropoff names + the row-persisted `payoutStoreForms` receipt evidence), resolves every anchor,
 * upserts the `stores` identity rows, and stamps the deterministic `storeKey` back onto the visit rows
 * (+ the offer↔job link). Because it reads committed rows only and the key is row-sourced + monotonic,
 * incremental fold ≡ from-zero refold (B1) and a re-run is a byte-identical no-op (L1).
 *
 * **Privacy:** store names/addresses are MERCHANT data — fine at rest, never logged at INFO+ (P7). No
 * network, no new capture. Customer hashes are never read here.
 */
internal class StoreResolutionRunner(private val dao: AnalyticsDao) {

    private val acceptedOutcome = AppEventType.OFFER_ACCEPTED.name

    /** Resolve one trigger: a job-scoped task ([StoreResolution.jobId] set) or a session-level task
     *  (jobId null ⇒ enumerate the session's jobs, L2). */
    suspend fun resolve(task: StoreResolution) {
        val sessionId = task.sessionId
        val taskJobId = task.jobId
        val jobIds: List<String> = when {
            taskJobId != null -> listOf(taskJobId)
            sessionId != null -> dao.jobIdsForSession(sessionId)
            else -> emptyList()
        }
        for (jobId in jobIds) resolveJob(jobId, task)
    }

    private suspend fun resolveJob(jobId: String, task: StoreResolution) {
        val pickups = dao.pickupRecordsForJob(jobId)
        // v1 (matches the field-verified shadow): anchors come from pickup rows; a job with delivery
        // rows but no pickup rows produces no store link.
        if (pickups.isEmpty()) return
        val anchors = pickups.map { it.storeName }.filter { it.isNotBlank() }.distinct()
        if (anchors.isEmpty()) return
        val deliveries = dao.deliveryRecordsForJob(jobId)

        val dropoffForms = deliveries.mapNotNull { it.storeName }
        // Union of the receipt store forms across the job's delivery rows, ORDER BY eventSequenceId
        // (the DAO orders them) — on the fielded single-receipt shape exactly one row is non-null (B2).
        val payoutForms = deliveries
            .flatMap { decodeForms(it.payoutStoreForms) }
            .map { StoreResolver.PayoutForm(it) }

        // Candidate offer(s) for the offer form + the offer↔job link (below). Exact via jobOfferHashes,
        // else the temporal nominee (F4). Their merchantName feeds the resolver's offerNameForm.
        val jobFirstAt = pickups.minOf { it.phaseStartedAt }
        val offerRows = candidateOffers(task, jobId, jobFirstAt)
        val offerForms = offerRows.mapNotNull { it.merchantName }

        val resolved = StoreResolver.resolveAnchors(anchors, offerForms, dropoffForms, payoutForms)
        val platform = pickups.first().platform

        // Build each anchor's deterministic storeKey + upsert the identity row (first-observed forms
        // preserved). No-op re-stamps are value-compared to avoid Room invalidation churn.
        val anchorKey = HashMap<String, String>()
        for (r in resolved) {
            val normChain = StoreKeys.normalizedChain(r.canonical)
            val runKey = StoreKeys.normalizeRunningKey(r.runningKey)
            val key = StoreKeys.storeKey(platform, normChain, runKey)
            anchorKey[r.canonical] = key
            upsertStore(key, platform, normChain, runKey, r, task.at, pickups)
        }

        // Stamp pickup rows by their own anchor (storeName == the anchor).
        for (pu in pickups) {
            val key = anchorKey[pu.storeName] ?: continue
            dao.stampPickupStoreKey(pu.eventSequenceId, key)
        }
        // Stamp delivery rows by best-matching their dropoff form to an anchor (the dropoff form differs
        // from the pickup form). The SQL carries the pin predicate (H1) + value guard.
        for (d in deliveries) {
            val store = d.storeName ?: continue
            val anchor = StoreNameMatch.bestMatch(anchors, store) ?: continue
            val key = anchorKey[anchor] ?: continue
            dao.stampDeliveryStoreKey(d.eventSequenceId, key)
        }

        // Offer↔job link (F4). Exact (hash) matches ARE this job's offers → stamp unconditionally.
        // The temporal nominee stamps ONLY on brand-token agreement (else it is a queued next-job
        // offer). storeKey = the anchor its merchantName best-matches (null for a no-match / multi-store).
        val exact = task.offerHashes.isNotEmpty()
        for (offer in offerRows) {
            val anchor = offer.merchantName?.let { StoreNameMatch.bestMatch(anchors, it) }
            if (!exact && anchor == null) continue
            dao.stampOfferLink(offer.eventSequenceId, anchor?.let { anchorKey[it] }, jobId)
        }
    }

    private suspend fun candidateOffers(
        task: StoreResolution,
        jobId: String,
        jobFirstAt: Long,
    ): List<OfferRecordEntity> {
        if (task.offerHashes.isNotEmpty()) return dao.offerRecordsByHashes(task.offerHashes)
        val sessionId = task.sessionId ?: return emptyList()
        return listOfNotNull(dao.nominateOfferForJob(sessionId, jobFirstAt, jobId, acceptedOutcome))
    }

    private suspend fun upsertStore(
        storeKey: String,
        platform: String,
        normalizedChain: String,
        runningKey: String?,
        r: StoreResolver.AnchorResolution,
        at: Long,
        pickups: List<PickupRecordEntity>,
    ) {
        val prior = dao.store(storeKey)
        val address = prior?.address
            ?: pickups.firstOrNull { it.storeName == r.canonical && it.storeAddress != null }?.storeAddress
        val store = StoreEntity(
            storeKey = storeKey,
            platform = platform,
            normalizedChain = normalizedChain,
            chainDisplay = prior?.chainDisplay ?: r.canonical,
            runningKey = runningKey, // consistent with the key by construction
            offerNameForm = prior?.offerNameForm ?: r.offerForm,
            pickupNameForm = prior?.pickupNameForm ?: r.canonical,
            payoutNameForm = prior?.payoutNameForm ?: r.payoutForm,
            address = address,
            firstSeenAt = prior?.firstSeenAt ?: at,
            lastSeenAt = maxOf(prior?.lastSeenAt ?: at, at),
        )
        // Value-compare so an idempotent re-run (same `at`) doesn't churn Room invalidation (L1).
        if (store != prior) dao.upsertStore(store)
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        private val formsSerializer = ListSerializer(String.serializer())

        /** Serialize the full receipt store-form set for `delivery_records.payoutStoreForms` (B1/B2). */
        fun encodeForms(forms: List<String>?): String? =
            forms?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(formsSerializer, it) }

        /** Decode the persisted receipt store-form set; empty on null/blank/malformed (fail-soft). */
        fun decodeForms(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                json.decodeFromString(formsSerializer, raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
