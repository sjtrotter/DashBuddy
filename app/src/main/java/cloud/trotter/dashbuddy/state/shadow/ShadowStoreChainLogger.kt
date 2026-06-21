package cloud.trotter.dashbuddy.state.shadow

import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.StoreChainProjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #159 / #526 Step 3 — **shadow** projection of the store-entity chain (offer → pickup → dropoff →
 * payout) for each completed job. It only **logs** the projection (it never writes an authoritative
 * read model), so we can read the logs, verify the resolution against real dashes, and build corpus
 * before a future post-session projector is trusted to persist per-store statistics.
 *
 * Runs off the hot path: a passive collector on [StateManagerV2.state] that captures the active job
 * (with its resolved task stores) and the PostTask payout while a job is live, and emits one chain
 * line when the job clears. Debug-build only (a diagnostic, like captures). PII-safe by construction
 * — store names are merchant names, customers are logged as `sha256` prefixes only (no raw PII).
 */
@Singleton
class ShadowStoreChainLogger @Inject constructor(
    private val stateManager: StateManagerV2,
) {
    private data class Pending(val job: Job, val payout: ParsedPay?)

    fun start(scope: CoroutineScope) {
        scope.launch {
            val pending = HashMap<Platform, Pending>()
            stateManager.state.collect { state ->
                for ((platform, region) in state.regions.platforms) {
                    val job = region.activeJob
                    if (job != null) {
                        val prior = pending[platform]
                        // A back-to-back job switch (no idle between) — flush the prior job first.
                        if (prior != null && prior.job.jobId != job.jobId) logChain(prior.job, prior.payout)
                        // Keep the payout once PostTask surfaces it, even if a later frame clears it.
                        val payout = region.lastPostTaskFields?.parsedPay
                            ?: prior?.takeIf { it.job.jobId == job.jobId }?.payout
                        pending[platform] = Pending(job, payout)
                    } else {
                        pending.remove(platform)?.let { logChain(it.job, it.payout) }
                    }
                }
            }
        }
    }

    private fun logChain(job: Job, payout: ParsedPay?) {
        val chain = StoreChainProjector.project(job, payout)
        if (chain.links.isEmpty()) return
        val body = chain.links.joinToString("; ") { l ->
            buildString {
                append("[").append(l.canonicalStore).append("]")
                append(" offer=").append(l.offerName ?: "—")
                append(" dropoff=").append(l.dropoffName ?: "—")
                append(" payout=").append(l.payoutName ?: "—")
                append(" key=").append(l.runningKey ?: "—")
                l.realizedTip?.let { append(" tip=").append(it) }
                append(" custs=").append(l.customerHashes.map { it.take(6) })
            }
        }
        Timber.tag("ShadowProjector").i("job %s store-chain (%d): %s", chain.jobId, chain.links.size, body)
    }
}
