package cloud.trotter.dashbuddy.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.core.data.census.CensusCredentialStore
import cloud.trotter.dashbuddy.core.data.census.CensusCredentialStore.Credential
import cloud.trotter.dashbuddy.core.data.census.CensusSpool
import cloud.trotter.dashbuddy.core.data.census.PersistentHealthSink
import cloud.trotter.dashbuddy.domain.census.HealthLedger
import cloud.trotter.dashbuddy.core.network.census.HealthResult
import cloud.trotter.dashbuddy.core.data.census.CensusUploadLock
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.network.census.CensusApi
import cloud.trotter.dashbuddy.core.network.census.CensusApiFactory
import cloud.trotter.dashbuddy.core.network.census.CensusTransport
import cloud.trotter.dashbuddy.core.network.census.EnrolResult
import cloud.trotter.dashbuddy.core.network.census.UploadResult
import cloud.trotter.dashbuddy.domain.census.CensusLastRun
import cloud.trotter.dashbuddy.domain.census.CensusRunOutcome
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

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
    private val healthSink: PersistentHealthSink,
) : CoroutineWorker(appContext, workerParams) {
    private class RunRecord {
        var outcome: CensusRunOutcome = CensusRunOutcome.FAILURE
        var detail: Int? = null
        var accepted = 0
        var duplicate = 0
        var rejected = 0
        var stale = 0
        var healthPosted = 0
        var healthRejected = 0
        fun set(outcome: CensusRunOutcome, detail: Int? = null) { this.outcome = outcome; this.detail = detail }
    }

    override suspend fun doWork(): Result = uploadLock.mutex.withLock {
        val record = RunRecord()
        val result = try {
            if (!canRun(record)) {
                Result.success()
            } else {
                val api = apis.create(preferences.censusBaseUrl.first())
                val credential = loadCredential()
                val enrolled = if (credential.enrolled) Enrollment.Ready(credential) else enrol(api, credential, record)
                when (enrolled) {
                    is Enrollment.Ready -> postHealth(api, enrolled.credential, record)
                        ?: uploadBatches(api, enrolled.credential, record)
                    is Enrollment.Stop -> enrolled.result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: CensusCredentialStore.Transient) {
            record.set(CensusRunOutcome.KEYSTORE_TRANSIENT)
            stats.keystoreTransient.incrementAndGet()
            Timber.tag(TAG).i("census keystoreTransient=%d", stats.keystoreTransient.get())
            Result.retry()
        } catch (_: Exception) {
            failure(record)
        }
        // A consent-off no-op writes nothing: the terminal outcome it would overwrite (revoked, enrol_rejected…) is
        // exactly what the status line exists to explain.
        if (record.outcome != CensusRunOutcome.DISABLED) {
            try {
                preferences.setCensusLastRun(CensusLastRun(System.currentTimeMillis(), record.outcome, record.detail))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Timber.tag(TAG).w("census status write failed")
            }
        }
        result
    }

    private suspend fun canRun(record: RunRecord): Boolean {
        if (!BuildConfig.DEBUG || !preferences.censusUploadEnabled.first()) {
            record.set(CensusRunOutcome.DISABLED)
            Timber.tag(TAG).d("census disabled runs=1")
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

    private suspend fun enrol(api: CensusTransport, initial: Credential, record: RunRecord): Enrollment {
        var credential = initial
        repeat(2) { attempt ->
            if (!preferences.censusUploadEnabled.first()) {
                record.set(CensusRunOutcome.DISABLED)
                return Enrollment.Stop(Result.success())
            }
            when (val result = api.enrol(credential.bearer(), BuildConfig.VERSION_NAME)) {
                is EnrolResult.Enrolled -> {
                    preferences.setCensusPolicy(result.policy)
                    credentials.markEnrolled()
                    record.set(CensusRunOutcome.ENROLLED)
                    Timber.tag(TAG).i("census enrolled installs=1")
                    return Enrollment.Ready(credential.copy(enrolled = true))
                }
                EnrolResult.InstallExists -> {
                    // Only a locally pending identity can reach here; never replace an enrolled one.
                    if (attempt == 0) credential = credentials.mint()
                    else {
                        val stopped = failure(record)
                        record.set(CensusRunOutcome.ENROL_CONFLICT)
                        return Enrollment.Stop(stopped)
                    }
                }
                EnrolResult.Revoked -> return Enrollment.Stop(revoke(record))
                is EnrolResult.RateLimited -> return Enrollment.Stop(defer(record, result.retryAfter))
                EnrolResult.Unauthorized -> return Enrollment.Stop(enrolRejected(record, 401))
                is EnrolResult.RequestRejected -> return Enrollment.Stop(enrolRejected(record, result.status))
                is EnrolResult.ServerUnavailable, is EnrolResult.TransportFailure ->
                    return Enrollment.Stop(failure(record))
            }
        }
        return Enrollment.Stop(failure(record))
    }

    /** Closed days are singleton UPSERTs; only the exact submitted revision can be acknowledged. */
    private suspend fun postHealth(api: CensusTransport, credential: Credential, record: RunRecord): Result? {
        val ledger = healthSink.snapshot()
        val todayUtc = LocalDate.now(ZoneOffset.UTC)
        val candidates = ledger.closedRowsToPost(todayUtc.toString(), todayUtc.minusDays(7).toString()).take(3)
        for ((key, row) in candidates) {
            if (!preferences.censusUploadEnabled.first()) {
                record.set(CensusRunOutcome.DISABLED)
                return Result.success()
            }
            val json = HealthLedger.reportJson(key, row, BuildConfig.VERSION_NAME, rulesetVersion = "dev")
            if (!HealthLedger.reportFits(json)) {
                healthSink.apply { if (it.generation == ledger.generation) it.markRefused(key, row.revision) else it }
                stats.healthOversized.incrementAndGet()
                Timber.tag(TAG).w("census health oversized day=%s", key.day)
                continue
            }
            val reasons = when (val result = api.postHealth(credential.bearer(), listOf(json))) {
                is HealthResult.Accepted -> {
                    if (result.accepted != 1) result.rejected else {
                        healthSink.apply { if (it.generation == ledger.generation) it.markPosted(key, row.revision) else it }
                        stats.healthPosted.incrementAndGet()
                        record.healthPosted++
                        continue
                    }
                }
                is HealthResult.BatchQuality -> result.rejected
                HealthResult.BadRequest -> mapOf("bad_request" to 1)
                HealthResult.PayloadTooLarge -> mapOf("batch_too_large" to 1)
                is HealthResult.RateLimited, is HealthResult.BudgetExhausted,
                is HealthResult.Unauthorized, is HealthResult.ServerUnavailable,
                is HealthResult.TransportFailure -> return null
                HealthResult.Revoked -> return revoke(record)
            }
            healthSink.apply { if (it.generation == ledger.generation) it.markRefused(key, row.revision) else it }
            stats.healthRejected.incrementAndGet()
            record.healthRejected++
            Timber.tag(TAG).w("census health rejected reasons=%s", reasons)
        }
        return null
    }

    private suspend fun uploadBatches(api: CensusTransport, credential: Credential, record: RunRecord): Result {
        val deadline = preferences.nextAllowedAtMillis.first()
        val now = System.currentTimeMillis()
        if (now < deadline) {
            record.set(CensusRunOutcome.DEFERRED, ((deadline - now) / 1000).toInt().coerceAtLeast(0))
            scheduler.deferUntil(deadline)
            Timber.tag(TAG).i("census deferred runs=1")
            return Result.success()
        }
        val run = UploadRun()
        repeat(3) {
            if (!preferences.censusUploadEnabled.first()) {
                record.set(CensusRunOutcome.DISABLED)
                return Result.success()
            }
            val pending = spool.take(100, 900 * 1024)
            if (pending.isEmpty()) {
                summarize(record, spoolEmpty = true)
                return Result.success()
            }
            val oldestDay = LocalDate.now(ZoneOffset.UTC).minusDays(7)
            val (stale, items) = pending.partition {
                LocalDate.parse(Json.parseToJsonElement(it.itemJson).jsonObject.getValue("day").jsonPrimitive.content) < oldestDay
            }
            if (stale.isNotEmpty()) {
                spool.remove(stale.map { it.id })
                spool.clearInFlight() // The surviving set needs a new batch ID and signature.
                stats.stale.addAndGet(stale.size.toLong())
                record.stale += stale.size
            }
            if (items.isEmpty()) return@repeat
            val batchId = spool.inFlight()?.batchId ?: CensusApi.batchId(items.map { it.fingerprint })
            uploadBatch(api, credential, items, batchId, run, record, depth = 0)?.let { return it }
        }
        // Three batches exhausted without ever seeing an empty spool: never claim emptiness.
        summarize(record, spoolEmpty = false)
        return Result.success()
    }

    /** The run's one token: what moved items wins; `enrolled` survives an otherwise idle run so a fresh identity is visible. */
    private fun summarize(record: RunRecord, spoolEmpty: Boolean) = with(record) {
        when {
            accepted > 0 -> set(CensusRunOutcome.UPLOADED, accepted)
            duplicate > 0 -> set(CensusRunOutcome.DUPLICATE, duplicate)
            rejected > 0 -> set(CensusRunOutcome.REJECTED, rejected)
            stale > 0 -> set(CensusRunOutcome.STALE_REMOVED, stale)
            outcome == CensusRunOutcome.OVERSIZED || outcome == CensusRunOutcome.BAD_REQUEST -> Unit
            healthPosted > 0 -> set(CensusRunOutcome.HEALTH_POSTED, healthPosted)
            healthRejected > 0 -> set(CensusRunOutcome.HEALTH_REJECTED, healthRejected)
            outcome == CensusRunOutcome.ENROLLED -> Unit
            spoolEmpty -> set(CensusRunOutcome.SPOOL_EMPTY)
            else -> Unit
        }
    }

    private class UploadRun {
        var resigned = false
        var clockSkew = false
    }

    /** Null means this batch is resolved and the run can continue, including remaining split halves. */
    private suspend fun uploadBatch(
        api: CensusTransport,
        credential: Credential,
        items: List<CensusSpool.Spooled>,
        batchId: String,
        run: UploadRun,
        record: RunRecord,
        depth: Int,
    ): Result? {
        if (!preferences.censusUploadEnabled.first()) {
            record.set(CensusRunOutcome.DISABLED)
            return Result.success()
        }
        val ids = items.map { it.id }
        spool.markInFlight(ids, batchId)
        when (val result = uploadWithResign(api, credential, items, batchId, run)) {
            is UploadResult.Accepted -> {
                removeBatch(ids)
                record.accepted += result.accepted
                record.duplicate += result.duplicate
                record.rejected += result.rejected.values.sum()
                stats.uploaded.addAndGet(result.accepted.toLong())
                stats.duplicate.addAndGet(result.duplicate.toLong())
                stats.reject(result.rejected)
                Timber.tag(TAG).i("census uploaded=%d duplicate=%d rejected=%d", result.accepted, result.duplicate, result.rejected.values.sum())
            }
            UploadResult.Duplicate -> {
                removeBatch(ids)
                record.duplicate += items.size
                stats.duplicate.addAndGet(items.size.toLong())
                Timber.tag(TAG).i("census duplicate=%d", items.size)
            }
            UploadResult.PayloadTooLarge -> {
                if (items.size == 1) {
                    removeBatch(ids)
                    record.set(CensusRunOutcome.OVERSIZED)
                    stats.oversized.incrementAndGet()
                    Timber.tag(TAG).w("census item oversized bytes=%d", items.single().itemJson.toByteArray(Charsets.UTF_8).size)
                } else {
                    // At most 100 items: seven halvings always reach singleton batches.
                    check(depth < MAX_SPLIT_DEPTH)
                    val middle = items.size / 2
                    for (half in listOf(items.take(middle), items.drop(middle))) {
                        val halfId = CensusApi.batchId(half.map { it.fingerprint })
                        uploadBatch(api, credential, half, halfId, run, record, depth + 1)?.let { return it }
                    }
                }
            }
            UploadResult.BadRequest -> {
                removeBatch(ids)
                record.set(CensusRunOutcome.BAD_REQUEST)
                stats.badRequest.incrementAndGet()
                Timber.tag(TAG).w("census batch bad_request items=%d", items.size)
            }
            is UploadResult.BatchQuality -> {
                reject(ids, result.rejected)
                record.rejected += result.rejected.values.sum()
            }
            is UploadResult.BudgetExhausted -> return defer(record, result.retryAfterSeconds)
            is UploadResult.RateLimited -> return defer(record, result.retryAfter)
            is UploadResult.Unauthorized -> {
                // An unresigned, non-skew 401 means the consent check short-circuited the re-sign: not an identity problem.
                record.set(when {
                    run.clockSkew -> CensusRunOutcome.CLOCK_SKEW
                    !run.resigned -> CensusRunOutcome.DISABLED
                    else -> CensusRunOutcome.UNAUTHORIZED
                })
                if (credential.enrolled && run.resigned && !run.clockSkew) {
                    stats.unauthorized.incrementAndGet()
                    if (unauthorizedWarnedFor.getAndSet(credential.installId) != credential.installId) {
                        Timber.tag(TAG).w("census unauthorized: identity unknown to server")
                    }
                }
                return Result.success()
            }
            UploadResult.Revoked -> return revoke(record)
            is UploadResult.ServerUnavailable, is UploadResult.TransportFailure -> return failure(record)
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
            run.clockSkew = clockSkew(result)
            if (run.clockSkew || run.resigned) return result
            if (!preferences.censusUploadEnabled.first()) return result
            run.resigned = true
            result = api.uploadSkeletons(credential.bearer(), batchId, json)
            if (result is UploadResult.Unauthorized) {
                stats.uploadFailures.incrementAndGet()
                run.clockSkew = clockSkew(result)
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

    private suspend fun defer(record: RunRecord, seconds: Long): Result {
        val now = System.currentTimeMillis()
        val coercedSeconds = seconds.coerceIn(1, 86_400)
        record.set(CensusRunOutcome.DEFERRED, coercedSeconds.toInt())
        val deadline = now + coercedSeconds * 1000
        preferences.setNextAllowedAtMillis(deadline)
        scheduler.deferUntil(deadline)
        Timber.tag(TAG).i("census deferred runs=1")
        return Result.success()
    }

    private suspend fun revoke(record: RunRecord): Result {
        record.set(CensusRunOutcome.REVOKED)
        // Switch off first, so even a failed disk wipe cannot leave uploads enabled.
        preferences.setCensusUploadEnabled(false)
        credentials.wipe()
        Timber.tag(TAG).w("census revoked")
        return Result.success()
    }

    private fun enrolRejected(record: RunRecord, status: Int): Result {
        record.set(CensusRunOutcome.ENROL_REJECTED, status)
        stats.enrolRejected.incrementAndGet()
        if (enrolRejectedWarned.compareAndSet(false, true)) {
            Timber.tag(TAG).w("census enrol rejected status=%d", status)
        }
        return Result.success()
    }

    private fun failure(record: RunRecord): Result {
        record.set(CensusRunOutcome.FAILURE)
        stats.uploadFailures.incrementAndGet()
        Timber.tag(TAG).i("census upload failures=%d", stats.uploadFailures.get())
        return Result.retry()
    }

    companion object {
        private const val TAG = "Census"
        private const val MAX_SPLIT_DEPTH = 7
        internal val enrolRejectedWarned = AtomicBoolean()
        internal val unauthorizedWarnedFor = AtomicReference<String?>()
        fun schedule(context: Context) = CensusWorkScheduler.schedule(context)
    }
}
