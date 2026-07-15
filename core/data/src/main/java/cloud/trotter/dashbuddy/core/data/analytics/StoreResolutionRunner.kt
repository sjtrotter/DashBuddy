package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.StoreEntity
import cloud.trotter.dashbuddy.domain.analytics.StoreResolution
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.StoreKeys
import cloud.trotter.dashbuddy.domain.state.StoreNameMatch
import cloud.trotter.dashbuddy.domain.state.StoreResolver
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

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
        // FIX 2: exclude the UNKNOWN_STORE sentinel — a null-store pickup must not mint a
        // `platform|unknown|` entity (the fielded #733 NULL-store shape). Same idiom as :domain's
        // DisplayNames (import the constant, don't re-literal it).
        val anchors = pickups.map { it.storeName }
            .filter { it.isNotBlank() && it != UNKNOWN_STORE }.distinct()
        if (anchors.isEmpty()) return
        val deliveries = dao.deliveryRecordsForJob(jobId)

        val dropoffForms = deliveries.mapNotNull { it.storeName }
            .filter { it.isNotBlank() && it != UNKNOWN_STORE } // FIX 2: never feed the sentinel to the resolver
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

        // Per-anchor address (#773): FIRST-NON-NULL storeAddress by eventSequenceId (pickups are
        // ORDER BY eventSequenceId ASC), THEN fail — the address fallback key comes from that ONE row,
        // never from a later row whose extraction would succeed (first-non-null-then-fail, F-3). The
        // display-address seed (upsertStore) reads this SAME map, so an `@`-keyed card never shows a
        // different location's address than the one that keyed it.
        val anchorAddresses: Map<String, String?> = anchors.associateWith { anchor ->
            pickups.firstOrNull { it.storeName == anchor && it.storeAddress != null }?.storeAddress
        }

        val resolved = StoreResolver.resolveAnchors(anchors, offerForms, dropoffForms, payoutForms, anchorAddresses)
        // FIX 7: key platform = the trigger's own platform when it is REAL, else the pickup row's
        // platform. This makes `StoreResolution.platform` load-bearing: a DASH_STOP re-resolution of an
        // `_unknown`-started session (its DASH_STOP carried the corrected platform) upgrades the key to
        // the real platform and re-stamps, instead of keying under the stale `_unknown` pickup rows.
        val platform = task.platform.takeIf { it != Platform.Unknown.wire } ?: pickups.first().platform

        // Build each anchor's deterministic storeKey + upsert the identity row (first-observed forms
        // preserved). No-op re-stamps are value-compared to avoid Room invalidation churn.
        val anchorKey = HashMap<String, String>()
        for (r in resolved) {
            val normChain = StoreKeys.normalizedChain(r.canonical)
            // The #773 ladder SSOT: normalized receipt key ?: address `@`-key ?: null (chain-only).
            val runKey = r.resolvedRunningKey
            val key = StoreKeys.storeKey(platform, normChain, runKey)
            anchorKey[r.canonical] = key
            upsertStore(key, platform, normChain, runKey, r, anchorAddresses[r.canonical])
        }

        // Stamp pickup rows by their own anchor (storeName == the anchor). FIX 6 monotonic backstop:
        // never re-stamp a keyed row down to a chain-only key of the same platform+chain.
        for (pu in pickups) {
            val key = anchorKey[pu.storeName] ?: continue
            if (isMonotonicDowngrade(pu.storeKey, key)) continue
            dao.stampPickupStoreKey(pu.eventSequenceId, key)
        }
        // Stamp delivery rows by best-matching their dropoff form to an anchor (the dropoff form differs
        // from the pickup form). The SQL carries the pin predicate (H1) + value guard.
        for (d in deliveries) {
            val store = d.storeName?.takeIf { it != UNKNOWN_STORE } ?: continue
            val anchor = StoreNameMatch.bestMatch(anchors, store) ?: continue
            val key = anchorKey[anchor] ?: continue
            if (isMonotonicDowngrade(d.storeKey, key)) continue
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

    /**
     * FIX 6 monotonic-key backstop, ladder-aware (#773): true iff [newKey] would lower the row's key
     * TIER **within the same platform+chain** — receipt (`platform|chain|02426`) > address
     * (`platform|chain|@12125`) > chain-only (`platform|chain|`). Resolution is row-sourced and monotonic
     * by design, so this only fires on a rare evidence loss (a payout-less re-run losing a receipt line, or
     * a receipt-keyed row seeing only address evidence on a later pass); keeping the higher-tier value
     * guards against a silent regression.
     *
     * The **same platform+chain guard is load-bearing** (FIX 7): the platform upgrade
     * `_unknown|heb|` → `doordash|heb|` crosses platforms (different prefix), so it is NOT a downgrade and
     * stays allowed. The genuinely new #773 edge blocked here is receipt→address; `@`→chain-only and
     * receipt→chain-only were already blocked. The storeKey strings are merchant-derived, so the
     * observability note is DEBUG (keys) + a merchant-free WARN counter (P7).
     */
    // `internal` (not private) so the ladder can be unit-tested directly — the 5 tier transitions
    // (chain-only→@ upgrade, receipt→@ blocked, @→receipt allowed, platform-upgrade allowed, no-churn)
    // are pure key-string logic that would be awkward to reconstruct end-to-end through the fold.
    internal fun isMonotonicDowngrade(current: String?, newKey: String): Boolean {
        if (current == null) return false
        // Only compare within the same platform+chain prefix (everything up to the last '|').
        if (current.substringBeforeLast('|') != newKey.substringBeforeLast('|')) return false
        val downgrade = keyTier(newKey) < keyTier(current)
        if (downgrade) {
            Timber.tag(TAG).d("store-key: kept tier-%d %s over lower-tier recompute %s", keyTier(current), current, newKey)
            Timber.tag(TAG).w("store-key downgrade averted (monotonic backstop)")
        }
        return downgrade
    }

    /** The #773 running-key tier of a storeKey: chain-only (0) < address `@` (1) < receipt (2). */
    internal fun keyTier(key: String): Int {
        val seg = key.substringAfterLast('|')
        return when {
            seg.isEmpty() -> 0
            seg.startsWith("@") -> 1
            else -> 2
        }
    }

    private suspend fun candidateOffers(
        task: StoreResolution,
        jobId: String,
        jobFirstAt: Long,
    ): List<OfferRecordEntity> {
        val sessionId = task.sessionId ?: return emptyList()
        // Exact path: the job's OWN accepted offer rows, scoped to session + accepted outcome (FIX 1a).
        if (task.offerHashes.isNotEmpty()) {
            return dao.offerRecordsByHashes(task.offerHashes, sessionId, acceptedOutcome)
        }
        // FIX 1b: if the job already holds a claim (from an earlier trigger), don't nominate a second
        // offer — the exact path stays convergent because its scoped lookup returns the same rows, but a
        // DASH_STOP re-run of a temporal-linked job must not claim a different nearby offer.
        if (dao.offerLinkCountForJob(jobId) > 0) return emptyList()
        return listOfNotNull(dao.nominateOfferForJob(sessionId, jobFirstAt, jobId, acceptedOutcome))
    }

    private suspend fun upsertStore(
        storeKey: String,
        platform: String,
        normalizedChain: String,
        runningKey: String?,
        r: StoreResolver.AnchorResolution,
        selectedAddress: String?,
    ) {
        val prior = dao.store(storeKey)
        // The display-address seed uses the SAME row selection as the address key source (#773) — the
        // caller's first-non-null-by-eventSequenceId anchorAddresses entry — so an `@`-keyed store's
        // card can never display a different location's address than the one that keyed it.
        val address = prior?.address ?: selectedAddress
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
        )
        // Value-compare so an idempotent re-run doesn't churn Room invalidation (L1). first/last-seen are
        // no longer row columns (FIX 3) — they derive at read — so the store row is now truly stable
        // across per-trigger re-resolutions (no `lastSeenAt` bump on every trigger).
        if (store != prior) dao.upsertStore(store)
    }

    companion object {
        private const val TAG = "Analytics"
        private val json = Json { encodeDefaults = true }
        private val formsSerializer = ListSerializer(String.serializer())

        /** Serialize the full receipt store-form set for `delivery_records.payoutStoreForms` (B1/B2). */
        fun encodeForms(forms: List<String>?): String? =
            forms?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(formsSerializer, it) }

        /**
         * Decode the persisted receipt store-form set; empty on null/blank/malformed (fail-soft). A
         * malformed payload is WARN'd (FIX 6) instead of silently swallowed — the raw string is
         * merchant-derived, so the WARN carries the failure class only, never the payload (P7).
         */
        fun decodeForms(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                json.decodeFromString(formsSerializer, raw)
            } catch (_: Exception) {
                Timber.tag(TAG).w("payoutStoreForms decode failed; treating this row as no receipt evidence")
                emptyList()
            }
        }
    }
}
