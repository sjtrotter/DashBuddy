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
 * upserts the `stores` identity rows, stamps the deterministic `storeKey` back onto the visit rows
 * (+ the offer↔job link), and — since #887 — deletes the identity row a monotonic key UPGRADE superseded
 * once nothing references it any more. Because it reads committed rows only and the key is row-sourced +
 * monotonic, incremental fold ≡ from-zero refold (B1) and a re-run is a byte-identical no-op (L1).
 * **#1000:** a job with NO anchor at all — no pickup rows, or pickup rows whose only storeName(s) are
 * blank/`UNKNOWN_STORE` (#733) — still stamps its EXACT offer↔job `linkedJobId` from the trigger's own
 * carried offer hashes — `storeKey` stays fail-null (no anchor to key with) — see [linkAnchorlessJob].
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
        // v1 (matches the field-verified shadow): anchors come from pickup rows. FIX 2: exclude the
        // UNKNOWN_STORE sentinel — a null-store pickup must not mint a `platform|unknown|` entity (the
        // fielded #733 NULL-store shape). Same idiom as :domain's DisplayNames (import the constant,
        // don't re-literal it). Empty `pickups` folds into an empty `anchors` here too, so the #1000
        // anchorless arm below covers BOTH shapes uniformly: no pickup row at all (a blown-through
        // pickup that never confirmed, the #615-class fail-null), and a pickup row whose only
        // storeName(s) are blank/UNKNOWN_STORE (#733).
        val anchors = pickups.map { it.storeName }
            .filter { it.isNotBlank() && it != UNKNOWN_STORE }.distinct()
        // No anchor ⇒ no storeKey, fail-null beats fail-wrong — #1000 rejected an offer-form fallback
        // for exactly this reason. But the DELIVERY_COMPLETED trigger's own offerHashes may still carry
        // the EXACT offer↔job link — stamp that link-only fact before giving up on the job (#1000).
        if (anchors.isEmpty()) {
            linkAnchorlessJob(jobId, task)
            return
        }
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
        val offerRows = candidateOffers(task, jobId, jobFirstAt, hasDeliveryRows = deliveries.isNotEmpty())
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

        // #887: every row whose PRIOR key this re-key replaces contributes that prior key as a
        // SUPERSESSION candidate. Collected only where the stamp actually lands (a downgrade-blocked or
        // pinned row keeps its key, so its key is not superseded), and swept at the end of the job.
        val superseded = LinkedHashSet<String>()

        // Stamp pickup rows by their own anchor (storeName == the anchor). FIX 6 monotonic backstop:
        // never re-stamp a keyed row down to a chain-only key of the same platform+chain.
        for (pu in pickups) {
            val key = anchorKey[pu.storeName] ?: continue
            if (isMonotonicDowngrade(pu.storeKey, key)) continue
            pu.storeKey?.takeIf { it != key }?.let { superseded += it }
            dao.stampPickupStoreKey(pu.eventSequenceId, key)
        }
        // Stamp delivery rows by best-matching their dropoff form to an anchor (the dropoff form differs
        // from the pickup form). The SQL carries the pin predicate (H1) + value guard.
        for (d in deliveries) {
            val store = d.storeName?.takeIf { it != UNKNOWN_STORE } ?: continue
            val anchor = StoreNameMatch.bestMatch(anchors, store) ?: continue
            val key = anchorKey[anchor] ?: continue
            if (isMonotonicDowngrade(d.storeKey, key)) continue
            // A PINNED row (H1 driver correction) is not re-keyed by the SQL, so its key is not superseded.
            if (d.storeKeyPinned == 0) d.storeKey?.takeIf { it != key }?.let { superseded += it }
            dao.stampDeliveryStoreKey(d.eventSequenceId, key)
        }

        // Offer↔job link (F4). Exact (hash) matches ARE this job's offers → stamp unconditionally.
        // The temporal nominee stamps ONLY on brand-token agreement (else it is a queued next-job
        // offer). storeKey = the anchor its merchantName best-matches (null for a no-match / multi-store).
        val exact = task.offerHashes.isNotEmpty()
        for (offer in offerRows) {
            val anchor = offer.merchantName?.let { StoreNameMatch.bestMatch(anchors, it) }
            if (!exact && anchor == null) continue
            val key = anchor?.let { anchorKey[it] }
            offer.storeKey?.takeIf { it != key }?.let { superseded += it }
            dao.stampOfferLink(offer.eventSequenceId, key, jobId)
        }

        // #887: drop each superseded identity row — STRICTLY LAST, so every re-stamp above (incl. the
        // offer links) is already committed and the reference count sees the post-re-key world.
        sweepSuperseded(superseded - anchorKey.values.toSet())
    }

    /**
     * #887 — delete the `stores` rows a monotonic key upgrade left behind. When one physical store mints
     * an address-tier key at pickup-close (`doordash|cvs|@23530`) and a later payout upgrades it to the
     * receipt-tier key (`doordash|cvs|3551`), the visit rows are re-keyed but the superseded identity row
     * used to survive as a zero-visit phantom (2 of 28 `stores` rows in the 07-27 pull). Same mechanism,
     * same fix, for the chain-only → keyed upgrade.
     *
     * **The guard fails toward KEEPING.** A superseded key is deleted ONLY when
     * [AnalyticsDao.storeKeyReferenceCount] proves that zero pickup / delivery / offer rows still point at
     * it. A row this re-key did not reach — ANOTHER job's visits to the same physical store that resolved
     * before the receipt existed, or an H1-pinned delivery row — keeps the entity alive; deleting it would
     * orphan a *referencing* row and blank its report card, which is strictly worse than a phantom.
     *
     * **Refold determinism.** The delete is a pure function of the committed rows at this point in the
     * seq-ordered resolution stream, so it replays identically. It also *converges* the two paths: an
     * incremental fold that split the job across a page boundary minted the intermediate row and now
     * deletes it; a from-zero refold that saw the receipt evidence in the same batch never minted it at
     * all. Terminal `stores` set: identical. (Before this, the raw table genuinely differed — the F1 test
     * documented it as an accepted read-invisible residual; it is no longer accepted.)
     *
     * **P7:** storeKeys are merchant-derived, so the kept-row observability is a DEBUG line (keys) plus a
     * merchant-free WARN — the same split as [isMonotonicDowngrade].
     */
    private suspend fun sweepSuperseded(candidates: Set<String>) {
        for (old in candidates) {
            val refs = dao.storeKeyReferenceCount(old)
            if (refs > 0) {
                Timber.tag(TAG).d("store-key supersession: keeping %s — %d row(s) still reference it", old, refs)
                Timber.tag(TAG).w("superseded store entity kept: %d row(s) the re-key did not reach still reference it", refs)
                continue
            }
            Timber.tag(TAG).d("store-key supersession: deleting unreferenced %s", old)
            dao.deleteStore(old)
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

    /**
     * #1000 — an anchorless job's ONLY resolvable fact is the exact offer↔job link the
     * `DELIVERY_COMPLETED` fold's own [StoreResolution.offerHashes] already carries — whether the job
     * has NO pickup rows at all, or pickup rows whose only storeName(s) are blank/`UNKNOWN_STORE`
     * (#733). Stamp it by REUSING [AnalyticsDao.stampOfferLink] with a null `storeKey` (F6a) — safe
     * because its claim guard (`linkedJobId IS NULL OR = :jobId`) blocks the call outright on any row
     * already claimed by a DIFFERENT job, so it can never null out that row's real `storeKey`; on a
     * fresh or same-job row the value guard is satisfied and only `linkedJobId` actually changes. `storeKey`
     * is deliberately never set here (no anchor exists to key with, and the design REJECTS minting one
     * from the offer's own parsed store form: a divergent `normalizedChain` would permanently split the
     * store entity, fail-wrong beats fail-null) — but if a REAL anchor later appears for this job, the
     * anchored resolution's own offer-link loop re-stamps the SAME row with a real key (see
     * [candidateOffers]'s `offerLinkCountForJob` narrowing, F4).
     *
     * **Preconditions, both load-bearing:**
     * - **Per-job trigger only** (F5): `task.jobId != jobId` bails immediately. A session-level trigger
     *   (`task.jobId == null`, [resolve]'s L2 enumeration branch) is relied on TODAY to carry no
     *   offerHashes at all (both session-level [StoreResolution] constructors — `foldDashStop` and
     *   [AnalyticsProjector]'s inferred-close trigger — pass `emptyList()`), which would already make
     *   this whole function a no-op below; this guard makes that safety STRUCTURAL rather than
     *   incidental, so a future hash-bearing session trigger fails closed instead of mass-claiming every
     *   job in the session against the same hash list.
     * - **Not yet outcome-resolved** (F3, #810 B2): [AnalyticsDao.unresolvedOfferRecordsByHashes]
     *   excludes any row whose `outcomeResolved` is already stamped — a resolved orphan must not gain a
     *   live `linkedJobId`, or the #975 est-vs-reality stack rule (drop BOTH siblings when 2+ accepted
     *   offers share one `linkedJobId`) would corrupt on a Tier-2 driver undo.
     *
     * **Content-hash multi-claim (F7).** `offerHash` is a CONTENT hash, not a row identity — a session
     * that accepts the same order twice with an identical parse returns MULTIPLE rows for ONE hash.
     * Claiming every unlinked row here would starve a sibling job's own exact-hash claim, and — because
     * whether the sibling row is even visible yet depends on which 500-event batch boundary the two
     * accepts land on — would break `incremental fold ≡ from-zero refold`, the whole premise the #1000
     * `PROJECTOR_VERSION` bump rests on. So this claims **at most one row per hash**, deterministically:
     * the earliest still-unlinked `eventSequenceId`. That choice is batch-boundary-invariant (whether
     * both sibling rows are already committed or the sibling hasn't landed yet, "the earliest currently
     * unlinked row" is always the same physical row), and the ANCHORED resolution's own offer-link loop
     * (unchanged by #1000, see [resolveJob]) already claims whichever sibling is left over once ITS job
     * gets a real pickup anchor — its per-row [AnalyticsDao.stampOfferLink] guard was already correct
     * for this shape.
     *
     * **Never the temporal nominee**: with no anchor there is no brand-token agreement check to run, and
     * stamping an unverified nominee is exactly the queued-next-job-offer mis-link F4 exists to block —
     * so this only ever consults [AnalyticsDao.unresolvedOfferRecordsByHashes] (the exact path).
     *
     * Idempotent + convergent: a hash whose only unclaimed row is gone (claimed by this job already, or
     * by another) is skipped, so incremental fold ≡ from-zero refold and a re-run is a no-op.
     */
    private suspend fun linkAnchorlessJob(jobId: String, task: StoreResolution) {
        if (task.jobId != jobId) return // F5: per-job triggers only — see the KDoc precondition above.
        val candidates = exactOfferCandidates(task, dao::unresolvedOfferRecordsByHashes)
        if (candidates.isEmpty()) return
        for ((_, rows) in candidates.groupBy { it.offerHash }) {
            if (rows.any { it.linkedJobId == jobId }) continue // already claimed by this job — no-op.
            val target = rows.filter { it.linkedJobId == null }.minByOrNull { it.eventSequenceId } ?: continue
            Timber.tag(TAG).d("anchorless job link: jobId=%s offerSeq=%d", jobId, target.eventSequenceId)
            dao.stampOfferLink(target.eventSequenceId, null, jobId)
        }
    }

    /**
     * #1000 F6b — the exact-hash offer lookup shared by the anchored resolution's exact arm
     * ([candidateOffers]) and the anchorless link arm ([linkAnchorlessJob]): given a trigger carrying a
     * session AND at least one carried offer hash, run [lookup] scoped to it. Returns emptyList() when
     * either is missing — temporal-fallback (for [candidateOffers]) / anchorless-no-op (for
     * [linkAnchorlessJob]) territory the caller decides.
     */
    private suspend fun exactOfferCandidates(
        task: StoreResolution,
        lookup: suspend (hashes: List<String>, sessionId: String, outcome: String) -> List<OfferRecordEntity>,
    ): List<OfferRecordEntity> {
        val sessionId = task.sessionId ?: return emptyList()
        if (task.offerHashes.isEmpty()) return emptyList()
        return lookup(task.offerHashes, sessionId, acceptedOutcome)
    }

    private suspend fun candidateOffers(
        task: StoreResolution,
        jobId: String,
        jobFirstAt: Long,
        hasDeliveryRows: Boolean,
    ): List<OfferRecordEntity> {
        // Exact path: the job's OWN accepted offer rows, scoped to session + accepted outcome (FIX 1a).
        if (task.offerHashes.isNotEmpty()) {
            return exactOfferCandidates(task, dao::offerRecordsByHashes)
        }
        val sessionId = task.sessionId ?: return emptyList()
        // #1000 F8: a job with NO delivery row is exactly the shape a re-mint (#499/#736) can leave
        // behind — the physical job's completion, and the offer's own carried hash, moved to a NEWER
        // jobId, leaving THIS job holding only pickup rows. Such a job's offer link would feed no #975
        // est-vs-reality join (that join keys on a DELIVERY row's own jobId) and is exactly the #810
        // orphan class already, so requiring a delivery row here closes off the construction where the
        // temporal nominee reaches PAST an offer this job's anchorless sibling already exact-claimed to
        // an OLDER, brand-coincidental one — evaluated against the FIX-1 family: no existing test or
        // comment relies on a delivery-less job's temporal nomination succeeding, so this closes the
        // construction outright rather than merely documenting it (see #1000's PR description).
        if (!hasDeliveryRows) return emptyList()
        // FIX 1b, narrowed by #1000 F4: only a storeKey-BEARING claim blocks a second nomination — a
        // link-only stamp from the anchorless arm must not stop THIS job's later anchored resolution
        // from nominating/backfilling storeKey once a late-arriving pickup gives it a real anchor (the
        // #526 D5 late-sweep shape); a DASH_STOP re-run of an already fully-keyed job still must not
        // claim a SECOND offer, which the storeKey-bearing count still catches exactly as before.
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
