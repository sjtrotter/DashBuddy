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
    /** The payout-line form — the running-key representation (`Target (02426)`), the #159 runningKey carrier. */
    val payoutName: String?,
    /** The RAW running key teased out of [payoutName]: the store number (`02426`) or area (`Alamo Ranch`). */
    val runningKey: String?,
    /** The address-derived `@`-prefixed key (#773), the chain-bare fallback; null unless the receipt was
     *  chain-bare AND the store's pickup address gave a leading street number. */
    val addressKey: String? = null,
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
 * The pure #159 store-resolution CORE (M5) — the one resolver logic shared by the read-model
 * projector (`AnalyticsProjector`, over committed DB rows) and the field-verified shadow projector
 * ([StoreChainProjector], over a live [Job]). It takes **neutral surface inputs** (no [Job], no DB
 * row), anchors on the pickup store names, and attaches each other surface form (offer / dropoff /
 * payout) to the anchor whose brand tokens it best matches ([StoreNameMatch]).
 *
 * Because it is one function of its inputs, the read-model resolve-from-rows path and the shadow
 * `Job` path produce identical resolutions from identical surfaces — that is what makes the
 * shadow log a valid field oracle for the persisted path.
 */
object StoreResolver {

    /** One payout store line — the store form ([type]) and its realized amount ([amount], shadow-only). */
    data class PayoutForm(val type: String, val amount: Double? = null)

    /** One anchor's resolved cross-surface forms. [runningKey] is the RAW receipt token ([StoreKeys]);
     *  [addressKey] is the `@`-prefixed address fallback (#773). [resolvedRunningKey] is the deterministic
     *  ladder both adapters key on. */
    data class AnchorResolution(
        val canonical: String,
        val offerForm: String?,
        val dropoffForm: String?,
        val payoutForm: String?,
        val runningKey: String?,
        val addressKey: String? = null,
        val realizedTip: Double?,
    ) {
        /**
         * The #773 precedence ladder (the ONE ladder SSOT both adapters key on): the
         * canonicalized **receipt** key ([StoreKeys.normalizeRunningKey], which strips any masquerading
         * `@`) wins; else the **address**-derived `@`-key ([StoreKeys.addressRunningKey], already
         * canonical, bypasses the receipt canonicalizer so its `@` survives); else null (chain-only).
         * Address-derived keys are provenance-marked by their leading `@` (F-1) — no extra column.
         */
        val resolvedRunningKey: String?
            get() = StoreKeys.normalizeRunningKey(runningKey) ?: addressKey
    }

    /**
     * Resolve every distinct pickup anchor against the offer / dropoff / payout surfaces. Each payout
     * line is assigned to its single best-matching anchor, so a store's [AnchorResolution.realizedTip]
     * sums ALL its matched lines (D4) and every store in a multi-store stack keys off its own line
     * (B2). A line matching no anchor is dropped (never mis-attributed). The running key is the first
     * non-null extraction across the store's matched lines; the store's [payoutForm] surfaces the line
     * that key came from.
     *
     * **Semantics note (FIX 13):** this per-line assignment + per-store tip-summing SUPERSEDES the older
     * per-anchor best-line semantics (the spec's D4 intent), so the pre-PR shadow logs are NOT
     * line-for-line comparable to this output.
     *
     * **Address fallback (#773):** [anchorAddresses] maps an anchor to its (caller-selected) store
     * address; when a receipt yields no running key the anchor's [AnchorResolution.addressKey] carries
     * the address-derived `@`-fragment ([StoreKeys.addressRunningKey]), and [AnchorResolution.resolvedRunningKey]
     * applies the `receiptKey ?: addressKey ?: null` ladder — so a chain-bare grocery payout keys per
     * location instead of collapsing every visit into one chain-only bucket.
     */
    fun resolveAnchors(
        anchors: List<String>,
        offerForms: List<String>,
        dropoffForms: List<String>,
        payoutForms: List<PayoutForm>,
        anchorAddresses: Map<String, String?> = emptyMap(),
    ): List<AnchorResolution> {
        val cleanAnchors = anchors.filter { it.isNotBlank() }.distinct()
        if (cleanAnchors.isEmpty()) return emptyList()
        val cleanOffers = offerForms.filter { it.isNotBlank() }
        val cleanDropoffs = dropoffForms.filter { it.isNotBlank() }
        // Assign each payout line to its single best-matching anchor (≥1 shared leading token).
        val payoutByAnchor: Map<String, List<PayoutForm>> = payoutForms
            .filter { it.type.isNotBlank() }
            .mapNotNull { form -> StoreNameMatch.bestMatch(cleanAnchors, form.type)?.let { it to form } }
            .groupBy({ it.first }, { it.second })
        return cleanAnchors.map { canonical ->
            val matched = payoutByAnchor[canonical].orEmpty()
            // The key comes from the first matched line that yields one; the payout form surfaces that
            // line (or, when none extracts a key, the first matched line — kept for audit).
            val keyForm = matched.firstOrNull { StoreKeys.extractRunningKey(it.type) != null }
                ?: matched.firstOrNull()
            AnchorResolution(
                canonical = canonical,
                offerForm = StoreNameMatch.bestMatch(cleanOffers, canonical),
                dropoffForm = StoreNameMatch.bestMatch(cleanDropoffs, canonical),
                payoutForm = keyForm?.type,
                runningKey = keyForm?.type?.let { StoreKeys.extractRunningKey(it) },
                addressKey = StoreKeys.addressRunningKey(anchorAddresses[canonical]),
                realizedTip = matched.mapNotNull { it.amount }.takeIf { it.isNotEmpty() }?.sum(),
            )
        }
    }
}

