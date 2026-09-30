package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE owner of the wide-event-receipt consent (#1151). Materialized once for the app's lifetime
 * (the [PlatformPreferencesRepository] pattern, #356): the accessibility listener, the Dashboard
 * prompt and the Settings switch all read this one [StateFlow].
 *
 * **Fail-closed decode:** nothing saved, or an unrecognized stored name, reads as
 * [EventReceiptConsent.UNDECIDED] — the filtered footprint. Only an explicit ALLOWED widens.
 *
 * **Value meanings (review NN2):** `null` means ONLY "not read yet". A failed read is retried with
 * bounded backoff ([RETRY_BASE_MS] × attempt, at most [MAX_RETRIES] per failure episode, one ERROR
 * per episode) while the value keeps what it had; when the retries are exhausted the value becomes
 * [EventReceiptConsent.UNDECIDED] — filtered, but USABLE: the prompt shows and the switch works. A
 * successful [set] is observed immediately; writes and the reader are serialized (review OO1).
 */
@Singleton
class EventReceiptPreferencesRepository @Inject constructor(
    private val dataSource: EventReceiptConsentDataSource,
    @param:ApplicationScope private val scope: CoroutineScope,
) : EventReceiptPreferences {

    private val _consent = MutableStateFlow<EventReceiptConsent?>(null)
    override val consent: StateFlow<EventReceiptConsent?> = _consent.asStateFlow()

    /**
     * Review OO1 — writes and the reader are SERIALIZED: [set] holds this lock, cancels AND joins the
     * current reader before writing, publishes the written value, then starts a fresh reader. A
     * reader publishes only while its [writeGeneration] is still current, so no reader can ever
     * publish over a value written after it started.
     */
    private val writeLock = Mutex()

    @Volatile
    private var writeGeneration = 0L

    @Volatile
    private var reader: Job = startReader()

    private fun startReader(): Job {
        val generation = writeGeneration
        return scope.launch {
            readFlow().collect { value ->
                if (writeGeneration == generation) _consent.value = value
            }
        }
    }

    private fun readFlow(): Flow<EventReceiptConsent> {
        var failuresInEpisode = 0
        return dataSource.consent
            .map { decode(it) }
            .onEach { failuresInEpisode = 0 }
            .retryWhen { cause, _ ->
                if (failuresInEpisode == 0) {
                    Timber.tag("Data").e(cause, "event-receipt consent unreadable — retrying")
                }
                failuresInEpisode++
                if (failuresInEpisode > MAX_RETRIES) {
                    false
                } else {
                    delay(RETRY_BASE_MS * failuresInEpisode)
                    true
                }
            }
            .catch {
                // Retries exhausted (already logged). Settle on the usable fail-closed UNDECIDED only
                // when nothing is known yet; a value already read or written this process is the
                // dasher's decision and an unreadable store does not overwrite it (OO1).
                Timber.tag("Data").e("event-receipt consent still unreadable — keeping the known value or UNDECIDED")
                emit(_consent.value ?: EventReceiptConsent.UNDECIDED)
            }
    }

    /**
     * Persist a decision (review NN3): never throws a storage failure — it is logged (ERROR, tag
     * `Data`) and reported as `false`, the value unchanged. On success the value is observed at once
     * (the store now holds exactly [consent]) and a reader that had given up is restarted.
     */
    override suspend fun set(consent: EventReceiptConsent): Boolean = writeLock.withLock {
        reader.cancelAndJoin() // no read is in flight while the store is written (OO1)
        try {
            dataSource.setConsent(consent.name)
        } catch (e: CancellationException) {
            reader = startReader()
            throw e
        } catch (e: Exception) {
            Timber.tag("Data").e(e, "event-receipt consent write failed — decision not saved")
            reader = startReader()
            return@withLock false
        }
        writeGeneration++
        _consent.value = consent
        reader = startReader()
        true
    }

    companion object {
        internal const val MAX_RETRIES = 5
        internal const val RETRY_BASE_MS = 1_000L

        /** Stored name → decision; absent or unknown ⇒ [EventReceiptConsent.UNDECIDED]. */
        internal fun decode(name: String?): EventReceiptConsent =
            EventReceiptConsent.entries.firstOrNull { it.name == name } ?: EventReceiptConsent.UNDECIDED
    }
}
