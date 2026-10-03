package cloud.trotter.dashbuddy.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.core.data.census.CensusCredentialStore
import cloud.trotter.dashbuddy.core.data.census.CensusCredentialStore.Credential
import cloud.trotter.dashbuddy.core.data.census.CensusSpool
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.network.census.CensusApi
import cloud.trotter.dashbuddy.core.network.census.CensusApiFactory
import cloud.trotter.dashbuddy.core.network.census.CensusTransport
import cloud.trotter.dashbuddy.core.network.census.EnrolResult
import cloud.trotter.dashbuddy.core.network.census.UploadResult
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Periodic and manual unique names can overlap: serialize credential and batch ownership. */
@Singleton
class CensusUploadLock @Inject constructor() {
    val mutex = Mutex()
}

@HiltWorker
class CensusUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val preferences: DevSettingsRepository,
    private val credentials: CensusCredentialStore,
    private val spool: CensusSpool,
    private val apis: CensusApiFactory,
    private val scheduler: CensusUploadScheduler,
    private val stats: CensusUploadStats,
    private val uploadLock: CensusUploadLock,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = uploadLock.mutex.withLock {
        try {
            if (!canRun()) return@withLock Result.success()
            val api = apis.create(preferences.censusBaseUrl.first())
            val credential = loadCredential()
            val enrolled = if (credential.enrolled) Enrollment.Ready(credential) else enrol(api, credential)
            when (enrolled) {
                is Enrollment.Ready -> uploadBatches(api, enrolled.credential)
                is Enrollment.Stop -> enrolled.result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure()
        }
    }

    private suspend fun canRun(): Boolean {
        if (!BuildConfig.DEBUG || !preferences.censusUploadEnabled.first()) {
            Timber.tag(TAG).i("census disabled runs=1")
            return false
        }
        val deadline = preferences.nextAllowedAtMillis.first()
        if (System.currentTimeMillis() < deadline) {
            scheduler.deferUntil(deadline)
            Timber.tag(TAG).i("census deferred runs=1")
            return false
        }
        return true
    }

    private suspend fun loadCredential(): Credential = try {
        credentials.current() ?: credentials.pending() ?: credentials.mint()
    } catch (_: CensusCredentialStore.Unusable) {
        Timber.tag(TAG).i("census unusable credentials=1")
        credentials.mint()
    }

    private sealed interface Enrollment {
        data class Ready(val credential: Credential) : Enrollment
        data class Stop(val result: Result) : Enrollment
    }

    private suspend fun enrol(api: CensusTransport, initial: Credential): Enrollment {
        var credential = initial
        repeat(2) { attempt ->
            if (!preferences.censusUploadEnabled.first()) return Enrollment.Stop(Result.success())
            when (val result = api.enrol(credential.bearer(), BuildConfig.VERSION_NAME)) {
                is EnrolResult.Enrolled -> {
                    preferences.setCensusPolicy(result.policy.toString())
                    credentials.markEnrolled()
                    Timber.tag(TAG).i("census enrolled installs=1")
                    return Enrollment.Ready(credential.copy(enrolled = true))
                }
                EnrolResult.InstallExists -> {
                    // Only a locally pending identity can reach here; never replace an enrolled one.
                    if (attempt == 0) credential = credentials.mint()
                    else return Enrollment.Stop(failure())
                }
                EnrolResult.Revoked -> return Enrollment.Stop(revoke())
                is EnrolResult.RateLimited -> return Enrollment.Stop(defer(result.retryAfter))
                EnrolResult.Unauthorized, is EnrolResult.ServerUnavailable, is EnrolResult.TransportFailure ->
                    return Enrollment.Stop(failure())
                is EnrolResult.RequestRejected -> return Enrollment.Stop(failure())
            }
        }
        return Enrollment.Stop(failure())
    }

    private suspend fun uploadBatches(api: CensusTransport, credential: Credential): Result {
        val run = UploadRun()
        repeat(3) {
            if (!preferences.censusUploadEnabled.first()) return Result.success()
            val items = spool.take(100, 900 * 1024)
            if (items.isEmpty()) return Result.success()
            val batchId = spool.inFlight()?.batchId ?: CensusApi.batchId(items.map { it.fingerprint })
            uploadBatch(api, credential, items, batchId, run, depth = 0)?.let { return it }
        }
        return Result.success()
    }

    private class UploadRun {
        var resigned = false
    }

    /** Null means this batch is resolved and the run can continue, including remaining split halves. */
    private suspend fun uploadBatch(
        api: CensusTransport,
        credential: Credential,
        items: List<CensusSpool.Spooled>,
        batchId: String,
        run: UploadRun,
        depth: Int,
    ): Result? {
        if (!preferences.censusUploadEnabled.first()) return Result.success()
        val ids = items.map { it.id }
        spool.markInFlight(ids, batchId)
        when (val result = uploadWithResign(api, credential, items, batchId, run)) {
            is UploadResult.Accepted -> {
                removeBatch(ids)
                stats.uploaded.addAndGet(result.accepted.toLong())
                stats.duplicate.addAndGet(result.duplicate.toLong())
                stats.reject(result.rejected)
                Timber.tag(TAG).i("census uploaded=%d duplicate=%d rejected=%d", result.accepted, result.duplicate, result.rejected.values.sum())
            }
            UploadResult.Duplicate -> {
                removeBatch(ids)
                stats.duplicate.addAndGet(items.size.toLong())
                Timber.tag(TAG).i("census duplicate=%d", items.size)
            }
            UploadResult.PayloadTooLarge -> {
                if (items.size == 1) {
                    removeBatch(ids)
                    stats.oversized.incrementAndGet()
                    Timber.tag(TAG).w("census item oversized bytes=%d", items.single().itemJson.toByteArray(Charsets.UTF_8).size)
                } else {
                    // At most 100 items: seven halvings always reach singleton batches.
                    check(depth < MAX_SPLIT_DEPTH)
                    val middle = items.size / 2
                    for (half in listOf(items.take(middle), items.drop(middle))) {
                        val halfId = CensusApi.batchId(half.map { it.fingerprint })
                        uploadBatch(api, credential, half, halfId, run, depth + 1)?.let { return it }
                    }
                }
            }
            UploadResult.BadRequest -> {
                removeBatch(ids)
                stats.badRequest.incrementAndGet()
                Timber.tag(TAG).w("census batch bad_request items=%d", items.size)
            }
            is UploadResult.BatchQuality -> reject(ids, result.rejected)
            is UploadResult.BudgetExhausted -> return defer(result.retryAfterSeconds)
            is UploadResult.RateLimited -> return defer(result.retryAfter)
            is UploadResult.Unauthorized -> return Result.success()
            UploadResult.Revoked -> return revoke()
            is UploadResult.ServerUnavailable, is UploadResult.TransportFailure -> return failure()
        }
        return null
    }

    private suspend fun removeBatch(ids: List<String>) {
        spool.remove(ids)
        spool.clearInFlight()
    }

    private suspend fun uploadWithResign(
        api: CensusTransport,
        credential: Credential,
        items: List<CensusSpool.Spooled>,
        batchId: String,
        run: UploadRun,
    ): UploadResult {
        val json = items.map { it.itemJson }
        var result = api.uploadSkeletons(credential.bearer(), batchId, json)
        if (result is UploadResult.Unauthorized) {
            stats.uploadFailures.incrementAndGet()
            if (clockSkew(result) || run.resigned) return result
            if (!preferences.censusUploadEnabled.first()) return result
            run.resigned = true
            result = api.uploadSkeletons(credential.bearer(), batchId, json)
            if (result is UploadResult.Unauthorized) {
                stats.uploadFailures.incrementAndGet()
                clockSkew(result)
            }
        }
        return result
    }

    private fun clockSkew(result: UploadResult.Unauthorized): Boolean {
        val date = result.serverDateMillis ?: return false
        if (abs(date / 1000 - System.currentTimeMillis() / 1000) <= 300) return false
        Timber.tag(TAG).w("census clock skew")
        return true
    }

    private suspend fun reject(ids: List<String>, rejected: Map<String, Int>) {
        removeBatch(ids)
        stats.reject(rejected)
        Timber.tag(TAG).w("census batch rejected reasons=%s", rejected)
    }

    private suspend fun defer(seconds: Long): Result {
        val now = System.currentTimeMillis()
        // Saturate only at the representable clock limit, never shorten the server's Retry-After.
        val maximumSeconds = (Long.MAX_VALUE - 1 - now) / 1000
        val deadline = now + seconds.coerceIn(1, maximumSeconds) * 1000
        preferences.setNextAllowedAtMillis(deadline)
        scheduler.deferUntil(deadline)
        Timber.tag(TAG).i("census deferred runs=1")
        return Result.success()
    }

    private suspend fun revoke(): Result {
        // Switch off first, so even a failed disk wipe cannot leave uploads enabled.
        preferences.setCensusUploadEnabled(false)
        credentials.wipe()
        Timber.tag(TAG).w("census revoked")
        return Result.success()
    }

    private fun failure(): Result {
        stats.uploadFailures.incrementAndGet()
        Timber.tag(TAG).i("census upload failures=%d", stats.uploadFailures.get())
        return Result.retry()
    }

    companion object {
        private const val TAG = "Census"
        private const val MAX_SPLIT_DEPTH = 7
        fun schedule(context: Context) = CensusWorkScheduler.schedule(context)
    }
}