/**
 * Projects a completed (or in-flight) [Job] + its payout into the per-store [JobStoreChain] (#159 /
 * #526 Step 3 — order attribution). Pure and side-effect-free: it is *shadow* projection input —
 * the caller logs it for verification / corpus and does NOT mutate authoritative state during a dash.
 *
 * The [Job] adapter over [StoreResolver] (M5): it flattens the job's pickup/dropoff/offer surfaces +
 * the payout into the neutral resolver inputs, then re-attaches the job-only [StoreChainLink] field
 * (the per-store customer hashes). The read-model projector is the sibling row adapter over the SAME
 * [StoreResolver.resolveAnchors] core, so the persisted keys match this field-verified log.
 */
object StoreChainProjector {

    fun project(job: Job, payout: ParsedPay?): JobStoreChain {
        val pickupTasks = job.tasks.filter { it.phase == TaskPhase.PICKUP && !it.storeName.isNullOrBlank() }
        val pickupStores = pickupTasks.map { it.storeName!! }.distinct()
        // Per-anchor address (#773): the FIRST pickup of this store carrying a non-null address — the
        // Job-side analog of the runner's first-non-null-by-eventSequenceId row selection.
        val anchorAddresses: Map<String, String?> = pickupStores.associateWith { store ->
            pickupTasks.firstOrNull { it.storeName == store && it.storeAddress != null }?.storeAddress
        }
        val offerHints = job.offerStoreHint
        val dropoffStores = job.tasks
            .filter { it.phase == TaskPhase.DROPOFF && !it.storeName.isNullOrBlank() }
            .map { it.storeName!! }
        val payoutForms = payout?.customerTips.orEmpty()
            .filter { it.type.isNotBlank() }
            .map { StoreResolver.PayoutForm(it.type, it.amount) }

        val links = StoreResolver.resolveAnchors(pickupStores, offerHints, dropoffStores, payoutForms, anchorAddresses)
            .map { r ->
                val customerHashes = job.tasks
                    .filter { it.phase == TaskPhase.DROPOFF && it.storeName == r.canonical && it.customerNameHash != null }
                    .mapNotNull { it.customerNameHash }
                    .distinct()
                StoreChainLink(
                    canonicalStore = r.canonical,
                    offerName = r.offerForm,
                    dropoffName = r.dropoffForm,
                    payoutName = r.payoutForm,
                    runningKey = r.runningKey,
                    addressKey = r.addressKey,
                    customerHashes = customerHashes,
                    realizedTip = r.realizedTip,
                )
            }
        return JobStoreChain(jobId = job.jobId, links = links)
    }
}
