package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * successful [set] is observed immediately and restarts a reader that had given up.
 */
@Singleton
class EventReceiptPreferencesRepository @Inject constructor(
    private val dataSource: EventReceiptConsentDataSource,
    @ApplicationScope private val scope: CoroutineScope,
) : EventReceiptPreferences {

    private val _consent = MutableStateFlow<EventReceiptConsent?>(null)
    override val consent: StateFlow<EventReceiptConsent?> = _consent.asStateFlow()

    @Volatile
    private var reader: Job = startReader()

    private fun startReader(): Job = scope.launch {
        readFlow().collect { _consent.value = it }
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
                // Retries exhausted (already logged): settle on the usable fail-closed value.
                Timber.tag("Data").e("event-receipt consent still unreadable — treating as undecided")
                emit(EventReceiptConsent.UNDECIDED)
            }
    }

    /**
     * Persist a decision (review NN3): never throws a storage failure — it is logged (ERROR, tag
     * `Data`) and reported as `false`, the value unchanged. On success the value is observed at once
     * (the store now holds exactly [consent]) and a reader that had given up is restarted.
     */
    override suspend fun set(consent: EventReceiptConsent): Boolean {
        try {
            dataSource.setConsent(consent.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("Data").e(e, "event-receipt consent write failed — decision not saved")
            return false
        }
        _consent.value = consent
        if (!reader.isActive) reader = startReader()
        return true
    }

    companion object {
        internal const val MAX_RETRIES = 5
        internal const val RETRY_BASE_MS = 1_000L

        /** Stored name → decision; absent or unknown ⇒ [EventReceiptConsent.UNDECIDED]. */
        internal fun decode(name: String?): EventReceiptConsent =
            EventReceiptConsent.entries.firstOrNull { it.name == name } ?: EventReceiptConsent.UNDECIDED
    }
}
